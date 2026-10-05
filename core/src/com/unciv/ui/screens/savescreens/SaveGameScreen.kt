package com.unciv.ui.screens.savescreens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.chain.ChainWallet
import com.unciv.logic.chain.SharedSaveIntent
import com.unciv.logic.chain.SharedSaveDifficulty
import com.unciv.logic.files.PlatformSaverLoader
import com.unciv.logic.files.UncivFiles
import com.unciv.models.translations.tr
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.components.widgets.TranslatedSelectBox
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.UncivTooltip.Companion.addTooltip
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.extensions.toCheckBox
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.ToastPopup
import com.unciv.utils.Concurrency
import com.unciv.utils.Log
import com.unciv.utils.launchOnGLThread


class SaveGameScreen(private val gameInfo: GameInfo) : LoadOrSaveScreen("Current saves") {
    private val gameNameTextField = UncivTextField(nameFieldLabelText)
    private var selectedIntent = gameInfo.sharedSaveIntent.takeIf { it in SharedSaveIntent.labels }.orEmpty()
    private var selectedDifficulty = gameInfo.sharedSaveDifficulty.takeIf { it in 1..5 } ?: 0
    private var selectedScenario = gameInfo.sharedScenario?.copy()

    companion object : Helpers {
        const val nameFieldLabelText = "Saved game name"
        const val saveButtonText = "Save game"
        const val savingText = "Saving..."
        const val saveToCustomText = "Save to custom location"
    }

    init {
        errorLabel.isVisible = false
        errorLabel.wrap = true

        setDefaultCloseAction()

        rightSideTable.initRightSideTable()

        rightSideButton.setText(saveButtonText.tr())
        rightSideButton.onActivation {
            val saveGameFile = game.files.getSave(gameNameTextField.text)
            if (saveGameFile.exists())
                doubleClickAction(saveGameFile)
            else saveGame(saveGameFile)
        }
        rightSideButton.keyShortcuts.add(KeyCharAndCode.RETURN)
        rightSideButton.enable()
    }

    private fun Table.initRightSideTable() {
        defaults().pad(0f, 10f, 0f, 10f)
        addGameNameField()

        // No "Copy to clipboard" or "Save to custom location": this fork can't load a game from
        // either (LoadGameScreen), so a save made there could never come back (user, 09-24). Code kept.
        add(errorLabel).width(stage.width / 2).center().row()
        row() // For uniformity with LoadScreen which has a load missing mods button here
        add(deleteSaveButton).row()
        add(showAutosavesCheckbox).row()
    }

    private fun Table.addGameNameField() {
        gameNameTextField.textFieldFilter = UncivFiles.fileNameTextFieldFilter()
        gameNameTextField.setTextFieldListener { textField, _ -> enableSaveButton(textField.text) }
        val defaultSaveName = "[${gameInfo.currentPlayer}] - [${gameInfo.turns}] turns".tr(hideIcons = true)
        gameNameTextField.text = defaultSaveName
        gameNameTextField.setSelection(0, defaultSaveName.length)

        add(nameFieldLabelText.toLabel()).row()
        add(gameNameTextField).width(300f).row()

        // Where the choice is made: the same setting as the Wallet popup's, shown here only when
        // saves are being recorded (user, 09-26 - they looked for it on this screen)
        val settings = game.settings
        if (settings.recordSavesOnChain && ChainWallet.service.isAvailable) {
            val intentTable = Table()
            fun updateIntent() {
                intentTable.clear()
                if (!settings.shareCloudSaves) return
                val goals = (selectedScenario?.let { "Goals [${it.completedCount}]/3 · [${it.duration}] turns".tr() }
                    ?: "Set goals for the next player".tr()).toTextButton()
                goals.name = "SharedSaveGoals"
                goals.onClick {
                    if (gameInfo.sharedScenario?.supported == true)
                        com.unciv.ui.popups.SharedScenarioPopup(this@SaveGameScreen, gameInfo)
                    else com.unciv.ui.popups.SharedScenarioEditor(this@SaveGameScreen, gameInfo, selectedScenario) {
                        selectedScenario = it
                        updateIntent()
                    }
                }
                intentTable.add(goals).width(300f).padTop(4f).row()
                val difficultyRow = Table()
                difficultyRow.add("Takeover difficulty (your estimate)".toLabel()).left().padRight(8f)
                val difficulty = TranslatedSelectBox(SharedSaveDifficulty.labels.values,
                    SharedSaveDifficulty.labels.getValue(selectedDifficulty))
                difficulty.name = "SharedSaveDifficulty"
                difficulty.onChange {
                    selectedDifficulty = SharedSaveDifficulty.labels.entries.first { it.value == difficulty.selected.value }.key
                }
                difficultyRow.add(difficulty).width(180f)
                intentTable.add(difficultyRow).padTop(8f).row()
                intentTable.add("1 star: easy / 5 stars: hard".toLabel(fontSize = 14)).row()
            }
            add("Share this save (anyone can load it)".toCheckBox(settings.shareCloudSaves) {
                settings.shareCloudSaves = it
                settings.save()
                updateIntent()
            }).padTop(6f).row()
            add(intentTable).row()
            updateIntent()
        }
    }

