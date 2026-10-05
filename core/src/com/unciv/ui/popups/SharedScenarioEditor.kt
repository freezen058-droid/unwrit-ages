package com.unciv.ui.popups

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.logic.GameInfo
import com.unciv.logic.chain.SharedScenario
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.widgets.TranslatedSelectBox
import com.unciv.ui.components.input.onChange
import com.unciv.ui.screens.basescreen.BaseScreen
import java.util.UUID

/** Draft-only editor for the three shipped templates; cancel never changes a game's goals. */
class SharedScenarioEditor(screen: BaseScreen, game: GameInfo, current: SharedScenario?,
                           onSelected: (SharedScenario?) -> Unit) : Popup(screen) {
    init {
        addGoodSizedLabel("Goals for the next player").row()
        val civ = game.getCurrentPlayerCivilization()
        val cities = civ.cities.toList()
        val technologies = game.ruleset.technologies.keys.filter { name -> civ.tech.researchedTechnologies.none { it.name == name } }
        val buildings = game.ruleset.buildings.values.filter { !it.isWonder && !it.isNationalWonder &&
            civ.getEquivalentBuilding(it).name == it.name &&
            (it.uniqueTo == null || civ.matchesFilter(it.uniqueTo!!)) }
            .map { it.name }.filter { name -> cities.any { !it.cityConstructions.isBuilt(name) } }
        if (cities.isEmpty() || technologies.isEmpty() || buildings.isEmpty()) {
            add("No eligible goals for this save.".toLabel()).row()
            addCloseButton(); open(force = true)
        } else {
            val draft = current?.copy() ?: SharedScenario().apply {
                id = UUID.randomUUID().toString(); title = "Shared save goals"
                civilization = civ.civID; startTurn = game.turns
                cityId = civ.getCapital()!!.id; cityName = civ.getCapital()!!.name
                opponent = civ.getCivsAtWarWith().firstOrNull()?.civID.orEmpty()
            }
            val table = Table().apply { defaults().left().pad(5f) }
            fun picker(label: String, values: List<String>, chosen: String, change: (String) -> Unit) {
                table.add(label.toLabel()).left()
                val select = TranslatedSelectBox(values, chosen.takeIf { it in values } ?: values.first())
                change(select.selected.value)
                select.onChange { change(select.selected.value) }
                table.add(select).width(250f).row()
            }
            picker("Technology goal", technologies, draft.technology) { draft.technology = it }
            picker("Building goal", buildings, draft.building) { draft.building = it }
            picker("City to build in and hold", cities.map { it.name }, draft.cityName) { name ->
                val city = cities.first { it.name == name }; draft.cityId = city.id; draft.cityName = name
            }
            picker("Scenario length", listOf("10", "15", "20", "25", "30"), draft.duration.toString()) {
                draft.duration = it.toInt()
            }
            add(table).row()
            add("Suggested order - choose your own strategy.".toLabel()).row()
            val error = "".toLabel().apply { wrap = true }
            add(error).width(450f).row()
            addButton("Use these goals") {
                if (cities.first { it.id == draft.cityId }.cityConstructions.isBuilt(draft.building))
                    error.setText("Choose a building not yet present in this city.".tr())
                else { onSelected(draft); close() }
            }
            addButton("No goals") { onSelected(null); close() }
            addCloseButton("Cancel")
            open(force = true)
        }
    }
}
