package com.unciv.ui.popups

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.unciv.UncivGame
import com.unciv.logic.files.UncivFiles
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread

/** Bundled canonical snapshot; no invented on-chain author, parent, or receipt. */
class FeaturedScenarioPopup(screen: BaseScreen) : Popup(screen) {
    private var loading = false
    init {
        addGoodSizedLabel("A Civilization on the Brink", color = Color.GOLD).row()
        val width = screen.stage.width * 0.65f
        for (text in listOf("Two cities. A costly war. Rebuild your economy without losing your capital.",
            "Research Currency, build a Market, and hold your capital.",
            "Defend, negotiate, or counterattack - choose your own strategy."))
            add(text.toLabel().apply { wrap = true }).width(width).left().padTop(10f).row()
        val status = "".toLabel().apply { wrap = true }
        add(status).width(width).row()
        val play = addButton("Play from here") {
            if (loading) return@addButton
            loading = true
            status.setText("Loading...".tr())
            Concurrency.run("FeaturedScenario") {
                val app = UncivGame.Current
                val previous = app.gameInfo
                try {
                    val game = UncivFiles.gameInfoFromString(Gdx.files.internal("scenarios/civilization-on-the-brink.json").readString("UTF-8"))
                    // Always start from the packaged snapshot, never from a previous playthrough.
                    game.sharedScenario?.briefingShown = false
                    app.loadGame(game, callFromLoadScreen = true)
                    launchOnGLThread { close() }
                } catch (ex: Exception) {
                    app.gameInfo = previous
                    launchOnGLThread {
                        loading = false
                        status.setText(ex.message ?: "Could not load scenario".tr())
                    }
                }
            }
        }.actor
        play.name = "FeaturedScenarioPlay"
        addCloseButton()
        open(force = true)
    }
}
