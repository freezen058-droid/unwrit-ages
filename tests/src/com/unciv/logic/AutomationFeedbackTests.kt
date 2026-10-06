package com.unciv.logic

import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.json.json
import com.unciv.logic.civilization.AutomationFeedback
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.civilization.diplomacy.DiplomacyManager
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.metadata.GameSettings
import com.unciv.models.stats.Stats
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.screens.worldscreen.unit.actions.InstantImprovementPlacement
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsFromUniques
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class AutomationFeedbackTests {
    private fun test() = TestGame().apply {
        makeHexagonalMap(6)
        for (tile in tileMap.values) { tile.baseTerrain = Constants.grassland; tile.setTerrainTransients() }
        UncivGame.Current.settings.autoAssignCityProduction = false
    }

    private fun war(test: TestGame): Pair<com.unciv.logic.civilization.Civilization, com.unciv.logic.civilization.Civilization> {
        val ours = test.addCiv(isPlayer = true)
        val enemy = test.addCiv(test.ruleset.nations.getValue("Greece"))
        ours.diplomacy[enemy.civID] = DiplomacyManager(ours, enemy.civID).apply { diplomaticStatus = DiplomaticStatus.War }
        enemy.diplomacy[ours.civID] = DiplomacyManager(enemy, ours.civID).apply { diplomaticStatus = DiplomaticStatus.War }
        ours.viewableTiles = test.tileMap.values.toSet()
        return ours to enemy
    }

    @Test fun defaultsAutomateRoutineWorkButPreservePlayerChoices() {
        val s = GameSettings()
        assertTrue(s.autoAssignCityProduction); assertTrue(s.citiesAutoBombardAtEndOfTurn)
        assertTrue(s.autoBuildingRoads); assertFalse(s.automatedWorkersReplaceImprovements)
        assertFalse(s.stopAutomatedWorkersRemoveVegetation)
        assertFalse(s.automatedUnitsCanUpgrade); assertFalse(s.automatedUnitsChoosePromotions)
        assertFalse(s.automatedUnitsMoveOnTurnStart)
    }

    @Test fun sameKnownEnemyDoesNotCancelSleepOrReturnUnitToCycle() {
        val test = test(); val (ours, enemy) = war(test)
        val unit = test.addUnit("Warrior", ours, test.getTile(HexCoord.Zero))
        test.addUnit("Warrior", enemy, test.getTile(2, 0))
        ours.notifications.clear()
        unit.sleep()
        repeat(3) { com.unciv.logic.map.mapunit.UnitTurnManager(unit).startTurn() }
        assertTrue(unit.isSleeping()); assertFalse(ours.units.getDueUnits().contains(unit))
        assertTrue(ours.notifications.isEmpty())
    }

    @Test fun newEnemyWakesUnitOnceAndGivesReason() {
        val test = test(); val (ours, enemy) = war(test)
        val unit = test.addUnit("Warrior", ours, test.getTile(HexCoord.Zero))
        unit.sleep()
        test.addUnit("Warrior", enemy, test.getTile(2, 0))
        ours.notifications.clear()
        com.unciv.logic.map.mapunit.UnitTurnManager(unit).startTurn()
        assertFalse(unit.isSleeping()); assertEquals("New enemy nearby", unit.orderInterruptionReason)
        assertTrue(ours.units.getDueUnits().contains(unit))
        assertEquals(1, ours.notifications.size)
        unit.sleep(); com.unciv.logic.map.mapunit.UnitTurnManager(unit).startTurn()
        assertTrue(unit.isSleeping()); assertEquals(1, ours.notifications.size)
    }

    @Test fun sleepUntilHealedIsNotInterruptedByKnownEnemyOrLackOfHealing() {
        val test = test(); val (ours, enemy) = war(test)
        for (tile in test.tileMap.values) { tile.baseTerrain = "Coast"; tile.setTerrainTransients() }
        val unit = test.addUnit("Galleass", ours, test.getTile(HexCoord.Zero)).apply { health = 50 }
        test.addUnit("Trireme", enemy, test.getTile(2, 0))
        assertFalse(unit.canHealInCurrentTile())
        unit.sleep(untilHealed = true); com.unciv.logic.map.mapunit.UnitTurnManager(unit).startTurn()
        assertTrue(unit.isSleepingUntilHealed()); assertFalse(unit.isIdle())
        unit.health = 100; com.unciv.logic.map.mapunit.UnitTurnManager(unit).startTurn()
        assertFalse(unit.isSleeping()); assertEquals("Fully healed", unit.orderInterruptionReason)
    }

    @Test fun attackWakesSleepingUnitsButHealingDoesNot() {
        val test = test(); val (ours, _) = war(test)
        val unit = test.addUnit("Warrior", ours, test.getTile(HexCoord.Zero))
        unit.sleep(); unit.takeDamage(-1)
        assertTrue(unit.isSleeping())
        unit.takeDamage(5)
        assertFalse(unit.isSleeping()); assertEquals("Attacked while sleeping", unit.orderInterruptionReason)
    }

    @Test fun sleepAcknowledgementSurvivesCloneAndJsonAndLegacySleepStaysAsleep() {
        val test = test(); val (ours, enemy) = war(test)
        val unit = test.addUnit("Warrior", ours, test.getTile(HexCoord.Zero))
        test.addUnit("Warrior", enemy, test.getTile(2, 0)); unit.sleep()
        val clone = unit.clone()
        clone.sleepKnownEnemies.clear(); assertFalse(unit.sleepKnownEnemies.isEmpty())
        val restored = json().fromJson(MapUnit::class.java, json().toJson(unit))
        assertEquals(unit.sleepKnownEnemies, restored.sleepKnownEnemies)
        assertTrue(restored.sleepThreatsInitialized)
        unit.sleepThreatsInitialized = false; unit.sleepKnownEnemies.clear(); com.unciv.logic.map.mapunit.UnitTurnManager(unit).startTurn()
        assertTrue(unit.isSleeping())
    }

    @Test fun repeatedWakeReasonsAreMergedAndDeduplicatedWithoutPopups() {
        val test = test(); val (ours, _) = war(test)
        val a = test.addUnit("Warrior", ours, test.getTile(HexCoord.Zero))
        val b = test.addUnit("Warrior", ours, test.getTile(1, 0))
        ours.notifications.clear()
        ours.popupAlerts.clear()
        AutomationFeedback.unit(a, "New enemy nearby"); AutomationFeedback.unit(b, "New enemy nearby")
        AutomationFeedback.unit(a, "New enemy nearby")
        assertEquals(1, ours.notifications.size); assertEquals(2, ours.notifications.single().actions.size)
        assertTrue(ours.popupAlerts.isEmpty())
    }

    @Test fun invalidProductionReportsWhyOnceAndDoesNotKeepRepeatFlag() {
        val test = test(); val (ours, _) = war(test)
        val city = test.addCity(ours, test.getTile(HexCoord.Zero))
        val construction = test.createBaseUnit("Sword").apply {
            movement = 2; strength = 8; cost = 30; requiredResource = "Iron"
        }
        val c = city.cityConstructions
        c.constructionQueue.clear(); c.constructionQueue.add(construction.name); c.setRepeated(construction.name, true)
        ours.notifications.clear()
        c.endTurn(Stats()); c.endTurn(Stats())
        assertFalse(c.isRepeated(construction.name)); assertFalse(c.constructionQueue.contains(construction.name))
        assertEquals(1, ours.notifications.size)
        assertTrue(ours.notifications.single().text.contains("Iron"))
        assertTrue(ours.notifications.single().actions.single() is com.unciv.logic.civilization.CityAction)
    }

    @Test fun autoProductionDoesNotOverrideAnExistingManualOrder() {
        val test = test(); val (ours, _) = war(test)
        val city = test.addCity(ours, test.getTile(HexCoord.Zero))
        city.cityConstructions.constructionQueue.clear()
        city.cityConstructions.addToQueue("Warrior")
        UncivGame.Current.settings.autoAssignCityProduction = true
        city.cityConstructions.chooseNextConstruction()
        assertEquals("Warrior", city.cityConstructions.currentConstructionName())
    }

    @Test fun completingARepeatedUnitFromAFullQueueKeepsItsFreedSlot() {
        val test = test(); val (ours, _) = war(test)
        val city = test.addCity(ours, test.getTile(HexCoord.Zero)); val c = city.cityConstructions
        c.constructionQueue.clear(); c.addToQueue("Warrior")
        repeat(9) { c.addToQueue("Worker") }
        c.setRepeated("Warrior", true); assertTrue(c.isQueueFull())
        ours.notifications.clear()
        assertTrue(c.completeConstruction(test.ruleset.units.getValue("Warrior")))
        assertEquals(10, c.constructionQueue.size); assertEquals("Warrior", c.constructionQueue.last())
        assertTrue(c.isRepeated("Warrior")); assertFalse(ours.notifications.any { it.text.startsWith("Production stopped") })
    }

    @Test fun exhaustingIronAfterBuildingTheLastRepeatedUnitReportsTheReplacement() {
        val test = test(); val (ours, _) = war(test)
        val city = test.addCity(ours, test.getTile(HexCoord.Zero))
        ours.tech.techsResearched.addAll(test.ruleset.technologies.keys)
        val deposit = test.getTile(1, 0); test.addTileToCity(city, deposit)
        deposit.setTileResource(test.ruleset.tileResources.getValue("Iron")); deposit.resourceAmount = 1
        deposit.improvement = "Mine"; ours.cache.updateCivResources()
        assertEquals(1, ours.getResourceAmount("Iron"))
        val unit = test.createBaseUnit("Sword").apply {
            movement = 2; strength = 8; cost = 30; requiredResource = "Iron"
        }
        val c = city.cityConstructions
        c.constructionQueue.clear(); c.addToQueue(unit); c.addToQueue("Worker"); c.setRepeated(unit.name, true)
        ours.notifications.clear()
        assertTrue(c.completeConstruction(unit))
        assertFalse(c.isRepeated(unit.name)); assertEquals("Worker", c.currentConstructionName())
        val notice = ours.notifications.single { it.text.startsWith("Production stopped") }
        assertTrue(notice.text.contains("Iron")); assertTrue(notice.text.contains("Now producing: [Worker]"))
        val count = ours.notifications.size; c.endTurn(Stats()); assertEquals(count, ours.notifications.size)
    }

    @Test fun anIdleAutomatedWorkerReportsOnceAndKeepsWaitingForNewWork() {
        val test = TestGame().apply { makeHexagonalMap(0) }
        val civ = test.addCiv(isPlayer = true)
        val tile = test.setTileTerrain(HexCoord.Zero, Constants.grassland)
        test.addCity(civ, tile)
        val worker = test.addUnit("Worker", civ, tile).apply { automated = true }
        civ.notifications.clear()
        worker.doAction(); worker.doAction()
        assertTrue(worker.isAutomated())
        assertEquals("No available work", worker.orderInterruptionReason)
        assertEquals(1, civ.notifications.count { it.text.contains("No available work") })
    }

    @Test fun everyGreatImprovementUnitUsesActualPlacementRules() {
        for ((name, improvement) in listOf("Great General" to "Citadel", "Great Merchant" to "Customs house",
            "Great Scientist" to "Academy", "Great Engineer" to "Manufactory", "Great Artist" to "Landmark")) {
            val test = test(); val (ours, _) = war(test)
            val city = test.addCity(ours, test.getTile(HexCoord.Zero))
            val tile = test.getTile(1, 0); test.addTileToCity(city, tile)
            val unit = test.addUnit(name, ours, tile)
            val names = InstantImprovementPlacement.names(unit)
            assertTrue("$name: $names", names.any { it.equals(improvement, ignoreCase = true) })
            assertTrue(name, InstantImprovementPlacement.canPlace(unit, tile))
            val action = UnitActionsFromUniques.getImprovementConstructionActionsFromGeneralUnique(unit, tile).first()
            assertNotNull(name, action.action)
            assertFalse(name, InstantImprovementPlacement.canPlace(unit, city.getCenterTile()))
            val disabled = UnitActionsFromUniques.getImprovementConstructionActionsFromGeneralUnique(unit, city.getCenterTile()).first()
            assertNull(disabled.action); assertNotNull(disabled.disabledReason)
            val far = UnitActionsFromUniques.getImprovementConstructionActionsFromGeneralUnique(unit, test.getTile(5, 0)).first()
            assertEquals(if (name == "Great General") "Have this tile close to your borders"
                else "Have this tile inside your empire", far.disabledReason)
        }
    }
}
