package com.unciv.ui.popups

import com.badlogic.gdx.graphics.Color
import com.unciv.logic.GameInfo
import com.unciv.logic.chain.SharedScenario
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.screens.basescreen.BaseScreen

/** One reusable, compact briefing/progress/result view. */
class SharedScenarioPopup(screen: BaseScreen, game: GameInfo, briefing: Boolean = false,
                          firstChapter: Boolean = false, showChallengeDetails: Boolean = false) : Popup(screen) {
    init {
        val scenario = requireNotNull(game.sharedScenario)
        scenario.observe(game)
        if (!firstChapter && !showChallengeDetails && !scenario.freePlay &&
            scenario.chapter?.outcome == "unfinished" && game === com.unciv.UncivGame.Current.gameInfo) {
            ChallengeEndingPopup(screen, game)
        } else {
        addGoodSizedLabel(scenario.title, color = Color.GOLD).row()
        val width = screen.stage.width * 0.65f
        fun line(text: String, gold: Boolean = false) {
            add(text.toLabel(if (gold) Color.GOLD else Color.WHITE).apply { wrap = true })
                .width(width).left().padTop(8f).row()
        }
        if (scenario.id == "civilization-on-the-brink-v1") {
            addButton("Scenario background") {
                val background = Popup(screen)
                background.addGoodSizedLabel("Scenario background", color = Color.GOLD).row()
                background.add("Two cities. A costly war. Rebuild your economy without losing your capital.".tr()
                    .toLabel().apply { wrap = true }).width(width).left().row()
                background.add("Greek forces threaten your border. Egypt remains a possible diplomatic partner.".tr()
                    .toLabel().apply { wrap = true }).width(width).left().padTop(10f).row()
                background.addCloseButton(); background.open(force = true)
            }.padRight(6f)
        }
        val chapter = scenario.chapter?.takeIf { it.supported && !firstChapter }
        if (chapter != null) {
            line((if (chapter.path == "renewal") "Chapter II: Renewal" else "Chapter II: Recovery").tr(), true)
            line((if (chapter.path == "renewal")
                "Your capital endured. Turn survival into prosperity and peace."
                else "The crisis is not over. Finish rebuilding and give your capital a second chance.").tr())
            line("Chapter turn [${(game.turns - chapter.startTurn).coerceIn(0, 15)}] / [15]".tr())
            fun mark(turn: Int) = if (turn >= 0) "✓  " else "□  "
            if (chapter.path == "renewal") {
                line(mark(chapter.treasuryTurn) + "1. Reach [${chapter.startingGold.toLong() + 100}] gold in your treasury".tr())
                val opponent = game.civilizations.firstOrNull { it.civID == scenario.opponent }?.civName ?: scenario.opponent
                line(mark(chapter.peaceTurn) + "2. Be at peace with [${opponent.tr()}] at chapter turn [15]".tr())
            } else {
                line(mark(chapter.researchTurn) + "1. Research [${scenario.technology.tr()}]".tr())
                line(mark(chapter.constructionTurn) + "2. Restore [${scenario.cityName.tr()}] with a [${scenario.building.tr()}]".tr())
            }
            line(mark(chapter.holdTurn) + "3. Hold [${scenario.cityName.tr()}] at chapter turn [15]".tr())
            if (chapter.outcome.isNotEmpty()) {
                line(when (chapter.outcome) {
                    "completed" -> "A new chapter is written. Your civilization has found its footing."
                    "defeated" -> "This civilization has fallen. Its story ends here, but another strategy awaits."
                    else -> "Challenge ended. Not every objective was completed."
                }.tr(), true)
                line("Treasury: [${chapter.finalGold}] · Cities: [${chapter.finalCities}]".tr())
                line("Continue your civilization, or share your save to invite another strategy.".tr())
                chapter.resultShown = true
            }
            chapter.briefingShown = true
                addButton("Chapter I results") { close(); SharedScenarioPopup(screen, game, firstChapter = true) }
                if (showChallengeDetails && chapter.outcome == "unfinished" && !scenario.freePlay &&
                    game === com.unciv.UncivGame.Current.gameInfo)
                    addCloseButton("Back", action = { ChallengeEndingPopup(screen, game) })
                else addCloseButton(if (chapter.outcome.isNotEmpty()) "Continue" else "Close")
        } else {
            if (briefing && scenario.id == "civilization-on-the-brink-v1")
                line("Greek forces threaten your border. Egypt remains a possible diplomatic partner.".tr())
            line("Scenario turn [${(game.turns - scenario.startTurn).coerceIn(0, scenario.duration)}] / [${scenario.duration}]".tr())
            line("Suggested order - choose your own strategy.".tr())
            for (goal in scenarioGoalLines(scenario)) line(goal)
            line(when (scenario.outcome) {
                "completed" -> "Civilization restored - all three goals completed.".tr()
                "unfinished" -> "The deadline has passed. Your civilization's story can continue.".tr()
                "defeated" -> "This civilization has fallen. Try a different strategy from the same starting save.".tr()
                else -> if (scenario.id == "civilization-on-the-brink-v1")
                    "Research unlocks the market. The market supports your army. Keep your capital at the deadline.".tr()
                    else "Complete your research and construction. Hold the chosen city at the deadline.".tr()
            }, true)
            if (scenario.outcome.isNotEmpty()) {
                line("Treasury: [${scenario.finalGold}] · Cities: [${scenario.finalCities}]".tr() + " · " +
                    (if (scenario.finalAtWar) "At war" else "At peace").tr())
                scenario.resultShown = true
                if (scenario.id == "civilization-on-the-brink-v1") {
                    line(when (scenario.outcome) {
                        "completed" -> "Your capital stands and its economy is rebuilt. Now shape the peace that follows."
                        "defeated" -> "This civilization's story ends here. Return to the featured scenario to try another strategy."
                        else -> "Your civilization survived, but its recovery is unfinished. The next chapter offers another chance."
                    }.tr(), true)
                    if (scenario.hasNextChapter && game === com.unciv.UncivGame.Current.gameInfo) {
                        addButton(if (scenario.outcome == "completed") "Begin Renewal" else "Begin Recovery") {
                            if (scenario.beginNextChapter(game)) {
                                close(); SharedScenarioPopup(screen, game)
                            }
                        }
                    } else if (firstChapter && scenario.chapter != null) {
                        addButton("Current chapter") { close(); SharedScenarioPopup(screen, game) }
                    }
                }
            }
            if (briefing) scenario.briefingShown = true
            addCloseButton(if (briefing) "Begin" else if (scenario.outcome.isNotEmpty()) "Continue" else "Close")
        }
        open(force = true)
        }
    }
}

fun scenarioGoalLines(s: SharedScenario): List<String> {
    fun mark(turn: Int) = if (turn >= 0) "✓  " else "□  "
    return listOf(
        mark(s.researchTurn) + "1. Research [${s.technology.tr()}]".tr(),
        mark(s.constructionTurn) + "2. Build [${s.building.tr()}] in [${s.cityName.tr()}]".tr(),
        mark(s.holdTurn) + "3. Hold [${s.cityName.tr()}] at scenario turn [${s.duration}]".tr()
    )
}
