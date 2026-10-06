package com.unciv.ui.popups

/** Number only enabled goals; disabled fields are never presented as unachieved goals. */
fun optionalScenarioGoalTexts(technology: String, building: String, buildCity: String,
                             holdCity: String, duration: Int,
                             military: com.unciv.logic.chain.ScenarioMilitaryGoals? = null,
                             order: List<String> = emptyList()): List<String> {
    val texts = linkedMapOf<String, String>()
    if (technology.isNotBlank()) texts["Research"] = "Research [$technology]"
    if (building.isNotBlank()) texts["Build"] = "Build [$building] in [$buildCity]"
    if (holdCity.isNotBlank()) texts["Hold city"] = "Hold [$holdCity] at scenario turn [$duration]"
    military?.let {
        if (it.captureCityId.isNotBlank()) texts["Capture city"] = "Control [${it.captureCityName}] at scenario turn [$duration]"
        if (it.musterCityId.isNotBlank()) texts["Assemble troops"] =
            "At scenario turn [$duration]: [${it.musterCount}] [${it.musterDomain}] military units within [${it.musterRadius}] tiles of [${it.musterCityName}]"
    }
    return (order + texts.keys).distinct().mapNotNull { texts[it] }
        .mapIndexed { i, text -> "[${i + 1}]. $text" }
}
