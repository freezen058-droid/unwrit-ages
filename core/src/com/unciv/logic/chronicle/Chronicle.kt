package com.unciv.logic.chronicle

import com.unciv.logic.GameInfo
import com.unciv.logic.IsPartOfGameInfoSerialization

/**
 * The milestones of one game, recorded as they happen.
 *
 * Why this exists: a save is a snapshot, and [com.unciv.logic.civilization.Civilization.notificationsLog]
 * only keeps the last `notificationsLogMaxTurns` (5) turns, so by the end of a game there is
 * no narrative left to tell - only the final position and [com.unciv.logic.civilization.CivRankingHistory]'s
 * per-turn numbers. This log is the narrative half: a short, append-only list of the things a
 * player would actually mention when describing their game.
 *
 * It is deliberately small. One entry is a turn, a year, a kind and up to two names; a long game
 * produces a few hundred of them, which is a few kilobytes inside a save that is already hundreds
 * of kilobytes - and it is compact enough to travel as NFT metadata for a victory certificate.
 *
 * It can only ever describe games played after it shipped: there is nothing in an older save to
 * reconstruct it from.
 */
class Chronicle : ArrayList<ChronicleEntry>(), IsPartOfGameInfoSerialization {

    /** Entries the player's own civilization took part in, oldest first. */
    fun forCiv(civName: String) = filter { it.actor == civName || it.target == civName }

    fun copy(): Chronicle {
        val copy = Chronicle()
        copy.addAll(this)
        return copy
    }

    companion object {
        /** A long game should not be able to bloat a save through this log. */
        const val MAX_ENTRIES = 2000

        /**
         * Record [kind] for [actor] (optionally against [target]) at the game's current turn.
         *
         * Safe to call from anywhere in the turn pipeline: it never throws, and it silently does
         * nothing once the log is full, because losing the tail of a chronicle is always better
         * than failing a turn.
         */
        fun record(
            gameInfo: GameInfo?,
            kind: ChronicleKind,
            actor: String,
            target: String? = null,
            detail: String? = null
        ) {
            if (gameInfo == null) return
            val log = gameInfo.chronicle
            if (log.size >= MAX_ENTRIES) return
            val entry = ChronicleEntry(gameInfo.turns, gameInfo.getYear(), kind.name, actor, target, detail)
            // The same event can be signalled twice in one turn (a city changing hands runs through
            // several managers); a chronicle with duplicates reads as a stutter.
            if (log.lastOrNull() == entry) return
            log.add(entry)
        }
    }
}

/** One line of the chronicle. Immutable, and small enough to serialise by hand if we ever need to. */
data class ChronicleEntry(
    val turn: Int = 0,
    /** In-game year, negative for BC - [GameInfo.getYear] already accounts for game speed. */
    val year: Int = 0,
    /** [ChronicleKind] by name, so an unknown kind from a newer version does not break loading. */
    val kind: String = "",
    /** Who did it - a civilization name. */
    val actor: String = "",
    /** Who it was done to, where that applies. */
    val target: String? = null,
    /** The city, wonder, technology or era involved. */
    val detail: String? = null
) : IsPartOfGameInfoSerialization

enum class ChronicleKind {
    /** [actor] founded [detail]. */
    CityFounded,

    /** [actor] declared war on [target]. */
    WarDeclared,

    /** [actor] made peace with [target]. */
    PeaceMade,

    /** [actor] took [detail] from [target]. */
    CityCaptured,

    /** [actor] razed [detail], which belonged to [target]. */
    CityDestroyed,

    /** [actor] liberated [detail] and returned it to [target]. */
    CityLiberated,

    /** [actor] completed the wonder [detail]. */
    WonderBuilt,

    /** [actor] entered the [detail] era. */
    EraEntered,

    /**
     * [target] was eliminated, by [actor] where we know who struck the last blow.
     * This is the entry the player means by "in turn 143 I finished off Rome".
     */
    CivDefeated,

    /** [actor] won by [detail]. */
    Victory
}
