package com.unciv.logic.chain

import com.unciv.logic.GameInfo
import com.unciv.logic.chronicle.ChronicleEntry
import com.unciv.logic.civilization.Civilization
import com.unciv.ui.screens.victoryscreen.RankingType

/**
 * What a finished game is worth keeping.
 *
 * A certificate is two halves that live in different places for a reason:
 *
 *  * the **record** - who won, how, on which turn and in which year, against whom, with the
 *    chronicle of how it went and the score curve behind it. A few kilobytes. This travels as the
 *    asset's metadata, so a wallet or a marketplace can show it without fetching anything, and so
 *    it survives even if every file host on earth goes away;
 *  * the **world** - the final save, hundreds of kilobytes of it. Planned to go to permanent
 *    storage with the metadata pointing at it, so the finished map could be reopened. **Not in
 *    1.0.0**: nothing uploads the save and `saveUri` is always empty, so the description must not
 *    say otherwise (it did until 09-25 - every certificate claimed a world it did not store).
 *
 * The one thing a certificate cannot promise is a turn-by-turn replay of the whole game: a save is
 * a snapshot and there is no action log to replay. What it can do is [ReplayData] - the map's own
 * ownership history, which the game already records, plus the ranking curves - which is enough to
 * play back the *shape* of a civilization's rise.
 */
object VictoryCertificate {

    /** Ranking curves worth carrying; the full set of 11 would triple the metadata for little gain. */
    private val CURVES = listOf(RankingType.Score, RankingType.Technologies, RankingType.Force,
        RankingType.Territory, RankingType.Population)

    /** Sample the curves at most this many times - a 500-turn game does not need 500 points. */
    private const val CURVE_POINTS = 60

    data class Record(
        val gameId: String,
        val winner: String,
        val victoryType: String,
        val victoryTurn: Int,
        /** In-game year of the victory turn, negative for BC. */
        val victoryYear: Int,
        val difficulty: String,
        val gameSpeed: String,
        val rivals: List<String>,
        val eliminated: List<String>,
        val mapSeed: Long,
        val mapType: String,
        val mapSize: String,
        /** Major civilizations in the game, the winner included. */
        val players: Int,
        /** The winner's nation - its emblem goes in the stele's medallion - and its two colours,
         *  as the game draws them: [emblemOuter] fills the disc, [emblemInner] draws the icon. */
        val nation: String,
        val emblemOuter: List<Int>,
        val emblemInner: List<Int>,
        /** Turn -> value, sampled. */
        val curves: Map<String, Map<Int, Int>>,
        val chronicle: List<ChronicleEntry>,
        /** Turns the game's AI played for the player ([GameInfo.autoPlayedTurns]); 0 = none. */
        val autoPlayedTurns: Int = 0,
        /** [StartAnchor.ORIGIN_ANCHORED] or [StartAnchor.ORIGIN_UNANCHORED]. */
        val origin: String = StartAnchor.ORIGIN_UNANCHORED,
        /** The start anchor, base58, so anyone can recompute [mapSeed]; empty when unanchored. */
        val startWallet: String = "",
        val startSignature: String = "",
        /** The shared save's record this game was taken over from ([GameInfo.continuedFromSave]). */
        val sourceSave: String = ""
    ) {
        val anchored get() = origin == StartAnchor.ORIGIN_ANCHORED
    }

