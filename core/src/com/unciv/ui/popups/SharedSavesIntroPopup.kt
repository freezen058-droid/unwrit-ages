package com.unciv.ui.popups

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.savescreens.SaveGalleryPopup

/** A focused, once-only introduction for both existing players and new arrivals. */
class SharedSavesIntroPopup(screen: BaseScreen, onDismiss: () -> Unit) : Popup(screen) {
    companion object { const val VERSION = 1 }

    init {
        val accent = Color.valueOf("d3ac6c")
        val width = minOf(screen.stage.width * 0.72f, 650f)
        add("Pass your civilization onward".toLabel(accent, 28).apply { wrap = true }).width(width).row()
        for ((icon, title, description) in listOf(
            Triple("Load", "Take command", "Continue another player's civilization."),
            Triple("Checkmark", "Write the next chapter", "Complete goals to open the next chapter."),
            Triple("Link", "Create a challenge for the next leader", "Save game > Set goals for the next player. Up to 3 chapters, with 3 goals each.")
        )) {
            val row = Table(BaseScreen.skin)
            row.add(ImageGetter.getImage("OtherIcons/$icon").apply { color = accent }).size(32f).padRight(14f).top()
            val words = Table(BaseScreen.skin)
            words.add(title.toLabel(accent, 23).apply { wrap = true }).width(width - 54f).left().row()
            words.add(description.toLabel(fontSize = 18).apply { wrap = true }).width(width - 54f).left().padTop(4f)
            row.add(words).width(width - 54f).left()
            add(row).width(width).padTop(8f).row()
        }
        add("Sharing: 1 SKR + SOL network fee. Shared saves are public.".toLabel(Color.LIGHT_GRAY, 18)
            .apply { wrap = true }).width(width).padTop(12f).row()
        closeListeners.add(onDismiss)
        addButton("Shared saves") { close(); SaveGalleryPopup(screen) }
        addCloseButton("Got it")
    }
}
