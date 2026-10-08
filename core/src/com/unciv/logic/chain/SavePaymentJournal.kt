package com.unciv.logic.chain

import com.badlogic.gdx.utils.Json

class SavePaymentPendingException(message: String) : IllegalStateException(message)

/** Persist signed bytes before broadcasting. A lost response must not create a new payment. */
class SavePaymentJournal(private val store: Store) {
    interface Store {
        fun read(): String?
        /** Must durably commit, or throw. Never broadcast after a failed write. */
        fun write(value: String)
    }

    class Entry {
        var payer = ""
        var operation = ""
        var memo = ""
        var signature = ""
        var signedTransaction = ""
        var lastValidBlockHeight = 0L
        var finalized = false
    }

    class State {
        var version = 1
        var entries = ArrayList<Entry>()
    }

    enum class Outcome { FINALIZED, FAILED, EXPIRED, UNKNOWN }

    companion object {
        /** Absence is inconclusive until the finalized chain has passed the signing lease. */
        fun outcome(level: String?, hasError: Boolean, finalizedHeight: Long?, lastValidHeight: Long): Outcome = when {
            level == "finalized" -> if (hasError) Outcome.FAILED else Outcome.FINALIZED
            level != null -> Outcome.UNKNOWN
            finalizedHeight != null && lastValidHeight > 0 && finalizedHeight > lastValidHeight -> Outcome.EXPIRED
            else -> Outcome.UNKNOWN
        }
    }

    private fun load(): State {
        val value = store.read() ?: return State()
        return Json().fromJson(State::class.java, value).also { state ->
            require(state.version == 1 && state.entries.size <= 256) { "Save payment history cannot be read safely" }
            require(state.entries.map { it.payer to it.operation }.distinct().size == state.entries.size)
            require(state.entries.map { it.signature }.distinct().size == state.entries.size)
            require(state.entries.all { it.payer.isNotBlank() && it.operation.isNotBlank() && it.signature.isNotBlank() })
            require(state.entries.filter { !it.finalized }.groupBy { it.payer }.values.all { it.size == 1 })
            require(state.entries.all { it.finalized || (it.signedTransaction.isNotBlank() && it.memo.isNotBlank() && it.lastValidBlockHeight > 0) })
        }
    }

    @Synchronized fun receipt(payer: String, operation: String): Entry? =
        load().entries.firstOrNull { it.payer == payer && it.operation == operation && it.finalized }

    @Synchronized fun pending(payer: String): Entry? = load().entries.singleOrNull { it.payer == payer && !it.finalized }

    @Synchronized fun prepare(entry: Entry) {
        require(entry.payer.isNotBlank() && entry.operation.isNotBlank() && entry.signature.isNotBlank())
        require(entry.signedTransaction.isNotBlank() && entry.memo.isNotBlank() && entry.lastValidBlockHeight > 0 && !entry.finalized)
        val state = load()
        check(state.entries.none { it.payer == entry.payer && !it.finalized }) { "Check the previous save payment before paying again" }
        check(state.entries.none { it.payer == entry.payer && it.operation == entry.operation }) { "This save already has a payment receipt" }
        check(state.entries.size < 256) { "Save payment history is full; no new payment was sent" }
        state.entries.add(entry)
        store.write(Json().toJson(state))
    }

    @Synchronized fun resolve(signature: String, outcome: Outcome) {
        check(outcome != Outcome.UNKNOWN) { "An unknown payment must remain pending" }
        val state = load()
        val entry = state.entries.single { it.signature == signature }
        if (entry.finalized) {
            check(outcome == Outcome.FINALIZED) { "A completed payment cannot be retried" }
            return
        }
        if (outcome == Outcome.FINALIZED) {
            entry.finalized = true
            entry.signedTransaction = "" // Keep receipt, not a broadcastable payload.
        } else state.entries.remove(entry)
        store.write(Json().toJson(state))
    }
}
