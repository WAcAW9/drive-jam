package com.drivejam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoodConductorTest {
    private val weekendNoon = Situation(hour = 12, weekday = false, rain = false, night = false)

    @Test
    fun angerIsMatchedButNotAmplified() {
        val s = MoodConductor.start(Feeling.ANNOYED, weekendNoon)
        assertEquals(0.3f, s.a, 1e-6f)
    }

    @Test
    fun trajectoryAndTargetsStayInsideSafetyEnvelope() {
        for (c in Company.entries) for (f in Feeling.entries) for (mode in -1..1) {
            val sit = weekendNoon.copy(driveMode = mode, night = mode == 1)
            val start = MoodConductor.start(f, sit)
            val target = MoodConductor.target(c, sit)
            for (t in 0..10) {
                val m = MoodConductor.at(start, target, t * 60f, 480f)
                assertTrue("$c/$f a=${m.a}", m.a in MoodConductor.A_MIN..MoodConductor.A_MAX)
                for (speed in listOf(0f, 60f, 200f)) {
                    assertTrue(MoodConductor.params(m, speed).bpm <= MoodConductor.BPM_CAP)
                }
            }
        }
    }

    @Test
    fun trajectoryEndsAtTarget() {
        val start = MoodConductor.start(Feeling.TIRED, weekendNoon)
        val target = MoodConductor.target(Company.FRIENDS, weekendNoon)
        assertEquals(target, MoodConductor.at(start, target, 999f, 480f))
    }
}
