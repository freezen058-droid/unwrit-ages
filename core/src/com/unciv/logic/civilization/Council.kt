package com.unciv.logic.civilization

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.city.City
import com.unciv.logic.city.CityFocus
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.nation.Personality
import com.unciv.models.ruleset.nation.PersonalityValue
import com.unciv.models.ruleset.tech.Era
import com.unciv.models.UnitActionType

/**
 * The Council (幕僚), phase 1 (ROADMAP "After 1.0.2: the council", user 09-30): the player hands a
 * city to the domestic advisor with an order, and the advisor runs it - what the city builds and
 * what its citizens work - by the same weighing the game's AI uses, tilted by the order. Every turn
 * it says what it did and why, so it never plays better than it explains.
 *
 * What the player hands over, they can take back at any time; nothing here touches a city not
 * handed over, and an AI civ never has a council.
 */
class Council : IsPartOfGameInfoSerialization {

    companion object {
        /** A threat worth a popup (user 09-30: only above a threshold): this many enemy military
         *  units within [THREAT_RANGE] tiles of a city. Fewer is the turn's ordinary business. */
        const val THREAT_UNITS = 2
        const val THREAT_RANGE = 3
        /** Turns before a threat the player answered is asked about again. */
        const val ASK_AGAIN_AFTER = 5
    }

    @Transient
    lateinit var civ: Civilization

    /** City id -> [Order] name, for the cities handed to the domestic advisor. */
    var cityOrders = HashMap<String, String>()

    /** What the advisors did for this turn, for the council screen. */
    var report = ArrayList<String>()

    /** Choices made between turns - a city finishing something picks its next item while the turn
     *  ends - that go into the next turn's [report]. */
    var pending = ArrayList<String>()

    /** Reports waiting for the player's answer, oldest first: `threat:<city id>:<enemy units>` or
     *  `passed:<city id>`. Kept here, not as a PopupAlert: an unknown AlertType in a shared save would
     *  break older versions of the game, an unknown field is ignored. */
    var reports = ArrayList<String>()

    /** City id -> the turn the player last answered a threat to it: not asked again for a while. */
    var answered = HashMap<String, Int>()

    /** "" = ask; "Hold" = hand a threatened city to the council without asking (a standing answer). */
    var standingAnswer = ""

    /** Cities the council took over because of a threat: offered back when it has passed. */
    var takenForThreat = HashSet<String>()

    enum class Order(
        val label: String,
        val explanation: String,
        val focus: CityFocus,
        private vararg val weights: Pair<PersonalityValue, Float>
    ) {
        Growth("Grow the city", "more food, more citizens", CityFocus.FoodFocus,
            PersonalityValue.Food to 10f, PersonalityValue.Happiness to 7f),
        Production("Build up production", "workshops and mines first", CityFocus.ProductionFocus,
            PersonalityValue.Production to 10f),
        Science("Research", "libraries and scholars", CityFocus.ScienceFocus,
            PersonalityValue.Science to 10f),
        Gold("Fill the treasury", "markets and trade", CityFocus.GoldFocus,
            PersonalityValue.Gold to 10f),
        Culture("Culture and happiness", "temples, amphitheatres, wonders of culture", CityFocus.CultureFocus,
            PersonalityValue.Culture to 10f, PersonalityValue.Happiness to 9f),
        Military("Stockpile the military", "trains units whenever it can", CityFocus.ProductionFocus,
            PersonalityValue.Military to 10f, PersonalityValue.Aggressive to 7f),
        /** User 09-30: in phase 1 - defences first, and idle units in the city fortify. */
        Hold("Hold", "walls and defenders first; idle units here fortify", CityFocus.ProductionFocus,
            PersonalityValue.Military to 9f, PersonalityValue.Aggressive to 0f);

        /** The AI's own personality with this order's values raised - what the advisor weighs by. */
        val personality: Personality by lazy {
            Personality().also { p -> for ((value, weight) in weights) p[value] = weight }
        }

        /** Always train units when possible: the AI otherwise waits for war or high production. */
        val stockpiles get() = this == Military || this == Hold
    }

    fun clone() = Council().also {
        it.cityOrders.putAll(cityOrders)
        it.report.addAll(report)
        it.pending.addAll(pending)
        it.reports.addAll(reports)
        it.answered.putAll(answered)
        it.standingAnswer = standingAnswer
        it.takenForThreat.addAll(takenForThreat)
    }

    /** The advisor chose [construction] for [city] (CityConstructions.chooseNextConstruction). */
    fun chose(city: City, construction: String) {
        val order = orderOf(city) ?: return
        pending.add("[${city.name}]: now building [$construction] ([${order.label}])")
    }

    fun setTransients(civ: Civilization) {
        this.civ = civ
    }

    fun orderOf(city: City): Order? =
        cityOrders[city.id]?.let { name -> Order.entries.firstOrNull { it.name == name } }

