package com.drivejam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Renders every style offline: catches silence, NaN/overload, and too-slow synthesis. Also leaves WAVs to listen to. */
class EngineRenderTest {
    private val outDir = File("build/demo-audio").apply { mkdirs() }

    private fun renderStyle(style: Style, seconds: Int, drive: DriveState = DriveState()): Pair<ShortArray, Double> {
        val e = MusicEngine(drive)
        e.load(Track(style, key = 0, seed = 42))
        e.params = MusicConductorDefaults.forStyle(style)
        val n = 512
        val total = 44100 * seconds
        val out = ShortArray(total * 2)
        val buf = ShortArray(n * 2)
        val t0 = System.nanoTime()
        var pos = 0
        while (pos < total) {
            e.render(buf, n)
            System.arraycopy(buf, 0, out, pos * 2, minOf(n, total - pos) * 2)
            pos += n
        }
        val realtime = seconds / ((System.nanoTime() - t0) / 1e9)
        return out to realtime
    }

    private fun writeWav(f: File, pcm: ShortArray) {
        val data = pcm.size * 2
        val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(44100).putInt(44100 * 4).putShort(4).putShort(16)
        b.put("data".toByteArray()).putInt(data)
        pcm.forEach { b.putShort(it) }
        f.writeBytes(b.array())
    }

    @Test
    fun everyStyleSoundsAndRendersFastEnough() {
        for (s in Style.entries) {
            val (pcm, realtime) = renderStyle(s, 40)
            writeWav(File(outDir, "${s.name.lowercase()}.wav"), pcm)
            val rms = Math.sqrt(pcm.map { it.toDouble() * it }.average()) / 32768
            val clipped = pcm.count { it >= 31900 || it <= -31900 }.toDouble() / pcm.size
            println("%-10s rms=%.3f clipped=%.4f realtime x%.1f".format(s.name, rms, clipped, realtime))
            assertTrue("$s silent", rms > 0.03)
            assertTrue("$s overloaded", clipped < 0.01)
            assertTrue("$s too slow", realtime > 3) // the emulator is several times slower than a desktop JVM
        }
    }

    @Test
    fun songFormAndMelodyResolve() {
        assertEquals(Song.Section.INTRO, Song.section(0))
        assertEquals(Song.Section.VERSE, Song.section(2))
        assertEquals(Song.Section.CHORUS, Song.section(6))
        assertEquals(0, Song.melodyBar(6))
        assertEquals(Song.Section.BREAK, Song.section(14))
        assertEquals(Song.Section.CHORUS2, Song.section(18))
        assertEquals(7, Song.melodyBar(25))
        assertEquals(Song.Section.VERSE, Song.section(26))
        assertTrue(Song.fillBar(5))
        val m = Song.melody(7)
        assertEquals(128, m.size)
        assertEquals(0, m.last { it >= 0 }) // the answer phrase lands on the root
    }
}

/** Typical mid-journey parameters for a style (what the conductor would send). */
object MusicConductorDefaults {
    fun forStyle(s: Style) = MoodConductor.params(Mood(0.4f, 0.3f), 40f, s.baseBpm)
}
