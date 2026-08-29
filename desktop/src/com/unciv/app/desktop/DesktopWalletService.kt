package com.unciv.app.desktop

import com.unciv.logic.chain.PlatformWalletService

/**
 * Solana Mobile Wallet Adapter talks to a wallet app via Android intents, so there is no
 * equivalent wallet-connect path on desktop yet. This stub keeps [com.unciv.logic.chain.ChainWallet.service]
 * explicit (instead of silently falling back to [PlatformWalletService.None]) and gives a
 * desktop-specific error message. A future desktop wallet (e.g. a CLI-signed or QR-code flow)
 * would replace this.
 */
class DesktopWalletService : PlatformWalletService {
    override val isAvailable = false
    override val connectedAddress: String? = null

    override fun connect(onConnected: (address: String) -> Unit, onError: (Exception) -> Unit) {
        onError(UnsupportedOperationException("Wallet login is only available on Android for now"))
    }

    override fun disconnect() {}

    override fun recordSaveHash(
        gameId: String,
        hashHex: String,
        onSuccess: (txSignature: String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        onError(UnsupportedOperationException("Wallet login is only available on Android for now"))
    }
}
