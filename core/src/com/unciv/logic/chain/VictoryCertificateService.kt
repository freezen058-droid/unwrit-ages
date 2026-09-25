package com.unciv.logic.chain

import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization

/**
 * Turns a won game into a certificate: assemble the record and its inscription, hand them to the
 * platform's wallet, remember what got uploaded.
 *
 * The remembering is the point: the picture and metadata are stored before the mint, and a mint
 * that then fails is retried against the same stored metadata rather than uploading again. The
 * URI is written to settings the moment it comes back, keyed by `gameId`.
 */
object VictoryCertificateService {

    /** Whether the game is in a state where a certificate means anything. */
    fun isAvailable(gameInfo: GameInfo, civ: Civilization): Boolean =
        gameInfo.victoryData != null
            && gameInfo.victoryData!!.winningCiv == civ.civID
            && ChainWallet.service.isAvailable

    fun alreadyMintedUpload(gameInfo: GameInfo): String? =
        UncivGame.Current.settings.uploadedCertificateSaves[gameInfo.gameId]

    /**
     * @param onProgress a short user-facing line for each stage.
     * @param onSuccess the minted asset's address.
     */
    fun mint(
        gameInfo: GameInfo,
        civ: Civilization,
        onProgress: (String) -> Unit,
        onSuccess: (assetAddress: String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val record = try {
            VictoryCertificate.record(gameInfo, civ)
        } catch (ex: Exception) {
            onError(ex); return
        }

        // The only place to connect a wallet is the main menu's WalletPopup, and this screen is
        // reached after hours of play - telling a winner to quit to the menu and come back is not
        // an option, so connect here and carry straight on into the mint. [isAvailable] has already
        // said the platform can do this; what is missing is only the authorization round-trip.
        if (!ChainWallet.isConnected) {
            ChainWallet.service.connect(
                onConnected = {
                    onProgress("Wallet connected")
                    mintConnected(gameInfo, record, onProgress, onSuccess, onError)
                },
                onError = onError
            )
            return
        }
        mintConnected(gameInfo, record, onProgress, onSuccess, onError)
    }

    /** The mint itself, with a connected wallet guaranteed and the record already assembled. */
    private fun mintConnected(
        gameInfo: GameInfo,
        record: VictoryCertificate.Record,
        onProgress: (String) -> Unit,
        onSuccess: (assetAddress: String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        ChainWallet.service.mintVictoryCertificate(
            certificateName = VictoryCertificate.NAME,
            inscription = VictoryCertificate.inscription(record),
            emblem = CertificateEmblem(record.nation, record.emblemOuter, record.emblemInner),
            alreadyUploadedMetadataUri = alreadyMintedUpload(gameInfo),
            buildMetadata = { imageUri -> VictoryCertificate.metadataJsonWithin(record, imageUri, METADATA_MAX_BYTES) },
            onUploaded = { remember(gameInfo.gameId, it) },
            onProgress = onProgress,
            onSuccess = onSuccess,
            onError = onError
        )
    }

    /** Turbo stores uploads up to 100 KiB free; a little under, for the envelope around the data. */
    const val METADATA_MAX_BYTES = 95 * 1024

    /** Called by the platform as soon as the metadata is stored, so a later failure cannot lose it. */
    fun remember(gameId: String, metadataUri: String) {
        if (metadataUri.isEmpty()) return
        val settings = UncivGame.Current.settings
        if (settings.uploadedCertificateSaves[gameId] == metadataUri) return
        settings.uploadedCertificateSaves[gameId] = metadataUri
        settings.save()
    }
}
