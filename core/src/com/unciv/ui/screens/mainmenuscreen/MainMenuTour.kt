package com.unciv.ui.screens.mainmenuscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen

/**
 * The first-launch tour of the main menu: one button at a time is left lit while the rest of the
 * screen is dimmed, with an arrow and a line of text saying what that button is for.
 *
 * Only the fork's own buttons are toured - Guide and Wallet. The others say what they do in their
 * names; these two are the ones a new player would otherwise pass by, and the Wallet is the
 * one they might wrongly think the game needs.
 *
 * A tap anywhere moves on, so the tour never traps a player who has already understood it.
 * [onDone] runs after the last step; the caller records there that it has been seen.
 */
class MainMenuTour(
    private val stage: Stage,
    private val steps: List<Step>,
    private val onDone: () -> Unit
) : Group() {

    class Step(val target: Actor, val text: String)

    private var index = 0

    init {
        touchable = Touchable.enabled
        onClick { next() }
    }

    fun show() {
        setSize(stage.width, stage.height)
        stage.addActor(this)
        showStep()
    }

    private fun next() {
        index++
        if (index < steps.size) return showStep()
        remove()
        onDone()
    }

    private fun showStep() {
        clearChildren()
        val step = steps[index]

        // Stage coordinates of the lit button, with a margin so the dimming does not touch it.
        val corner = step.target.localToStageCoordinates(Vector2(0f, 0f))
        val left = corner.x - highlightMargin
        val bottom = corner.y - highlightMargin
        val right = corner.x + step.target.width + highlightMargin
        val top = corner.y + step.target.height + highlightMargin

        // Four panels around the button rather than one over everything, so the button itself
        // keeps its own colours.
        dim(0f, 0f, width, bottom)
        dim(0f, top, width, height - top)
        dim(0f, bottom, left, top - bottom)
        dim(right, bottom, width - right, top - bottom)
        outline(left, bottom, right, top)

        val isLast = index == steps.lastIndex
        val callout = Table().pad(18f, 22f, 16f, 22f)
        callout.background = BaseScreen.skinStrings.getUiBackground(
            "MainMenuScreen/TourCallout",
            BaseScreen.skinStrings.roundedEdgeRectangleShape,
            Color.valueOf("1d1a15f2")
        )
        val text = step.text.toLabel(fontSize = 24)
        text.wrap = true
        callout.add(text).width(calloutTextWidth).row()
        val progress = "${index + 1}/${steps.size}"
        val footer = Table()
        footer.add(progress.toLabel(Color.LIGHT_GRAY, 20)).expandX().left()
        footer.add((if (isLast) "Got it" else "Next").toLabel(accent, 26))
        callout.add(footer).fillX().padTop(12f)
        callout.pack()
        callout.touchable = Touchable.disabled

        placeCallout(callout, left, bottom, right, top)
    }

    /**
     * Put the callout on whichever side of the button has room, the arrow between them pointing
     * at the button. Sideways first, since the menu is two columns in the middle of a landscape
     * screen; above or below when the screen is too narrow for that (the one-column portrait menu).
     */
    private fun placeCallout(callout: Table, left: Float, bottom: Float, right: Float, top: Float) {
        val arrowSize = 56f
        val needed = callout.width + arrowSize + 2 * gap + edgeMargin
        val roomLeft = left
        val roomRight = width - right
        val midY = (bottom + top) / 2
        val midX = (left + right) / 2

        val arrowAlign: Int
        val arrowX: Float
        val arrowY: Float
        var calloutX: Float
        var calloutY: Float
        when {
            roomLeft >= needed || roomRight >= needed -> {
                val onLeft = roomLeft >= roomRight
                arrowY = midY - arrowSize / 2
                if (onLeft) {
                    arrowAlign = Align.right
                    arrowX = left - gap - arrowSize
                    calloutX = arrowX - gap - callout.width
                } else {
                    arrowAlign = Align.left
                    arrowX = right + gap
                    calloutX = arrowX + arrowSize + gap
                }
                calloutY = midY - callout.height / 2
            }
            else -> {
                val above = height - top >= bottom
                arrowX = midX - arrowSize / 2
                if (above) {
                    arrowAlign = Align.bottom
                    arrowY = top + gap
                    calloutY = arrowY + arrowSize + gap
                } else {
                    arrowAlign = Align.top
                    arrowY = bottom - gap - arrowSize
                    calloutY = arrowY - gap - callout.height
                }
                calloutX = midX - callout.width / 2
            }
        }
        calloutX = calloutX.coerceIn(edgeMargin, (width - callout.width - edgeMargin).coerceAtLeast(edgeMargin))
        calloutY = calloutY.coerceIn(edgeMargin, (height - callout.height - edgeMargin).coerceAtLeast(edgeMargin))
        callout.setPosition(calloutX, calloutY)
        addActor(callout)

        val arrow = ImageGetter.getArrowImage(arrowAlign)
        arrow.color = accent
        arrow.setSize(arrowSize, arrowSize)
        arrow.setOrigin(Align.center)
        arrow.setPosition(arrowX, arrowY)
        arrow.touchable = Touchable.disabled
        // A small nudge towards the button and back, so the eye finds the arrow first.
        val nudge = 10f
        val (dx, dy) = when (arrowAlign) {
            Align.right -> nudge to 0f
            Align.left -> -nudge to 0f
            Align.bottom -> 0f to -nudge
            else -> 0f to nudge
        }
        arrow.addAction(Actions.forever(Actions.sequence(
            Actions.moveBy(dx, dy, 0.45f),
            Actions.moveBy(-dx, -dy, 0.45f)
        )))
        addActor(arrow)
    }

    private fun dim(x: Float, y: Float, w: Float, h: Float) {
        if (w <= 0f || h <= 0f) return
        val panel = ImageGetter.getWhiteDot()
        panel.color = dimColor
        panel.setBounds(x, y, w, h)
        panel.touchable = Touchable.disabled
        addActor(panel)
    }

    private fun outline(left: Float, bottom: Float, right: Float, top: Float) {
        val t = 3f
        for ((x, y, w, h) in listOf(
            listOf(left, bottom, right - left, t),
            listOf(left, top - t, right - left, t),
            listOf(left, bottom, t, top - bottom),
            listOf(right - t, bottom, t, top - bottom),
        )) {
            val line = ImageGetter.getWhiteDot()
            line.color = accent
            line.setBounds(x, y, w, h)
            line.touchable = Touchable.disabled
            addActor(line)
        }
    }

    private companion object {
        const val highlightMargin = 8f
        const val gap = 10f
        const val edgeMargin = 16f
        const val calloutTextWidth = 380f
        val dimColor = Color(0f, 0f, 0f, 0.72f)
        /** The gold of the store art and the certificate. */
        val accent: Color = Color.valueOf("d3ac6c")
    }
}
