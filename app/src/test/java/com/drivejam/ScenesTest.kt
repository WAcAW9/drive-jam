package com.drivejam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenesTest {
    private val nov = 10

    @Test
    fun daylightAndSunsetInNovember() {
        assertTrue(Daylight.isDay(nov, 12f))
        assertFalse(Daylight.isDay(nov, 20f))
        assertTrue(Daylight.isSunset(nov, 17f))
        assertFalse(Daylight.isSunset(nov, 12f))
        assertFalse(Daylight.isSunset(nov, 21f))
    }

    @Test
    fun trafficNeedsStopAndGoThenClearsWhenSpeedPicksUp() {
        val d = TrafficDetector()
        var t = 0L
        // 30 s crawling at 8 km/h, braking every 8 s
        while (t <= 30_000) { d.update(t, 8f, (t / 1000) % 8 == 0L, driving = true); t += 100 }
        assertTrue(d.active)
        d.update(t, 40f, false, driving = true)
        assertFalse(d.active)
        assertEquals(t, d.clearedAt)
    }

    @Test
    fun noTrafficWhileParkedOrCruising() {
        val parked = TrafficDetector(); val cruising = TrafficDetector()
        var t = 0L
        while (t <= 30_000) {
            val brake = (t / 1000) % 8 == 0L
            parked.update(t, 8f, brake, driving = false)
            cruising.update(t, 60f, brake, driving = true)
            t += 100
        }
        assertFalse(parked.active)
        assertFalse(cruising.active)
    }

    @Test
    fun sceneParams() {
        val p = MusicParams(bpm = 100, layers = 5, brightness = 0.8f)
        val jam = MoodConductor.scene(p, sunset = false, tunnel = false, traffic = true)
        assertTrue(jam.layers <= 2 && jam.bpm < 100 && jam.brightness < 0.8f)
        assertFalse(MoodConductor.scene(p, sunset = true, tunnel = true, traffic = false).sunset) // no sunset inside a tunnel
    }

    @Test
    fun hardBrakesOnlyCostTheHarmonyStampAndNeverChangeTheType() {
        fun trip(brakes: Int) = Trip(0L, Company.FRIENDS).apply { km = 12f; hardBrakes = brakes; sunset = true }
        val smooth = TripCards.card(trip(0), 34 * 60_000L, emptySet())
        val rough = TripCards.card(trip(3), 34 * 60_000L, emptySet())
        assertTrue(Stamp.HARMONY in smooth.stamps)
        assertFalse(Stamp.HARMONY in rough.stamps)
        assertEquals(smooth.type, rough.type)
        assertEquals(smooth.stamps - Stamp.HARMONY, rough.stamps)
        assertEquals("친구들과, 해 질 녘 34분", smooth.title)
    }

    @Test
    fun onlyUnownedStampsAreNew() {
        val t = Trip(0L, Company.SOLO).apply { km = 3f; rain = true }
        val c = TripCards.card(t, 60_000L, setOf(Stamp.RAIN))
        assertEquals(listOf(Stamp.RAIN, Stamp.HARMONY), c.stamps)
        assertEquals(listOf(Stamp.HARMONY), c.newStamps)
        assertEquals(2, c.owned)
    }
}
