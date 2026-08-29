package com.unciv.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.unciv.utils.Log
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
        /** Single in-flight operation; set immediately before [start] launches this activity. */
        @Volatile
        private var pendingOperation: (suspend (ActivityResultSender) -> Unit)? = null

        fun start(from: Activity, operation: suspend (ActivityResultSender) -> Unit) {
            pendingOperation = operation
            from.startActivity(Intent(from, WalletBridgeActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val operation = pendingOperation
        pendingOperation = null
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
