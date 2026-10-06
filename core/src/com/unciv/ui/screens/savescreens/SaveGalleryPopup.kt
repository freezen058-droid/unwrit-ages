package com.unciv.ui.screens.savescreens

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.logic.GameInfo
import com.unciv.logic.chain.ChainWallet
import com.unciv.logic.chain.CloudSave
import com.unciv.logic.chain.SharedSaveIntent
import com.unciv.logic.chain.SharedSaveCache
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.map.HexMath
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.UncivDateFormat.formatDate
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.TranslatedSelectBox
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread
import java.util.Date
import kotlin.math.roundToInt

/**
 * The shared-save gallery (user 09-30: "園區" - shared saves sorted so they do not drift around the
 * chain). Every save here was recorded with a paid 1-SKR record; it lists them by what players tipped
 * their authors, or by date, filtered by civ, map and era, each opening a preview of its map before
 * anyone loads it. No server: the records and the tips are read from the chain
 * ([com.unciv.logic.chain.PlatformWalletService.listSaveRecords], [com.unciv.logic.chain.PlatformWalletService.listSaveTips]).
 */
class SaveGalleryPopup(private val screen: BaseScreen) : Popup(screen) {

    private companion object {
        const val ALL_CIVS = "All civilizations"
        const val ALL_MAPS = "All map types"
        const val ALL_ERAS = "All eras"
        const val MOST_TIPPED = "Most tipped"
        const val NEWEST = "Newest"
    }

    private val status = "Loading...".toLabel().apply { wrap = true }
    private val filters = Table()
    private val navigation = Table()
    private val list = Table()
    private var records: List<CloudSave.Record> = emptyList()
    private val tips = mutableMapOf<String, Long>()
    /** The largest bounty on each save, by its record's signature. */
    private val bounties = mutableMapOf<String, CloudSave.Bounty>()
    private var mine: Set<String> = emptySet()
    /** Authors who hold a Seeker Genesis Token - one per Seeker phone. */
    private var seekers: Set<String> = emptySet()
    private var sort = MOST_TIPPED
    private var civ = ALL_CIVS
    private var mapType = ALL_MAPS
    private var era = ALL_ERAS
    private var continuationOf: CloudSave.Record? = null
    private var closed = false
    private val comparisonSummaries = mutableMapOf<String, String>()
    private val comparisonRequested = mutableSetOf<String>()
    private val comparisonDefinitions = mutableMapOf<String, String>()
    private val comparisonQueue = java.util.ArrayDeque<CloudSave.Record>()
    private var comparisonBusy = false

    init {
        addGoodSizedLabel("Shared saves").row()
        add("Download a shared save online, then play offline. No wallet needed to play."
            .toLabel().apply { wrap = true }).width(screen.stage.width * 0.7f).row()
        add(navigation).width(screen.stage.width * 0.7f).row()
        add(filters).row()
        add(status).width(screen.stage.width * 0.7f).row()
        add(AutoScrollPane(list)).maxHeight(screen.stage.height * 0.5f).width(screen.stage.width * 0.7f).row()
        addCloseButton()
        closeListeners.add { closed = true }
        open(force = true)
        show()
        status.setText("Loading...".tr())
        fetch()
    }

    private fun failed(ex: Exception) {
        if (closed) return
        status.setText("Could not restore the save:".tr() + "\n" + (ex.message ?: ex.javaClass.simpleName))
        pack()
        fitOrCenterContentIntoVisibleArea()
    }

    private fun fetch() {
        val wallet = ChainWallet.service
        wallet.listSaveRecords(true, onError = ::failed, onSuccess = { shared ->
            records = shared
            // A gallery without its tips still lists every save, by date
            val authors = shared.mapNotNull { r -> r.meta?.author?.let { r.signature to it } }.toMap()
            wallet.listSaveTips(authors, onError = { show() }, onSuccess = { tips.putAll(it); show() })
            wallet.seekerOwners(authors.values.toSet(), onError = {}, onSuccess = { seekers = it; show() })
            wallet.listBounties(onError = {}, onSuccess = { list ->
                for (b in list) if ((bounties[b.saveSignature]?.skr ?: 0) < b.skr) bounties[b.saveSignature] = b
                show()
            })
            if (ChainWallet.isConnected)
                wallet.listSaveRecords(false, onError = {}, onSuccess = { own -> mine = own.map { it.signature }.toSet(); show() })
        })
    }

