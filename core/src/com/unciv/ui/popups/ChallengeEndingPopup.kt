package com.unciv.ui.popups

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.Align
import com.unciv.logic.GameInfo
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.WorldScreen

/** The challenge ends; the civilization remains available for free play. */
class ChallengeEndingPopup(screen: BaseScreen, game: GameInfo) : Popup(screen) {
    init {
        val scenario = requireNotNull(game.sharedScenario)
        val chapter = requireNotNull(scenario.chapter)
        val width = (screen.stage.width * 0.55f).coerceAtMost(620f)
        background = BaseScreen.skinStrings.getUiBackground("General/Popup/Background",
            tintColor = Color(0.24f, 0.015f, 0.025f, 0.72f))
        innerTable.background = BaseScreen.skinStrings.getUiBackground("SharedScenario/ChallengeEnding",
            tintColor = Color(0.16f, 0.025f, 0.035f, 1f))
        add("Challenge failed".toLabel(fontColor = Color(1f, 0.22f, 0.16f, 1f), fontSize = 44,
            alignment = Align.center)).width(width).padTop(18f).padBottom(20f).row()
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
        val primary = addButton("Retry challenge") { close(); FeaturedScenarioPopup(screen, previousResult = game) }
        primary.size(300f, 64f).colspan(2).padTop(18f).padBottom(12f).row()
        primary.actor.label.setFontSize(26)
        val quiet = TextButton.TextButtonStyle(primary.actor.style).apply { fontColor = Color.LIGHT_GRAY }
        primary.actor.style = TextButton.TextButtonStyle(primary.actor.style).apply {
            up = BaseScreen.skinStrings.getUiBackground("SharedScenario/Retry",
                BaseScreen.skinStrings.roundedEdgeRectangleShape, Color(0.65f, 0.12f, 0.08f, 1f))
            down = BaseScreen.skinStrings.getUiBackground("SharedScenario/RetryPressed",
                BaseScreen.skinStrings.roundedEdgeRectangleShape, Color(0.42f, 0.06f, 0.04f, 1f))
        }
        val details = addButton("View objectives", style = quiet) {
            close(); SharedScenarioPopup(screen, game, showChallengeDetails = true)
        }.height(42f).padRight(12f)
        details.actor.label.setFontSize(16)
        details.width(details.actor.prefWidth + 16f)
        val free = addButton("Continue", KeyCharAndCode.BACK, style = quiet) {
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
