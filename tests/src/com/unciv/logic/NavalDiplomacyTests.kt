package com.unciv.logic

import com.unciv.Constants
import com.unciv.logic.chain.NavalCampaign
import com.unciv.logic.chain.NavalChapter
import com.unciv.logic.chain.SharedScenario
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.civilization.diplomacy.DiplomacyManager
import com.unciv.logic.trade.*
import com.unciv.testing.TestGame
import com.unciv.testing.BaseTestRunner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class NavalDiplomacyTests {
    private fun campaign() = SharedScenario().apply {
        id = "strait-watch-v1"; civilization = "Rome"; opponent = "Greece"
        cityId = "capital"; startTurn = 90; duration = 30
        navalCampaign = NavalCampaign().apply { enemyPortId = "port" }
    }

    @Test fun warLockIsSymmetricAndSpansAllThreeChaptersAndIntermissions() {
        val game = GameInfo().apply { turns = 102; sharedScenario = campaign() }
        val s = game.sharedScenario!!
        fun locked() {
            assertTrue(NavalCampaign.blocksPeace(game,"Rome","Greece"))
            assertTrue(NavalCampaign.blocksPeace(game,"Greece","Rome"))
            assertFalse(NavalCampaign.blocksPeace(game,"Rome","Egypt"))
        }
        locked()
        s.outcome = "completed"; locked()
        s.navalCampaign!!.control = NavalChapter().apply { startTurn = 120 }; locked()
        s.navalCampaign!!.control!!.outcome = "completed"; locked()
        s.navalCampaign!!.assault = NavalChapter().apply { number = 3; startTurn = 145 }; locked()
        s.navalCampaign!!.assault!!.outcome = "completed"
        assertFalse(NavalCampaign.blocksPeace(game,"Rome","Greece"))
    }

    @Test fun failureContinueAndOrdinarySavesAllowNormalDiplomacy() {
        val game = GameInfo().apply { turns = 102; sharedScenario = campaign() }
        val s = game.sharedScenario!!
        for (result in listOf("unfinished","defeated")) {
            s.outcome = result
            assertFalse(NavalCampaign.blocksPeace(game,"Rome","Greece"))
        }
        s.outcome = ""; s.freePlay = true
        assertFalse(NavalCampaign.blocksPeace(game,"Rome","Greece"))
        s.freePlay = false; s.navalCampaign = null
        assertFalse(NavalCampaign.blocksPeace(game,"Rome","Greece"))
        game.sharedScenario = null
        assertFalse(NavalCampaign.blocksPeace(game,"Rome","Greece"))
    }

    @Test fun peaceOffersQueuedTradesAndThirdPartyMediationCannotBypassTheLock() {
        val test = TestGame()
        val rome = test.addCiv(test.ruleset.nations.getValue("Rome"), isPlayer = true)
        val greece = test.addCiv(test.ruleset.nations.getValue("Greece"))
        val egypt = test.addCiv(test.ruleset.nations.getValue("Egypt"))
        for ((a,b) in listOf(rome to greece,rome to egypt,greece to egypt)) {
            a.diplomacy[b.civID] = DiplomacyManager(a,b.civID).apply { diplomaticStatus = DiplomaticStatus.War }
            b.diplomacy[a.civID] = DiplomacyManager(b,a.civID).apply { diplomaticStatus = DiplomaticStatus.War }
        }
        test.gameInfo.turns = 102
        test.gameInfo.sharedScenario = campaign().apply { civilization = rome.civID; opponent = greece.civID }
        rome.addGold(100); greece.addGold(100)
        val peace = TradeOffer(Constants.peaceTreaty,TradeOfferType.Treaty,speed=test.gameInfo.speed)
        val trade = TradeLogic(rome,greece)
        assertFalse(trade.ourAvailableOffers.any { it.name == Constants.peaceTreaty })
        assertFalse(trade.theirAvailableOffers.any { it.name == Constants.peaceTreaty })
        trade.currentTrade.ourOffers.add(peace); trade.currentTrade.theirOffers.add(peace)
        trade.currentTrade.ourOffers.add(TradeOffer(Constants.flatGold,TradeOfferType.Gold,20,test.gameInfo.speed))
        assertFalse(TradeEvaluation().isTradeValid(trade.currentTrade,rome,greece))
        val before = rome.gold
        trade.acceptTrade()
        assertEquals(before,rome.gold)
        assertTrue(rome.isAtWarWith(greece))
        assertTrue(rome.getDiplomacyManager(greece)!!.trades.isEmpty())
        rome.getDiplomacyManager(greece)!!.makePeace()
        assertTrue(rome.isAtWarWith(greece))
        assertFalse(TradeEvaluation().isPeaceProposalEnabled(greece,rome))
        val mediation = TradeLogic(rome,egypt)
        mediation.currentTrade.ourOffers.add(TradeOffer(greece.civID,TradeOfferType.PeaceProposal,1,test.gameInfo.speed))
        assertFalse(TradeEvaluation().isTradeValid(mediation.currentTrade,rome,egypt))
        mediation.acceptTrade()
        assertTrue(rome.isAtWarWith(greece))
        assertTrue(TradeLogic(rome,egypt).ourAvailableOffers.any { it.name == Constants.peaceTreaty })
        test.gameInfo.sharedScenario!!.freePlay = true
        assertTrue(TradeLogic(rome,greece).ourAvailableOffers.any { it.name == Constants.peaceTreaty })
        assertTrue(TradeEvaluation().isTradeValid(trade.currentTrade,rome,greece))
        trade.acceptTrade()
        assertFalse(rome.isAtWarWith(greece))
        assertEquals(before-20,rome.gold)
    }

    @Test fun onlyMilitaryShipsNearTheOriginalCapitalCountAtTheDeadline() {
        for (thirdNear in listOf(false,true)) {
            val test = TestGame()
            test.makeHexagonalMap(12, "Coast")
            // This helper sets terrain on its previous map; normalize the actual new tiles.
            for (tile in test.tileMap.values) {
                tile.baseTerrain = "Coast"; tile.setTerrainTransients()
            }
            val home = test.getTile(0,0)
            home.baseTerrain = "Grassland"
            home.setTerrainTransients()
            val rome = test.addCiv(test.ruleset.nations.getValue("Rome"), isPlayer = true)
            val city = test.addCity(rome,home)
            test.addUnit("Trireme",rome,test.getTile(1,0))
            test.addUnit("Trireme",rome,test.getTile(2,0))
            test.addUnit("Trireme",rome,test.getTile(if (thirdNear) 3 else 8,0))
            test.addUnit("Work Boats",rome,test.getTile(0,1))
            val s = campaign().apply {
                civilization = rome.civID; cityId = city.id; outcome = "completed"
                navalCampaign!!.control = NavalChapter().apply { startTurn = 120 }
            }
            test.gameInfo.sharedScenario = s
            test.gameInfo.turns = 144; s.observe(test.gameInfo)
            assertEquals(-1,s.navalCampaign!!.control!!.fleetTurn)
            test.gameInfo.turns = 145; s.observe(test.gameInfo)
            assertEquals(if (thirdNear) "completed" else "unfinished",s.currentOutcome)
        }
    }
}
