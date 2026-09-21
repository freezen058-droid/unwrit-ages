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
 *  * the **world** - the final save, hundreds of kilobytes of it. This goes to permanent storage
 *    and the metadata points at it, so the finished map can be reopened, walked, and played on
 *    from "one more turn".
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
        /** Turn -> value, sampled. */
        val curves: Map<String, Map<Int, Int>>,
        val chronicle: List<ChronicleEntry>
    )

    fun record(gameInfo: GameInfo, winner: Civilization): Record {
        val victory = gameInfo.victoryData
        val mapParams = gameInfo.tileMap.mapParameters
        return Record(
            gameId = gameInfo.gameId,
            winner = winner.civName,
            victoryType = victory?.victoryType ?: "",
            victoryTurn = victory?.victoryTurn ?: gameInfo.turns,
            victoryYear = gameInfo.getYear(),
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
            curves = CURVES.associate { type ->
                type.name to sample(winner.statsHistory.mapValues { it.value[type] ?: 0 })
            },
            chronicle = gameInfo.chronicle.toList()
        )
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
     * `description`, `image`, `attributes`, plus our own `civilwars` payload for anything that
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
        sb.field("name", "${record.winner} - ${describe(record.victoryType)}, turn ${record.victoryTurn}")
        sb.append(',')
        sb.field("description", description(record))
        sb.append(',')
        sb.field("image", imageUri)
        sb.append(",\"attributes\":[")
        val attributes = listOf(
            "Victory" to describe(record.victoryType),
            "Civilization" to record.winner,
            "Turn" to record.victoryTurn.toString(),
            "Year" to year(record.victoryYear),
            "Difficulty" to record.difficulty,
            "Speed" to record.gameSpeed,
            "Map" to "${record.mapType} ${record.mapSize}",
            "Rivals eliminated" to record.eliminated.size.toString()
        )
        attributes.forEachIndexed { i, (trait, value) ->
            if (i > 0) sb.append(',')
            sb.append("{")
            sb.field("trait_type", trait)
            sb.append(',')
            sb.field("value", value)
            sb.append("}")
        }
        sb.append("],\"civilwars\":{")
        sb.field("gameId", record.gameId)
        sb.append(',')
        sb.field("saveUri", saveUri)
        sb.append(",\"mapSeed\":").append(record.mapSeed)
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

    /**
     * The same certificate, small enough to live *inside* the asset instead of behind a URL.
     *
     * [metadataJson] is a few kilobytes - the chronicle and five ranking curves - and has to be
     * uploaded somewhere and pointed at. This one is the subset a wallet actually renders, written
     * to fit in the `uri` field of the mint transaction itself as a `data:` URI. Nothing to upload,
     * nothing to keep paying for, and no host whose disappearance empties the certificate.
     *
     * Everything variable in it is bounded, because the budget is a hard one: a Solana transaction
     * is 1232 bytes and this shares them with two signatures, five account keys and two
     * instructions. [withDescription] is the one thing the caller can turn off, because it is the
     * longest field and the only one that is prose rather than fact.
     */
    fun compactMetadataJson(record: Record, imageUri: String, withDescription: Boolean = true): String {
        val sb = StringBuilder(640)
        sb.append('{')
        sb.field("name", "${record.winner} - ${describe(record.victoryType)}, turn ${record.victoryTurn}")
        if (withDescription) {
            sb.append(',')
            sb.field("description", description(record, short = true))
        }
        if (imageUri.isNotEmpty()) {
            sb.append(',')
            sb.field("image", imageUri)
        }
        sb.append(",\"attributes\":[")
        val attributes = listOf(
            "Victory" to describe(record.victoryType),
            "Civilization" to record.winner,
            "Turn" to record.victoryTurn.toString(),
            "Year" to year(record.victoryYear),
            "Difficulty" to record.difficulty,
            "Speed" to record.gameSpeed
        )
        attributes.forEachIndexed { i, (trait, value) ->
            if (i > 0) sb.append(',')
            sb.append('{')
            sb.field("trait_type", trait)
            sb.append(',')
            sb.field("value", value)
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    /** @param short drops the parts whose length the player controls - see [compactMetadataJson]. */
    private fun description(record: Record, short: Boolean = false): String {
        val lines = StringBuilder()
        lines.append("${record.winner} achieved a ${describe(record.victoryType).lowercase()} ")
            .append("on turn ${record.victoryTurn}, ${year(record.victoryYear)}, ")
            .append("at ${record.difficulty} difficulty.")
        if (short) return lines.toString()
        if (record.eliminated.isNotEmpty())
            lines.append(" Eliminated: ${record.eliminated.joinToString(", ")}.")
        lines.append(" The final world is stored permanently and can be reopened from this certificate.")
        return lines.toString()
    }

    private fun describe(victoryType: String) =
        if (victoryType.isEmpty()) "Victory" else "$victoryType Victory"

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
