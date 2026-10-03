package com.unciv.ui.popups

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onClick
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.images.ImageGetter

/** Discover the fork's additions without connecting a wallet or starting a transaction. */
class UnwritAgesFeaturesPopup(stage: Stage, onDismiss: () -> Unit = {}) : Popup(stage) {
    private val content = Table(BaseScreen.skin)
    private val textWidth = minOf(stage.width * 0.72f, 720f)
    private var index = 0
    private val certificateTextures = mutableMapOf<String, Texture>()
    private val skipButton = "Skip".toTextButton().apply {
        onClick {
            close()
            ToastPopup("Replay anytime: Guide > Sharing & keepsakes.".tr(), stage, 4000)
        }
        keyShortcuts.add(KeyCharAndCode.BACK)
    }
    private val backButton = addButton("Back") { showPage(index - 1) }.actor
    private val nextButton = addButton("Next") {
        if (index == pages.lastIndex) close() else showPage(index + 1)
    }.actor

    init {
        add(content).width(textWidth).row()
        clickBehindToClose = false
        closeListeners.add(onDismiss)
        closeListeners.add { certificateTextures.values.forEach { it.dispose() }; certificateTextures.clear() }
        showPage(0)
    }

    private fun showPage(pageIndex: Int) {
        index = pageIndex
        content.clearChildren()
        content.defaults().pad(4f).fillX()
        val header = Table(BaseScreen.skin)
        header.add(("Unwrit Ages features".tr() + "  ${index + 1}/${pages.size}")
            .toLabel(accent, 18)).growX().left()
        header.add(skipButton).width(72f).height(32f).padLeft(20f).right()
        content.add(header).width(textWidth).row()
        if (index == 3) addAnchorComparison() else addIntroContent(content, pages[index], introductions[index], textWidth)
        backButton.isDisabled = index == 0
        nextButton.setText((if (index == pages.lastIndex) "Got it" else "Next").tr())
        innerTable.invalidateHierarchy()
        pack()
        getScrollPane()?.apply {
            scrollY = 0f
            updateVisualScroll()
        }
        fitOrCenterContentIntoVisibleArea()
    }

    private fun addAnchorComparison() {
        content.add("Anchor for a gilded certificate".toLabel(accent, 28)).width(textWidth).row()
        val body = Table(BaseScreen.skin)
        val comparison = Table(BaseScreen.skin)
        val cardWidth = textWidth * 0.36f / 2f - 8f
        for ((file, caption) in listOf("stele.jpg" to "Standard certificate", "stele_anchored.jpg" to "Gilded certificate")) {
            val texture = certificateTextures.getOrPut(file) {
                Texture(Gdx.files.internal("certificate/$file")).apply {
                    setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
                }
            }
            val card = Table(BaseScreen.skin)
            card.add(Image(texture)).size(cardWidth).row()
            card.add(caption.toLabel(if (file == "stele_anchored.jpg") accent else Color.LIGHT_GRAY, 18).apply {
                wrap = true; setAlignment(Align.center)
            }).width(cardWidth).padTop(5f)
            comparison.add(card).padRight(8f).top()
        }
        body.add(comparison).width(textWidth * 0.36f).top()
        val explanation = Table(BaseScreen.skin)
        val width = textWidth * 0.64f - 20f
        for (point in listOf("Record only your starting point on-chain.",
            "Only SOL network fees.", "Win and mint with the same wallet.")) {
            explanation.add("•".toLabel(accent, 20)).top().padRight(8f).padBottom(5f)
            explanation.add(point.toLabel(fontSize = 20).apply { wrap = true }).width(width - 20f).padBottom(5f).row()
        }
        body.add(explanation).width(width).top()
        content.add(body).width(textWidth).row()
        content.add("Route: New game > Anchor the start on-chain.".toLabel(Color.LIGHT_GRAY, 18).apply {
            wrap = true
        }).width(textWidth).row()
    }

