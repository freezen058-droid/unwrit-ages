package com.unciv.logic

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.files.UncivFiles
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.translations.TranslationFileWriter
import com.unciv.models.translations.Translations
import com.unciv.models.translations.Translations.Companion.conditionalOrderingKey
import com.unciv.models.translations.Translations.Companion.conditionalPlacementKey
import com.unciv.models.translations.Translations.Companion.shouldCapitalizeKey
import com.unciv.testing.BaseTestRunner
import com.unciv.ui.components.widgets.LanguageSelectBox
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every line the game can show has a translation in every language the game offers.
 *
 * Measured against what the generator collects from the code templates and the JSONs, not
 * against English.properties: a line added to a JSON without re-running the generator is in
 * no language file at all, so a check driven by the files passes while the game shows English
 * (09-25: the second and third lines of the very first popup, in both Chinese).
 */
@RunWith(BaseTestRunner::class)
class ShippedLanguagesTests {
    @Test
    fun everyShippedLanguageTranslatesEveryLine() {
        // The generator reads the JSONs through UncivGame's files, as the in-game button does.
        UncivGame.Current = UncivGame()
        UncivGame.Current.settings = GameSettings()
        UncivGame.Current.files = UncivFiles(Gdx.files)
        RulesetCache.loadRulesets(noMods = true)
        val translations = Translations()
        translations.readAllLanguagesTranslation()
        val keys = TranslationFileWriter.baseGameTranslationKeys().filterNot { isNotShownText(it) }

        val report = StringBuilder()
        var missing = 0
        for (language in LanguageSelectBox.SHIPPED_LANGUAGES.filter { it != "English" }) {
            val gaps = keys.filter { translations[it]?.containsKey(language) != true }.sorted()
            missing += gaps.size
            if (gaps.isNotEmpty()) report.appendLine("$language: ${gaps.size} missing")
            gaps.forEach { report.appendLine("  $it") }
        }
        println(report)
        Assert.assertEquals("Lines with no translation in a shipped language:\n$report", 0, missing)
    }

    /** Settings a language file carries for the translator, and section headings - not text the player reads. */
    private fun isNotShownText(key: String) =
        key.startsWith("#") || key.startsWith("Fastlane_") ||
            key in setOf(conditionalOrderingKey, conditionalPlacementKey, shouldCapitalizeKey)
}