    /** Hands [city] to the domestic advisor with [order], or takes it back with null. */
    fun assign(city: City, order: Order?) {
        if (order == null) {
            cityOrders.remove(city.id)
            city.setCityFocus(CityFocus.NoFocus)
            return
        }
        cityOrders[city.id] = order.name
        city.setCityFocus(order.focus)
        city.reassignPopulationDeferred()
        // The advisor plans the queue from here on: what the player had queued after the item in
        // progress goes, so the order is what the city works towards
        val constructions = city.cityConstructions
        while (constructions.constructionQueue.size > 1) constructions.removeFromQueue(1, true)
        if (constructions.getCurrentConstruction() is PerpetualConstruction) constructions.chooseNextConstruction()
    }

    /** The council's name in this era (the generic column of ROADMAP's table; per-civilisation
     *  names wait for the user's review). */
    fun name(era: Era = civ.getEra()): String = when {
        era.eraNumber <= 1 -> "Council of Elders"
        era.eraNumber == 2 -> "Royal Court"
        era.eraNumber == 3 -> "Privy Council"
        else -> "Cabinet"
    }

    /** At the start of the player's turn: every handed-over city keeps its focus, gets something
     *  to build if it has nothing, and under Hold its idle military units fortify. What changed
     *  goes to [report] and the player's notifications. */
    fun startTurn() {
        report.clear()
        report.addAll(pending)
        pending.clear()
        reports.removeAll { item -> civ.cities.none { item.split(":")[1] == it.id } }   // cities lost since
        for (city in civ.cities.toList()) {
            val order = orderOf(city) ?: continue
            if (city.getCityFocus() != order.focus) city.setCityFocus(order.focus)
            val constructions = city.cityConstructions
            if (constructions.getCurrentConstruction() is PerpetualConstruction) constructions.chooseNextConstruction()
            report.addAll(pending)   // what that choice added
            pending.clear()
            if (order == Order.Hold) {
                val idle = idleMilitaryNear(city)
                // as "Fortify all idle units" does it: fortify, or sleep what cannot
                for (unit in idle) if (unit.canFortify()) unit.fortify() else unit.action = UnitActionType.Sleep.value
                val fortified = idle.size
                if (fortified > 0) note(city, "[${city.name}]: [$fortified] idle units hold the city ([${order.label}])")
            }
        }
        watchForThreats()
    }

    /** Enemy military units - of civs at war with us, on tiles we see - within 3 tiles of [city]. */
    fun enemiesNear(city: City): Int =
        city.getCenterTile().getTilesInDistance(THREAT_RANGE).count { tile ->
            val unit = tile.militaryUnit ?: return@count false
            tile in civ.viewableTiles && unit.civ != civ && unit.civ.isAtWarWith(civ)
        }

    /** Threats and all-clears for the player's answer ([reports]), after the advisors have acted. */
    private fun watchForThreats() {
        for (city in civ.cities) {
            val enemies = enemiesNear(city)
            if (city.id in takenForThreat) {
                if (enemies == 0 && reports.none { it == "passed:${city.id}" }) reports.add("passed:${city.id}")
                continue
            }
            if (enemies < THREAT_UNITS || orderOf(city) == Order.Hold) continue
            val lastAnswer = answered[city.id]
            if (lastAnswer != null && civ.gameInfo.turns - lastAnswer < ASK_AGAIN_AFTER) continue
            if (reports.any { it.startsWith("threat:${city.id}:") }) continue
            if (standingAnswer == Order.Hold.name) {
                handOverForThreat(city)
                note(city, "[${city.name}]: [$enemies] enemy units near - the council holds it (standing order)")
            } else reports.add("threat:${city.id}:$enemies")
        }
    }

    /** The player's answer "hand it to the council": the city holds until the threat passes. */
    fun handOverForThreat(city: City) {
        assign(city, Order.Hold)
        takenForThreat.add(city.id)
        answered[city.id] = civ.gameInfo.turns
    }

    /** The threat to [city] has passed and the player takes it back - or leaves it ([keep]). */
    fun threatPassed(city: City, keep: Boolean) {
        takenForThreat.remove(city.id)
        if (!keep) assign(city, null)
    }

    private fun note(city: City, line: String) {
        report.add(line)
        civ.addNotification(line, com.unciv.logic.civilization.CityAction.withLocation(city),
            NotificationCategory.Production, NotificationIcon.Construction)
    }

    /** Military units of ours waiting for orders on the city or next to it (the same "due" units
     *  the Next unit button cycles through). */
    private fun idleMilitaryNear(city: City): List<MapUnit> {
        val near = city.getCenterTile().getTilesInDistance(1).toSet()
        return civ.units.getDueUnits().filter { it.isMilitary() && it.hasMovement() && it.getTile() in near }.toList()
    }
}
