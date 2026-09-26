package com.unciv.logic

import com.unciv.logic.chain.StartAnchor
import com.unciv.logic.chain.VictoryCertificate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert
import org.junit.Test

class CertificateMetadataTests {
    private val record = VictoryCertificate.Record(
        gameId = "g", winner = "The Ottomans", victoryType = "Scientific", victoryTurn = 150,
        victoryYear = 1600, difficulty = "Chieftain", gameSpeed = "Quick", rivals = listOf("China"),
        eliminated = emptyList(), mapSeed = 1L, mapType = "Pangaea", mapSize = "Tiny", players = 2,
        nation = "The Ottomans", emblemOuter = listOf(1, 2, 3), emblemInner = listOf(4, 5, 6),
        curves = emptyMap(), chronicle = emptyList()
    )

    @Test
    fun metadataIsJsonAndListsThePictureOnBothGateways() {
        val image = "https://turbo-gateway.com/_TzpZ6Hp9WuE7BvSpz-9UXHevLVO99yODNqGyrEh0LA"
        val json = Json.parseToJsonElement(VictoryCertificate.metadataJson(record, image, "")).jsonObject
        Assert.assertEquals(image, json["image"]!!.jsonPrimitive.content)
        val properties = json["properties"]!!.jsonObject
        Assert.assertEquals("image", properties["category"]!!.jsonPrimitive.content)
        val uris = properties["files"]!!.jsonArray.map { it.jsonObject["uri"]!!.jsonPrimitive.content }
        Assert.assertEquals(listOf(image, "https://arweave.net/_TzpZ6Hp9WuE7BvSpz-9UXHevLVO99yODNqGyrEh0LA"), uris)
        Assert.assertTrue(json["attributes"]!!.jsonArray.isNotEmpty())
    }

    private fun traits(r: VictoryCertificate.Record) =
        Json.parseToJsonElement(VictoryCertificate.metadataJson(r, "https://x/y", "")).jsonObject["attributes"]!!
            .jsonArray.associate { it.jsonObject["trait_type"]!!.jsonPrimitive.content to it.jsonObject["value"]!!.jsonPrimitive.content }

    @Test
    fun autoPlayIsStatedOnlyWhenItRan() {
        Assert.assertNull(traits(record)["AutoPlay"])
        Assert.assertTrue(VictoryCertificate.inscription(record).none { it.text.startsWith("AutoPlay") })

        val autoPlayed = record.copy(autoPlayedTurns = 37)
        Assert.assertEquals("37 turns", traits(autoPlayed)["AutoPlay"])
        Assert.assertEquals("AutoPlay: 37 turns", VictoryCertificate.inscription(autoPlayed).last().text)
        Assert.assertEquals("AutoPlay: 1 turn", VictoryCertificate.inscription(record.copy(autoPlayedTurns = 1)).last().text)
    }

    @Test
    fun theSeedIsWhatAnyoneCanRecomputeFromTheAnchor() {
        // First eight bytes of SHA-256("Wa11et" + "S1g"), big-endian - computed outside Kotlin
        // (Python hashlib), so a change to the derivation cannot pass by agreeing with itself.
        Assert.assertEquals(105442945842005338L, StartAnchor.seedFor("Wa11et", "S1g"))
        Assert.assertNotEquals(StartAnchor.seedFor("Wa11et", "S1g"), StartAnchor.seedFor("Other", "S1g"))
    }

    private fun anchoredGame(wallet: String, signature: String, seed: Long) = GameInfo().apply {
        startAnchorWallet = wallet
        startAnchorSignature = signature
        tileMap.mapParameters.seed = seed
    }

    @Test
    fun originIsAnchoredOnlyWhenTheMapStillHasTheAnchorsSeed() {
        val seed = StartAnchor.seedFor("W", "S")
        Assert.assertEquals(StartAnchor.ORIGIN_ANCHORED, StartAnchor.origin(anchoredGame("W", "S", seed), "W"))
        // Not yet known who mints (the picture before connecting): the anchor alone decides.
        Assert.assertEquals(StartAnchor.ORIGIN_ANCHORED, StartAnchor.origin(anchoredGame("W", "S", seed), null))
        // Another wallet's certificate, a map that is not the anchor's, and no anchor at all.
        Assert.assertEquals(StartAnchor.ORIGIN_UNANCHORED, StartAnchor.origin(anchoredGame("W", "S", seed), "X"))
        Assert.assertEquals(StartAnchor.ORIGIN_UNANCHORED, StartAnchor.origin(anchoredGame("W", "S", seed + 1), "W"))
        Assert.assertEquals(StartAnchor.ORIGIN_UNANCHORED, StartAnchor.origin(GameInfo(), null))
    }

    @Test
    fun originIsAnAttributeAndTheAnchorTravelsOnlyWhenAnchored() {
        fun payload(r: VictoryCertificate.Record) =
            Json.parseToJsonElement(VictoryCertificate.metadataJson(r, "https://x/y", "")).jsonObject["unwritAges"]!!.jsonObject
        Assert.assertEquals("Unanchored", traits(record)["Origin"])
        Assert.assertNull(payload(record)["startSignature"])

        val anchored = record.copy(origin = StartAnchor.ORIGIN_ANCHORED, startWallet = "W", startSignature = "S")
        Assert.assertEquals("Anchored start", traits(anchored)["Origin"])
        Assert.assertEquals("W", payload(anchored)["startWallet"]!!.jsonPrimitive.content)
        Assert.assertEquals("S", payload(anchored)["startSignature"]!!.jsonPrimitive.content)
    }

    @Test
    fun nameIsTheGameAndTheVictoryIsInTheDescription() {
        val json = Json.parseToJsonElement(VictoryCertificate.metadataJson(record, "https://x/y", "")).jsonObject
        Assert.assertEquals("Unwrit Ages Victory", json["name"]!!.jsonPrimitive.content)
        Assert.assertTrue(json["description"]!!.jsonPrimitive.content.startsWith(record.winner + " achieved"))
        Assert.assertEquals(record.winner, traits(record)["Civilization"])
        // No save is uploaded in 1.0.0, so nothing may promise the world can be reopened.
        Assert.assertFalse(json["description"]!!.jsonPrimitive.content.contains("reopen"))
    }
}
