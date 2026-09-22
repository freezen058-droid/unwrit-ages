package com.unciv.ui.components.widgets

import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.Constants
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onChange
import com.unciv.ui.popups.options.OptionsPopup
import com.unciv.ui.screens.LanguagePickerScreen
import com.unciv.ui.screens.basescreen.BaseScreen

/** Represents a row in the Language picker, used both in [OptionsPopup] and in [LanguagePickerScreen]
 *  @see addLanguageSelection
 */
internal class LanguageTable(val language: String) : Table() {
    private val baseColor = BaseScreen.skinStrings.skinConfig.baseColor
    private val darkBaseColor = baseColor.darken(0.5f)

    init{
        pad(10f)
        defaults().pad(10f)
        left()
        // No flag icon. A flag is a state, not a language: Spanish, Russian, Portuguese and both
        // Chinese scripts are each spoken across borders that a single flag necessarily picks a
        // side in, and a store listing is not the place to do that. The name alone is unambiguous.

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
        /** One entry in the [LanguageSelection] dropdown. [language] is the key as the settings and
         *  the translation files spell it; what the player reads is [display] - the same name with
         *  its underscores opened out, or the prompt text for the entry that selects nothing. */
        internal class LanguageChoice(val language: String, private val display: String) {
            override fun toString() = display
            // SelectBox.selected = needs these, or setting the selection by value does nothing.
            override fun equals(other: Any?) = other is LanguageChoice && language == other.language
            override fun hashCode() = language.hashCode()
        }

        /** The language chooser: English as a row of its own, the rest behind one dropdown.
         *
         *  Ten boxes in a column needed scrolling on a phone and left most of a landscape screen
         *  empty; this fits without either. English is a row rather than one more line in the list
         *  because it is the default, and a default the player can see is worth more than the space
         *  it costs.
         */
        internal class LanguageSelection(
            val englishRow: LanguageTable,
            private val others: SelectBox<LanguageChoice>
        ) {
            /** Show [language] as the chosen one: the row lights up for English, and the dropdown
             *  names the language when it is one of the other nine, or goes back to its prompt. */
            fun update(language: String) {
                englishRow.update(language)
                others.selected = others.items.firstOrNull { it.language == language }
                    ?: others.items.first()
            }
        }

        /** Add the chooser to a Table. [onSelect] is called with a language whenever the player
         *  picks one - from the English row or from the dropdown. */
        internal fun Table.addLanguageSelection(onSelect: (String) -> Unit): LanguageSelection {
            // A fixed width for both controls. Left to themselves they size to their own text,
            // which on a landscape screen leaves two small boxes adrift in the middle of it - a
            // heading and one column of matching width read as something someone laid out.
            // "Language" rather than a new string of our own: it is already translated into all
            // ten, being the name of the Options tab that holds this same chooser.
            val controlWidth = 420f
            add("Language".toLabel(fontSize = Constants.headingFontSize)).padBottom(16f).row()

            val englishRow = LanguageTable(Constants.english)
            add(englishRow).width(controlWidth).fillX().padBottom(10f).row()

            // The first entry selects nothing - it is what the box reads when English is chosen, so
            // the box never claims a language that is not the one in force.
            val prompt = LanguageChoice("", "Other languages".tr())
            val choices = listOf(prompt) + SHIPPED_LANGUAGES
                .filter { it != Constants.english }
                // Sorted on the name as displayed, because that is the string the player reads down.
                .map { LanguageChoice(it, it.replace("_", " ")) }
                .sortedBy { it.toString() }

            val others = SelectBox<LanguageChoice>(BaseScreen.skin)
            others.setItems(*choices.toTypedArray())
            others.selected = prompt
            others.onChange {
                val picked = others.selected ?: return@onChange
                // Re-picking the prompt is not a choice; leave the selection where it was.
                if (picked.language.isEmpty()) return@onChange
                onSelect(picked.language)
            }
            add(others).width(controlWidth).fillX().row()

            return LanguageSelection(englishRow, others)
        }

        /**
         * The languages this game ships.
         *
         * Upstream lists all 48 translations it has ever received, at every level of
         * completeness, with a note asking the player to help finish them. That is right for a
         * community project and wrong for a published game: a player opening the language list
         * should be choosing, not auditing.
         *
         * Ten, not the fourteen that were here before. The cut was not about how complete the
         * translations are - upstream's are done and cost us nothing - but about the strings
         * *this fork* adds, the wallet and certificate ones above all. A fee disclosure showing
         * in English inside a Turkish interface is worse than not offering Turkish. Every
         * language below is one we keep our own strings current in; percentages measured
         * 2026-09-21, and the one key missing everywhere is ConditionalsOrder, a sorting
         * sentinel that is empty in French too.
         */
        val SHIPPED_LANGUAGES = setOf(
            Constants.english,
            "Simplified_Chinese",       // 100%
            "Traditional_Chinese",      // 100%
            "French",                   // 99.9%
            "Brazilian_Portuguese",     // 99.9%
            "Russian",                  // 99.8%
            "Spanish",                  // 99.7%
            "Polish",                   // 99.2%
            "German",                   // 97.9%
            "Japanese",                 // 97.0%
        )
    }
}
