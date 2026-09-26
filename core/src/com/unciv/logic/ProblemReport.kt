package com.unciv.logic

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.utils.ScreenUtils
import com.unciv.UncivGame
import com.unciv.utils.Concurrency
import com.unciv.utils.Log
import com.unciv.utils.launchOnGLThread
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * A problem report the player chose to send (functions/report.js): their words, the error if there
 * was one, the version and the phone - and, only if they left them ticked, the game's save and a
 * screenshot. Nothing is sent without the player pressing Send (user, 09-26: option B).
 */
object ProblemReport {

    const val ENDPOINT = "https://unwritages.pages.dev/report"

    /** The endpoint refuses more; a screenshot is dropped before the save is. */
    private const val MAX_BYTES = 4_500_000

    /** The stage as the player sees it, as PNG at most 1280 px wide - drawn again into an
     *  offscreen buffer rather than read from the screen, which some phones do not return. */
    fun screenshot(stage: Stage): ByteArray? = try {
        val w = Gdx.graphics.backBufferWidth
        val h = Gdx.graphics.backBufferHeight
        val buffer = FrameBuffer(Pixmap.Format.RGBA8888, w, h, false)
        buffer.begin()
        ScreenUtils.clear(0f, 0f, 0f, 1f)
        stage.draw()
        val full = Pixmap.createFromFrameBuffer(0, 0, w, h)
        buffer.end()
        buffer.dispose()
        val scale = minOf(1f, 1280f / w)
        val small = Pixmap((w * scale).toInt(), (h * scale).toInt(), Pixmap.Format.RGBA8888)
        small.filter = Pixmap.Filter.BiLinear
        small.drawPixmap(full, 0, 0, w, h, 0, 0, small.width, small.height)
        full.dispose()
        val out = ByteArrayOutputStream()
        // A framebuffer's rows run bottom-up; the encoder's default flip puts them right
        val png = PixmapIO.PNG()
        try { png.write(out, small) } finally { png.dispose() }
        small.dispose()
        out.toByteArray()
    } catch (ex: Throwable) {
        Log.error("Could not take a screenshot for the report", ex)
        null
    }

    /**
     * @param kind "crash" or "problem"
     * @param onDone on the GL thread: null when received, else the reason
     */
    fun send(kind: String, text: String, details: String, save: String?, screenshot: ByteArray?, onDone: (String?) -> Unit) {
        Concurrency.run("ProblemReport") {
            val error = try {
                var body = json(kind, text, details, save, screenshot)
                if (body.length > MAX_BYTES && screenshot != null) body = json(kind, text, details, save, null)
                if (body.length > MAX_BYTES) body = json(kind, text, details, null, null)
                post(body)
            } catch (ex: Exception) {
                Log.error("Could not send the problem report", ex)
                ex.message ?: ex.javaClass.simpleName
            }
            launchOnGLThread { onDone(error) }
        }
    }

    /** @return null when the endpoint accepted it, else its message or the HTTP status */
    private fun post(body: String): String? {
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val status = connection.responseCode
        if (status == 200) return null
        val reply = (connection.errorStream ?: connection.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
        return Regex("\"message\":\"([^\"]*)\"").find(reply)?.groupValues?.get(1) ?: "HTTP $status"
    }

    private fun json(kind: String, text: String, details: String, save: String?, screenshot: ByteArray?): String {
        val sb = StringBuilder(1024 + (save?.length ?: 0) + (screenshot?.size ?: 0) * 4 / 3)
        fun field(name: String, value: String) {
            if (sb.length > 1) sb.append(',')
            sb.append('"').append(name).append("\":\"")
            for (c in value) when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append("%04x".format(c.code))
                else -> sb.append(c)
            }
            sb.append('"')
        }
        sb.append('{')
        field("kind", kind)
        field("text", text.take(2000))
        field("details", details.take(60_000))
        field("version", UncivGame.VERSION.toNiceString())
        field("system", try { Log.getSystemInfo() } catch (_: Throwable) { "" })
        if (save != null) field("save", save)
        if (screenshot != null) field("screenshot", Base64.getEncoder().encodeToString(screenshot))
        sb.append('}')
        return sb.toString()
    }
}
