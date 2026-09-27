package com.unciv.logic

import com.unciv.UncivGame
import org.junit.Assert
import org.junit.Test
import java.io.File

/**
 * The version the game shows (main menu, About, crash and problem reports) is typed into
 * [UncivGame.VERSION] by hand, apart from the APK's versionName/versionCode in BuildConfig.
 * 1.0.1's first build still reported 1.0.0 (user's report "gm2", 09-27); this keeps them together.
 */
class VersionMatchesBuildConfigTest {
    @Test
    fun gameVersionIsBuildConfigVersion() {
        val buildConfig = File("../../buildSrc/src/main/kotlin/BuildConfig.kt").readText()
        val text = Regex("""appVersion\s*=\s*"([^"]+)"""").find(buildConfig)!!.groupValues[1]
        val number = Regex("""appCodeNumber\s*=\s*(\d+)""").find(buildConfig)!!.groupValues[1].toInt()
        Assert.assertEquals("UncivGame.VERSION.text vs BuildConfig.appVersion", text, UncivGame.VERSION.text)
        Assert.assertEquals("UncivGame.VERSION.number vs BuildConfig.appCodeNumber", number, UncivGame.VERSION.number)
    }
}
