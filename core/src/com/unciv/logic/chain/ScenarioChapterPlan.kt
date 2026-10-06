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
    val goalCount get() = if (!optionalGoals) 3 else
        listOf(technology.isNotBlank(), building.isNotBlank(), holdCityId.isNotBlank()).count { it }
    val supported get() = duration in 1..100 && if (optionalGoals)
        goalCount > 0 && (building.isBlank() || cityId.isNotBlank())
        else technology.isNotBlank() && building.isNotBlank() && cityId.isNotBlank()
    fun copy() = com.unciv.json.json().fromJson(ScenarioChapterPlan::class.java, com.unciv.json.json().toJson(this))
}