    /** @param minter the wallet the certificate goes to, when known: a start anchored by another
     *  wallet does not make this one's certificate anchored. */
    fun record(gameInfo: GameInfo, winner: Civilization, minter: String? = ChainWallet.service.connectedAddress): Record {
        val victory = gameInfo.victoryData
        val mapParams = gameInfo.tileMap.mapParameters
        return Record(
            gameId = gameInfo.gameId,
            winner = winner.civName,
            victoryType = victory?.victoryType ?: "",
            victoryTurn = victory?.victoryTurn ?: gameInfo.turns,
            // the year of the victory turn, not of the turn it is minted on (09-28: a win on turn
            // 223 minted on 225 was carved "Turn 223 / 1910 AD")
            victoryYear = gameInfo.getYear((victory?.victoryTurn ?: gameInfo.turns) - gameInfo.turns),
            difficulty = gameInfo.difficulty,
            gameSpeed = gameInfo.gameParameters.speed,
            rivals = gameInfo.civilizations
                .filter { it.isMajorCiv() && it != winner }
                .map { it.civName },
            // Read off the chronicle rather than the civ list: a civ that was eliminated and one
            // that merely lost look identical in the final state.
            eliminated = gameInfo.chronicle
                .filter { it.kind == "CivDefeated" }
                .mapNotNull { it.target },
            mapSeed = mapParams.seed,
            mapType = mapParams.type,
            mapSize = mapParams.mapSize.name,
            players = gameInfo.civilizations.count { it.isMajorCiv() },
            nation = winner.nation.name,
            emblemOuter = winner.nation.getOuterColor().let { c -> listOf(c.r, c.g, c.b).map { Math.round(it * 255) } },
            emblemInner = winner.nation.getInnerColor().let { c -> listOf(c.r, c.g, c.b).map { Math.round(it * 255) } },
            curves = CURVES.associate { type ->
                type.name to sample(winner.statsHistory.mapValues { it.value[type] ?: 0 })
            },
            chronicle = gameInfo.chronicle.toList(),
            autoPlayedTurns = gameInfo.autoPlayedTurns,
            origin = StartAnchor.origin(gameInfo, minter),
            startWallet = gameInfo.startAnchorWallet,
            startSignature = gameInfo.startAnchorSignature,
            sourceSave = gameInfo.continuedFromSave
        )
    }

    /**
     * Who drew each emblem the certificate can carry, as upstream's docs/Credits.md records it.
     * These are attribution licences (CC BY 3.0, Flaticon's free licence), which allow a paid
     * certificate but require the credit to travel with it - so it goes into the metadata.
     * Nations missing here have no emblem on the stele: China, Inca, Iroquois and Songhai are
     * CC BY-NC-SA (non-commercial), and Denmark, Mongolia and Spain have no recorded source.
     * android/assets/certificate/emblems/ holds exactly the icons listed here.
     */
    val EMBLEM_CREDITS = mapOf(
        "America" to "Shield by Nathan Driskell",
        "Arabia" to "Star and Crescent, the Noun Project",
        "Austria" to "Flag of Austria by Olena Panasovska (modified)",
        "Aztecs" to "Aztec icon by Kāne",
        "Babylon" to "Lamassu by Jason Dilworth",
        "Byzantium" to "Orthodox Cross by Avana Vana",
        "Carthage" to "Elephant by Hea Poh Lin (modified)",
        "Celts" to "Celtic Knot by Ervin Bolat",
        "China" to "Dragon, drawn for Unwrit Ages",
        "Denmark" to "Longship, drawn for Unwrit Ages",
        "Egypt" to "Eye of Horus by Lilit Kalachyan",
        "England" to "Crown by Peter van Driel",
        "Ethiopia" to "Lion by IronSV, royal crown by Vectors Market, Spear by Firza Alamsyah, pennant by Sara Jeffries",
        "France" to "Fleur de Lis by Jessika Gadoury",
        "Germany" to "Iron Cross by Souvik Maity",
        "Greece" to "Omega by icon 54",
        "Inca" to "Sun of Inti, drawn for Unwrit Ages",
        "Iroquois" to "Hiawatha Belt, the Haudenosaunee flag",
        "India" to "Ashoka Chakra by sahua d",
        "Japan" to "Family Crest Komon by sahua d",
        "Korea" to "Korea by CJS",
        "Mongolia" to "Soyombo, drawn for Unwrit Ages",
        "Persia" to "Sword by Those Icons (Flaticon)",
        "Polynesia" to "Swirl by IronSV",
        "Rome" to "Laurel by VectorBakery",
        "Russia" to "Russia by Eugen Belyakoff",
        "Siam" to "Dharmachakra by Parkjisun",
        "Songhai" to "Tomb of Askia, drawn for Unwrit Ages",
        "Spain" to "Castle of Castile, drawn for Unwrit Ages",
        "Sweden" to "Three Crowns by Daniel Falk",
        "The Huns" to "Sun symbol by Eddo",
        "The Maya" to "Maya civilization by Olena Panasovska",
        "The Netherlands" to "Lion by Nikki Rodriguez",
        "The Ottomans" to "crescents by Estu Suhartono (modified)"
    )

    /** One line of the stele's inscription; [style] names an entry in the renderer's style table
     *  (pic/batch_review/_sd/stele.py STYLES, mirrored in the Android CertificateImage). */
    data class InscriptionLine(val style: String, val text: String)

