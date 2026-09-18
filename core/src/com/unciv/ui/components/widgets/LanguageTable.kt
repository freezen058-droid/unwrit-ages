package com.unciv.ui.components.widgets

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.models.metadata.LocaleCode
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.KeyShortcutDispatcher
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.widgets.LanguageTable.Companion.addLanguageTables
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.options.OptionsPopup
import com.unciv.ui.screens.LanguagePickerScreen
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.civilopediascreen.FormattedLine
import com.unciv.ui.screens.civilopediascreen.MarkupRenderer


/** Represents a row in the Language picker, used both in [OptionsPopup] and in [LanguagePickerScreen]
 *  @see addLanguageTables
 */
internal class LanguageTable(val language: String, val percentComplete: Int) : Table() {
    private val baseColor = BaseScreen.skinStrings.skinConfig.baseColor
    private val darkBaseColor = baseColor.darken(0.5f)

    init{
        pad(10f)
        defaults().pad(10f)
        left()
        if(ImageGetter.imageExists("FlagIcons/$language"))
            add(ImageGetter.getImage("FlagIcons/$language")).size(40f)

        val spaceSplitLang = language.replace("_"," ")
        add(spaceSplitLang.toLabel())
        update("")
        touchable =
            Touchable.enabled // so click listener is activated when any part is clicked, not only children
        pack()
    }

    fun update(chosenLanguage: String) {
        background = BaseScreen.skinStrings.getUiBackground(
            "LanguagePickerScreen/LanguageTable",
            tintColor = if (chosenLanguage == language) baseColor else darkBaseColor
        )
    }

    companion object {
        /** Extension to add the Language boxes to a Table, used both in OptionsPopup and in LanguagePickerScreen */
        fun Table.addLanguageTables(expectedWidth: Float): ArrayList<LanguageTable> {
            val languageTables = ArrayList<LanguageTable>()

            val tableLanguages = Table()
            tableLanguages.defaults().uniformX().fillX().pad(10.0f)

            val systemLanguage = LocaleCode.getSystemLanguage()

            val languageCompletionPercentage = UncivGame.Current.translations
                .percentCompleteOfLanguages
            languageTables.addAll(
                languageCompletionPercentage
                .filter { it.key in SHIPPED_LANGUAGES }
                .map { LanguageTable(it.key, if (it.key == Constants.english) 100 else it.value) }
                .sortedWith(
                    compareBy<LanguageTable> { it.language != Constants.english }
                    .thenBy { it.language != systemLanguage }
                    .thenByDescending { it.percentComplete }
                )
            )

            languageTables.forEach {
                tableLanguages.add(it).row()
            }
            add(tableLanguages).row()

            return languageTables
        }

        /**
         * The languages this game ships.
         *
         * Upstream lists all 48 translations it has ever received, at every level of
         * completeness, with a note asking the player to help finish them. That is right for a
         * community project and wrong for a published game: a player opening the language list
         * should be choosing, not auditing. Everything here is at 97% or better.
         */
        val SHIPPED_LANGUAGES = setOf(
            Constants.english,
            "Simplified_Chinese",       // 99%
            "Traditional_Chinese",      // 98%
            "Spanish",                  // 99%
            "Brazilian_Portuguese",     // 99%
            "Russian",                  // 99%
            "French",                   // 99%
            "German",                   // 97%
            "Japanese",                 // 97%
            "Indonesian",               // 99%
            "Italian",                  // 99%
            "Polish",                   // 99%
            "Turkish",                  // 98%
            "Vietnamese",               // 99%
        )

        /** Create round-robin letter key handling, such that repeatedly pressing 'R' will cycle through all languages starting with 'R' */
        fun Actor.addLanguageKeyShortcuts(languageTables: ArrayList<LanguageTable>, getSelection: ()->String, action: (String)->Unit) {
            // Yes this is too complicated. Trying to preserve existing architecture choices.
            // One - extending KeyShortcut to allow another type filtering by a lambda,
            //       then teach KeyShortcutDispatcher.Resolver to recognize that - and pass on the actual key to its activation - could help.
            // Two - Changing addLanguageTables above to an actual container class holding the LanguageTables - could help.
            fun activation(letter: Char) {
                val candidates = languageTables.filter { it.language.first() == letter }
                if (candidates.isEmpty()) return
                if (candidates.size == 1) return action(candidates.first().language)
                val currentIndex = candidates.indexOfFirst { it.language == getSelection() }
                val newSelection = candidates[(currentIndex + 1) % candidates.size]
                action(newSelection.language)
            }

            val letters = languageTables.map { it.language.first() }.toSet()
            for (letter in letters) {
                keyShortcuts.add(KeyShortcutDispatcher.KeyShortcut(KeyboardBinding.None, KeyCharAndCode(letter), 0)) {
                    activation(letter)
                }
            }
        }
    }
}
