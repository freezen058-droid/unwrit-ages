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
class FeaturedScenarioPopup(screen: BaseScreen, previousResult: com.unciv.logic.GameInfo? = null) : Popup(screen) {
    private var loading = false
    init {
        val naval = previousResult?.sharedScenario?.id == "strait-watch-v1"
        val authored = previousResult != null && previousResult.sharedScenario?.id !in
            listOf("strait-watch-v1", "civilization-on-the-brink-v1")
        addGoodSizedLabel(if (authored) previousResult!!.sharedScenario!!.title else if (naval) "The Strait Must Hold" else "A Civilization on the Brink", color = Color.GOLD).row()
        val width = screen.stage.width * 0.65f
        val descriptions = if (authored) listOf("Restart from the save you accepted.") else if (naval) listOf(
            "A stronger enemy fleet is approaching. Fortify the coastal capital, research Navigation, and bring reinforcements through the strait. Hold for 30 turns."
        ) else listOf("Two cities. A costly war. Rebuild your economy without losing your capital.",
            "Research Currency, build a Market, and hold your capital.",
            "Defend, negotiate, or counterattack - choose your own strategy.")
        for (text in descriptions)
            add(text.toLabel().apply { wrap = true }).width(width).left().padTop(10f).row()
        if (previousResult != null && !authored)
            add("Restart from the original starting save. Your previous result will be saved on this device.".toLabel()
                .apply { wrap = true }).width(width).left().padTop(10f).row()
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
                    if (previousResult != null) {
                        val baseName = (if (previousResult.sharedScenario!!.chapter != null)
                            "[${previousResult.sharedScenario!!.title}] - Chapter II - Turn [${previousResult.turns}]"
                            else "[${previousResult.sharedScenario!!.title}] - Turn [${previousResult.turns}]").tr(hideIcons = true)
                        var name = baseName; var index = 2
                        while (app.files.getSave(name).exists()) name = "$baseName (${index++})"
                        app.files.saveGame(previousResult, name, recordOnChain = false)
                    }
                    val path = if (naval) "scenarios/strait-watch.json" else "scenarios/civilization-on-the-brink.json"
                    val game = if (authored) com.unciv.logic.chain.ScenarioReplay.load(previousResult!!)
                        else UncivFiles.gameInfoFromString(Gdx.files.internal(path).readString("UTF-8"))
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
