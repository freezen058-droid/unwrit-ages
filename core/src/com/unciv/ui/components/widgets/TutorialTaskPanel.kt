package com.unciv.ui.components.widgets

import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.UncivGame
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.Event
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.civilopediascreen.MarkupRenderer

/**
 *  The tutorial task the player is on right now, or null if there is none.
 *
 *  One definition used by every screen that shows it, so the world map and the city screen can
 *  never disagree about which task is current.
 */
fun currentTutorialTask(civ: Civilization): Event? {
    if (!UncivGame.Current.settings.showTutorials) return null
    if (civ.isDefeated()) return null
    val state = civ.state
    return civ.gameInfo.ruleset.events.values.firstOrNull {
        it.presentation == Event.Presentation.Floating && it.isAvailable(state)
    }
}

/**
 *  The current task's instructions, for a screen that is not the world map.
 *
 *  The world screen has had a floating task panel all along; the city screen never did - and
 *  several of the tasks tell the player to do something *in* the city screen ("Enter city screen
 *  → click the assigned tile to unassign → click an unassigned tile"). The instruction therefore
 *  vanished at the exact moment its second and third steps had to be carried out, which is how a
 *  beginner ends up in the city screen not knowing what they came for.
 *
 *  Text only, no illustration: this sits on the busiest screen in the game, and the pictures are
 *  screenshots of the world map anyway.
 */
class TutorialTaskPanel : Table() {
    init {
        background = BaseScreen.skinStrings.getUiBackground(
            "WorldScreen/TutorialTaskTable",
            tintColor = BaseScreen.skinStrings.skinConfig.baseColor.darken(0.5f)
        )
        touchable = Touchable.enabled
        isVisible = false
    }

    /** Fill with [civ]'s current task, or hide when there is none or the player collapsed it.
     *  [labelWidth] is what the instruction wraps at - a task like "Enter city screen -> click the
     *  assigned tile to unassign -> click an unassigned tile" is one long line and would otherwise
     *  run off both edges of the screen. */
    fun update(civ: Civilization, labelWidth: Float) {
        clear()
        val task = currentTutorialTask(civ)
        if (task == null) {
            isVisible = false
            return
        }
        if (UncivGame.Current.isTutorialTaskCollapsed) {
            // Same one-tap collapse the world screen has, so a player who has hidden the hint
            // there does not find it back again in here.
            add(currentTaskDot()).pad(5f)
        } else {
            val lines = task.civilopediaText.filter { it.extraImage.isEmpty() }
            MarkupRenderer.renderTo(this, lines, labelWidth)
        }
        pack()
        isVisible = true
    }

    private fun currentTaskDot() =
        ImageGetter.getImage("OtherIcons/HiddenTutorialTask").apply { setSize(30f, 30f) }
}
