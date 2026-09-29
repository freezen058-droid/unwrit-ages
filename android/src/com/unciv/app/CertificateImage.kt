package com.unciv.app

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import com.unciv.logic.chain.CertificateEmblem
import com.unciv.logic.chain.VictoryCertificate
import java.io.ByteArrayOutputStream

/**
 * Draws a victory certificate: the painted stele (assets/certificate/stele.jpg) with the game's
 * inscription and the winner's emblem inlaid in gold on its tablet.
 *
 * The look was designed in pic/batch_review/_sd/stele.py; the numbers below are its LAYOUT and
 * STYLES and must change with them. The one difference is the engraving itself: Python composites
 * masks and lets the stone's grain through the gold, here each line is drawn twice - the lip's
 * shadow along its upper-left edge, then the gold - which reads the same at a glance.
 */
class CertificateImage(private val assets: AssetManager) {

    private companion object {
        const val SIZE = 1024
        val PANEL = RectF(377f, 397f, 647f, 811f)
        const val MEDAL_X = 512f
        const val MEDAL_Y = 355f
        const val MEDAL_R = 36f
        /** On the gilded stele the emblem sits in the arch's niche, above the rail it would
         *  otherwise overlap; the largest size that keeps every nation's emblem (India and Japan
         *  are the widest) at least 2 px off the arch's frame (09-28). */
        const val MEDAL_Y_ANCHORED = 346f
        const val MEDAL_R_ANCHORED = 30f
        /** Gilded letters; the civilisation, the victory and the emblem are the brighter gold. */
        val GOLD = Color.rgb(206, 160, 78)
        val GOLD_BRIGHT = Color.rgb(240, 200, 118)
        const val MARK = "UNWRIT AGES"
        /** Turbo stores uploads up to 100 KiB free; leave room for the envelope and tags. */
        const val MAX_JPEG_BYTES = 95 * 1024
    }

    private class Style(val typeface: Typeface, val weight: Int, val size: Float, val gold: Boolean, val after: Float)

    private val cinzel = Typeface.createFromAsset(assets, "certificate/Cinzel.ttf")
    private val garamond = Typeface.createFromAsset(assets, "certificate/EBGaramond.ttf")
    private val styles = mapOf(
        "civ" to Style(cinzel, 700, 34f, false, 6f),
        "victory" to Style(cinzel, 700, 22f, true, 16f),
        "when" to Style(garamond, 600, 26f, false, 4f),
        "rule" to Style(garamond, 0, 2f, true, 16f),
        "fact" to Style(garamond, 500, 22f, false, 6f),
        "mark" to Style(cinzel, 700, 18f, true, 0f),
    )

    /** The finished certificate as a JPEG small enough for the free upload tier. [anchored] draws
     *  on the anchored stele - the same painting gilded, its tablet unchanged, so the inscription's
     *  layout fits both (ROADMAP "Provenance"); only the emblem moves up into the arch - or the
     *  plain one while it is not painted. */
    fun render(lines: List<VictoryCertificate.InscriptionLine>, emblem: CertificateEmblem, anchored: Boolean): ByteArray {
        val gilded = anchored && "stele_anchored.jpg" in (assets.list("certificate") ?: emptyArray())
        val file = if (gilded) "certificate/stele_anchored.jpg" else "certificate/stele.jpg"
        val base = assets.open(file).use { BitmapFactory.decodeStream(it) }
        val bitmap = base.copy(Bitmap.Config.ARGB_8888, true)
        base.recycle()
        val canvas = Canvas(bitmap)
        if (gilded) drawEmblem(canvas, emblem, MEDAL_Y_ANCHORED, MEDAL_R_ANCHORED)
        else drawEmblem(canvas, emblem, MEDAL_Y, MEDAL_R)
        drawInscription(canvas, lines)
        return encode(bitmap).also { bitmap.recycle() }
    }

