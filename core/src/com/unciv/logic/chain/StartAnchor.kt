package com.unciv.logic.chain

import com.unciv.logic.GameInfo
import java.security.MessageDigest

/**
 * An anchored start: the wallet signs `unwritages-start:<gameId>` before the map exists, and the
 * map is then generated from a seed derived from that signature. A save taken from someone else's
 * game was generated from *their* wallet's seed, so it cannot be moved under one's own anchor
 * without the map changing under it - which is the whole point (ROADMAP "Provenance").
 *
 * What this is not: proof that the game was played fairly. [origin] is computed by the client and
 * can be forged by a modified one; what the certificate carries (the start signature and wallet)
 * lets anyone recompute the seed and check.
 */
object StartAnchor {

    const val MEMO_PREFIX = "unwritages-start:"

    const val ORIGIN_ANCHORED = "Anchored start"
    const val ORIGIN_UNANCHORED = "Unanchored"
    /** A game taken over from someone else's shared save; the turn follows. */
    const val ORIGIN_CONTINUED = "Continued from turn "

    fun memo(gameId: String) = MEMO_PREFIX + gameId

    /** The map seed for a start anchored by [wallet] with [signature] (both base58): the first
     *  eight bytes of SHA-256(wallet ‖ signature), big-endian. Recomputable by anyone who reads
     *  the certificate's metadata. */
    fun seedFor(wallet: String, signature: String): Long {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((wallet + signature).toByteArray(Charsets.UTF_8))
        return digest.take(8).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }
    }

    /** What an anchored start leaves in the game: the gameId it signed, and who signed it how. */
    data class Anchor(val gameId: String, val wallet: String, val signature: String) {
        val seed get() = seedFor(wallet, signature)
    }

    /**
     * [ORIGIN_ANCHORED] when this game was started under an anchor whose seed its map still has,
     * and - when [minter] is known - by that same wallet; [ORIGIN_CONTINUED] when it was taken over
     * from someone else's shared save. Everything else is [ORIGIN_UNANCHORED]:
     * unverified, not accused.
     */
    fun origin(gameInfo: GameInfo, minter: String?): String {
        if (gameInfo.continuedFromTurn > 0) return ORIGIN_CONTINUED + gameInfo.continuedFromTurn
        if (gameInfo.startAnchorSignature.isEmpty() || gameInfo.startAnchorWallet.isEmpty())
            return ORIGIN_UNANCHORED
        if (minter != null && minter != gameInfo.startAnchorWallet) return ORIGIN_UNANCHORED
        val seed = seedFor(gameInfo.startAnchorWallet, gameInfo.startAnchorSignature)
        return if (gameInfo.tileMap.mapParameters.seed == seed) ORIGIN_ANCHORED else ORIGIN_UNANCHORED
    }
}
