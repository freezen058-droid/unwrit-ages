package com.unciv.ui.popups

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.logic.GameInfo
import com.unciv.logic.chain.SharedScenario
import com.unciv.logic.chain.ScenarioChapterPlan
import com.unciv.logic.chain.ScenarioAuthoring
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.widgets.TranslatedSelectBox
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.input.onClick
import com.unciv.ui.screens.basescreen.BaseScreen

/** Draft-only, up to three linear chapters. Cancel never changes the current game. */
class SharedScenarioEditor(screen: BaseScreen, game: GameInfo, current: SharedScenario?,
                           onSelected: (SharedScenario?) -> Unit) : Popup(screen) {
    init {
        addGoodSizedLabel("Goals for the next player").row()
        val civ = game.getCurrentPlayerCivilization()
        val cities = civ.cities.toList()
        val technologies = game.ruleset.technologies.keys.filter { !civ.tech.isResearched(it) }
        val buildings = game.ruleset.buildings.values.filter { !it.isWonder && !it.isNationalWonder &&
            civ.getEquivalentBuilding(it).name == it.name && (it.uniqueTo == null || civ.matchesFilter(it.uniqueTo!!)) }
            .map { it.name }.filter { name -> cities.any { !it.cityConstructions.isBuilt(name) } }
        if (cities.isEmpty()) {
            add("No eligible goals for this save.".toLabel()).row()
            addCloseButton(); open(force = true)
        } else {
            val preferred = civ.getCapital() ?: cities.first()
            val chapters = ArrayList(current?.let { ScenarioAuthoring.plans(it) } ?: listOf(ScenarioChapterPlan().apply {
                optionalGoals = true; technology = technologies.firstOrNull().orEmpty()
                building = buildings.firstOrNull { !preferred.cityConstructions.isBuilt(it) }.orEmpty()
                cityId = preferred.id; cityName = preferred.name; holdCityId = preferred.id; holdCityName = preferred.name
            }))
            var index = 0
            val form = Table().apply { defaults().left().pad(2f) }
            add(form).row()
            val error = "".toLabel().apply { wrap = true }
            add(error).width(450f).row()
            fun refresh() {
                form.clear()
                val plan = chapters[index]
                val navigation = Table()
                if (index > 0) navigation.add("Previous".toTextButton().apply {
                    onClick { index--; refresh() }
                }).width(135f).height(40f) else navigation.add().width(135f)
                navigation.add("Chapter [${index + 1}] / [${chapters.size}]".toLabel(Color.GOLD)).width(220f).center()
                if (index < chapters.lastIndex) navigation.add("Next".toTextButton().apply {
                    onClick { index++; refresh() }
                }).width(135f).height(40f) else navigation.add().width(135f)
                form.add(navigation).colspan(2).center().padBottom(6f).row()
                fun picker(label: String, name: String, values: List<String>, chosen: String,
                           rebuild: Boolean = false, change: (String) -> Unit) {
                    form.add(label.toLabel()).left()
                    val select = TranslatedSelectBox(values, chosen.takeIf { it in values } ?: values.first())
                    select.name = name
                    change(select.selected.value)
                    select.onChange { change(select.selected.value); if (rebuild) refresh() }
                    form.add(select).width(250f).row()
                }
                fun goalValue(value: String) = value.takeUnless { it == "None" }.orEmpty()
                picker("Technology goal", "ChapterTechnology", listOf("None") + technologies,
                    plan.technology.ifBlank { "None" }) { plan.technology = goalValue(it) }
                picker("Building goal", "ChapterBuilding", listOf("None") + buildings,
                    plan.building.ifBlank { "None" }, rebuild = true) { plan.building = goalValue(it) }
                if (plan.building.isNotBlank()) picker("Build in city", "ChapterBuildCity", cities.map { it.name }, plan.cityName) { name ->
                    val city = cities.first { it.name == name }; plan.cityId = city.id; plan.cityName = name
                }
                picker("Hold city", "ChapterCity", listOf("None") + cities.map { it.name },
                    plan.holdCityName.ifBlank { "None" }) { name ->
                    val city = cities.firstOrNull { it.name == name }; plan.holdCityId = city?.id.orEmpty(); plan.holdCityName = city?.name.orEmpty()
                }
                picker("Scenario length", "ChapterDuration", listOf("10", "15", "20", "25", "30"), plan.duration.toString()) {
                    plan.duration = it.toInt()
                }
                val controls = Table()
                if (chapters.size < 3) controls.add("Add chapter".toTextButton().apply {
                    onClick {
                        chapters.add(ScenarioChapterPlan().apply {
                            optionalGoals = true
                            technology = technologies.firstOrNull { name -> chapters.none { it.technology == name } }.orEmpty()
                            cityId = plan.cityId; cityName = plan.cityName
                            building = buildings.firstOrNull { name -> chapters.none { it.building == name && it.cityId == cityId } &&
                                cities.first { it.id == cityId }.cityConstructions.isBuilt(name).not() }.orEmpty()
                            holdCityId = plan.holdCityId; holdCityName = plan.holdCityName
                        })
                        index = chapters.lastIndex; error.setText(""); refresh()
                    }
                }).width(180f).height(40f).padRight(8f)
                if (index > 0) controls.add("Remove chapter".toTextButton().apply {
                    onClick { chapters.removeAt(index); index--; error.setText(""); refresh() }
                }).width(230f).height(40f)
                form.add(controls).colspan(2).center().padTop(6f).row()
            }
            refresh()
            addButton("Use these goals") {
                val active = chapters.filter { it.goalCount > 0 }
                val invalid = chapters.indexOfFirst { plan -> plan.building.isNotBlank() &&
                    cities.firstOrNull { it.id == plan.cityId }?.cityConstructions?.isBuilt(plan.building) != false }
                val tech = active.map { it.technology }.filter { it.isNotBlank() }
                val built = active.filter { it.building.isNotBlank() }.map { it.cityId to it.building }
                if (invalid >= 0) {
                    index = invalid; refresh(); error.setText("Choose a building not yet present in this city.".tr())
                } else if (tech.distinct().size != tech.size) {
                    error.setText("Choose a different technology for each chapter.".tr())
                } else if (built.distinct().size != built.size) {
                    error.setText("Choose a different building or city for each chapter.".tr())
                } else { onSelected(ScenarioAuthoring.draft(game, current, chapters)); close() }
            }
            addButton("No goals") { onSelected(null); close() }
            addCloseButton("Cancel")
            open(force = true)
        }
    }
}
