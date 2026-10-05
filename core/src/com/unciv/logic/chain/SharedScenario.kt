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
    var chapter: SharedScenarioChapter? = null

    val hasNextChapter get() = id == "civilization-on-the-brink-v1" &&
        outcome in listOf("completed", "unfinished") && chapter == null
    val needsPopup get() = chapter?.takeIf { it.supported }?.let {
        !it.briefingShown || it.outcome.isNotEmpty() && !it.resultShown
    } ?: (!briefingShown || outcome.isNotEmpty() && !resultShown)

    /** Chapter one stays immutable. The next chapter starts when the player accepts it. */
    fun beginNextChapter(game: GameInfo): Boolean {
        if (!supported || !hasNextChapter) return false
        val civ = game.civilizations.firstOrNull { it.civID == civilization } ?: return false
        if (civ.isDefeated() || game.turns > Int.MAX_VALUE - 15) return false
        chapter = SharedScenarioChapter().apply {
            path = if (this@SharedScenario.outcome == "completed") "renewal" else "recovery"
            startTurn = game.turns; startingGold = civ.gold
            researchTurn = this@SharedScenario.researchTurn
            constructionTurn = this@SharedScenario.constructionTurn
            if (path == "recovery" && constructionTurn < 0 && civ.cities.any {
                    it.id == cityId && it.cityConstructions.isBuilt(building) }) constructionTurn = game.turns
        }
        chapter!!.observe(game, this)
        return true
    }

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
        it.chapter = chapter?.copy()
    }

    /** Called from the engine as well as the UI, including automated turns. Never changes rules. */
    fun observe(game: GameInfo) {
        if (!supported || game.turns < startTurn) return
        chapter?.observe(game, this)
        if (outcome.isNotEmpty()) return
        val civ = game.civilizations.firstOrNull { it.civID == civilization } ?: return
        evaluate(game.turns, technology in civ.tech.techsResearched,
            civ.cities.any { it.id == cityId }, civ.isDefeated(), civ.gold, civ.cities.size,
            civ.diplomacy[opponent]?.diplomaticStatus?.name == "War")
    }

    /** A construction counts only when it is added while the scenario player owns the city. */
    fun constructed(turn: Int, owner: String, city: String, name: String) {
        chapter?.let {
            if (supported && it.path == "recovery" && it.outcome.isEmpty() &&
                turn in it.startTurn..it.deadline && owner == civilization && city == cityId &&
                name == building && it.constructionTurn < 0) it.constructionTurn = turn
        }
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
