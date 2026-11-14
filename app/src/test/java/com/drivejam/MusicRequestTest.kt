package com.drivejam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicRequestTest {
    @Test
    fun rainyCafeMeansCalmLofi() {
        val r = RequestParser.parse("비 오는 날 카페 같은 잔잔한 음악으로")
        assertEquals(Style.LOFI, r.style)
        assertTrue(r.mood.a < 0f)
        assertTrue(r.newTrack)
        assertEquals("더 차분한 로파이", r.summary)
    }

    @Test
    fun dreamySynthwave() = assertEquals(Style.SYNTHWAVE, RequestParser.parse("몽환적인 신스웨이브로").style)

    @Test
    fun clubMeansUpbeatHouse() {
        val r = RequestParser.parse("신나는 클럽 음악 틀어줘")
        assertEquals(Style.HOUSE, r.style)
        assertTrue(r.mood.a > 0f)
    }

    @Test
    fun energyOnlyKeepsTheSong() {
        val r = RequestParser.parse("조금 더 신나게")
        assertNull(r.style)
        assertFalse(r.newTrack)
        assertEquals("더 신나는 분위기", r.summary)
    }

    @Test
    fun somethingElseForcesANewSong() {
        val r = RequestParser.parse("완전 다른 거")
        assertNull(r.style)
        assertTrue(r.newTrack)
    }

    @Test
    fun unknownWordsStillDoSomething() = assertTrue(RequestParser.parse("음 그냥 아무거나").newTrack)

    @Test
    fun stylePatternsAreSixteenSteps() = Style.entries.forEach { s ->
        listOf(s.kick, s.snare, s.hat, s.openHat, s.bass, s.comp).forEach { assertEquals(s.name, 16, it.length) }
    }

    @Test
    fun nextNeverRepeatsTheAvoidedStyle() {
        val sit = Situation(hour = 12, weekday = false, rain = false, night = false)
        val rnd = java.util.Random(1)
        repeat(200) {
            val t = TrackPicker.pick(Company.FRIENDS, sit, { 1f }, setOf(Style.CITY_POP, Style.HOUSE), rnd)
            assertNotEquals(Style.CITY_POP, t.style)
            assertNotEquals(Style.HOUSE, t.style)
        }
        assertEquals(Style.CHILL, TrackPicker.pick(Company.FRIENDS, sit, { 1f }, emptySet(), rnd, forced = Style.CHILL).style)
    }
}
