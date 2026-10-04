package com.unciv.logic.chain

import com.unciv.logic.GameInfo

/** Snapshot lineage, not a proof of legal moves or who played the intervening turns. */
object SharedSaveProgress {
    data class Span(val start: Int, val end: Int) {
        val turns get() = end - start
    }

    fun from(game: GameInfo, parent: String?, sourceTurn: Int? = null): Span? {
        if (parent.isNullOrEmpty() || game.continuedFromSave != parent) return null
        val start = game.continuedFromTurn
        if (start < 0 || game.turns < start || sourceTurn != null && sourceTurn != start) return null
        return Span(start, game.turns)
    }
}
