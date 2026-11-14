package com.drivejam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/**
 * Demo-only visualizer so a video viewer can *see* what changes the music:
 * mood point on the valence/energy plane, the 16-step sequencer, and which car signals are active.
 * ponytail: continuous animation is for the demo video only; the driving UI stays a static now-playing view.
 */
class DemoView(context: Context, private val drive: DriveState, private val engine: MusicEngine) : View(context) {
    @Volatile var mood = Mood(0f, 0f)
    @Volatile var target = Mood(0f, 0f)
    @Volatile var params = MusicParams()

    private val trail = ArrayDeque<Mood>()
    private val accent = Color.parseColor("#4FD1C5")
    private val dim = Color.parseColor("#2A2F3A")
    private val text = Color.parseColor("#C7CED9")
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val pad = h * 0.04f
        val side = min(h - 2 * pad, w * 0.42f)

        // --- valence/energy plane ---
        val x0 = pad; val y0 = pad
        fun px(v: Float) = x0 + (v + 1f) / 2f * side
        fun py(a: Float) = y0 + (1f - (a + 1f) / 2f) * side
        p.style = Paint.Style.FILL; p.color = Color.parseColor("#1B2029")
        r.set(x0, y0, x0 + side, y0 + side); c.drawRoundRect(r, 24f, 24f, p)
        p.color = Color.parseColor("#163A3A") // safety envelope (energy band)
        c.drawRect(x0, py(MoodConductor.A_MAX), x0 + side, py(MoodConductor.A_MIN), p)
        p.color = dim; p.strokeWidth = 2f
        c.drawLine(px(0f), y0, px(0f), y0 + side, p); c.drawLine(x0, py(0f), x0 + side, py(0f), p)
        label(c, "기분 −", x0 + 12, py(0f) - 10, side * 0.045f, text)
        label(c, "기분 +", x0 + side - side * 0.2f, py(0f) - 10, side * 0.045f, text)
        label(c, "에너지 ↑", px(0f) + 10, y0 + side * 0.06f, side * 0.045f, text)
        label(c, "안심 범위", x0 + 12, py(MoodConductor.A_MAX) + side * 0.05f, side * 0.035f, Color.parseColor("#4E8F8A"))

        val m = mood; val t = target
        if (trail.isEmpty() || MoodConductor.distance(trail.last(), m) > 0.003f) { trail.addLast(m); if (trail.size > 400) trail.removeFirst() }
        p.color = Color.parseColor("#3A8F88"); trail.forEach { c.drawCircle(px(it.v), py(it.a), 3f, p) }
        p.style = Paint.Style.STROKE; p.strokeWidth = 5f; p.color = Color.WHITE
        c.drawCircle(px(t.v), py(t.a), side * 0.04f, p)
        label(c, "목표", px(t.v) + side * 0.05f, py(t.a) + 10, side * 0.04f, Color.WHITE)
        p.style = Paint.Style.FILL; p.color = accent
        val step = engine.stepNow
        val beat = if (step % 4 == 0) 1.35f else 1f
        c.drawCircle(px(m.v), py(m.a), side * 0.035f * beat, p)

        // --- right column ---
        val rx = x0 + side + pad * 1.5f
        val rw = w - rx - pad
        val pr = params
        label(c, "${pr.bpm} BPM · ${pr.scale.label}", rx, y0 + h * 0.09f, h * 0.075f, Color.WHITE, bold = true)
        label(c, if (pr.muffled) "레이어 ${pr.layers} · 고속 구간 먹먹하게" else "레이어 ${pr.layers}", rx, y0 + h * 0.15f, h * 0.035f, text)

        // 16-step sequencer
        val cell = rw / 16f
        for (i in 0 until 16) {
            p.color = when { i == step -> accent; i % 4 == 0 -> Color.parseColor("#3A4150"); else -> dim }
            r.set(rx + i * cell + 3, y0 + h * 0.19f, rx + (i + 1) * cell - 3, y0 + h * 0.19f + cell)
            c.drawRoundRect(r, 8f, 8f, p)
        }

        // signal tiles (2 rows x 3)
        val tiles = listOf(
            Tile(when (drive.turn) { -1 -> "◀ 깜빡이"; 1 -> "깜빡이 ▶"; else -> "깜빡이" }, "하이햇", drive.turn != 0 && step % 4 < 2),
            Tile("핸들 ${drive.steerDeg.toInt()}°", "필터·방향", abs(drive.steerDeg) > 15f),
            Tile("브레이크", if (drive.scratchUntil > System.currentTimeMillis()) "급정거 멈칫!" else "메아리", drive.brake),
            Tile("와이퍼", if (drive.rain >= 1f) "빗소리 촘촘" else "빗소리", drive.rain > 0f),
            Tile("전조등", "밤 → 차분하게", drive.night),
            Tile("${drive.speedKmh.toInt()} km/h", "템포 ±8", drive.speedKmh > 1f),
        )
        val ty = y0 + h * 0.19f + cell + pad
        val tw = (rw - 2 * pad * 0.5f) / 3f
        val th = (h - ty - pad * 2.2f) / 2f
        tiles.forEachIndexed { i, tile ->
            val tx = rx + (i % 3) * (tw + pad * 0.5f)
            val tyy = ty + (i / 3) * (th + pad * 0.5f)
            p.color = if (tile.on) accent else dim
            r.set(tx, tyy, tx + tw, tyy + th); c.drawRoundRect(r, 20f, 20f, p)
            val fg = if (tile.on) Color.parseColor("#12151C") else Color.WHITE
            label(c, tile.title, tx + 20, tyy + th * 0.45f, th * 0.24f, fg, bold = true)
            label(c, tile.sub, tx + 20, tyy + th * 0.8f, th * 0.17f, if (tile.on) fg else text)
        }

        postInvalidateDelayed(50) // ~20 fps is plenty for the video and leaves CPU to the audio thread
    }

    private data class Tile(val title: String, val sub: String, val on: Boolean)

    private fun label(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false) {
        p.style = Paint.Style.FILL; p.color = color; p.textSize = size
        p.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        c.drawText(s, x, y, p)
    }
}
