package com.unciv.ui.popups

import com.unciv.logic.ProblemReport
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.toCheckBox
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.screens.basescreen.BaseScreen

/**
 * "Report a problem" - from the game's menu, or the crash screen's "Send report". The player
 * writes what happened and chooses what goes with it; nothing is sent until they press Send.
 *
 * @param save the game's save, offered as an attachment (ticked by default) when there is one
 * @param screenshot a picture of the screen, offered likewise; null on the crash screen
 * @param details the error report, for a crash; always sent, and said so
 */
class ReportProblemPopup(
    screen: BaseScreen,
    private val kind: String,
    private val save: String?,
    private val screenshot: ByteArray?,
    private val details: String = ""
) : Popup(screen) {

    init {
        val width = screen.stage.width * 0.6f
        addGoodSizedLabel("Report a problem").row()
        // Two whole sentences, not one assembled from parts: each must be found by translation
        add((if (details.isEmpty())
                "Tell us what happened. The report goes to the Unwrit Ages developers with the game's version and your phone's model. Nothing else is sent unless you leave it ticked below."
            else "Tell us what happened. The report goes to the Unwrit Ages developers with the error above, the game's version and your phone's model. Nothing else is sent unless you leave it ticked below."
            ).toLabel().apply { wrap = true }).width(width).row()
        val words = UncivTextField("What were you doing when it happened?")
        words.maxLength = 2000
        add(words).width(width).padTop(10f).row()
        val withSave = "Attach this game's save (recommended)".toCheckBox(startsOutChecked = true)
        val withShot = "Attach a screenshot".toCheckBox(startsOutChecked = true)
        if (save != null) add(withSave).left().row()
        if (screenshot != null) add(withShot).left().row()

        val status = "".toLabel().apply { wrap = true }
        add(status).width(width).row()

        val send = "Send".toTextButton()
        addCloseButton()
        add(send)
        send.onClick {
            send.disable()
            status.setText("Sending...".tr())
            ProblemReport.send(
                kind, words.text, details,
                save.takeIf { withSave.isChecked }, screenshot.takeIf { withShot.isChecked }
            ) { error ->
                if (error == null) {
                    close()
                    ToastPopup("Thank you - the report was sent.", screen)
                } else {
                    status.setText("The report could not be sent:".tr() + "\n" + error)
                    send.enable()
                }
            }
        }
        open(force = true)
    }
}
