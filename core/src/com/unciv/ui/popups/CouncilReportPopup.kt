package com.unciv.ui.popups

import com.unciv.Constants
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Council
import com.unciv.models.ruleset.Building
import com.unciv.ui.screens.worldscreen.WorldScreen

/**
 * One council report that wants the player's answer (ROADMAP "the council", Reports; user 09-30):
 * the situation, and ways to solve it with one tap. Shown by WorldScreen.update, one at a time,
 * after the game's own alerts.
 *
 *  * `threat:<city>:<n>` - enemy units massing near a city: I'll handle it / hand it to the council
 *    (it holds) / only build walls / ignore / always hand threatened cities over without asking.
 *  * `passed:<city>` - the threat to a city the council took over has passed: take it back / leave it.
 */
class CouncilReportPopup(private val worldScreen: WorldScreen, private val item: String) : Popup(worldScreen) {

    private val council = worldScreen.selectedCiv.council

    init {
        val parts = item.split(":")
        val city = worldScreen.selectedCiv.cities.firstOrNull { it.id == parts.getOrNull(1) }
        council.reports.remove(item)
        if (city == null || parts[0] !in setOf("threat", "passed", "unhappy")) {
            worldScreen.shouldUpdate = true
        } else {
            addGoodSizedLabel(council.name()).row()
            when (parts[0]) {
                "threat" -> threat(city, parts.getOrNull(2)?.toIntOrNull() ?: 0)
                "passed" -> passed(city)
                "unhappy" -> unhappy(city, parts.getOrNull(2)?.toIntOrNull() ?: 0)
            }
            open(force = true)
        }
    }

    private fun answered(city: City) {
        council.answered[city.id] = worldScreen.gameInfo.turns
        worldScreen.shouldUpdate = true
    }

    private fun threat(city: City, enemies: Int) {
        addGoodSizedLabel("[$enemies] enemy units are massing near [${city.name}].").row()
        addGoodSizedLabel("What should be done?", size = Constants.defaultFontSize - 2).row()
        addButton("I'll handle it myself") { answered(city); close() }.row()
        addButton("Hand it to the council - it holds the city") {
            council.handOverForThreat(city)
            worldScreen.shouldUpdate = true
            close()
        }.row()
        val walls = defensiveBuilding(city)
        if (walls != null)
            addButton("Only build [${walls.name}] first") {
                city.cityConstructions.addToQueue(walls, addToTop = true)   // first; the rest waits one item
                answered(city)
                close()
            }.row()
        addButton("Always hand threatened cities to the council, don't ask") {
            council.standingAnswer = Council.Order.Hold.name
            council.handOverForThreat(city)
            worldScreen.shouldUpdate = true
            close()
        }.row()
        addCloseButton("Ignore") { answered(city) }
    }

    private fun unhappy(city: City, happiness: Int) {
        addGoodSizedLabel("The empire is unhappy ([$happiness]).").row()
        addGoodSizedLabel("[${city.name}] is our largest city. The council can put it on culture and happiness.",
            size = Constants.defaultFontSize - 2).row()
        addButton("Hand [${city.name}] to the council - culture and happiness") {
            council.assign(city, Council.Order.Culture)
            council.answered[Council.UNHAPPY] = worldScreen.gameInfo.turns
            worldScreen.shouldUpdate = true
            close()
        }.row()
        addCloseButton("I'll handle it myself") { council.answered[Council.UNHAPPY] = worldScreen.gameInfo.turns }
    }

    private fun passed(city: City) {
        addGoodSizedLabel("The enemy has left [${city.name}]. The council offers it back.").row()
        addButton("Back to how it was") { council.threatPassed(city, keep = false); worldScreen.shouldUpdate = true; close() }.row()
        addCloseButton("Leave it with the council") { council.threatPassed(city, keep = true) }
    }

    /** The first defence the city can build now that it has not built: walls, castle... */
    private fun defensiveBuilding(city: City): Building? =
        city.cityConstructions.getBuildableBuildings()
            .filter { it.cityStrength > 0 && it.name !in city.cityConstructions.constructionQueue }
            .minByOrNull { it.cost }
}
