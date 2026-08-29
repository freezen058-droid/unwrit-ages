package com.unciv.logic.chain

import java.security.MessageDigest

/**
 * Global holder for the platform's [PlatformWalletService], set once at app launch
 * (see AndroidLauncher). Defaults to a no-op so core/desktop code that doesn't set
 * this up explicitly still compiles and runs without a wallet.
 */
object ChainWallet {
    var service: PlatformWalletService = PlatformWalletService.None

    val isConnected: Boolean
        get() = service.connectedAddress != null

    /** SHA-256 of [data], as lowercase hex - used to fingerprint a save file for on-chain recording. */
    fun sha256Hex(data: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
