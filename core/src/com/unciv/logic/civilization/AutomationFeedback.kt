package com.unciv.logic.civilization

import com.unciv.logic.city.City
import com.unciv.logic.map.mapunit.MapUnit

/** Exceptions only. Normal automation must not generate a notification every turn. */
object AutomationFeedback {
    fun unit(unit: MapUnit, reason: String) {
        if (!unit.civ.isHuman()) return
        val prefix = "Units need orders: [$reason] ("
        val previous = unit.civ.notifications.firstOrNull { it.text.startsWith(prefix) }
        val actions = previous?.actions?.toMutableList() ?: mutableListOf()
        // One unit can be interrupted more than once in a turn; don't count it twice.
        val action = MapUnitAction(unit)
        if (actions.none { it is MapUnitAction && it.references(unit) }) actions.add(action)
        if (previous != null) unit.civ.notifications.remove(previous)
        unit.civ.addNotification("Units need orders: [$reason] ([${actions.size}])",
            actions, NotificationCategory.Units, unit.name)
    }

    fun production(city: City, construction: String, reason: String, replacement: String) {
        if (!city.civ.isHuman()) return
        var text = "Production stopped in [${city.name}]: [$construction]. [$reason]"
        if (replacement.isNotEmpty() && replacement != construction && replacement != "Idle")
            text += "\nNow producing: [$replacement]"
        else text += "\nChoose the next production."
        if (city.civ.notifications.any { it.text == text }) return
        city.civ.addNotification(text, CityAction(city.location), NotificationCategory.Production, construction)
    }
}
