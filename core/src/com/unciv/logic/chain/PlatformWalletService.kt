package com.unciv.logic.chain

/**
 * Contract for platform-specific wallet integration (e.g. Solana Mobile Wallet Adapter on
 * Android). Mirrors the [com.unciv.logic.files.PlatformSaverLoader] pattern: core defines the
 * contract and a no-op default, each platform module supplies a real implementation and wires
 * it in at launch via [ChainWallet.service].
 */
interface PlatformWalletService {

    /** Whether wallet connection is supported on this platform/build at all. */
    val isAvailable: Boolean

    /** Currently connected wallet address (base58), or null if not connected. */
    val connectedAddress: String?

    fun connect(
        onConnected: (address: String) -> Unit,
        onError: (Exception) -> Unit = {}
    )

    fun disconnect()

    /**
     * Submits [hashHex] (a hex-encoded hash of a save file) as a Memo-program transaction signed
     * by the connected wallet, so the existence of that exact save at that point in time can be
     * verified on-chain later. Does not upload the save itself - saves stay in normal local/cloud
     * storage, this is a lightweight proof-of-existence only. [saveName] is the player-chosen save
     * name (not just [gameId], an opaque UUID) so the on-chain record is human-identifiable.
     */
    fun recordSaveHash(
        gameId: String,
        saveName: String,
        hashHex: String,
        onSuccess: (txSignature: String) -> Unit,
        onError: (Exception) -> Unit = {}
    )

    companion object {
        val None = object : PlatformWalletService {
            override val isAvailable = false
            override val connectedAddress: String? = null
            override fun connect(onConnected: (address: String) -> Unit, onError: (Exception) -> Unit) {
                onError(UnsupportedOperationException("Wallet integration is not available on this platform"))
            }
            override fun disconnect() {}
            override fun recordSaveHash(
                gameId: String,
                saveName: String,
                hashHex: String,
                onSuccess: (txSignature: String) -> Unit,
                onError: (Exception) -> Unit
            ) {
                onError(UnsupportedOperationException("Wallet integration is not available on this platform"))
            }
        }
    }
}