    private fun isMine(record: CloudSave.Record) =
        record.signature in mine || (record.meta?.author != null && record.meta.author == ChainWallet.service.connectedAddress)

    /** The first save ever shared - on the chain's clock, so nobody can move it (user 09-30). */
    private val genesis get() = records.filter { it.blockTime > 0 }.minByOrNull { it.blockTime }?.signature

    private fun show() {
        if (closed) return
        buildFilters()
        navigation.clear()
        navigation.defaults().pad(4f)
        val parent = continuationOf
        if (parent != null) {
            navigation.add("Continuations of [${parent.name}]".toLabel(hideIcons = true).apply { wrap = true })
                .width(screen.stage.width * 0.43f).left()
            navigation.add("All shared saves".toTextButton().apply {
                onClick { continuationOf = null; show() }
            })
        } else {
            navigation.add("How to share a save".toTextButton().apply {
                onClick { SharedWorldHelpPopup(screen) }
            })
        }
        val availableSignatures = records.map { it.signature }.toSet()
        val shown = records
            .filter {
                if (parent != null) it.meta?.parent == parent.signature
                else it.meta?.parent.isNullOrEmpty() || it.meta?.parent !in availableSignatures
            }
            .filter { parent != null || civ == ALL_CIVS || it.meta?.civ == civ }
            .filter { parent != null || mapType == ALL_MAPS || it.meta?.mapType == mapType }
            .filter { parent != null || era == ALL_ERAS || it.meta?.era == era }
            .sortedWith(
                if (sort == MOST_TIPPED) compareByDescending<CloudSave.Record> { tips[it.signature] ?: 0L }.thenByDescending { it.blockTime }
                else compareByDescending { it.blockTime }
            ).sortedByDescending { parent == null && it.signature == genesis }
        status.setText(when {
            parent != null && shown.isEmpty() -> "No continuations yet. Load this save and share your progress.".tr()
            records.isEmpty() -> "No one has shared a save yet.".tr()
            shown.isEmpty() -> "No shared save matches these filters.".tr()
            else -> ""
        })
        list.clear()
        for (record in shown) list.add(row(record)).growX().pad(4f).row()
        if (parent != null) {
            if (comparisonRequested.add(parent.signature)) comparisonQueue.addFirst(parent)
            for (record in shown) if (comparisonRequested.add(record.signature)) comparisonQueue.add(record)
            fetchNextComparison()
        }
        pack()
        fitOrCenterContentIntoVisibleArea()
    }

    private fun buildFilters() {
        filters.clear()
        if (continuationOf != null) return
        filters.defaults().pad(4f)
        fun box(all: String, values: List<String?>, current: String, set: (String) -> Unit) =
            TranslatedSelectBox(listOf(all) + values.filterNotNull().distinct().sorted(), current).apply {
                onChange { set(selected.value); show() }
            }
        filters.add(TranslatedSelectBox(listOf(MOST_TIPPED, NEWEST), sort).apply { onChange { sort = selected.value; show() } })
        filters.add(box(ALL_CIVS, records.map { it.meta?.civ }, civ) { civ = it })
        filters.add(box(ALL_MAPS, records.map { it.meta?.mapType }, mapType) { mapType = it })
        filters.add(box(ALL_ERAS, records.map { it.meta?.era }, era) { era = it })
    }

