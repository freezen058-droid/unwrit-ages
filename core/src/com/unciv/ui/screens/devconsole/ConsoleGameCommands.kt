package com.unciv.ui.screens.devconsole

import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.VictoryData
import com.unciv.logic.civilization.Civilization
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.utils.Concurrency

internal class ConsoleGameCommands : ConsoleCommandNode {
    override val subcommands = hashMapOf<String, ConsoleCommand>(
        "setdifficulty" to ConsoleAction("game setdifficulty <difficulty>") { console, params ->
            val difficulty = params[0].findOrNull(console.gameInfo.ruleset.difficulties.values)
                ?: throw ConsoleErrorException("Unrecognized difficulty")
            console.gameInfo.difficulty = difficulty.name
            console.gameInfo.setTransients()
            DevConsoleResponse.OK
        },

        // There is no other way to reach a finished game on demand, and the victory screen - and
        // the certificate offered on it - is otherwise only testable by actually winning one.
        "victory" to ConsoleAction("game victory [civName] [victoryType]") { console, params ->
            val civ = console.getCivByNameOrSelected(params.getOrNull(0))
            // victories is a LinkedHashMap, so "the first one" is the ruleset's own order and the
            // same one every time - not whatever a hash happened to put in front.
            val victoryType = params.getOrNull(1)
                ?.let { it.findOrNull(console.gameInfo.ruleset.victories.keys)
                    ?: throw ConsoleErrorException("Unrecognized victory type") }
                ?: console.gameInfo.ruleset.victories.keys.first()
            console.gameInfo.victoryData = VictoryData(civ, victoryType, console.gameInfo.turns)
            // WorldScreen.update() opens the VictoryScreen itself, but only when no popup is open -
            // and this console is one. So mark it dirty here and it lands as the console closes.
            console.screen.shouldUpdate = true
            DevConsoleResponse.hint("[${civ.civName}] wins by [$victoryType] - close the console to see it")
        },

        // Draws this game's certificate as the mint would, into the save folder, without spending
        // anything - the way to check the picture on a real device.
        "certificate" to ConsoleAction("game certificate") { console, _ ->
            val civ = console.screen.selectedCiv
            val record = com.unciv.logic.chain.VictoryCertificate.record(console.gameInfo, civ)
            val jpeg = com.unciv.logic.chain.ChainWallet.service.renderCertificate(
                com.unciv.logic.chain.VictoryCertificate.inscription(record),
                com.unciv.logic.chain.CertificateEmblem(record.nation, record.emblemOuter, record.emblemInner),
                record.anchored
            ) ?: throw ConsoleErrorException("This platform cannot draw a certificate")
            val file = com.unciv.UncivGame.Current.files.getSave("certificate-preview.jpg")
            file.writeBytes(jpeg, false)
            DevConsoleResponse.hint("[${jpeg.size}] bytes written to [${file.path()}]")
        },

        "setturn" to ConsoleAction("game setturn <nonNegativeAmount>") { console, params ->
            val turn = params[0].toInt()
            console.gameInfo.turns = turn
            console.gameInfo.setTransients()
            DevConsoleResponse.OK
        },

        "add-spectator" to ConsoleAction("game add-spectator") { console, _ ->
            val existingSpectator = console.gameInfo.getSpectatorOrNull()
            if (existingSpectator != null) throw ConsoleErrorException("Spectator already exists")
            console.gameInfo.getSpectator("")
            DevConsoleResponse.OK
        },

        "remove-spectator" to ConsoleAction("game remove-spectator") { console, _ ->
            val existingSpectator = console.gameInfo.getSpectatorOrNull()
                ?: throw ConsoleErrorException("No Spectator in this game")
            ConfirmPopup(console.screen, "Warning: This needs to save and reload an autosave", "Do it anyway") {
                doRemoveSpectator(console, existingSpectator)
            }.open(true)
            DevConsoleResponse.OK
        },
    )

    /** Similar to [GameInfo.getSpectator], but for single player only (no player id check), and won't automatically add one */
    private fun GameInfo.getSpectatorOrNull() = civilizations.firstOrNull { it.nation.isSpectator }

    private fun doRemoveSpectator(console: DevConsolePopup, existingSpectator: Civilization) {
        val game = console.gameInfo
        if (game.currentPlayerCiv == existingSpectator)
            game.currentPlayer = ""
        game.civilizations.remove(existingSpectator)
        // dunno why a spectator makes it into everybody's diplomacy maps
        for (civ in game.civilizations)
            civ.diplomacy.remove(Constants.spectator)
        // Saving and reloading clears up the rest
        UncivGame.Current.files.autosaves.autoSave(console.gameInfo)
        console.screen.shouldUpdate = false
        UncivGame.Current.removeScreensOfType(WorldScreen::class)
        val newGame = UncivGame.Current.files.autosaves.loadLatestAutosave()
        Concurrency.runOnGLThread {
            UncivGame.Current.loadGame(newGame, callFromLoadScreen = true)
        }
    }
}
