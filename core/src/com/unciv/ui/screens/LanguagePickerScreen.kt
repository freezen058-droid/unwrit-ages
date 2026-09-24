package com.unciv.ui.screens

import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.fonts.Fonts
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

    /**
     * The skin's font at a bigger size, for the dropdown alone.
     *
     * A SelectBox draws with its style's font as is - unlike a Label it has no scale of its own -
     * and the skin's font is shared by every widget in the game, so scaling it would scale them
     * all. Glyphs are rendered at [Fonts.ORIGINAL_FONT_SIZE] and drawn scaled down, so 28 is as
     * sharp as 18. Created once per fresh install and disposed with this screen.
     */
    private val dropdownFont: BitmapFont = Fonts.fontImplementation.getBitmapFont().apply {
        data.markupEnabled = true
        data.setScale(dropdownFontSize / Fonts.ORIGINAL_FONT_SIZE)
    }

    private val selectBox = LanguageSelectBox(dropdownFont) { chosen ->
        chosenLanguage = chosen
        rightSideButton.setText(confirmText[chosen] ?: confirmText.getValue(Constants.english))
    }

    init {
        closeButton.isVisible = false

        // A width all the controls share. Left to itself the dropdown sizes to its longest name,
        // which on a landscape screen leaves one small box adrift in the middle of it.
        val controlWidth = 560f
        val textWidth = minOf(720f, stage.width - 64f)

        topTable.add("Unwrit Ages".toLabel(fontSize = 34)).padBottom(12f).row()
        // All three at once: nothing has been translated yet, and the player we are telling to
        // pick a language is exactly the one who may not read English.
        for (text in introductions) {
            val label = text.toLabel(fontSize = 22, alignment = Align.center)
            label.wrap = true
            topTable.add(label).width(textWidth).padBottom(10f).row()
        }
        topTable.add(selectBox).width(controlWidth).height(72f).padTop(18f).row()
        selectBox.showSelected(chosenLanguage)

        rightSideButton.setText(confirmText.getValue(Constants.english))
        rightSideButton.onActivation { pickLanguage() }
        rightSideButton.keyShortcuts.add(KeyCharAndCode.RETURN)
        rightSideButton.enable()
    }

    override fun dispose() {
        super.dispose()
        dropdownFont.dispose()
    }

    private fun pickLanguage() {
        game.settings.language = chosenLanguage
        game.settings.updateLocaleFromLanguage()
        game.settings.isFreshlyCreated = false     // mark so the picker isn't called next launch
        game.settings.save()

        game.translations.tryReadTranslationForCurrentLanguage()
        game.replaceCurrentScreen(MainMenuScreen())
    }

    private companion object {
        const val dropdownFontSize = 28f

        /** Written here rather than in the translation files: they are shown before any of those
         *  is loaded, and each is only ever shown in its own language. */
        val introductions = listOf(
            "A turn-based strategy game: lead one settler to an empire across six thousand years.\n" +
                "First, choose your language. You can change it later in Options.",
            "回合制策略游戏：从一位移民起步，用六千年建立你的帝国。\n请先选择语言，之后可在“选项”中更改。",
            "回合制策略遊戲：從一位移民起步，用六千年建立你的帝國。\n請先選擇語言，之後可在「選項」中更改。",
        )

        val confirmText = mapOf(
            Constants.english to "Continue",
            "Simplified_Chinese" to "确定",
            "Traditional_Chinese" to "確定",
        )
    }
}
