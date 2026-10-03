package com.unciv.logic.chain

/** Stable, optional author invitations. Unknown IDs are ignored, never shown as free text. */
object SharedSaveIntent {
    val labels = linkedMapOf(
        "" to "No author goal",
        "defend" to "Protect this civilization",
        "economy" to "Restore the economy",
        "campaign" to "Continue the campaign",
        "victory" to "Lead this civilization to victory",
        "explore" to "Write your own chapter"
    )

    fun label(id: String): String? = labels[id]?.takeIf { id.isNotEmpty() }
}
