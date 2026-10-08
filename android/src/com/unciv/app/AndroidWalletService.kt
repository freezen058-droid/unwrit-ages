package com.unciv.app

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.util.Base64
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.RpcCluster
import com.solana.mobilewalletadapter.clientlib.Solana
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import com.solana.mobilewalletadapter.clientlib.successPayload
import com.solana.programs.AssociatedTokenProgram
import com.solana.programs.SystemProgram
import com.solana.programs.TokenProgram
import com.solana.publickey.ProgramDerivedAddress
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AccountMeta
import com.solana.transaction.Instruction
import com.solana.transaction.LegacyMessage
import com.solana.transaction.Message
import com.solana.transaction.Transaction
import com.solana.transaction.TransactionInstruction
import com.unciv.logic.chain.Ans104
import com.unciv.logic.chain.CertificateEmblem
import com.unciv.logic.chain.CertificatePayment
import com.unciv.logic.chain.CloudSave
import com.unciv.logic.chain.PlatformWalletService
import com.unciv.logic.chain.StartAnchor
import com.unciv.logic.chain.SavePaymentJournal
import com.unciv.logic.chain.SavePaymentPendingException
import com.unciv.logic.chain.VictoryCertificate
import com.unciv.utils.Concurrency
import com.unciv.utils.Log
import com.unciv.utils.launchOnGLThread
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom

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
        private val savePaymentMutex = kotlinx.coroutines.sync.Mutex()
        // Mainnet. MobileWalletAdapter's own `blockchain`/`rpcCluster` (which cluster the wallet
        // app authorizes and signs against) are a separate setting from RPC_ENDPOINT and default to
        // devnet whatever RPC_ENDPOINT says - the `walletAdapter` property below sets both from
        // IS_MAINNET so they cannot drift apart. The 1.0.0 release candidate shipped with this
        // still false: certificates minted on devnet, the fee went nowhere, and the SKR save
        // record could not work at all (SKR has no devnet mint).
        private const val IS_MAINNET = true
        // Not a provider URL: the site's /rpc function forwards to Helius with the API key held as
        // a Pages secret, so no key is inside the APK (see functions/rpc.js). The public mainnet
        // endpoint rate-limits hard and is not meant for production traffic.
        private val RPC_ENDPOINT = if (IS_MAINNET) "https://unwritages.pages.dev/rpc" else "https://api.devnet.solana.com"
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

        // Metaplex Core, the program the victory certificate is minted under. Same address on
        // mainnet and devnet. This and the CreateV1 account list/data layout below were captured by
        // building the instruction with @metaplex-foundation/mpl-core@1.10.0 and printing it rather
        // than transcribed from prose docs - see pic/batch_review/_sd/chaincost/CORE-CREATE.md.
        private const val MPL_CORE_PROGRAM_ID = "CoREENxT6tW1HoK8ypY1SxRMZTcVPm7R94rH4PZNhX7d"
        private const val SYSTEM_PROGRAM_ID = "11111111111111111111111111111111"

        // The certificate's service fee, which funds further development. Denominated in US cents
        // and converted to lamports at mint time (see [certificateFeeLamports]) so the price the
        // player sees does not drift with SOL. Charged in SOL rather than SKR because a mint
        // already requires SOL for the asset's rent - a SOL fee needs no second token and no extra
        // token account, just one more transfer in the same transaction.
        private const val CERTIFICATE_FEE_USD_CENTS = 90L  // US$0.75 until 09-25

        // Pyth's SOL/USD "push oracle" price account. Verified live 2026-09-21 against BOTH
        // clusters - same address, same account, owner `rec5EK...`, feed id matching Pyth's
        // published SOL/USD id, price 117.57 (mainnet) / 118.03 (devnet), 30 s and 240 s old. The
        // legacy pyth-client v2 account (H6ARHf...) was checked first and rejected: it still
        // exists with a valid magic number but no longer carries a price, which is exactly the
        // kind of source that would silently mis-price the fee.
        private const val PYTH_SOL_USD_ACCOUNT = "7UVimffxr9ow1uXYxsr4LHAcV58mLzhmwaeKvJ1pjLiE"
        private const val PYTH_RECEIVER_PROGRAM_ID = "rec5EKMGg6MxZYaMdyBfgwp4d5rB9T1VQH5pJv5LtFJ"
        private const val PYTH_SOL_USD_FEED_ID = "ef0d8b6fda2ceba41da15d4095d1da392a0d2f8ed0c6c7bc0f4cfac8c280b56d"
        // PriceUpdateV2, byte offsets read off the real accounts (see above): 8 anchor
        // discriminator, 32 write authority, 1 verification level, 32 feed id, then the message.
        private const val PYTH_FEED_ID_OFFSET = 41
        private const val PYTH_PRICE_OFFSET = 73
        private const val PYTH_EXPO_OFFSET = 89
        private const val PYTH_PUBLISH_TIME_OFFSET = 93
        private const val PYTH_ACCOUNT_MIN_BYTES = 101
        /** Both clusters published within 4 minutes when measured; 10 gives room without ever
         *  letting a long-dead feed price a charge to a player. */
        private const val PYTH_MAX_PRICE_AGE_SECONDS = 600L
        /** Used only when the feed cannot be read or is stale. Measured 2026-09-21. */
        private const val FALLBACK_SOL_USD_CENTS = 11757L
        // Hard bounds on what a player can ever be charged, in case the feed returns something
        // absurd. They are the fee above at SOL = $500 and SOL = $30. Below $30 the ceiling
        // charges less than the nominal fee; above $500 the floor charges somewhat more, which
        // is the price of never letting a broken feed push the charge towards nothing.
        private const val CERTIFICATE_FEE_MIN_LAMPORTS = 1_800_000L
        private const val CERTIFICATE_FEE_MAX_LAMPORTS = 30_000_000L

        // Paying the certificate fee in SKR (user, 09-30). There is no Pyth price account for SKR
        // on-chain - the push-oracle PDA for Pyth's SKR/USD feed (38846e...) does not exist, checked
        // 2026-09-30 - and Pyth's Hermes API now answers 401 without a key. So the price comes from
        // Jupiter's price API, or CoinGecko's when Jupiter does not answer; both returned US$0.0182
        // on 09-30. Unlike SOL there is no fallback price: a wrong SKR price charges the wrong
        // amount, and the player can always pay in SOL instead.
        private const val SKR_PRICE_JUPITER = "https://lite-api.jup.ag/price/v3?ids=$SKR_MINT"
        private const val SKR_PRICE_COINGECKO = "https://api.coingecko.com/api/v3/simple/price?ids=seeker&vs_currencies=usd"
        /** A price outside this band is treated as broken: the fee would be 900 SKR or 0.9 SKR. */
        private const val SKR_USD_MIN = 0.001

        /** Every gallery tip carries this account (CloudSave.TIP_PREFIX). Base58 of
         *  SHA-256("unwritages:gallery-tips:v1"): an address nobody holds a key for. */
        private const val TIP_REFERENCE = "44BCs5bxqSWJ4pw12hJUBibeLtbA34cvKztYCpCq68Q9"

        /** Seeker Genesis Token (docs.solanamobile.com, "Seeker Genesis Token"): a Token-2022 mint
         *  whose MetadataPointer and TokenGroupMember both name this group. Checked 10-01 against two
         *  real SGTs (member numbers 113174, 113703): mint authority GT2zuHVa..., these extensions. */
        private const val SGT_GROUP = "GT22s89nU4iWFkNXj1Bw6uYhJJWDRPpShHt4Bk8f99Te"
        private const val TOKEN_2022_PROGRAM = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
        private const val SKR_USD_MAX = 1.0

        // A Solana transaction has to fit in 1232 bytes and the certificate name is player-derived
        // (civ name + victory type + turn), so it gets a bound. Everything else in this transaction
        // is fixed-size, so this is the only part that could push it over.
        private const val CERTIFICATE_NAME_MAX_CHARS = 96

        // Each certificate's picture and metadata are uploaded to Arweave before the mint, and the
        // asset's uri is the metadata's address on Turbo's own gateway. The 1.0.0 candidate put the
        // metadata inline as a `data:` URI instead, to avoid the upload - and wallets fetch the uri
        // over HTTPS, so the Seeker wallet showed that certificate with no picture at all.
        //
        // Not arweave.net: on 09-24 it answered 404 for ten minutes after the mint, while Turbo's
        // gateway served the same items at once - and the wallet's indexer read the uri in those
        // ten minutes and kept the empty result. The data is the same permanent Arweave item either
        // way; any gateway serves it by id, and the metadata lists the arweave.net address too.
        private const val ARWEAVE_GATEWAY = "https://turbo-gateway.com/"
        /** https://solana.com/docs/core/transactions - the whole signed transaction, not the message. */
        private const val TRANSACTION_SIZE_LIMIT = 1232
        private const val TURBO_UPLOAD_ENDPOINT = "https://upload.ardrive.io/v1/tx"
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
    private data class TurboUploadResponse(val id: String = "")

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
    private data class BlockhashValue(val blockhash: String, val lastValidBlockHeight: Long = 0)

    @Serializable
    private data class BlockhashResult(val value: BlockhashValue)

    @Serializable
    private data class RpcError(val message: String? = null)

    @Serializable
    private data class BlockhashRpcResponse(val result: BlockhashResult? = null, val error: RpcError? = null)

    private val httpClient: HttpClient by lazy {
        // Android-aware certificate validation is required with domain-specific network security.
        HttpClient(OkHttp) {
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

    private suspend fun fetchLatestBlockhash(): String = fetchBlockhashLease().blockhash

    private suspend fun fetchBlockhashLease(): BlockhashValue {
        val response: BlockhashRpcResponse = httpClient.post(RPC_ENDPOINT) {
            contentType(ContentType.Application.Json)
            setBody(BlockhashRpcRequest())
        }.body()
        return response.result?.value
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

    @Serializable
    private data class AccountInfoOwnerAndData(
        val owner: String? = null,
        val data: List<String> = emptyList()
    )
    @Serializable
    private data class AccountDataRpcResponse(val result: AccountDataResult? = null, val error: RpcError? = null)
    @Serializable
    private data class AccountDataResult(val value: AccountInfoOwnerAndData? = null)

    /** The account's owner program and raw data, or null if the account does not exist. An RPC
     *  error throws: read as "no account", it made an SKR balance of 54 look like 0 (09-30). */
    private suspend fun fetchAccount(address: String): Pair<String, ByteArray>? {
        val requestBody = kotlinx.serialization.json.buildJsonObject {
            put("jsonrpc", kotlinx.serialization.json.JsonPrimitive("2.0"))
            put("id", kotlinx.serialization.json.JsonPrimitive(1))
            put("method", kotlinx.serialization.json.JsonPrimitive("getAccountInfo"))
            put("params", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive(address))
                add(kotlinx.serialization.json.buildJsonObject {
                    put("encoding", kotlinx.serialization.json.JsonPrimitive("base64"))
                })
            })
        }
        val response: AccountDataRpcResponse = httpClient.post(RPC_ENDPOINT) {
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }.body()
        response.error?.let { throw IllegalStateException("RPC getAccountInfo: ${it.message}") }
        val value = response.result?.value ?: return null
        val owner = value.owner ?: return null
        val encoded = value.data.firstOrNull() ?: return null
        return owner to Base64.decode(encoded, Base64.DEFAULT)
    }

    /**
     * What [CERTIFICATE_FEE_USD_CENTS] is worth in lamports right now.
     *
     * Everything the answer depends on is checked before it is used - the account's owning
     * program, the feed id, the publish time, and the sign of the price - because the failure this
     * guards against is not an exception but a *wrong number*, which would come out as a player
     * being charged the wrong amount with nothing visibly broken. Any check failing falls back to
     * [FALLBACK_SOL_USD_CENTS] rather than aborting: a price feed being unreachable is not a good
     * reason to refuse someone their victory certificate.
     */
    private suspend fun certificateFeeLamports(): Long {
        val solUsdCents = try {
            val account = fetchAccount(PYTH_SOL_USD_ACCOUNT)
                ?: throw IllegalStateException("price account not found")
            val (owner, data) = account
            check(owner == PYTH_RECEIVER_PROGRAM_ID) { "price account owned by $owner" }
            check(data.size >= PYTH_ACCOUNT_MIN_BYTES) { "price account is ${data.size} bytes" }

            val feedId = data.copyOfRange(PYTH_FEED_ID_OFFSET, PYTH_FEED_ID_OFFSET + 32)
                .joinToString("") { "%02x".format(it) }
            check(feedId == PYTH_SOL_USD_FEED_ID) { "price account carries feed $feedId" }

            val price = readLongLE(data, PYTH_PRICE_OFFSET)
            val expo = readIntLE(data, PYTH_EXPO_OFFSET)
            val publishTime = readLongLE(data, PYTH_PUBLISH_TIME_OFFSET)
            val age = System.currentTimeMillis() / 1000 - publishTime
            check(age in -PYTH_MAX_PRICE_AGE_SECONDS..PYTH_MAX_PRICE_AGE_SECONDS) { "price is $age s old" }
            check(price > 0) { "price is $price" }

            // price * 10^expo is dollars, so * 100 is cents. expo is negative (-8 when measured).
            val cents = price * 100.0 * Math.pow(10.0, expo.toDouble())
            check(cents > 0 && cents.isFinite()) { "price works out to $cents cents" }
            Log.debug("Certificate fee: SOL/USD %.4f from Pyth, %d s old", cents / 100, age)
            cents
        } catch (ex: Exception) {
            Log.error("Certificate fee: could not read the SOL/USD feed, using the fallback price", ex)
            FALLBACK_SOL_USD_CENTS.toDouble()
        }

        val lamports = (CERTIFICATE_FEE_USD_CENTS * 1_000_000_000.0 / solUsdCents).toLong()
        return lamports.coerceIn(CERTIFICATE_FEE_MIN_LAMPORTS, CERTIFICATE_FEE_MAX_LAMPORTS)
    }

    /**
     * How many bytes this message will occupy on the wire once signed.
     *
     * A legacy transaction is `compact-u16(signatureCount) | signatures | message`, and with fewer
     * than 128 signers the length prefix is a single byte - the same layout [withAssetSignature]
     * relies on.
     */
    private fun transactionSize(message: Message) =
        1 + message.signatureCount.toInt() * Transaction.SIGNATURE_LENGTH_BYTES + message.serialize().size

    private fun readLongLE(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 7 downTo 0) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return value
    }

    private fun readIntLE(bytes: ByteArray, offset: Int): Int {
        var value = 0
        for (i in 3 downTo 0) value = (value shl 8) or (bytes[offset + i].toInt() and 0xFF)
        return value
    }

    @Serializable
    private data class SendTransactionRpcResponse(val result: String? = null, val error: RpcError? = null)

    /**
     * Broadcasts an already fully-signed transaction and returns its base58 signature.
     *
     * [recordSaveHash] never needs this because MWA's signAndSendTransactions has the wallet
     * broadcast; the certificate mint signs in two places (see [mintVictoryCertificate]) so it asks
     * the wallet for a signature only and does the sending itself. Preflight is deliberately left
     * on: a malformed instruction then comes back as a readable simulation error in [RpcError]
     * rather than as a transaction that is accepted and silently never lands. JSON-RPC shape per
     * https://solana.com/docs/rpc/http/sendtransaction.
     */
    /**
     * Stores [data] on Arweave through Turbo and returns its address on Turbo's gateway, which
     * serves an upload as soon as it is accepted (see [ARWEAVE_GATEWAY]).
     *
     * Signed with a key made for this one upload: Turbo stores items up to 100 KiB without charge,
     * so the key needs no funds and the player is not asked to sign anything for it. The id Turbo
     * reports must be the one computed from our own signature - anything else is not our upload.
     */
    private suspend fun uploadToArweave(data: ByteArray, contentType: String): String {
        val key = AssetKeypair()
        val item = Ans104.create(
            data,
            listOf(Ans104.Tag("Content-Type", contentType), Ans104.Tag("App-Name", "Unwrit Ages")),
            key.publicKeyBytes, key::sign
        )
        val response: TurboUploadResponse = httpClient.post(TURBO_UPLOAD_ENDPOINT) {
            contentType(ContentType.Application.OctetStream)
            setBody(item.raw)
        }.body()
        check(response.id == item.id) { "The storage service returned an unexpected id (${response.id})" }
        return ARWEAVE_GATEWAY + item.id
    }

    private suspend fun sendSignedTransaction(signedTransaction: ByteArray): String {
        val requestBody = kotlinx.serialization.json.buildJsonObject {
            put("jsonrpc", kotlinx.serialization.json.JsonPrimitive("2.0"))
            put("id", kotlinx.serialization.json.JsonPrimitive(1))
            put("method", kotlinx.serialization.json.JsonPrimitive("sendTransaction"))
            put("params", kotlinx.serialization.json.buildJsonArray {
                // NO_WRAP: android.util.Base64 inserts newlines by default, which the RPC server
                // rejects as an invalid base64 payload.
                add(kotlinx.serialization.json.JsonPrimitive(Base64.encodeToString(signedTransaction, Base64.NO_WRAP)))
                add(kotlinx.serialization.json.buildJsonObject {
                    put("encoding", kotlinx.serialization.json.JsonPrimitive("base64"))
                    put("preflightCommitment", kotlinx.serialization.json.JsonPrimitive("confirmed"))
                })
            })
        }
        val response: SendTransactionRpcResponse = httpClient.post(RPC_ENDPOINT) {
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }.body()
        val reason = response.error?.message ?: "no reason given"
        // Seen 2026-09-24 with the wallet still on devnet; a blockhash that expired while the player
        // was approving reads the same. Either way the transaction never ran, so nothing is charged.
        val hint = if ("Blockhash not found" in reason)
            " - check that your wallet is on Mainnet (nothing was charged)"
        else ""
        return response.result
            ?: throw IllegalStateException("The network rejected the certificate mint: $reason$hint")
    }

    private val walletAdapter: MobileWalletAdapter by lazy {
        MobileWalletAdapter(
            connectionIdentity = ConnectionIdentity(
                // MWA wallets show this URI to the player during the auth prompt, so it has to
                // resolve and has to match the name beside it - the app has been Unwrit Ages since
                // round 169. Verified live 2026-09-22: the site serves the landing page, the
                // privacy policy and the licence notice, so a player who taps it lands somewhere
                // real. It is a pages.dev subdomain rather than a registered domain, which is a
                // deliberate cost decision, not an oversight - moving to one later means changing
                // this and iconUri and shipping a version, so it is a release-time change.
                identityUri = Uri.parse("https://unwritages.pages.dev"),
                // RELATIVE, resolved by the wallet against identityUri. The comment that used to
                // sit here said the opposite - that it had to be absolute - and the code followed
                // it, so MobileWalletAdapter rejected every connection attempt with "IF non-null,
                // iconRelativeUri must be a relative Uri" and the wallet could not be connected at
                // all. Nobody caught it because connecting a wallet on a real device was still an
                // unticked line in the ship plan. Resolves to
                // https://unwritages.pages.dev/icon.png, which the site serves.
                iconUri = Uri.parse("icon.png"),
                identityName = "Unwrit Ages"
            )
        ).apply {
            // Tied to IS_MAINNET (see its comment above) so this can never drift out of sync with
            // RPC_ENDPOINT - both must flip together for a mainnet cutover to actually work.
            blockchain = if (IS_MAINNET) Solana.Mainnet else Solana.Devnet
            rpcCluster = if (IS_MAINNET) RpcCluster.MainnetBeta else RpcCluster.Devnet
        }
    }

    private val memoProgramId = SolanaPublicKey.from(MEMO_PROGRAM_ID)

    @Volatile
    private var authToken: String? = null

    @Volatile
    private var _connectedAddress: String? = null

    override val isAvailable: Boolean = true

    override val certificateFeeUsdCents = CERTIFICATE_FEE_USD_CENTS.toInt()

    override fun renderCertificate(inscription: List<VictoryCertificate.InscriptionLine>, emblem: CertificateEmblem, anchored: Boolean) =
        CertificateImage(activity.assets).render(inscription, emblem, anchored)

    override fun explorerUrl(address: String) =
        "https://explorer.solana.com/address/$address" + (if (IS_MAINNET) "" else "?cluster=devnet")

    override val connectedAddress: String?
        get() = _connectedAddress

    /**
     * Runs [block] on a real [androidx.activity.ComponentActivity] via [WalletBridgeActivity],
     * since MWA's [ActivityResultSender] requires one and [activity] (AndroidLauncher, extending
     * libGDX's AndroidApplication) is a plain android.app.Activity - see WalletBridgeActivity.kt.
     */
    private suspend fun <T> withBridge(block: suspend (ActivityResultSender) -> T): T {
        val deferred = CompletableDeferred<T>()
        val started = WalletBridgeActivity.start(activity) { sender ->
            try {
                deferred.complete(block(sender))
            } catch (ex: Exception) {
                deferred.completeExceptionally(ex)
            }
        }
        // A security audit found that ignoring this return value let a second concurrent call
        // (e.g. a double-tapped Save button) silently clobber a first one still in flight - that
        // first call's deferred then never completed, hanging forever with its wake lock held
        // until the OS's own ~3 minute timeout, with no error ever surfaced to the player. Failing
        // fast here instead turns that into an immediate, clear error.
        if (!started) throw IllegalStateException("Another wallet operation is already in progress - please wait for it to finish")
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
        // The adapter keeps its own copy of the token and reauthorizes with it, so Connect went
        // straight back to the same account and a player could not switch (BUGS #20, 10-01).
        // Forgetting it here makes the next Connect a fresh authorization - the wallet asks which
        // account - without the overlapping deauthorize round-trip described above.
        walletAdapter.authToken = null
    }

    /**
     * Mints a Metaplex Core asset (a "Core NFT") owned by the connected wallet.
     *
     * Two things make this harder than [recordSaveHash], and both are handled below:
     *
     *  1. **The asset account has to sign.** CreateV1 takes the new asset's address as a *signer*,
     *     and that address is a keypair this client invents on the spot - the wallet has never seen
     *     it and cannot sign for it. So the transaction carries two signatures: ours for the asset
     *     and the wallet's for the payer. See [buildLegacyMessage] for how the two are ordered.
     *  2. **The fee payer has to be account 0.** Solana defines the fee payer as the first account
     *     key, and CreateV1 lists the asset before the payer, so the message's account list cannot
     *     simply follow the instruction's. [buildLegacyMessage] pins the payer first.
     *
     * Before any of that, the certificate is drawn ([CertificateImage]) and it and its metadata are
     * stored on Arweave ([uploadToArweave]); the metadata URI goes back through [onUploaded] at
     * once, so a retry after a failed mint arrives with [alreadyUploadedMetadataUri] and skips it.
     */
    override fun mintVictoryCertificate(
        certificateName: String,
        inscription: List<VictoryCertificate.InscriptionLine>,
        emblem: CertificateEmblem,
        anchored: Boolean,
        alreadyUploadedMetadataUri: String?,
        payment: CertificatePayment,
        buildMetadata: (imageUri: String) -> String,
        onUploaded: (metadataUri: String) -> Unit,
        onProgress: (String) -> Unit,
        onSuccess: (assetAddress: String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val payerAddress = _connectedAddress
        if (payerAddress == null) {
            onError(IllegalStateException("No wallet connected - connect a wallet before minting a certificate"))
            return
        }

        Concurrency.run("WalletMintCertificate") {
            try {
                val payer = SolanaPublicKey.from(payerAddress)
                // The fee first: a missing SKR price or an empty SKR balance is said before
                // anything is drawn or uploaded, while the player can still pick SOL instead.
                val feeInstructions = when (payment) {
                    CertificatePayment.SOL -> {
                        val feeLamports = certificateFeeLamports()
                        Log.debug("Victory certificate: fee %d lamports (%d US cents)", feeLamports, CERTIFICATE_FEE_USD_CENTS)
                        listOf(SystemProgram.transfer(payer, SolanaPublicKey.from(TREASURY_ADDRESS), feeLamports))
                    }
                    CertificatePayment.SKR -> skrFeeInstructions(payer, certificateFeeSkr())
                }

                // The picture and the metadata go to Arweave first, and the metadata's URI is handed
                // back at once: a mint that fails after this point retries against it for free.
                val metadataUri = alreadyUploadedMetadataUri ?: run {
                    launchOnGLThread { onProgress("Carving the certificate...") }
                    val jpeg = CertificateImage(activity.assets).render(inscription, emblem, anchored)
                    Log.debug("Victory certificate: picture %d bytes", jpeg.size)
                    launchOnGLThread { onProgress("Storing the certificate...") }
                    val imageUri = uploadToArweave(jpeg, "image/jpeg")
                    val json = buildMetadata(imageUri).toByteArray(Charsets.UTF_8)
                    Log.debug("Victory certificate: metadata %d bytes", json.size)
                    uploadToArweave(json, "application/json").also { uri -> launchOnGLThread { onUploaded(uri) } }
                }
                Log.debug("Victory certificate: metadata at %s", metadataUri)

                launchOnGLThread { onProgress("Preparing certificate...") }

                val assetKeypair = AssetKeypair()
                val assetAddress = assetKeypair.publicKey.base58()

                // Both in the same transaction, so the fee and the certificate succeed or fail
                // together - there is no state in which a player has paid and holds nothing.

                val blockhash = fetchLatestBlockhash()
                val name = certificateName.take(CERTIFICATE_NAME_MAX_CHARS)
                val message = buildLegacyMessage(
                    feePayer = payer,
                    instructions = listOf(
                        createV1Instruction(asset = assetKeypair.publicKey, payer = payer, name = name, uri = metadataUri)
                    ) + feeInstructions,
                    blockhash = blockhash
                )
                check(transactionSize(message) <= TRANSACTION_SIZE_LIMIT) {
                    "The certificate does not fit in one transaction (${transactionSize(message)} of $TRANSACTION_SIZE_LIMIT bytes)"
                }
                Log.debug("Victory certificate: transaction %d bytes", transactionSize(message))
                val messageBytes = message.serialize()

                // A transaction's signature array is positional: slot i belongs to account key i,
                // for the first `signatureCount` keys. So the only thing deciding "which signature
                // goes where" is the message's own account ordering - read the answer out of the
                // built message rather than assuming it, and fail loudly if it is not what this
                // code is built on (payer at 0 because it is the fee payer, asset somewhere else in
                // the signer range because CreateV1 marks it a signer too).
                val signerCount = message.signatureCount.toInt()
                val signerKeys = message.accounts.take(signerCount)
                val payerIndex = signerKeys.indexOfFirst { it.base58() == payerAddress }
                val assetIndex = signerKeys.indexOfFirst { it.base58() == assetAddress }
                check(payerIndex == 0 && assetIndex > 0) {
                    "Certificate signer order is wrong: payer at $payerIndex, asset at $assetIndex of $signerCount"
                }

                // Sign for the asset first, then hand the partially-signed transaction to the
                // wallet. Both signatures are over the exact same bytes - the serialized *message*,
                // not the transaction - which is why ours can be produced before the wallet's.
                val assetSignature = assetKeypair.sign(messageBytes)
                val signatures = MutableList(signerCount) { ByteArray(Transaction.SIGNATURE_LENGTH_BYTES) }
                signatures[assetIndex] = assetSignature
                val partiallySigned = Transaction(signatures, message).serialize()

                launchOnGLThread { onProgress("Waiting for wallet approval...") }

                // signTransactions, not signAndSendTransactions: the wallet is only being asked for
                // the payer's signature, and having the signed bytes back in hand lets this code
                // re-assert the asset signature (see withAssetSignature) instead of trusting every
                // wallet app to preserve a signature it did not produce. Sending is then ours.
                val result = withWakeLock {
                    withBridge { sender ->
                        walletAdapter.transact(sender) {
                            signTransactions(arrayOf(partiallySigned))
                        }
                    }
                }

                when (result) {
                    is TransactionResult.Success -> {
                        val signed = result.successPayload?.signedPayloads?.firstOrNull()
                        if (signed == null) {
                            launchOnGLThread { onError(IllegalStateException("The wallet returned no signed transaction")) }
                        } else {
                            val fullySigned = withAssetSignature(signed, messageBytes, signerCount, assetIndex, assetSignature)
                            launchOnGLThread { onProgress("Minting certificate...") }
                            val txSignature = sendSignedTransaction(fullySigned)
                            Log.debug("Victory certificate minted: asset=%s tx=%s", assetAddress, txSignature)
                            launchOnGLThread { onSuccess(assetAddress) }
                        }
                    }
                    is TransactionResult.NoWalletFound -> launchOnGLThread {
                        onError(Exception("No MWA-compatible wallet app found - install Phantom or Solflare"))
                    }
                    is TransactionResult.Failure -> launchOnGLThread { onError(result.e) }
                }
            } catch (ex: Exception) {
                Log.error("Failed to mint victory certificate", ex)
                launchOnGLThread { onError(ex) }
            }
        }
    }

    /**
     * Takes the transaction the wallet handed back and makes sure it still says what we signed.
     *
     * A legacy transaction on the wire is `compact-u16(signatureCount) | signatures | message`, and
     * with fewer than 128 signers that length prefix is a single byte, so the layout here is fixed
     * and checkable. Two things are checked and one repaired: the message must be byte-identical to
     * the one both signatures cover (if a wallet rewrote it - to add a priority fee, say - the
     * asset signature is void and the only honest move is to stop), the payer's slot must actually
     * be filled, and the asset's slot is written back from [assetSignature] so it does not matter
     * whether the wallet preserved it, zeroed it, or dropped it.
     */
    private fun withAssetSignature(
        signedPayload: ByteArray,
        messageBytes: ByteArray,
        signerCount: Int,
        assetIndex: Int,
        assetSignature: ByteArray
    ): ByteArray {
        val signatureLength = Transaction.SIGNATURE_LENGTH_BYTES
        val messageOffset = 1 + signerCount * signatureLength
        if (signedPayload.size != messageOffset + messageBytes.size ||
            !signedPayload.copyOfRange(messageOffset, signedPayload.size).contentEquals(messageBytes)
        ) throw IllegalStateException("The wallet changed the certificate transaction before signing it - mint cancelled")

        val fullySigned = signedPayload.copyOf()
        assetSignature.copyInto(fullySigned, 1 + assetIndex * signatureLength)

        val payerSignature = fullySigned.copyOfRange(1, 1 + signatureLength)
        if (payerSignature.all { it == 0.toByte() })
            throw IllegalStateException("The wallet did not sign the certificate transaction")

        return fullySigned
    }

    /**
     * The CreateV1 instruction, accounts in the order the program expects them.
     *
     * The unsupplied optional accounts are passed as **the Core program's own address** - not the
     * system program, not a zero key. That is what @metaplex-foundation/mpl-core emits and what the
     * program checks for; getting it wrong fails on-chain with an unhelpful error. Leaving `owner`
     * and `updateAuthority` unsupplied is deliberate: Core then defaults both to [payer], which is
     * exactly what a certificate wants - the player owns it, and can later update its `uri`.
     */
    private fun createV1Instruction(
        asset: SolanaPublicKey,
        payer: SolanaPublicKey,
        name: String,
        uri: String
    ): TransactionInstruction {
        val coreProgram = SolanaPublicKey.from(MPL_CORE_PROGRAM_ID)
        val notSupplied = AccountMeta(coreProgram, isSigner = false, isWritable = false)
        return TransactionInstruction(
            coreProgram,
            listOf(
                AccountMeta(asset, isSigner = true, isWritable = true),
                notSupplied,    // collection
                notSupplied,    // authority
                AccountMeta(payer, isSigner = true, isWritable = true),
                notSupplied,    // owner
                notSupplied,    // updateAuthority
                AccountMeta(SolanaPublicKey.from(SYSTEM_PROGRAM_ID), isSigner = false, isWritable = false),
                notSupplied     // logWrapper
            ),
            createV1InstructionData(name, uri)
        )
    }

    /**
     * CreateV1's Borsh payload, byte for byte as the JS SDK emits it: `00` discriminator, `00`
     * dataState = AccountState, the name and uri each as a u32 LE length followed by UTF-8, then
     * `01` (plugins = Some) and `00000000` (of an empty vec).
     *
     * Hand-rolled rather than run through kborsh: eleven bytes of framing, and writing them out
     * literally is what makes them checkable against the captured reference.
     */
    private fun createV1InstructionData(name: String, uri: String): ByteArray {
        fun ByteArrayOutputStream.writeBorshString(value: String) {
            val utf8 = value.toByteArray(Charsets.UTF_8)
            write(utf8.size and 0xFF)
            write((utf8.size ushr 8) and 0xFF)
            write((utf8.size ushr 16) and 0xFF)
            write((utf8.size ushr 24) and 0xFF)
            write(utf8)
        }
        return ByteArrayOutputStream().apply {
            write(0)
            write(0)
            writeBorshString(name)
            writeBorshString(uri)
            write(1)
            write(byteArrayOf(0, 0, 0, 0))
        }.toByteArray()
    }

    /**
     * Compiles [instructions] into a legacy message with [feePayer] pinned to account index 0.
     *
     * Written out rather than handed to web3-solana's own `Message.Builder`, for two reasons found
     * by building messages with it and reading the bytes back (2026-09-18, web3-solana 0.3.0):
     *
     *  * it has no notion of a fee payer - account 0 is just whichever writable signer an
     *    instruction happened to name first, which for CreateV1 is the brand-new *asset* account,
     *    an account with no lamports to pay a fee with;
     *  * its `numReadonlyUnsignedAccounts` header field counts only the read-only accounts the
     *    instructions named, not the program ids it appends after them - so the program being
     *    invoked ends up marked writable, which the runtime rejects.
     *
     * The ordering below is the one the runtime requires: writable signers, read-only signers,
     * writable non-signers, read-only non-signers, with the header counting the read-only tail of
     * each half. Program ids go in last, read-only, merging with an existing entry if an
     * instruction already named that key.
     */
    private fun buildLegacyMessage(
        feePayer: SolanaPublicKey,
        instructions: List<TransactionInstruction>,
        blockhash: String
    ): Message {
        class MessageAccount(val key: SolanaPublicKey, var isSigner: Boolean, var isWritable: Boolean)

        val accounts = LinkedHashMap<String, MessageAccount>()
        fun include(key: SolanaPublicKey, isSigner: Boolean, isWritable: Boolean) {
            val existing = accounts[key.base58()]
            if (existing == null) accounts[key.base58()] = MessageAccount(key, isSigner, isWritable)
            else {
                // An account named twice keeps the widest access asked for anywhere - dropping a
                // privilege here would fail at runtime, inside the instruction that needed it.
                existing.isSigner = existing.isSigner || isSigner
                existing.isWritable = existing.isWritable || isWritable
            }
        }

        // First, so it lands at index 0 of the writable-signer group and so becomes the fee payer.
        include(feePayer, isSigner = true, isWritable = true)
        for (instruction in instructions)
            for (account in instruction.accounts)
                include(account.publicKey, account.isSigner, account.isWritable)
        for (instruction in instructions)
            include(instruction.programId, isSigner = false, isWritable = false)

        val writableSigners = accounts.values.filter { it.isSigner && it.isWritable }
        val readonlySigners = accounts.values.filter { it.isSigner && !it.isWritable }
        val writableNonSigners = accounts.values.filter { !it.isSigner && it.isWritable }
        val readonlyNonSigners = accounts.values.filter { !it.isSigner && !it.isWritable }
        val ordered = writableSigners + readonlySigners + writableNonSigners + readonlyNonSigners

        val indexOf = ordered.withIndex().associate { (index, account) -> account.key.base58() to index }
        val compiled = instructions.map { instruction ->
            Instruction(
                indexOf.getValue(instruction.programId.base58()).toUByte(),
                instruction.accounts.map { indexOf.getValue(it.publicKey.base58()).toByte() }.toByteArray(),
                instruction.data
            )
        }

        return LegacyMessage(
            (writableSigners.size + readonlySigners.size).toUByte(),
            readonlySigners.size.toUByte(),
            readonlyNonSigners.size.toUByte(),
            ordered.map { it.key },
            SolanaPublicKey.from(blockhash),
            compiled
        )
    }

    /**
     * Polls until the network reports [signature] confirmed - the start anchor's map is generated
     * from the signature, so it has to be known to have landed first. A transaction that failed
     * on chain, or is not seen within [timeoutMs], is an error: the game then starts unanchored.
     */
    private suspend fun awaitConfirmation(signature: String, timeoutMs: Long = 45_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val requestBody = kotlinx.serialization.json.buildJsonObject {
                put("jsonrpc", kotlinx.serialization.json.JsonPrimitive("2.0"))
                put("id", kotlinx.serialization.json.JsonPrimitive(1))
                put("method", kotlinx.serialization.json.JsonPrimitive("getSignatureStatuses"))
                put("params", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(signature)) })
                })
            }
            val response: kotlinx.serialization.json.JsonObject = httpClient.post(RPC_ENDPOINT) {
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }.body()
            // A refusal (e.g. the site's RPC proxy not forwarding this method) is said at once,
            // not waited out as "not confirmed in time" - that hid a 403 on the phone, 09-26.
            (response["error"] as? kotlinx.serialization.json.JsonObject)?.let { err ->
                throw IllegalStateException("The network refused the confirmation check: " +
                    ((err["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: err.toString()))
            }
            // result.value is a list with one entry per signature: null until the network has
            // seen it, then an object with "err" (null on success) and "confirmationStatus".
            val status = (response["result"] as? kotlinx.serialization.json.JsonObject)
                ?.get("value")?.let { it as? kotlinx.serialization.json.JsonArray }
                ?.firstOrNull() as? kotlinx.serialization.json.JsonObject
            if (status != null) {
                val err = status["err"]
                if (err != null && err !is kotlinx.serialization.json.JsonNull)
                    throw IllegalStateException("The start anchor failed on chain: $err")
                val level = (status["confirmationStatus"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                if (level == "confirmed" || level == "finalized") return
            }
            kotlinx.coroutines.delay(1_000)
        }
        throw IllegalStateException("The start anchor was not confirmed in time")
    }

    override fun anchorGameStart(
        gameId: String,
        onSuccess: (txSignature: String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val address = _connectedAddress
        if (authToken == null || address == null) {
            onError(IllegalStateException("No wallet connected"))
            return
        }

        Concurrency.run("WalletAnchorStart") {
            try {
                val ownerKey = SolanaPublicKey.from(address)
                // Memo only: no transfer, so the player pays the network's fee and nothing else
                // (user, 09-26). Read-only signer, as in [recordSaveHash].
                val memoInstruction = TransactionInstruction(
                    memoProgramId,
                    listOf(AccountMeta(ownerKey, isSigner = true, isWritable = false)),
                    StartAnchor.memo(gameId).encodeToByteArray()
                )
                val message = Message.Builder()
                    .addInstruction(memoInstruction)
                    .setRecentBlockhash(fetchLatestBlockhash())
                    .build()
                val unsignedTx = Transaction(message)

                val result = withWakeLock {
                    withBridge { sender ->
                        walletAdapter.transact(sender) {
                            signAndSendTransactions(arrayOf(unsignedTx.serialize()))
                        }
                    }
                }
                val signature = when (result) {
                    is TransactionResult.Success -> result.successPayload?.signatures?.firstOrNull()
                        ?.let { base58Encode(it) }
                        ?: throw IllegalStateException("Wallet did not return a transaction signature")
                    is TransactionResult.NoWalletFound -> throw Exception("No MWA-compatible wallet app found")
                    is TransactionResult.Failure -> throw result.e
                }
                awaitConfirmation(signature)
                launchOnGLThread { onSuccess(signature) }
            } catch (ex: Exception) {
                Log.error("Failed to anchor the game start on-chain", ex)
                launchOnGLThread { onError(ex) }
            }
        }
    }

    /** Cloud-save keys by wallet address, for this run of the app only - never written anywhere. */
    private val cloudKeys = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    override fun cloudSaveKey(onSuccess: (key: ByteArray) -> Unit, onError: (Exception) -> Unit) {
        val address = _connectedAddress
        if (authToken == null || address == null) {
            onError(IllegalStateException("No wallet connected"))
            return
        }
        cloudKeys[address]?.let { onSuccess(it); return }
        Concurrency.run("WalletCloudKey") {
            try {
                val owner = SolanaPublicKey.from(address)
                val result = withWakeLock {
                    withBridge { sender ->
                        walletAdapter.transact(sender) {
                            signMessagesDetached(arrayOf(CloudSave.KEY_MESSAGE.encodeToByteArray()), arrayOf(owner.bytes))
                        }
                    }
                }
                val signature = when (result) {
                    is TransactionResult.Success -> result.successPayload?.messages?.firstOrNull()?.signatures?.firstOrNull()
                        ?: throw IllegalStateException("Wallet did not return a signature")
                    is TransactionResult.NoWalletFound -> throw Exception("No MWA-compatible wallet app found")
                    is TransactionResult.Failure -> throw result.e
                }
                val key = CloudSave.keyFromSignature(signature)
                cloudKeys[address] = key
                launchOnGLThread { onSuccess(key) }
            } catch (ex: Exception) {
                Log.error("Failed to get the cloud save key", ex)
                launchOnGLThread { onError(ex) }
            }
        }
    }

    override fun uploadCloudSave(data: ByteArray, onSuccess: (arweaveIds: List<String>) -> Unit, onError: (Exception) -> Unit) {
        Concurrency.run("WalletCloudUpload") {
            try {
                // Each piece is its own data item under Turbo's free size, so the upload costs
                // nothing and the 1 SKR record fee stays the whole price (ROADMAP)
                val ids = CloudSave.chunks(data).map { uploadToArweave(it, "application/octet-stream").substringAfterLast('/') }
                launchOnGLThread { onSuccess(ids) }
            } catch (ex: Exception) {
                Log.error("Failed to upload the cloud save", ex)
                launchOnGLThread { onError(ex) }
            }
        }
    }

    override fun listSaveRecords(shared: Boolean, onSuccess: (List<CloudSave.Record>) -> Unit, onError: (Exception) -> Unit) {
        val own = _connectedAddress
        if (!shared && own == null) {
            onError(IllegalStateException("No wallet connected"))
            return
        }
        Concurrency.run("WalletListSaves") {
            try {
                // Every record pays its fee into the treasury's SKR account, so that account's
                // history is everyone's records; the treasury's own address is not in them
                val address = if (shared) treasurySkrAccount().base58() else own!!
                val records = memoHistory(address).mapNotNull { (memo, signature, blockTime) ->
                    CloudSave.parse(memo, signature, blockTime)
                }.filter { if (shared) it.shared && it.restorable else true }
                launchOnGLThread { onSuccess(records) }
            } catch (ex: Exception) {
                Log.error("Failed to list save records", ex)
                launchOnGLThread { onError(ex) }
            }
        }
    }

    /** Successful finalized memo records, newest first. Tip ranking scans all pages or reports an error. */
    private suspend fun memoHistory(address: String, complete: Boolean = false): List<Triple<String, String, Long>> {
        val records = ArrayList<Triple<String, String, Long>>()
        var before: String? = null
        val seen = HashSet<String>()
        repeat(if (complete) 100 else 1) {
            val requestBody = kotlinx.serialization.json.buildJsonObject {
                put("jsonrpc", kotlinx.serialization.json.JsonPrimitive("2.0"))
                put("id", kotlinx.serialization.json.JsonPrimitive(1))
                put("method", kotlinx.serialization.json.JsonPrimitive("getSignaturesForAddress"))
                put("params", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive(address))
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("limit", kotlinx.serialization.json.JsonPrimitive(1000))
                        put("commitment", kotlinx.serialization.json.JsonPrimitive("finalized"))
                        if (before != null) put("before", kotlinx.serialization.json.JsonPrimitive(before))
                    })
                })
            }
            val response: kotlinx.serialization.json.JsonObject = httpClient.post(RPC_ENDPOINT) {
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }.body()
            (response["error"] as? kotlinx.serialization.json.JsonObject)?.let { err ->
                throw IllegalStateException("The network refused the list: " +
                    ((err["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: err.toString()))
            }
            val entries = response["result"] as? kotlinx.serialization.json.JsonArray
                ?: throw IllegalStateException("The network returned no history")
            records += entries.mapNotNull { e ->
                val o = e as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val err = o["err"]
                if (err != null && err !is kotlinx.serialization.json.JsonNull) return@mapNotNull null
                val memo = (o["memo"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: return@mapNotNull null
                Triple(
                    memo,
                    (o["signature"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "",
                    (o["blockTime"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L
                )
            }
            if (!complete || entries.size < 1000) return records.distinctBy { it.second }
            val next = (entries.last() as? JsonObject)?.get("signature")?.jsonPrimitive?.content
                ?: throw IllegalStateException("The network returned an incomplete history")
            check(seen.add(next)) { "The network repeated a history page" }
            before = next
        }
        throw IllegalStateException("Tip history is too large to verify completely. Rankings were not updated.")
    }

    /** Tip transactions already checked: transaction/save/author key -> verified amount, or null when the
     *  transaction did not pay what its memo claims. A settled transaction never changes. */
    private val checkedTips = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<Pair<String, Long>>>()

    override fun listBounties(onSuccess: (List<CloudSave.Bounty>) -> Unit, onError: (Exception) -> Unit) {
        Concurrency.run("WalletListBounties") {
            try {
                val bounties = memoHistory(treasurySkrAccount().base58()).mapNotNull { CloudSave.parseBounty(it.first) }
                launchOnGLThread { onSuccess(bounties) }
            } catch (ex: Exception) {
                Log.error("Failed to list bounties", ex)
                launchOnGLThread { onError(ex) }
            }
        }
    }

    /** Addresses already checked for a Seeker Genesis Token. A token moves only between one owner's
     *  Seed Vault accounts, so an answer holds for the session. */
    private val seekerChecked = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    override fun seekerOwners(addresses: Set<String>, onSuccess: (Set<String>) -> Unit, onError: (Exception) -> Unit) {
        Concurrency.run("WalletSeekerOwners") {
            try {
                for (address in addresses) if (!seekerChecked.containsKey(address))
                    seekerChecked[address] = holdsSeekerToken(address)
                launchOnGLThread { onSuccess(addresses.filter { seekerChecked[it] == true }.toSet()) }
            } catch (ex: Exception) {
                Log.error("Failed to check Seeker Genesis Tokens", ex)
                launchOnGLThread { onError(ex) }
            }
        }
    }

    private suspend fun rpcJson(method: String, params: kotlinx.serialization.json.JsonArray): JsonObject {
        val body = kotlinx.serialization.json.buildJsonObject {
            put("jsonrpc", kotlinx.serialization.json.JsonPrimitive("2.0"))
            put("id", kotlinx.serialization.json.JsonPrimitive(1))
            put("method", kotlinx.serialization.json.JsonPrimitive(method))
            put("params", params)
        }
        val response: JsonObject = httpClient.post(RPC_ENDPOINT) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()
        (response["error"] as? JsonObject)?.let { throw IllegalStateException("RPC $method: ${it["message"]?.jsonPrimitive?.content}") }
        return response
    }

    /** Whether [owner] holds, with a balance, a Token-2022 mint that is a member of the SGT group. */
    private suspend fun holdsSeekerToken(owner: String): Boolean {
        val accounts = rpcJson("getTokenAccountsByOwner", kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.JsonPrimitive(owner))
            add(kotlinx.serialization.json.buildJsonObject { put("programId", kotlinx.serialization.json.JsonPrimitive(TOKEN_2022_PROGRAM)) })
            add(kotlinx.serialization.json.buildJsonObject { put("encoding", kotlinx.serialization.json.JsonPrimitive("jsonParsed")) })
        })["result"]?.jsonObject?.get("value") as? kotlinx.serialization.json.JsonArray ?: return false
        // An SGT is one token with no decimals; anything else cannot be one
        val mints = accounts.mapNotNull {
            val info = it.jsonObject["account"]?.jsonObject?.get("data")?.jsonObject?.get("parsed")?.jsonObject?.get("info")?.jsonObject
            val amount = info?.get("tokenAmount")?.jsonObject?.get("amount")?.jsonPrimitive?.content
            if (amount == "1") info["mint"]?.jsonPrimitive?.content else null
        }
        for (mint in mints) {
            val value = rpcJson("getAccountInfo", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive(mint))
                add(kotlinx.serialization.json.buildJsonObject { put("encoding", kotlinx.serialization.json.JsonPrimitive("jsonParsed")) })
            })["result"]?.jsonObject?.get("value") as? JsonObject ?: continue
            if (value["owner"]?.jsonPrimitive?.content != TOKEN_2022_PROGRAM) continue
            val extensions = value["data"]?.jsonObject?.get("parsed")?.jsonObject?.get("info")?.jsonObject
                ?.get("extensions") as? kotlinx.serialization.json.JsonArray ?: continue
            fun state(name: String) = extensions.map { it.jsonObject }
                .firstOrNull { it["extension"]?.jsonPrimitive?.content == name }?.get("state")?.jsonObject
            if (state("metadataPointer")?.get("metadataAddress")?.jsonPrimitive?.content == SGT_GROUP &&
                state("tokenGroupMember")?.get("group")?.jsonPrimitive?.content == SGT_GROUP) return true
        }
        return false
    }

    private val verifiedSaveAuthors = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private suspend fun verifiedSaveAuthor(signature: String, author: String): Boolean? {
        val key = "$signature:$author"
        verifiedSaveAuthors[key]?.let { return it }
        val transaction = finalizedTransaction(signature) ?: return null
        val valid = com.unciv.logic.chain.TipTransactionVerifier.verifyAuthor(transaction, signature, author, TREASURY_ADDRESS)
        verifiedSaveAuthors[key] = valid
        return valid
    }

    override fun listSaveTips(authors: Map<String, String>, onSuccess: (Map<String, Long>) -> Unit, onError: (Exception) -> Unit) {
        Concurrency.run("WalletListTips") {
            try {
                for ((memo, txSignature, _) in memoHistory(TIP_REFERENCE, complete = true)) {
                    val (save, wholeSkr) = CloudSave.parseTip(memo) ?: continue
                    val author = authors[save] ?: continue
                    val cacheKey = "$txSignature:$save:$author"
                    if (checkedTips.containsKey(cacheKey)) continue
                    val verifiedAuthor = verifiedSaveAuthor(save, author)
                        ?: throw IllegalStateException("The save author could not be checked. Please try again.")
                    val transaction = finalizedTransaction(txSignature)
                        ?: throw IllegalStateException("A tip could not be checked. Please try again.")
                    val valid = verifiedAuthor && com.unciv.logic.chain.TipTransactionVerifier.verifyTip(
                        transaction, txSignature, save, author, wholeSkr)
                    checkedTips[cacheKey] = java.util.Optional.ofNullable(if (valid) save to wholeSkr else null)
                }
                val tips = checkedTips.entries.filter { (key, _) ->
                    val parts = key.split(":")
                    parts.size == 3 && authors[parts[1]] == parts[2]
                }.mapNotNull { it.value.orElse(null) }
                    .groupBy({ it.first }, { it.second }).mapValues { (_, amounts) ->
                        amounts.fold(0L) { sum, amount -> Math.addExact(sum, amount) }
                    }
                launchOnGLThread { onSuccess(tips) }
            } catch (ex: Exception) {
                Log.error("Failed to list save tips", ex)
                launchOnGLThread { onError(ex) }
            }
        }
    }

    /** Null is retryable: missing/unfinalized transactions and RPC errors are never negative-cached. */
    private suspend fun finalizedTransaction(signature: String): String? {
        val requestBody = kotlinx.serialization.json.buildJsonObject {
            put("jsonrpc", kotlinx.serialization.json.JsonPrimitive("2.0"))
            put("id", kotlinx.serialization.json.JsonPrimitive(1))
            put("method", kotlinx.serialization.json.JsonPrimitive("getTransaction"))
            put("params", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive(signature))
                add(kotlinx.serialization.json.buildJsonObject {
                    put("encoding", kotlinx.serialization.json.JsonPrimitive("json"))
                    put("commitment", kotlinx.serialization.json.JsonPrimitive("finalized"))
                    put("maxSupportedTransactionVersion", kotlinx.serialization.json.JsonPrimitive(0))
                })
            })
        }
        val response: JsonObject = httpClient.post(RPC_ENDPOINT) {
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }.body()
        if (response["error"] != null) return null
        return (response["result"] as? JsonObject)?.toString()
    }

    override fun tipSaveAuthor(author: String, saveSignature: String, wholeSkr: Long, onSuccess: (txSignature: String) -> Unit, onError: (Exception) -> Unit) {
        val tipper = _connectedAddress
        if (authToken == null || tipper == null) {
            onError(IllegalStateException("No wallet connected"))
            return
        }
        Concurrency.run("WalletTipSave") {
            try {
                check(wholeSkr in 1..CloudSave.MAX_TIP_SKR) { "A tip is 1 to ${CloudSave.MAX_TIP_SKR} SKR" }
                check(author != tipper) { "This is your own save" }
                check(verifiedSaveAuthor(saveSignature, author) == true) { "The save author could not be verified. Please try again later." }
                val amount = wholeSkr * 1_000_000L
                val tipperKey = SolanaPublicKey.from(tipper)
                val authorKey = SolanaPublicKey.from(author)
                val skrMint = SolanaPublicKey.from(SKR_MINT)
                val from = skrAccount(tipperKey)
                val to = skrAccount(authorKey)
                val balance = skrBalance(from)
                check(balance >= amount) { "This wallet has ${balance / 1_000_000} SKR; the tip is $wholeSkr SKR" }

                val builder = Message.Builder()
                // An author paid 1 SKR to share, so their SKR account exists - unless they emptied
                // and closed it since; then the tipper opens it, as a fee payer does for the treasury
                if (!accountExists(to)) builder.addInstruction(
                    AssociatedTokenProgram.createAssociatedTokenAccount(
                        mint = skrMint, associatedAccount = to, owner = authorKey, payer = tipperKey
                    )
                )
                // The reference rides on the transfer as a read-only, non-signer account (Solana
                // Pay's pattern): the token program ignores it, and getSignaturesForAddress on it
                // finds every tip. Not on the memo - the Memo program requires every account to sign.
                val transfer = TokenProgram.transferChecked(
                    from = from, to = to, amount = amount, decimals = SKR_DECIMALS, owner = tipperKey, mint = skrMint
                )
                val transferWithReference = TransactionInstruction(
                    transfer.programId,
                    transfer.accounts + AccountMeta(SolanaPublicKey.from(TIP_REFERENCE), isSigner = false, isWritable = false),
                    transfer.data
                )
                val memo = TransactionInstruction(
                    memoProgramId,
                    listOf(AccountMeta(tipperKey, isSigner = true, isWritable = false)),
                    CloudSave.tipMemo(saveSignature, wholeSkr).encodeToByteArray()
                )
                val message = builder
                    .addInstruction(transferWithReference)
                    .addInstruction(memo)
                    .setRecentBlockhash(fetchLatestBlockhash())
                    .build()
                val unsignedTx = Transaction(message)
                val result = withWakeLock {
                    withBridge { sender ->
                        walletAdapter.transact(sender) { signAndSendTransactions(arrayOf(unsignedTx.serialize())) }
                    }
                }
                when (result) {
                    is TransactionResult.Success -> {
                        val sigBytes = result.successPayload?.signatures?.firstOrNull()
                        if (sigBytes == null) launchOnGLThread { onError(IllegalStateException("Wallet did not return a transaction signature")) }
                        else launchOnGLThread { onSuccess(base58Encode(sigBytes)) }
                    }
                    is TransactionResult.NoWalletFound -> launchOnGLThread { onError(Exception("No MWA-compatible wallet app found")) }
                    is TransactionResult.Failure -> launchOnGLThread { onError(result.e) }
                }
            } catch (ex: Exception) {
                Log.error("Failed to tip a save's author", ex)
                launchOnGLThread { onError(ex) }
            }
        }
    }

    override fun downloadCloudSave(arweaveIds: List<String>, onSuccess: (ByteArray) -> Unit, onError: (Exception) -> Unit) {
        Concurrency.run("WalletCloudDownload") {
            try {
                val bytes = com.unciv.logic.chain.CloudSaveDownload.fetch(arweaveIds) { id, gateway ->
                    val url = if (gateway == 0) ARWEAVE_GATEWAY + id else "https://arweave.net/$id"
                    try {
                        kotlinx.coroutines.withTimeout(15_000) {
                            val response = httpClient.get(url)
                            if (response.status.value == 200) response.body<ByteArray>() else null
                        }
                    } catch (_: kotlinx.coroutines.TimeoutCancellationException) { null }
                }
                launchOnGLThread { onSuccess(bytes) }
            } catch (ex: Exception) {
                Log.error("Failed to download the cloud save", ex)
                launchOnGLThread { onError(ex) }
            }
        }
    }

    /** The treasury's SKR token account: the ATA PDA of [treasury, TOKEN_PROGRAM, SKR mint]. */
    private suspend fun treasurySkrAccount(): SolanaPublicKey {
        val pda = ProgramDerivedAddress.find(
            listOf(SolanaPublicKey.from(TREASURY_ADDRESS).bytes, TokenProgram.PROGRAM_ID.bytes, SolanaPublicKey.from(SKR_MINT).bytes),
            AssociatedTokenProgram.PROGRAM_ID
        ).getOrThrow()
        return SolanaPublicKey(pda.bytes)
    }

    /** [owner]'s SKR token account: the ATA PDA of [owner, TOKEN_PROGRAM, SKR mint]. */
    private suspend fun skrAccount(owner: SolanaPublicKey): SolanaPublicKey {
        val pda = ProgramDerivedAddress.find(
            listOf(owner.bytes, TokenProgram.PROGRAM_ID.bytes, SolanaPublicKey.from(SKR_MINT).bytes),
            AssociatedTokenProgram.PROGRAM_ID
        ).getOrThrow()
        return SolanaPublicKey(pda.bytes)
    }

    /** US dollars per SKR, from the first source that answers with a believable number. */
    private suspend fun skrUsdPrice(): Double {
        fun JsonElement.field(name: String) = jsonObject[name]!!
        val sources = listOf<Pair<String, suspend () -> Double>>(
            "Jupiter" to { httpClient.get(SKR_PRICE_JUPITER).body<JsonObject>().field(SKR_MINT).field("usdPrice").jsonPrimitive.double },
            "CoinGecko" to { httpClient.get(SKR_PRICE_COINGECKO).body<JsonObject>().field("seeker").field("usd").jsonPrimitive.double }
        )
        for ((name, read) in sources) {
            try {
                val price = read()
                if (price in SKR_USD_MIN..SKR_USD_MAX) {
                    Log.debug("Certificate fee: SKR/USD %.5f from %s", price, name)
                    return price
                }
                Log.error("Certificate fee: %s gave SKR/USD %f, outside the believable band", name, price)
            } catch (ex: Exception) {
                Log.error("Certificate fee: could not read SKR/USD from $name", ex)
            }
        }
        throw IllegalStateException("Could not get the SKR price - pay in SOL instead")
    }

    /** [CERTIFICATE_FEE_USD_CENTS] in SKR base units, rounded up to a whole SKR so the wallet
     *  shows a plain number. */
    private suspend fun certificateFeeSkr(): Long {
        val wholeSkr = Math.ceil(CERTIFICATE_FEE_USD_CENTS / (skrUsdPrice() * 100)).toLong()
        return wholeSkr * 1_000_000L
    }

    /** Base units of SKR in [account]; 0 when the account does not exist. Read from the account's
     *  data (an SPL token account holds its amount as a little-endian u64 at byte 64), because the
     *  RPC proxy forwards getAccountInfo but not getTokenAccountBalance. */
    private suspend fun skrBalance(account: SolanaPublicKey): Long {
        val (owner, data) = fetchAccount(account.base58()) ?: return 0L
        check(owner == TokenProgram.PROGRAM_ID.base58() && data.size >= 72) { "SKR account owned by $owner" }
        return java.nio.ByteBuffer.wrap(data, 64, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).long
    }

    /**
     * The SKR fee as instructions: create the treasury's SKR account if nobody has yet, then a
     * transferChecked from the player's. The balance is checked here, because a transaction short
     * of SKR otherwise fails in preflight with a token-program error nobody can read.
     */
    private suspend fun skrFeeInstructions(payer: SolanaPublicKey, amount: Long): List<TransactionInstruction> {
        val skrMint = SolanaPublicKey.from(SKR_MINT)
        val treasuryKey = SolanaPublicKey.from(TREASURY_ADDRESS)
        val playerAccount = skrAccount(payer)
        val treasuryAccount = skrAccount(treasuryKey)
        val balance = skrBalance(playerAccount)
        check(balance >= amount) {
            "This wallet has ${balance / 1_000_000} SKR; the certificate costs ${amount / 1_000_000} SKR"
        }
        Log.debug("Victory certificate: fee %d SKR (%d US cents)", amount / 1_000_000, CERTIFICATE_FEE_USD_CENTS)
        val create = if (accountExists(treasuryAccount)) emptyList() else listOf(
            AssociatedTokenProgram.createAssociatedTokenAccount(
                mint = skrMint, associatedAccount = treasuryAccount, owner = treasuryKey, payer = payer
            )
        )
        return create + TokenProgram.transferChecked(
            from = playerAccount, to = treasuryAccount, amount = amount,
            decimals = SKR_DECIMALS, owner = payer, mint = skrMint
        )
    }

    override fun recordSaveHash(
        record: CloudSave.Record,
        onSuccess: (txSignature: String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        // Keep the memo well within Solana's ~1232 byte tx size limit - gameId is a UUID
        // (36 chars), the hash 64, the name capped at 64 with ':' stripped (the field
        // delimiter), and each Arweave id 43: one per 90 KiB of save, so even a large
        // late-game save stays a few hundred bytes (CloudSave.Record.memo)
        val memoText = record.memo()
        if (memoText.length >= 900) return onError(IllegalStateException("This save is too large to record in one transaction"))
        val payer = _connectedAddress
        if (payer == null || authToken == null) return onError(IllegalStateException("No wallet connected"))
        val operation = listOf(record.gameId, CloudSave.safeName(record.name), record.hashHex,
            record.visibility, record.keyFingerprint).joinToString(":")
        Concurrency.run("WalletRecordSaveHash") {
            try {
                savePaymentMutex.lock()
                val signature = try { recoverOrPaySave(payer, operation, memoText) }
                finally { savePaymentMutex.unlock() }
                launchOnGLThread { onSuccess(signature) }
            } catch (ex: Exception) {
                Log.error("Save payment requires checking", ex)
                val unresolved = runCatching { savePayments.pending(payer) != null }.getOrDefault(true)
                val error = if (unresolved) SavePaymentPendingException(
                    "The payment result is not known yet. Try Save again to check it before paying.") else ex
                launchOnGLThread { onError(error) }
            }
        }
    }

    private val savePayments by lazy {
        val preferences = activity.getSharedPreferences("save-payment-recovery-v1", Activity.MODE_PRIVATE)
        SavePaymentJournal(object : SavePaymentJournal.Store {
            override fun read(): String? = preferences.getString("journal", null)
            override fun write(value: String) {
                check(preferences.edit().putString("journal", value).commit()) {
                    "Could not keep the payment receipt safely; no new payment was sent"
                }
            }
        })
    }

    private suspend fun recoveryRpc(method: String, params: kotlinx.serialization.json.JsonArray): JsonObject {
        val response: JsonObject = httpClient.post(RPC_ENDPOINT) {
            contentType(ContentType.Application.Json)
            setBody(kotlinx.serialization.json.buildJsonObject {
                put("jsonrpc", kotlinx.serialization.json.JsonPrimitive("2.0"))
                put("id", kotlinx.serialization.json.JsonPrimitive(1))
                put("method", kotlinx.serialization.json.JsonPrimitive(method)); put("params", params)
            })
        }.body()
        check(response["error"] == null) { "The payment status could not be checked. Try again to check; do not pay again." }
        return response
    }

    private suspend fun checkSavePayment(entry: SavePaymentJournal.Entry): SavePaymentJournal.Outcome {
        // Read finalized height before historical status. Missing recent-cache data is not failure.
        val heightResponse = recoveryRpc("getBlockHeight", kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.buildJsonObject {
                put("commitment", kotlinx.serialization.json.JsonPrimitive("finalized"))
            })
        })
        val height = heightResponse["result"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: throw IllegalStateException("The finalized chain height could not be checked")
        val statusResponse = recoveryRpc("getSignatureStatuses", kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(entry.signature)) })
            add(kotlinx.serialization.json.buildJsonObject {
                put("searchTransactionHistory", kotlinx.serialization.json.JsonPrimitive(true))
            })
        })
        val values = statusResponse["result"]?.jsonObject?.get("value") as? kotlinx.serialization.json.JsonArray
            ?: throw IllegalStateException("The payment status response is incomplete")
        check(values.size == 1) { "The payment status response is incomplete" }
        check(values[0] is JsonObject || values[0] is kotlinx.serialization.json.JsonNull) {
            "The payment status response is invalid"
        }
        val status = values[0] as? JsonObject
        val level = status?.get("confirmationStatus")?.jsonPrimitive?.content
        check(status == null || level != null) { "The payment confirmation level is missing" }
        check(status == null || status.containsKey("err")) { "The payment execution result is missing" }
        val err = status?.get("err")
        return SavePaymentJournal.outcome(level, err != null && err !is kotlinx.serialization.json.JsonNull,
            height, entry.lastValidBlockHeight)
    }

    private suspend fun recoverOrPaySave(payer: String, operation: String, memo: String): String {
        savePayments.receipt(payer, operation)?.let { return it.signature }
        savePayments.pending(payer)?.let { pending ->
            val outcome = checkSavePayment(pending)
            if (outcome == SavePaymentJournal.Outcome.UNKNOWN) {
                // Same signed bytes retain the same signature; resubmission cannot be a second fee.
                // Never submit another snapshot while the previous payment remains uncertain.
                if (pending.operation == operation) {
                    try { sendSignedTransaction(Base64.decode(pending.signedTransaction, Base64.NO_WRAP)) }
                    catch (_: Exception) { /* Keep the original receipt for later checks. */ }
                    val checked = checkSavePayment(pending)
                    if (checked == SavePaymentJournal.Outcome.FINALIZED) {
                        savePayments.resolve(pending.signature, checked)
                        return pending.signature
                    }
                }
                throw IllegalStateException("A previous save payment is still being checked. Try Save again to check its status; no new payment was sent.")
            }
            savePayments.resolve(pending.signature, outcome)
            if (outcome == SavePaymentJournal.Outcome.FINALIZED && pending.operation == operation)
                return pending.signature
        }
        check(_connectedAddress == payer && authToken != null) { "Reconnect the same wallet before saving" }
        val lease = fetchBlockhashLease()
        check(lease.lastValidBlockHeight > 0) { "The payment expiry could not be checked; no payment was sent" }
        val owner = SolanaPublicKey.from(payer)
        val message = Message.Builder().apply {
            skrFeeInstructions(owner, SKR_FEE_AMOUNT).forEach { addInstruction(it) }
        }.addInstruction(TransactionInstruction(memoProgramId,
            listOf(AccountMeta(owner, isSigner = true, isWritable = false)), memo.encodeToByteArray()))
            .setRecentBlockhash(lease.blockhash).build()
        val unsigned = Transaction(message).serialize()
        val result = withWakeLock {
            withBridge { sender -> walletAdapter.transact(sender) { signTransactions(arrayOf(unsigned)) } }
        }
        val signed = when (result) {
            is TransactionResult.Success -> result.successPayload?.signedPayloads?.firstOrNull()
                ?: throw IllegalStateException("The wallet returned no signed payment; no payment was sent")
            is TransactionResult.NoWalletFound -> throw IllegalStateException("No MWA-compatible wallet app found")
            is TransactionResult.Failure -> throw result.e
        }
        check(unsigned[0].toInt() == 1 && signed.size == unsigned.size && signed[0].toInt() == 1 &&
            signed.copyOfRange(65, signed.size).contentEquals(unsigned.copyOfRange(65, unsigned.size))) {
            "The wallet changed the save payment; no payment was sent"
        }
        val signatureBytes = signed.copyOfRange(1, 65)
        check(signatureBytes.any { it != 0.toByte() }) { "The wallet did not sign the save payment" }
        val pending = SavePaymentJournal.Entry().apply {
            this.payer = payer; this.operation = operation; this.memo = memo
            signature = base58Encode(signatureBytes)
            signedTransaction = Base64.encodeToString(signed, Base64.NO_WRAP)
            lastValidBlockHeight = lease.lastValidBlockHeight
        }
        // Durable commit BEFORE first broadcast. Signing alone does not pay.
        savePayments.prepare(pending)
        try { check(sendSignedTransaction(signed) == pending.signature) { "Unexpected payment response" } }
        catch (ex: Exception) { Log.debug("Save submission response unavailable: %s", ex.javaClass.simpleName) }
        val deadline = System.currentTimeMillis() + 45_000
        do {
            val outcome = checkSavePayment(pending)
            if (outcome != SavePaymentJournal.Outcome.UNKNOWN) {
                savePayments.resolve(pending.signature, outcome)
                if (outcome == SavePaymentJournal.Outcome.FINALIZED) return pending.signature
                throw IllegalStateException("The save payment did not complete. You can retry; a failed on-chain attempt may still cost a SOL network fee.")
            }
            kotlinx.coroutines.delay(1_500)
        } while (System.currentTimeMillis() < deadline)
        throw IllegalStateException("The save payment result is not known yet. Try Save again to check its status; no new payment will be requested while it is pending.")
    }

    override fun postBounty(bounty: CloudSave.Bounty, onSuccess: (txSignature: String) -> Unit, onError: (Exception) -> Unit) {
        // Posted like a save record, 1 SKR to the treasury: so the treasury's history - which the
        // gallery reads anyway - lists it, and a bounty costs something to post
        sendMemoWithSkrFee(bounty.memo(), "WalletPostBounty", onSuccess, onError)
    }

    /** [memoText] as a Memo-program instruction signed by the connected wallet, with the 1 SKR fee
     *  to the treasury in the same transaction. */
    private fun sendMemoWithSkrFee(
        memoText: String,
        threadName: String,
        onSuccess: (txSignature: String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val token = authToken
        if (token == null) {
            onError(IllegalStateException("No wallet connected"))
            return
        }

        Concurrency.run(threadName) {
            try {

                val blockhash = fetchLatestBlockhash()

                val ownerKey = SolanaPublicKey.from(
                    _connectedAddress ?: throw IllegalStateException("No wallet connected")
                )

                val memoInstruction = TransactionInstruction(
                    memoProgramId,
                    // isWritable = false: the Memo program never touches account data, only reads
                    // the signer to attribute the memo - a security audit flagged the previous
                    // isWritable=true as unnecessarily broad (harmless here, but no reason to ask
                    // for write access this instruction never uses).
                    listOf(AccountMeta(ownerKey, isSigner = true, isWritable = false)),
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
                Log.error("Failed to send a memo with the SKR fee ($threadName)", ex)
                launchOnGLThread { onError(ex) }
            }
        }
    }
}

/**
 * A throwaway Ed25519 keypair, used for exactly one thing: signing the CreateV1 that brings a
 * Metaplex Core asset into existence at this key's address. Core requires the new asset account to
 * sign its own creation, and the wallet cannot - it has never seen this key. After the mint the
 * key has no further authority over the asset (owner and update authority both default to the
 * payer), so it is simply dropped; nothing here is logged, returned or written to disk, and the
 * only value that ever leaves this class is the public key and a signature.
 *
 * net.i2p.crypto:eddsa rather than java.security: minSdk is 24 and the platform's "Ed25519"
 * KeyPairGenerator/Signature only exist from API 33. Verified 2026-09-18 against the RFC 8032
 * §7.1 test vectors - both the public key derived from the seed and the signature match, for the
 * empty message and for the one-byte message. (salkt's TweetNaclFast, already on the classpath via
 * web3-solana, was checked first and ruled out: it exposes no key generation at all, and its
 * crypto_sign did not reproduce those vectors.)
 */
private class AssetKeypair {
    private val curve = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)

    private val privateKey = EdDSAPrivateKey(
        // 32 random bytes are the whole of an Ed25519 private key; everything else is derived.
        EdDSAPrivateKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, curve)
    )

    /** The asset's on-chain address: an Ed25519 public key, which is what a Solana address is. */
    val publicKey: SolanaPublicKey = SolanaPublicKey(privateKey.abyte)

    /** The same key as raw bytes, as an Arweave data item's owner field wants it. */
    val publicKeyBytes: ByteArray get() = privateKey.abyte

    /** Detached 64-byte signature over [message] - here, the serialized transaction message. */
    fun sign(message: ByteArray): ByteArray =
        EdDSAEngine(MessageDigest.getInstance(curve.hashAlgorithm)).run {
            initSign(privateKey)
            // signOneShot rather than update()+sign(): Ed25519 needs the whole message twice over,
            // and EdDSAEngine's one-shot path is the one the library documents as the fast one.
            signOneShot(message)
        }
}
