package com.unciv.logic.chain

import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.files.UncivFiles

/** A local retry snapshot, never a chain submission. Each distinct accepted state gets its own key. */
object ScenarioReplay {
    fun key(game: GameInfo): String = "Challenge start - " + ChainWallet.sha256Hex(
        "${game.gameId}|${game.sharedScenario!!.definitionHash()}|${game.continuedFromSave.orEmpty()}")

    private fun file(game: GameInfo) = UncivGame.Current.files.getLocalFile("ChallengeStarts").child(key(game))

    fun remember(game: GameInfo) {
        if (game.sharedScenario?.supported != true) return
        val files = UncivGame.Current.files
        val target = file(game)
        if (!target.exists()) {
            target.parent().mkdirs()
            files.saveGame(game, target, recordOnChain = false)
        }
    }

    fun available(game: GameInfo): Boolean = file(game).exists()

    fun load(game: GameInfo): GameInfo {
        val restored = UncivFiles.gameInfoFromString(file(game).readString("UTF-8"))
        check(restored.gameId == game.gameId && restored.sharedScenario?.definitionHash() == game.sharedScenario?.definitionHash())
        return restored
    }
}
