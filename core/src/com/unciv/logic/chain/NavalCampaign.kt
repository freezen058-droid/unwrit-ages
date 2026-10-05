package com.unciv.logic.chain

import com.unciv.logic.GameInfo
import com.unciv.logic.IsPartOfGameInfoSerialization

/** Fixed objectives on one shared world; completed chapters remain immutable. */
class NavalCampaign : IsPartOfGameInfoSerialization {
    companion object {
        /** The campaign's two belligerents cannot negotiate away its military objectives. */
        @yairm210.purity.annotations.Readonly
        fun blocksPeace(game: GameInfo, first: String, second: String): Boolean {
            val scenario = game.sharedScenario ?: return false
            val campaign = scenario.navalCampaign ?: return false
            if (!scenario.supported || scenario.freePlay || campaign.enemyPortId.isBlank() ||
                scenario.opponent.isBlank() || game.turns < scenario.startTurn) return false
            if (scenario.currentOutcome.isNotEmpty() &&
                (scenario.currentOutcome != "completed" || !scenario.hasNextChapter)) return false
            return (first == scenario.civilization && second == scenario.opponent) ||
                (second == scenario.civilization && first == scenario.opponent)
        }
    }

    var enemyPortId = ""
    var enemyPortName = ""
    var control: NavalChapter? = null
    var assault: NavalChapter? = null
    val active get() = assault ?: control
    val hasNextChapter get() = assault == null && (control == null || control?.outcome == "completed")

    fun beginNext(game: GameInfo, scenario: SharedScenario): Boolean {
        if (!hasNextChapter || scenario.freePlay || scenario.outcome != "completed" ||
            enemyPortId.isBlank() || game.turns > Int.MAX_VALUE - 35) return false
        val civ = game.civilizations.firstOrNull { it.civID == scenario.civilization } ?: return false
        if (civ.isDefeated() || civ.cities.none { it.id == scenario.cityId }) return false
        val chapter = NavalChapter().apply {
            number = if (control == null) 2 else 3
            startTurn = game.turns
        }
        if (chapter.number == 2) control = chapter else assault = chapter
        chapter.observe(game, scenario, this)
        return true
    }

    fun copy() = com.unciv.json.json().fromJson(NavalCampaign::class.java, com.unciv.json.json().toJson(this))
}

class NavalChapter : IsPartOfGameInfoSerialization {
    var number = 2
    var startTurn = 0
    var fleetTurn = -1
    var objectiveTurn = -1
    var holdTurn = -1
    var outcome = ""
    var briefingShown = false
    var resultShown = false
    var finalGold = 0
    var finalShips = 0
    val duration get() = if (number == 2) 25 else 35
    val deadline get() = startTurn + duration
    val supported get() = number in 2..3 && startTurn in 0..(Int.MAX_VALUE - duration)
    val completedCount get() = listOf(fleetTurn, objectiveTurn, holdTurn).count { it >= 0 }

    fun observe(game: GameInfo, scenario: SharedScenario, campaign: NavalCampaign) {
        if (!supported || outcome.isNotEmpty() || game.turns < startTurn) return
        val civ = game.civilizations.firstOrNull { it.civID == scenario.civilization } ?: return
        val ships = civ.units.getCivUnits().filter { it.baseUnit.isWaterUnit && it.baseUnit.isMilitary }.toList()
        val target = game.tileMap.values.firstOrNull { it.isCityCenter() && it.getCity()?.id == scenario.cityId }
        val fleetAtHome = if (target == null) 0 else ships.count { it.currentTile.aerialDistanceTo(target) <= 4 }
        val controlsStrait = target != null && ships.any { it.currentTile.aerialDistanceTo(target) <= 4 } &&
            game.civilizations.filter { it != civ && civ.isAtWarWith(it) }.none { opponent ->
                opponent.units.getCivUnits().any { it.baseUnit.isWaterUnit && it.currentTile.aerialDistanceTo(target) <= 4 }
            }
        val ownsPort = civ.cities.any { it.id == campaign.enemyPortId }
        evaluate(game.turns, if (number == 2) fleetAtHome >= 3 else ships.count { it.name == "Frigate" } >= 2,
            if (number == 2) controlsStrait else ownsPort,
            civ.cities.any { it.id == scenario.cityId }, civ.isDefeated(), civ.gold, ships.size)
    }

    fun evaluate(turn: Int, fleetReady: Boolean, objectiveHeld: Boolean, ownsHome: Boolean,
                 defeated: Boolean, gold: Int, ships: Int) {
        if (!supported || outcome.isNotEmpty() || turn < startTurn) return
        if (turn <= deadline && fleetReady && fleetTurn < 0 && (number != 2 || turn == deadline)) fleetTurn = turn
        if (defeated || turn >= deadline) {
            if (!defeated && turn == deadline) {
                if (objectiveHeld) objectiveTurn = turn
                if (ownsHome) holdTurn = turn
            }
            outcome = if (defeated) "defeated" else if (completedCount == 3) "completed" else "unfinished"
            finalGold = gold; finalShips = ships
        }
    }
}
