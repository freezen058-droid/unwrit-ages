package com.unciv.ui.screens.victoryscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.logic.chain.VictoryCertificateService
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.Victory
import com.unciv.ui.components.widgets.TabbedPager
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.equalizeColumns
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.WorldScreen

class VictoryScreenOurVictory(
    worldScreen: WorldScreen
) : Table(BaseScreen.skin), TabbedPager.IPageExtensions {
    private val header = Table()
    private val stageWidth = worldScreen.stage.width

    init {
        align(Align.top)

        val gameInfo = worldScreen.gameInfo
        val victoriesToShow = gameInfo.getEnabledVictories()

        defaults().pad(10f)
        for ((victoryName, victory) in victoriesToShow) {
            header.add("[$victoryName] Victory".toLabel()).pad(10f)
            add(getColumn(victory, worldScreen.selectedGameView.civView.getCiv())).top()
        }

        row()
        for (victory in victoriesToShow.values) {
            val victoryScreenHeaderLabel = victory.victoryScreenHeader.toLabel()
            victoryScreenHeaderLabel.wrap = true
            add(victoryScreenHeaderLabel).width(stageWidth / 5)
        }

        header.addSeparator(Color.GRAY)

        addCertificateRow(worldScreen, victoriesToShow.size)
    }

    /**
     * The certificate offer, at the one moment it means something.
     *
     * Deliberately the last row and nothing more than a button: winning is the emotional peak of a
     * game that took hours, and the worst thing to put there is a sales pitch. It is absent unless
     * this player actually won and a wallet is available, so a player who never touches the chain
     * never sees it.
     */
    private companion object {
        /** Said only when an upload is already paid for, because it changes what a retry costs. */
        const val RETRY_HINT = "\nThe world is stored - retrying only mints."
    }

    private fun addCertificateRow(worldScreen: WorldScreen, columns: Int) {
        val gameInfo = worldScreen.gameInfo
        val civ = worldScreen.selectedGameView.civView.getCiv()
        if (!VictoryCertificateService.isAvailable(gameInfo, civ)) return

        val status = "".toLabel()
        val button = "Mint victory certificate".toTextButton()
        button.onClick {
            button.disable()
            status.setText("Storing the final world...")
            VictoryCertificateService.mint(
                gameInfo, civ,
                imageUri = "",
                onProgress = { status.setText(it) },
                onSuccess = { status.setText("Certificate minted: $it") },
                onError = {
                    // The upload may already be paid for; say so, because it changes what a retry costs.
                    val paid = VictoryCertificateService.alreadyMintedUpload(gameInfo) != null
                    status.setText(
                        (it.localizedMessage ?: "Could not mint the certificate") +
                            (if (paid) RETRY_HINT else "")
                    )
                    button.enable()
                }
            )
        }

        row()
        val cell = Table()
        cell.add(button).padBottom(6f).row()
        cell.add(status).row()
        add(cell).colspan(maxOf(1, columns)).padTop(16f)
    }

    private fun getColumn(victory: Victory, playerCiv: Civilization): Table {
        val table = Table()
        table.defaults().space(10f)
        var firstIncomplete = true
        for (milestone in victory.milestoneObjects) {
            val completionStatus = when {
                milestone.hasBeenCompletedBy(playerCiv) -> Victory.CompletionStatus.Completed
                firstIncomplete -> {
                    firstIncomplete = false
                    Victory.CompletionStatus.Partially
                }
                else -> Victory.CompletionStatus.Incomplete
            }
            for (button in milestone.getVictoryScreenButtons(completionStatus, playerCiv)) {
                table.add(button).row()
            }
        }
        return table
    }

    override fun activated(index: Int, caption: String, pager: TabbedPager) {
        equalizeColumns(header, this)
    }

    override fun getFixedContent() = header
}
