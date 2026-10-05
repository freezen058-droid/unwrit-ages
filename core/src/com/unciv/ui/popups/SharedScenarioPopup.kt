package com.unciv.ui.popups

import com.badlogic.gdx.graphics.Color
import com.unciv.logic.GameInfo
import com.unciv.logic.chain.SharedScenario
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.screens.basescreen.BaseScreen

/** One reusable, compact briefing/progress/result view. */
class SharedScenarioPopup(screen: BaseScreen, game: GameInfo, briefing: Boolean = false) : Popup(screen) {
    init {
        val scenario = requireNotNull(game.sharedScenario)
        scenario.observe(game)
        addGoodSizedLabel(scenario.title, color = Color.GOLD).row()
        val width = screen.stage.width * 0.65f
        fun line(text: String, gold: Boolean = false) {
            add(text.toLabel(if (gold) Color.GOLD else Color.WHITE).apply { wrap = true })
                .width(width).left().padTop(8f).row()
        }
        if (briefing && scenario.id == "civilization-on-the-brink-v1")
            line("Two cities. A costly war. Rebuild your economy without losing your capital.".tr())
        line("Scenario turn [${(game.turns - scenario.startTurn).coerceIn(0, scenario.duration)}] / [${scenario.duration}]".tr())
        line("Suggested order - choose your own strategy.".tr())
        for (goal in scenarioGoalLines(scenario)) line(goal)
        line(when (scenario.outcome) {
            "completed" -> "Civilization restored - all three goals completed.".tr()
            "unfinished" -> "The deadline has passed. Your civilization's story can continue.".tr()
            "defeated" -> "This civilization has fallen. Try a different strategy from the same starting save.".tr()
            else -> if (scenario.id == "civilization-on-the-brink-v1")
                "Research unlocks the market. The market supports your army. Keep your capital at the deadline.".tr()
                else "Complete your research and construction. Hold the chosen city at the deadline.".tr()
        }, true)
        if (scenario.outcome.isNotEmpty()) {
            line("Treasury: [${scenario.finalGold}] · Cities: [${scenario.finalCities}]".tr() + " · " +
                (if (scenario.finalAtWar) "At war" else "At peace").tr())
            scenario.resultShown = true
        }
        if (briefing) scenario.briefingShown = true
        addCloseButton(if (briefing) "Begin" else "Close")
        open(force = true)
    }
}

fun scenarioGoalLines(s: SharedScenario): List<String> {
    fun completion(turn: Int) = if (turn < 0) (if (s.outcome.isEmpty()) "In progress" else "Not completed").tr()
        else "Completed on scenario turn [${turn - s.startTurn}]".tr()
    return listOf(
        "1. Research [${s.technology.tr()}]".tr() + " · " + completion(s.researchTurn),
        "2. Build [${s.building.tr()}] in [${s.cityName.tr()}]".tr() + " · " + completion(s.constructionTurn),
        "3. Hold [${s.cityName.tr()}] at scenario turn [${s.duration}]".tr() + " · " + completion(s.holdTurn)
    )
}
