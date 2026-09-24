package com.unciv.dev

import com.badlogic.gdx.Gdx
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.GameStarter
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapSize
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.GameSettings
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.RulesetCache
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.RedirectOutput
import com.unciv.testing.RedirectPolicy
import org.junit.Assert
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Whole games played by the AI, with a save/load round trip every [CHUNK] turns - the way a phone
 * autosaves - to find crashes that only show up late or only after a reload.
 *
 * Too slow for every build, so it only runs when asked:
 * `UNCIV_SOAK=3 ./gradlew :tests:test --tests com.unciv.dev.SoakTest` (the number is how many games).
 */
@RunWith(BaseTestRunner::class)
@RedirectOutput(RedirectPolicy.Show)
class SoakTest {

    @Test
    fun aiGamesSurviveToTheEnd() {
        val games = System.getenv("UNCIV_SOAK")?.toIntOrNull() ?: 0
        Assume.assumeTrue("set UNCIV_SOAK to run", games > 0)

        RulesetCache.loadRulesets(noMods = true)
        UncivGame.Current = UncivGame()
        UncivGame.Current.files = UncivFiles(Gdx.files)
        UncivGame.Current.settings = GameSettings().apply { showTutorials = false }

        val failures = ArrayList<String>()
        for (seed in 1L..games) {
            try {
                playGame(seed)
            } catch (ex: Throwable) {
                failures += "seed $seed: ${ex.stackTraceToString().lines().take(25).joinToString("\n")}"
                println("SOAK seed $seed FAILED: $ex")
            }
        }
        Assert.assertTrue(failures.joinToString("\n\n"), failures.isEmpty())
    }

    private fun playGame(seed: Long) {
        val nations = listOf("Rome", "Greece", "The Ottomans", "England", "China", "Aztecs", "Egypt", "Japan")
            .shuffled(kotlin.random.Random(seed)).take(4)
        val parameters = GameParameters().apply {
            difficulty = "Prince"
            numberOfCityStates = 4
            victoryTypes = ArrayList(RulesetCache[baseRuleset]!!.victories.keys)
            players.clear()
            for (nation in nations) players.add(Player(nation, PlayerType.AI))
            players.add(Player(Constants.spectator, PlayerType.Human))
        }
        val mapParameters = MapParameters().apply {
            mapSize = MapSize.Small
            this.seed = seed
        }
        var game: GameInfo = GameStarter.startNewGame(GameSetupInfo(parameters, mapParameters))
        UncivGame.Current.gameInfo = game
        println("SOAK seed $seed: ${nations.joinToString()}")

        val start = System.currentTimeMillis()
        while (game.turns < MAX_TURNS) {
            game.simulateMaxTurns = game.turns + CHUNK
            game.simulateUntilWin = true
            game.nextTurn()

            val saved = UncivFiles.gameInfoToString(game)
            game = UncivFiles.gameInfoFromString(saved)
            UncivGame.Current.gameInfo = game

            val winner = game.civilizations.firstOrNull { it.victoryManager.hasWon() }
            val alive = game.civilizations.count { it.isMajorCiv() && it.isAlive() }
            println("SOAK seed $seed turn ${game.turns}: ${saved.length / 1024} KiB, $alive majors, " +
                "${game.chronicle.size} chronicle, ${(System.currentTimeMillis() - start) / 1000}s")
            if (winner != null) {
                println("SOAK seed $seed: ${winner.civName} won ${winner.victoryManager.getVictoryTypeAchieved()} on turn ${game.turns}")
                return
            }
        }
        println("SOAK seed $seed: no winner by turn $MAX_TURNS")
    }

    companion object {
        const val CHUNK = 25
        const val MAX_TURNS = 500
    }
}
