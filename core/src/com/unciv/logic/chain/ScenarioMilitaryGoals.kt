package com.unciv.logic.chain

import com.unciv.logic.GameInfo
import com.unciv.logic.IsPartOfGameInfoSerialization

/** Author-selected targets. Both military goals are checked at the chapter deadline. */
class ScenarioMilitaryGoals : IsPartOfGameInfoSerialization {
    var captureCityId = ""
    var captureCityName = ""
    var musterCityId = ""
    var musterCityName = ""
    var musterCount = 3
    var musterRadius = 3
    var musterDomain = "Land"
    val goalCount get() = listOf(captureCityId.isNotBlank(), musterCityId.isNotBlank()).count { it }
    val supported get() = goalCount > 0 && (musterCityId.isBlank() ||
        musterCount in 1..20 && musterRadius in 0..6 && musterDomain in listOf("Land", "Naval"))
    fun definitionParts(): List<String> = if (goalCount == 0) emptyList() else listOf(
        "military-goals-v1", captureCityId, musterCityId,
        if (musterCityId.isNotBlank()) musterCount.toString() else "",
        if (musterCityId.isNotBlank()) musterRadius.toString() else "",
        if (musterCityId.isNotBlank()) musterDomain else ""
    )
    fun captureHeld(game: GameInfo, civilization: String) = game.civilizations
        .firstOrNull { it.civID == civilization }?.cities?.any { it.id == captureCityId } == true
    fun musterReady(game: GameInfo, civilization: String): Boolean {
        val civ = game.civilizations.firstOrNull { it.civID == civilization } ?: return false
        val city = civ.cities.firstOrNull { it.id == musterCityId } ?: return false
        return civ.units.getCivUnits().count {
            it.baseUnit.isMilitary &&
                (if (musterDomain == "Naval") it.baseUnit.isWaterUnit else it.baseUnit.isLandUnit) &&
                it.currentTile.aerialDistanceTo(city.getCenterTile()) <= musterRadius
        } >= musterCount
    }
    fun copy() = com.unciv.json.json().fromJson(ScenarioMilitaryGoals::class.java, com.unciv.json.json().toJson(this))
}
