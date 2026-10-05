package com.unciv.logic.chain

import com.unciv.logic.GameInfo
import com.unciv.logic.IsPartOfGameInfoSerialization

/** A single branching epilogue; it never replaces the first chapter's shared challenge result. */
class SharedScenarioChapter : IsPartOfGameInfoSerialization {
    var path = ""
    var startTurn = 0
    var startingGold = 0
    var researchTurn = -1
    var constructionTurn = -1
    var treasuryTurn = -1
    var peaceTurn = -1
    var holdTurn = -1
    var outcome = ""
    var finalGold = 0
    var finalCities = 0
    var finalAtWar = false
    var briefingShown = false
    var resultShown = false
    val deadline get() = startTurn + 15
    val completedCount get() = if (path == "renewal")
        listOf(treasuryTurn, peaceTurn, holdTurn).count { it >= 0 }
        else listOf(researchTurn, constructionTurn, holdTurn).count { it >= 0 }
    val supported get() = path in listOf("renewal", "recovery") && startTurn in 0..(Int.MAX_VALUE - 15)

    fun copy() = com.unciv.json.json().fromJson(SharedScenarioChapter::class.java,
        com.unciv.json.json().toJson(this))

    fun observe(game: GameInfo, scenario: SharedScenario) {
        if (!supported || outcome.isNotEmpty() || game.turns < startTurn) return
        val civ = game.civilizations.firstOrNull { it.civID == scenario.civilization } ?: return
        evaluate(game.turns, scenario.technology in civ.tech.techsResearched,
            civ.cities.any { it.id == scenario.cityId }, civ.isDefeated(), civ.gold, civ.cities.size,
            civ.diplomacy[scenario.opponent]?.diplomaticStatus?.name == "War")
    }

    fun evaluate(turn: Int, researched: Boolean, ownsCity: Boolean, defeated: Boolean,
                 gold: Int, cities: Int, atWar: Boolean) {
        if (!supported || outcome.isNotEmpty() || turn < startTurn) return
        if (turn <= deadline) {
            if (researched && researchTurn < 0) researchTurn = turn
            if (gold.toLong() >= startingGold.toLong() + 100 && treasuryTurn < 0) treasuryTurn = turn
        }
        if (defeated || turn >= deadline) {
            if (!defeated && turn == deadline) {
                if (ownsCity) holdTurn = turn
                if (!atWar) peaceTurn = turn
            }
            outcome = if (defeated) "defeated" else if (completedCount == 3) "completed" else "unfinished"
            finalGold = gold; finalCities = cities; finalAtWar = atWar
        }
    }
}
