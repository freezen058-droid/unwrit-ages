package com.unciv.ui.screens.worldscreen.unit.actions

import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType

/** Shared placement rule for every instant-improvement unit, including modded ones. */
object InstantImprovementPlacement {
    fun improvements(unit: MapUnit, tile: Tile = unit.currentTile): List<TileImprovement> {
        val context = GameContext(civInfo = unit.civ, unit = unit, tile = tile)
        val filters = UnitActionModifiers.getUsableUnitActionUniques(unit,
            UniqueType.ConstructImprovementInstantly).map { it.params[0] }.toList()
        if (filters.isEmpty()) return emptyList()
        return tile.ruleset.tileImprovements.values.filter { improvement ->
            filters.any { improvement.matchesFilter(it, context) }
        }
    }

    fun names(unit: MapUnit): List<String> = improvements(unit).map { it.name }

    fun canPlace(unit: MapUnit, tile: Tile): Boolean {
        if (tile.isMarkedForCreatesOneImprovement()) return false
        if (tile != unit.currentTile && !unit.movement.canMoveTo(tile)) return false
        val context = GameContext(civInfo = unit.civ, unit = unit, tile = tile)
        return improvements(unit, tile).any { tile.improvementFunctions.canBuildImprovement(it, context) }
    }
}
