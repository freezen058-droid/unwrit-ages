package com.unciv.logic.chain

import com.unciv.logic.IsPartOfGameInfoSerialization

/** A future chapter definition; its clock starts only when the player enters it. */
class ScenarioChapterPlan : IsPartOfGameInfoSerialization {
    var technology = ""
    var building = ""
    var cityId = ""
    var cityName = ""
    var duration = 20
    val supported get() = technology.isNotBlank() && building.isNotBlank() && cityId.isNotBlank() && duration in 1..100
    fun copy() = com.unciv.json.json().fromJson(ScenarioChapterPlan::class.java, com.unciv.json.json().toJson(this))
}
