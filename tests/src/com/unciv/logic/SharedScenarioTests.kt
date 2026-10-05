package com.unciv.logic

import com.unciv.logic.chain.SharedScenario
import com.unciv.json.json
import org.junit.Assert.*
import org.junit.Test

class SharedScenarioTests {
    private fun scenario() = SharedScenario().apply {
        id = "fixture"; civilization = "Rome"; cityId = "original-capital"; startTurn = 40; duration = 20
    }

    @Test fun goalsAreParallelAndCompleteOnlyAtDeadline() {
        val s = scenario()
        s.constructed(48, "Rome", "original-capital", "Market")
        s.evaluate(49, true, true, false, 10, 2, true)
        assertEquals(2, s.completedCount); assertEquals("", s.outcome)
        s.evaluate(60, true, true, false, 70, 3, false)
        assertEquals("completed", s.outcome); assertEquals(60, s.holdTurn)
        s.evaluate(61, false, false, true, 0, 0, true)
        assertEquals(70, s.finalGold); assertEquals(3, s.completedCount)
    }

    @Test fun wrongCityEnemyConstructionAndLateResearchDoNotCount() {
        val s = scenario()
        s.constructed(45, "Rome", "new-capital", "Market")
        s.constructed(45, "Greece", "original-capital", "Market")
        s.constructed(61, "Rome", "original-capital", "Market")
        s.evaluate(61, true, true, false, 500, 2, false)
        assertEquals(0, s.completedCount); assertEquals("unfinished", s.outcome)
    }

    @Test fun recapturingOriginalCityWorksButReplacementCapitalDoesNot() {
        val s = scenario()
        s.evaluate(50, true, false, false, 10, 1, true)
        s.evaluate(60, true, true, false, 10, 2, true)
        assertEquals(60, s.holdTurn)
        val lost = scenario()
        lost.evaluate(60, true, false, false, 10, 2, true)
        assertEquals(-1, lost.holdTurn)
    }

    @Test fun cloneSerializationAndOldSavesPreserveCompatibility() {
        val game = GameInfo().apply { sharedScenario = scenario().apply { researchTurn = 45 } }
        val clone = game.clone()
        clone.sharedScenario!!.researchTurn = 55
        assertEquals(45, game.sharedScenario!!.researchTurn)
        val loaded = json().fromJson(GameInfo::class.java, json().toJson(game))
        assertEquals(45, loaded.sharedScenario!!.researchTurn)
        assertNull(json().fromJson(GameInfo::class.java, "{}").sharedScenario)
    }

    @Test fun defeatedAndFutureVersionsNeverBecomeSuccess() {
        val s = scenario()
        s.evaluate(45, true, false, true, 0, 0, true)
        assertEquals("defeated", s.outcome)
        val future = scenario().apply { version = 99 }
        future.evaluate(60, true, true, false, 100, 2, false)
        assertEquals("", future.outcome)
    }

    @Test fun engineObservationReadsSavedFactsAndDoesNotDependOnTheUI() {
        val s = scenario()
        val game = GameInfo().apply { turns = 48; sharedScenario = s }
        val civ = com.unciv.logic.civilization.Civilization("Rome").apply {
            nation = com.unciv.models.ruleset.nation.Nation()
            hasEverOwnedOriginalCapital = true
            tech.techsResearched.add("Currency")
            cities = arrayListOf(com.unciv.logic.city.City().apply { id = "original-capital" })
        }
        game.civilizations.add(civ)
        s.observe(game)
        assertEquals(48, s.researchTurn)
        game.turns = 60; s.observe(game)
        assertEquals(60, s.holdTurn)
        assertEquals("unfinished", s.outcome)
    }

    @Test fun goalDefinitionsSurviveProgressButChangeWhenRulesChange() {
        val s = scenario()
        val definition = s.definitionHash()
        s.researchTurn = 49; s.finalGold = 100
        assertEquals(definition, s.definitionHash())
        s.duration = 25
        assertNotEquals(definition, s.definitionHash())
    }

