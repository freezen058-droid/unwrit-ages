package com.unciv.ui.popups

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.models.ruleset.Event
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.screens.basescreen.BaseScreen

/**
 *  Every tutorial task, what is done, and what the undone ones are waiting for.
 *
 *  The floating panel shows one task at a time and says nothing about how many are left or why
 *  the next one has not appeared - so a player who finished the chain saw the panel simply stop,
 *  and a player waiting on "Move an air unit" had no way to know it wants an air unit. This is
 *  the whole list, with the conditions read out of the tasks themselves rather than written down
 *  here a second time: a task whose conditions change cannot end up described wrongly.
 *
 *  What it cannot do is say how to *reach* a condition - "you need an air unit" is in the data,
 *  "research Flight to get one" is not, and inferring it would mean walking the tech tree for a
 *  hint. Those few are worth writing by hand, and are not invented here.
 */
object TutorialTaskBoard {
    private const val DONE = "✓"
    private const val CURRENT = "▶"
    private const val PENDING = "•"

    fun build(ruleset: Ruleset, width: Float): Table {
        val table = Table(BaseScreen.skin)
        table.pad(10f)
        table.defaults().pad(4f).align(Align.left)

        val tasks = ruleset.events.values.filter { it.presentation == Event.Presentation.Floating }
        if (tasks.isEmpty()) return table

        val byName = tasks.associateBy { it.name }
        val chain = orderedChain(tasks, byName)
        val situational = tasks.filter { it !in chain }

        val done = UncivGame.Current.settings.tutorialTasksCompleted
        // The current one is the first of the chain not yet done - which needs no game in
        // progress, so this page reads correctly from the main menu too.
        val current = chain.firstOrNull { taskKey(it) !in done }

        table.add("Step by step".toLabel(fontSize = Constants.headingFontSize)).row()
        for (task in chain) addRow(table, task, done, task === current, width)

        if (situational.isNotEmpty()) {
            table.addSeparator(Color.GRAY).padTop(10f).padBottom(10f)
            table.add("When it comes up".toLabel(fontSize = Constants.headingFontSize)).row()
            for (task in situational) addRow(table, task, done, false, width)
        }
        return table
    }

    /** Walk the "after X" links so the list is in the order the tasks actually appear. */
    private fun orderedChain(tasks: List<Event>, byName: Map<String, Event>): List<Event> {
        val successor = HashMap<String, Event>()
        val hasPredecessor = HashSet<String>()
        for (task in tasks) {
            val after = predecessorOf(task) ?: continue
            successor["Tutorial Task: [$after]"] = task
            hasPredecessor += task.name
        }
        val head = tasks.firstOrNull { it.name !in hasPredecessor && successor.containsKey(it.name) }
            ?: return emptyList()
        val chain = ArrayList<Event>()
        var node: Event? = head
        while (node != null && node !in chain) {
            chain += node
            node = successor[node.name]
        }
        return chain
    }

    private fun predecessorOf(task: Event) = task.uniqueObjects
        .filter { it.type == UniqueType.OnlyAvailable }
        .flatMap { it.modifiers }
        .firstOrNull { it.type == UniqueType.ConditionalTutorialCompleted }
        ?.params?.firstOrNull()

    /** The name as [com.unciv.models.metadata.GameSettings.tutorialTasksCompleted] stores it. */
    private fun taskKey(task: Event) =
        task.name.removePrefix("Tutorial Task: [").removeSuffix("]")

    /** The heading the player sees on the floating panel, so both name the task the same way. */
    private fun heading(task: Event) =
        task.civilopediaText.firstOrNull { it.text.isNotEmpty() }?.text ?: taskKey(task)

    /** Everything an undone task is still waiting for, minus the chain bookkeeping. */
    private fun unmetConditions(task: Event) = task.uniqueObjects
        .filter { it.type == UniqueType.OnlyAvailable }
        .flatMap { it.modifiers }
        .filter {
            it.type != UniqueType.ConditionalTutorialsEnabled &&
                it.type != UniqueType.ConditionalTutorialCompleted
        }
        .map { it.text }

    private fun addRow(table: Table, task: Event, done: Set<String>, isCurrent: Boolean, width: Float) {
        val isDone = taskKey(task) in done
        val marker = when {
            isDone -> DONE
            isCurrent -> CURRENT
            else -> PENDING
        }
        val color = when {
            isDone -> Color.LIGHT_GRAY
            isCurrent -> Color.WHITE
            else -> Color.GRAY
        }
        val row = Table()
        row.add("$marker ${heading(task).tr()}".toLabel(fontColor = color)).align(Align.left).row()
        if (!isDone) {
            for (condition in unmetConditions(task)) {
                val label = condition.tr().toLabel(fontColor = Color.GRAY,
                    fontSize = Constants.defaultFontSize - 4)
                label.wrap = true
                row.add(label).width(width - 80f).padLeft(24f).align(Align.left).row()
            }
        }
        table.add(row).width(width - 40f).align(Align.left).row()
    }
}
