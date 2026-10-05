package com.unciv.ui.popups

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.logic.GameInfo
import com.unciv.logic.chain.SharedScenario
import com.unciv.logic.chain.ScenarioChapterPlan
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.widgets.TranslatedSelectBox
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.input.onClick
import com.unciv.ui.screens.basescreen.BaseScreen
import java.util.UUID

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
            val chapters = arrayListOf(ScenarioChapterPlan().apply {
                technology = draft.technology; building = draft.building; cityId = draft.cityId
                cityName = draft.cityName; duration = draft.duration
            })
            chapters.addAll(draft.nextChapterPlans.map { it.copy() })
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
                fun picker(label: String, name: String, values: List<String>, chosen: String, change: (String) -> Unit) {
                    form.add(label.toLabel()).left()
                    val select = TranslatedSelectBox(values, chosen.takeIf { it in values } ?: values.first())
                    select.name = name
                    change(select.selected.value)
                    select.onChange { change(select.selected.value) }
                    form.add(select).width(250f).row()
                }
                picker("Technology goal", "ChapterTechnology", technologies, plan.technology) { plan.technology = it }
                picker("Building goal", "ChapterBuilding", buildings, plan.building) { plan.building = it }
                picker("City to build in and hold", "ChapterCity", cities.map { it.name }, plan.cityName) { name ->
                    val city = cities.first { it.name == name }; plan.cityId = city.id; plan.cityName = name
                }
                picker("Scenario length", "ChapterDuration", listOf("10", "15", "20", "25", "30"), plan.duration.toString()) {
                    plan.duration = it.toInt()
                }
                val controls = Table()
                if (chapters.size < 3) controls.add("Add chapter".toTextButton().apply {
                    onClick {
                        if (technologies.size <= chapters.size) {
                            error.setText("No eligible goals for this save.".tr()); return@onClick
                        }
                        val usedTech = chapters.map { it.technology }
                        chapters.add(ScenarioChapterPlan().apply {
                            technology = technologies.first { it !in usedTech }
                            building = buildings.firstOrNull { name -> chapters.none { it.building == name && it.cityId == plan.cityId } }
                                ?: buildings.first()
                            cityId = plan.cityId; cityName = plan.cityName
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
                val invalid = chapters.indexOfFirst { plan -> cities.first { it.id == plan.cityId }.cityConstructions.isBuilt(plan.building) }
                if (invalid >= 0) {
                    index = invalid; refresh(); error.setText("Choose a building not yet present in this city.".tr())
                } else if (chapters.map { it.technology }.distinct().size != chapters.size) {
                    error.setText("Choose a different technology for each chapter.".tr())
                } else if (chapters.map { it.cityId to it.building }.distinct().size != chapters.size) {
                    error.setText("Choose a different building or city for each chapter.".tr())
                } else {
                    val first = chapters.first()
                    draft.technology = first.technology; draft.building = first.building
                    draft.cityId = first.cityId; draft.cityName = first.cityName; draft.duration = first.duration
                    draft.nextChapterPlans = ArrayList(chapters.drop(1).map { it.copy() })
                    onSelected(draft); close()
                }
            }
            addButton("No goals") { onSelected(null); close() }
            addCloseButton("Cancel")
            open(force = true)
        }
    }
}
