package com.unciv.ui.popups

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
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
        if (cities.isEmpty()) {
            add("No eligible goals for this save.".toLabel()).row()
            addCloseButton(); open(force = true)
        } else {
            val chapters = ArrayList(current?.let { ScenarioAuthoring.plans(it) } ?: listOf(ScenarioChapterPlan().apply {
                optionalGoals = true
            }))
            var index = 0
            var editingGoal: Int? = if (current == null) 0 else null
            var activeGoalTable: ScenarioGoalSlotsTable? = null
            lateinit var applyButton: TextButton
            val form = Table().apply { defaults().left().pad(2f) }
            add(form).row()
            val error = "".toLabel().apply { wrap = true }
            add(error).width(450f).row()
            fun refresh() {
                form.clear()
                val plan = chapters[index]
                var addGoalButton: TextButton? = null
                applyButton.setText((if (editingGoal != null) "OK" else "Use these goals").tr())
                val navigation = Table()
                if (index > 0) navigation.add("Previous".toTextButton().apply {
                    onClick { index--; editingGoal = null; error.setText(""); refresh() }
                }).width(135f).height(40f) else navigation.add().width(135f)
                navigation.add("Chapter [${index + 1}] / [${chapters.size}]".toLabel(Color.GOLD)).width(220f).center()
                if (index < chapters.lastIndex) navigation.add("Next".toTextButton().apply {
                    onClick { index++; editingGoal = null; error.setText(""); refresh() }
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
                if (editingGoal != null) {
                    val count = "✓ ${plan.goalCount}/3".toLabel(Color.LIGHT_GRAY)
                    count.name = "ConfiguredGoalCount"
                    form.add(count).colspan(2).right().padBottom(6f).row()
                    activeGoalTable = ScenarioGoalSlotsTable(game, plan, editingGoal) {
                        count.setText("✓ ${plan.goalCount}/3")
                        addGoalButton?.isVisible = plan.goalCount < 3
                    }
                    form.add(activeGoalTable).colspan(2).row()
                } else {
                    activeGoalTable = null
                    val goals = optionalScenarioGoalTexts(plan.technology, plan.building, plan.cityName,
                        plan.holdCityName, plan.duration, plan.militaryGoals, plan.goalOrder)
                    if (goals.isEmpty()) form.add("No goals".toLabel()).colspan(2).left().row()
                    for ((goalIndex, text) in goals.withIndex()) {
                        val goal = text.tr().toTextButton().apply {
                            name = "GoalSummary$goalIndex"; label.setWrap(true)
                            onClick { editingGoal = goalIndex; error.setText(""); refresh() }
                        }
                        form.add(goal).colspan(2).width(450f).minHeight(48f).padBottom(4f).row()
                    }
                    picker("Scenario length", "ChapterDuration", listOf("10", "15", "20", "25", "30"), plan.duration.toString(), rebuild = true) {
                        plan.duration = it.toInt()
                    }
                }
                if (plan.goalCount < 3) form.add("+ Goal".toTextButton().apply {
                    addGoalButton = this
                    name = "AddScenarioGoal"
                    onClick {
                        if (plan.goalCount >= 3) return@onClick
                        if (editingGoal != null && activeGoalTable?.hasSelectedGoal == false)
                            error.setText("Choose a goal before adding another.".tr())
                        else { editingGoal = plan.goalCount.takeIf { it < 3 }; error.setText(""); refresh() }
                    }
                }).colspan(2).center().width(180f).height(48f).padTop(6f).row()
                val controls = Table()
                if (editingGoal == null && chapters.size < 3) controls.add("Add chapter".toTextButton().apply {
                    onClick {
                        chapters.add(ScenarioChapterPlan().apply {
                            optionalGoals = true
                        })
                        index = chapters.lastIndex; editingGoal = 0; error.setText(""); refresh()
                    }
                }).width(180f).height(40f).padRight(8f)
                if (index > 0) controls.add("Remove chapter".toTextButton().apply {
                    onClick { chapters.removeAt(index); index--; editingGoal = null; error.setText(""); refresh() }
                }).width(230f).height(40f)
                form.add(controls).colspan(2).center().padTop(6f).row()
            }
            applyButton = addButton("OK") {
                if (editingGoal != null) {
                    editingGoal = null; error.setText(""); refresh()
                    return@addButton
                }
                val active = chapters.filter { it.goalCount > 0 }
                val invalid = chapters.indexOfFirst { plan -> plan.building.isNotBlank() &&
                    cities.firstOrNull { it.id == plan.cityId }?.cityConstructions?.isBuilt(plan.building) != false }
                val tech = active.map { it.technology }.filter { it.isNotBlank() }
                val built = active.filter { it.building.isNotBlank() }.map { it.cityId to it.building }
                val emptyChapter = if (chapters.size > 1) chapters.indexOfFirst { it.goalCount == 0 } else -1
                if (emptyChapter >= 0) {
                    index = emptyChapter; refresh()
                    error.setText("Chapter [${index + 1}] needs at least one goal.".tr())
                } else if (invalid >= 0) {
                    index = invalid; refresh(); error.setText("Choose a building not yet present in this city.".tr())
                } else if (tech.distinct().size != tech.size) {
                    error.setText("Choose a different technology for each chapter.".tr())
                } else if (built.distinct().size != built.size) {
                    error.setText("Choose a different building or city for each chapter.".tr())
                } else { onSelected(ScenarioAuthoring.draft(game, current, chapters)); close() }
            }.actor
            addButton("No goals") {
                if (chapters.size > 1) error.setText("Keep one chapter to save without goals.".tr())
                else { onSelected(null); close() }
            }
            addCloseButton("Cancel")
            refresh()
            open(force = true)
        }
    }
}
