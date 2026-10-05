package com.unciv.logic.chain

import com.unciv.logic.GameInfo
import com.unciv.logic.IsPartOfGameInfoSerialization

/** Local scenario milestones, not a proof of legal play. A relay keeps the original deadline. */
class SharedScenario : IsPartOfGameInfoSerialization {
    var version = 1
    var id = ""
    var title = ""
    var civilization = ""
    var startTurn = 0
    var duration = 20
    var technology = "Currency"
    var cityId = ""
    var cityName = ""
    var building = "Market"
    var opponent = ""
    var researchTurn = -1
    var constructionTurn = -1
    var holdTurn = -1
    var outcome = "" // empty = underway, completed / unfinished / defeated
    var finalGold = 0
    var finalCities = 0
    var finalAtWar = false
    var briefingShown = false
    var resultShown = false

    val deadline get() = startTurn + duration
    val completedCount get() = listOf(researchTurn, constructionTurn, holdTurn).count { it >= 0 }
    val supported get() = version == 1 && id.isNotBlank() && civilization.isNotBlank() &&
        cityId.isNotBlank() && technology.isNotBlank() && building.isNotBlank() &&
        duration in 1..100 && startTurn in 0..(Int.MAX_VALUE - duration)

    /** Immutable goal definition used to reject comparisons against a changed challenge. */
    fun definitionHash(): String = ChainWallet.sha256Hex(com.unciv.json.json().toJson(listOf(
        version.toString(), id, civilization, startTurn.toString(), duration.toString(),
        technology, cityId, building, opponent)))

    fun copy() = SharedScenario().also {
        it.version = version; it.id = id; it.title = title; it.civilization = civilization
        it.startTurn = startTurn; it.duration = duration; it.technology = technology
        it.cityId = cityId; it.cityName = cityName; it.building = building; it.opponent = opponent
        it.researchTurn = researchTurn; it.constructionTurn = constructionTurn; it.holdTurn = holdTurn
        it.outcome = outcome; it.finalGold = finalGold; it.finalCities = finalCities
        it.finalAtWar = finalAtWar; it.briefingShown = briefingShown; it.resultShown = resultShown
    }

    /** Called from the engine as well as the UI, including automated turns. Never changes rules. */
    fun observe(game: GameInfo) {
        if (!supported || outcome.isNotEmpty() || game.turns < startTurn) return
        val civ = game.civilizations.firstOrNull { it.civID == civilization } ?: return
        evaluate(game.turns, technology in civ.tech.techsResearched,
            civ.cities.any { it.id == cityId }, civ.isDefeated(), civ.gold, civ.cities.size,
            civ.diplomacy[opponent]?.diplomaticStatus?.name == "War")
    }

    /** A construction counts only when it is added while the scenario player owns the city. */
    fun constructed(turn: Int, owner: String, city: String, name: String) {
        if (supported && outcome.isEmpty() && turn in startTurn..deadline &&
            owner == civilization && city == cityId && name == building && constructionTurn < 0)
            constructionTurn = turn
    }

    fun evaluate(turn: Int, researched: Boolean, ownsCity: Boolean, defeated: Boolean,
                          gold: Int, cities: Int, atWar: Boolean) {
        if (!supported || outcome.isNotEmpty() || turn < startTurn) return
        // Missed checkpoints must not turn achievements earned after the deadline into success.
        if (turn <= deadline && researched && researchTurn < 0) researchTurn = turn
        if (defeated || turn >= deadline) {
            if (!defeated && turn == deadline && ownsCity) holdTurn = turn
            outcome = if (defeated) "defeated" else if (completedCount == 3) "completed" else "unfinished"
            finalGold = gold; finalCities = cities; finalAtWar = atWar
        }
    }
}
