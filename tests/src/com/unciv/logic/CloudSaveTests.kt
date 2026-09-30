package com.unciv.logic

import com.unciv.logic.chain.ChainWallet
import com.unciv.logic.chain.CloudSave
import org.junit.Assert
import org.junit.Test

class CloudSaveTests {
    private val json = "{\"gameId\":\"g\",\"turns\":88,\"civilizations\":[" + "{\"civName\":\"Japan\"},".repeat(4000) + "{}]}"
    private val key = CloudSave.keyFromSignature(ByteArray(64) { it.toByte() })

    @Test
    fun privateRoundTripsWithTheKeyOnly() {
        val packed = CloudSave.pack(json, key)
        Assert.assertEquals(json, CloudSave.unpack(packed, key))
        // Encrypted: the plain JSON is nowhere in what gets uploaded
        Assert.assertFalse(String(packed, Charsets.ISO_8859_1).contains("Japan"))
        val otherKey = CloudSave.keyFromSignature(ByteArray(64) { (it + 1).toByte() })
        Assert.assertThrows(CloudSave.WrongKeyException::class.java) { CloudSave.unpack(packed, otherKey) }
    }

    @Test
    fun aDamagedUploadIsRefusedNotLoaded() {
        val packed = CloudSave.pack(json, key)
        packed[packed.size / 2] = (packed[packed.size / 2].toInt() xor 1).toByte()
        Assert.assertThrows(CloudSave.WrongKeyException::class.java) { CloudSave.unpack(packed, key) }
    }

    @Test
    fun sharedNeedsNoKey() {
        Assert.assertEquals(json, CloudSave.unpack(CloudSave.pack(json, null), null))
    }

    @Test
    fun chunksReassembleAndStayUnderTheFreeTier() {
        val bytes = ByteArray(CloudSave.CHUNK_BYTES * 2 + 5) { (it % 251).toByte() }
        val chunks = CloudSave.chunks(bytes)
        Assert.assertEquals(3, chunks.size)
        Assert.assertTrue(chunks.all { it.size <= CloudSave.CHUNK_BYTES })
        Assert.assertArrayEquals(bytes, chunks.reduce { a, b -> a + b })
    }

    @Test
    fun memoRoundTripsThroughTheRpcsFormat() {
        val hash = ChainWallet.sha256Hex(json)
        val r = CloudSave.Record("26d9ce72-1635", "Japan: turn 88", hash, CloudSave.PRIVATE,
            CloudSave.keyFingerprint(key), listOf("idA", "idB"))
        // getSignaturesForAddress reports "[length] memo", other memos joined by "; "
        val reported = "[${r.memo().length}] ${r.memo()}; [5] other"
        val back = CloudSave.parse(reported, "sig", 1790000000)!!
        Assert.assertEquals("Japan turn 88", back.name)   // colons cannot survive: they separate fields
        Assert.assertEquals(listOf("idA", "idB"), back.arweaveIds)
        Assert.assertEquals(r.keyFingerprint, back.keyFingerprint)
        Assert.assertTrue(back.restorable)
        Assert.assertFalse(back.shared)
        Assert.assertEquals("sig", back.signature)
    }

    @Test
    fun a100RecordStillParsesButCannotBeRestored() {
        val hash = "a".repeat(64)
        val old = CloudSave.parse("[98] unwritages-save:g1:My save:$hash")!!
        Assert.assertEquals("My save", old.name)
        Assert.assertFalse(old.restorable)
        Assert.assertNull(CloudSave.parse("[10] hello"))
        Assert.assertNull(CloudSave.parse("unwritages-start:g1"))
    }

    private val meta = CloudSave.Meta("Rome", "Pangaea", "Tiny", "Classical era", 59, "Settler",
        "2hJFxhGzLqS5zjVqVzpAE46ECQPKV7okwbAhFeBD4Luu")

