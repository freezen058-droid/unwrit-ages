package com.unciv.ui.popups.options

import com.unciv.Constants
import com.unciv.ui.components.extensions.MusicControls
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.widgets.LanguageSelectBox
import com.unciv.ui.components.widgets.TabbedPager

/**
 *  The language dropdown and the sound controls on one page. Each used to have a tab of its own,
 *  and each tab was nearly empty: one dropdown, and three or four sliders.
 */
internal class LanguageAndSoundTab(
    optionsPopup: OptionsPopup
) : OptionsPopupTab(optionsPopup), MusicControls {
    private val languageSelectBox by lazy { LanguageSelectBox { onLanguageChoice(it) } }

    override fun lateInitialize() {
        val music = game.musicController

        add("Language".toLabel(fontSize = Constants.headingFontSize)).colspan(2).row()
        add(languageSelectBox).width(420f).colspan(2).padBottom(10f).row()
        languageSelectBox.showSelected(settings.language)

        addSeparator()
        add("Sound".toLabel(fontSize = Constants.headingFontSize)).colspan(2).row()

        addSoundEffectsVolumeSlider(settings)
        addCitySoundsVolumeSlider(settings)

        if (music.isVoicesAvailable())
            addVoicesVolumeSlider(settings, music)

        if (music.isMusicAvailable())
            addMusicControls(settings, music)

        super.lateInitialize()
    }

    private fun onLanguageChoice(language: String) {
        if (language == settings.language) return
        settings.language = language
        settings.updateLocaleFromLanguage()
        game.translations.tryReadTranslationForCurrentLanguage()
        reloadWorldAndOptions()
    }

    override fun activated(index: Int, caption: String, pager: TabbedPager) {
        super.activated(index, caption, pager)
        languageSelectBox.showSelected(settings.language)
    }
}
