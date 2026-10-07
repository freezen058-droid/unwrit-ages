package com.unciv.logic

import com.unciv.logic.chain.ChallengeCondition
import com.unciv.logic.chain.ChallengeGoal
import com.unciv.logic.chain.ScenarioAuthoring
import com.unciv.logic.chain.ScenarioChapterPlan
import com.unciv.logic.chain.SharedScenario
import com.unciv.json.json
import org.junit.Assert.*
import org.junit.Test

class ChallengeGoalTests {
    private fun goal(mode: String = "milestone", delta: Boolean = false) = ChallengeGoal().apply {
        id = "test-goal"; packId = "expedition"; title = "Explore [10] new tiles"; label = "Explore new lands"
        this.mode = mode
        conditions.add(ChallengeCondition().apply { metric = "exploredTiles"; target = 10; this.delta = delta })
    }
    @Test fun deltaStartsAtChapterOriginAndDoesNotRewardOldExploration() {
        val g = goal(delta = true); g.initialize(mapOf("exploredTiles" to 100))
        g.observe(0, 0, 20, mapOf("exploredTiles" to 100)); assertEquals(-1, g.completedTurn)
        g.observe(3, 0, 20, mapOf("exploredTiles" to 109)); assertEquals(-1, g.completedTurn)
        g.observe(4, 0, 20, mapOf("exploredTiles" to 110)); assertEquals(4, g.completedTurn)
    }
    @Test fun deadlineStateDoesNotCountEarlierAchievementOrLateRecovery() {
        val g = goal("deadline")
        g.observe(3, 0, 20, mapOf("exploredTiles" to 10)); assertEquals(-1, g.completedTurn)
        g.observe(20, 0, 20, mapOf("exploredTiles" to 9)); assertEquals(-1, g.completedTurn)
        g.observe(21, 0, 20, mapOf("exploredTiles" to 20)); assertEquals(-1, g.completedTurn)
    }
    @Test fun sustainedGoalsIgnoreDuplicateRefreshesResetOnFailureAndDoNotFillSkippedTurns() {
        val g = goal("consecutive").apply { turns = 3 }
        g.observe(0, 0, 20, mapOf("exploredTiles" to 10)); assertEquals(0, g.streak)
        repeat(5) { g.observe(1, 0, 20, mapOf("exploredTiles" to 10)) }; assertEquals(1, g.streak)
        g.observe(2, 0, 20, mapOf("exploredTiles" to 9)); assertEquals(0, g.streak)
        g.observe(3, 0, 20, mapOf("exploredTiles" to 10)); assertEquals(1, g.streak)
        g.observe(5, 0, 20, mapOf("exploredTiles" to 10)); assertEquals(1, g.streak)
        g.observe(6, 0, 20, mapOf("exploredTiles" to 10)); g.observe(7, 0, 20, mapOf("exploredTiles" to 10))
        assertEquals(7, g.completedTurn)
    }
    @Test fun dataCombinationsSupportEitherOrAndExplicitTradeoffLimits() {
        val g = goal().apply { conditions.add(ChallengeCondition().apply { metric = "gold"; comparison = "atMost"; target = 100 }) }
        g.observe(1, 0, 10, mapOf("exploredTiles" to 10, "gold" to 101)); assertEquals(-1, g.completedTurn)
        g.observe(2, 0, 10, mapOf("exploredTiles" to 10, "gold" to 100)); assertEquals(2, g.completedTurn)
        val any = goal().apply { combination = "any"; conditions.add(ChallengeCondition().apply { metric = "cities"; target = 3 }) }
        any.observe(1, 0, 10, mapOf("exploredTiles" to 1, "cities" to 3)); assertEquals(1, any.completedTurn)
    }
    @Test fun unknownMetricsScriptsBranchesAndTiersFailClosed() {
        assertFalse(goal().apply { conditions[0].metric = "runScript" }.supported)
        assertFalse(goal().apply { mode = "execute" }.supported)
        assertFalse(goal().apply { outcomeBranches.add("chapter-2") }.supported)
        assertFalse(goal().apply { resultTiers.add("gold") }.supported)
        assertFalse(goal().apply { conditions.clear() }.supported)
        val missing = goal(); missing.observe(1, 0, 10, emptyMap()); assertEquals(-1, missing.completedTurn)
    }
    @Test fun saveRoundtripPreservesBaselineAndConsecutiveProgress() {
        val g = goal("consecutive", true).apply { turns = 3 }
        g.initialize(mapOf("exploredTiles" to 50)); g.observe(1, 0, 20, mapOf("exploredTiles" to 60))
        val copy = json().fromJson(ChallengeGoal::class.java, json().toJson(g))
        copy.observe(2, 0, 20, mapOf("exploredTiles" to 60)); copy.observe(3, 0, 20, mapOf("exploredTiles" to 60))
        assertEquals(3, copy.completedTurn); assertEquals(50, copy.baseline["exploredTiles"])
        copy.reset(); assertFalse(copy.initialized); assertEquals(-1, copy.completedTurn)
    }
    @Test fun challengeDefinitionIncludesNewRulesButNotMutableProgress() {
        val s = SharedScenario().apply { version = 2; id = "new"; civilization = "Rome"; optionalGoals = true
            technology = ""; building = ""; duration = 150; challengeGoals.add(goal()) }
        assertTrue(s.supported); val hash = s.definitionHash()
        s.challengeGoals[0].completedTurn = 5; assertEquals(hash, s.definitionHash())
        s.challengeGoals[0].conditions[0].target++; assertNotEquals(hash, s.definitionHash())
        s.version = 1; assertFalse(s.supported)
    }
    @Test fun sustainedGoalMustFitChapterAndLongChaptersAreBounded() {
        val p = ScenarioChapterPlan().apply { optionalGoals = true; duration = 150; challengeGoals.add(goal("consecutive").apply { turns = 151 }) }
        assertFalse(p.supported); p.challengeGoals[0].turns = 150; assertTrue(p.supported)
        p.duration = 301; assertFalse(p.supported)
    }
}
