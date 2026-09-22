package com.unciv.ui.popups.options

import com.unciv.Constants
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.widgets.LanguageSelectBox
import com.unciv.ui.components.widgets.TabbedPager

internal class LanguageTab(
    optionsPopup: OptionsPopup
): OptionsPopupTab(optionsPopup) {
    private val selectBox by lazy { LanguageSelectBox { onChoice(it) } }
    private var chosenLanguage = settings.language

    init {
        pad(20f)
        defaults().pad(10f)
    }

    override fun lateInitialize() {
        add("Language".toLabel(fontSize = Constants.headingFontSize)).row()
        add(selectBox).width(420f).fillX().row()
        selectBox.showSelected(chosenLanguage)
        super.lateInitialize()
    }

    private fun onChoice(language: String) {
        chosenLanguage = language
        if (chosenLanguage != settings.language)
            selectLanguage()
    }

    private fun selectLanguage() {
        settings.language = chosenLanguage
        settings.updateLocaleFromLanguage()
        game.translations.tryReadTranslationForCurrentLanguage()
        reloadWorldAndOptions()
    }

    override fun activated(index: Int, caption: String, pager: TabbedPager) {
        super.activated(index, caption, pager)
        chosenLanguage = settings.language
        selectBox.showSelected(chosenLanguage)
    }
}
