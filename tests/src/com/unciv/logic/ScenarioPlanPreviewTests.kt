package com.unciv.logic

import com.unciv.json.json
import com.unciv.logic.chain.NavalCampaign
import com.unciv.logic.chain.ScenarioChapterPlan
import com.unciv.logic.chain.SharedScenario
import com.unciv.ui.popups.scenarioPlanPages
import org.junit.Assert.*
import org.junit.Test

class ScenarioPlanPreviewTests {
    @Test fun futureNavalChaptersAreVisibleWithoutStartingOrChangingResults() {
        val scenario = SharedScenario().apply {
            id = "strait-watch-v1"; title = "The Strait Must Hold"; civilization = "Rome"
            cityId = "rome"; cityName = "Rome"; technology = "Navigation"; building = "Castle"; duration = 30
            navalCampaign = NavalCampaign().apply { enemyPortId = "athens"; enemyPortName = "Athens" }
        }
        val before = json().toJson(scenario)
        val pages = scenarioPlanPages(scenario)
        assertEquals(listOf(30, 25, 35), pages.map { it.duration })
        assertTrue(pages[1].goals[0].contains("3 warships within 4 tiles"))
        assertTrue(pages[2].goals[1].contains("[Athens] at the deadline"))
        assertTrue(pages[2].hint.contains("Caravel or Privateer"))
        assertEquals(before, json().toJson(scenario))
        assertNull(scenario.navalCampaign!!.active)
        assertEquals(1, scenario.chapterNumber)
    }

    @Test fun authoredPreviewPreservesDistinctGoalsCitiesAndDeadlines() {
        val scenario = SharedScenario().apply {
            title = "Relay"; duration = 10; technology = "Writing"; building = "Library"; cityName = "Rome"
            nextChapterPlans.add(ScenarioChapterPlan().apply {
                technology = "Currency"; building = "Market"; cityId = "antium"; cityName = "Antium"; duration = 15
            })
            nextChapterPlans.add(ScenarioChapterPlan().apply {
                technology = "Navigation"; building = "Harbor"; cityId = "rome"; cityName = "Rome"; duration = 30
            })
        }
        val before = json().toJson(scenario)
        val pages = scenarioPlanPages(scenario)
        assertEquals(listOf(10, 15, 30), pages.map { it.duration })
        assertEquals("2. Build [Market] in [Antium]", pages[1].goals[1])
        assertEquals("3. Hold [Rome] at scenario turn [30]", pages[2].goals[2])
        assertEquals(before, json().toJson(scenario))
        assertTrue(scenario.authoredChapterHistory.isEmpty())
    }
}
