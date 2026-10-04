package com.unciv.logic.chain

import com.unciv.ui.components.fonts.Fonts

/** Author's estimate of taking over this snapshot; independent of the game's AI difficulty. */
object SharedSaveDifficulty {
    val labels = linkedMapOf(0 to "Not rated").apply {
        for (rating in 1..5) put(rating, Fonts.star.toString().repeat(rating))
    }
    fun stars(rating: Int): String? = labels[rating]?.takeIf { rating in 1..5 }
}
