package com.unciv.ui.screens.victoryscreen

import com.badlogic.gdx.Gdx
import com.unciv.Constants
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.logic.GameInfo
import com.unciv.logic.chain.ChainWallet
import com.unciv.logic.chain.VictoryCertificateService
import com.unciv.logic.civilization.Civilization
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen

/**
 * The mint button with its progress line, and what follows a mint: a plain "done" and a way to
 * look at the result. One implementation for the victory tab and the popup that offers it the
 * moment the game is won, so the two cannot drift apart.
 *
 * No price line. The wallet's own approval sheet states exactly what leaves the wallet, before
 * anything is signed, and the user asked for the offer itself to stay free of it.
 */
class VictoryCertificateOffer(
    private val gameInfo: GameInfo,
    private val civ: Civilization,
    private val onMinted: () -> Unit = {}
) : Table(BaseScreen.skin) {

    private companion object {
        /** Said only when an upload is already paid for, because it changes what a retry costs. */
        const val RETRY_HINT = "\nThe world is stored - retrying only mints."
    }

    private val status = "".toLabel()
    private val button = "Mint victory certificate".toTextButton()
    private val result = Table()

    init {
        defaults().pad(4f)
        status.wrap = true
        button.onClick { mint() }
        add(button).row()
        add(status).width(500f).row()
        add(result).row()
        gameInfo.certificateAddress?.let { showMinted(it) }
    }

    private fun mint() {
        button.disable()
        result.clear()
        // The service connects a wallet first if there is none, so say which of the two is
        // happening - "Preparing certificate" while a wallet dialog is coming up is a lie.
        // .tr() on every one of these: a Label only translates the text it was built with, and
        // these arrive later, from the platform, as the mint moves through its stages.
        status.setText(
            (if (ChainWallet.isConnected) "Preparing certificate..."
            else "Waiting for your wallet...").tr()
        )
        VictoryCertificateService.mint(
            gameInfo, civ,
            imageUri = "",
            onProgress = { status.setText(it.tr()) },
            onSuccess = { address -> showMinted(address) },
            onError = {
                val paid = VictoryCertificateService.alreadyMintedUpload(gameInfo) != null
                status.setText(
                    (it.localizedMessage ?: "Could not mint the certificate".tr()) +
                        (if (paid) RETRY_HINT.tr() else "")
                )
                button.enable()
            }
        )
    }

    /** Before: a line of small text holding a 44-character address, which a player who just
     *  approved a payment could not tell apart from an error. */
    private fun showMinted(address: String) {
        gameInfo.certificateAddress = address
        button.isVisible = false
        result.clear()
        status.setText("Your victory certificate is in your wallet.".tr())
        val url = ChainWallet.service.explorerUrl(address)
        if (url != null) {
            val view = "View certificate".toTextButton()
            view.onClick { Gdx.net.openURI(url) }
            result.add(view)
        }
        onMinted()
    }
}

/** Offered once, the moment the game is won - the tab below the fold is where it waited before,
 *  and nobody scrolled there to find it. */
class VictoryCertificatePopup(screen: BaseScreen, gameInfo: GameInfo, civ: Civilization) : Popup(screen) {
    init {
        addGoodSizedLabel("You won. Keep this victory as a certificate in your wallet?").row()
        add(VictoryCertificateOffer(gameInfo, civ)).row()
        addGoodSizedLabel("You can also do this later, with the gold button at the bottom right of the victory screen.",
            size = Constants.defaultFontSize - 4).padTop(8f).row()
        addCloseButton("Not now")
    }
}
