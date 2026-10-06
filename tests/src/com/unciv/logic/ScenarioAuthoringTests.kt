package com.unciv.logic

import com.unciv.json.json
import com.unciv.logic.chain.ScenarioAuthoring
import com.unciv.logic.chain.ScenarioChapterPlan
import com.unciv.logic.chain.SharedScenario
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class ScenarioAuthoringTests {
    private fun scenario(mask: Int) = SharedScenario().apply {
        id = "optional"; civilization = "Rome"; startTurn = 0; duration = 10
        optionalGoals = true; technology = if (mask and 1 != 0) "Currency" else ""
        building = if (mask and 2 != 0) "Market" else ""; cityId = "construction-city"; cityName = "Rome"
        holdCityId = if (mask and 4 != 0) "defense-city" else ""; holdCityName = if (holdCityId.isEmpty()) "" else "Antium"
    }
    private fun game() = TestGame().apply {
        val civ = addCiv(isPlayer = true)
        addCity(civ, getTile(0, 0))
        gameInfo.currentPlayer = civ.civID; gameInfo.currentPlayerCiv = civ
    }.gameInfo

    @Test fun everyNonemptyGoalCombinationCompletesWithOnlyItsEnabledGoals() {
        for (mask in 1..7) {
            val s = scenario(mask)
            assertTrue(s.supported)
            if (mask and 2 != 0) s.constructed(2, "Rome", "construction-city", "Market")
            s.evaluate(3, mask and 1 != 0, true, false, 100, 2, false)
            assertEquals("", s.outcome)
            s.evaluate(10, mask and 1 != 0, mask and 4 != 0, false, 100, 2, false)
            assertEquals("mask=$mask", "completed", s.outcome)
            assertEquals(Integer.bitCount(mask), s.goalCount)
            assertEquals(s.goalCount, s.completedCount)
            if (mask and 1 == 0) assertEquals(-1, s.researchTurn)
            if (mask and 2 == 0) assertEquals(-1, s.constructionTurn)
            if (mask and 4 == 0) assertEquals(-1, s.holdTurn)
        }
    }

    @Test fun disabledOrMissingGoalsCannotCreateFalseAchievements() {
        val none = scenario(0); none.evaluate(10, true, true, false, 0, 0, false)
        assertFalse(none.supported); assertEquals("", none.outcome)
        val techOnly = scenario(1); techOnly.constructed(2, "Rome", "construction-city", "")
        techOnly.evaluate(10, false, true, false, 0, 2, false)
        assertEquals("unfinished", techOnly.outcome); assertEquals(0, techOnly.completedCount)
        val building = scenario(2); building.constructed(2, "Rome", "defense-city", "Market")
        building.evaluate(10, true, true, false, 0, 2, false)
        assertEquals("unfinished", building.outcome)
        val defeated = scenario(1); defeated.evaluate(3, true, true, true, 0, 0, false)
        assertEquals("defeated", defeated.outcome)
    }

    @Test fun localSerializationCanReopenForEditingButTakenOverChallengeCannot() {
        val g = GameInfo().apply { sharedScenario = scenario(1).apply { authorDraft = true } }
        val loaded = json().fromJson(GameInfo::class.java, json().toJson(g))
        assertTrue(ScenarioAuthoring.canEdit(loaded)); assertEquals("", loaded.sharedScenario!!.building)
        loaded.continuedFromSave = "parent-record"
        assertFalse(ScenarioAuthoring.canEdit(loaded))
        val oldLocal = GameInfo().apply { sharedScenario = scenario(7).apply { id = java.util.UUID.randomUUID().toString() } }
        assertTrue(ScenarioAuthoring.canEdit(oldLocal))
    }

    @Test fun changedDraftResetsHistoryAndUnchangedDraftKeepsResults() {
        val g = game().apply { turns = 20 }
        val plan = ScenarioChapterPlan().apply { optionalGoals = true; technology = "Currency"; duration = 10 }
        val original = ScenarioAuthoring.draft(g, null, listOf(plan))!!
        original.researchTurn = 25; original.outcome = "completed"
        val unchanged = ScenarioAuthoring.draft(g, original, listOf(plan))!!
        assertEquals(original.id, unchanged.id); assertEquals("completed", unchanged.outcome)
        g.turns = 30
        val changed = ScenarioAuthoring.draft(g, original, listOf(plan.copy().apply { technology = "Navigation" }))!!
        assertNotEquals(original.id, changed.id); assertNotEquals(original.definitionHash(), changed.definitionHash())
        assertEquals(30, changed.startTurn); assertEquals(-1, changed.researchTurn); assertEquals("", changed.outcome)
        assertEquals("completed", original.outcome)
        assertNull(ScenarioAuthoring.draft(g, original, listOf(ScenarioChapterPlan().apply { optionalGoals = true })))
    }

    @Test fun optionalChaptersCloneAndActivateWithTheirOwnGoalCount() {
        val g = game()
        val first = ScenarioChapterPlan().apply { optionalGoals = true; technology = "Currency"; duration = 10 }
        val second = ScenarioChapterPlan().apply { optionalGoals = true; technology = "Navigation"; duration = 15 }
        g.sharedScenario = ScenarioAuthoring.draft(g, null, listOf(first, second))
        val clone = g.clone().sharedScenario!!
        assertTrue(clone.optionalGoals); assertTrue(clone.nextChapterPlans[0].optionalGoals)
        assertEquals(1, clone.goalCount)
        g.sharedScenario!!.evaluate(10, true, false, false, 0, 1, false); g.turns = 10
        assertTrue(g.sharedScenario!!.beginNextChapter(g))
        assertEquals(1, g.sharedScenario!!.authoredChapter!!.goalCount)
        g.sharedScenario!!.authoredChapter!!.evaluate(25, true, false, false, 0, 1, false)
        assertEquals("completed", g.sharedScenario!!.currentOutcome)
    }

    @Test fun addingOptionalRulesChangesDefinitionButOldDefinitionsKeepTheirOriginalHash() {
        val legacy = scenario(7).apply { optionalGoals = false }
        val expected = com.unciv.logic.chain.ChainWallet.sha256Hex(json().toJson(listOf(
            legacy.version.toString(), legacy.id, legacy.civilization, legacy.startTurn.toString(), legacy.duration.toString(),
            legacy.technology, legacy.cityId, legacy.building, legacy.opponent)))
        assertEquals(expected, legacy.definitionHash())
        assertNotEquals(expected, legacy.copy().apply { optionalGoals = true }.definitionHash())
        assertNotEquals(scenario(4).definitionHash(), scenario(4).apply { holdCityId = "another-city" }.definitionHash())
    }
}
