package com.unciv.ui.popups

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.screens.basescreen.BaseScreen

/** Discover the fork's additions without connecting a wallet or starting a transaction. */
class UnwritAgesFeaturesPopup(stage: Stage, onDismiss: () -> Unit = {}) : Popup(stage) {
    private val content = Table(BaseScreen.skin)
    private val textWidth = minOf(stage.width * 0.72f, 720f)
    private var index = 0
    private val backButton = addButton("Back") { showPage(index - 1) }.actor
    private val nextButton = addButton("Next") {
        if (index == pages.lastIndex) close() else showPage(index + 1)
    }.actor

    init {
        add(content).width(textWidth).row()
        addCloseButton("Skip")
        clickBehindToClose = false
        closeListeners.add(onDismiss)
        showPage(0)
    }

    private fun showPage(pageIndex: Int) {
        index = pageIndex
        content.clearChildren()
        content.defaults().pad(8f).fillX()
        content.add(("Unwrit Ages features".tr() + "  ${index + 1}/${pages.size}")
            .toLabel(accent, 20)).width(textWidth).row()
        addPageContent(content, pages[index], textWidth)
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

    companion object {
        /** Independent of the app version: upgrades see new feature introductions once. */
        const val CURRENT_VERSION = 1
        private val accent = Color.valueOf("d3ac6c")
        private class Feature(val title: String, val body: String, val location: String)
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
    }
}
