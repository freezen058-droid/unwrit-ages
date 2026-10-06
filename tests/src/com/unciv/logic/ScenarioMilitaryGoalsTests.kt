package com.unciv.logic

import com.unciv.logic.chain.*
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class ScenarioMilitaryGoalsTests {
    private fun militaryScenario() = SharedScenario().apply {
        id = "military"; civilization = "Rome"; optionalGoals = true
        technology = ""; building = ""; duration = 10
        militaryGoals = ScenarioMilitaryGoals().apply { captureCityId = "enemy"; musterCityId = "home" }
    }
    @Test fun militaryGoalsRequireHoldingBothConditionsAtTheExactDeadline() {
        val s = militaryScenario()
        assertTrue(s.supported); assertEquals(2, s.goalCount)
        s.evaluate(9, false, false, false, 0, 1, true, true, true)
        assertEquals(-1, s.captureTurn); assertEquals(-1, s.musterTurn)
        s.evaluate(10, false, false, false, 0, 1, true, false, true)
        assertEquals("unfinished", s.outcome); assertEquals(1, s.completedCount)
        s.evaluate(11, false, false, false, 0, 2, true, true, true)
        assertEquals("unfinished", s.outcome); assertEquals(-1, s.captureTurn)
        val won = militaryScenario()
        won.evaluate(10, false, false, false, 0, 2, true, true, true)
        assertEquals("completed", won.outcome); assertEquals(2, won.completedCount)
        val late = militaryScenario()
        late.evaluate(11, false, false, false, 0, 2, true, true, true)
        assertEquals("unfinished", late.outcome); assertEquals(0, late.completedCount)
    }
    @Test fun captureUsesOriginalCityIdentityAndCurrentOwnership() {
        val t = TestGame().apply { makeHexagonalMap(6) }
        val player = t.addCiv(isPlayer = true); val enemy = t.addCiv()
        val home = t.addCity(player, t.getTile(0, 0)); val target = t.addCity(enemy, t.getTile(5, 0))
        val rules = ScenarioMilitaryGoals().apply { captureCityId = target.id }
        assertFalse(rules.captureHeld(t.gameInfo, player.civID))
        target.moveToCiv(player); assertTrue(rules.captureHeld(t.gameInfo, player.civID))
        target.moveToCiv(enemy); assertFalse(rules.captureHeld(t.gameInfo, player.civID))
        rules.captureCityId = "razed-or-missing-city"
        assertFalse(rules.captureHeld(t.gameInfo, player.civID)); assertNotEquals(home.id, target.id)
    }
    @Test fun musterCountsOnlyOwnMilitaryUnitsOfSelectedDomainWithinRadius() {
        val t = TestGame().apply { makeHexagonalMap(6) }
        val player = t.addCiv(isPlayer = true); val enemy = t.addCiv()
        val home = t.addCity(player, t.getTile(0, 0))
        t.addUnit("Warrior", player, t.getTile(1, 0))
        t.addUnit("Warrior", player, t.getTile(0, 1))
        val removable = t.addUnit("Archer", player, t.getTile(-1, 0))
        t.addUnit("Worker", player, t.getTile(0, -1))
        t.addUnit("Warrior", enemy, t.getTile(2, 0))
        t.addUnit("Warrior", player, t.getTile(5, 0))
        val rules = ScenarioMilitaryGoals().apply { musterCityId = home.id; musterCount = 3; musterRadius = 2 }
        assertTrue(rules.musterReady(t.gameInfo, player.civID))
        rules.musterCount = 4; assertFalse(rules.musterReady(t.gameInfo, player.civID))
        rules.musterCount = 3; rules.musterDomain = "Naval"
        assertFalse(rules.musterReady(t.gameInfo, player.civID))
        rules.musterDomain = "Land"; removable.destroy()
        assertFalse(rules.musterReady(t.gameInfo, player.civID))
        home.moveToCiv(enemy); rules.musterCount = 1
        assertFalse(rules.musterReady(t.gameInfo, player.civID))
    }
    @Test fun serializedMilitaryDefinitionsSurviveRevisionAndChapterTransition() {
        val t = TestGame().apply { makeHexagonalMap(3) }
        val civ = t.addCiv(isPlayer = true); val city = t.addCity(civ, t.getTile(0, 0))
        val g = t.gameInfo.apply { currentPlayer = civ.civID; currentPlayerCiv = civ }
        val p = ScenarioChapterPlan().apply {
            optionalGoals = true; militaryGoals = ScenarioMilitaryGoals().apply { musterCityId = city.id }
            goalOrder.add("Assemble troops")
        }
        g.sharedScenario = ScenarioAuthoring.draft(g, null, listOf(p, p.copy()))
        val s = g.clone().sharedScenario!!
        assertEquals(2, s.totalChapters); assertEquals(1, s.goalCount)
        assertEquals(s.definitionHash(), g.sharedScenario!!.definitionHash())
        assertNotEquals(s.definitionHash(), s.copy().apply { militaryGoals!!.musterRadius++ }.definitionHash())
        s.evaluate(20, false, false, false, 0, 1, false, false, true)
        g.sharedScenario = s; g.turns = 20
        assertTrue(s.beginNextChapter(g)); assertEquals(1, s.authoredChapter!!.goalCount)
        assertEquals(3, s.authoredChapter!!.militaryGoals!!.musterCount)
        assertEquals(-1, s.authoredChapter!!.musterTurn)
        assertTrue(ScenarioAuthoring.plans(s).all { it.militaryGoals?.musterCityId == city.id })
    }
    @Test fun engineObservationCombinesActualCaptureAndNavalMusterAtDeadline() {
        val t = TestGame().apply { makeHexagonalMap(6) }
        val player = t.addCiv(isPlayer = true); val enemy = t.addCiv()
        for (tile in t.tileMap.values) { tile.baseTerrain = "Coast"; tile.setTerrainTransients() }
        val homeTile = t.getTile(0, 0).apply { baseTerrain = "Grassland"; setTerrainTransients() }
        val targetTile = t.getTile(5, 0).apply { baseTerrain = "Grassland"; setTerrainTransients() }
        val home = t.addCity(player, homeTile); val target = t.addCity(enemy, targetTile)
        t.addUnit("Trireme", player, t.getTile(1, 0)); t.addUnit("Trireme", player, t.getTile(0, 1))
        t.addUnit("Work Boats", player, t.getTile(-1, 0))
        val g = t.gameInfo
        val s = militaryScenario().apply {
            civilization = player.civID
            militaryGoals!!.captureCityId = target.id; militaryGoals!!.musterCityId = home.id
            militaryGoals!!.musterDomain = "Naval"; militaryGoals!!.musterCount = 2
        }
        g.turns = 9; s.observe(g); assertEquals(0, s.completedCount)
        target.moveToCiv(player); g.turns = 10; s.observe(g)
        assertEquals("completed", s.outcome); assertEquals(2, s.completedCount)
        val tooMany = s.copy().apply { outcome = ""; captureTurn = -1; musterTurn = -1; militaryGoals!!.musterCount = 3 }
        tooMany.observe(g)
        assertEquals("unfinished", tooMany.outcome); assertEquals(-1, tooMany.musterTurn)
    }
}
