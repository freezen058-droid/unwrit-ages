package com.unciv.ui.screens.worldscreen.bottombar

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.logic.map.tile.Tile
import com.unciv.ui.objectdescriptions.TileDescription
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.addBorderAllowOpacity
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.civilopediascreen.FormattedLine.IconDisplay
import com.unciv.ui.screens.civilopediascreen.MarkupRenderer
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.utils.DebugUtils
import com.unciv.view.CivView

class TileInfoTable(private val worldScreen: WorldScreen) : Table(BaseScreen.skin) {
    var civView: CivView = worldScreen.selectedGameView.civView

    /** Folded state survives a change of tile: a player who put this away wants it to stay away,
     *  not to spring open again the next time they tap something. */
    private var isCollapsed = false
    private var shownTile: Tile? = null
    private val collapseButton = "".toTextButton()

    init {
        background = BaseScreen.skinStrings.getUiBackground(
            "WorldScreen/TileInfoTable",
            tintColor = BaseScreen.skinStrings.skinConfig.baseColor.darken(0.5f)
        )
        collapseButton.onClick {
            isCollapsed = !isCollapsed
            updateTileTable(shownTile)
            // WorldScreen works out how much of the notification column we cover from our height,
            // so it has to recompute once we change shape.
            worldScreen.shouldUpdate = true
        }
    }

    internal fun updateTileTable(tile: Tile?) {
        shownTile = tile
        clearChildren()
        pad(5f)

        if (tile != null && (DebugUtils.VISIBLE_MAP || civView.getCiv().hasExplored(tile)) ) {
            // This panel shares the right edge with the notification column, and on a city tile it
            // grows tall enough to bury it. Hence both a fold button and a ceiling on the height,
            // with anything over the ceiling made scrollable instead of pushing into the
            // notifications.
            collapseButton.setText(if (isCollapsed) EXPAND_LABEL else COLLAPSE_LABEL)
            add(collapseButton).size(collapseButtonSize).expandX().right().row()

            if (!isCollapsed) {
                val content = Table()
                content.add(getStatsTable(tile)).left().row()
                content.add(MarkupRenderer.render(TileDescription.toMarkup(civView.gameView.tileMapView.getTile(tile), civView), padding = 0f, iconDisplay = IconDisplay.None) {
                    worldScreen.openCivilopedia(it)
                } ).padTop(5f).row()
                if (DebugUtils.VISIBLE_MAP) content.add(tile.position.toPrettyString().toLabel()).colspan(2).pad(5f).row()
                if (DebugUtils.SHOW_TILE_IMAGE_LOCATIONS){
                    val imagesString = "Images: " + worldScreen.mapHolder.tileGroups[civView.gameView.tileMapView.getTile(tile)]!!.layerTerrain.tileBaseImages.joinToString{"\n"+it.name}
                    content.add(imagesString.toLabel()).row()
                }
                content.pack()

                val maxHeight = worldScreen.stage.height * maxHeightFraction
                if (content.prefHeight > maxHeight)
                    add(ScrollPane(content)).height(maxHeight).row()
                else
                    add(content).row()
            }
        }

        pack()
        addBorderAllowOpacity(1f, Color.WHITE)
    }

    companion object {
        /** At most this much of the screen height, so the notification column always keeps the rest. */
        private const val maxHeightFraction = 0.42f
        private const val collapseButtonSize = 32f
        private const val COLLAPSE_LABEL = "-"
        private const val EXPAND_LABEL = "+"
    }

    private fun getStatsTable(tile: Tile): Table {
        val table = Table()
        table.defaults().pad(2f)
        
        for ((key, value) in tile.stats.getTileStats(civView.getCiv())) {
            table.add((key.character + value.toInt().toString()).toLabel())
                .align(Align.left).padRight(5f)
        }
        table.touchable = Touchable.enabled
        table.onClick {
            Popup(worldScreen).apply {
                for ((name, stats) in tile.stats.getTileStatsBreakdown(tile.getCity(), civView.getCiv()))
                    add("${name.tr()}: {${stats.clone()}}".toLabel()).row()
                addCloseButton()
            }.open()
        }
        return table
    }

    override fun draw(batch: Batch?, parentAlpha: Float) = super.draw(batch, parentAlpha)
}
