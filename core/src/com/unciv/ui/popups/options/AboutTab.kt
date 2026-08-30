package com.unciv.ui.popups.options

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.UncivGame
import com.unciv.ui.screens.civilopediascreen.FormattedLine
import com.unciv.ui.screens.civilopediascreen.MarkupRenderer

internal class AboutTab(
    optionsPopup: OptionsPopup
): OptionsPopupTab(optionsPopup) {
    init {
        renderTo(this)
    }

    companion object {
        /** Get "About" content in a separate Table without the [OptionsPopupTab] contract */
        // MainMenuscreen adds this to a Popup - one Table wrapper could be saved by using renderTo(Popup.innerTable), but that would be longer code
        fun asTable() = Table().apply { renderTo(this) }

        private fun renderTo(table: Table) {
            table.pad(20f)
            val lines = sequence {
                yield(FormattedLine(extraImage = "banner", imageSize = 240f, centered = true))
                yield(FormattedLine())
                yield(FormattedLine("{Version}: ${UncivGame.VERSION.toNiceString()}"))
                yield(FormattedLine("Visit repository", link = "https://github.com/freezen058-droid/Wars"))
                yield(FormattedLine())
                yield(FormattedLine("CivilWars is a fork of Unciv by Yair Morgenstern and contributors, licensed under MPL-2.0."))
            }
            MarkupRenderer.renderTo(table, lines.asIterable())
        }
    }
}
