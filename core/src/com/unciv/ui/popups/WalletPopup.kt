package com.unciv.ui.popups

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.logic.chain.ChainWallet
import com.unciv.models.metadata.GameSettings
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.center
import com.unciv.ui.components.extensions.getCloseButton
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.TabbedPager
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.options.OptionsPopupHelpers
import com.unciv.ui.popups.options.OptionsPopupPages
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.utils.Concurrency

/**
 * Standalone popup for wallet connection and the on-chain-related autosave setting,
 * reachable directly from the main menu (previously buried in Settings).
 */
class WalletPopup(
    stageToShowOn: Stage,
    private val settings: GameSettings
) : Popup(stageToShowOn, scrollable = Scrollability.None) {

    private val tabs: TabbedPager

    init {
        clickBehindToClose = true
        innerTable.pad(0f)

        val tabMaxWidth = if (stageToShowOn.width < 600f) stageToShowOn.width - 10f else 0.8f * stageToShowOn.width
        val tabMinWidth = 0.6f * stageToShowOn.width
        val tabMaxHeight = 0.8f * stageToShowOn.height

        tabs = TabbedPager(
            tabMinWidth, tabMaxWidth, tabMaxHeight, tabMaxHeight,
            headerFontSize = 21, backgroundColor = Color.CLEAR
        )
        add(tabs).pad(0f).grow().row()

        tabs.addPage("Wallet", WalletPage(this, settings), ImageGetter.getImage("OtherIcons/Settings"), 24f)
        tabs.addPage("Auto-Save", AutoSavePage(settings), ImageGetter.getImage("OtherIcons/Settings"), 24f)

        tabs.decorateHeader(getCloseButton { close() })

        pack()
        center(stageToShowOn)
    }

    /** Open on the Wallet tab. A TabbedPager starts with no page selected, which showed an empty
     *  popup until a tab was tapped; like OptionsPopup, select once the popup is on the stage. */
    override fun setVisible(visible: Boolean) {
        super.setVisible(visible)
        if (!visible || tabs.activePage >= 0) return
        tabs.selectPage(0)
    }
}

private class WalletPage(
    private val popup: WalletPopup,
    private val settings: GameSettings
) : Table(BaseScreen.skin), TabbedPager.IPageExtensions, OptionsPopupHelpers {
    override val rightWidgetMinWidth = 240f
    override val activePage get() = OptionsPopupPages.Gameplay // unused - this page never calls reload/reopen helpers

    private lateinit var statusLabel: Label
    private var isInitialized = false

    init {
        pad(10f)
        defaults().pad(5f)
    }

    override fun activated(index: Int, caption: String, pager: TabbedPager) {
        if (isInitialized) return
        isInitialized = true

        addHeader("Wallet")

        add(("Put a save's fingerprint on the blockchain - a permanent record that your "
            + "empire reached that point - for 1 SKR.\nYour wallet asks before every one. Autosaves "
            + "are recorded only if you turn that on in the Auto-Save tab.").toLabel()
            .apply { wrap = true }).colspan(2).fillX().row()

        addSeparator()

        statusLabel = walletStatusText().toLabel()
        add(statusLabel).colspan(2).left().row()

        val connectButton = "Connect Wallet".toTextButton()
        add(connectButton.onClick {
            ChainWallet.service.connect(
                onConnected = { Concurrency.runOnGLThread { statusLabel.setText(walletStatusText()) } },
                onError = { ex -> Concurrency.runOnGLThread { showError(ex.message ?: "Failed to connect wallet") } }
            )
        }).colspan(2).row()

        val disconnectButton = "Disconnect Wallet".toTextButton()
        add(disconnectButton.onClick {
            ChainWallet.service.disconnect()
            statusLabel.setText(walletStatusText())
        }).colspan(2).row()

        addSeparator()

        addCheckbox("Record save games on-chain (requires a connected wallet)", settings::recordSavesOnChain)
    }

    private fun showError(message: String) {
        Popup(popup.stageToShowOn).apply {
            addGoodSizedLabel(message).row()
            addCloseButton()
            open(true)
        }
    }

    private fun walletStatusText(): String {
        val address = ChainWallet.service.connectedAddress
        return when {
            address != null -> "Wallet: ${address.take(4)}…${address.takeLast(4)}"
            !ChainWallet.service.isAvailable -> "Wallet: not available on this platform"
            else -> "Wallet: not connected"
        }
    }
}

private class AutoSavePage(
    private val settings: GameSettings
) : Table(BaseScreen.skin), TabbedPager.IPageExtensions, OptionsPopupHelpers {
    override val rightWidgetMinWidth = 240f
    override val activePage get() = OptionsPopupPages.Gameplay // unused - this page never calls reload/reopen helpers

    private var isInitialized = false

    init {
        pad(10f)
        defaults().pad(5f)
    }

    override fun activated(index: Int, caption: String, pager: TabbedPager) {
        if (isInitialized) return
        isInitialized = true

        addHeader("Auto-Save")

        addSelectBox("Turns between autosaves", settings::turnsBetweenAutosaves, listOf(1, 2, 5, 10, 20, 50, 100, 1000))

        addSeparator()

        add(("With this on, every autosave turn asks whether to put that progress on the chain "
            + "as well, for 1 SKR.\nNothing is ever charged without you saying yes to it first.").toLabel()
            .apply { wrap = true }).colspan(2).fillX().row()
        addCheckbox("Ask to record on-chain every autosave", settings::remindRecordOnChainOnAutosave)
    }
}
