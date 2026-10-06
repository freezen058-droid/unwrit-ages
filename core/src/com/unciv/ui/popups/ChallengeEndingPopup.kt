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
        val chapter = scenario.chapter
        val naval = scenario.navalCampaign?.active
        val authored = scenario.authoredChapter
        val firstResult = !(authored?.resultShown ?: naval?.resultShown ?: chapter?.resultShown ?: scenario.resultShown)
        if (firstResult) com.unciv.ui.audio.SoundPlayer.play(com.unciv.models.UncivSound("challengeFailed"))
        val width = (screen.stage.width * 0.55f).coerceAtMost(620f)
        background = BaseScreen.skinStrings.getUiBackground("General/Popup/Background",
            tintColor = Color(0.24f, 0.015f, 0.025f, 0.72f))
        innerTable.background = BaseScreen.skinStrings.getUiBackground("SharedScenario/ChallengeEnding",
            tintColor = Color(0.16f, 0.025f, 0.035f, 1f))
        add("Challenge failed".toLabel(fontColor = Color(1f, 0.22f, 0.16f, 1f), fontSize = 44,
            alignment = Align.center)).width(width).padTop(18f).padBottom(20f).row()
        val missing = buildList {
            if (authored != null) {
                if (authored.researchTurn < 0 && (!authored.optionalGoals || authored.technology.isNotBlank())) add("Complete research".tr())
                if (authored.constructionTurn < 0 && (!authored.optionalGoals || authored.building.isNotBlank())) add("Building goal".tr())
                if (authored.holdTurn < 0 && (!authored.optionalGoals || authored.holdCityId.isNotBlank()))
                    add("Hold [${if (authored.optionalGoals) authored.holdCityName else authored.cityName}]".tr())
                if (authored.militaryGoals?.captureCityId?.isNotBlank() == true && authored.captureTurn < 0) add("Capture city".tr())
                if (authored.militaryGoals?.musterCityId?.isNotBlank() == true && authored.musterTurn < 0) add("Assemble troops".tr())
            } else if (naval != null) {
                if (naval.fleetTurn < 0) add((if (naval.number == 2) "Assemble your fleet" else "Field two frigates").tr())
                if (naval.objectiveTurn < 0) add((if (naval.number == 2) "Secure the strait" else "Capture the enemy port").tr())
                if (naval.holdTurn < 0) add("Hold your capital".tr())
            } else if (chapter == null) {
                if (scenario.researchTurn < 0 && (!scenario.optionalGoals || scenario.technology.isNotBlank())) add("Complete research".tr())
                if (scenario.constructionTurn < 0 && (!scenario.optionalGoals || scenario.building.isNotBlank())) add("Building goal".tr())
                if (scenario.holdTurn < 0 && (!scenario.optionalGoals || scenario.holdCityId.isNotBlank()))
                    add(if (scenario.optionalGoals) "Hold [${scenario.holdCityName}]".tr() else "Hold your capital".tr())
                if (scenario.militaryGoals?.captureCityId?.isNotBlank() == true && scenario.captureTurn < 0) add("Capture city".tr())
                if (scenario.militaryGoals?.musterCityId?.isNotBlank() == true && scenario.musterTurn < 0) add("Assemble troops".tr())
            } else if (chapter.path == "renewal") {
                if (chapter.treasuryTurn < 0) add("Grow your treasury".tr())
                if (chapter.peaceTurn < 0) add("Secure peace".tr())
            } else {
                if (chapter.researchTurn < 0) add("Complete research".tr())
                if (chapter.constructionTurn < 0) add("Rebuild the market".tr())
            }
            if (chapter != null && chapter.holdTurn < 0) add("Hold your capital".tr())
        }
        add("Unfinished: [${missing.joinToString(" · ")}]".toLabel(fontColor = Color.LIGHT_GRAY,
            alignment = Align.center, fontSize = 20).apply { wrap = true }).width(width).padBottom(10f).row()
        val canRetry = scenario.id in listOf("strait-watch-v1", "civilization-on-the-brink-v1") ||
            com.unciv.logic.chain.ScenarioReplay.available(game)
        val primary = addButton(if (canRetry) "Retry challenge" else "View goals") {
            close()
            if (canRetry) FeaturedScenarioPopup(screen, previousResult = game)
            else SharedScenarioPopup(screen, game, showChallengeDetails = true)
        }
        primary.size(300f, 64f).colspan(2).padTop(18f).padBottom(12f).row()
        primary.actor.label.setFontSize(26)
        val quiet = TextButton.TextButtonStyle(primary.actor.style).apply { fontColor = Color.LIGHT_GRAY }
        primary.actor.style = TextButton.TextButtonStyle(primary.actor.style).apply {
            up = BaseScreen.skinStrings.getUiBackground("SharedScenario/Retry",
                BaseScreen.skinStrings.roundedEdgeRectangleShape, Color(0.65f, 0.12f, 0.08f, 1f))
            down = BaseScreen.skinStrings.getUiBackground("SharedScenario/RetryPressed",
                BaseScreen.skinStrings.roundedEdgeRectangleShape, Color(0.42f, 0.06f, 0.04f, 1f))
        }
        val details = addButton("View goals", style = quiet) {
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
        if (authored != null) { authored.briefingShown = true; authored.resultShown = true }
        else if (naval != null) { naval.briefingShown = true; naval.resultShown = true }
        else if (chapter != null) { chapter.briefingShown = true; chapter.resultShown = true }
        else { scenario.briefingShown = true; scenario.resultShown = true }
        open(force = true)
    }
}
