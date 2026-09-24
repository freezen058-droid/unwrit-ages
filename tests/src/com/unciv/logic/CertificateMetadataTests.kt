package com.unciv.logic

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
}
