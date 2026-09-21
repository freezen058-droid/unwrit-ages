package com.unciv.logic.chain

import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.files.UncivFiles

/**
 * Turns a won game into a certificate: assemble the record, hand the save and a metadata builder to
 * the platform's wallet, remember what got uploaded.
 *
 * The remembering is the point. Storage and minting are paid for separately, so an upload that
 * succeeded before a mint that failed is money already spent. The URI is written to settings the
 * moment it comes back, keyed by `gameId`, and a retry reuses it - so a player who loses signal
 * halfway through pays once, not twice.
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
        imageUri: String,
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
                    mintConnected(gameInfo, imageUri, record, onProgress, onSuccess, onError)
                },
                onError = onError
            )
            return
        }
        mintConnected(gameInfo, imageUri, record, onProgress, onSuccess, onError)
    }

    /** The mint itself, with a connected wallet guaranteed and the record already assembled. */
    private fun mintConnected(
        gameInfo: GameInfo,
        imageUri: String,
        record: VictoryCertificate.Record,
        onProgress: (String) -> Unit,
        onSuccess: (assetAddress: String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        // Force the zipped form regardless of the player's setting: this is the copy that has to
        // travel and be paid for by the byte, not the one they read in a text editor.
        val saveData = UncivFiles.gameInfoToString(gameInfo, forceZip = true)
            .toByteArray(Charsets.UTF_8)

        val name = "${record.winner} - ${record.victoryType} T${record.victoryTurn}"

        ChainWallet.service.mintVictoryCertificate(
            certificateName = name,
            saveData = saveData,
            alreadyUploadedSaveUri = alreadyMintedUpload(gameInfo),
            buildMetadata = { saveUri, image ->
                VictoryCertificate.metadataJson(record, image.ifEmpty { imageUri }, saveUri)
            },
            buildInlineMetadata = { image, withDescription ->
                VictoryCertificate.compactMetadataJson(record, image.ifEmpty { imageUri }, withDescription)
            },
            onProgress = onProgress,
            onSuccess = { assetAddress, saveUri ->
                remember(gameInfo.gameId, saveUri)
                onSuccess(assetAddress)
            },
            onError = { ex ->
                // The upload may well have succeeded before the mint failed; the platform reports
                // the URI through onSuccess only, so nothing to record here - but do not clear what
                // is already remembered, which is what makes the retry cheap.
                onError(ex)
            }
        )
    }

    /** Called by the platform as soon as an upload lands, so a later failure cannot lose it. */
    fun remember(gameId: String, saveUri: String) {
        if (saveUri.isEmpty()) return
        val settings = UncivGame.Current.settings
        if (settings.uploadedCertificateSaves[gameId] == saveUri) return
        settings.uploadedCertificateSaves[gameId] = saveUri
        settings.save()
    }
}
