package com.drivejam

import kotlin.math.abs

/** Valence (mood) / arousal (energy) point, both in [-1, 1]. See docs/design-music-engine.md. */
data class Mood(val v: Float, val a: Float)

enum class Company(val label: String, val target: Mood?) {
    SOLO("혼자", null),
    FRIENDS("친구들", Mood(0.6f, 0.5f)),
    PARTNER("연인", Mood(0.5f, 0.0f)),
    FAMILY("가족", Mood(0.5f, -0.1f)),
}

enum class Feeling(val label: String, val mood: Mood?) {
    AUTO("알아서", null),
    TIRED("피곤해", Mood(-0.1f, -0.6f)),
    ANNOYED("짜증나", Mood(-0.6f, 0.6f)),
    GOOD("좋아", Mood(0.6f, 0.5f)),
    CALM("평온해", Mood(0.4f, -0.3f)),
}

/** What the car tells us about the moment. driveMode: -1 eco, 0 normal, 1 sport. */
data class Situation(
    val hour: Int,
    val weekday: Boolean,
    val rain: Boolean,
    val night: Boolean,
    val driveMode: Int = 0,
)

data class MusicParams(
    val bpm: Int = 96,
    val layers: Int = 3,
    val brightness: Float = 0.5f,
    val scale: Scale = Scale.DORIAN,
    val muffled: Boolean = false,
)

object MoodConductor {
    // Safety envelope: a boundary, not a goal (decisions.md #17).
    const val A_MIN = -0.3f
    const val A_MAX = 0.6f
    const val BPM_CAP = 128

    private fun clampSafe(m: Mood) = Mood(m.v.coerceIn(-1f, 1f), m.a.coerceIn(A_MIN, A_MAX))

    private fun morning(s: Situation) = s.weekday && s.hour in 6..9
    private fun evening(s: Situation) = s.weekday && s.hour in 17..20

    /** Prior for the driver's state when they pick "알아서". */
    fun prior(s: Situation): Mood = when {
        morning(s) -> Mood(0.2f, -0.4f)
        evening(s) -> Mood(0.0f, 0.2f)
        else -> Mood(0.3f, 0.0f)
    }.let { if (s.rain) Mood(it.v - 0.1f, it.a - 0.1f) else it }

    /** Anger is matched but never amplified. */
    fun clampMatch(m: Mood): Mood = if (m.v < 0f && m.a > 0.4f) m.copy(a = 0.3f) else m

    fun start(feeling: Feeling, s: Situation): Mood = clampMatch(feeling.mood ?: prior(s))

    /** [request] is the listener's "더 신나게 / 더 차분하게" nudge; it still ends inside the safety envelope. */
    fun target(company: Company, s: Situation, request: Mood = Mood(0f, 0f)): Mood {
        var t = company.target ?: when {
            morning(s) -> Mood(0.4f, 0.3f)   // wake up
            evening(s) -> Mood(0.5f, -0.1f)  // unwind
            else -> Mood(0.4f, 0.1f)
        }
        if (s.night) t = t.copy(a = t.a - 0.1f)
        t = t.copy(a = t.a + 0.1f * s.driveMode)
        return clampSafe(Mood(t.v + request.v, t.a + request.a))
    }

    /** Iso-principle trajectory: smoothstep from start to target over durationSec. */
    fun at(start: Mood, target: Mood, elapsedSec: Float, durationSec: Float): Mood {
        val x = (elapsedSec / durationSec).coerceIn(0f, 1f)
        val e = x * x * (3 - 2 * x)
        return clampSafe(Mood(start.v + (target.v - start.v) * e, start.a + (target.a - start.a) * e))
    }

    /** [baseBpm] comes from the playing style (lo-fi 82 … house 122); mood and speed move around it. */
    fun params(m: Mood, speedKmh: Float, baseBpm: Int = 100): MusicParams {
        val highway = speedKmh > 110f
        // Speed nudges tempo by at most ±8 BPM and stops rewarding past 100 km/h.
        val speedMod = (speedKmh.coerceIn(0f, 100f) / 100f) * 16f - 8f
        val bpm = (baseBpm + m.a * 12f + speedMod).toInt().coerceIn(66, BPM_CAP)
        val layers = when {
            m.a < -0.3f -> 2
            m.a < 0f -> 3
            m.a <= 0.3f -> 4
            else -> 5
        }
        val bright = (0.5f + 0.3f * m.a + 0.2f * m.v - if (highway) 0.2f else 0f).coerceIn(0.1f, 1f)
        val scale = when {
            m.v > 0.3f -> Scale.MAJOR
            m.v < -0.3f -> Scale.MINOR
            else -> Scale.DORIAN
        }
        return MusicParams(bpm, layers, bright, scale, muffled = highway)
    }

    fun distance(a: Mood, b: Mood) = abs(a.v - b.v) + abs(a.a - b.a)
}
