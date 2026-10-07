package com.unciv.ui.popups

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.UncivGame
import com.unciv.logic.chain.ChallengePack
import com.unciv.logic.chain.ChallengePacks
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.savescreens.SaveGalleryPopup

/** Introduces only newly available packs. The existing Guide retains all enabled packs. */
class ChallengePacksPopup(screen: BaseScreen, packs: List<ChallengePack>, onDismiss: () -> Unit) : Popup(screen) {
    init {
        addGoodSizedLabel("New paths for your civilization").row()
        val width = minOf(screen.stage.width * 0.72f, 650f)
        for (pack in packs) add(packCard(pack, width)).width(width).padBottom(12f).row()
        closeListeners.add(onDismiss)
        addButton("Shared saves") { close(); SaveGalleryPopup(screen) }
        addCloseButton("Got it")
    }
    companion object {
        fun packCard(pack: ChallengePack, width: Float): Table {
            val table = Table(BaseScreen.skin)
            val language = UncivGame.Current.settings.language
            val header = Table(BaseScreen.skin)
            header.add(ImageGetter.getImage("OtherIcons/${pack.icon}").apply { color = Color.valueOf("d3ac6c") })
                .size(34f).padRight(12f)
            header.add(pack.localizedTitle(language).toLabel(Color.valueOf("d3ac6c"), 26).apply { wrap = true })
                .width(width - 50f).left()
            table.add(header).width(width).left().padBottom(8f).row()
            for (point in pack.localizedGuide(language)) {
                val line = Table(BaseScreen.skin)
                line.add("\u2022".toLabel(Color.valueOf("d3ac6c"), 22)).top().padRight(10f)
                line.add(point.toLabel(fontSize = 22).apply { wrap = true }).width(width - 24f).left()
                table.add(line).width(width).padBottom(6f).row()
            }
            return table
        }
        fun guideCards(width: Float): Table = Table(BaseScreen.skin).apply {
            for (pack in ChallengePacks.enabled()) add(packCard(pack, width)).width(width).padTop(12f).row()
        }
    }
}
