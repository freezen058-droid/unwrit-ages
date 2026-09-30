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
     *  for checking the look on a device. Null where the platform cannot draw one.
     *  [anchored] picks the anchored stele (see [StartAnchor]). */
    fun renderCertificate(inscription: List<VictoryCertificate.InscriptionLine>, emblem: CertificateEmblem, anchored: Boolean): ByteArray? = null

    /**
     * Signs and sends the memo [StartAnchor.memo] for [gameId] with the connected wallet - no fee
     * beyond the network's - and calls [onSuccess] only once the network has confirmed it, since
     * the map is generated from the signature and a start that never landed must not claim one.
     */
    fun anchorGameStart(
        gameId: String,
        onSuccess: (txSignature: String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        onError(UnsupportedOperationException("Wallet integration is not available on this platform"))
    }

    fun connect(
        onConnected: (address: String) -> Unit,
        onError: (Exception) -> Unit = {}
    )

    fun disconnect()

    /**
     * Submits [record]'s memo ([CloudSave.Record.memo]) as a Memo-program transaction signed by
     * the connected wallet, with the 1 SKR fee in the same transaction, so that exact save at that
     * point in time can be verified on-chain later. Since cloud saves the record also names the
     * Arweave uploads of the save itself ([uploadCloudSave]); a record without them is the 1.0.0
     * proof-of-existence only.
     */
    fun recordSaveHash(
        record: CloudSave.Record,
        onSuccess: (txSignature: String) -> Unit,
        onError: (Exception) -> Unit = {}
    )

    /** The connected wallet's cloud-save key ([CloudSave.keyFromSignature] of its signature over
     *  [CloudSave.KEY_MESSAGE]). Asks the wallet once; kept in memory only, per address. */
    fun cloudSaveKey(onSuccess: (key: ByteArray) -> Unit, onError: (Exception) -> Unit) {
        onError(UnsupportedOperationException("Wallet integration is not available on this platform"))
    }

    /** Stores [data] on Arweave in [CloudSave.chunks], each a free upload; returns their ids in order. */
    fun uploadCloudSave(data: ByteArray, onSuccess: (arweaveIds: List<String>) -> Unit, onError: (Exception) -> Unit) {
        onError(UnsupportedOperationException("Wallet integration is not available on this platform"))
    }

    /** The recorded saves, newest first: the connected wallet's own (private and shared), or
     *  when [shared] every player's shared ones - read from the transactions that paid the fee. */
    fun listSaveRecords(shared: Boolean, onSuccess: (List<CloudSave.Record>) -> Unit, onError: (Exception) -> Unit) {
        onError(UnsupportedOperationException("Wallet integration is not available on this platform"))
    }

    /** The bytes [uploadCloudSave] stored under [arweaveIds], joined back in order. */
    fun downloadCloudSave(arweaveIds: List<String>, onSuccess: (ByteArray) -> Unit, onError: (Exception) -> Unit) {
        onError(UnsupportedOperationException("Wallet integration is not available on this platform"))
    }

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
     * @param anchored draw the anchored stele - the game's origin is [StartAnchor.ORIGIN_ANCHORED].
     * @param payment which token the fee is paid in; the player picks it before the wallet opens.
     * @param buildMetadata called with the picture's permanent URI - returns the metadata JSON.
     * @param onUploaded the metadata's permanent URI, as soon as it exists, so the caller can record it.
     * @param onSuccess the minted asset's address.
     */
    fun mintVictoryCertificate(
        certificateName: String,
        inscription: List<VictoryCertificate.InscriptionLine>,
        emblem: CertificateEmblem,
        anchored: Boolean,
        alreadyUploadedMetadataUri: String?,
        payment: CertificatePayment,
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
                record: CloudSave.Record,
                onSuccess: (txSignature: String) -> Unit,
                onError: (Exception) -> Unit
            ) {
                onError(UnsupportedOperationException("Wallet integration is not available on this platform"))
            }
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
                onError(UnsupportedOperationException("Wallet integration is not available on this platform"))
            }
        }
    }
}

/** The winner's nation for the stele's medallion: its name finds the icon, the colours paint it. */
data class CertificateEmblem(val nation: String, val outer: List<Int>, val inner: List<Int>)

/** What the certificate's fee is paid in. The same US-dollar fee either way, converted at mint time. */
enum class CertificatePayment { SOL, SKR }
