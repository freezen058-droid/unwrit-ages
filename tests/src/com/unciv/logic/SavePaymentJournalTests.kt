package com.unciv.logic

import com.unciv.logic.chain.SavePaymentJournal
import org.junit.Assert.*
import org.junit.Test

class SavePaymentJournalTests {
    private class Storage : SavePaymentJournal.Store {
        var value: String? = null
        var fail = false
        override fun read() = value
        override fun write(value: String) { check(!fail) { "Disk write failed" }; this.value = value }
    }
    private fun entry(payer: String = "alice", operation: String = "snapshot1", signature: String = "sig1") =
        SavePaymentJournal.Entry().apply {
            this.payer = payer; this.operation = operation; this.signature = signature
            memo = "original encrypted storage ids"; signedTransaction = "exact signed bytes"; lastValidBlockHeight = 100
        }

    @Test fun signedPayloadAndStoragePointerSurviveProcessRestart() {
        val store = Storage(); SavePaymentJournal(store).prepare(entry())
        val pending = SavePaymentJournal(store).pending("alice")!!
        assertEquals("exact signed bytes", pending.signedTransaction)
        assertEquals("original encrypted storage ids", pending.memo)
        assertEquals(100, pending.lastValidBlockHeight)
    }
    @Test fun lostResponseDoesNotPermitAnotherPayment() {
        val journal = SavePaymentJournal(Storage()); journal.prepare(entry())
        assertThrows(IllegalStateException::class.java) { journal.prepare(entry(operation = "snapshot2", signature = "sig2")) }
    }
    @Test fun unknownOutcomeCannotBeClearedForRetry() {
        val journal = SavePaymentJournal(Storage()); journal.prepare(entry())
        assertThrows(IllegalStateException::class.java) { journal.resolve("sig1", SavePaymentJournal.Outcome.UNKNOWN) }
        assertNotNull(journal.pending("alice"))
    }
    @Test fun finalizedReceiptSurvivesLostUiCallbackAndRestart() {
        val store = Storage(); val journal = SavePaymentJournal(store); journal.prepare(entry())
        journal.resolve("sig1", SavePaymentJournal.Outcome.FINALIZED)
        val receipt = SavePaymentJournal(store).receipt("alice", "snapshot1")!!
        assertEquals("sig1", receipt.signature); assertEquals("", receipt.signedTransaction)
        assertNull(SavePaymentJournal(store).pending("alice"))
    }
    @Test fun completedSnapshotCannotChargeAgain() {
        val journal = SavePaymentJournal(Storage()); journal.prepare(entry()); journal.resolve("sig1", SavePaymentJournal.Outcome.FINALIZED)
        assertThrows(IllegalStateException::class.java) { journal.prepare(entry(signature = "new-paid-sig")) }
    }
    @Test fun finalizedFailurePermitsAnExplicitNewAttempt() {
        val journal = SavePaymentJournal(Storage()); journal.prepare(entry()); journal.resolve("sig1", SavePaymentJournal.Outcome.FAILED)
        journal.prepare(entry(signature = "sig2")); assertEquals("sig2", journal.pending("alice")!!.signature)
    }
    @Test fun provenExpiryPermitsAnExplicitNewAttempt() {
        val journal = SavePaymentJournal(Storage()); journal.prepare(entry()); journal.resolve("sig1", SavePaymentJournal.Outcome.EXPIRED)
        journal.prepare(entry(signature = "sig2")); assertNotNull(journal.pending("alice"))
    }
    @Test fun walletSwitchDoesNotReuseOtherWalletReceipt() {
        val journal = SavePaymentJournal(Storage()); journal.prepare(entry()); journal.resolve("sig1", SavePaymentJournal.Outcome.FINALIZED)
        assertNull(journal.receipt("bob", "snapshot1")); journal.prepare(entry(payer = "bob", signature = "sig2"))
    }
    @Test fun failedDurableWriteLeavesNoBroadcastablePendingOperation() {
        val store = Storage(); store.fail = true
        assertThrows(IllegalStateException::class.java) { SavePaymentJournal(store).prepare(entry()) }; assertNull(store.value)
    }
    @Test fun failedReceiptWriteKeepsPendingForLaterReconciliation() {
        val store = Storage(); val journal = SavePaymentJournal(store); journal.prepare(entry()); store.fail = true
        assertThrows(IllegalStateException::class.java) { journal.resolve("sig1", SavePaymentJournal.Outcome.FINALIZED) }
        assertNotNull(SavePaymentJournal(store).pending("alice"))
    }
    @Test fun corruptHistoryFailsClosed() {
        val store = Storage(); store.value = "not json"
        assertThrows(Exception::class.java) { SavePaymentJournal(store).prepare(entry()) }
    }
    @Test fun timeoutAndMissingRpcStatusDoNotProveFailure() {
        assertEquals(SavePaymentJournal.Outcome.UNKNOWN, SavePaymentJournal.outcome(null, false, null, 100))
        assertEquals(SavePaymentJournal.Outcome.UNKNOWN, SavePaymentJournal.outcome(null, false, 100, 100))
    }
    @Test fun processedOrConfirmedStatusStillNeedsFinality() {
        assertEquals(SavePaymentJournal.Outcome.UNKNOWN, SavePaymentJournal.outcome("processed", false, 120, 100))
        assertEquals(SavePaymentJournal.Outcome.UNKNOWN, SavePaymentJournal.outcome("confirmed", true, 120, 100))
    }
    @Test fun onlyFinalizedHeightBeyondLeaseProvesAbsentTransactionExpired() {
        assertEquals(SavePaymentJournal.Outcome.EXPIRED, SavePaymentJournal.outcome(null, false, 101, 100))
        assertEquals(SavePaymentJournal.Outcome.UNKNOWN, SavePaymentJournal.outcome(null, false, 101, 0))
    }
    @Test fun finalizedSuccessOrFailureTakesPrecedenceOverExpiry() {
        assertEquals(SavePaymentJournal.Outcome.FINALIZED, SavePaymentJournal.outcome("finalized", false, 120, 100))
        assertEquals(SavePaymentJournal.Outcome.FAILED, SavePaymentJournal.outcome("finalized", true, 120, 100))
    }
    @Test fun finalizedReceiptCannotLaterBecomeRetryable() {
        val journal = SavePaymentJournal(Storage()); journal.prepare(entry()); journal.resolve("sig1", SavePaymentJournal.Outcome.FINALIZED)
        assertThrows(IllegalStateException::class.java) { journal.resolve("sig1", SavePaymentJournal.Outcome.EXPIRED) }
    }
    @Test fun unknownHistoryVersionBlocksAnyNewPayment() {
        val store = Storage(); store.value = "{version:99,entries:[]}"
        assertThrows(IllegalArgumentException::class.java) { SavePaymentJournal(store).prepare(entry()) }
    }
    @Test fun historyCapacityDoesNotEvictReceiptsAndRiskChargingAgain() {
        val store = Storage(); val journal = SavePaymentJournal(store)
        repeat(256) {
            journal.prepare(entry(operation = "snapshot$it", signature = "sig$it"))
            journal.resolve("sig$it", SavePaymentJournal.Outcome.FINALIZED)
        }
        assertThrows(IllegalStateException::class.java) { journal.prepare(entry(operation = "extra", signature = "extra")) }
        assertNotNull(journal.receipt("alice", "snapshot0"))
    }
    @Test fun unrelatedSnapshotCannotReuseACompletedReceipt() {
        val journal = SavePaymentJournal(Storage()); journal.prepare(entry()); journal.resolve("sig1", SavePaymentJournal.Outcome.FINALIZED)
        assertNull(journal.receipt("alice", "changed-snapshot"))
    }
}
