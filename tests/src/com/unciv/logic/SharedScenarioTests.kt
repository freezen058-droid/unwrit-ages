package com.unciv.logic

import com.unciv.logic.chain.SharedScenario
import com.unciv.json.json
import org.junit.Assert.*
import org.junit.Test

class SharedScenarioTests {
    private fun scenario() = SharedScenario().apply {
        id = "fixture"; civilization = "Rome"; cityId = "original-capital"; startTurn = 40; duration = 20
    }

    @Test fun goalsAreParallelAndCompleteOnlyAtDeadline() {
        val s = scenario()
        s.constructed(48, "Rome", "original-capital", "Market")
        s.evaluate(49, true, true, false, 10, 2, true)
        assertEquals(2, s.completedCount); assertEquals("", s.outcome)
        s.evaluate(60, true, true, false, 70, 3, false)
        assertEquals("completed", s.outcome); assertEquals(60, s.holdTurn)
        s.evaluate(61, false, false, true, 0, 0, true)
        assertEquals(70, s.finalGold); assertEquals(3, s.completedCount)
    }

    @Test fun wrongCityEnemyConstructionAndLateResearchDoNotCount() {
        val s = scenario()
        s.constructed(45, "Rome", "new-capital", "Market")
        s.constructed(45, "Greece", "original-capital", "Market")
        s.constructed(61, "Rome", "original-capital", "Market")
        s.evaluate(61, true, true, false, 500, 2, false)
        assertEquals(0, s.completedCount); assertEquals("unfinished", s.outcome)
    }

    @Test fun recapturingOriginalCityWorksButReplacementCapitalDoesNot() {
        val s = scenario()
        s.evaluate(50, true, false, false, 10, 1, true)
        s.evaluate(60, true, true, false, 10, 2, true)
        assertEquals(60, s.holdTurn)
        val lost = scenario()
        lost.evaluate(60, true, false, false, 10, 2, true)
        assertEquals(-1, lost.holdTurn)
    }

    @Test fun cloneSerializationAndOldSavesPreserveCompatibility() {
        val game = GameInfo().apply { sharedScenario = scenario().apply { researchTurn = 45 } }
        val clone = game.clone()
        clone.sharedScenario!!.researchTurn = 55
        assertEquals(45, game.sharedScenario!!.researchTurn)
        val loaded = json().fromJson(GameInfo::class.java, json().toJson(game))
        assertEquals(45, loaded.sharedScenario!!.researchTurn)
        assertNull(json().fromJson(GameInfo::class.java, "{}").sharedScenario)
    }

    @Test fun defeatedAndFutureVersionsNeverBecomeSuccess() {
        val s = scenario()
        s.evaluate(45, true, false, true, 0, 0, true)
        assertEquals("defeated", s.outcome)
        val future = scenario().apply { version = 99 }
        future.evaluate(60, true, true, false, 100, 2, false)
        assertEquals("", future.outcome)
    }

    @Test fun engineObservationReadsSavedFactsAndDoesNotDependOnTheUI() {
        val s = scenario()
        val game = GameInfo().apply { turns = 48; sharedScenario = s }
        val civ = com.unciv.logic.civilization.Civilization("Rome").apply {
            nation = com.unciv.models.ruleset.nation.Nation()
            hasEverOwnedOriginalCapital = true
            tech.techsResearched.add("Currency")
            cities = listOf(com.unciv.logic.city.City().apply { id = "original-capital" })
        }
        game.civilizations.add(civ)
        s.observe(game)
        assertEquals(48, s.researchTurn)
        game.turns = 60; s.observe(game)
        assertEquals(60, s.holdTurn)
        assertEquals("unfinished", s.outcome)
    }

    @Test fun goalDefinitionsSurviveProgressButChangeWhenRulesChange() {
        val s = scenario()
        val definition = s.definitionHash()
        s.researchTurn = 49; s.finalGold = 100
        assertEquals(definition, s.definitionHash())
        s.duration = 25
        assertNotEquals(definition, s.definitionHash())
    }
}
