package com.unciv.logic.chain

/**
 * Contract for platform-specific wallet integration (e.g. Solana Mobile Wallet Adapter on
 * Android). Mirrors the [com.unciv.logic.files.PlatformSaverLoader] pattern: core defines the
 * contract and a no-op default, each platform module supplies a real implementation and wires
 * it in at launch via [ChainWallet.service].
 */
interface PlatformWalletService {

    /** Whether wallet connection is supported on this platform/build at all. */
    val isAvailable: Boolean

    /** Currently connected wallet address (base58), or null if not connected. */
    val connectedAddress: String?

    fun connect(
        onConnected: (address: String) -> Unit,
        onError: (Exception) -> Unit = {}
    )

    fun disconnect()

    /**
     * Submits [hashHex] (a hex-encoded hash of a save file) as a Memo-program transaction signed
     * by the connected wallet, so the existence of that exact save at that point in time can be
     * verified on-chain later. Does not upload the save itself - saves stay in normal local/cloud
     * storage, this is a lightweight proof-of-existence only. [saveName] is the player-chosen save
     * name (not just [gameId], an opaque UUID) so the on-chain record is human-identifiable.
     */
    fun recordSaveHash(
        gameId: String,
        saveName: String,
        hashHex: String,
        onSuccess: (txSignature: String) -> Unit,
        onError: (Exception) -> Unit = {}
    )

    /**
     * Stores [saveData] permanently, mints a victory certificate owned by the connected wallet, and
     * reports the asset's address.
     *
     * The order is fixed and the reason is money: the storage upload and the mint are paid for
     * separately, so the upload must complete and its URI be handed back through
     * [alreadyUploadedSaveUri] / [buildMetadata] before the mint is attempted. A mint that fails can
     * then be retried without paying to store the same save again.
     *
     * @param alreadyUploadedSaveUri set when a previous attempt got as far as uploading; the
     *        implementation must skip the upload and reuse it.
     * @param buildMetadata called once the save's URI is known - returns the metadata JSON to store
     *        and point the asset at.
     * @param onProgress a short user-facing line: uploading, minting, done.
     * @param onSuccess the minted asset's address, and the save URI, which the caller records so a
     *        later retry can skip the upload.
     */
    fun mintVictoryCertificate(
        certificateName: String,
        saveData: ByteArray,
        alreadyUploadedSaveUri: String?,
        buildMetadata: (saveUri: String, imageUri: String) -> String,
        onProgress: (String) -> Unit = {},
        onSuccess: (assetAddress: String, saveUri: String) -> Unit,
        onError: (Exception) -> Unit = {}
    )

    companion object {
        val None = object : PlatformWalletService {
            override val isAvailable = false
            override val connectedAddress: String? = null
            override fun connect(onConnected: (address: String) -> Unit, onError: (Exception) -> Unit) {
                onError(UnsupportedOperationException("Wallet integration is not available on this platform"))
            }
            override fun disconnect() {}
            override fun recordSaveHash(
                gameId: String,
                saveName: String,
                hashHex: String,
                onSuccess: (txSignature: String) -> Unit,
                onError: (Exception) -> Unit
            ) {
                onError(UnsupportedOperationException("Wallet integration is not available on this platform"))
            }
            override fun mintVictoryCertificate(
                certificateName: String,
                saveData: ByteArray,
                alreadyUploadedSaveUri: String?,
                buildMetadata: (saveUri: String, imageUri: String) -> String,
                onProgress: (String) -> Unit,
                onSuccess: (assetAddress: String, saveUri: String) -> Unit,
                onError: (Exception) -> Unit
            ) {
                onError(UnsupportedOperationException("Wallet integration is not available on this platform"))
            }
        }
    }
}
