package com.drivejam

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/** Chord progressions (semitones from A3). One chord per bar. */
enum class Scale(val label: String, val chords: Array<IntArray>) {
    MAJOR("장조", arrayOf(intArrayOf(0, 4, 7), intArrayOf(7, 11, 14), intArrayOf(9, 12, 16), intArrayOf(5, 9, 12))),
    DORIAN("도리안", arrayOf(intArrayOf(0, 3, 7), intArrayOf(5, 9, 12), intArrayOf(0, 3, 7), intArrayOf(-2, 2, 5))),
    MINOR("단조", arrayOf(intArrayOf(0, 3, 7), intArrayOf(-4, 0, 3), intArrayOf(3, 7, 10), intArrayOf(-2, 2, 5))),
}

/**
 * Rule-based generative engine: a 16-step sequencer + a few synth voices rendered on one audio thread.
 * Car signals are read live from [drive]; mood-level [params] are applied only on bar boundaries.
 */
class MusicEngine(private val drive: DriveState) {
    @Volatile var params = MusicParams()
    @Volatile var gain = 1f // audio-focus ducking; the app never raises system volume
    @Volatile private var running = false
    private var thread: Thread? = null

    /** Demo only: also write the rendered audio to this WAV file. */
    @Volatile var recordTo: java.io.File? = null
    /** Current sequencer step (0-15), for the demo visualizer. */
    @Volatile var stepNow = 0
        private set

