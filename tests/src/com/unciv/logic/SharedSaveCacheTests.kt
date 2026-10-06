package com.unciv.logic

import com.unciv.logic.chain.ChainWallet
import com.unciv.logic.chain.CloudSave
import com.unciv.logic.chain.SharedSaveCache
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class SharedSaveCacheTests {
    @Test fun verifiedUploadsSurviveRestartAndDamagedCacheIsDiscarded() {
        val dir = Files.createTempDirectory("shared-save-cache").toFile()
        try {
            val json = "{\"turns\":90}"
            val hash = ChainWallet.sha256Hex(json)
            val bytes = CloudSave.pack(json, null)
            SharedSaveCache(dir).put(hash, bytes)
            assertArrayEquals(bytes, SharedSaveCache(dir).read(hash))
            dir.resolve("$hash.bin").writeBytes(byteArrayOf(1, 2, 3))
            assertNull(SharedSaveCache(dir).read(hash))
            assertFalse(dir.resolve("$hash.bin").exists())
        } finally { dir.deleteRecursively() }
    }
    @Test fun WrongHashPrivateAndUnsafeNamesAreNeverCached() {
        val dir = Files.createTempDirectory("shared-save-cache").toFile()
        try {
            val cache = SharedSaveCache(dir)
            val hash = ChainWallet.sha256Hex("save")
            cache.put(hash, CloudSave.pack("different", null))
            cache.put(hash, CloudSave.pack("save", ByteArray(32)))
            cache.put("../outside", CloudSave.pack("save", null))
            assertNull(cache.read(hash))
            assertEquals(0, dir.listFiles()!!.size)
        } finally { dir.deleteRecursively() }
    }
    @Test fun LimitsEvictOnlyCacheEntries() {
        val dir = Files.createTempDirectory("shared-save-cache").toFile()
        try {
            dir.resolve("unrelated.txt").writeText("keep")
            val cache = SharedSaveCache(dir, maxEntries = 1)
            val a = CloudSave.pack("a", null); val b = CloudSave.pack("b", null)
            cache.put(ChainWallet.sha256Hex("a"), a)
            dir.resolve("${ChainWallet.sha256Hex("a")}.bin").setLastModified(1)
            cache.put(ChainWallet.sha256Hex("b"), b)
            assertNull(cache.read(ChainWallet.sha256Hex("a")))
            assertArrayEquals(b, cache.read(ChainWallet.sha256Hex("b")))
            assertEquals("keep", dir.resolve("unrelated.txt").readText())
            SharedSaveCache(dir, maxBytes = 1).put(ChainWallet.sha256Hex("c"), CloudSave.pack("c", null))
            assertFalse(dir.resolve("${ChainWallet.sha256Hex("c")}.bin").exists())
        } finally { dir.deleteRecursively() }
    }
}
