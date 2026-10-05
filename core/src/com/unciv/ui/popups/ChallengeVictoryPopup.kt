package com.unciv.ui.popups

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.Align
import com.unciv.logic.GameInfo
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.WorldScreen

/** Chapter milestones and the final challenge deserve their own ending. */
class ChallengeVictoryPopup(screen: BaseScreen, game: GameInfo) : Popup(screen) {
    init {
        val scenario = requireNotNull(game.sharedScenario)
        val nextChapter = scenario.hasNextChapter
        val width = (screen.stage.width * 0.55f).coerceAtMost(620f)
        background = BaseScreen.skinStrings.getUiBackground("General/Popup/Background",
            tintColor = Color(0.10f, 0.075f, 0.015f, 0.68f))
        innerTable.background = BaseScreen.skinStrings.getUiBackground("SharedScenario/ChallengeVictory",
            tintColor = Color(0.15f, 0.105f, 0.035f, 1f))
        add(ImageGetter.getWhiteDot().apply { color = Color.GOLD }).size(width, 3f).padTop(8f).padBottom(18f).row()
        add((if (nextChapter) "Chapter complete" else "Challenge completed")
            .toLabel(fontColor = Color.GOLD, fontSize = if (nextChapter) 38 else 42, alignment = Align.center)
            .apply { wrap = true }).width(width).padBottom(16f).row()
        add("All objectives achieved".toLabel(fontColor = Color.WHITE, fontSize = 22, alignment = Align.center))
            .width(width).padBottom(14f).row()
        add(ImageGetter.getWhiteDot().apply { color = Color(0.7f, 0.48f, 0.1f, 1f) }).size(width * 0.5f, 2f).row()
        val nextLabel = if (!nextChapter) "Continue" else if (scenario.nextChapterPlans.isNotEmpty())
            (if (scenario.chapterNumber == 1) "Begin Chapter II" else "Begin Chapter III") else if (scenario.navalCampaign != null)
            (if (scenario.navalCampaign!!.active == null) "Begin Chapter II" else "Begin Chapter III") else "Begin Renewal"
        val primary = addButton(nextLabel) {
            close()
            if (nextChapter && scenario.beginNextChapter(game)) SharedScenarioPopup(screen, game)
            (screen as? WorldScreen)?.shouldUpdate = true
        }
        primary.size(300f, 64f).colspan(2).padTop(18f).padBottom(12f).row()
        primary.actor.label.setFontSize(26)
        val quiet = TextButton.TextButtonStyle(primary.actor.style).apply { fontColor = Color.LIGHT_GRAY }
        primary.actor.style = TextButton.TextButtonStyle(primary.actor.style).apply {
            up = BaseScreen.skinStrings.getUiBackground("SharedScenario/VictoryContinue",
                BaseScreen.skinStrings.roundedEdgeRectangleShape, Color(0.50f, 0.34f, 0.06f, 1f))
            down = BaseScreen.skinStrings.getUiBackground("SharedScenario/VictoryContinuePressed",
                BaseScreen.skinStrings.roundedEdgeRectangleShape, Color(0.32f, 0.22f, 0.04f, 1f))
        }
        val details = addButton("View objectives", style = quiet) {
            close(); SharedScenarioPopup(screen, game, showChallengeDetails = true)
        }.height(42f)
        details.actor.label.setFontSize(16)
        details.width(details.actor.prefWidth + 16f)
        if (!nextChapter) primary.actor.keyShortcuts.add(KeyCharAndCode.BACK)
        scenario.authoredChapter?.let { it.briefingShown = true; it.resultShown = true }
            ?: scenario.navalCampaign?.active?.let { it.briefingShown = true; it.resultShown = true }
            ?: scenario.chapter?.let { it.briefingShown = true; it.resultShown = true }
            ?: run { scenario.briefingShown = true; scenario.resultShown = true }
        open(force = true)
    }
}
