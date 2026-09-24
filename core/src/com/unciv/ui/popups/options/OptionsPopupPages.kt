package com.unciv.ui.popups.options

import com.badlogic.gdx.Gdx
import com.unciv.GUI
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.ui.components.extensions.areSecretKeysPressed
import com.unciv.ui.images.ImageGetter

enum class OptionsPopupPages(
    val label: String,
    internal val iconPath: String,
    internal val getContent: OptionsPopup.() -> OptionsPopupTab
) {
    Display("Display", "UnitPromotionIcons/Scouting", { DisplayTab(this) }),
    Gameplay("Gameplay", "OtherIcons/Options", { GameplayTab(this) }),
    Automation("Automation", "OtherIcons/NationSwap", { AutomationTab(this) }),
    // A speech bubble, not the current language's flag. A flag stands for a state, and Spanish,
    // Russian, Portuguese and both Chinese scripts are each spoken across borders that picking one
    // flag takes a side in. One tab for both: apart, each was a nearly empty page.
    LanguageAndSound("Language & Sound", "OtherIcons/Chat", { LanguageAndSoundTab(this) }),
    // Multiplayer is not part of this fork's offering, and its options page is a wall of
    // sync intervals and turn-notification settings that mean nothing in a single-player game.
    Multiplayer("Multiplayer", "OtherIcons/Multiplayer", { MultiplayerTab(this) }) {
        override fun visible(withDebug: Boolean) = false
    },
    Keys("Keys", "OtherIcons/Keyboard", { KeyBindingsTab(this, tabMinWidth - 40f) }) {   // 40 = padding
        override fun visible(withDebug: Boolean) = GUI.keyboardAvailable
    },
    Advanced("Advanced", "OtherIcons/Settings", { AdvancedTab(this) }),
    ModCheck("Locate mod errors", "OtherIcons/Mods", { ModCheckTab(this) }) {
        override fun visible(withDebug: Boolean) = RulesetCache.size > BaseRuleset.entries.size
    },
    Debug("Debug", "OtherIcons/SecretOptions", { DebugTab(this) }) {
        override fun visible(withDebug: Boolean) = withDebug || Gdx.input.areSecretKeysPressed()
    },
    About("About", "Icons/Unciv128.png", { AboutTab(this) }),
    ;

    internal open fun visible(withDebug: Boolean) = true
    internal fun getIcon() =
        if (iconPath.endsWith(".png")) ImageGetter.getExternalImage(iconPath)
        else ImageGetter.getImage(iconPath)

    companion object {
        operator fun get(ordinal: Int) = entries.first { it.ordinal == ordinal }
    }
}
