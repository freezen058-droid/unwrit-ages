package com.unciv.ui.popups

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.models.ruleset.Ruleset
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.center
import com.unciv.ui.components.extensions.getCloseButton
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.TabbedPager
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.mainmenuscreen.MainMenuTour

/**
 * Everything a new player needs, in the order they need it.
 *
 * The game already ships sixty tutorials, and they are good - but they only ever appear one at a
 * time, triggered by something that just happened, and a player who wants to *look something up*
 * has nowhere to go. This is that place: the same texts, grouped into six chapters and readable
 * end to end, with no game in progress required.
 *
 * The chapters name tutorials by their [Ruleset] entry name, and a name that is not in the ruleset
 * is skipped rather than blowing up - a mod can replace the tutorial set, and this should degrade
 * to "fewer chapters" rather than to a crash on the main menu.
 */
class TutorialGuidePopup(
    stageToShowOn: Stage,
    private val ruleset: Ruleset,
    /** Starts the teaching game. Null where there is no menu to start one from. */
    private val startTutorialGame: (() -> Unit)? = null,
    /** The game being played, if any. Its step-by-step progress gets a tab, but only in the
     *  teaching game - no other game has any, and an empty checklist would read as a promise. */
    private val gameInfo: GameInfo? = null
) : Popup(stageToShowOn, scrollable = Scrollability.None) {

    private companion object {
        /** The gold of the menu tour's highlight, the store art and the certificate. */
        val playAccent: Color = Color.valueOf("d3ac6c")

        /** Headings for tutorials whose ruleset name is an internal trigger id - shown as-is, those
         *  read "BarbarianEncountered" in every language (09-25). */
        val TITLES = mapOf(
            "BarbarianEncountered" to "Barbarians",
            "EnemyCityNeedsConqueringWithMeleeUnit" to "Capturing a City",
        )

        /** Chapter title to the tutorials it collects, in reading order rather than ruleset order. */
        val CHAPTERS = listOf(
            "Getting started" to listOf(
                "Introduction", "New Game", "Slow Start", "Settler", "City Expansion"),
            "Running your cities" to listOf(
                "Food", "Production", "Gold", "Happiness", "Unhappiness",
                "Culture and Policies", "Specialists", "Golden Age"),
            "Research" to listOf(
                "Science", "Great People", "Research Agreements"),
            "Land and resources" to listOf(
                "Workers", "Luxury Resource", "Strategic Resource",
                "Removing Terrain Features", "Roads and Railroads", "Trade Route"),
            "War" to listOf(
                "Combat", "Injured Units", "Experience", "BarbarianEncountered", "Enemy City",
                "EnemyCityNeedsConqueringWithMeleeUnit", "After Conquering", "Pillaging"),
            "Victory" to listOf("Victory Types"),
        )
    }

    /** Set by [chapterPage] when the play offer is shown, for the first-open pointer. */
    private var playButton: Table? = null

    init {
        clickBehindToClose = true
        innerTable.pad(0f)

        val tabMaxWidth = if (stageToShowOn.width < 600f) stageToShowOn.width - 10f else 0.8f * stageToShowOn.width
        val tabMinWidth = 0.6f * stageToShowOn.width
        val tabMaxHeight = 0.8f * stageToShowOn.height

        val tabs = TabbedPager(
            tabMinWidth, tabMaxWidth, tabMaxHeight, tabMaxHeight,
            headerFontSize = 21, backgroundColor = Color.CLEAR
        )
        add(tabs).pad(0f).grow().row()

        for ((index, chapter) in CHAPTERS.withIndex()) {
            val (title, tutorialNames) = chapter
            // The offer to play sits at the top of the first chapter rather than on the main menu:
            // it is the one place we know a beginner is already looking, and the menu has enough
            // ways to start a game on it already.
            val page = chapterPage(tutorialNames, tabMaxWidth, withPlayButton = index == 0)
            if (page == null) continue
            tabs.addPage(title, page, ImageGetter.getImage("OtherIcons/Quickstart"), 24f)
        }

        tabs.addPage("Sharing & keepsakes", UnwritAgesFeaturesPopup.guidePage(tabMaxWidth),
            ImageGetter.getImage("OtherIcons/Quickstart"), 24f)

        // Last tab: the whole list with what is done and what each undone one is waiting for.
        // The chapters say how the game works; this says where the player is in learning it.
        val game = gameInfo
        if (game != null && game.isTutorialGame)
            tabs.addPage("Your progress", TutorialTaskBoard.build(ruleset, game.tutorialTasksCompleted, tabMaxWidth),
                ImageGetter.getImage("OtherIcons/Quickstart"), 24f)

        tabs.decorateHeader(getCloseButton { close() })
        // A TabbedPager opens with no page selected, which showed as an empty box under the tabs
        // until the player happened to tap one.
        tabs.selectPage(0)

        pack()
        center(stageToShowOn)
        pointAtPlayButtonOnce(stageToShowOn)
    }

    /**
     * The first time the Guide is opened from the menu, point at the tutorial game the same way the
     * menu tour points at the Guide. After a delay: the button has no stage position until the
     * popup has been opened and laid out.
     */
    private fun pointAtPlayButtonOnce(stage: Stage) {
        val target = playButton ?: return
        val settings = UncivGame.Current.settings
        if (settings.guideTourShown) return
        stage.addAction(Actions.delay(0.4f, Actions.run {
            if (target.stage == null) return@run  // closed before the pointer came up
            MainMenuTour(stage, listOf(MainMenuTour.Step(target,
                "Start here: a small practice game that gives you one task at a time."))) {
                settings.guideTourShown = true
                settings.save()
            }.show()
        }))
    }

    /** One chapter, or null if the ruleset has none of its tutorials. */
    private fun chapterPage(tutorialNames: List<String>, width: Float, withPlayButton: Boolean = false): Table? {
        val found = tutorialNames.mapNotNull { name ->
            val steps = ruleset.tutorials[name]?.steps?.filter { it.isNotBlank() }
            if (steps.isNullOrEmpty()) null else name to steps
        }
        if (found.isEmpty()) return null

        val table = Table(BaseScreen.skin)
        table.pad(10f)
        table.defaults().pad(5f)

        val play = startTutorialGame
        if (withPlayButton && play != null) {
            // Gold like the tour's highlight and larger than any other button here: it is the one
            // thing on this page a beginner should press.
            val playButton = Table()
            playButton.background = BaseScreen.skinStrings.getUiBackground(
                "TutorialGuide/PlayButton", BaseScreen.skinStrings.roundedEdgeRectangleShape, playAccent)
            playButton.pad(14f, 36f, 14f, 36f)
            playButton.add(ImageGetter.getImage("OtherIcons/Quickstart").apply { color = Color.BLACK })
                .size(34f).padRight(12f)
            playButton.add("Start a tutorial game".toLabel(Color.BLACK, 30))
            playButton.touchable = Touchable.enabled
            playButton.onClick { close(); play() }
            this.playButton = playButton
            table.add(playButton).padTop(6f).padBottom(8f).row()
            val blurb = "A small map, one rival and the gentlest difficulty, with the hints switched on - the fastest way to see how all of this works."
                .toLabel(fontSize = Constants.defaultFontSize - 4)
            blurb.wrap = true
            blurb.setAlignment(Align.center)
            table.add(blurb).width(width - 60f).row()
            table.addSeparator(Color.GRAY).padTop(10f).padBottom(10f)
        }
        // The text does the work here, so give it as much width as the tab will allow and let it
        // wrap - a guide laid out in a narrow column is a guide nobody finishes.
        val textWidth = width - 60f
        for ((index, entry) in found.withIndex()) {
            val (name, steps) = entry
            if (index > 0) table.addSeparator(Color.GRAY).padTop(10f).padBottom(10f)
            table.add((TITLES[name] ?: name).toLabel(fontSize = Constants.headingFontSize))
                .width(textWidth).align(Align.left).row()
            for (step in steps) {
                val label = step.toLabel()
                label.wrap = true
                label.setAlignment(Align.topLeft)
                table.add(label).width(textWidth).align(Align.left).row()
            }
        }
        return table
    }
}
