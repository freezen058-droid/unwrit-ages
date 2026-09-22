package com.unciv.ui.components.widgets

import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable
import com.unciv.Constants
import com.unciv.ui.components.input.onChange
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.options.OptionsPopup
import com.unciv.ui.screens.LanguagePickerScreen
import com.unciv.ui.screens.basescreen.BaseScreen
import com.badlogic.gdx.scenes.scene2d.ui.List as GdxList

/**
 *  One dropdown holding every language this game ships - English first, the rest alphabetically.
 *
 *  Used by both [LanguagePickerScreen] and [OptionsPopup]. It replaced a column of ten tappable
 *  boxes: ten needed scrolling on a phone and left most of a landscape screen empty, and the order
 *  they were in - English, then the device's language, then by how complete each translation was -
 *  ranked them by a number the player cannot see and put the same nine languages in a different
 *  order on every device.
 *
 *  No flags. A flag stands for a state, not a language, and Spanish, Russian, Portuguese and both
 *  Chinese scripts are each spoken across borders that picking one flag takes a side in.
 */
class LanguageSelectBox(
    private val onSelect: (String) -> Unit
) : SelectBox<LanguageSelectBox.Choice>(fingerSizedStyle()) {

    /** [language] is the key as the settings and the translation files spell it; what the player
     *  reads is the same name with its underscores opened out. */
    class Choice(val language: String) {
        override fun toString() = language.replace("_", " ")
        // SelectBox.selected = needs these, or setting the selection by value does nothing.
        override fun equals(other: Any?) = other is Choice && language == other.language
        override fun hashCode() = language.hashCode()
    }

    /** Set while the selection is being moved from code, so that reflecting the current language
     *  does not read back as the player having just picked one. */
    private var settingFromCode = false

    init {
        val ordered = listOf(Constants.english) +
            // Sorted on the name as displayed, because that is the string the player reads down.
            SHIPPED_LANGUAGES.filter { it != Constants.english }.sortedBy { it.replace("_", " ") }
        setItems(*ordered.map { Choice(it) }.toTypedArray())
        onChange {
            if (settingFromCode) return@onChange
            onSelect((selected ?: return@onChange).language)
        }
    }

    /** Show [language] as the current one without reporting it as a fresh choice. */
    fun showSelected(language: String) {
        val choice = items.firstOrNull { it.language == language } ?: return
        if (selected == choice) return
        settingFromCode = true
        selected = choice
        settingFromCode = false
    }

    companion object {
        /**
         * A style whose dropdown rows are big enough to hit with a thumb.
         *
         * libGDX sizes a row from the font plus the vertical padding of the list's selection
         * drawable, so padding that drawable is the only lever. Both the style and the list style
         * are copied and the drawable is a fresh tint - the skin's own instances are shared with
         * every other dropdown in the game, and the default rows are about 42px, which is under
         * half of what a finger needs.
         */
        private fun fingerSizedStyle(): SelectBoxStyle {
            val style = SelectBoxStyle(BaseScreen.skin.get(SelectBoxStyle::class.java))
            style.listStyle = GdxList.ListStyle(style.listStyle)
            val selection = ImageGetter.getWhiteDotDrawable()
                .tint(BaseScreen.skinStrings.skinConfig.baseColor)
            if (selection is BaseDrawable) {
                selection.topHeight = 14f
                selection.bottomHeight = 14f
            }
            style.listStyle.selection = selection
            return style
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
