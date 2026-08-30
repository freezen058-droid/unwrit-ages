package com.unciv.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.unciv.utils.Log
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.launch

/**
 * Solana Mobile Wallet Adapter's [ActivityResultSender] requires an
 * [androidx.activity.ComponentActivity] (it registers an [androidx.activity.result.ActivityResultLauncher]
 * internally, an AndroidX Activity Result API concept). [AndroidLauncher] extends libGDX's
 * `AndroidApplication`, which extends plain `android.app.Activity` - confirmed by reading
 * libGDX's AndroidApplication.java source, it does NOT extend ComponentActivity - so MWA calls
 * cannot be hosted directly on it.
 *
 * This transparent, zero-UI bridge Activity exists solely to host one MWA `transact()` call at a
 * time with a real ComponentActivity, then reports back through [pendingOperation]'s own closure
 * and finishes itself. See [AndroidWalletService.withBridge].
 */
class WalletBridgeActivity : ComponentActivity() {

    companion object {
        /**
         * Single in-flight operation; set atomically by [start] immediately before it launches
         * this activity. An [AtomicReference] with [AtomicReference.compareAndSet] (not a plain
         * `@Volatile` var) is required here, not just style: [start] can be called from different
         * threads in the DAEMON thread pool (recordSaveHash and connect both go through
         * `Concurrency.run`), so two overlapping calls need a real atomic check-and-set to avoid
         * one silently clobbering the other. A security audit found that a plain volatile var let
         * a second concurrent call overwrite the first before its own onCreate ever read it - the
         * first caller's [kotlinx.coroutines.CompletableDeferred] then never completes, hanging
         * forever with its wake lock held until the OS's own timeout, with no error surfaced.
         */
        private val pendingOperation = AtomicReference<(suspend (ActivityResultSender) -> Unit)?>(null)

        /**
         * @return false if another operation is already in flight - this activity only ever hosts
         * one at a time. Callers MUST treat false as an immediate failure (throw/report an error),
         * not silently drop it or queue-and-hope; see the class doc for what happens if they don't.
         */
        fun start(from: Activity, operation: suspend (ActivityResultSender) -> Unit): Boolean {
            if (!pendingOperation.compareAndSet(null, operation)) return false
            from.startActivity(Intent(from, WalletBridgeActivity::class.java))
            return true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val operation = pendingOperation.getAndSet(null)
        if (operation == null) {
            finish()
            return
        }

        // Must construct before this activity reaches STARTED - ActivityResultSender registers
        // its ActivityResultLauncher in its constructor, and AndroidX requires that registration
        // happen no later than onCreate.
        val sender = ActivityResultSender(this)

        lifecycleScope.launch {
            try {
                operation(sender)
            } catch (ex: Exception) {
                Log.error("Wallet bridge operation failed", ex)
            } finally {
                finish()
            }
        }
    }
}
