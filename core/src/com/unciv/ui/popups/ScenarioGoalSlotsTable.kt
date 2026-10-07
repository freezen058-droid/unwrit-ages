package com.unciv.ui.popups

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.logic.GameInfo
import com.unciv.logic.chain.ScenarioChapterPlan
import com.unciv.logic.chain.ScenarioMilitaryGoals
import com.unciv.logic.chain.ChallengePacks
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.widgets.TranslatedSelectBox
import com.unciv.ui.components.input.onChange

/** Three distinct goal types, with parameters only for the selected types. */
class ScenarioGoalSlotsTable(game: GameInfo, private val plan: ScenarioChapterPlan,
                             private val singleGoalIndex: Int? = null,
                             private val onGoalChanged: (() -> Unit)? = null) : Table() {
    private val civ = game.getCurrentPlayerCivilization()
    private val cities = civ.cities.toList()
    private val preferred = civ.getCapital() ?: cities.first()
    private val targets = game.civilizations.filter { it != civ }.flatMap { it.cities }
        .filter { it.getCenterTile().isExplored(civ) }
    private val technologies = game.ruleset.technologies.keys.filter { !civ.tech.isResearched(it) }
    private val buildings = game.ruleset.buildings.values.filter { !it.isWonder && !it.isNationalWonder &&
        civ.getEquivalentBuilding(it).name == it.name && (it.uniqueTo == null || civ.matchesFilter(it.uniqueTo!!)) }
        .map { it.name }.filter { name -> cities.any { !it.cityConstructions.isBuilt(name) } }
    private val availableGoals = (plan.challengeGoals + ChallengePacks.availableGoals()).distinctBy { it.label }
    private val language = com.unciv.UncivGame.Current.settings.language
    private fun kindLabel(goal: com.unciv.logic.chain.ChallengeGoal) = goal.displayLabel(language)
    private val kinds = ArrayList<String>()
    val hasSelectedGoal get() = singleGoalIndex?.let { kinds[it] != "None" } ?: (plan.goalCount > 0)
    init {
        val active = buildList {
            if (plan.technology.isNotBlank()) add("Research")
            if (plan.building.isNotBlank()) add("Build")
            if (plan.holdCityId.isNotBlank()) add("Hold city")
            if (plan.militaryGoals?.captureCityId?.isNotBlank() == true) add("Capture city")
            if (plan.militaryGoals?.musterCityId?.isNotBlank() == true) add("Assemble troops")
            addAll(plan.challengeGoals.map { kindLabel(it) })
        }
        kinds.addAll((plan.goalOrder.map { id -> availableGoals.firstOrNull { it.id == id }?.let { kindLabel(it) } ?: id } + active).distinct().filter { it in active })
        while (kinds.size < 3) kinds.add("None")
        defaults().left().pad(2f)
        refresh()
    }
    private fun military() = plan.militaryGoals ?: ScenarioMilitaryGoals().also { plan.militaryGoals = it }
    private fun switch(old: String, next: String) {
        plan.challengeGoals.removeAll { kindLabel(it) == old }
        availableGoals.firstOrNull { kindLabel(it) == next }?.let { plan.challengeGoals.add(it.copy().apply { reset() }) }
        when (old) {
            "Research" -> plan.technology = ""
            "Build" -> { plan.building = ""; plan.cityId = ""; plan.cityName = "" }
            "Hold city" -> { plan.holdCityId = ""; plan.holdCityName = "" }
            "Capture city" -> military().captureCityId = ""
            "Assemble troops" -> military().musterCityId = ""
        }
        when (next) {
            "Research" -> plan.technology = technologies.first()
            "Build" -> { plan.building = buildings.firstOrNull { !preferred.cityConstructions.isBuilt(it) } ?: buildings.first()
                plan.cityId = preferred.id; plan.cityName = preferred.name }
            "Hold city" -> { plan.holdCityId = preferred.id; plan.holdCityName = preferred.name }
            "Capture city" -> { military().captureCityId = targets.first().id; military().captureCityName = targets.first().name }
            "Assemble troops" -> { military().musterCityId = preferred.id; military().musterCityName = preferred.name }
        }
        if (plan.militaryGoals?.goalCount == 0) plan.militaryGoals = null
    }
    private fun picker(label: String, name: String, values: List<String>, chosen: String, changed: (String) -> Unit) {
        add(label.toLabel()).left()
        val select = TranslatedSelectBox(values, chosen.takeIf { it in values } ?: values.first())
        select.name = name; changed(select.selected.value)
        select.onChange { changed(select.selected.value) }
        add(select).width(select.prefWidth.coerceIn(250f, 350f)).row()
    }
    private fun refresh() {
        clear()
        for (i in singleGoalIndex?.let { it..it } ?: (0..2)) {
            val kind = kinds[i]
            val options = (listOf("None", "Research", "Build", "Hold city", "Capture city", "Assemble troops") + availableGoals.map { kindLabel(it) })
                .filter { it == "None" || it == kind || it !in kinds }
                .filter { it == kind || when (it) {
                    "Research" -> technologies.isNotEmpty(); "Build" -> buildings.isNotEmpty()
                    "Capture city" -> targets.isNotEmpty(); else -> true
                } }
            picker("Goal [${i + 1}]", "GoalType$i", options, kind) { selected ->
                if (selected != kinds[i]) {
                    switch(kinds[i], selected); kinds[i] = selected; refresh(); onGoalChanged?.invoke()
                }
            }
            when (kind) {
                "Research" -> picker("Technology", "GoalTechnology$i", (technologies + plan.technology).distinct(), plan.technology) { plan.technology = it }
                "Build" -> {
                    picker("Building", "GoalBuilding$i", (buildings + plan.building).distinct(), plan.building) { plan.building = it }
                    picker("Build in city", "GoalBuildCity$i", cities.map { it.name }, plan.cityName) {
                        val city = cities.first { city -> city.name == it }; plan.cityId = city.id; plan.cityName = city.name }
                }
                "Hold city" -> picker("City", "GoalCity$i", cities.map { it.name }, plan.holdCityName) {
                    val city = cities.first { city -> city.name == it }; plan.holdCityId = city.id; plan.holdCityName = city.name }
                "Capture city" -> {
                    val available = (targets + cities.filter { it.id == military().captureCityId }).distinctBy { it.id }
                    if (available.isNotEmpty()) picker("Target city", "GoalCaptureCity$i",
                        available.map { "${it.civ.civName}: ${it.name}" },
                        available.firstOrNull { it.id == military().captureCityId }?.let { "${it.civ.civName}: ${it.name}" }.orEmpty()) {
                        val city = available.first { city -> "${city.civ.civName}: ${city.name}" == it }
                        military().captureCityId = city.id; military().captureCityName = city.name }
                }
                "Assemble troops" -> {
                    picker("City", "GoalCity$i", cities.map { it.name }, military().musterCityName) {
                        val city = cities.first { city -> city.name == it }; military().musterCityId = city.id; military().musterCityName = city.name }
                    picker("Military units", "GoalDomain$i", listOf("Land", "Naval"), military().musterDomain) { military().musterDomain = it }
                    val quantities = Table().apply { defaults().left().pad(2f) }
                    quantities.add("Required units".toLabel())
                    val count = TranslatedSelectBox(listOf("3", "5", "8"), military().musterCount.toString())
                    count.name = "GoalCount$i"; count.onChange { military().musterCount = count.selected.value.toInt() }
                    quantities.add(count).width(75f).padRight(12f)
                    quantities.add("Within tiles".toLabel())
                    val radius = TranslatedSelectBox(listOf("2", "3", "4"), military().musterRadius.toString())
                    radius.name = "GoalRadius$i"; radius.onChange { military().musterRadius = radius.selected.value.toInt() }
                    quantities.add(radius).width(75f)
                    add(quantities).colspan(2).row()
                }
                else -> plan.challengeGoals.firstOrNull { kindLabel(it) == kind }?.let { goal ->
                    val rule = goal.conditions.first()
                    if (Regex("\\[\\d+\\]").containsMatchIn(goal.title)) {
                        val peace = rule.metric == "peace" && goal.mode == "consecutive"
                        val values = when (rule.metric) {
                            "exploredTiles" -> listOf(10, 20, 40, 60, 100)
                            "gold" -> listOf(50, 100, 200, 500, 1000)
                            "cities", "wonders" -> listOf(1, 2, 3)
                            "population", "technologies" -> listOf(1, 3, 5, 10)
                            "peace" -> listOf(5, 10, 15, 20, 30)
                            else -> listOf(0, 5, 10)
                        }
                        val current = if (peace) goal.turns else rule.target
                        picker(when (rule.metric) {
                            "exploredTiles" -> "New tiles"
                            "cities" -> "Additional cities"
                            "gold" -> "Additional gold"
                            "population" -> "Additional population"
                            "technologies" -> "New technologies"
                            "wonders" -> "Additional wonders"
                            "happiness" -> "Minimum happiness"
                            else -> "Consecutive turns"
                        },
                            "PackGoalTarget$i", (values + current).distinct().sorted().map { it.toString() }, current.toString()) {
                            val n = it.toInt()
                            if (peace) goal.turns = n else rule.target = n
                            val number = Regex("\\[\\d+\\]")
                            goal.title = number.replaceFirst(goal.title, "[$n]")
                            goal.titleTw = number.replaceFirst(goal.titleTw, "[$n]")
                            goal.titleCn = number.replaceFirst(goal.titleCn, "[$n]")
                        }
                    }
                }
            }
        }
        plan.goalOrder = ArrayList(kinds.filter { it != "None" }.map { label ->
            plan.challengeGoals.firstOrNull { kindLabel(it) == label }?.id ?: label
        })
    }
}