    private fun enableSaveButton(text: String) {
        rightSideButton.isEnabled = UncivFiles.isValidFileName(text)
    }

    private fun copyToClipboardHandler() {
        Concurrency.run("Copy game to clipboard") {
            // the Gzip rarely leads to ANRs
            try {
                Gdx.app.clipboard.contents = UncivFiles.gameInfoToString(gameInfo, forceZip = true)
                launchOnGLThread {
                    ToastPopup("Current game copied to clipboard!", this@SaveGameScreen)
                }
            } catch (ex: Throwable) {
                Log.error(saveToClipboardErrorMessage, ex)
                launchOnGLThread {
                    ToastPopup(saveToClipboardErrorMessage, this@SaveGameScreen)
                }
            }
        }
    }

    private fun Table.addSaveToCustomLocation() {
        val saveToCustomLocation = saveToCustomText.toTextButton()
        saveToCustomLocation.onClick {
            saveToCustomLocation.setText(savingText.tr())
            saveToCustomLocation.disable()
            errorLabel.isVisible = false
            Concurrency.runOnNonDaemonThreadPool(saveToCustomText) {

                game.files.saveGameToCustomLocation(gameInfo, gameNameTextField.text,
                    {
                        game.popScreen()
                    },
                    {
                        if (it !is PlatformSaverLoader.Cancelled) {
                            handleException(it, "Could not save game to custom location!")
                        }
                        saveToCustomLocation.setText(saveToCustomText.tr())
                        saveToCustomLocation.enable()
                    }
                )
            }
        }
        add(saveToCustomLocation).row()
    }

    private fun saveGame(saveGameFile: FileHandle) {
        if (gameInfo.sharedScenario?.supported != true)
            gameInfo.sharedScenario = selectedScenario?.copy()
        gameInfo.sharedSaveIntent = if (game.settings.recordSavesOnChain && game.settings.shareCloudSaves)
            selectedIntent else ""
        gameInfo.sharedSaveDifficulty = if (game.settings.recordSavesOnChain && game.settings.shareCloudSaves)
            selectedDifficulty else 0
        rightSideButton.setText(savingText.tr())
        // Disable while saving, mirroring addSaveToCustomLocation() below - a security audit
        // found this button had no such guard, so a double-tap (or holding Enter, since this
        // button's key shortcut stayed active) could fire two independent saveGame() calls before
        // the first completed. With recordOnChain=true, each independently opens its own wallet
        // signing round-trip - i.e. a real double-tap could charge the SKR save fee twice for what
        // the player perceives as one save.
        rightSideButton.disable()
        errorLabel.isVisible = false
        Concurrency.runOnNonDaemonThreadPool("SaveGame") {
            game.files.saveGame(gameInfo, saveGameFile, recordOnChain = true) { exception ->
                launchOnGLThread {
                    if (exception != null) {
                        handleException(exception, "Could not save game!", game.files.getSave(gameNameTextField.text))
                        rightSideButton.setText(saveButtonText.tr())
                        rightSideButton.enable()
                    }
                    else {
                        // The one place a save is unambiguously the player's own doing - autosaves
                        // and quicksaves do not come through this screen - so it is where the
                        // tutorial task can be ticked off.
                        UncivGame.Current.settings.addCompletedTutorialTask("Save your game")
                        UncivGame.Current.popScreen()
                    }
                }
            }
        }
    }

    override fun onExistingSaveSelected(saveGameFile: FileHandle) {
        gameNameTextField.text = saveGameFile.name()
    }

    override fun doubleClickAction(saveGameFile: FileHandle) {
        ConfirmPopup(
            this,
            "Overwrite existing file?",
            "Overwrite",
        ) { saveGame(saveGameFile) }.open()
    }

}