    /** Load outcome snapshots only while viewing continuations, sequentially and hash-checked. */
    private fun fetchNextComparison() {
        if (closed || comparisonBusy || comparisonQueue.isEmpty()) return
        comparisonBusy = true
        val record = comparisonQueue.removeFirst()
        fun finish(summary: String?, definition: String = "") {
            com.badlogic.gdx.Gdx.app.postRunnable {
                comparisonBusy = false
                if (closed) return@postRunnable
                comparisonDefinitions[record.signature] = definition
                if (summary != null) comparisonSummaries[record.signature] = summary
                show()
                fetchNextComparison()
            }
        }
        downloadSharedSave(record,
            onError = { finish(null) }, onSuccess = { bytes ->
                Concurrency.run("ScenarioComparison") {
                    var definition = ""
                    val summary = try {
                        val game = decodeCloudSave(record, bytes, null)
                        val s = game.sharedScenario?.takeIf { it.supported }
                        definition = s?.definitionHash().orEmpty()
                        val parent = record.meta?.parent
                        if (s == null || parent.isNullOrEmpty() || game.continuedFromSave != parent ||
                            comparisonDefinitions[parent] != definition) null else {
                            val civ = game.civilizations.firstOrNull { it.civID == s.civilization }
                            val settled = s.outcome.isNotEmpty()
                            "Goals [${s.completedCount}]/[${s.goalCount}] · Scenario turn [${(game.turns - s.startTurn).coerceIn(0, s.duration)}]/[${s.duration}]".tr() +
                                "\n" + buildList {
                                    if (!s.optionalGoals || s.technology.isNotBlank()) add("[${s.technology.tr()}]: turn [${if (s.researchTurn < 0) "-" else (s.researchTurn - s.startTurn).toString()}]".tr())
                                    if (!s.optionalGoals || s.building.isNotBlank()) add("[${s.building.tr()}]: turn [${if (s.constructionTurn < 0) "-" else (s.constructionTurn - s.startTurn).toString()}]".tr())
                                    s.militaryGoals?.let {
                                        if (it.captureCityId.isNotBlank()) add("[${it.captureCityName}]: turn [${if (s.captureTurn < 0) "-" else (s.captureTurn - s.startTurn).toString()}]".tr())
                                        if (it.musterCityId.isNotBlank()) add("[${"Assemble troops".tr()}]: turn [${if (s.musterTurn < 0) "-" else (s.musterTurn - s.startTurn).toString()}]".tr())
                                    }
                                }.joinToString(" · ") +
                                "\n" + "Treasury: [${if (settled) s.finalGold else civ?.gold ?: 0}] · Cities: [${if (settled) s.finalCities else civ?.cities?.size ?: 0}]".tr() + " · " +
                                (if (if (settled) s.finalAtWar else civ?.diplomacy?.get(s.opponent)?.diplomaticStatus == DiplomaticStatus.War) "At war" else "At peace").tr()
                        }
                    } catch (_: Exception) { null }
                    finish(summary, definition)
                }
            })
    }

    private fun row(record: CloudSave.Record): Table {
        val button = Table(BaseScreen.skin)
        val isGenesis = record.signature == genesis
        button.background = BaseScreen.skinStrings.getUiBackground("General/Border",
            tintColor = if (isGenesis) Color(0.55f, 0.42f, 0.12f, 1f) else Color(0.2f, 0.25f, 0.35f, 1f))
        button.pad(8f)
        if (isGenesis) button.add("Genesis - the first save ever shared".toLabel(Color.GOLD)).left().colspan(2).row()
        button.add(record.name.toLabel(hideIcons = true)).left().growX()
        val tipped = tips[record.signature] ?: 0
        val bounty = bounties[record.signature]
        button.add(listOfNotNull(
            bounty?.let { "Bounty [${it.skr}] SKR".tr() },
            if (tipped > 0) "[$tipped] SKR tipped".tr() else null
        ).joinToString("  ·  ").toLabel(Color.GOLD)).right().row()
        val seeker = record.meta?.author in seekers
        button.add(((if (seeker) "Seeker".tr() + "  ·  " else "") + describe(record))
            .toLabel(fontSize = Constants.defaultFontSize - 4, hideIcons = true)).left().colspan(2)
        comparisonSummaries[record.signature]?.let {
            button.row()
            button.add(it.toLabel(fontSize = Constants.defaultFontSize - 4).apply { wrap = true })
                .width(screen.stage.width * 0.6f).left().colspan(2).padTop(5f)
        }
        button.touchable = com.badlogic.gdx.scenes.scene2d.Touchable.enabled
        button.onClick { openDetails(record) }
        return button
    }

