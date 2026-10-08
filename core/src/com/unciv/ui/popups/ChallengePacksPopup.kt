package com.unciv.ui.popups

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.UncivGame
import com.unciv.logic.chain.ChallengePack
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.savescreens.SaveGalleryPopup
import com.badlogic.gdx.Gdx
import com.unciv.logic.files.UncivFiles
import com.unciv.ui.screens.savescreens.SaveGameScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts

/** Introduces only newly available packs. The existing Guide retains all enabled packs. */
class ChallengePacksPopup(private val screen: BaseScreen, packs: List<ChallengePack>, onDismiss: () -> Unit) : Popup(screen) {
    private var navigating = false
    init {
        val width = minOf(screen.stage.width * 0.76f, 680f)
        val header = Table(BaseScreen.skin)
        header.add("New goals unlocked!".toLabel(Color.valueOf("d3ac6c"), 30)).expandX().left()
        header.add("×".toTextButton().apply {
            applyDiscoveryStyle(this, quiet = true)
            name = "CloseNewGoals"
            onClick { close() }
            keyShortcuts.add(KeyCharAndCode.BACK) { close() }
        }).size(44f).right()
        add(header).width(width).padBottom(16f).row()
        for (pack in packs) add(packCard(pack, width)).width(width).padBottom(12f).row()
        closeListeners.add(onDismiss)
        val actions = Table(BaseScreen.skin)
        val half = (width - 20f) / 2f
        actions.add("Use these goals in your own chapters.".toLabel(Color.LIGHT_GRAY, 19).apply { wrap = true })
            .width(half).left().padRight(20f)
        actions.add("Play a challenge built with the new goals.".toLabel(Color.LIGHT_GRAY, 19).apply { wrap = true })
            .width(half).left().row()
        actions.add("Try setting new goals".toTextButton().apply {
            applyDiscoveryStyle(this)
            name = "TryNewGoalEditor"
            onClick { openSaveScreen() }
        }).width(half).height(48f).padTop(8f).padRight(20f)
        actions.add("Try the new challenge".toTextButton().apply {
            applyDiscoveryStyle(this)
            name = "TryNewGoalChallenge"
            onClick {
                val pack = packs.first()
                val local = Gdx.files.local("challenge-pack-example-${pack.id}.json")
                if (local.exists()) { close(); ChallengePackExamplePopup(screen, local) }
                else if (pack.exampleSaveSignature.isNotEmpty()) { close(); SaveGalleryPopup(screen, pack.exampleSaveSignature) }
                else ToastPopup("This challenge is not available yet.", screen)
            }
        }).width(half).height(48f).padTop(8f)
        add(actions).width(width).row()
    }
    private fun openSaveScreen() {
        if (navigating) return
        val app = UncivGame.Current
        app.gameInfo?.let { close(); app.pushScreen(SaveGameScreen(it)); return }
        navigating = true
        Concurrency.run("NewGoalsSaveScreen") {
            try {
                val local = Gdx.files.local("challenge-pack-example-expedition.json")
                val current = if (app.files.autosaves.autosaveExists()) app.files.autosaves.loadLatestAutosave()
                    else if (local.exists()) UncivFiles.gameInfoFromString(local.readString("UTF-8"))
                    else throw IllegalStateException("Start or load a game before setting goals.".tr())
                launchOnGLThread { close(); app.pushScreen(SaveGameScreen(current)) }
            } catch (ex: Exception) {
                launchOnGLThread { navigating = false; ToastPopup(ex.message ?: "Could not load game", screen) }
            }
        }
    }
    companion object {
        private fun applyDiscoveryStyle(button: TextButton, quiet: Boolean = false) {
            if (quiet) button.style = TextButton.TextButtonStyle(button.style).apply {
                up = ImageGetter.getWhiteDotDrawable().tint(Color.CLEAR)
                fontColor = Color.valueOf("d3ac6c")
            }
        }
        fun packCard(pack: ChallengePack, width: Float): Table {
            val table = Table(BaseScreen.skin)
            val language = UncivGame.Current.settings.language
            val cardWidth = width
            for (goal in pack.goals) {
                val card = Table(BaseScreen.skin)
                card.background = BaseScreen.skinStrings.getUiBackground(
                    "ChallengePacks/GoalCard", BaseScreen.skinStrings.roundedEdgeRectangleShape,
                    BaseScreen.skinStrings.skinConfig.baseColor.darken(0.35f))
                card.pad(14f)
                val metric = goal.conditions.firstOrNull()?.metric
                val icon = when (metric) {
                    "exploredTiles" -> "OtherIcons/Search"
                    "cities", "population" -> "OtherIcons/Cities"
                    else -> "OtherIcons/${pack.icon}"
                }
                card.add(ImageGetter.getImage(icon).apply { color = Color.valueOf("d3ac6c") })
                    .size(48f).padRight(18f)
                val text = Table(BaseScreen.skin)
                text.add(goal.displayLabel(language).toLabel(Color.valueOf("d3ac6c"), 24)).left().row()
                val description = when (metric) {
                    "exploredTiles" -> "Explore new tiles to reach your goal.".tr()
                    "cities" -> "Gain cities to reach your goal.".tr()
                    else -> goal.displayTitle(language).tr()
                }
                text.add(description.toLabel(fontSize = 20).apply { wrap = true }).width(cardWidth - 94f).left().padTop(5f)
                card.add(text).expandX().left()
                table.add(card).width(cardWidth).padBottom(8f).row()
            }
            return table
        }
    }
}

/** A private review snapshot, carrying no invented on-chain receipt. */
private class ChallengePackExamplePopup(screen: BaseScreen, file: com.badlogic.gdx.files.FileHandle) : Popup(screen) {
    private var loading = false
    init {
        addGoodSizedLabel("New horizons", color = Color.valueOf("d3ac6c")).row()
        addGoodSizedLabel("Explore new lands and establish another city.").row()
        addGoodSizedLabel("Local preview").row()
        addButton("Play from here") {
            if (loading) return@addButton
            loading = true
            Concurrency.run("NewGoalsExample") {
                val app = UncivGame.Current
                val previous = app.gameInfo
                try {
                    app.loadGame(UncivFiles.gameInfoFromString(file.readString("UTF-8")), callFromLoadScreen = true)
                    launchOnGLThread { close() }
                } catch (ex: Exception) {
                    app.gameInfo = previous
                    com.unciv.utils.Log.error("Local challenge example could not load", ex)
                    launchOnGLThread {
                        loading = false
                        val restored = com.unciv.ui.screens.mainmenuscreen.MainMenuScreen()
                        app.setScreen(restored)
                        ToastPopup(ex.message ?: "Could not load game", restored, 10000)
                    }
                }
            }
        }
        addCloseButton()
        open(true)
    }
}
