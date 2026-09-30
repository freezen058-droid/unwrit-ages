package com.unciv.logic

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.civilization.Council
import com.unciv.logic.files.UncivFiles
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.RulesetCache
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A long run with the council in charge of every city of the human player, from a real save:
 * nothing may throw, the orders survive every save and load, and the advisors keep the cities busy.
 *
 *     ./gradlew :tests:test --tests "com.unciv.logic.CouncilPlaythroughTest" -Dunciv.councilSave=/path/to/save
 *
 * Skipped without the property (the save is a player's game, not part of the repository).
 */
@RunWith(GdxTestRunner::class)
class CouncilPlaythroughTest {
    companion object {
        @BeforeClass @JvmStatic
        fun setup() {
            UncivGame.Current = UncivGame()
            UncivGame.Current.settings = GameSettings()
            UncivGame.Current.files = UncivFiles(Gdx.files)
            UncivGame.Current.musicController = com.unciv.ui.audio.MusicController()   // nextTurn picks a track every 10 turns
            RulesetCache.loadRulesets(noMods = true)
        }
    }

    @Test
    fun theCouncilRunsAnEmpireForFortyTurns() {
        val path = System.getProperty("unciv.councilSave") ?: System.getenv("COUNCIL_SAVE")
        Assume.assumeTrue(path != null && File(path).exists())
        var game = UncivFiles.gameInfoFromString(File(path!!).readText())
        val orders = Council.Order.entries
        val human = game.getCurrentPlayerCivilization()
        human.cities.forEachIndexed { i, city -> human.council.assign(city, orders[i % orders.size]) }
        human.council.garrisonCities = true
        human.council.standingAnswer = Council.Order.Hold.name
        val startTurn = game.turns
        var idleCityTurns = 0
        repeat(40) {
            game.nextTurn()
            // round trip through a save, as every autosave does
            game = UncivFiles.gameInfoFromString(UncivFiles.gameInfoToString(game))
            val civ = game.getCurrentPlayerCivilization()
            assertEquals(human.civName, civ.civName)
            // the player accepts every all-clear, as the popup's "Back to how it was" does
            for (item in civ.council.reports.filter { it.startsWith("passed:") }) {
                civ.council.reports.remove(item)
                civ.cities.firstOrNull { it.id == item.substringAfter(":") }?.let { civ.council.threatPassed(it, keep = false) }
            }
            for (city in civ.cities) {
                if (civ.council.orderOf(city) == null) continue
                if (city.cityConstructions.currentConstructionName().isEmpty()) idleCityTurns++
            }
        }
        val civ = game.getCurrentPlayerCivilization()
        println("turn ${startTurn} -> ${game.turns}; cities ${civ.cities.size}; " +
            "council cities ${civ.cities.count { civ.council.orderOf(it) != null }}; last report ${civ.council.report}")
        System.getenv("COUNCIL_SUMMARY")?.let { File(it).writeText("turn $startTurn -> ${game.turns}; cities ${civ.cities.size}; " +
            "orders ${civ.council.cityOrders.values}; buildings ${civ.cities.map { it.name + ":" + it.cityConstructions.getBuiltBuildings().count() }}; report ${civ.council.report}") }
        assertEquals("40 turns were played", startTurn + 40, game.turns)
        assertTrue("orders only for cities we hold", civ.council.cityOrders.keys.all { id -> civ.cities.any { it.id == id } })
        assertTrue("the council kept orders through 40 saves", civ.council.cityOrders.isNotEmpty())
        assertEquals("a council city was left with nothing to build", 0, idleCityTurns)
    }
}