    private fun openDetails(record: CloudSave.Record) {
        val source = record.meta?.parent?.takeIf { it.isNotEmpty() }?.let { p -> records.firstOrNull { it.signature == p } }
        SaveGalleryDetailPopup(screen, record, isMine(record), record.signature == genesis, source, relays(record),
            bounties[record.signature], record.meta?.author in seekers,
            onSource = { source?.let { openDetails(it) } },
            onContinuations = { continuationOf = record; show() },
            onTipped = { amount ->
                tips[record.signature] = (tips[record.signature] ?: 0) + amount
                show()
            })
    }

    /** How many shared saves were taken over from [record] and shared again. */
    private fun relays(record: CloudSave.Record) = records.count { it.meta?.parent == record.signature }

    private fun describe(record: CloudSave.Record): String {
        val date = if (record.blockTime > 0) Date(record.blockTime * 1000).formatDate() else ""
        val meta = record.meta ?: return date
        val relayed = relays(record)
        return (listOf(meta.civ.tr(), meta.mapType.tr() + " " + meta.mapSize.tr(), meta.era.tr(),
            "Turn [${meta.turn}]".tr(), date) + (if (relayed > 0) listOf(continuationCountText(relayed)) else emptyList()))
            .joinToString("  ·  ")
    }
}

