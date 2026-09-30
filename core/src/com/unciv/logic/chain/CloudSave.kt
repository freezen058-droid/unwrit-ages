package com.unciv.logic.chain

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Saves you can get back from the chain (ROADMAP "After 1.0.0: saves you can get back").
 *
 * Recording a save (1 SKR) now also stores the save itself on Arweave and names it in the memo:
 *
 *  * **private** (the default): gzip, then AES-256-GCM with a key derived from the wallet signing
 *    [KEY_MESSAGE]. Ed25519 signatures are deterministic, so the same wallet re-derives the same
 *    key on any device and nothing secret is ever stored. Another wallet sees only ciphertext;
 *  * **shared**: gzip only, so anyone can load the game and play on - a game won from it says
 *    "Continued from turn N" on its certificate.
 *
 * Restoring checks the SHA-256 of the recovered save against the memo's, so a corrupted or
 * substituted upload is refused rather than loaded. Everything here is pure JVM - the wallet,
 * the upload and the RPC are the platform's ([PlatformWalletService]).
 */
object CloudSave {

    /** What the wallet signs to derive the key. It says what it is for, so a player shown the
     *  same text by anything other than this game has a reason to refuse. */
    const val KEY_MESSAGE = "Unwrit Ages cloud save key v1.\n" +
        "Signing this lets Unwrit Ages encrypt and restore your saves. " +
        "Only sign it inside the Unwrit Ages app - anyone with this signature can read your saves."

    const val MEMO_PREFIX = "unwritages-save:"
    const val PRIVATE = "p"
    const val SHARED = "s"

    /** Turbo stores items up to 100 KiB free; a little under, for the data item's envelope. */
    const val CHUNK_BYTES = 90 * 1024

