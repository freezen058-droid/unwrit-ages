package com.unciv.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.unciv.UncivGame
import com.unciv.logic.battle.AttackableTile
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.map.HexCoord
import com.unciv.models.UnitActionType
import com.unciv.ui.screens.worldscreen.bottombar.BattleTableHelpers.battleAnimationDeferred
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import com.unciv.utils.Concurrency

/**
 * Demo/test builds only (BuildConfig.DEMO_COMMANDS; the store build never registers it): drive the game
 * from adb with the same rule checks the UI uses - a move goes through pathfinding, an attack only hits
 * what TargetHelper says this unit can attack, a purchase only what the city screen would allow.
 *
 *     adb shell am broadcast -a com.unwritages.app.CMD --es c "attack 12 0 -6"
 *
 * Results go to logcat, tag UACMD. Commands: state | center x y | move id x y | attack id x y |
 * bombard city x y | action id SetUp|Fortify|Sleep|Explore|... | promote id name | build city name |
 * buy city name | tech name | policy name | war civ | peace-no | endturn | certificate | seeker address | councilreport threat|passed city
 */
class DemoCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("c") ?: return
        Concurrency.runOnGLThread {
            val out = try { run(cmd.trim().split(Regex("\\s+"))) } catch (e: Throwable) { "ERR ${e.javaClass.simpleName}: ${e.message}" }
            for (line in out.lines()) android.util.Log.i("UACMD", line)
            android.util.Log.i("UACMD", "DONE $cmd")
        }
    }

    private fun run(a: List<String>): String {
        val game = UncivGame.Current
        val ws = game.worldScreen ?: return "ERR no world screen"
        val info = ws.gameInfo
        val civ = info.getCurrentPlayerCivilization()
        fun tile(x: String, y: String) = info.tileMap[HexCoord(x.toInt(), y.toInt())]
        fun unit(id: String) = civ.units.getCivUnits().firstOrNull { it.id == id.toInt() } ?: error("no unit $id")
        fun city(name: String) = civ.cities.firstOrNull { it.name.equals(name, true) } ?: error("no city $name")
        fun pos(h: HexCoord) = "${h.x},${h.y}"
        val result = when (a[0]) {
            "state" -> buildString {
                appendLine("turn ${info.turns} gold ${civ.gold} research ${civ.tech.techsToResearch} policyReady ${civ.policies.canAdoptPolicy()}")
                for (c in civ.cities) appendLine("city ${c.name} ${pos(c.location)} hp ${c.health} pop ${c.population.population} queue ${c.cityConstructions.constructionQueue} bombard ${c.canBombard()}")
                for (u in civ.units.getCivUnits()) appendLine("unit ${u.id} ${u.name} ${pos(u.currentTile.position)} hp ${u.health} mv ${u.currentMovement} act ${u.action} setup ${u.isSetUpForSiege()} promo ${u.promotions.canBePromoted()}")
                val seen = civ.viewableTiles
                for (t in seen) {
                    val o = t.getCity()
                    if (t.isCityCenter() && o != null && o.civ != civ) appendLine("ecity ${o.name} ${o.civ.civName} ${pos(t.position)} hp ${o.health}")
                    for (u in t.getUnits()) if (u.civ != civ) appendLine("enemy ${u.civ.civName} ${u.name} ${pos(t.position)} hp ${u.health}")
                }
                for (other in civ.getKnownCivs()) appendLine("civ ${other.civName} war ${civ.isAtWarWith(other)} cities ${other.cities.size}")
            }
            "center" -> { ws.mapHolder.setCenterPosition(HexCoord(a[1].toInt(), a[2].toInt()), immediately = true, selectUnit = false); "ok" }
            "move" -> {
                val u = unit(a[1]); val t = tile(a[2], a[3])
                if (!u.movement.canReach(t)) error("cannot reach")
                u.action = null
                val reached = u.movement.headTowards(t)
                ws.mapHolder.setCenterPosition(u.currentTile.position, immediately = true, selectUnit = false)
                "at ${pos(reached.position)} mv ${u.currentMovement}"
            }
            "attack" -> {
                val u = unit(a[1]); val t = tile(a[2], a[3])
                val at = TargetHelper.getAttackableEnemies(u, u.movement.getDistanceToTiles()).firstOrNull { it.tileToAttack == t }
                    ?: error("cannot attack ${a[2]},${a[3]}")
                val attacker = MapUnitCombatant(u)
                if (!Battle.movePreparingAttack(attacker, at)) error("moved but cannot attack")
                val defender = at.combatant!!
                val (dDef, dAtt) = Battle.attackOrNuke(attacker, at)
                ws.battleAnimationDeferred(attacker, dAtt, defender, dDef)
                "dealt $dDef took $dAtt; target hp ${defender.getHealth()} me ${u.health}"
            }
            "bombard" -> {
                val c = city(a[1]); val t = tile(a[2], a[3])
                if (!c.canBombard() || t !in TargetHelper.getBombardableTiles(c)) error("cannot bombard")
                val attacker = CityCombatant(c)
                val at = AttackableTile(c.getCenterTile(), t, 0f, Battle.getMapCombatantOfTile(t))
                val defender = at.combatant!!
                val (dDef, dAtt) = Battle.attackOrNuke(attacker, at)
                ws.battleAnimationDeferred(attacker, dAtt, defender, dDef)
                "dealt $dDef; target hp ${defender.getHealth()}"
            }
            "action" -> {
                val u = unit(a[1])
                if (!UnitActions.invokeUnitAction(u, UnitActionType.valueOf(a[2]))) error("action not available")
                "ok"
            }
            "promote" -> {
                val u = unit(a[1]); val name = a.drop(2).joinToString(" ")
                if (name !in u.promotions.getAvailablePromotions().map { it.name }) error("not available")
                u.promotions.addPromotion(name); "ok"
            }
            "build" -> { city(a[1]).cityConstructions.addToQueue(a.drop(2).joinToString(" ")); "ok" }
            "buy" -> {
                val c = city(a[1]); val name = a.drop(2).joinToString(" ")
                val cons = info.ruleset.units[name] ?: info.ruleset.buildings[name] ?: error("no such construction")
                val cost = cons.getStatBuyCost(c, com.unciv.models.stats.Stat.Gold) ?: error("not buyable")
                if (!c.cityConstructions.isConstructionPurchaseAllowed(cons, com.unciv.models.stats.Stat.Gold, cost)) error("purchase not allowed")
                if (!c.cityConstructions.purchaseConstruction(cons, -1, false)) error("purchase failed")
                "bought for $cost"
            }
            "tech" -> {
                val t = info.ruleset.technologies[a.drop(1).joinToString(" ")] ?: error("no tech")
                civ.tech.techsToResearch.clear()
                civ.tech.techsToResearch.addAll(civ.tech.getRequiredTechsToDestination(t).map { it.name })
                "queue ${civ.tech.techsToResearch}"
            }
            "policy" -> {
                val p = info.ruleset.policies[a.drop(1).joinToString(" ")] ?: error("no policy")
                if (!civ.policies.canAdoptPolicy() || !civ.policies.isAdoptable(p)) error("not adoptable")
                civ.policies.adopt(p); "ok"
            }
            "war" -> {
                val other = civ.getKnownCivs().firstOrNull { it.civName.equals(a[1], true) } ?: error("unknown civ")
                civ.getDiplomacyManager(other)!!.declareWar(); "ok"
            }
            "endturn" -> { ws.nextTurn(); "ending turn" }
            // A council report for the named city, to see the popup without staging a war
            "councilreport" -> { civ.council.reports.add("${a[1]}:${city(a[2]).id}:3"); ws.shouldUpdate = true; "queued" }
            // Whether an address holds a Seeker Genesis Token, as the gallery's Seeker mark decides it
            "seeker" -> {
                com.unciv.logic.chain.ChainWallet.service.seekerOwners(setOf(a[1]),
                    onError = { android.util.Log.i("UACMD", "seeker ERR ${it.message}") },
                    onSuccess = { android.util.Log.i("UACMD", "seeker ${a[1]} -> ${a[1] in it}") })
                "checking"
            }
            // The certificate as a mint would draw it, beside SaveFiles (certificate-preview.jpg - in it, it showed in the load list), nothing
            // spent - to compare phones and settings (BUGS #18: high-contrast text, 10-01)
            "certificate" -> {
                val record = com.unciv.logic.chain.VictoryCertificate.record(info, civ)
                val jpeg = com.unciv.logic.chain.ChainWallet.service.renderCertificate(
                    com.unciv.logic.chain.VictoryCertificate.inscription(record),
                    com.unciv.logic.chain.CertificateEmblem(record.nation, record.emblemOuter, record.emblemInner),
                    record.anchored
                ) ?: error("cannot draw")
                val file = game.files.getSave("certificate-preview.jpg").parent().parent().child("certificate-preview.jpg")
                file.writeBytes(jpeg, false)
                "wrote ${jpeg.size} bytes to ${file.path()}"
            }
            else -> error("unknown command")
        }
        ws.shouldUpdate = true
        return result
    }
}
