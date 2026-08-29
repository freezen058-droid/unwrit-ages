package com.unciv.ui.popups.options

import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.unciv.logic.chain.ChainWallet
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.popups.Popup
import com.unciv.utils.Concurrency

internal class WalletTab(
    optionsPopup: OptionsPopup
) : OptionsPopupTab(optionsPopup) {

    private lateinit var statusLabel: Label

    override fun lateInitialize() {
        addHeader("Wallet")

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

        super.lateInitialize()
    }

    private fun showError(message: String) {
        Popup(optionsPopup.stageToShowOn).apply {
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
