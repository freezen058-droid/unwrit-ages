package com.unciv.ui.popups.options

import com.unciv.Constants
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.LanguageTable.Companion.addLanguageSelection
import com.unciv.ui.components.widgets.TabbedPager

internal class LanguageTab(
    optionsPopup: OptionsPopup
): OptionsPopupTab(optionsPopup) {
    private val selection by lazy { this.addLanguageSelection { onChoice(it) } }
    private var chosenLanguage = settings.language

    init {
        pad(0f)
        defaults().pad(0f)
    }

    override fun lateInitialize() {
        selection.englishRow.onClick { onChoice(Constants.english) }
        super.lateInitialize()
    }

    private fun onChoice(language: String) {
        chosenLanguage = language
        updateSelection()
    }

    private fun selectLanguage() {
        settings.language = chosenLanguage
        settings.updateLocaleFromLanguage()
        game.translations.tryReadTranslationForCurrentLanguage()
        reloadWorldAndOptions()
    }

    private fun updateSelection() {
        selection.update(chosenLanguage)
        if (chosenLanguage != settings.language)
            selectLanguage()
    }

    override fun activated(index: Int, caption: String, pager: TabbedPager) {
        super.activated(index, caption, pager)
        updateSelection()
    }
}