/** One shared save: its map as the sharing player saw it, what it holds, play it or tip its author. */
private class SaveGalleryDetailPopup(
    private val screen: BaseScreen,
    private val record: CloudSave.Record,
    private val mine: Boolean,
    genesis: Boolean,
    private val source: CloudSave.Record?,
    private val relayCount: Int,
    private val bounty: CloudSave.Bounty?,
    private val seekerAuthor: Boolean,
    private val onSource: () -> Unit,
    private val onContinuations: () -> Unit,
    private val onTipped: (Long) -> Unit
) : Popup(screen) {

    private val status = "Downloading...".toLabel().apply { wrap = true; setAlignment(Align.center) }
    private val body = Table()
    private val invitation = Table()
    private var texture: Texture? = null
    private var closed = false

    init {
        addGoodSizedLabel(record.name).row()
        if (genesis) addGoodSizedLabel("Genesis - the first save ever shared", color = Color.GOLD).row()
        add(invitation).row()
        add(body).row()
        add(status).width(screen.stage.width * 0.6f).row()
        addButton("View continuations") {
            if (relayCount > 0) {
                close()
                onContinuations()
            }
        }.actor.apply {
            isDisabled = relayCount == 0
            if (isDisabled) color.a = 0.45f
        }
        if (source != null) addButton("View source save") { close(); onSource() }
        addCloseButton()
        closeListeners.add { closed = true; texture?.dispose(); texture = null }
        open(force = true)
        downloadSharedSave(record, onError = ::failed, onSuccess = { bytes ->
            Concurrency.run("GalleryPreview") {
                val game = try { decodeCloudSave(record, bytes, null) } catch (ex: Exception) {
                    launchOnGLThread { failed(ex) }
                    return@run
                }
                launchOnGLThread { show(game) }
            }
        })
    }

    private fun failed(ex: Exception) {
        if (closed) return
        status.setText("Could not restore the save:".tr() + "\n" + (ex.message ?: ex.javaClass.simpleName))
    }

    private fun show(game: GameInfo) {
        if (closed) return
        status.setText("")
        val civ = game.getCurrentPlayerCivilization()
        game.sharedScenario?.takeIf { it.supported }?.let { scenario ->
            if (scenario.totalChapters > 1) invitation.add(
                "Chapter [${scenario.chapterNumber}] / [${scenario.totalChapters}]".toLabel(Color.GOLD))
                .width(screen.stage.width * 0.7f).row()
            invitation.add("[${scenario.title.tr()}] · Goals [${scenario.authoredChapter?.completedCount ?: scenario.navalCampaign?.active?.completedCount ?: scenario.chapter?.completedCount ?: scenario.completedCount}]/[${scenario.currentGoalCount}]".tr()
                .toLabel(Color.GOLD).apply { wrap = true }).width(screen.stage.width * 0.7f).row()
        }
        SharedSaveIntent.label(game.sharedSaveIntent)?.let {
            invitation.add("Author's goal: [${it.tr()}]".tr().toLabel(Color.GOLD).apply { wrap = true })
                .width(screen.stage.width * 0.7f).row()
        }
        com.unciv.logic.chain.SharedSaveDifficulty.stars(game.sharedSaveDifficulty)?.let {
            invitation.add("Author's takeover difficulty: [$it]".tr().toLabel(Color.GOLD)).row()
        }
        if (game.victoryData != null)
            invitation.add("This game's victory has already been decided.".toLabel(Color.GOLD)).row()
        texture = mapPreview(game, 480, 300)
        body.defaults().pad(6f)
        // Two columns (user 10-01): the map left; facts, Play and the tips right. Stacked under the
        // map, the buttons fell below the popup's fold on a phone held sideways.
        val previewWidth = screen.stage.height * 0.5f * 1.6f
        val preview = Table().apply { defaults().left() }
        // Include Time even when the ruleset hides it from the victory-progress screen.
        val victories = game.gameParameters.victoryTypes.mapNotNull { game.ruleset.victories[it] }
        val victoryNames = victories.map { it.name.tr() }.joinToString(", ")
        val rules = mutableListOf(
            if (victories.isEmpty()) "No victory condition enabled.".tr()
            else "Victory: [$victoryNames]".tr(),
            if (victories.any { it.enablesMaxTurns() })
                "Turns remaining: [${(game.gameParameters.maxTurns - game.turns).coerceAtLeast(0)}]".tr()
            else "No turn limit".tr()
        )
        val parameters = game.gameParameters
        val specialRules = buildList {
            if (parameters.oneCityChallenge) add("One City Challenge".tr())
            if (parameters.noCityRazing) add("No City Razing".tr())
            if (parameters.noBarbarians) add("No Barbarians".tr())
            else if (parameters.ragingBarbarians) add("Raging Barbarians".tr())
            if (!parameters.nuclearWeaponsEnabled) add("Nuclear weapons disabled".tr())
            if (parameters.espionageEnabled) add("Espionage enabled".tr())
        }
        for (rule in rules) preview.add(rule.toLabel(fontSize = Constants.defaultFontSize - 2).apply { wrap = true })
            .width(previewWidth).padTop(4f).row()
        preview.add(Image(texture)).size(previewWidth, screen.stage.height * 0.5f).padTop(6f).row()
        if (specialRules.isNotEmpty()) preview.add(specialRules.joinToString(" · ")
            .toLabel(fontSize = Constants.defaultFontSize - 2).apply { wrap = true })
            .width(previewWidth).padTop(4f).row()
        body.add(preview).top()
        val side = Table().apply { defaults().left().pad(2f) }
        val map = game.tileMap.mapParameters
        val small = Constants.defaultFontSize - 2
        for (line in listOf(
            civ.civName.tr() + "  ·  " + civ.getEra().name.tr(),
            "Turn [${game.turns}]".tr() + "  ·  " + "Cities: [${civ.cities.size}]".tr(),
            map.type.tr() + " " + map.mapSize.name.tr(),
            game.difficulty.tr() + "  ·  " + game.gameParameters.speed.tr(),
            record.meta?.author?.let { "Shared by [${it.take(4)}…${it.takeLast(4)}]".tr() +
                (if (seekerAuthor) "  ·  " + "Seeker owner".tr() else "") } ?: "",
            source?.let { "Continued from [${it.name}]".tr() }
                ?: record.meta?.parent?.takeIf { it.isNotEmpty() }?.let { "The source save is not in the current listing.".tr() } ?: "",
            if (relayCount > 0) continuationCountText(relayCount) else ""
        )) if (line.isNotEmpty()) side.add(line.toLabel(fontSize = small, hideIcons = true).apply { wrap = true })
            .width(screen.stage.width * 0.34f).row()

        com.unciv.logic.chain.SharedSaveProgress.from(game, record.meta?.parent, source?.meta?.turn)?.let {
            side.add("This branch: turn [${it.start}] to [${it.end}] (+[${it.turns}])".tr()
                .toLabel(fontSize = small, hideIcons = true).apply { wrap = true })
                .width(screen.stage.width * 0.34f).row()
        }

        val income = civ.stats.statsForNextTurn.gold.roundToInt()
        game.sharedScenario?.takeIf { it.supported }?.let { scenario ->
            side.add("Scenario goals".toTextButton().apply {
                onClick { com.unciv.ui.popups.SharedScenarioPopup(screen, game) }
            }).padTop(5f).row()
            if (scenario.currentOutcome.isNotEmpty()) side.add(
                ("Scenario result: [${scenario.currentOutcome.tr()}]".tr() + "\n" +
                    (if (scenario.chapterNumber > 1) "Chapter I results".tr() + "\n" else "") +
                    "Treasury: [${scenario.finalGold}] · Cities: [${scenario.finalCities}]".tr() + " · " +
                    (if (scenario.finalAtWar) "At war" else "At peace").tr())
                    .toLabel(fontSize = small).apply { wrap = true })
                .width(screen.stage.width * 0.34f).padTop(4f).row()
        }
        val rate = (if (income >= 0) "+" else "") + income
        val research = civ.tech.techsToResearch.firstOrNull()?.tr()
        // Only established diplomatic relationships; never inspect unseen units or rivals' stats.
        val enemies = civ.diplomacy.values.filter {
            it.diplomaticStatus == DiplomaticStatus.War && !it.otherCiv.isDefeated()
        }.map { it.otherCiv.civName.tr() }.sorted()
        for (line in listOf(
            "Treasury: [${civ.gold}] · [$rate] per turn".tr(),
            research?.let { "Research: [$it]".tr() } ?: "No research selected".tr(),
            if (enemies.isEmpty()) "Not at war with other civilizations".tr()
            else "At war with: [${enemies.joinToString(", ")}]".tr()
        )) side.add(line.toLabel(fontSize = small, hideIcons = true).apply { wrap = true })
            .width(screen.stage.width * 0.34f).padTop(3f).row()

        lateinit var play: com.badlogic.gdx.scenes.scene2d.ui.TextButton
        play = addButton("Play from here") {
            val application = com.unciv.UncivGame.Current
            val previousGame = application.gameInfo
            play.isDisabled = true
            status.setText("Checking...".tr())
            Concurrency.run("GalleryPlay") {
                playRestored(game, record, takenOver = !mine, onLoading = { close() }, onError = { ex ->
                    // loadGame assigns gameInfo before validating. The menu autosaves it,
                    // so restore the previous game before recovering from a failed load.
                    application.gameInfo = previousGame
                    val errorScreen = application.goToMainMenu()
                    Popup(errorScreen).apply {
                        addGoodSizedLabel("Could not restore the save:".tr() + "\n" + (ex.message ?: ex.javaClass.simpleName)).row()
                        addCloseButton()
                        open(force = true)
                    }
                })
            }
        }.actor
        val author = record.meta?.author
        if (!mine && author != null && ChainWallet.service.isAvailable) {
            side.add("Tip the author (SKR)".toLabel(fontSize = small)).padTop(10f).row()
            val chips = Table().apply { defaults().pad(3f).uniformX().fillX() }
            chips.add("1".toTextButton().apply { onClick { tip(author, 1L) } })
            chips.add("10".toTextButton().apply { onClick { tip(author, 10L) } })
            // The commemorative tip (user 09-30): 17 for Unciv, whose repository opened on 2017-11-21
            chips.add("17".toTextButton().apply { label.color = Color.GOLD; onClick { tip(author, 17L) } })
            chips.add("Other...".toTextButton().apply { onClick { TipAmountPopup(screen) { tip(author, it) } } })
            side.add(chips).row()
            side.add("17: Unciv was born in 2017".toLabel(Color.GOLD, fontSize = small - 2)).row()
        }
        if (bounty != null)
            side.add(("Bounty: [${bounty.skr}] SKR to the first victory won from here within [${bounty.turns}] turns, without AutoPlay. " +
                "The author pays it by hand, on seeing the certificate.").tr().toLabel(Color.GOLD, fontSize = small - 2).apply { wrap = true })
                .width(screen.stage.width * 0.3f).padTop(8f).row()
        body.add(side).top().growX()
        pack()
        fitOrCenterContentIntoVisibleArea()
    }

    private fun tip(author: String, amount: Long) {
        fun send() {
            status.setText("Waiting for your wallet...".tr())
            ChainWallet.service.tipSaveAuthor(author, record.signature, amount, onError = ::failed, onSuccess = {
                status.setText("Thank you - [$amount] SKR went to the author.".tr())
                onTipped(amount)
            })
        }
        if (ChainWallet.isConnected) send()
        else ChainWallet.service.connect(onConnected = { send() }, onError = ::failed)
    }
}