    /** What is carved into the stele, top to bottom - in English whatever the game's language, so
     *  every certificate reads the same in a wallet or a marketplace. Mirrors stele.py lines_for. */
    fun inscription(record: Record): List<InscriptionLine> {
        // One fact per line: the stele's face is tall and narrow. The map seed is in the metadata,
        // not here - it matters to someone replaying the map, not to someone looking at the stone.
        val lines = mutableListOf(
            InscriptionLine("civ", record.winner.uppercase()),
            InscriptionLine("victory", describe(record.victoryType).uppercase()),
            InscriptionLine("when", "Turn ${record.victoryTurn}"),
            InscriptionLine("when", year(record.victoryYear)),
            InscriptionLine("rule", ""),
            InscriptionLine("fact", "${record.difficulty} difficulty"),
            InscriptionLine("fact", "${record.players} players"),
        )
        if (record.rivals.isNotEmpty()) lines += InscriptionLine("fact", "Rivals: " + record.rivals.joinToString(", "))
        if (record.eliminated.isNotEmpty()) lines += InscriptionLine("fact", "Eliminated: " + record.eliminated.joinToString(", "))
        // The game's settings last: who was there and who fell read first.
        lines += InscriptionLine("fact", "${record.gameSpeed} speed")
        lines += InscriptionLine("fact", "${record.mapType} · ${record.mapSize}")
        // Stated, not judged: the certificate is issued either way (user, 09-24).
        if (record.autoPlayedTurns > 0) lines += InscriptionLine("fact", autoPlay(record.autoPlayedTurns))
        // Likewise a game taken over from someone else's shared save (option B, user 09-26)
        if (record.origin.startsWith(StartAnchor.ORIGIN_CONTINUED)) lines += InscriptionLine("fact", record.origin)
        return lines
    }

    /** Every turn if the game was short, otherwise an even spread that always keeps the last turn. */
    private fun sample(series: Map<Int, Int>): Map<Int, Int> {
        if (series.size <= CURVE_POINTS) return series.toSortedMap()
        val turns = series.keys.sorted()
        val step = (turns.size - 1).toDouble() / (CURVE_POINTS - 1)
        val picked = (0 until CURVE_POINTS).map { turns[Math.round(it * step).toInt()] }
        return (picked + turns.last()).distinct().sorted().associateWith { series[it] ?: 0 }
    }

