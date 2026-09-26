package com.unciv.ui.screens.savescreens

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.chain.ChainWallet
import com.unciv.logic.chain.CloudSave
import com.unciv.logic.files.UncivFiles
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.UncivDateFormat.formatDate
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread
import java.util.Date

/**
 * Saves recorded on the chain, to bring back into this device (CloudSave):
 *  * [shared] false - the connected wallet's own records, private or shared; a private one is
 *    decrypted with the wallet's key;
 *  * [shared] true - every player's shared saves. Loading one of another player's marks the game
 *    "Continued from turn N", which its certificate states (ROADMAP "Provenance", option B).
 *
 * Whatever comes back is checked against the SHA-256 in its record before it is saved and loaded:
 * a damaged or substituted upload is refused.
 */
class ChainSavesPopup(private val screen: BaseScreen, private val shared: Boolean) : Popup(screen) {

    private val status = "Loading...".toLabel().apply { wrap = true }
    private val list = Table()

    init {
        addGoodSizedLabel(if (shared) "Shared saves" else "Restore from the chain").row()
        add(status).width(screen.stage.width * 0.6f).row()
        add(AutoScrollPane(list)).maxHeight(screen.stage.height * 0.55f).width(screen.stage.width * 0.6f).row()
        addCloseButton()
        open(force = true)

        if (!shared && !ChainWallet.isConnected)
            ChainWallet.service.connect(onConnected = { fetch() }, onError = ::failed)
        else fetch()
    }

    private fun failed(ex: Exception) {
        status.setText("Could not restore the save:".tr() + "\n" + (ex.message ?: ex.javaClass.simpleName))
    }

    private fun fetch() {
        ChainWallet.service.listSaveRecords(shared, onError = ::failed, onSuccess = { records ->
            if (!shared || !ChainWallet.isConnected) return@listSaveRecords show(records, emptySet())
            // Your own shared saves are yours: loading them is not taking over someone else's game
            ChainWallet.service.listSaveRecords(false, onError = { show(records, emptySet()) }, onSuccess = { own ->
                show(records, own.map { it.signature }.toSet())
            })
        })
    }

    private fun show(records: List<CloudSave.Record>, mine: Set<String>) {
        val restorable = records.filter { it.restorable }
        status.setText(when {
            restorable.isNotEmpty() -> ""
            shared -> "No one has shared a save yet.".tr()
            records.isNotEmpty() -> "Your records are from before cloud saves: they hold a fingerprint only, so there is nothing to restore.".tr()
            else -> "This wallet has not recorded any saves.".tr()
        })
        list.clear()
        for (record in restorable) {
            val date = if (record.blockTime > 0) Date(record.blockTime * 1000).formatDate() else ""
            val tag = if (!shared && record.shared) " · " + "shared".tr() else ""
            val button = "${record.name}  $date$tag".toTextButton(hideIcons = true)
            button.label.setAlignment(com.badlogic.gdx.utils.Align.left)
            button.onClick { restore(record, takenOver = shared && record.signature !in mine) }
            list.add(button).growX().pad(4f).row()
        }
    }

    private fun restore(record: CloudSave.Record, takenOver: Boolean) {
        list.clear()
        status.setText("Downloading...".tr())
        ChainWallet.service.downloadCloudSave(record.arweaveIds, onError = ::failed, onSuccess = { bytes ->
            if (record.shared) finish(record, bytes, null, takenOver)
            else ChainWallet.service.cloudSaveKey(onError = ::failed, onSuccess = { key ->
                if (record.keyFingerprint.isNotEmpty() && record.keyFingerprint != CloudSave.keyFingerprint(key))
                    return@cloudSaveKey status.setText("This save was recorded with a different wallet, or your wallet now signs differently. It cannot be opened with this one.".tr())
                finish(record, bytes, key, takenOver)
            })
        })
    }

    private fun finish(record: CloudSave.Record, bytes: ByteArray, key: ByteArray?, takenOver: Boolean) {
        status.setText("Checking...".tr())
        Concurrency.run("RestoreCloudSave") {
            val game: GameInfo = try {
                val json = CloudSave.unpack(bytes, key)
                if (ChainWallet.sha256Hex(json) != record.hashHex)
                    throw IllegalStateException("The downloaded save does not match its record on the chain, so it was not loaded.".tr())
                UncivFiles.gameInfoFromString(json)
            } catch (ex: Exception) {
                launchOnGLThread { failed(ex) }
                return@run
            }
            if (takenOver) game.continuedFromTurn = game.turns
            val files = UncivGame.Current.files
            var name = record.name.ifBlank { "Restored" }
            if (files.getSave(name).exists()) name += " (" + "restored".tr() + ")"
            files.saveGame(game, name)
            launchOnGLThread { close() }
            try {
                UncivGame.Current.loadGame(game, callFromLoadScreen = true)
            } catch (ex: Exception) {
                // Saved to the device either way - it is in the load list now
                launchOnGLThread { open(force = true); failed(ex) }
            }
        }
    }
}
