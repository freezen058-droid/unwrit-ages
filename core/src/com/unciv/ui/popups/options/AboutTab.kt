package com.unciv.ui.popups.options

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.Constants
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
                yield(FormattedLine())
                yield(FormattedLine("Unwrit Ages is a fork of Unciv by Yair Morgenstern and contributors, licensed under MPL-2.0."))
                yield(FormattedLine("Unciv", link = "https://github.com/yairm210/Unciv"))
                // MPL-2.0 3.2: a binary may be distributed only if the Source Code Form of
                // the Covered Files it modifies is made available to its recipients.
                if (Constants.sourceOfferUrl.isEmpty())
                    yield(FormattedLine("Source offer not configured - see MPL-NOTICE.md"))
                else
                    yield(FormattedLine("Source for the modified MPL files",
                        link = Constants.sourceOfferUrl))
            }
            MarkupRenderer.renderTo(table, lines.asIterable())
        }
    }
}
