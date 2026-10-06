package com.unciv.logic.chain

import java.io.File

/** Only public, hash-verified immutable uploads. Never caches a live/mutable game or wallet key. */
class SharedSaveCache(private val directory: File, private val maxBytes: Long = 32L * 1024 * 1024,
                      private val maxEntries: Int = 20) {
    companion object { private val lock = Any() }

    fun read(hash: String): ByteArray? = synchronized(lock) {
        val file = file(hash) ?: return@synchronized null
        if (!file.isFile) return@synchronized null
        try {
            if (file.length() > maxBytes) { file.delete(); return@synchronized null }
            val bytes = file.readBytes()
            if (!matches(bytes, hash)) { file.delete(); return@synchronized null }
            file.setLastModified(System.currentTimeMillis())
            bytes
        } catch (_: Exception) { file.delete(); null }
    }

    fun put(hash: String, bytes: ByteArray) = synchronized(lock) {
        val target = file(hash) ?: return@synchronized
        if (bytes.size > maxBytes || !matches(bytes, hash)) return@synchronized
        try {
            if (!directory.exists() && !directory.mkdirs()) return@synchronized
            val temporary = File(directory, "$hash.tmp")
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(target)) temporary.delete()
            val entries = directory.listFiles()?.filter { it.name.matches(Regex("[0-9a-f]{64}\\.bin")) }
                ?.sortedByDescending { it.lastModified() }.orEmpty()
            var size = 0L
            entries.forEachIndexed { index, entry ->
                size += entry.length()
                if (index >= maxEntries || size > maxBytes) entry.delete()
            }
        } catch (_: Exception) { /* Cache failures must never prevent playing a verified save. */ }
    }

    private fun file(hash: String): File? =
        if (hash.matches(Regex("[0-9a-f]{64}"))) File(directory, "$hash.bin") else null

    private fun matches(bytes: ByteArray, hash: String): Boolean = try {
        ChainWallet.sha256Hex(CloudSave.unpack(bytes, null)) == hash
    } catch (_: Exception) { false }
}
