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
            ToastPopup("Explore anytime: Guide > Your journey.".tr(), stage, 4000)
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
        header.add(("Shape your civilization".tr() + "  ${index + 1}/${pages.size}")
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
        content.add("Mark your civilization's beginning".toLabel(accent, 28)).width(textWidth).row()
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
        for (point in listOf("Anchor your civilization's beginning on-chain: SOL network fee only.",
            "After victory, mint your certificate as an NFT: US$0.90 in SOL or SKR + network fee.")) {
            explanation.add("•".toLabel(accent, 20)).top().padRight(8f).padBottom(5f)
            explanation.add(point.toLabel(fontSize = 20).apply { wrap = true }).width(width - 20f).padBottom(5f).row()
        }
        body.add(explanation).width(width).top()
        content.add(body).width(textWidth).row()
        content.add("Gilded: anchor, win and mint with the same wallet. Autorun turns are recorded.".toLabel(Color.LIGHT_GRAY, 18).apply {
            wrap = true
        }).width(textWidth).row()
        content.add("Route: New game > Anchor the start on-chain.".toLabel(Color.LIGHT_GRAY, 18).apply {
            wrap = true
        }).width(textWidth).row()
    }

    companion object {
        /** Independent of the app version: upgrades see new feature introductions once. */
        const val CURRENT_VERSION = 1
        private const val GUIDE_NOTE_SIZE = 21
        private val accent = Color.valueOf("d3ac6c")
        private class Feature(val title: String, val body: String, val location: String)
        private class Intro(val icons: List<Pair<String, String>>, val points: List<String>)
        private val introductions = listOf(
            Intro(listOf("Shield" to "Fortify all", "Resume" to "Repeat", "Cities" to "Auto production"), listOf(
                "Build your civilization offline.",
                "Command more. Repeat less.",
                "Guide: begin with a practice game.")),
            Intro(listOf("Load" to "Save", "Link" to "Same wallet", "Load" to "Restore"), listOf(
                "Private saves: encrypted for your wallet.",
                "1 SKR per save + SOL network fee.",
                "Route: Load game > Restore from the chain.")),
            Intro(listOf("Load" to "Load", "Resume" to "Play", "Link" to "Share"), listOf(
                "Take another player's civilization further.",
                "Share publicly: 1 SKR + SOL network fee.",
                "Route: Shared saves > View continuations.")),
            Intro(listOf("New" to "New game", "Link" to "Approve", "Checkmark" to "Start record"), listOf(
                "Mark the beginning of your civilization on-chain.",
                "Anchor: SOL network fee only.",
                "Route: New game > Anchor the start on-chain.")),
            Intro(listOf("Capital" to "Win", "Banner" to "Certificate", "Link" to "Your wallet"), listOf(
                "After victory, mint your certificate as an NFT.",
                "US$0.90 in SOL or SKR + network fee.",
                "Standard or gilded: the same minting price."))
        )
        private val allFeatures = listOf(
            Feature("Lead your civilization",
                "Play the full game offline without a wallet. Use Fortify all, Repeat and automatic city production to spend fewer taps on routine work.",
                "The Guide teaches the basics; the following pages introduce optional sharing and keepsakes."),
            Feature("Carry your civilization with you",
                "Record a cloud save for 1 SKR plus the SOL network fee. Private saves are encrypted for your wallet; the same wallet can restore them on another phone.",
                "In Wallet, connect and enable on-chain saves. Use Load game > Restore from the chain to recover them."),
            Feature("Continue a civilization's story",
                "Browse and load Shared saves for free, without a wallet. Continue another player's empire. Connect a wallet only if you want to share your own save or tip its author in SKR.",
                "Open Shared saves from the main menu. View continuations shows saves continued from the one you selected. Sharing costs 1 SKR plus the SOL network fee; shared saves are public."),
            Feature("Mark your civilization's beginning",
                "Mark your civilization's beginning with your wallet. Win and mint with the same wallet for a gilded certificate. The start record links your wallet to the starting map; it does not verify every turn or prevent cheating. Continuing another player's save uses a standard certificate.",
                "When starting a new game, choose Anchor the start on-chain. Anchor costs only the SOL network fee. Certificate minting is a separate purchase after victory."),
            Feature("Make your victory a keepsake",
                "After winning, you can mint a painted victory certificate to your wallet. It is an optional keepsake, priced at US$0.90 in SOL or SKR, plus the SOL network fee.",
                "Choose whether to mint on the victory screen. You can also finish the game without minting anything.")
        )

        private val pages = allFeatures.take(4)

        /** Optional sharing and keepsakes stay available without repeating the basic gameplay chapter. */
        fun guidePage(width: Float, onTextureCreated: (Texture) -> Unit): Table {
            val table = Table(BaseScreen.skin)
            table.pad(10f)
            table.defaults().pad(8f).fillX()
            val textWidth = width - 60f
            val enabledPacks = com.unciv.logic.chain.ChallengePacks.enabled()
            if (enabledPacks.isNotEmpty()) {
                val entry = "New goals unlocked!".toTextButton().apply {
                    name = "GuideNewGoals"
                    style = com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle(style).apply {
                        up = BaseScreen.skinStrings.getUiBackground(
                            "TutorialGuide/PlayButton", BaseScreen.skinStrings.roundedEdgeRectangleShape, accent)
                        down = BaseScreen.skinStrings.getUiBackground(
                            "TutorialGuide/PlayButton", BaseScreen.skinStrings.roundedEdgeRectangleShape,
                            accent.cpy().mul(0.85f).apply { a = 1f })
                        over = down
                    }
                    clearChildren()
                    pad(14f, 36f, 14f, 36f)
                    add(ImageGetter.getImage("OtherIcons/Quickstart").apply { color = Color.BLACK })
                        .size(34f).padRight(12f)
                    add("New goals unlocked!".toLabel(Color.BLACK, 30))
                    onClick {
                        val screen = com.unciv.UncivGame.Current.getScreen() ?: return@onClick
                        ChallengePacksPopup(screen, enabledPacks) {}.open(true)
                    }
                }
                table.add(entry).width(minOf(textWidth, 500f)).minHeight(64f).padBottom(8f).row()
                val language = com.unciv.UncivGame.Current.settings.language
                table.add(enabledPacks.flatMap { it.goals }.joinToString(" · ") { it.displayLabel(language) }
                    .toLabel(Color.LIGHT_GRAY, GUIDE_NOTE_SIZE).apply { wrap = true }).width(textWidth).left().row()
                table.addSeparator(Color.GRAY).padTop(12f).padBottom(12f)
            }
            addSharedSaveGuide(table, textWidth)
            table.addSeparator(Color.GRAY).padTop(12f).padBottom(12f)
            addGuideSection(table, textWidth, "Load", "Carry your civilization with you", listOf(
                "Private saves: encrypted for your wallet.",
                "Save: Wallet > Enable on-chain saves > Save game.",
                "Restore: Load game > Restore from the chain. Use the same wallet.",
                "1 SKR per save + SOL network fee."))
            table.addSeparator(Color.GRAY).padTop(12f).padBottom(12f)
            addGuideSection(table, textWidth, "Banner", "Mark your civilization's beginning", listOf(
                "Anchor your civilization's beginning on-chain: SOL network fee only.",
                "After victory, mint your certificate as an NFT: US$0.90 in SOL or SKR + network fee.",
                "Route: New game > Anchor the start on-chain.")) { destination, contentWidth ->
                addCertificateComparison(destination, contentWidth, onTextureCreated)
            }
            table.add("Gilded: anchor, win and mint with the same wallet. Autorun turns are recorded."
                .toLabel(Color.LIGHT_GRAY, GUIDE_NOTE_SIZE).apply { wrap = true }).width(textWidth).row()
            return table
        }

        private fun addCertificateComparison(table: Table, width: Float, onTextureCreated: (Texture) -> Unit) {
            val comparison = Table(BaseScreen.skin)
            val imageSize = minOf(140f, (width - 32f) / 2f)
            for ((file, caption) in listOf("stele.jpg" to "Standard certificate", "stele_anchored.jpg" to "Gilded certificate")) {
                val texture = Texture(Gdx.files.internal("certificate/$file")).apply {
                    setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
                }
                onTextureCreated(texture)
                val item = Table(BaseScreen.skin)
                item.add(Image(texture)).size(imageSize).row()
                item.add(caption.toLabel(if (file == "stele_anchored.jpg") accent else Color.LIGHT_GRAY, GUIDE_NOTE_SIZE)
                    .apply { wrap = true; setAlignment(Align.center) }).width(imageSize + 12f).padTop(8f)
                comparison.add(item).pad(0f, 8f, 0f, 8f).top()
            }
            table.add(comparison).width(width).padBottom(12f).row()
        }

        private fun addGuideSection(table: Table, width: Float, icon: String, title: String, points: List<String>,
                                    visual: ((Table, Float) -> Unit)? = null) {
            val header = Table(BaseScreen.skin)
            header.add(ImageGetter.getImage("OtherIcons/$icon").apply { color = accent }).size(34f).padRight(14f).top()
            header.add(title.toLabel(accent, 28).apply { wrap = true }).width(width - 58f).left()
            table.add(header).width(width).padBottom(8f).row()
            visual?.invoke(table, width)
            for (point in points) {
                val line = Table(BaseScreen.skin)
                line.add("\u2022".toLabel(accent, 22)).top().padRight(10f)
                line.add(point.toLabel(fontSize = 22).apply { wrap = true }).width(width - 24f).left()
                table.add(line).width(width).padBottom(6f).row()
            }
        }

        /** Three visual steps, kept in the existing sharing tab. */
        private fun addSharedSaveGuide(table: Table, width: Float) {
            table.add("Continue a civilization's story".toLabel(accent, 30).apply { wrap = true })
                .width(width).row()
            fun step(icon: String, title: String, points: List<String>) {
                val card = Table(BaseScreen.skin)
                card.defaults().padBottom(6f)
                card.add(ImageGetter.getImage("OtherIcons/$icon").apply { color = accent })
                    .size(34f).padRight(14f).top()
                val words = Table(BaseScreen.skin)
                val lineWidth = width - 58f
                words.add(title.toLabel(accent, 25).apply { wrap = true }).width(lineWidth).left().row()
                for (point in points) {
                    val row = Table(BaseScreen.skin)
                    row.add("•".toLabel(accent, 22)).top().padRight(10f)
                    row.add(point.toLabel(fontSize = 22).apply { wrap = true }).width(lineWidth - 24f).left()
                    words.add(row).width(lineWidth).padTop(5f).row()
                }
                card.add(words).width(lineWidth).left()
                table.add(card).width(width).padTop(10f).row()
            }
            step("Load", "Take command", listOf(
                "Shared saves: choose a world, check its briefing, then load it.",
                "Open Scenario goals above the map to see your chapter and remaining turns."))
            step("Checkmark", "Write the next chapter", listOf(
                "Meet the goals before time runs out. Success opens the next chapter.",
                "If the challenge ends, retry or Continue playing your civilization."))
            step("Link", "Create a challenge for the next leader", listOf(
                "Save game > Set goals for the next player.",
                "Up to 3 chapters, 3 goals each. Use + Goal and Add chapter.",
                "Select Share this save, then save and approve in your wallet."))
            table.add("Sharing: 1 SKR + SOL network fee. Shared saves are public.".toLabel(Color.LIGHT_GRAY, GUIDE_NOTE_SIZE)
                .apply { wrap = true }).width(width).padTop(10f).row()
            table.add("View continuations follows the next leaders in this world's story.".toLabel(Color.LIGHT_GRAY, GUIDE_NOTE_SIZE)
                .apply { wrap = true }).width(width).row()
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
