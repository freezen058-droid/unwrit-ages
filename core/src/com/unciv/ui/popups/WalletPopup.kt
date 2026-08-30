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

    init {
        clickBehindToClose = true
        innerTable.pad(0f)

        val tabMaxWidth = if (stageToShowOn.width < 600f) stageToShowOn.width - 10f else 0.8f * stageToShowOn.width
        val tabMinWidth = 0.6f * stageToShowOn.width
        val tabMaxHeight = 0.8f * stageToShowOn.height

        val tabs = TabbedPager(
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

        add("將已命名的存檔雜湊上傳至區塊鏈，以彰顯你的文明之功績，僅需支付 1 SKR。\n(僅限手動存檔，自動存檔不會收費，見「自動存檔」頁籤)".toLabel()
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

        add("開啟後，每到自動存檔的回合，會跳出提示詢問是否要額外將該次進度上傳區塊鏈（1 SKR），\n不會在沒有你確認的情況下自動扣款。".toLabel()
            .apply { wrap = true }).colspan(2).fillX().row()
        addCheckbox("Ask to record on-chain every autosave", settings::remindRecordOnChainOnAutosave)
    }
}
