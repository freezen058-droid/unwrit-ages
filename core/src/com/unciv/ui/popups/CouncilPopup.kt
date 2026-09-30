package com.unciv.ui.popups

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.Constants
import com.unciv.logic.civilization.Council
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.TranslatedSelectBox
import com.unciv.ui.screens.worldscreen.WorldScreen

/**
 * The council screen (ROADMAP "the council", phase 1): each city is either the player's own or the
 * domestic advisor's, with an order; below, what the advisors did this turn. Everything the advisor
 * does is also in the notifications - it never acts out of sight.
 */
class CouncilPopup(private val worldScreen: WorldScreen) : Popup(worldScreen, Scrollability.None) {

    private companion object {
        const val MYSELF = "I run it myself"
    }

    init {
        val civ = worldScreen.selectedCiv
        val council = civ.council
        addGoodSizedLabel(council.name()).row()
        addGoodSizedLabel("Hand a city to the domestic advisor with an order: it chooses what the city builds and what its citizens work, and reports every turn. Take it back at any time.",
            size = Constants.defaultFontSize - 4).width(worldScreen.stage.width * 0.6f).row()

        val cities = Table().apply { defaults().pad(4f).left() }
        val orders = listOf(MYSELF) + Council.Order.entries.map { it.label }
        for (city in civ.cities) {
            cities.add(city.name.toLabel(hideIcons = true)).minWidth(180f)
            val current = council.orderOf(city)?.label ?: MYSELF
            val hint = (council.orderOf(city)?.explanation ?: "").toLabel(Color.LIGHT_GRAY, Constants.defaultFontSize - 4)
            cities.add(TranslatedSelectBox(orders, current).apply {
                onChange {
                    val order = Council.Order.entries.firstOrNull { it.label == selected.value }
                    council.assign(city, order)
                    hint.setText((order?.explanation ?: "").tr())
                    worldScreen.shouldUpdate = true
                }
            })
            cities.add(hint).row()
        }
        if (civ.cities.isEmpty()) cities.add("Found a city first.".toLabel())
        add(AutoScrollPane(cities)).maxHeight(worldScreen.stage.height * 0.45f).row()

        val report = council.report
        addGoodSizedLabel(if (report.isEmpty()) "The council has nothing to report this turn." else "This turn:",
            size = Constants.defaultFontSize - 2).padTop(10f).row()
        for (line in report.takeLast(6)) addGoodSizedLabel(line, size = Constants.defaultFontSize - 4).row()
        addCloseButton()
        open(force = true)
    }
}