    /** The nation's icon inlaid in gold, like the letters. A mod's nation has no bundled icon;
     *  its tablet then carries the inscription alone. (The emblem's colours stay in the metadata.) */
    private fun drawEmblem(canvas: Canvas, emblem: CertificateEmblem, medalY: Float, medalR: Float) {
        val icon = try {
            assets.open("certificate/emblems/${emblem.nation}.png").use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) { null } ?: return
        val box = RectF(MEDAL_X - medalR, medalY - medalR, MEDAL_X + medalR, medalY + medalR)
        fun layer(dx: Float, argb: Int, blur: Float) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = PorterDuffColorFilter(argb, PorterDuff.Mode.SRC_IN)
                if (blur > 0f) maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawBitmap(icon, null, RectF(box.left + dx, box.top + dx, box.right + dx, box.bottom + dx), paint)
        }
        layer(-1.5f, Color.argb((0.55f * 255).toInt(), 0, 0, 0), 1.2f)   // the lip's shadow
        layer(0f, GOLD_BRIGHT, 0f)
        icon.recycle()
    }

    private fun paintFor(style: Style, size: Float) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = style.typeface
        textSize = size
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) fontVariationSettings = "'wght' ${style.weight}"
    }

    /** The style's size, shrunk until the line fits the panel. */
    private fun fitted(text: String, style: Style, width: Float): Paint {
        var size = style.size
        var paint = paintFor(style, size)
        while (paint.measureText(text) > width && size > 12f) {
            size -= 1f
            paint = paintFor(style, size)
        }
        return paint
    }

    /** A fact too wide for the face wraps at spaces; titles shrink instead (see [fitted]). */
    private fun wrap(line: VictoryCertificate.InscriptionLine, width: Float, styles: Map<String, Style>): List<VictoryCertificate.InscriptionLine> {
        val style = styles[line.style] ?: return listOf(line)
        if (line.style != "fact") return listOf(line)
        val paint = paintFor(style, style.size)
        if (paint.measureText(line.text) <= width) return listOf(line)
        val out = mutableListOf<String>()
        var current = ""
        for (word in line.text.split(" ")) {
            val trial = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(trial) <= width || current.isEmpty()) current = trial
            else { out += current; current = word }
        }
        out += current
        return out.map { VictoryCertificate.InscriptionLine(line.style, it) }
    }

    private fun drawInscription(canvas: Canvas, allLines: List<VictoryCertificate.InscriptionLine>) {
        val width = PANEL.width() - 20f
        val cx = PANEL.centerX()
        class Placed(val line: VictoryCertificate.InscriptionLine, val style: Style, val paint: Paint?, val height: Float)
        val markStyle = styles.getValue("mark")
        val markPaint = fitted(MARK, markStyle, width)
        val room = PANEL.height() - markPaint.textSize - 20f
        fun layout(factSize: Float): List<Placed> {
            val sized = styles + ("fact" to styles.getValue("fact").let { Style(it.typeface, it.weight, factSize, it.gold, it.after) })
            return allLines.flatMap { wrap(it, width, sized) }.mapNotNull { line ->
                val style = sized[line.style] ?: return@mapNotNull null
                if (line.style == "rule") Placed(line, style, null, 12f)
                else fitted(line.text, style, width).let { Placed(line, style, it, it.textSize) }
            }
        }
        fun height(placed: List<Placed>) = placed.sumOf { (it.height + it.style.after).toDouble() }.toFloat()
        // A busy game - seven rivals, three eliminated, AutoPlay - has more lines than the tablet
        // holds at full size: the facts shrink a point at a time until the block clears the mark.
        var factSize = styles.getValue("fact").size
        var placed = layout(factSize)
        while (height(placed) > room && factSize > 14f) {
            factSize -= 1f
            placed = layout(factSize)
        }
        val block = height(placed)
        // Top of each line's em box, like PIL's text origin, so the vertical rhythm matches stele.py.
        var y = PANEL.top + (PANEL.height() - markPaint.textSize - 20f - block) / 2
        for (p in placed) {
            if (p.paint == null) {
                y += 10f                     // below the descenders of the line above
                val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GOLD_BRIGHT; strokeWidth = 2f }
                canvas.drawLine(cx - width * 0.3f, y, cx + width * 0.3f, y, rule)
                y += 2f + p.style.after
                continue
            }
            engrave(canvas, p.line.text, cx, y, p.paint, p.style.gold)
            y += p.height + p.style.after
        }
        engrave(canvas, MARK, cx, PANEL.bottom - markPaint.textSize - 10f, markPaint, gold = true)
    }

    /** A gilded groove: the stone's shadow along its upper-left lip, then the gold - the brighter
     *  gold for the titles (the styles marked gold in stele.py STYLES). */
    private fun engrave(canvas: Canvas, text: String, cx: Float, top: Float, paint: Paint, gold: Boolean) {
        val x = cx - paint.measureText(text) / 2
        val baseline = top - paint.fontMetrics.ascent
        fun layer(dx: Float, argb: Int, blur: Float) {
            val p = Paint(paint).apply {
                color = argb
                if (blur > 0f) maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawText(text, x + dx, baseline + dx, p)
        }
        layer(-1.5f, Color.argb((0.55f * 255).toInt(), 0, 0, 0), 1.2f)   // the lip's shadow
        layer(0f, if (gold) GOLD_BRIGHT else GOLD, 0.5f)
    }

    /** Highest quality that fits the free tier; a busy inscription costs a little quality, not money. */
    private fun encode(bitmap: Bitmap): ByteArray {
        var quality = 86
        while (true) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= MAX_JPEG_BYTES || quality <= 50) return out.toByteArray()
            quality -= 4
        }
    }
}