    companion object {
        /** Independent of the app version: upgrades see new feature introductions once. */
        const val CURRENT_VERSION = 1
        private val accent = Color.valueOf("d3ac6c")
        private class Feature(val title: String, val body: String, val location: String)
        private class Intro(val icons: List<Pair<String, String>>, val points: List<String>)
        private val introductions = listOf(
            Intro(listOf("Shield" to "Fortify all", "Resume" to "Repeat", "Cities" to "Auto production"), listOf(
                "Build your civilization offline.",
                "Spend fewer taps on routine work.",
                "Guide: learn in a practice game.")),
            Intro(listOf("Load" to "Save", "Link" to "Same wallet", "Load" to "Restore"), listOf(
                "Private saves: encrypted for your wallet.",
                "1 SKR per save + SOL network fee.",
                "Route: Load game > Restore from the chain.")),
            Intro(listOf("Load" to "Load", "Resume" to "Play", "Link" to "Share"), listOf(
                "Continue another player's civilization.",
                "Share publicly: 1 SKR + SOL network fee.",
                "Route: Shared saves > View continuations.")),
            Intro(listOf("New" to "New game", "Link" to "Approve", "Checkmark" to "Start record"), listOf(
                "Record only your starting point on-chain.",
                "Only the SOL network fee.",
                "Route: New game > Anchor the start on-chain.")),
            Intro(listOf("Capital" to "Win", "Banner" to "Certificate", "Link" to "Your wallet"), listOf(
                "Collect your victory certificate as an NFT.",
                "US$0.90 in SOL or SKR + network fee.",
                "Mint your certificate after winning."))
        )
        private val pages = listOf(
            Feature("Your empire, your choice",
                "Play the full game offline without a wallet. Use Fortify all, Repeat and automatic city production to spend fewer taps on routine work.",
                "The Guide teaches the basics; the following pages introduce optional sharing and keepsakes."),
            Feature("Carry your game to another phone",
                "Record a cloud save for 1 SKR plus the SOL network fee. Private saves are encrypted for your wallet; the same wallet can restore them on another phone.",
                "In Wallet, connect and enable on-chain saves. Use Load game > Restore from the chain to recover them."),
            Feature("Share and continue saves",
                "Browse and load Shared saves for free, without a wallet. Continue another player's empire. Connect a wallet only if you want to share your own save or tip its author in SKR.",
                "Open Shared saves from the main menu. View continuations shows saves continued from the one you selected. Sharing costs 1 SKR plus the SOL network fee; shared saves are public."),
            Feature("Record your starting point",
                "An optional on-chain start record links your wallet to the map's starting seed. It records the beginning; it does not verify every turn or prevent cheating.",
                "When starting a new game, choose Anchor the start on-chain. You approve a wallet transaction and pay the SOL network fee."),
            Feature("Keep a victory",
                "After winning, you can mint a painted victory certificate to your wallet. It is an optional keepsake, priced at US$0.90 in SOL or SKR, plus the SOL network fee.",
                "Choose whether to mint on the victory screen. You can also finish the game without minting anything.")
        )

        /** Optional sharing and keepsakes stay available without repeating the basic gameplay chapter. */
        fun guidePage(width: Float): Table {
            val table = Table(BaseScreen.skin)
            table.pad(10f)
            table.defaults().pad(8f).fillX()
            val textWidth = width - 60f
            table.add("Replay feature introduction".toTextButton().apply {
                onClick { stage?.let { UnwritAgesFeaturesPopup(it).open(true) } }
            }).width(textWidth).padBottom(12f).row()
            for ((index, feature) in pages.drop(1).withIndex()) {
                if (index > 0) table.addSeparator(Color.GRAY).padTop(12f).padBottom(12f)
                addPageContent(table, feature, textWidth)
            }
            return table
        }

        private fun addPageContent(table: Table, feature: Feature, width: Float) {
            table.add(feature.title.toLabel(accent, 30).apply { wrap = true }).width(width).row()
            table.add(feature.body.toLabel(fontSize = 24).apply { wrap = true }).width(width).row()
            table.add(feature.location.toLabel(Color.LIGHT_GRAY, 21).apply { wrap = true }).width(width).row()
        }

        /** A glanceable overview; the Guide retains the detailed instructions above. */
        private fun addIntroContent(table: Table, feature: Feature, intro: Intro, width: Float) {
            table.add(feature.title.toLabel(accent, 28).apply { wrap = true; setAlignment(Align.center) })
                .width(width).row()
            val visual = Table(BaseScreen.skin)
            for ((icon, caption) in intro.icons) {
                val step = Table(BaseScreen.skin)
                step.add(ImageGetter.getImage("OtherIcons/$icon").apply { color = accent }).size(30f).row()
                step.add(caption.toLabel(Color.LIGHT_GRAY, 18).apply { wrap = true; setAlignment(Align.center) })
                    .width(width / 3f - 16f).padTop(3f)
                visual.add(step).width(width / 3f).top()
            }
            table.add(visual).width(width).padBottom(6f).row()
            val bullets = Table(BaseScreen.skin)
            for (point in intro.points) {
                bullets.add("•".toLabel(accent, 22)).top().padRight(12f).padBottom(4f)
                bullets.add(point.toLabel(fontSize = 22).apply { wrap = true })
                    .width(width - 30f).left().padBottom(4f).row()
            }
            table.add(bullets).width(width).row()
        }
    }
}
