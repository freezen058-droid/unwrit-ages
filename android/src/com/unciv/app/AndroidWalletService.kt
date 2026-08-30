package com.unciv.app

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.PowerManager
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import com.solana.mobilewalletadapter.clientlib.successPayload
import com.solana.programs.AssociatedTokenProgram
import com.solana.programs.TokenProgram
import com.solana.publickey.ProgramDerivedAddress
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AccountMeta
import com.solana.transaction.Message
import com.solana.transaction.Transaction
import com.solana.transaction.TransactionInstruction
import com.unciv.logic.chain.PlatformWalletService
import com.unciv.utils.Concurrency
import com.unciv.utils.Log
import com.unciv.utils.launchOnGLThread
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Android implementation of [PlatformWalletService] using the Solana Mobile Wallet Adapter (MWA).
 *
 * MWA works by round-tripping to an installed wallet app (Phantom, Solflare, etc) via an
 * intent-based session - there is no in-app private key. [connect] opens that session to get an
 * authorized public key; [recordSaveHash] opens a new session per call to sign+send a small Memo
 * transaction. Both are network + IPC bound, so both run off Concurrency.run() and hop back to
 * the GL thread only to invoke the caller's callback (matches the pattern in AndroidSaverLoader).
 *
 * API shapes below were verified 2026-08-29 against docs.solanamobile.com ("Using Mobile Wallet
 * Adapter", "Example: Sign and send a SOL transfer") and the mobile-wallet-adapter-clientlib-ktx
 * source on GitHub (solana-mobile/mobile-wallet-adapter, android/clientlib-ktx). Not compiled or
 * run against a real device + wallet app in this environment - run
 * `./gradlew :android:assembleDebug` with the Android SDK configured and smoke-test with
 * Phantom/Solflare (devnet) installed before shipping.
 */
class AndroidWalletService(private val activity: Activity) : PlatformWalletService {

    companion object {
        // TODO: switch to "https://api.mainnet-beta.solana.com" (or a paid RPC provider - the
        // public mainnet endpoint rate-limits aggressively) once this fork is out of testing.
        private const val RPC_ENDPOINT = "https://api.devnet.solana.com"
        private const val MEMO_PROGRAM_ID = "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr"
        private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

        // SKR save-recording fee. Mint address confirmed 2026-08-30 against multiple independent
        // sources (Coinbase's official announcement, Solscan, solanamobile.com, OKX, Jupiter) - do
        // not change this without re-verifying via an on-chain explorer, a wrong mint address here
        // would silently send a worthless/wrong token instead of real SKR.
        private const val SKR_MINT = "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3"
        // SKR has 6 decimals (confirmed via Jupiter token API 2026-08-30) - 1 SKR = 1_000_000 base units.
        private const val SKR_DECIMALS: Byte = 6
        private const val SKR_FEE_AMOUNT = 1_000_000L // 1 SKR per recorded save
        // Project treasury wallet, provided directly by the project owner - not a personal wallet.
        private const val TREASURY_ADDRESS = "4FHEBH1tspMLq2oeUMSp88JmkbVyzdG6veVh5FzMJ1v2"
        /** Safety cap for [withWakeLock] - well above any realistic user-response time, just to
         * guarantee the wake lock can never be held indefinitely if something else goes wrong. */
        private const val WAKE_LOCK_TIMEOUT_MS = 3 * 60 * 1000L
    }

    /**
     * Encodes [bytes] as Base58 (Bitcoin/Solana alphabet). Written locally rather than pulled in
     * as a dependency: web3-solana's own [SolanaPublicKey.base58] only accepts exactly 32 bytes
     * (a public key), but a transaction *signature* is 64 bytes, so it can't be reused here - and
     * rather than guess unverified Maven coordinates for a third-party Base58 lib, this is short
     * enough (and the algorithm standard enough) to own directly.
     */
    private fun base58Encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        var zeros = 0
        while (zeros < bytes.size && bytes[zeros] == 0.toByte()) zeros++

        val digits = IntArray(bytes.size * 2)
        var digitLength = 0
        for (i in zeros until bytes.size) {
            var carry = bytes[i].toInt() and 0xFF
            var j = 0
            while (j < digitLength || carry != 0) {
                if (j < digitLength) carry += digits[j] * 256
                digits[j] = carry % 58
                carry /= 58
                j++
            }
            digitLength = j
        }

        val sb = StringBuilder()
        repeat(zeros) { sb.append(BASE58_ALPHABET[0]) }
        for (i in digitLength - 1 downTo 0) sb.append(BASE58_ALPHABET[digits[i]])
        return sb.toString()
    }

    // web3-solana:0.3.0 (the JVM-target artifact this project resolves - it does not publish a
    // distinct Android target) turned out not to contain any RPC client at all when inspected
    // directly (only com.solana.{transaction,publickey,programs,serialization,signer} - no
    // com.solana.rpc/com.solana.networking package, despite an earlier docs-based assumption that
    // it did). Rather than guess at another unverified dependency, this is a minimal hand-rolled
    // JSON-RPC call using the ktor-client bundle this project already depends on (see core's
    // `api(rootProject.libs.bundles.ktor.client)`), following the JSON-RPC shape documented at
    // https://solana.com/docs/rpc/http/getlatestblockhash.
    @Serializable
    private data class CommitmentParam(val commitment: String = "finalized")

    @Serializable
    private data class BlockhashRpcRequest(
        val jsonrpc: String = "2.0",
        val id: Int = 1,
        val method: String = "getLatestBlockhash",
        val params: List<CommitmentParam> = listOf(CommitmentParam())
    )

    @Serializable
    private data class BlockhashValue(val blockhash: String)

    @Serializable
    private data class BlockhashResult(val value: BlockhashValue)

    @Serializable
    private data class RpcError(val message: String? = null)

    @Serializable
    private data class BlockhashRpcResponse(val result: BlockhashResult? = null, val error: RpcError? = null)

    private val httpClient: HttpClient by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    // Solana RPC responses include fields (e.g. "context") our response classes
                    // don't model - only the ones we actually need.
                    ignoreUnknownKeys = true
                    // Without this, kotlinx.serialization omits any field left at its declared
                    // default - and every field in BlockhashRpcRequest uses a default, so the
                    // request body was being serialized as literally "{}", which the RPC server
                    // correctly rejected as "Invalid request".
                    encodeDefaults = true
                })
            }
        }
    }

    private suspend fun fetchLatestBlockhash(): String {
        val response: BlockhashRpcResponse = httpClient.post(RPC_ENDPOINT) {
            contentType(ContentType.Application.Json)
            setBody(BlockhashRpcRequest())
        }.body()
        return response.result?.value?.blockhash
            ?: throw IllegalStateException("Failed to fetch a recent blockhash: ${response.error?.message}")
    }

    @Serializable
    private data class AccountInfoRpcResponse(val result: AccountInfoResult? = null, val error: RpcError? = null)
    @Serializable
    private data class AccountInfoResult(val value: kotlinx.serialization.json.JsonElement? = null)

    /**
     * True if [address] already exists on-chain (getAccountInfo's "value" is non-null when the
     * account exists, null when it doesn't) - used to decide whether the treasury's SKR token
     * account still needs to be created. AssociatedTokenProgram in the pinned web3-solana:0.3.0
     * only exposes a non-idempotent createAssociatedTokenAccount (verified via javap against the
     * actual jar 2026-08-30 - no createIdempotent in this version despite it existing on the
     * library's main branch), so this explicit existence check replaces what createIdempotent
     * would otherwise have handled for free.
     */
    private suspend fun accountExists(address: SolanaPublicKey): Boolean {
        val requestBody = kotlinx.serialization.json.buildJsonObject {
            put("jsonrpc", kotlinx.serialization.json.JsonPrimitive("2.0"))
            put("id", kotlinx.serialization.json.JsonPrimitive(1))
            put("method", kotlinx.serialization.json.JsonPrimitive("getAccountInfo"))
            put("params", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive(address.base58()))
                add(kotlinx.serialization.json.buildJsonObject {
                    put("encoding", kotlinx.serialization.json.JsonPrimitive("base64"))
                })
            })
        }
        val response: AccountInfoRpcResponse = httpClient.post(RPC_ENDPOINT) {
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }.body()
        return response.result?.value != null
    }

    private val walletAdapter: MobileWalletAdapter by lazy {
        MobileWalletAdapter(
            connectionIdentity = ConnectionIdentity(
                // TODO: point this at a real hosted domain for this fork once one exists - MWA
                // wallets show this URI (and can verify it via .well-known/assetlinks-adjacent
                // checks) to the user during the auth prompt.
                identityUri = Uri.parse("https://civilwars.app"),
                iconUri = Uri.parse("favicon.ico"),
                identityName = "CivilWars"
            )
        )
    }

    private val memoProgramId = SolanaPublicKey.from(MEMO_PROGRAM_ID)

    @Volatile
    private var authToken: String? = null

    @Volatile
    private var _connectedAddress: String? = null

    override val isAvailable: Boolean = true

    override val connectedAddress: String?
        get() = _connectedAddress

    /**
     * Runs [block] on a real [androidx.activity.ComponentActivity] via [WalletBridgeActivity],
     * since MWA's [ActivityResultSender] requires one and [activity] (AndroidLauncher, extending
     * libGDX's AndroidApplication) is a plain android.app.Activity - see WalletBridgeActivity.kt.
     */
    private suspend fun <T> withBridge(block: suspend (ActivityResultSender) -> T): T {
        val deferred = CompletableDeferred<T>()
        WalletBridgeActivity.start(activity) { sender ->
            try {
                deferred.complete(block(sender))
            } catch (ex: Exception) {
                deferred.completeExceptionally(ex)
            }
        }
        return deferred.await()
    }

    /**
     * Holds a PARTIAL_WAKE_LOCK for the duration of [block]. A pending MWA round-trip (waiting on
     * the wallet app to show its UI and for the user to respond) was observed getting silently cut
     * off with a JobCancellationException after the device went idle and Android's cached-app
     * freezer suspended this process - confirmed via `adb logcat` showing
     * "ActivityManager: freezing <pid> com.civilwars.app" right before the cancellation. A held
     * wake lock keeps this process out of that freezer for as long as the lock is held. Capped at
     * [WAKE_LOCK_TIMEOUT_MS] as a safety net so a stuck call can never hold it forever.
     */
    private suspend fun <T> withWakeLock(block: suspend () -> T): T {
        val powerManager = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CivilWars:WalletOperation")
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        try {
            return block()
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    override fun connect(onConnected: (address: String) -> Unit, onError: (Exception) -> Unit) {
        Concurrency.run("WalletConnect") {
            try {
                var connectedKey: SolanaPublicKey? = null
                val result = withWakeLock {
                    withBridge { sender ->
                        walletAdapter.transact(sender) { authResult ->
                            authToken = authResult.authToken
                            connectedKey = SolanaPublicKey(authResult.accounts.first().publicKey)
                        }
                    }
                }

                when (result) {
                    is TransactionResult.Success -> {
                        val key = connectedKey
                        if (key == null) {
                            Log.error("Wallet connect: transact() succeeded but no account was returned")
                            launchOnGLThread { onError(IllegalStateException("Wallet did not return an account")) }
                        } else {
                            val address = key.base58()
                            _connectedAddress = address
                            Log.debug("Wallet connect: succeeded, address=%s", address)
                            launchOnGLThread { onConnected(address) }
                        }
                    }
                    is TransactionResult.NoWalletFound -> {
                        Log.error("Wallet connect: no MWA-compatible wallet app found")
                        launchOnGLThread {
                            onError(Exception("No MWA-compatible wallet app found - install Phantom or Solflare"))
                        }
                    }
                    is TransactionResult.Failure -> {
                        Log.error("Wallet connect: transact() returned Failure", result.e)
                        launchOnGLThread { onError(result.e) }
                    }
                }
            } catch (ex: Exception) {
                Log.error("Wallet connect failed", ex)
                launchOnGLThread { onError(ex) }
            }
        }
    }

    override fun disconnect() {
        // Purely local: just forget the token/address so this app stops trying to use them.
        // Deliberately does NOT call the wallet's deauthorize() - that requires its own
        // WalletBridgeActivity/MWA round-trip, and WalletBridgeActivity only supports one
        // operation in flight at a time (single companion-object pendingOperation slot). Firing
        // it in the background here raced with a subsequent connect() (user tapping Connect right
        // after Disconnect), and the wallet rejected the second, overlapping authorization request
        // ("-1/authorization request failed"). The wallet keeping a stale authToken around after a
        // local disconnect is harmless - it's simply never presented again from this side.
        authToken = null
        _connectedAddress = null
    }

    override fun recordSaveHash(
        gameId: String,
        saveName: String,
        hashHex: String,
        onSuccess: (txSignature: String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val token = authToken
        if (token == null) {
            onError(IllegalStateException("No wallet connected"))
            return
        }

        Concurrency.run("WalletRecordSaveHash") {
            try {
                // Keep the memo short and well within Solana's ~1232 byte tx size limit -
                // gameId is a UUID (36 chars), hashHex a SHA-256 hex digest (64 chars), saveName
                // capped so a long player-chosen name can't blow the budget, and any ':' in the
                // name is stripped so it can't be confused with our own field delimiter.
                val safeSaveName = saveName.replace(":", "").take(64)
                val memoText = "unciv-save:$gameId:$safeSaveName:$hashHex"

                val blockhash = fetchLatestBlockhash()

                val ownerKey = SolanaPublicKey.from(
                    _connectedAddress ?: throw IllegalStateException("No wallet connected")
                )

                val memoInstruction = TransactionInstruction(
                    memoProgramId,
                    listOf(AccountMeta(ownerKey, isSigner = true, isWritable = true)),
                    memoText.encodeToByteArray()
                )

                // SKR fee: player pays SKR_FEE_AMOUNT (raw units, SKR_DECIMALS decimals) to the
                // project treasury as part of the same signed transaction. Associated Token
                // Account addresses are PDAs derived from [owner, TOKEN_PROGRAM_ID, mint] under
                // the Associated Token Program - verified against solana-program.com's ATA docs
                // 2026-08-30, do not change the seed order.
                val skrMint = SolanaPublicKey.from(SKR_MINT)
                val treasuryKey = SolanaPublicKey.from(TREASURY_ADDRESS)

                suspend fun deriveAta(owner: SolanaPublicKey): SolanaPublicKey {
                    val pda = ProgramDerivedAddress.find(
                        listOf(owner.bytes, TokenProgram.PROGRAM_ID.bytes, skrMint.bytes),
                        AssociatedTokenProgram.PROGRAM_ID
                    ).getOrThrow()
                    return SolanaPublicKey(pda.bytes)
                }

                val playerSkrAta = deriveAta(ownerKey)
                val treasurySkrAta = deriveAta(treasuryKey)

                // web3-solana:0.3.0 (the version this project is pinned to - verified via javap
                // against the actual jar, not just docs) has no idempotent "create if missing"
                // helper, only a plain createAssociatedTokenAccount that errors if the account
                // already exists. So: check first, only add the create instruction the very first
                // time anyone ever pays the fee. Player is the fee-payer for this one-time setup,
                // same as they're the fee-payer for the transaction itself.
                val messageBuilder = Message.Builder()
                if (!accountExists(treasurySkrAta)) {
                    messageBuilder.addInstruction(
                        AssociatedTokenProgram.createAssociatedTokenAccount(
                            mint = skrMint,
                            associatedAccount = treasurySkrAta,
                            owner = treasuryKey,
                            payer = ownerKey
                        )
                    )
                }

                // transferChecked (not plain transfer) so the mint+decimals are validated by the
                // token program itself, not just trusted from our own PDA derivation.
                val feeTransferInstruction = TokenProgram.transferChecked(
                    from = playerSkrAta,
                    to = treasurySkrAta,
                    amount = SKR_FEE_AMOUNT,
                    decimals = SKR_DECIMALS,
                    owner = ownerKey,
                    mint = skrMint
                )

                val message = messageBuilder
                    .addInstruction(feeTransferInstruction)
                    .addInstruction(memoInstruction)
                    .setRecentBlockhash(blockhash)
                    .build()
                val unsignedTx = Transaction(message)

                // signAndSendTransactions(transactions: Array<ByteArray>): SignAndSendTransactionsResult,
                // and result.successPayload?.signatures?.first() is a raw ByteArray signature -
                // both verified against docs.solanamobile.com's "Sign and send a SOL transfer"
                // example and AdapterOperations.kt. NOT a base58 String, and NOT a 32-byte
                // SolanaPublicKey (that's for public keys, not the 64-byte signature) - encode
                // with our own base58Encode() instead.
                val result = withWakeLock {
                    withBridge { sender ->
                        walletAdapter.transact(sender) {
                            signAndSendTransactions(arrayOf(unsignedTx.serialize()))
                        }
                    }
                }

                when (result) {
                    is TransactionResult.Success -> {
                        val sigBytes = result.successPayload?.signatures?.firstOrNull()
                        if (sigBytes == null) launchOnGLThread { onError(IllegalStateException("Wallet did not return a transaction signature")) }
                        else launchOnGLThread { onSuccess(base58Encode(sigBytes)) }
                    }
                    is TransactionResult.NoWalletFound -> launchOnGLThread {
                        onError(Exception("No MWA-compatible wallet app found"))
                    }
                    is TransactionResult.Failure -> launchOnGLThread {
                        onError(result.e)
                    }
                }
            } catch (ex: Exception) {
                Log.error("Failed to record save hash on-chain", ex)
                launchOnGLThread { onError(ex) }
            }
        }
    }
}
