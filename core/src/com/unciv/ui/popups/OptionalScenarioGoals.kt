package com.unciv.ui.popups

/** Number only enabled goals; disabled fields are never presented as unachieved goals. */
fun optionalScenarioGoalTexts(technology: String, building: String, buildCity: String,
                             holdCity: String, duration: Int): List<String> = buildList {
    if (technology.isNotBlank()) add("[${size + 1}]. Research [$technology]")
    if (building.isNotBlank()) add("[${size + 1}]. Build [$building] in [$buildCity]")
    if (holdCity.isNotBlank()) add("[${size + 1}]. Hold [$holdCity] at scenario turn [$duration]")
}