    private val MAGIC_PRIVATE = "UAS1".toByteArray()
    private val MAGIC_SHARED = "UAS0".toByteArray()
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    /** The encryption key: SHA-256 of the wallet's signature over [KEY_MESSAGE]. */
    fun keyFromSignature(signature: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(signature)

    /** Four bytes of the key's own hash, in the memo: on restore it tells "this wallet signs
     *  differently now" apart from "the upload is damaged". Reveals nothing about the key. */
    fun keyFingerprint(key: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(key).take(4).joinToString("") { "%02x".format(it) }

    fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    fun gunzip(bytes: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }.toString(Charsets.UTF_8)

    /** The bytes that go to Arweave for the save [json]: shared when [key] is null. */
    fun pack(json: String, key: ByteArray?): ByteArray {
        val zipped = gzip(json)
        if (key == null) return MAGIC_SHARED + zipped
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        return MAGIC_PRIVATE + nonce + cipher.doFinal(zipped)
    }

    class WrongKeyException : Exception("This save was encrypted with a different key - was it recorded by another wallet?")

    /** The save's JSON back from [pack]'s bytes; [key] is needed for a private one. GCM
     *  authenticates, so a wrong key or a damaged upload throws instead of returning garbage. */
    fun unpack(bytes: ByteArray, key: ByteArray?): String {
        val magic = bytes.copyOfRange(0, 4)
        val body = bytes.copyOfRange(4, bytes.size)
        return when {
            magic.contentEquals(MAGIC_SHARED) -> gunzip(body)
            magic.contentEquals(MAGIC_PRIVATE) -> {
                requireNotNull(key) { "A private save needs the wallet's key" }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
                    GCMParameterSpec(TAG_BITS, body.copyOfRange(0, NONCE_BYTES)))
                val plain = try {
                    cipher.doFinal(body.copyOfRange(NONCE_BYTES, body.size))
                } catch (_: javax.crypto.AEADBadTagException) {
                    throw WrongKeyException()
                }
                gunzip(plain)
            }
            else -> throw IllegalArgumentException("Not an Unwrit Ages cloud save")
        }
    }

    /** [bytes] in pieces of at most [CHUNK_BYTES], each stored as its own free upload. */
    fun chunks(bytes: ByteArray): List<ByteArray> =
        (bytes.indices step CHUNK_BYTES).map { bytes.copyOfRange(it, minOf(it + CHUNK_BYTES, bytes.size)) }

    /**
     * One save record as its memo says it: `unwritages-save:<gameId>:<name>:<sha256>` and, since
     * cloud saves, `:<p|s>:<key fingerprint, private only>:<arweave id>,<arweave id>...`.
     * Records from 1.0.0 have no cloud part and cannot be restored (the file was never uploaded).
     */
    data class Record(
        val gameId: String,
        val name: String,
        val hashHex: String,
        val visibility: String = "",
        val keyFingerprint: String = "",
        val arweaveIds: List<String> = emptyList(),
        /** From the transaction, not the memo: its signature and time (seconds), when listed. */
        val signature: String = "",
        val blockTime: Long = 0,
        /** What the shared-save gallery sorts and filters by; shared records since 09-30 only. */
        val meta: Meta? = null
    ) {
        val restorable get() = arweaveIds.isNotEmpty()
        val shared get() = visibility == SHARED

        fun memo(): String {
            val base = MEMO_PREFIX + "$gameId:${safeName(name)}:$hashHex"
            if (arweaveIds.isEmpty()) return base
            val cloud = "$base:$visibility:$keyFingerprint:" + arweaveIds.joinToString(",")
            // Only a shared record says what is in it: a private one stays a name and a hash
            return if (shared && meta != null) "$cloud:${meta.encode()}" else cloud
        }
    }

    /**
     * A shared save described for the gallery, as a seventh memo field: `civ|map type|map size|era|
     * turn|difficulty|author[|parent]`. Readers before 09-30 stop at the sixth field, so they still
     * list and load the save. [author] is the recording wallet, where tips go - in the memo because
     * listing reads memos only, not the transaction's signer. [parent] is the record of the shared
     * save this game was taken over from, if it was (10-01); records before it have none.
     */
    data class Meta(
        val civ: String,
        val mapType: String,
        val mapSize: String,
        val era: String,
        val turn: Int,
        val difficulty: String,
        val author: String,
        val parent: String = ""
    ) {
        /** The words are capped to keep the memo small; the author's address never is - cut, tips go nowhere. */
        fun encode() = (listOf(civ, mapType, mapSize, era, turn.toString(), difficulty).map { safeField(it).take(32) } +
            safeField(author) + (if (parent.isEmpty()) emptyList() else listOf(safeField(parent)))).joinToString("|")

        companion object {
            fun decode(field: String): Meta? {
                val p = field.split("|")
                if (p.size < 7) return null
                return Meta(p[0], p[1], p[2], p[3], p[4].toIntOrNull() ?: 0, p[5], p[6], p.getOrNull(7).orEmpty())
            }
            private fun safeField(text: String) = text.replace(Regex("[:|;]"), "")
        }
    }

    /** Colons are the memo's field separator and the RPC joins memos with "; " - neither may be in
     *  the name; it is capped so the transaction stays small. */
    fun safeName(name: String) = name.replace(":", "").replace(";", "").take(64)

    /**
     * A tip to a shared save's author: an SKR transfer to them, with this memo naming the save by its
     * record's transaction signature. Every tip also carries one fixed reference account, so one
     * getSignaturesForAddress on it lists every tip there is - the gallery's rating.
     */
    const val TIP_PREFIX = "unwritages-tip:"

    fun tipMemo(saveSignature: String, wholeSkr: Long) = "$TIP_PREFIX$saveSignature:$wholeSkr"

    /** (save signature, whole SKR) from a memo field as the RPC reports it, or null. */
    fun parseTip(memoField: String): Pair<String, Long>? {
        val at = memoField.indexOf(TIP_PREFIX)
        if (at < 0) return null
        val parts = memoField.substring(at + TIP_PREFIX.length).substringBefore(";").trim().split(":")
        if (parts.size < 2 || parts[0].isBlank()) return null
        val amount = parts[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
        return parts[0] to amount
    }

    /**
     * A bounty on a shared save (ROADMAP gallery, option A - manual, user 10-01): [skr] SKR to the first
     * victory won from the save [saveSignature] within [turns] turns of taking it over, without
     * AutoPlay. A promise, not an escrow: the poster pays by hand, on seeing the winner's certificate
     * (it names the source save, the turn it was continued from and any AutoPlay). Posted as a memo
     * with the 1 SKR record fee, so it is in the treasury's history beside the records.
     */
    data class Bounty(val saveSignature: String, val skr: Long, val turns: Int, val poster: String) {
        fun memo() = "$BOUNTY_PREFIX$saveSignature:$skr:$turns:$poster"
    }

    const val BOUNTY_PREFIX = "unwritages-bounty:"

    /** A [Bounty] from a memo field as the RPC reports it, or null. */
    fun parseBounty(memoField: String): Bounty? {
        val at = memoField.indexOf(BOUNTY_PREFIX)
        if (at < 0) return null
        val p = memoField.substring(at + BOUNTY_PREFIX.length).substringBefore(";").trim().split(":")
        if (p.size < 4 || p[0].isBlank() || p[3].isBlank()) return null
        val skr = p[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
        val turns = p[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return Bounty(p[0], skr, turns, p[3])
    }

    /** The most the game's own tip buttons send at once. A memo only claims its amount; what counts
     *  is what the author received, checked against the transaction (listSaveTips), so larger tips
     *  sent some other way still count once they are real. */
    const val MAX_TIP_SKR = 100L

    /**
     * The record in a memo as the RPC reports it - getSignaturesForAddress prefixes each memo
     * with its length in brackets (`[97] unwritages-save:...`), and a transaction may carry other
     * memos too, separated by "; ". Null when it is not a save record.
     */
    fun parse(memoField: String, signature: String = "", blockTime: Long = 0): Record? {
        val at = memoField.indexOf(MEMO_PREFIX)
        if (at < 0) return null
        val text = memoField.substring(at + MEMO_PREFIX.length).substringBefore(";").trim()
        val parts = text.split(":")
        if (parts.size < 3) return null
        val (gameId, name, hash) = parts
        if (hash.length != 64) return null
        if (parts.size < 6) return Record(gameId, name, hash, signature = signature, blockTime = blockTime)
        val ids = parts[5].split(",").filter { it.isNotBlank() }
        val meta = parts.getOrNull(6)?.let { Meta.decode(it) }
        return Record(gameId, name, hash, parts[3], parts[4], ids, signature, blockTime, meta)
    }
}