    @Test
    fun aSharedRecordCarriesItsGalleryDetails() {
        val r = CloudSave.Record("g", "Rome - 59 turns", "b".repeat(64), CloudSave.SHARED, "", listOf("id"), meta = meta)
        val back = CloudSave.parse("[${r.memo().length}] ${r.memo()}; [5] other")!!
        Assert.assertEquals(meta, back.meta)
        Assert.assertEquals(listOf("id"), back.arweaveIds)
        Assert.assertTrue(r.memo().length < 900)
    }

    @Test
    fun aPrivateRecordSaysNothingAboutItsGame() {
        val r = CloudSave.Record("g", "Rome", "b".repeat(64), CloudSave.PRIVATE, "abcd", listOf("id"), meta = meta)
        Assert.assertFalse(r.memo().contains("Pangaea"))
        Assert.assertNull(CloudSave.parse(r.memo())!!.meta)
    }

    @Test
    fun theGalleryDetailsCannotBreakTheMemo() {
        val odd = meta.copy(civ = "Ro:me|x;y", mapType = "M".repeat(80))
        val r = CloudSave.Record("g", "a;b", "b".repeat(64), CloudSave.SHARED, "", listOf("id"), meta = odd)
        val back = CloudSave.parse(r.memo())!!
        val m = back.meta!!
        Assert.assertEquals("Romexy", m.civ)
        Assert.assertEquals(32, m.mapType.length)
        Assert.assertEquals("ab", back.name)
        Assert.assertEquals(meta.author, m.author)
    }

    @Test
    fun aRelayedSaveNamesItsParentAndOldRecordsHaveNone() {
        val relayed = meta.copy(parent = "5DVjGn1RNVEdSBM6zTWdouSqtyYeAASVCfqnFikBpkK2aJZwNFxy9VELXARnXmwv95FByvvUzArYpj5PoswiP4s3")
        val r = CloudSave.Record("g", "Rome", "b".repeat(64), CloudSave.SHARED, "", listOf("id"), meta = relayed)
        Assert.assertEquals(relayed, CloudSave.parse(r.memo())!!.meta)
        Assert.assertTrue(r.memo().length < 900)
        // A record from before lineage: seven fields, no parent
        val old = CloudSave.Record("g", "Rome", "b".repeat(64), CloudSave.SHARED, "", listOf("id"), meta = meta)
        Assert.assertFalse(old.memo().endsWith("|"))
        Assert.assertEquals("", CloudSave.parse(old.memo())!!.meta!!.parent)
    }

    @Test
    fun aBountyRoundTripsAndIsNeverMistakenForARecordOrATip() {
        val b = CloudSave.Bounty("5DVjGn1R", 20, 50, "2hJFxhGzLqS5zjVqVzpAE46ECQPKV7okwbAhFeBD4Luu")
        Assert.assertEquals(b, CloudSave.parseBounty("[${b.memo().length}] ${b.memo()}; [5] other"))
        Assert.assertNull(CloudSave.parse(b.memo()))
        Assert.assertNull(CloudSave.parseTip(b.memo()))
        Assert.assertNull(CloudSave.parseBounty(CloudSave.tipMemo("5DVjGn1R", 17)))
        Assert.assertNull(CloudSave.parseBounty("unwritages-bounty:sig:0:50:poster"))
        Assert.assertNull(CloudSave.parseBounty("unwritages-bounty:sig:20:50"))
    }

    @Test
    fun tipMemosParseAsTheRpcReportsThem() {
        val memo = CloudSave.tipMemo("sigA", 17)
        Assert.assertEquals("sigA" to 17L, CloudSave.parseTip("[${memo.length}] $memo; [5] other"))
        // Large claims parse - whether they were paid is checked against the transaction
        Assert.assertEquals("sigB" to 1_000L, CloudSave.parseTip(CloudSave.tipMemo("sigB", 1_000)))
        Assert.assertNull(CloudSave.parseTip(CloudSave.tipMemo("sigC", 0)))
        Assert.assertNull(CloudSave.parseTip("unwritages-save:g:x:" + "a".repeat(64)))
    }
}