/** Cache only verified public uploads. Every open still decodes a fresh, independent game. */
private fun downloadSharedSave(record: CloudSave.Record, onSuccess: (ByteArray) -> Unit,
                               onError: (Exception) -> Unit) {
    val cache = SharedSaveCache(com.unciv.UncivGame.Current.files.getLocalFile("Cache/SharedSaves").file())
    Concurrency.run("SharedSaveCache") {
        val cached = cache.read(record.hashHex)
        if (cached != null) {
            launchOnGLThread { onSuccess(cached) }
        } else launchOnGLThread {
            ChainWallet.service.downloadCloudSave(record.arweaveIds, onError = onError, onSuccess = { bytes ->
                Concurrency.run("VerifySharedSaveCache") {
                    try {
                        decodeCloudSave(record, bytes, null)
                        cache.put(record.hashHex, bytes)
                        launchOnGLThread { onSuccess(bytes) }
                    } catch (ex: Exception) { launchOnGLThread { onError(ex) } }
                }
            })
        }
    }
}

/** Reading this guide never connects a wallet or requests a transaction. */
private class SharedWorldHelpPopup(screen: BaseScreen) : Popup(screen) {
    init {
        addGoodSizedLabel("Share and continue saves").row()
        val textWidth = screen.stage.width * 0.65f
        val steps = listOf(
            "Connect your wallet" to "Wallet > Connect Wallet. Enable on-chain saves.",
            "Prepare your save" to "Save game: choose a name. Add goals to create a challenge.",
            "Share your story" to "Select Share this save, then save and approve in your wallet."
        )
        for ((index, step) in steps.withIndex()) {
            val row = Table()
            row.add("${index + 1}".toLabel(Color.GOLD, fontSize = Constants.headingFontSize)).top().padRight(18f)
            val text = Table()
            text.add(step.first.toLabel(Color.GOLD)).left().row()
            text.add(step.second.toLabel().apply { wrap = true }).width(textWidth - 60f).left()
            row.add(text).left()
            add(row).width(textWidth).left().pad(8f).row()
        }
        add("1 SKR + SOL network fee. Shared saves are public; continuations keep their source link."
            .toLabel(fontSize = Constants.defaultFontSize - 2).apply { wrap = true })
            .width(textWidth).padTop(12f).row()
        addCloseButton()
        open(force = true)
    }
}

