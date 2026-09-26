package com.unciv.ui.screens.victoryscreen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.logic.GameInfo
import com.unciv.logic.chain.CertificateEmblem
import com.unciv.logic.chain.ChainWallet
import com.unciv.logic.chain.VictoryCertificate
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
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread

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
        const val RETRY_HINT = "\nThe certificate is stored - retrying only mints."

        /** The offer on screen now. A mint outlives the offer that started it: coming back from the
         *  wallet rebuilds the victory screen, so its progress and result go to whichever offer is
         *  showing by then, not to one that is gone. */
        var current: VictoryCertificateOffer? = null
        /** The last progress line of a mint still running, for an offer built while it runs. */
        var mintStatus: String? = null
    }

    private val status = "".toLabel()
    private val button = "Mint victory certificate".toTextButton()
    private val picture = Table()
    private val result = Table()

    init {
        defaults().pad(4f)
        status.wrap = true
        status.setAlignment(Align.center)    // centred over the certificate below it
        button.onClick { mint() }
        add(button).row()
        add(status).width(500f).row()
        add(picture).row()
        add(result).row()
        current = this
        val address = gameInfo.certificateAddress
        val running = mintStatus
        if (address != null) showMinted(address)
        else if (running != null) {
            button.disable()
            status.setText(running)
        }
    }

    private fun mint() {
        button.disable()
        result.clear()
        // The service connects a wallet first if there is none, so say which of the two is
        // happening - "Preparing certificate" while a wallet dialog is coming up is a lie.
        // .tr() on every one of these: a Label only translates the text it was built with, and
        // these arrive later, from the platform, as the mint moves through its stages.
        progress(
            (if (ChainWallet.isConnected) "Preparing certificate..."
            else "Waiting for your wallet...").tr()
        )
        VictoryCertificateService.mint(
            gameInfo, civ,
            onProgress = { progress(it.tr()) },
            onSuccess = { address ->
                mintStatus = null
                (current ?: this).showMinted(address)
            },
            onError = {
                mintStatus = null
                val paid = VictoryCertificateService.alreadyMintedUpload(gameInfo) != null
                val offer = current ?: this
                offer.status.setText(
                    (it.localizedMessage ?: "Could not mint the certificate".tr()) +
                        (if (paid) RETRY_HINT.tr() else "")
                )
                offer.button.enable()
            }
        )
    }

    private fun progress(text: String) {
        mintStatus = text
        (current ?: this).status.setText(text)
    }

    /** Before: a line of small text holding a 44-character address, which a player who just
     *  approved a payment could not tell apart from an error. */
    private fun showMinted(address: String) {
        gameInfo.certificateAddress = address
        button.isVisible = false
        result.clear()
        status.setText("Congratulations! Your victory certificate is in your wallet!".tr())
        showPicture()
        val url = ChainWallet.service.explorerUrl(address)
        if (url != null) {
            val view = "View certificate".toTextButton()
            view.onClick { Gdx.net.openURI(url) }
            result.add(view)
        }
        onMinted()
    }

    /** The certificate itself, here in the game and not only in the wallet. Drawn again rather
     *  than fetched: the same renderer and the same record give the picture that was uploaded,
     *  without a network round trip - and a platform that cannot draw it simply shows none. */
    private fun showPicture() {
        val record = VictoryCertificate.record(gameInfo, civ)
        Concurrency.run("CertificatePicture") {
            val jpeg = ChainWallet.service.renderCertificate(
                VictoryCertificate.inscription(record),
                CertificateEmblem(record.nation, record.emblemOuter, record.emblemInner),
                record.anchored
            ) ?: return@run
            launchOnGLThread {
                val pixmap = Pixmap(jpeg, 0, jpeg.size)
                val texture = Texture(pixmap).apply { setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear) }
                pixmap.dispose()
                val stage = stage ?: return@launchOnGLThread texture.dispose()
                val image = Image(texture)
                image.onClick { CertificatePicturePopup(stage, texture) }
                picture.clear()
                picture.add(image).size(stage.height * 0.5f)
                picture.invalidateHierarchy()
                // A popup is sized and centred once, when it opens - before this picture existed -
                // so it grew upwards from where it stood. Size and centre it again around it.
                firstAscendant(Popup::class.java)?.run {
                    pack()
                    setPosition((stage.width - width) / 2, (stage.height - height) / 2)
                }
            }
        }
    }
}

/** The certificate at the size of the screen; the texture belongs to the offer, not to this. */
private class CertificatePicturePopup(stage: Stage, texture: Texture) : Popup(stage) {
    init {
        val side = stageToShowOn.height * 0.8f
        add(Image(texture)).size(side).row()
        addCloseButton()
        open(force = true)
    }
}

/** Offered once, the moment the game is won - the tab below the fold is where it waited before,
 *  and nobody scrolled there to find it. */
class VictoryCertificatePopup(screen: BaseScreen, gameInfo: GameInfo, civ: Civilization) : Popup(screen) {
    init {
        // The question and the "later" note only make sense before the mint: a minted certificate
        // opens without them, and a mint made here takes them away when it lands.
        val minted = gameInfo.certificateAddress != null
        val question = if (minted) null
            else addGoodSizedLabel("You won. Keep this victory as a certificate in your wallet?").apply { row() }
        var note: Cell<Label>? = null
        add(VictoryCertificateOffer(gameInfo, civ, onMinted = {
            for (cell in listOfNotNull(question, note)) cell.clearActor().pad(0f)
            invalidateHierarchy()
        })).row()
        if (!minted)
            note = addGoodSizedLabel("You can also do this later, with the gold button at the bottom right of the victory screen.",
                size = Constants.defaultFontSize - 4).padTop(8f).apply { row() }
        addCloseButton()
    }
}
