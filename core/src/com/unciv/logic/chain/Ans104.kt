package com.unciv.logic.chain

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * An ANS-104 data item - the envelope Arweave bundlers such as Turbo accept for an upload - signed
 * with an Ed25519 key (signature type 2).
 *
 * The victory certificate's picture and its metadata are uploaded this way, signed by a one-off key
 * made for the upload: Turbo stores items up to 100 KiB without charge, so the key never needs
 * funds, and the player's wallet is not asked to sign anything extra. The signing itself is passed
 * in, because the Ed25519 library lives in the Android module; everything else is here, where it
 * is unit-tested byte for byte against the reference implementation (@dha-team/arbundles).
 *
 * Format: https://github.com/ArweaveTeam/arweave-standards/blob/master/ans/ANS-104.md
 */
object Ans104 {
    private const val SIGNATURE_TYPE_ED25519 = 2

    class Tag(val name: String, val value: String)

    class DataItem(val raw: ByteArray, val id: String)

    /**
     * @param owner the 32-byte Ed25519 public key.
     * @param sign detached Ed25519 signature over the given message, 64 bytes.
     */
    fun create(data: ByteArray, tags: List<Tag>, owner: ByteArray, sign: (ByteArray) -> ByteArray): DataItem {
        require(owner.size == 32) { "Ed25519 owner must be 32 bytes" }
        val tagBytes = encodeTags(tags)
        val message = signatureData(owner, tagBytes, data)
        val signature = sign(message)
        require(signature.size == 64) { "Ed25519 signature must be 64 bytes" }

        val out = ByteArrayOutputStream(2 + 64 + 32 + 2 + 16 + tagBytes.size + data.size)
        out.write(le(SIGNATURE_TYPE_ED25519.toLong(), 2))
        out.write(signature)
        out.write(owner)
        out.write(0)            // no target
        out.write(0)            // no anchor
        out.write(le(tags.size.toLong(), 8))
        out.write(le(tagBytes.size.toLong(), 8))
        out.write(tagBytes)
        out.write(data)
        return DataItem(out.toByteArray(), id(signature))
    }

    /** What gets signed: the deep hash of the item's fields, 48 bytes (SHA-384). */
    fun signatureData(owner: ByteArray, tagBytes: ByteArray, data: ByteArray): ByteArray =
        deepHash(listOf(
            "dataitem".toByteArray(), "1".toByteArray(), SIGNATURE_TYPE_ED25519.toString().toByteArray(),
            owner, ByteArray(0), ByteArray(0), tagBytes, data
        ))

    /** An item's id is the SHA-256 of its signature, base64url without padding. */
    fun id(signature: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(sha("SHA-256", signature))

    /** Tags are an Avro array of {name: bytes, value: bytes} records. */
    fun encodeTags(tags: List<Tag>): ByteArray {
        if (tags.isEmpty()) return ByteArray(0)
        val out = ByteArrayOutputStream()
        out.write(zigzag(tags.size.toLong()))
        for (tag in tags) {
            for (bytes in listOf(tag.name.toByteArray(), tag.value.toByteArray())) {
                out.write(zigzag(bytes.size.toLong()))
                out.write(bytes)
            }
        }
        out.write(0)
        return out.toByteArray()
    }

    private fun deepHash(items: List<ByteArray>): ByteArray {
        var acc = sha("SHA-384", "list${items.size}".toByteArray())
        for (item in items) acc = sha("SHA-384", acc + deepHashBlob(item))
        return acc
    }

    private fun deepHashBlob(blob: ByteArray): ByteArray =
        sha("SHA-384", sha("SHA-384", "blob${blob.size}".toByteArray()) + sha("SHA-384", blob))

    private fun sha(algorithm: String, bytes: ByteArray): ByteArray =
        MessageDigest.getInstance(algorithm).digest(bytes)

    private fun le(value: Long, bytes: Int) = ByteArray(bytes) { ((value shr (8 * it)) and 0xff).toByte() }

    private fun zigzag(value: Long): ByteArray {
        var n = (value shl 1) xor (value shr 63)
        val out = ByteArrayOutputStream()
        while (n and 0x7fL.inv() != 0L) {
            out.write(((n and 0x7f) or 0x80).toInt())
            n = n ushr 7
        }
        out.write(n.toInt())
        return out.toByteArray()
    }
}
