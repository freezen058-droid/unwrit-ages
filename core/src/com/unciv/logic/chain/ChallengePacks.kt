package com.unciv.logic.chain

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.json.json
import com.unciv.utils.Concurrency
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class ChallengePack {
    var id = ""
    var revision = 1
    var enabled = false
    var title = ""
    var titleTw = ""
    var titleCn = ""
    var icon = "Resume"
    /** Published example linked by its actual chain receipt, never by a display title. */
    var exampleSaveSignature = ""
    var guide = ArrayList<String>()
    var guideTw = ArrayList<String>()
    var guideCn = ArrayList<String>()
    var goals = ArrayList<ChallengeGoal>()
    val supported get() = id.matches(Regex("[a-z0-9-]{1,64}")) && revision in 1..1000000 &&
        listOf(title, titleTw, titleCn).all { it.length in 1..160 } &&
        icon in listOf("Resume", "Load", "Cities", "Capital", "Shield", "Link", "Checkmark") &&
        (exampleSaveSignature.isEmpty() || exampleSaveSignature.matches(Regex("[1-9A-HJ-NP-Za-km-z]{64,88}"))) &&
        listOf(guide, guideTw, guideCn).all { it.size in 1..4 && it.all { line -> line.length in 1..300 } } &&
        goals.size in 1..12 && goals.all { it.supported && it.packId == id && it.packRevision == revision &&
            listOf(it.labelTw, it.labelCn, it.titleTw, it.titleCn).all { text -> text.isNotBlank() } &&
            !it.initialized && it.baseline.isEmpty() && it.completedTurn == -1 && it.lastObservedTurn == -1 && it.streak == 0 } &&
        goals.map { it.id }.distinct().size == goals.size && goals.map { it.label }.distinct().size == goals.size
    fun localizedTitle(language: String) = when (language) {
        "Traditional_Chinese" -> titleTw
        "Simplified_Chinese" -> titleCn
        else -> title
    }
    fun localizedGuide(language: String) = when (language) {
        "Traditional_Chinese" -> guideTw
        "Simplified_Chinese" -> guideCn
        else -> guide
    }
}
class ChallengePackManifest {
    var schema = 1
    var engine = 1
    var revision = 1
    var packs = ArrayList<ChallengePack>()
    val supported get() = schema == 1 && engine == 1 && revision in 1..1000000 &&
        packs.size in 1..10 && packs.all { it.supported } &&
        packs.map { it.id }.distinct().size == packs.size &&
        packs.flatMap { it.goals }.map { it.id }.distinct().size == packs.sumOf { it.goals.size } &&
        packs.flatMap { it.goals }.map { it.label }.distinct().size == packs.sumOf { it.goals.size }
}
class SignedChallengePacks { var payload = ""; var signature = "" }

/** Authenticated static content only. An unavailable feed never blocks offline play. */
object ChallengePacks {
    const val PUBLIC_KEY = "MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEAwR4IVEyJYiaqy4WoeyQCK5xWE/Nhd9BZE0i9rtDuQ1uoIQhQeA81lIgVNongxNilkntUMNH3/RbYoyUScvLy1HgHsUDiaRcN9wVkeFMsfesdo96zo5t7mKoEHl7N5DMvnI6EU/kIi441N3hN83b5qQkRMZAuj8MKYwDzZrTdbQwNo1yUXQTgH9cWdLClkQ+hQEE+hHxmMmrcY3ThAPgSi0Kn8FQkpqb2v+X29QtZ4Il0rDuAvE0bKrnvFWZlq7Cd8laYC0c/UdBk5ir/iJSPt3xB3oWa+qdwG0miKejJLDoAi05nS/TqQ327iOQFQkGI6KXLgnpXAAOmjZ7pnJqedDQmET2gWmuWTwuSRF23EX+4qLJmJ16j0dndBlZ6sgM04AvCJEVqQJWY/gdaIJSkOZvS0o1wBygpmxZt+MunYX5hK9FxRvUMeWiLKXK+5CXg3meoh6nda9sIUIk6l0QWpXKjvH3XOPxTQ560hSJLcM48GQUBADE8VfomvIZwPhGZAgMBAAE="
    const val FEED = "https://unwritages.pages.dev/content/challenge-packs.json"
    const val MAX_BYTES = 128 * 1024
    @Volatile private var manifest: ChallengePackManifest? = null
    private var requested = false
    private fun cache() = Gdx.files.local("challenge-packs-cache.json")
    fun verify(envelope: String, publicKey: String = PUBLIC_KEY): ChallengePackManifest {
        require(envelope.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val parser = json().apply { ignoreUnknownFields = false; setTypeName(null) }
        val signed = parser.fromJson(SignedChallengePacks::class.java, envelope)
        val payload = Base64.getDecoder().decode(signed.payload)
        require(payload.size <= MAX_BYTES)
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)))
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(key); verifier.update(payload)
        require(verifier.verify(Base64.getDecoder().decode(signed.signature))) { "Invalid challenge pack signature" }
        val result = parser.fromJson(ChallengePackManifest::class.java, String(payload, Charsets.UTF_8))
        require(result.supported) { "Unsupported challenge pack rules" }
        return result
    }
    fun current(): ChallengePackManifest {
        manifest?.let { return it }
        val bundled = verify(Gdx.files.internal("jsons/ChallengePacks.json").readString("UTF-8"))
        val cached = runCatching { verify(cache().readString("UTF-8")) }.getOrNull()
        return (cached?.takeIf { it.revision >= bundled.revision } ?: bundled).also { manifest = it }
    }
    fun enabled() = current().packs.filter { it.enabled }
    fun availableGoals() = enabled().flatMap { it.goals }
    fun goal(id: String) = availableGoals().firstOrNull { it.id == id }
    fun unseen(seen: Map<String, Int>, packs: List<ChallengePack> = enabled()) =
        packs.filter { it.enabled && it.revision > (seen[it.id] ?: 0) }
    fun refresh(onReady: () -> Unit) {
        current()
        if (requested) { onReady(); return }
        requested = true
        Concurrency.run("ChallengePackRefresh") {
            val accepted = runCatching {
                val connection = URL(FEED).openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 4000; connection.readTimeout = 4000
                try {
                    require(connection.responseCode == 200)
                    require(connection.contentLength <= MAX_BYTES)
                    val bytes = connection.inputStream.use { it.readBytesLimited(MAX_BYTES) }
                    val envelope = String(bytes, Charsets.UTF_8)
                    val incoming = verify(envelope)
                    require(incoming.revision >= current().revision)
                    incoming to envelope
                } finally { connection.disconnect() }
            }.getOrNull()
            Concurrency.runOnGLThread {
                if (accepted != null) {
                    // Store only verified envelopes; don't let a cache I/O failure hide usable content.
                    runCatching { cache().writeString(accepted.second, false, "UTF-8") }
                    manifest = accepted.first
                }
                onReady()
            }
        }
    }
    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val n = read(buffer); if (n < 0) break
            require(out.size() + n <= limit)
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
