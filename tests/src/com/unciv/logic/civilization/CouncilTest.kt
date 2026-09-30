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
}
