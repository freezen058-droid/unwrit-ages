package com.unciv.ui.components.widgets

import com.badlogic.gdx.graphics.g2d.BitmapFont
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
    /** A font to draw the box and its rows in instead of the skin's; see [LanguagePickerScreen]. */
    font: BitmapFont? = null,
    private val onSelect: (String) -> Unit
) : SelectBox<LanguageSelectBox.Choice>(fingerSizedStyle(font)) {

    /** [language] is the key as the settings and the translation files spell it; what the player
     *  reads is the name in its own script, then in English, so that a player who reads only one
     *  of them can still find it - on the first launch nothing has been translated yet. */
    class Choice(val language: String) {
        override fun toString() = nativeNames[language]?.let { "$it — ${language.replace("_", " ")}" }
            ?: language.replace("_", " ")
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
        private fun fingerSizedStyle(font: BitmapFont?): SelectBoxStyle {
            val style = SelectBoxStyle(BaseScreen.skin.get(SelectBoxStyle::class.java))
            style.listStyle = GdxList.ListStyle(style.listStyle)
            if (font != null) {
                style.font = font
                style.listStyle.font = font
            }
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
         * Three: English and both Chinese scripts. Every other translation needs this fork's own
         * strings - the wallet, the certificate, the guide - kept current as they change, and a
         * fee or a hint showing in English inside a French interface is worse than not offering
         * French. The launch supports three well rather than ten partly; more can come back one
         * at a time, each with its strings finished. (Was fourteen, then ten.)
         */
        /** The shipped languages that are not written in Latin letters, in their own script. */
        private val nativeNames = mapOf(
            "Simplified_Chinese" to "简体中文",
            "Traditional_Chinese" to "繁體中文",
        )

        val SHIPPED_LANGUAGES = setOf(
            Constants.english,
            "Simplified_Chinese",
            "Traditional_Chinese",
        )
    }
}