    val isPlaying get() = running

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "DriveJamAudio").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
    }

    // ---------------- audio thread only ----------------
    private val sr = 44100
    private val dt = 1f / sr
    private var cur = MusicParams()
    private var samplesPerStep = 1
    private var sampleInStep = 0
    private var step = -1
    private var chord = -1

    // Per-sample decay factors (multiplying beats calling exp() every sample on a slow emulator CPU).
    private val kickFreqK = exp(-28f / 44100f); private val kickAmpK = exp(-7f / 44100f)
    private val snK = exp(-18f / 44100f); private val bassK = exp(-4f / 44100f); private val dropK = exp(-60f / 44100f)
    private var kickF = 0f; private var kickA = 0f; private var snA = 0f; private var hatA = 0f; private var hatK = 1f
    private var bassA = 0f; private var dropA = 0f
    private var seed = 0x2545F491
    /** xorshift white noise in [-1, 1]; java.util.Random is too slow per sample. */
    private fun noise(): Float {
        seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5)
        return seed * 4.656613e-10f
    }
    private fun softClip(x: Float) = if (x > 3f) 1f else if (x < -3f) -1f else x * (27f + x * x) / (27f + 9f * x * x)

    private var kickT = 9f; private var kickPh = 0.0
    private var snT = 9f
    private var hatT = 9f; private var hatAmp = 0f; private var hatPan = 0f; private var hatDecay = 70f; private var hatLp = 0f
    private var bassT = 9f; private var bassPh = 0.0; private var bassHz = 55.0; private var bassLp = 0f
    private val padPh = DoubleArray(6); private val padHz = DoubleArray(6)
    private var padLp1 = 0f; private var padLp2 = 0f; private var padLevel = 0f
    private var rainLp = 0f; private var dropT = 9f; private var dropHz = 3000f; private var dropPan = 0f
    private var cutoff = 0.5f; private var feedback = 0.25f; private var pitchMul = 1.0
    private val dlL = FloatArray(sr); private val dlR = FloatArray(sr); private var dlPos = 0

    private val bassSteps = booleanArrayOf(true, false, false, true, false, false, true, false, true, false, true, false, false, false, true, false)

    private fun loop() {
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sr)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(max(minBuf, 8192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val n = 512
        val buf = ShortArray(n * 2)
        val bytes = java.nio.ByteBuffer.allocate(n * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val wav = recordTo?.let { f -> java.io.RandomAccessFile(f, "rw").apply { setLength(0); write(ByteArray(44)) } }
        padLevel = 0f
        track.play()
        if (wav != null) android.util.Log.i("DriveJam", "wav start ${System.currentTimeMillis()}")
        while (running) {
            render(buf, n)
            track.write(buf, 0, buf.size)
            if (wav != null) {
                bytes.clear(); bytes.asShortBuffer().put(buf); wav.write(bytes.array())
            }
        }
        track.pause(); track.flush(); track.release()
        if (wav != null) android.util.Log.i("DriveJam", "wav end ${System.currentTimeMillis()}")
        wav?.run { writeWavHeader(this); close() }
    }

    private fun writeWavHeader(f: java.io.RandomAccessFile) {
        val data = (f.length() - 44).toInt()
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(sr).putInt(sr * 4).putShort(4).putShort(16)
        h.put("data".toByteArray()).putInt(data)
        f.seek(0); f.write(h.array())
    }

    private fun setChord() {
        val c = cur.scale.chords[chord]
        for (k in 0 until 3) {
            val hz = 220.0 * Math.pow(2.0, c[k] / 12.0)
            padHz[2 * k] = hz * 0.997
            padHz[2 * k + 1] = hz * 1.003
        }
        var r = ((c[0] % 12) + 12) % 12
        if (r > 7) r -= 12
        bassHz = 55.0 * Math.pow(2.0, r / 12.0)
    }

    private fun trigHat(amp: Float, pan: Float, decay: Float) {
        hatT = 0f; hatAmp = amp; hatPan = pan; hatDecay = decay; hatA = 1f; hatK = exp(-decay / sr)
    }

    private fun onStep(scratching: Boolean) {
        step = (step + 1) % 16
        stepNow = step
        if (step == 0) { // bar boundary: apply mood-level changes here only
            cur = params
            chord = (chord + 1) % 4
            setChord()
            samplesPerStep = (sr * 60.0 / cur.bpm / 4).toInt()
        }
        if (scratching) return
        val l = cur.layers
        if (l >= 2 && (step == 0 || step == 8 || (l >= 4 && (step == 4 || step == 12)))) { kickT = 0f; kickPh = 0.0; kickF = 1f; kickA = 1f }
        if (l >= 3 && (step == 4 || step == 12)) { snT = 0f; snA = 1f }
        if ((l >= 4 && step % 4 == 2) || (l >= 5 && step % 2 == 0)) trigHat(0.35f, 0f, 70f)
        val turn = drive.turn
        if (turn != 0) trigHat(if (step % 4 == 0) 0.9f else 0.5f, 0.8f * turn, 45f) // blinker hi-hat, panned to its side
        if (l >= 2 && bassSteps[step]) { bassT = 0f; bassA = 1f }
    }

    private fun render(out: ShortArray, n: Int) {
        val now = System.currentTimeMillis()
        val scratching = drive.scratchUntil > now
        val steer = drive.steer.coerceIn(-1f, 1f)
        val rain = drive.rain
        val targetCut = ((cur.brightness * 0.8f + abs(steer) * 0.5f) * if (cur.muffled) 0.6f else 1f).coerceIn(0.05f, 1f)
        val targetFb = if (drive.brake) 0.62f else 0.25f // brake = the car "holds its breath": echoes linger
        val targetPitch = if (scratching) 0.55 else 1.0
        val padPan = steer * 0.6f
        val delayLen = (samplesPerStep * 3).coerceIn(1, sr - 1)
        val g = gain
        for (i in 0 until n) {
            if (++sampleInStep >= samplesPerStep) { sampleInStep = 0; onStep(scratching) }
            cutoff += (targetCut - cutoff) * 0.0005f
            feedback += (targetFb - feedback) * 0.0002f
            pitchMul += (targetPitch - pitchMul) * 0.0004
            if (padLevel < 1f) padLevel += 0.00001f

            var drums = 0f
            if (kickT < 0.5f) {
                kickPh += (45f + 110f * kickF) * dt
                drums += sin(2 * PI * kickPh).toFloat() * kickA * 0.9f
                kickF *= kickFreqK; kickA *= kickAmpK; kickT += dt
            }
            if (snT < 0.3f) {
                drums += noise() * snA * 0.3f
                snA *= snK; snT += dt
            }
            var hat = 0f
            if (hatT < 0.25f) {
                val w = noise()
                hatLp += (w - hatLp) * 0.35f
                hat = (w - hatLp) * hatAmp * hatA * 0.5f
                hatA *= hatK; hatT += dt
            }
            var bass = 0f
            if (bassT < 0.6f) {
                bassPh += bassHz * pitchMul * dt
                if (bassPh >= 1.0) bassPh -= 1.0
                val saw = (2 * bassPh - 1).toFloat()
                bassLp += (saw - bassLp) * 0.06f
                bass = bassLp * bassA * 0.45f
                bassA *= bassK; bassT += dt
            }
            var pad = 0f
            for (k in 0 until 6) {
                padPh[k] += padHz[k] * pitchMul * dt
                if (padPh[k] >= 1.0) padPh[k] -= 1.0
                pad += (2 * padPh[k] - 1).toFloat()
            }
            val coef = 0.01f + cutoff * cutoff * 0.35f
            padLp1 += (pad * 0.05f - padLp1) * coef
            padLp2 += (padLp1 - padLp2) * coef
            val padOut = padLp2 * padLevel

            var rainL = 0f; var rainR = 0f
            if (rain > 0f) {
                val w = noise()
                rainLp += (w - rainLp) * 0.2f
                rainL = rainLp * 0.05f * rain; rainR = rainL
                if (dropT > 0.08f && noise() > 1f - 0.0006f * rain) {
                    dropT = 0f; dropA = 1f; dropHz = 3000f + noise() * 1200f; dropPan = noise()
                }
            }
            if (dropT < 0.08f) {
                val d = sin(2 * PI * dropHz * dropT).toFloat() * dropA * 0.12f
                dropA *= dropK
                rainL += d * (1 - dropPan); rainR += d * (1 + dropPan)
                dropT += dt
            }

            // Ping-pong delay as the "space" of the track.
            val rp = (dlPos - delayLen + sr) % sr
            val dL = dlL[rp]; val dR = dlR[rp]
            val send = padOut * 0.6f + hat * 0.5f
            dlL[dlPos] = send + dR * feedback
            dlR[dlPos] = dL * feedback
            dlPos = (dlPos + 1) % sr

            var l = drums * 0.8f + bass + padOut * (1 - padPan) + hat * (1 - hatPan) + rainL + dL * 0.35f
            var r = drums * 0.8f + bass + padOut * (1 + padPan) + hat * (1 + hatPan) + rainR + dR * 0.35f
            if (scratching && (sampleInStep * 4 / samplesPerStep) % 2 == 1) { l *= 0.25f; r *= 0.25f } // stutter
            out[2 * i] = (softClip(l * g * 0.9f) * 32000f).toInt().toShort()
            out[2 * i + 1] = (softClip(r * g * 0.9f) * 32000f).toInt().toShort()
        }
    }
}
