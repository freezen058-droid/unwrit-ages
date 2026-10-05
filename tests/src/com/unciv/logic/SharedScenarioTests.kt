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

    @Test fun countdownTracksEachDeadlineAndStopsWhenChallengeEnds() {
        val s = scenario()
        assertEquals(6, s.remainingTurns(s.deadline - 6))
        assertEquals(5, s.remainingTurns(s.deadline - 5))
        assertEquals(1, s.remainingTurns(s.deadline - 1))
        assertNull(s.remainingTurns(s.deadline))
        s.outcome = "completed"
        assertNull(s.remainingTurns(s.deadline - 1))
        s.chapter = com.unciv.logic.chain.SharedScenarioChapter().apply { path = "renewal"; startTurn = 30 }
        assertEquals(5, s.remainingTurns(40))
        s.chapter!!.outcome = "completed"
        assertNull(s.remainingTurns(44))
        s.chapter!!.outcome = ""
        s.freePlay = true
        assertNull(s.remainingTurns(44))
    }

    @Test fun navalCheckpointsRequireBothTheFleetAndTerritoryAtTheDeadline() {
        val c = com.unciv.logic.chain.NavalChapter().apply { number = 2; startTurn = 120 }
        c.evaluate(125, true, true, true, false, 40, 3)
        assertEquals(1, c.completedCount)
        c.evaluate(145, true, false, true, false, 50, 3)
        assertEquals("unfinished", c.outcome)
        c.evaluate(146, true, true, true, false, 100, 8)
        assertEquals(50, c.finalGold); assertEquals(-1, c.objectiveTurn)
        val third = com.unciv.logic.chain.NavalChapter().apply { number = 3; startTurn = 140 }
        third.evaluate(150, true, true, true, false, 30, 4)
        assertEquals(1, third.completedCount)
        third.evaluate(175, true, true, true, false, 35, 4)
        assertEquals("completed", third.outcome); assertEquals(3, third.completedCount)
        assertEquals(175, third.objectiveTurn)
    }

    @Test fun navalHistoryAndBriefingSurviveCloneAndSaveWithoutChangingChapterOne() {
        val s = scenario().apply {
            description = "A shared coastal challenge"
            outcome = "completed"; finalGold = 70
            navalCampaign = com.unciv.logic.chain.NavalCampaign().apply {
                enemyPortId = "port"; enemyPortName = "Athens"
                control = com.unciv.logic.chain.NavalChapter().apply { startTurn = 120; outcome = "completed"; finalGold = 45 }
                assault = com.unciv.logic.chain.NavalChapter().apply { number = 3; startTurn = 140 }
            }
        }
        val game = GameInfo().apply { sharedScenario = s }
        val loaded = json().fromJson(GameInfo::class.java, json().toJson(game))
        assertEquals(s.definitionHash(), loaded.sharedScenario!!.definitionHash())
        assertEquals("A shared coastal challenge", loaded.sharedScenario!!.description)
        assertEquals(45, game.clone().sharedScenario!!.navalCampaign!!.control!!.finalGold)
        assertEquals(5, s.remainingTurns(170))
        s.navalCampaign!!.active!!.evaluate(175, true, true, true, false, 80, 4)
        assertEquals(70, s.finalGold); assertEquals("completed", s.outcome)
        assertEquals(45, s.navalCampaign!!.control!!.finalGold)
        assertFalse(s.hasNextChapter)
    }

    @Test fun authoredThreeChaptersStartOnAcceptanceAndPreserveHistory() {
        val game = chapterGame("completed")
        val s = game.sharedScenario!!.apply {
            id = "authored-linear"
            nextChapterPlans = arrayListOf(
                com.unciv.logic.chain.ScenarioChapterPlan().apply {
                    technology = "Currency"; building = "Library"; cityId = "original-capital"; duration = 10
                },
                com.unciv.logic.chain.ScenarioChapterPlan().apply {
                    technology = "Currency"; building = "University"; cityId = "original-capital"; duration = 15
                })
        }
        val definition = s.definitionHash()
        assertEquals(3, s.totalChapters)
        assertTrue(s.beginNextChapter(game))
        assertEquals(65, s.authoredChapter!!.startTurn)
        assertEquals(2, s.chapterNumber)
        assertFalse(s.beginNextChapter(game))
        s.constructed(68, "Rome", "original-capital", "Library")
        s.authoredChapter!!.evaluate(75, true, true, false, 120, 2, false)
        assertTrue(s.hasNextChapter)
        game.turns = 80
        assertTrue(s.beginNextChapter(game))
        assertEquals(80, s.authoredChapter!!.startTurn)
        assertEquals(3, s.chapterNumber)
        assertEquals("authored-linear-chapter-3", s.authoredChapter!!.id)
        assertEquals(120, s.authoredChapterHistory.first().finalGold)
        s.authoredChapter!!.evaluate(95, true, true, false, 200, 2, false)
        assertEquals("unfinished", s.currentOutcome)
        assertFalse(s.hasNextChapter)
        assertEquals("completed", s.outcome)
        assertEquals(70, s.finalGold)
        assertEquals(definition, s.definitionHash())
        val loaded = json().fromJson(GameInfo::class.java, json().toJson(game)).sharedScenario!!
        assertEquals(3, loaded.chapterNumber)
        assertEquals("unfinished", loaded.currentOutcome)
        assertEquals(120, loaded.authoredChapterHistory.first().finalGold)
        val copy = s.copy()
        copy.authoredChapterHistory.first().finalGold = 999
        assertEquals(120, s.authoredChapterHistory.first().finalGold)
        assertNull(s.remainingTurns(94))
    }

    @Test fun authoredFailureBlocksLaterChaptersAndPlansAffectDefinition() {
        val s = scenario().apply {
            id = "authored-linear"; outcome = "unfinished"
            nextChapterPlans.add(com.unciv.logic.chain.ScenarioChapterPlan().apply {
                technology = "Education"; building = "University"; cityId = "original-capital"
            })
        }
        assertFalse(s.hasNextChapter)
        val definition = s.definitionHash()
        s.nextChapterPlans.first().duration = 30
        assertNotEquals(definition, s.definitionHash())
    }

    @Test fun freePlayPreservesResultsAndSurvivesSaveAndClone() {
        val s = scenario().apply { outcome = "unfinished"; finalGold = 70; freePlay = true }
        val game = GameInfo().apply { sharedScenario = s }
        val loaded = json().fromJson(GameInfo::class.java, json().toJson(game))
        assertTrue(loaded.sharedScenario!!.freePlay)
        assertTrue(game.clone().sharedScenario!!.freePlay)
        s.evaluate(80, true, true, false, 500, 3, false)
        assertEquals(70, s.finalGold); assertEquals("unfinished", s.outcome)
        assertFalse(s.beginNextChapter(game))
    }
}
