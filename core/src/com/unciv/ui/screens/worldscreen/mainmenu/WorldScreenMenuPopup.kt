package com.unciv.ui.screens.worldscreen.mainmenu

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.onLongPress
import com.unciv.logic.ProblemReport
import com.unciv.logic.files.UncivFiles
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ReportProblemPopup
import com.unciv.ui.popups.TutorialGuidePopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.savescreens.LoadGameScreen
import com.unciv.ui.screens.victoryscreen.VictoryScreen
import com.unciv.ui.screens.worldscreen.WorldScreen

/** The in-game menu called from the "Hamburger" button top-left
 *
 *  Popup automatically opens as soon as it's initialized
 */
class WorldScreenMenuPopup(
    val worldScreen: WorldScreen,
    expertMode: Boolean = false
) : Popup(worldScreen, scrollable = Scrollability.All) {
    private val singleColumn: Boolean
    private fun <T: Actor?> Cell<T>.nextColumn() {
        if (!singleColumn && column == 0) return
        row()
    }

    init {
        worldScreen.autoPlay.stopAutoPlay()
        defaults().fillX()

        val showSave = !worldScreen.gameInfo.gameParameters.isOnlineMultiplayer
        val showMusic = worldScreen.game.musicController.isMusicAvailable()
        val showConsole = WorldScreen.DEVELOPER_CONSOLE_OPEN && showSave && expertMode
        val buttonCount = 10 + (if (showSave) 1 else 0) + (if (showMusic) 1 else 0) + (if (showConsole) 1 else 0)

        val emptyPrefHeight = this.prefHeight
        val firstCell = addButton("Main menu") {
            worldScreen.game.goToMainMenu()
        }
        singleColumn = worldScreen.isCrampedPortrait() ||
            2 * prefWidth > maxPopupWidth ||  // Very coarse: Assume width of translated "Main menu" is representative
            buttonCount * (prefHeight - emptyPrefHeight) + emptyPrefHeight < maxPopupHeight
        firstCell.nextColumn()

        addButton("Civilopedia", KeyboardBinding.Civilopedia) {
            close()
            worldScreen.openCivilopedia()
        }.nextColumn()

        // The guide was reachable only from the main menu, which meant leaving the game to read
        // it - and its most useful page, the task list with what each unfinished one is waiting
        // for, is exactly what a player wants while playing.
        addButton("Guide", KeyboardBinding.None) {
            close()
            TutorialGuidePopup(worldScreen.stage, worldScreen.gameInfo.ruleset, gameInfo = worldScreen.gameInfo).open(true)
        }.nextColumn()
        if (showSave)
            addButton("Save game", KeyboardBinding.SaveGame) {
                close()
                worldScreen.openSaveGameScreen()
            }.nextColumn()
        addButton("Load game", KeyboardBinding.LoadGame) {
            close()
            worldScreen.game.pushScreen(LoadGameScreen())
        }.nextColumn()
        addButton("Start new game", KeyboardBinding.NewGame) {
            close()
            worldScreen.openNewGameScreen()
        }.nextColumn()
        // The council (phase 1): named for the era - Council of Elders, Royal Court, Privy Council, Cabinet
        addButton(worldScreen.selectedCiv.council.name(), KeyboardBinding.None) {
            close()
            com.unciv.ui.popups.CouncilPopup(worldScreen)
        }.nextColumn()
        addButton("Victory status", KeyboardBinding.VictoryScreen) {
            close()
            worldScreen.game.pushScreen(VictoryScreen(worldScreen))
        }.nextColumn()
        val optionsCell = addButton("Options", KeyboardBinding.Options) {
            close()
            worldScreen.openOptionsPopup()
        }
        optionsCell.actor.onLongPress {
            close()
            worldScreen.openOptionsPopup(withDebug = true)
        }
        optionsCell.nextColumn()
        if (showMusic)
            addButton("Music", KeyboardBinding.MusicPlayer) {
                close()
                WorldScreenMusicPopup(worldScreen).open(force = true)
            }.nextColumn()

        // A problem report the player sends themselves (user, 09-26: option B) - the picture is
        // taken once this menu is gone, so it shows the game, not the menu
        addButton("Report a problem", KeyboardBinding.None) {
            close()
            val save = try { UncivFiles.gameInfoToString(worldScreen.gameInfo, forceZip = true) } catch (_: Throwable) { null }
            ReportProblemPopup(worldScreen, "problem", save, ProblemReport.screenshot(worldScreen.stage))
        }.nextColumn()

        if (showConsole)
            addButton("Developer Console", KeyboardBinding.DeveloperConsole) {
                close()
                worldScreen.openDeveloperConsole()
            }.nextColumn()
        
        addButton("Exit") {
            close()
            Gdx.app.exit()
        }.apply { actor.style = BaseScreen.skin.get("negative", TextButtonStyle::class.java) }
            .nextColumn()

        addCloseButton().run { colspan(if (singleColumn || column == 1) 1 else 2) }
        pack()

        open(force = true)
    }
}
