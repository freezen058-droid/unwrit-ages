package com.unciv.ui.screens.worldscreen.status

import com.badlogic.gdx.graphics.Color
import com.unciv.Constants
import com.unciv.logic.civilization.managers.ReligionManager
import com.unciv.models.Counter
import com.unciv.models.ruleset.BeliefType
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.cityscreen.CityScreen
import com.unciv.ui.screens.overviewscreen.EspionageOverviewScreen
import com.unciv.ui.screens.pickerscreens.DiplomaticVotePickerScreen
import com.unciv.ui.screens.pickerscreens.PantheonPickerScreen
import com.unciv.ui.screens.pickerscreens.PolicyPickerScreen
import com.unciv.ui.screens.pickerscreens.ReligiousBeliefsPickerScreen
import com.unciv.ui.screens.pickerscreens.TechPickerScreen
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread
import yairm210.purity.annotations.Readonly

enum class NextTurnAction(protected val text: String, val color: Color) {
    Default("", ImageGetter.CHARCOAL) {
        override val icon get() = null
        override fun isChoice(worldScreen: WorldScreen) = false
    },
    RetryUpload("Retry Upload", Color.RED) {
        override fun isChoice(worldScreen: WorldScreen) =
            worldScreen.failedUpload
        override fun action(worldScreen: WorldScreen) =
            worldScreen.nextTurn()
    },
    AutoPlay("AutoPlay", Color.WHITE) {
        override fun isChoice(worldScreen: WorldScreen) =
            worldScreen.autoPlay.isAutoPlaying()
        override fun action(worldScreen: WorldScreen) =
            worldScreen.autoPlay.stopAutoPlay()
    },
    Working(Constants.working, Color.GRAY) {
        override fun isChoice(worldScreen: WorldScreen) =
            worldScreen.isNextTurnUpdateRunning()
    },
    Waiting("Waiting for other players...",Color.GRAY) {
        override fun getText(worldScreen: WorldScreen) =
            if (worldScreen.gameInfo.gameParameters.isOnlineMultiplayer)
                "Waiting for [${worldScreen.gameInfo.currentPlayerCiv}]..."
            else text
        override fun isChoice(worldScreen: WorldScreen) =
            !worldScreen.isPlayersTurn
    },
    PickConstruction("Pick construction", Color.CORAL) {
        override fun isChoice(worldScreen: WorldScreen) =
            getCityWithNoProductionSet(worldScreen) != null
        override fun action(worldScreen: WorldScreen) {
            val city = getCityWithNoProductionSet(worldScreen) ?: return
            val openCity = { worldScreen.game.pushScreen(CityScreen(worldScreen.selectedGameView.getCityView(city))) }
            val settings = worldScreen.game.settings
            // Asked once, when there is a second city to manage; never in the teaching game, whose tasks pick by hand
            if (settings.autoAssignCityProduction || settings.autoProductionOffered
                    || worldScreen.gameInfo.isTutorialGame || city.civ.cities.size < 2) {
                openCity()
                return
            }
            settings.autoProductionOffered = true
            settings.save()
            Popup(worldScreen).apply {
                addGoodSizedLabel("Let cities choose their own production when their queue runs out?").row()
                addGoodSizedLabel("You can still set any city's queue yourself. Change this later in Options > Automation.",
                    Constants.defaultFontSize - 4).padTop(10f).row()
                addOKButton("Let them choose") {
                    settings.autoAssignCityProduction = true
                    settings.save()
                    for (ownCity in city.civ.cities) ownCity.cityConstructions.chooseNextConstruction()
                    worldScreen.shouldUpdate = true
                }
                addCloseButton("I'll choose", action = openCity)
                equalizeLastTwoButtonWidths()
            }.open()
        }
    },
    PickTech("Pick a tech", Color.SKY) {
        override fun isChoice(worldScreen: WorldScreen) =
            worldScreen.selectedGameView.civView.shouldOpenTechPicker()
        override fun action(worldScreen: WorldScreen) =
            worldScreen.game.pushScreen(
                TechPickerScreen(worldScreen.selectedGameView.civView.getCiv(), null)
            )
    },
    PickPolicy("Pick a policy", Color.VIOLET) {
        override fun isChoice(worldScreen: WorldScreen) =
            worldScreen.selectedGameView.civView.shouldShowPolicyPicker()
        override fun action(worldScreen: WorldScreen) {
            worldScreen.game.pushScreen(PolicyPickerScreen(worldScreen.selectedCiv, worldScreen.canChangeState))
            worldScreen.selectedGameView.civView.tryDismissPolicyPicker()
        }
    },
    MoveSpies("Move Spies", Color.WHITE) {
        override fun isChoice(worldScreen: WorldScreen) =
                worldScreen.gameInfo.isEspionageEnabled() && worldScreen.selectedGameView.civView.shouldShowMoveSpies()
        override fun action(worldScreen: WorldScreen) {
            worldScreen.game.pushScreen(EspionageOverviewScreen(worldScreen.selectedCiv, worldScreen))
            worldScreen.selectedGameView.civView.tryDismissMoveSpies()
        }
    },
    FoundPantheon("Found Pantheon", Color.valueOf(BeliefType.Pantheon.color)) {
        override fun isChoice(worldScreen: WorldScreen) =
            worldScreen.selectedGameView.civView.canFoundPantheon()
        override fun action(worldScreen: WorldScreen) =
            worldScreen.game.pushScreen(PantheonPickerScreen(worldScreen.selectedGameView.civView.getCiv()))
    },
    ExpandPantheon("Expand Pantheon", Color.valueOf(BeliefType.Pantheon.color)) {
        override fun isChoice(worldScreen: WorldScreen) =
            worldScreen.selectedGameView.civView.canExpandPantheon()
        override fun action(worldScreen: WorldScreen) =
            worldScreen.game.pushScreen(PantheonPickerScreen(worldScreen.selectedGameView.civView.getCiv()))
    },
    FoundReligion("Found Religion", Color.valueOf(BeliefType.Founder.color)) {
        override fun isChoice(worldScreen: WorldScreen) =
            worldScreen.selectedGameView.civView.isFoundingReligion()
        override fun action(worldScreen: WorldScreen) =
            openReligionPicker(worldScreen, true) { getBeliefsToChooseAtFounding() }
    },
    EnhanceReligion("Enhance a Religion", Color.valueOf(BeliefType.Enhancer.color)) {
        override fun isChoice(worldScreen: WorldScreen) =
            worldScreen.selectedGameView.civView.isEnhancingReligion()
        override fun action(worldScreen: WorldScreen) =
            openReligionPicker(worldScreen, false) { getBeliefsToChooseAtEnhancing() }
    },
    ReformReligion("Reform Religion", Color.valueOf(BeliefType.Enhancer.color)) {
        override fun isChoice(worldScreen: WorldScreen) =
            worldScreen.selectedGameView.civView.hasFreeBeliefs()
        override fun action(worldScreen: WorldScreen) =
            openReligionPicker(worldScreen, false) { freeBeliefsAsEnums() }
    },
    WorldCongressVote("Vote for World Leader", Color.MAROON) {
        override fun isChoice(worldScreen: WorldScreen) =
            worldScreen.selectedGameView.civView.mayVoteForDiplomaticVictory()
        override fun action(worldScreen: WorldScreen) =
            worldScreen.game.pushScreen(DiplomaticVotePickerScreen(worldScreen.selectedGameView.civView.getCiv()))
    },
    NextUnit("Next unit", Color.LIGHT_GRAY) {
        override fun isChoice(worldScreen: WorldScreen) =
            worldScreen.game.settings.checkForDueUnits && worldScreen.selectedGameView.civView.dueUnitsCount() > 0
        override fun action(worldScreen: WorldScreen) =
            worldScreen.switchToNextUnit(!worldScreen.game.settings.checkForDueUnitsCycles)
        override fun getSubText(worldScreen: WorldScreen): String? =
            getIdleUnitsText(worldScreen)
    },
    MoveAutomatedUnits("Move automated units", Color.LIGHT_GRAY) {
        override fun isChoice(worldScreen: WorldScreen) =
            worldScreen.canMoveAutomatedUnits()
        override fun action(worldScreen: WorldScreen) =
            moveAutomatedUnits(worldScreen)
    },
    NextTurn("Next turn", Color.WHITE) {
        override fun isChoice(worldScreen: WorldScreen) =
            true  // When none of the others is active..
        override fun action(worldScreen: WorldScreen) =
            worldScreen.confirmedNextTurn()
        override fun getSubText(worldScreen: WorldScreen): String? =
            getIdleUnitsText(worldScreen)
    },