/**
 * The save's map as its player knew it: explored tiles in their terrain's colour, owned land in its
 * owner's, cities as light dots - drawn from the save itself, so every shared save has a preview
 * without anyone uploading a picture.
 */
internal fun mapPreview(game: GameInfo, width: Int, height: Int): Texture {
    val pixmap = mapPreviewPixmap(game, width, height)
    return Texture(pixmap).also { it.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear); pixmap.dispose() }
}

/** [mapPreview]'s picture, before it becomes a texture - drawn without a GL context. */
fun mapPreviewPixmap(game: GameInfo, width: Int, height: Int): Pixmap {
    val viewer = game.getCurrentPlayerCivilization()
    val tiles = game.tileMap.values.filter { viewer.hasExplored(it) }
    val pixmap = Pixmap(width, height, Pixmap.Format.RGBA8888)
    pixmap.setColor(Color(0.06f, 0.05f, 0.05f, 1f))
    pixmap.fill()
    if (tiles.isNotEmpty()) {
        val points = tiles.map { HexMath.hex2WorldCoords(it.position) }
        val minX = points.minOf { it.x } - 1; val maxX = points.maxOf { it.x } + 1
        val minY = points.minOf { it.y } - 1; val maxY = points.maxOf { it.y } + 1
        val scale = minOf(width / (maxX - minX), height / (maxY - minY))
        val offX = (width - (maxX - minX) * scale) / 2; val offY = (height - (maxY - minY) * scale) / 2
        // circles just over half the distance between neighbours touch and cover the gaps between hexes
        val step = HexMath.hex2WorldCoords(com.unciv.logic.map.HexCoord(1, 0)).dst(HexMath.hex2WorldCoords(com.unciv.logic.map.HexCoord(0, 0)))
        val radius = maxOf(1, (step * scale * 0.6f).toInt())
        for ((tile, p) in tiles.zip(points)) {
            val x = (offX + (p.x - minX) * scale).toInt()
            val y = (height - offY - (p.y - minY) * scale).toInt()      // world y points up, pixmap y down
            val terrain = tile.getBaseTerrain().getColor()
            val owner = tile.getOwner()
            // owned land in its owner's colour with a hint of the terrain, as the in-game minimap shows it
            pixmap.setColor(if (owner == null) terrain else terrain.cpy().lerp(owner.nation.getOuterColor(), 0.8f))
            pixmap.fillCircle(x, y, radius)
            if (tile.isCityCenter()) {
                pixmap.setColor(owner?.nation?.getInnerColor() ?: Color.WHITE)
                pixmap.fillCircle(x, y, maxOf(2, radius * 3 / 4))
            }
        }
    }
    return pixmap
}

/** Any tip from 1 to [CloudSave.MAX_TIP_SKR] SKR, set with buttons - no keyboard, which on a phone
 *  covered the field it was typing into. */
private class TipAmountPopup(screen: BaseScreen, private val onChosen: (Long) -> Unit) : Popup(screen) {
    private var amount = 5L
    private val shown = "".toLabel(fontSize = Constants.headingFontSize)

    init {
        addGoodSizedLabel("Tip the author (SKR)").row()
        add(shown).pad(10f).center().row()
        val steps = Table().apply { defaults().pad(4f).minWidth(70f) }
        for (step in listOf(-10L, -1L, 1L, 10L))
            steps.add((if (step > 0) "+$step" else "$step").toTextButton().apply { onClick { set(amount + step) } })
        add(steps).row()
        set(amount)
        addCloseButton()
        addOKButton("Tip") { onChosen(amount) }
        equalizeLastTwoButtonWidths()
        open(force = true)
    }

    private fun set(value: Long) {
        amount = value.coerceIn(1L, CloudSave.MAX_TIP_SKR)
        shown.setText("[$amount] SKR".tr())
    }
}


private fun continuationCountText(count: Int) =
    if (count == 1) "1 continuation".tr() else "[$count] continuations".tr()