    /**
     * The asset's off-chain metadata, in the shape wallets and marketplaces expect: `name`,
     * `description`, `image`, `attributes`, plus our own `unwritAges` payload for anything that
     * wants to render the game rather than just list it.
     *
     * [imageUri] and [saveUri] are permanent-storage URIs; both are filled in *after* the upload,
     * which is why they are parameters rather than something this function goes and fetches. The
     * upload has to succeed before the mint is attempted, so a failed mint can be retried without
     * paying to store the same save twice.
     */
    fun metadataJson(record: Record, imageUri: String, saveUri: String): String {
        val sb = StringBuilder(4096)
        sb.append('{')
        sb.field("name", NAME)
        sb.append(',')
        sb.field("description", description(record))
        sb.append(',')
        sb.field("image", imageUri)
        // The picture again with its type - some wallets read only this - and at arweave.net as
        // well, the canonical gateway, in case the one [imageUri] names is ever gone.
        sb.append(",\"properties\":{")
        sb.field("category", "image")
        sb.append(",\"files\":[")
        listOf(imageUri, "https://arweave.net/" + imageUri.substringAfterLast('/')).distinct().forEachIndexed { i, uri ->
            if (i > 0) sb.append(',')
            sb.append('{')
            sb.field("uri", uri)
            sb.append(',')
            sb.field("type", "image/jpeg")
            sb.append('}')
        }
        sb.append("]}")
        sb.append(",\"attributes\":[")
        val attributes = listOf(
            "Victory" to describe(record.victoryType),
            "Civilization" to record.winner,
            "Turn" to record.victoryTurn.toString(),
            "Year" to year(record.victoryYear),
            "Difficulty" to record.difficulty,
            "Speed" to record.gameSpeed,
            "Map" to "${record.mapType} ${record.mapSize}",
            "Rivals eliminated" to record.eliminated.size.toString(),
            "Origin" to record.origin
        ) + (if (record.autoPlayedTurns > 0) listOf("AutoPlay" to autoPlay(record.autoPlayedTurns).removePrefix("AutoPlay: ")) else emptyList()
        ) + (EMBLEM_CREDITS[record.nation]?.let { listOf("Emblem" to it) } ?: emptyList())
        attributes.forEachIndexed { i, (trait, value) ->
            if (i > 0) sb.append(',')
            sb.append("{")
            sb.field("trait_type", trait)
            sb.append(',')
            sb.field("value", value)
            sb.append("}")
        }
        sb.append("],\"unwritAges\":{")
        sb.field("gameId", record.gameId)
        sb.append(',')
        sb.field("saveUri", saveUri)
        sb.append(",\"mapSeed\":").append(record.mapSeed)
        // Won from someone else's shared save: which one, so a victory can be traced to where it began
        if (record.sourceSave.isNotEmpty()) {
            sb.append(',')
            sb.field("sourceSave", record.sourceSave)
        }
        // Only for an anchored certificate: SHA-256(startWallet ‖ startSignature) is mapSeed, and
        // the signature's memo names gameId - anyone can check both (ROADMAP "Provenance").
        if (record.anchored) {
            sb.append(',')
            sb.field("startWallet", record.startWallet)
            sb.append(',')
            sb.field("startSignature", record.startSignature)
        }
        sb.append(",\"curves\":{")
        record.curves.entries.forEachIndexed { i, (name, points) ->
            if (i > 0) sb.append(',')
            sb.field(name, null)
            sb.append('{')
            points.entries.forEachIndexed { j, (turn, value) ->
                if (j > 0) sb.append(',')
                sb.append('"').append(turn).append("\":").append(value)
            }
            sb.append('}')
        }
        sb.append("},\"chronicle\":[")
        record.chronicle.forEachIndexed { i, e ->
            if (i > 0) sb.append(',')
            sb.append("{\"t\":").append(e.turn).append(",\"y\":").append(e.year).append(',')
            sb.field("k", e.kind)
            sb.append(',')
            sb.field("a", e.actor)
            if (e.target != null) { sb.append(','); sb.field("b", e.target) }
            if (e.detail != null) { sb.append(','); sb.field("d", e.detail) }
            sb.append('}')
        }
        sb.append("]}}")
        return sb.toString()
    }

    /** [metadataJson], cut down only if it would not fit the free upload tier: the chronicle is
     *  the one part whose length the game controls, so a very long game loses it before the
     *  certificate loses the free upload. Everything a wallet shows is kept either way. */
    fun metadataJsonWithin(record: Record, imageUri: String, maxBytes: Int): String {
        val full = metadataJson(record, imageUri, "")
        if (full.toByteArray(Charsets.UTF_8).size <= maxBytes) return full
        return metadataJson(record.copy(chronicle = emptyList()), imageUri, "")
    }

    /**
     * Every certificate's name: the game first, so a wallet lists them together (user, 09-25).
     * Who won, how and when is in the description and the attributes, not the name.
     */
    const val NAME = "Unwrit Ages Victory"

    private fun description(record: Record): String {
        val lines = StringBuilder()
        lines.append("${record.winner} achieved a ${describe(record.victoryType).lowercase()} ")
            .append("on turn ${record.victoryTurn}, ${year(record.victoryYear)}, ")
            .append("at ${record.difficulty} difficulty.")
        if (record.eliminated.isNotEmpty())
            lines.append(" Eliminated: ${record.eliminated.joinToString(", ")}.")
        return lines.toString()
    }

    private fun describe(victoryType: String) =
        if (victoryType.isEmpty()) "Victory" else "$victoryType Victory"

    private fun autoPlay(turns: Int) = "AutoPlay: $turns turn" + if (turns == 1) "" else "s"

    private fun year(year: Int) = if (year < 0) "${-year} BC" else "$year AD"

    /** `"key":"value"`, or just `"key":` when [value] is null and the caller writes the value. */
    private fun StringBuilder.field(key: String, value: String?) {
        append('"').appendEscaped(key).append("\":")
        if (value != null) append('"').appendEscaped(value).append('"')
    }

    private fun StringBuilder.appendEscaped(s: String): StringBuilder {
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u").append("%04x".format(c.code))
            else -> append(c)
        }
        return this
    }
}
