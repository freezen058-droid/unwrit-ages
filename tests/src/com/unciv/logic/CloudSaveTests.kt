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
}
