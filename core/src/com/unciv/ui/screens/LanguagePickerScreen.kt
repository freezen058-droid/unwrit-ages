package com.unciv.ui.screens

import com.unciv.Constants
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.LanguageTable
import com.unciv.ui.components.widgets.LanguageTable.Companion.LanguageSelection
import com.unciv.ui.components.widgets.LanguageTable.Companion.addLanguageSelection
import com.unciv.ui.popups.options.OptionsPopup
import com.unciv.ui.screens.mainmenuscreen.MainMenuScreen
import com.unciv.ui.screens.pickerscreens.PickerScreen

/** A [PickerScreen] to select a language, used once on the initial run after a fresh install.
 *  After that, [OptionsPopup] provides the functionality.
 *  Reusable code is in [LanguageTable] and [addLanguageSelection].
 */
class LanguagePickerScreen : PickerScreen() {
    /**
     * English, not the device's language.
     *
     * This used to be `LocaleCode.getSystemLanguage()`, which answers from a list of every
     * language upstream ever had - so on a device set to one of the 37 this fork stopped
     * shipping it returned a language with no file and no row in the list, left the button
     * enabled, and wrote that language into the settings when pressed. English is both the safe
     * answer and the one a player expects to find already chosen.
     */
    private var chosenLanguage = Constants.english

    private val selection: LanguageSelection

    fun update() {
        selection.update(chosenLanguage)
    }

    init {
        closeButton.isVisible = false

        selection = topTable.addLanguageSelection { onChoice(it) }
        selection.englishRow.onClick { onChoice(Constants.english) }

        rightSideButton.setText("Pick language".tr())
        rightSideButton.onActivation {
            pickLanguage()
        }
        rightSideButton.keyShortcuts.add(KeyCharAndCode.RETURN)
        onChoice()
    }

    private fun onChoice(choice: String) {
        chosenLanguage = choice
        onChoice()
    }
    private fun onChoice() {
        rightSideButton.enable()
        update()
    }

    private fun pickLanguage() {
        game.settings.language = chosenLanguage
        game.settings.updateLocaleFromLanguage()
        game.settings.isFreshlyCreated = false     // mark so the picker isn't called next launch
        game.settings.save()

        game.translations.tryReadTranslationForCurrentLanguage()
        game.replaceCurrentScreen(MainMenuScreen())
    }
}
