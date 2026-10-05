package com.unciv.ui.popups

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.Align
import com.unciv.logic.GameInfo
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.WorldScreen

/** The challenge ends; the civilization remains available for free play. */
class ChallengeEndingPopup(screen: BaseScreen, game: GameInfo) : Popup(screen) {
    init {
        val scenario = requireNotNull(game.sharedScenario)
        val chapter = requireNotNull(scenario.chapter)
        val width = (screen.stage.width * 0.55f).coerceAtMost(620f)
        background = BaseScreen.skinStrings.getUiBackground("General/Popup/Background",
            tintColor = Color(0f, 0f, 0f, 0.55f))
        innerTable.background = BaseScreen.skinStrings.getUiBackground("SharedScenario/ChallengeEnding",
            tintColor = Color(0.19f, 0.08f, 0.08f, 1f))
        add(ImageGetter.getImage("OtherIcons/Shield").apply { color = Color(0.9f, 0.35f, 0.25f, 1f) })
            .size(32f).padTop(6f).padBottom(4f).row()
        add("Challenge failed".toLabel(fontColor = Color(1f, 0.46f, 0.32f, 1f), fontSize = 32,
            alignment = Align.center)).width(width).padBottom(6f).row()
        add("Challenge ended. Not every objective was completed.".toLabel(alignment = Align.center, fontSize = 20)
            .apply { wrap = true }).width(width).padBottom(8f).row()
        val missing = buildList {
            if (chapter.path == "renewal") {
                if (chapter.treasuryTurn < 0) add("Grow your treasury".tr())
                if (chapter.peaceTurn < 0) add("Secure peace".tr())
            } else {
                if (chapter.researchTurn < 0) add("Complete research".tr())
                if (chapter.constructionTurn < 0) add("Rebuild the market".tr())
            }
            if (chapter.holdTurn < 0) add("Hold your capital".tr())
        }
        add("Unfinished: [${missing.joinToString(" · ")}]".toLabel(fontColor = Color.LIGHT_GRAY,
            alignment = Align.center, fontSize = 20).apply { wrap = true }).width(width).padBottom(10f).row()
        add("Same beginning. New strategy. Try again.".toLabel(
            fontColor = Color.GOLD, alignment = Align.center, fontSize = 20).apply { wrap = true })
            .width(width).padBottom(6f).row()
        val primary = addButton("Retry challenge") { close(); FeaturedScenarioPopup(screen, previousResult = game) }
        primary.size(270f, 56f).colspan(2).padBottom(8f).row()
        primary.actor.label.setFontSize(22)
        val quiet = TextButton.TextButtonStyle(primary.actor.style).apply { fontColor = Color.LIGHT_GRAY }
        val details = addButton("View objectives", style = quiet) {
            close(); SharedScenarioPopup(screen, game, showChallengeDetails = true)
        }.height(42f).padRight(12f)
        details.actor.label.setFontSize(16)
        details.width(details.actor.prefWidth + 16f)
        val free = addButton("Free play", KeyCharAndCode.BACK, style = quiet) {
            scenario.freePlay = true
            close()
            (screen as? WorldScreen)?.shouldUpdate = true
        }
        free.actor.label.setFontSize(16)
        free.width(free.actor.prefWidth + 16f).height(42f)
        chapter.briefingShown = true; chapter.resultShown = true
        open(force = true)
    }
}
