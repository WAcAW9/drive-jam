package com.drivejam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/** A static album cover per track (style palette + seeded motif). Static = allowed on screen while driving. */
object CoverArt {
    private val cache = object : LinkedHashMap<Track, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Track, Bitmap>) = size > 12
    }

    fun of(t: Track, size: Int = 600): Bitmap = synchronized(cache) { cache.getOrPut(t) { draw(t, size) } }

    private fun draw(t: Track, n: Int): Bitmap {
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = java.util.Random(t.seed.toLong())
        val (c0, c1) = t.style.colors[0] to t.style.colors[1]
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, n.toFloat(), c0, c1, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, n.toFloat(), n.toFloat(), p)
        p.shader = null
        val f = n.toFloat()
        fun rf(a: Float, b: Float) = a + r.nextFloat() * (b - a)
        when (t.style) {
            Style.CITY_POP -> { // sun over a striped sea
                p.color = 0xFFFFE08A.toInt(); c.drawCircle(f * rf(0.3f, 0.7f), f * 0.55f, f * 0.2f, p)
                p.color = 0x553A86FF; c.drawRect(0f, f * 0.62f, f, f, p)
                p.color = 0x88FFFFFF.toInt()
                for (i in 0 until 6) c.drawRect(f * rf(0f, 0.5f), f * (0.66f + i * 0.055f), f * rf(0.5f, 1f), f * (0.672f + i * 0.055f), p)
            }
            Style.HOUSE -> { // pulsing rings
                p.style = Paint.Style.STROKE
                val cx = f * rf(0.35f, 0.65f); val cy = f * rf(0.35f, 0.65f)
                for (i in 1..9) { p.strokeWidth = f * 0.012f * (10 - i) / 3f; p.color = Color.argb(200 - i * 18, 255, 255, 255); c.drawCircle(cx, cy, f * 0.06f * i, p) }
                p.style = Paint.Style.FILL
            }
            Style.LOFI -> { // soft blobs + film grain
                for (i in 0 until 4) { p.color = Color.argb(70, 255, 230 - i * 20, 200); c.drawCircle(f * rf(0.1f, 0.9f), f * rf(0.2f, 0.8f), f * rf(0.12f, 0.3f), p) }
                for (i in 0 until 900) { p.color = Color.argb(r.nextInt(60), 255, 255, 255); c.drawPoint(f * r.nextFloat(), f * r.nextFloat(), p) }
            }
            Style.SYNTHWAVE -> { // striped sun + perspective grid
                val sy = f * 0.48f
                p.shader = LinearGradient(0f, sy - f * 0.25f, 0f, sy + f * 0.25f, 0xFFFFD166.toInt(), 0xFFFF2E97.toInt(), Shader.TileMode.CLAMP)
                c.drawCircle(f / 2, sy, f * 0.25f, p); p.shader = null
                p.color = c1
                for (i in 0 until 5) c.drawRect(0f, sy + f * (0.04f + i * 0.045f), f, sy + f * (0.055f + i * 0.045f + i * 0.004f), p)
                c.drawRect(0f, f * 0.68f, f, f, p)
                p.color = 0xCCFF2E97.toInt(); p.strokeWidth = f * 0.004f
                for (i in -8..8) c.drawLine(f / 2 + i * f * 0.02f, f * 0.68f, f / 2 + i * f * 0.18f, f, p)
                for (i in 0 until 6) { val y = f * 0.68f + f * 0.32f * (i * i) / 25f; c.drawLine(0f, y, f, y, p) }
            }
            Style.ACOUSTIC -> { // overlapping leaves
                for (i in 0 until 7) { p.color = Color.argb(110, 255, 255, 255 - r.nextInt(80)); c.drawOval(f * rf(0f, 0.7f), f * rf(0f, 0.7f), f * rf(0.3f, 1f), f * rf(0.3f, 1f), p) }
            }
            Style.CHILL -> { // moon, horizon, stars
                p.color = 0x22FFFFFF; c.drawRect(0f, f * 0.7f, f, f, p)
                p.color = 0xFFF1F5F9.toInt(); c.drawCircle(f * rf(0.6f, 0.8f), f * rf(0.2f, 0.35f), f * 0.07f, p)
                for (i in 0 until 40) { p.color = Color.argb(80 + r.nextInt(150), 255, 255, 255); c.drawCircle(f * r.nextFloat(), f * 0.65f * r.nextFloat(), f * 0.003f, p) }
            }
        }
        return bmp
    }
}

/** Prev / play / pause / next glyphs drawn as paths (no reliance on emoji fonts in the car). */
class TransportIcon(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    enum class Kind { PREV, PLAY, PAUSE, NEXT }
    var kind = Kind.PLAY
        set(v) { field = v; invalidate() }
    var fg = Color.WHITE
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val s = minOf(w, h) * 0.32f
        val cx = w / 2; val cy = h / 2
        p.color = fg; path.reset()
        when (kind) {
            Kind.PLAY -> { path.moveTo(cx - s * 0.7f, cy - s); path.lineTo(cx + s, cy); path.lineTo(cx - s * 0.7f, cy + s); path.close(); c.drawPath(path, p) }
            Kind.PAUSE -> { c.drawRoundRect(cx - s * 0.75f, cy - s, cx - s * 0.2f, cy + s, 6f, 6f, p); c.drawRoundRect(cx + s * 0.2f, cy - s, cx + s * 0.75f, cy + s, 6f, 6f, p) }
            Kind.NEXT, Kind.PREV -> {
                val d = if (kind == Kind.NEXT) 1f else -1f
                path.moveTo(cx - d * s, cy - s * 0.8f); path.lineTo(cx + d * s * 0.5f, cy); path.lineTo(cx - d * s, cy + s * 0.8f); path.close()
                c.drawPath(path, p)
                c.drawRect(minOf(cx + d * s * 0.5f, cx + d * s * 0.8f), cy - s * 0.8f, maxOf(cx + d * s * 0.5f, cx + d * s * 0.8f), cy + s * 0.8f, p)
            }
        }
    }
}