    ;
    open val icon: String? get() = if (text != "AutoPlay") "NotificationIcons/$name" else "NotificationIcons/Working"
    open fun getText(worldScreen: WorldScreen) = text
    open fun getSubText(worldScreen: WorldScreen): String? = null
    abstract fun isChoice(worldScreen: WorldScreen): Boolean
    open fun action(worldScreen: WorldScreen) {}

    companion object {
        // Readability helpers to allow concise enum instances
        @Readonly
        private fun getCityWithNoProductionSet(worldScreen: WorldScreen) =
            worldScreen.selectedGameView.civView.getCiv().cities
            .firstOrNull {
                !it.isPuppet && it.cityConstructions. currentConstructionName().isEmpty()
            }

        private fun openReligionPicker(
                worldScreen: WorldScreen,
                pickIconAndName: Boolean,
                getBeliefs: ReligionManager.() -> Counter<BeliefType>
            ) =
            worldScreen.game.pushScreen(
                ReligiousBeliefsPickerScreen(
                    worldScreen.selectedGameView.civView.getCiv(),
                    worldScreen.selectedGameView.civView.getCiv().religionManager.getBeliefs(),
                    pickIconAndName = pickIconAndName
                )
            )

        @Readonly
        private fun WorldScreen.canMoveAutomatedUnits(): Boolean {
            if (selectedGameView.civView.isSpectator()) return false
            if (game.settings.automatedUnitsMoveOnTurnStart) return false
            if (selectedGameView.civView.hasMovedAutomatedUnitsThisTurn()) return false
            return selectedGameView.civView.hasUnitsReadyToAutomate()
        }

        private fun moveAutomatedUnits(worldScreen: WorldScreen) {
            // Don't allow double-click of 'n' to spawn 2 processes trying to automate units
            if (!worldScreen.isPlayersTurn) return

            worldScreen.isPlayersTurn = false // Disable state changes
            worldScreen.selectedGameView.civView.tryMarkMovedAutomatedUnits()
            worldScreen.nextTurnButton.disable()
            Concurrency.run("Move automated units") {
                worldScreen.selectedGameView.civView.tryAutomateAllUnits()
                launchOnGLThread {
                    worldScreen.shouldUpdate = true
                    worldScreen.isPlayersTurn = true //Re-enable state changes
                    worldScreen.nextTurnButton.enable()
                }
            }
        }

        private fun WorldScreen.confirmedNextTurn() {
            fun action() {
                game.settings.addCompletedTutorialTask("Pass a turn")
                nextTurn()
            }
            if (game.settings.confirmNextTurn) {
                ConfirmPopup(this, "Confirm next turn", "Next turn",
                    true, action = ::action).open()
            } else action()
        }

        /**
        Show due units in next-unit and next-turn phase, encouraging the player to give order to
        idle units.
        It also serves to inform new players that the NextUnit-Button cycles units. That's easy
        to grasp, because the number doesn't change when repeatedly clicking the button.
        We also show due units on the NextTurn button, so players see due units in case the
        the NextTurn phase is disabled.
        */
        private fun getIdleUnitsText(worldScreen: WorldScreen): String? {
            val count = worldScreen.selectedGameView.civView.dueUnitsCount()
            if (count > 0) {
                return "[$count] units idle"
            }
            return null
        }
    }
}
