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
                // No "fork of Unciv" sentence or upstream link here (user, 09-24): the licence page
                // linked below credits upstream and explains the licence in full. MPL-2.0 asks the
                // app to say where the source is, not to carry the credit itself.
                // CC BY-SA 3.0 asks for attribution and for modifications to be marked, and it
                // asks it of whoever receives the work - which means the person holding the app,
                // not only someone reading NOTICE.md in the repository. The bulk recolour pass
                // made this fork's tiles derivatives of that art, so the credit ships with them.
                yield(FormattedLine("Tileset art by The Bucketeer / @GeneralWadaling, CC BY-SA 3.0, modified."))
                // MPL-2.0 3.2: recipients of a binary must be told how to obtain the Source Code
                // Form of the Covered Files it modifies. The licence page says how (by email, on
                // request), so this line must stay even though the archive is no longer a download.
                yield(FormattedLine("Licence", link = Constants.licenceUrl))
                yield(FormattedLine())
                // The dApp Store developer agreement requires the privacy policy to be reachable
                // from inside the app, so an empty value is announced rather than hidden.
                if (Constants.privacyPolicyUrl.isEmpty())
                    yield(FormattedLine("Privacy policy not configured - see docs/Privacy-Policy.md"))
                else
                    yield(FormattedLine("Privacy policy", link = Constants.privacyPolicyUrl))
            }
            MarkupRenderer.renderTo(table, lines.asIterable())
        }
    }
}
