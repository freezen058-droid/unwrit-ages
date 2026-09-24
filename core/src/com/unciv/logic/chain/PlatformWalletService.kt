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

    /**
     * The service fee charged for one victory certificate, in US cents, so the player can be told
     * the price before they agree to it. Zero where certificates are not offered at all. In cents
     * rather than a currency-formatted string because the platform charges in a cryptocurrency and
     * converts at mint time - this is the stable number, not what leaves the wallet.
     */
    val certificateFeeUsdCents: Int get() = 0

    /** A block-explorer page for [address] on the cluster this service talks to, or null where
     *  there is none - so a minted certificate can be looked at, not just named. */
    fun explorerUrl(address: String): String? = null

    /** The certificate picture exactly as a mint would draw it, without uploading or minting -
     *  for checking the look on a device. Null where the platform cannot draw one. */
    fun renderCertificate(inscription: List<VictoryCertificate.InscriptionLine>, emblem: CertificateEmblem): ByteArray? = null

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
     * Draws the certificate - the stele with this game's inscription and emblem - stores the picture
     * and its metadata permanently, then mints the asset, owned by the connected wallet and pointing
     * at that metadata.
     *
     * The storage comes first and is remembered: a mint that fails is retried with
     * [alreadyUploadedMetadataUri] and must not upload again.
     *
     * @param inscription the lines to carve, top to bottom; see [VictoryCertificate.inscription].
     * @param emblem the winner's nation name and its two colours, for the medallion.
     * @param buildMetadata called with the picture's permanent URI - returns the metadata JSON.
     * @param onUploaded the metadata's permanent URI, as soon as it exists, so the caller can record it.
     * @param onSuccess the minted asset's address.
     */
    fun mintVictoryCertificate(
        certificateName: String,
        inscription: List<VictoryCertificate.InscriptionLine>,
        emblem: CertificateEmblem,
        alreadyUploadedMetadataUri: String?,
        buildMetadata: (imageUri: String) -> String,
        onUploaded: (metadataUri: String) -> Unit,
        onProgress: (String) -> Unit = {},
        onSuccess: (assetAddress: String) -> Unit,
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
                inscription: List<VictoryCertificate.InscriptionLine>,
                emblem: CertificateEmblem,
                alreadyUploadedMetadataUri: String?,
                buildMetadata: (imageUri: String) -> String,
                onUploaded: (metadataUri: String) -> Unit,
                onProgress: (String) -> Unit,
                onSuccess: (assetAddress: String) -> Unit,
                onError: (Exception) -> Unit
            ) {
                onError(UnsupportedOperationException("Wallet integration is not available on this platform"))
            }
        }
    }
}

/** The winner's nation for the stele's medallion: its name finds the icon, the colours paint it. */
data class CertificateEmblem(val nation: String, val outer: List<Int>, val inner: List<Int>)
