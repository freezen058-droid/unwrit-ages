@file:Suppress("InvalidPackageDeclaration")
package com.unciv.build

object BuildConfig {
    /** Display name of this fork, shown as the app name and in store listings */
    const val appName = "Unwrit Ages"
    const val appCodeNumber = 2
    const val appVersion = "1.0.1"

    /** Kotlin/R-class package namespace. Kept as the upstream Unciv package so we don't
     * have to rewrite every source file's `package` declaration and R references. */
    const val namespace = "com.unciv.app"

    /** Store-facing unique app id (Play Store / Solana dApp Store listing key). This is what
     * distinguishes this fork from upstream Unciv - must never collide with com.unciv.app. Locked once published. */
    const val applicationId = "com.unwritages.app"

}
