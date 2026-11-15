package com.drivejam

/** Sunrise / sunset by month (Seoul, mid-month, local hours). ponytail: one city's table; use location once FusedLocation opens. */
object Daylight {
    private val SUNRISE = floatArrayOf(7.7f, 7.4f, 6.9f, 6.2f, 5.6f, 5.2f, 5.3f, 5.7f, 6.1f, 6.6f, 7.1f, 7.6f)
    private val SUNSET = floatArrayOf(17.6f, 18.1f, 18.6f, 19.0f, 19.4f, 19.9f, 19.9f, 19.5f, 18.8f, 18.1f, 17.4f, 17.3f)

    /** [month] 0..11 (Calendar.MONTH), [hour] fractional local hour. */
    fun isDay(month: Int, hour: Float) = hour in SUNRISE[month] + 0.5f..SUNSET[month] - 0.5f

    /** The last hour before sunset and the first 20 minutes after. */
    fun isSunset(month: Int, hour: Float) = hour in SUNSET[month] - 1f..SUNSET[month] + 0.33f
}

/**
 * Stop-and-go traffic: in D, the last 30 s averaged under 20 km/h with 3+ brake presses.
 * Clears once speed picks up past 35 km/h ("길이 뚫렸다").
 */
class TrafficDetector {
    var active = false; private set
    var clearedAt = 0L; private set
    private var lastBrake = false
    private val presses = ArrayDeque<Long>()
    private val speeds = ArrayDeque<Pair<Long, Float>>()

    fun update(now: Long, speedKmh: Float, brake: Boolean, driving: Boolean): Boolean {
        if (brake && !lastBrake) presses.addLast(now)
        lastBrake = brake
        speeds.addLast(now to speedKmh)
        while (presses.isNotEmpty() && now - presses.first() > WINDOW) presses.removeFirst()
        while (now - speeds.first().first > WINDOW) speeds.removeFirst()
        val filled = now - speeds.first().first >= WINDOW * 8 / 10
        if (!active && driving && filled && presses.size >= 3 && speeds.sumOf { it.second.toDouble() } / speeds.size < 20.0) {
            active = true
        } else if (active && (!driving || speedKmh > 35f)) {
            active = false
            if (driving) clearedAt = now
        }
        return active
    }

    companion object { const val WINDOW = 30_000L }
}

/** Collectible moments. Nothing here can be earned by speed, hard acceleration or hard braking (decisions.md #25). */
enum class Stamp(val label: String) {
    RAIN("빗길 드라이브"),
    SUNSET("해 질 녘 드라이브"),
    TUNNEL("터널 사운드"),
    NIGHT("밤 드라이브"),
    TOGETHER("함께 탄 차"),
    TRAFFIC("정체도 음악으로"),
    HARMONY("화음 · 급제동 없이"),
}

/** What one drive leaves behind. Speed is deliberately not recorded. */
class Trip(val startedAt: Long, val company: Company) {
    var km = 0f
    var songs = 1
    var tunnels = 0
    var hardBrakes = 0
    var rain = false
    var sunset = false
    var night = false
    var traffic = false
}

data class TripCard(
    val title: String,
    val stats: String,
    val type: String,
    val typeLine: String,
    val stamps: List<Stamp>,
    val newStamps: List<Stamp>,
    val owned: Int,
)

object TripCards {
    fun stamps(t: Trip): List<Stamp> = buildList {
        if (t.rain) add(Stamp.RAIN)
        if (t.sunset) add(Stamp.SUNSET)
        if (t.tunnels > 0) add(Stamp.TUNNEL)
        if (t.night) add(Stamp.NIGHT)
        if (t.company != Company.SOLO) add(Stamp.TOGETHER)
        if (t.traffic) add(Stamp.TRAFFIC)
        if (t.hardBrakes == 0 && t.km >= 1f) add(Stamp.HARMONY) // no penalty: a rough day just doesn't earn this one
    }

    /** Every type is a compliment; none ranks above another, so there is nothing to chase by driving harder. */
    private fun type(t: Trip) = when {
        t.rain -> "앰비언트형 드라이버" to "빗길에서도 흐름이 고요했어요"
        t.night -> "재즈형 드라이버" to "밤길을 여유로운 리듬으로 달렸어요"
        t.company != Company.SOLO -> "시티팝형 드라이버" to "함께 탄 사람들과 경쾌하게 흘렀어요"
        else -> "클래식형 드라이버" to "처음부터 끝까지 고르게 이어졌어요"
    }

    fun card(t: Trip, endedAt: Long, owned: Set<Stamp>): TripCard {
        val min = ((endedAt - t.startedAt) / 60_000).toInt().coerceAtLeast(1)
        val who = when (t.company) {
            Company.SOLO -> "혼자"; Company.FRIENDS -> "친구들과"; Company.PARTNER -> "둘이서"; Company.FAMILY -> "가족과"
        }
        val scene = when {
            t.sunset -> "해 질 녘"; t.rain -> "비 오는 길"; t.night -> "밤길"; t.tunnels > 0 -> "터널 지나"; else -> "오늘의 길"
        }
        val earned = stamps(t)
        val (type, line) = type(t)
        return TripCard(
            title = "$who, $scene ${min}분",
            stats = String.format(java.util.Locale.US, "%.1f km · %d분 · %d곡", t.km, min, t.songs),
            type = type, typeLine = line,
            stamps = earned, newStamps = earned.filter { it !in owned },
            owned = (owned + earned).size,
        )
    }
}
