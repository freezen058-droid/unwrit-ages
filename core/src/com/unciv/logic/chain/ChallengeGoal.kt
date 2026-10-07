package com.unciv.logic.chain

import com.unciv.logic.GameInfo
import com.unciv.logic.IsPartOfGameInfoSerialization

/** Data-only rules. No scripts, file access, transactions or world mutations. */
class ChallengeCondition : IsPartOfGameInfoSerialization {
    var metric = ""
    var comparison = "atLeast"
    var target = 1
    var delta = false
    val supported get() = metric in ChallengeGoal.METRICS && comparison in listOf("atLeast", "atMost", "equal") &&
        target in -1000000..1000000
    fun matches(value: Int): Boolean = when (comparison) {
        "atLeast" -> value >= target
        "atMost" -> value <= target
        "equal" -> value == target
        else -> false
    }
}

class ChallengeGoal : IsPartOfGameInfoSerialization {
    var engine = 1
    var id = ""
    var packId = ""
    var packRevision = 1
    var title = ""
    var label = ""
    var labelTw = ""
    var labelCn = ""
    var titleTw = ""
    var titleCn = ""
    var mode = "milestone" // milestone, deadline, consecutive
    var combination = "all" // all / any; chapter branches are a separate future capability
    var turns = 1
    var conditions = ArrayList<ChallengeCondition>()
    // Reserved formats fail closed until the engine implements them.
    var outcomeBranches = ArrayList<String>()
    var resultTiers = ArrayList<String>()
    var baseline = HashMap<String, Int>()
    var initialized = false
    var lastObservedTurn = -1
    var streak = 0
    var completedTurn = -1
    val supported get() = engine == 1 && id.matches(Regex("[a-z0-9-]{1,64}")) &&
        packId.matches(Regex("[a-z0-9-]{1,64}")) && packRevision in 1..1000000 && title.length in 1..160 && label.length in 1..32 && listOf(labelTw, labelCn).all { it.length <= 32 } &&
        listOf(titleTw, titleCn).all { it.length <= 160 } &&
        mode in listOf("milestone", "deadline", "consecutive") && combination in listOf("all", "any") &&
        turns in 1..300 && conditions.size in 1..8 && conditions.all { it.supported } &&
        outcomeBranches.isEmpty() && resultTiers.isEmpty()
    fun definitionParts(): List<String> = listOf("challenge-goal-v1", engine.toString(), id, packId, packRevision.toString(), title, label,
        mode, combination, turns.toString(), labelTw, labelCn, titleTw, titleCn) + conditions.flatMap {
        listOf(it.metric, it.comparison, it.target.toString(), it.delta.toString())
    }
    fun displayLabel(language: String): String = when (language) {
        "Traditional_Chinese" -> labelTw.ifEmpty { label }
        "Simplified_Chinese" -> labelCn.ifEmpty { label }
        else -> label
    }
    fun displayTitle(language: String): String = when (language) {
        "Traditional_Chinese" -> titleTw.ifEmpty { title }
        "Simplified_Chinese" -> titleCn.ifEmpty { title }
        else -> title
    }
    fun copy(): ChallengeGoal = com.unciv.json.json().fromJson(ChallengeGoal::class.java, com.unciv.json.json().toJson(this))
    fun reset() { baseline.clear(); initialized = false; lastObservedTurn = -1; streak = 0; completedTurn = -1 }
    fun initialize(values: Map<String, Int>) {
        if (initialized) return
        conditions.filter { it.delta }.forEach { baseline[it.metric] = values[it.metric] ?: 0 }
        initialized = true
    }
    fun observe(turn: Int, startTurn: Int, deadline: Int, values: Map<String, Int>) {
        if (!supported || turn !in startTurn..deadline || completedTurn >= 0) return
        initialize(values)
        val matches = conditions.map { rule -> values[rule.metric]?.let { current ->
            rule.matches(current - if (rule.delta) baseline[rule.metric] ?: 0 else 0)
        } ?: false }
        val met = if (combination == "all") matches.all { it } else matches.any { it }
        when (mode) {
            "milestone" -> if (met) completedTurn = turn
            "deadline" -> if (turn == deadline && met) completedTurn = turn
            "consecutive" -> {
                // One observation per completed turn, never count UI refreshes or the starting point.
                if (turn <= startTurn || turn <= lastObservedTurn) return
                streak = if (!met) 0 else if (lastObservedTurn == turn - 1) streak + 1 else 1
                lastObservedTurn = turn
                if (streak >= turns) completedTurn = turn
            }
        }
    }
    companion object {
        val METRICS = setOf("exploredTiles", "gold", "population", "cities", "happiness", "peace", "wonders", "technologies")
        fun snapshot(game: GameInfo, civilization: String): Map<String, Int> {
            val civ = game.civilizations.firstOrNull { it.civID == civilization } ?: return emptyMap()
            return mapOf("exploredTiles" to game.tileMap.values.count { it.isExplored(civ) },
                "gold" to civ.gold, "population" to civ.cities.sumOf { it.population.population },
                "cities" to civ.cities.size, "happiness" to civ.getHappiness(), "peace" to if (civ.getCivsAtWarWith().any { !it.isBarbarian && !it.isSpectator() }) 0 else 1,
                "wonders" to civ.cities.sumOf { city -> city.cityConstructions.getBuiltBuildings().count { it.isWonder } },
                "technologies" to civ.tech.techsResearched.size)
        }
    }
}
