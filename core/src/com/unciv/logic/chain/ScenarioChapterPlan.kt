package com.unciv.logic.chain

import com.unciv.logic.IsPartOfGameInfoSerialization

/** A future chapter definition; its clock starts only when the player enters it. */
class ScenarioChapterPlan : IsPartOfGameInfoSerialization {
    var technology = ""
    var building = ""
    var cityId = ""
    var cityName = ""
    var duration = 20
    var optionalGoals = false
    var holdCityId = ""
    var holdCityName = ""
    var militaryGoals: ScenarioMilitaryGoals? = null
    var goalOrder = ArrayList<String>()
    var challengeGoals = ArrayList<ChallengeGoal>()
    val goalCount get() = if (!optionalGoals) 3 else
        listOf(technology.isNotBlank(), building.isNotBlank(), holdCityId.isNotBlank()).count { it } + (militaryGoals?.goalCount ?: 0) + challengeGoals.size
    val supported get() = duration in 1..300 && if (optionalGoals)
        goalCount in 1..3 && (building.isBlank() || cityId.isNotBlank()) && (militaryGoals?.supported != false) && challengeGoals.all { it.supported && (it.mode != "consecutive" || it.turns <= duration) } && challengeGoals.map { it.id }.distinct().size == challengeGoals.size
        else technology.isNotBlank() && building.isNotBlank() && cityId.isNotBlank() && challengeGoals.isEmpty()
    fun copy() = com.unciv.json.json().fromJson(ScenarioChapterPlan::class.java, com.unciv.json.json().toJson(this))
}
