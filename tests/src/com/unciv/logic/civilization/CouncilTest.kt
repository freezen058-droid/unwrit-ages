package com.unciv.logic.civilization

import com.unciv.logic.city.City
import com.unciv.logic.city.CityFocus
import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class CouncilTest {
    private val testGame = TestGame()
    private lateinit var civ: Civilization
    private lateinit var city: City

    @Before
    fun setUp() {
        testGame.makeHexagonalMap(4)
        civ = testGame.addCiv(isPlayer = true)
        city = testGame.addCity(civ, testGame.getTile(HexCoord.Zero))
    }

    @Test
    fun aCityHandedOverTakesTheOrdersFocusAndComesBack() {
        civ.council.assign(city, Council.Order.Culture)
        assertEquals(Council.Order.Culture, civ.council.orderOf(city))
        assertEquals(CityFocus.CultureFocus, city.getCityFocus())

        civ.council.assign(city, null)
        assertNull(civ.council.orderOf(city))
        assertEquals(CityFocus.NoFocus, city.getCityFocus())
    }

    @Test
    fun theAdvisorAlwaysGivesItsCitySomethingToBuild() {
        city.cityConstructions.constructionQueue.clear()
        civ.council.assign(city, Council.Order.Growth)
        civ.council.startTurn()
        assertTrue(city.cityConstructions.getCurrentConstruction() !is PerpetualConstruction)
    }

    @Test
    fun whatTheAdvisorChoseBetweenTurnsIsInTheNextReport() {
        city.cityConstructions.constructionQueue.clear()
        civ.council.assign(city, Council.Order.Production)   // chooses now, between turns
        civ.council.startTurn()
        assertTrue(civ.council.report.any { it.startsWith("[${city.name}]: now building") })
        assertTrue(civ.council.pending.isEmpty())
    }

    @Test
    fun stockpilingTrainsAUnitInPeacetime() {
        city.cityConstructions.constructionQueue.clear()
        civ.council.assign(city, Council.Order.Military)
        assertTrue(city.cityConstructions.getCurrentConstruction() is BaseUnit)
    }

    @Test
    fun theCouncilSurvivesASaveAndItsNameFollowsTheEra() {
        civ.council.assign(city, Council.Order.Hold)
        val copy = civ.clone()
        assertEquals(Council.Order.Hold.name, copy.council.cityOrders[city.id])
        assertEquals("Council of Elders", civ.council.name())
    }

    @Test
    fun anAiCivsCitiesAreNeverRunByACouncil() {
        val ai = testGame.addCiv()
        val aiCity = testGame.addCity(ai, testGame.getTile(3, 0))
        ai.council.cityOrders[aiCity.id] = Council.Order.Military.name
        ai.council.startTurn()   // does nothing harmful; the construction hook ignores AI councils
        aiCity.cityConstructions.constructionQueue.clear()
        aiCity.cityConstructions.chooseNextConstruction()
        assertTrue(aiCity.cityConstructions.constructionQueue.isNotEmpty())
    }

    private fun enemyArmyNearTheCity(): Civilization {
        val enemy = testGame.addCiv()
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        testGame.addUnit("Warrior", enemy, testGame.getTile(2, 0))
        testGame.addUnit("Warrior", enemy, testGame.getTile(0, 2))
        civ.cache.updateViewableTiles()
        return enemy
    }

    @Test
    fun anArmyNearACityAsksThePlayerOnceAndHoldsWhenHandedOver() {
        enemyArmyNearTheCity()
        civ.council.startTurn()
        assertEquals(listOf("threat:${city.id}:2"), civ.council.reports)
        civ.council.startTurn()
        assertEquals(1, civ.council.reports.size)   // not asked twice for the same threat

        civ.council.reports.clear()
        civ.council.handOverForThreat(city)
        assertEquals(Council.Order.Hold, civ.council.orderOf(city))
        civ.council.startTurn()
        assertTrue(civ.council.reports.isEmpty())   // held: nothing to ask
    }

    @Test
    fun theStandingAnswerHandsTheCityOverWithoutAsking() {
        enemyArmyNearTheCity()
        civ.council.standingAnswer = Council.Order.Hold.name
        civ.council.startTurn()
        assertTrue(civ.council.reports.isEmpty())
        assertEquals(Council.Order.Hold, civ.council.orderOf(city))
        assertTrue(city.id in civ.council.takenForThreat)
    }

    @Test
    fun whenTheThreatPassesTheCouncilOffersTheCityBack() {
        civ.council.handOverForThreat(city)
        civ.council.startTurn()                       // no enemies around
        assertEquals(listOf("passed:${city.id}"), civ.council.reports)
        civ.council.threatPassed(city, keep = false)
        assertNull(civ.council.orderOf(city))
    }

    @Test
    fun anUnhappyEmpireIsOfferedCultureForItsLargestCity() {
        civ.council.startTurn()
        assertTrue(civ.council.reports.none { it.startsWith("unhappy:") })   // a content empire: nothing to ask

        city.population.setPopulation(40)
        civ.updateStatsForNextTurn()
        assertTrue("the setup must make the empire unhappy", civ.getHappiness() < 0)
        civ.council.startTurn()
        assertTrue(civ.council.reports.any { it.startsWith("unhappy:${city.id}:") })

        civ.council.reports.clear()
        civ.council.answered[Council.UNHAPPY] = civ.gameInfo.turns
        civ.council.startTurn()
        assertTrue(civ.council.reports.none { it.startsWith("unhappy:") })   // answered: not again so soon
    }
}
