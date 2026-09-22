package com.unciv.ui.screens

import com.unciv.Constants
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.LanguageSelectBox
import com.unciv.ui.popups.options.OptionsPopup
import com.unciv.ui.screens.mainmenuscreen.MainMenuScreen
import com.unciv.ui.screens.pickerscreens.PickerScreen

/** A [PickerScreen] to select a language, used once on the initial run after a fresh install.
 *  After that, [OptionsPopup] provides the functionality.
 *  The dropdown itself is [LanguageSelectBox].
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

    private val selectBox = LanguageSelectBox { chosenLanguage = it }

    init {
        closeButton.isVisible = false

        // A width both controls share. Left to itself the dropdown sizes to its longest name,
        // which on a landscape screen leaves one small box adrift in the middle of it.
        val controlWidth = 420f
        // "Language" rather than a string of our own: it is already translated into all ten,
        // being the name of the Options tab that holds this same dropdown.
        topTable.add("Language".toLabel(fontSize = Constants.headingFontSize)).padBottom(16f).row()
        topTable.add(selectBox).width(controlWidth).fillX().row()
        selectBox.showSelected(chosenLanguage)

        rightSideButton.setText("Pick language".tr())
        rightSideButton.onActivation { pickLanguage() }
        rightSideButton.keyShortcuts.add(KeyCharAndCode.RETURN)
        rightSideButton.enable()
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