    private fun chapterGame(outcome: String): GameInfo {
        val s = scenario().apply {
            id = "civilization-on-the-brink-v1"; this.outcome = outcome
            researchTurn = 48; finalGold = 70; finalCities = 2
        }
        val civ = com.unciv.logic.civilization.Civilization("Rome").apply {
            nation = com.unciv.models.ruleset.nation.Nation()
            hasEverOwnedOriginalCapital = true; addGold(90)
            tech.techsResearched.add("Currency")
            cities = arrayListOf(com.unciv.logic.city.City().apply { id = "original-capital" })
        }
        return GameInfo().apply { turns = 65; sharedScenario = s; civilizations.add(civ) }
    }

    @Test fun chapterBranchesStartOnAcceptanceAndDoNotRewriteOriginalResults() {
        val game = chapterGame("completed"); val s = game.sharedScenario!!
        val hash = s.definitionHash()
        assertTrue(s.beginNextChapter(game))
        assertEquals("renewal", s.chapter!!.path)
        assertEquals(65, s.chapter!!.startTurn); assertEquals(80, s.chapter!!.deadline)
        assertFalse(s.beginNextChapter(game))
        s.chapter!!.briefingShown = true
        assertFalse(s.needsPopup)
        s.chapter!!.evaluate(80, true, true, false, 190, 2, false)
        assertTrue(s.needsPopup)
        s.chapter!!.resultShown = true
        assertFalse(s.needsPopup)
        assertEquals("completed", s.chapter!!.outcome)
        assertEquals(70, s.finalGold); assertEquals("completed", s.outcome)
        assertEquals(hash, s.definitionHash())
        val recovery = chapterGame("unfinished")
        assertTrue(recovery.sharedScenario!!.beginNextChapter(recovery))
        assertEquals("recovery", recovery.sharedScenario!!.chapter!!.path)
    }

    @Test fun recoveryCarriesAchievementsAndReceivesConstructionAfterChapterOneCloses() {
        val game = chapterGame("unfinished"); val s = game.sharedScenario!!
        assertTrue(s.beginNextChapter(game))
        assertEquals(48, s.chapter!!.researchTurn)
        s.constructed(70, "Greece", "original-capital", "Market")
        assertEquals(-1, s.chapter!!.constructionTurn)
        s.constructed(71, "Rome", "original-capital", "Market")
        s.chapter!!.evaluate(80, true, true, false, 120, 2, true)
        assertEquals("completed", s.chapter!!.outcome)
        assertEquals(-1, s.constructionTurn); assertEquals("unfinished", s.outcome)
        val loaded = json().fromJson(GameInfo::class.java, json().toJson(game))
        assertEquals(71, loaded.sharedScenario!!.chapter!!.constructionTurn)
        val clone = GameInfo().apply { sharedScenario = s }.clone()
        clone.sharedScenario!!.chapter!!.finalGold = 999
        assertEquals(120, s.chapter!!.finalGold)
    }

    @Test fun chapterRejectsLateClaimsAndSettlesWarAndCityOwnershipAtDeadline() {
        val chapter = com.unciv.logic.chain.SharedScenarioChapter().apply {
            path = "renewal"; startTurn = 20; startingGold = 40
        }
        chapter.evaluate(21, true, true, false, 140, 2, false)
        assertEquals(1, chapter.completedCount)
        chapter.evaluate(35, true, false, false, 160, 1, true)
        assertEquals("unfinished", chapter.outcome)
        chapter.evaluate(36, true, true, false, 200, 2, false)
        assertEquals(-1, chapter.holdTurn); assertEquals(-1, chapter.peaceTurn)
        val late = com.unciv.logic.chain.SharedScenarioChapter().apply { path = "renewal"; startTurn = 20 }
        late.evaluate(36, true, true, false, 1000, 3, false)
        assertEquals(0, late.completedCount)
        assertFalse(chapterGame("defeated").sharedScenario!!.hasNextChapter)
    }

    @Test fun recoveryAcceptedLaterCanRecognizeRebuildingWithoutChangingOriginalScore() {
        val game = chapterGame("unfinished")
        game.civilizations.first().cities.first().cityConstructions.builtBuildings.add("Market")
        val s = game.sharedScenario!!
        assertTrue(s.beginNextChapter(game))
        assertEquals(65, s.chapter!!.constructionTurn)
        assertEquals(-1, s.constructionTurn)
        assertEquals("unfinished", s.outcome)
    }
}
