package com.unciv.ui.popups

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.logic.chain.SharedScenario
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.screens.basescreen.BaseScreen

data class ScenarioPlanPage(val title: String, val duration: Int, val goals: List<String>, val hint: String = "")

/** Read only: previewing an unpublished chapter must not start its clock or mark it shown. */
fun scenarioPlanPages(scenario: SharedScenario): List<ScenarioPlanPage> {
    fun standard(technology: String, building: String, city: String, duration: Int) = listOf(
        "1. Research [$technology]", "2. Build [$building] in [$city]",
        "3. Hold [$city] at scenario turn [$duration]"
    )
    val first = ScenarioPlanPage(scenario.title, scenario.duration,
        if (scenario.optionalGoals) optionalScenarioGoalTexts(scenario.technology, scenario.building,
            scenario.cityName, scenario.holdCityName, scenario.duration)
        else standard(scenario.technology, scenario.building, scenario.cityName, scenario.duration))
    if (scenario.nextChapterPlans.isNotEmpty()) return listOf(first) + scenario.nextChapterPlans.map { plan ->
        ScenarioPlanPage(scenario.title, plan.duration, if (plan.optionalGoals) optionalScenarioGoalTexts(
            plan.technology, plan.building, plan.cityName, plan.holdCityName, plan.duration)
            else standard(plan.technology, plan.building, plan.cityName, plan.duration))
    }
    val naval = scenario.navalCampaign ?: return listOf(first)
    return listOf(first,
        ScenarioPlanPage("Chapter II: Command the Strait", 25, listOf(
            "1. At the deadline: at least 3 warships within 4 tiles of [${scenario.cityName}]",
            "2. At the deadline: no enemy ships within 4 tiles of [${scenario.cityName}]",
            "3. Hold [${scenario.cityName}] at the deadline"
        ), "Build warships in Antium and bring them north to defend Rome."),
        ScenarioPlanPage("Chapter III: Break the Blockade", 35, listOf(
            "1. Field at least 2 Frigates",
            "2. Capture and hold [${naval.enemyPortName}] at the deadline",
            "3. Hold [${scenario.cityName}] at the deadline"
        ), "Use Frigates to bombard the port, then a Caravel or Privateer to capture it.")
    )
}

class SharedScenarioPlanPopup(screen: BaseScreen, scenario: SharedScenario) : Popup(screen) {
    init {
        val pages = scenarioPlanPages(scenario)
        var index = 0
        val width = (screen.stage.width * 0.65f).coerceAtMost(700f)
        addGoodSizedLabel("Chapter overview", color = Color.GOLD).row()
        val body = Table()
        add(body).width(width).row()
        fun refresh() {
            body.clear()
            body.defaults().left().padTop(8f)
            val page = pages[index]
            val navigation = Table()
            navigation.add("Previous".toTextButton().apply {
                isDisabled = index == 0
                onClick { if (index > 0) { index--; refresh() } }
            }).width(135f).height(40f)
            navigation.add("Chapter [${index + 1}] / [${pages.size}]".toLabel(Color.GOLD)).width(220f).center()
            navigation.add("Next".toTextButton().apply {
                isDisabled = index == pages.lastIndex
                onClick { if (index < pages.lastIndex) { index++; refresh() } }
            }).width(135f).height(40f)
            body.add(navigation).center().row()
            fun line(text: String, color: Color = Color.WHITE) {
                body.add(text.tr().toLabel(color).apply { wrap = true }).width(width).left().row()
            }
            line(page.title, Color.GOLD)
            line("Duration: [${page.duration}] turns")
            for (goal in page.goals) line(goal)
            if (page.hint.isNotBlank()) line(page.hint)
        }
        refresh()
        addCloseButton()
        open(force = true)
    }
}
