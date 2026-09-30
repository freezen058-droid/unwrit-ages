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
    private val list = Table()
    private var records: List<CloudSave.Record> = emptyList()
    private val tips = mutableMapOf<String, Long>()
    /** The largest bounty on each save, by its record's signature. */
    private val bounties = mutableMapOf<String, CloudSave.Bounty>()
    private var mine: Set<String> = emptySet()
    private var sort = MOST_TIPPED
    private var civ = ALL_CIVS
    private var mapType = ALL_MAPS
    private var era = ALL_ERAS

    init {
        addGoodSizedLabel("Shared saves").row()
        add(filters).row()
        add(status).width(screen.stage.width * 0.7f).row()
        add(AutoScrollPane(list)).maxHeight(screen.stage.height * 0.5f).width(screen.stage.width * 0.7f).row()
        addCloseButton()
        open(force = true)
        fetch()
    }

    private fun failed(ex: Exception) {
        status.setText("Could not restore the save:".tr() + "\n" + (ex.message ?: ex.javaClass.simpleName))
    }

    private fun fetch() {
        val wallet = ChainWallet.service
        wallet.listSaveRecords(true, onError = ::failed, onSuccess = { shared ->
            records = shared
            // A gallery without its tips still lists every save, by date
            val authors = shared.mapNotNull { r -> r.meta?.author?.let { r.signature to it } }.toMap()
            wallet.listSaveTips(authors, onError = { show() }, onSuccess = { tips.putAll(it); show() })
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
        buildFilters()
        val shown = records
            .filter { civ == ALL_CIVS || it.meta?.civ == civ }
            .filter { mapType == ALL_MAPS || it.meta?.mapType == mapType }
            .filter { era == ALL_ERAS || it.meta?.era == era }
            .sortedWith(
                if (sort == MOST_TIPPED) compareByDescending<CloudSave.Record> { tips[it.signature] ?: 0 }.thenByDescending { it.blockTime }
                else compareByDescending { it.blockTime }
            ).sortedByDescending { it.signature == genesis }     // stable: Genesis first, the rest as sorted
        status.setText(when {
            records.isEmpty() -> "No one has shared a save yet.".tr()
            shown.isEmpty() -> "No shared save matches these filters.".tr()
            else -> ""
        })
        list.clear()
        for (record in shown) list.add(row(record)).growX().pad(4f).row()
    }

    private fun buildFilters() {
        filters.clear()
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
        button.add(describe(record).toLabel(fontSize = Constants.defaultFontSize - 4, hideIcons = true)).left().colspan(2)
        button.touchable = com.badlogic.gdx.scenes.scene2d.Touchable.enabled
        val parentName = record.meta?.parent?.takeIf { it.isNotEmpty() }?.let { p -> records.firstOrNull { it.signature == p }?.name }
        button.onClick { SaveGalleryDetailPopup(screen, record, isMine(record), isGenesis, parentName, relays(record), bounty) { amount ->
            tips[record.signature] = (tips[record.signature] ?: 0) + amount
            show()
        } }
        return button
    }

    /** How many shared saves were taken over from [record] and shared again. */
    private fun relays(record: CloudSave.Record) = records.count { it.meta?.parent == record.signature }

    private fun describe(record: CloudSave.Record): String {
        val date = if (record.blockTime > 0) Date(record.blockTime * 1000).formatDate() else ""
        val meta = record.meta ?: return date
        val relayed = relays(record)
        return (listOf(meta.civ.tr(), meta.mapType.tr() + " " + meta.mapSize.tr(), meta.era.tr(),
            "Turn [${meta.turn}]".tr(), date) + (if (relayed > 0) listOf("Relayed [$relayed] times".tr()) else emptyList()))
            .joinToString("  ·  ")
    }
}

/** One shared save: its map as the sharing player saw it, what it holds, play it or tip its author. */
private class SaveGalleryDetailPopup(
    private val screen: BaseScreen,
    private val record: CloudSave.Record,
    private val mine: Boolean,
    genesis: Boolean,
    private val parentName: String?,
    private val relayCount: Int,
    private val bounty: CloudSave.Bounty?,
    private val onTipped: (Long) -> Unit
) : Popup(screen) {

    private val status = "Downloading...".toLabel().apply { wrap = true; setAlignment(Align.center) }
    private val body = Table()
    private var texture: Texture? = null

    init {
        addGoodSizedLabel(record.name).row()
        if (genesis) addGoodSizedLabel("Genesis - the first save ever shared", color = Color.GOLD).row()
        add(body).row()
        add(status).width(screen.stage.width * 0.6f).row()
        addCloseButton { texture?.dispose() }
        open(force = true)
        ChainWallet.service.downloadCloudSave(record.arweaveIds, onError = ::failed, onSuccess = { bytes ->
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
        status.setText("Could not restore the save:".tr() + "\n" + (ex.message ?: ex.javaClass.simpleName))
    }

    private fun show(game: GameInfo) {
        status.setText("")
        val civ = game.getCurrentPlayerCivilization()
        texture = mapPreview(game, 480, 300)
        body.defaults().pad(6f)
        // Two columns (user 10-01): the map left; facts, Play and the tips right. Stacked under the
        // map, the buttons fell below the popup's fold on a phone held sideways.
        body.add(Image(texture)).size(screen.stage.height * 0.5f * 1.6f, screen.stage.height * 0.5f).top()
        val side = Table().apply { defaults().left().pad(2f) }
        val map = game.tileMap.mapParameters
        val small = Constants.defaultFontSize - 2
        for (line in listOf(
            civ.civName.tr() + "  ·  " + civ.getEra().name.tr(),
            "Turn [${game.turns}]".tr() + "  ·  " + "Cities: [${civ.cities.size}]".tr(),
            map.type.tr() + " " + map.mapSize.name.tr() + "  ·  " + game.difficulty.tr(),
            record.meta?.author?.let { "Shared by [${it.take(4)}…${it.takeLast(4)}]".tr() } ?: "",
            parentName?.let { "Continued from [$it]".tr() } ?: "",
            if (relayCount > 0) "Relayed [$relayCount] times".tr() else ""
        )) if (line.isNotEmpty()) side.add(line.toLabel(fontSize = small, hideIcons = true)).row()

        val play = "Play from here".toTextButton()
        play.onClick {
            status.setText("Checking...".tr())
            Concurrency.run("GalleryPlay") {
                playRestored(game, record, takenOver = !mine, onLoading = { texture?.dispose(); close() }, onError = ::failed)
            }
        }
        side.add(play).growX().padTop(10f).row()

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
        // The author offers a bounty on their own save (manual, option A)
        if (mine && ChainWallet.isConnected)
            side.add("Offer a bounty".toTextButton().apply { onClick { BountyPopup(screen, record) { status.setText(it) } } }).padTop(8f).row()
        body.add(side).top().growX()
        pack()
        setPosition((stage.width - width) / 2, (stage.height - height) / 2)
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

/** The author's bounty on their shared save: how much, within how many turns - then posted with the
 *  1 SKR record fee. The words say plainly it is a promise the author keeps by hand. */
private class BountyPopup(screen: BaseScreen, private val record: CloudSave.Record, private val onResult: (String) -> Unit) : Popup(screen) {
    private var skr = 20L
    private var turns = 50
    private val skrLabel = "".toLabel(fontSize = Constants.headingFontSize)
    private val turnsLabel = "".toLabel(fontSize = Constants.headingFontSize)

    init {
        addGoodSizedLabel("Offer a bounty").row()
        addGoodSizedLabel("You pay it yourself, by hand, to the first player whose certificate shows a victory won from this save within the turns you set, without AutoPlay.",
            size = Constants.defaultFontSize - 4).row()
        fun stepper(label: com.badlogic.gdx.scenes.scene2d.ui.Label, steps: List<Int>, change: (Int) -> Unit) = Table().apply {
            defaults().pad(3f).minWidth(64f)
            for (step in steps.filter { it < 0 }) add((step.toString()).toTextButton().apply { onClick { change(step) } })
            add(label).minWidth(160f)
            for (step in steps.filter { it > 0 }) add(("+$step").toTextButton().apply { onClick { change(step) } })
        }
        add(stepper(skrLabel, listOf(-10, -1, 1, 10)) { skr = (skr + it).coerceIn(1L, CloudSave.MAX_TIP_SKR); refresh() }).row()
        add(stepper(turnsLabel, listOf(-10, 10)) { turns = (turns + it).coerceIn(10, 300); refresh() }).row()
        refresh()
        addCloseButton()
        addOKButton("Post the bounty (1 SKR)") {
            val poster = ChainWallet.service.connectedAddress ?: return@addOKButton
            onResult("Waiting for your wallet...".tr())
            ChainWallet.service.postBounty(CloudSave.Bounty(record.signature, skr, turns, poster),
                onError = { onResult(it.message ?: it.javaClass.simpleName) },
                onSuccess = { onResult("Bounty posted - it shows in the gallery once the network has it.".tr()) })
        }
        equalizeLastTwoButtonWidths()
        open(force = true)
    }

    private fun refresh() {
        skrLabel.setText("[$skr] SKR".tr())
        turnsLabel.setText("Within [$turns] turns".tr())
    }
}
