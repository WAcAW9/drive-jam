package com.drivejam

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/** Chord progressions (semitones from the tonic, one chord per bar). A track picks one by its seed. */
enum class Scale(val label: String, val progressions: Array<Array<IntArray>>) {
    MAJOR("장조", arrayOf(
        arrayOf(intArrayOf(0, 4, 7), intArrayOf(7, 11, 14), intArrayOf(9, 12, 16), intArrayOf(5, 9, 12)),  // I V vi IV
        arrayOf(intArrayOf(0, 4, 7), intArrayOf(9, 12, 16), intArrayOf(5, 9, 12), intArrayOf(7, 11, 14)),  // I vi IV V
        arrayOf(intArrayOf(5, 9, 12), intArrayOf(7, 11, 14), intArrayOf(4, 7, 11), intArrayOf(9, 12, 16)), // IV V iii vi
    )),
    DORIAN("도리안", arrayOf(
        arrayOf(intArrayOf(0, 3, 7), intArrayOf(5, 9, 12), intArrayOf(0, 3, 7), intArrayOf(-2, 2, 5)),  // i IV i VII
        arrayOf(intArrayOf(0, 3, 7), intArrayOf(-2, 2, 5), intArrayOf(5, 9, 12), intArrayOf(0, 3, 7)),  // i VII IV i
        arrayOf(intArrayOf(2, 5, 9), intArrayOf(5, 9, 12), intArrayOf(0, 3, 7), intArrayOf(0, 3, 7)),   // ii IV i i
    )),
    MINOR("단조", arrayOf(
        arrayOf(intArrayOf(0, 3, 7), intArrayOf(-4, 0, 3), intArrayOf(3, 7, 10), intArrayOf(-2, 2, 5)), // i VI III VII
        arrayOf(intArrayOf(0, 3, 7), intArrayOf(5, 8, 12), intArrayOf(-2, 2, 5), intArrayOf(3, 7, 10)), // i iv VII III
        arrayOf(intArrayOf(0, 3, 7), intArrayOf(-2, 2, 5), intArrayOf(-4, 0, 3), intArrayOf(-2, 2, 5)), // i VII VI VII
    )),
}

/**
 * Rule-based generative engine: a 16-step sequencer + a few synth voices rendered on one audio thread.
 * The [Track] picks the style (patterns, timbre, key); mood-level [params] and track changes apply on bar
 * boundaries; car signals are read live from [drive] and play on top of every style.
 */
class MusicEngine(private val drive: DriveState) {
    @Volatile var params = MusicParams()
    @Volatile var gain = 1f // audio-focus ducking; the app never raises system volume
    /** Set while playing to cross-fade into another track (1 bar out with a filter sweep, 2 bars in). */
    @Volatile var pendingTrack: Track? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    /** Demo only: also write the rendered audio to this WAV file. */
    @Volatile var recordTo: java.io.File? = null
    /** Current sequencer step (0-15), for the demo visualizer. */
    @Volatile var stepNow = 0
        private set

    val isPlaying get() = running

    /** Load the first track (call while stopped). */
    fun load(t: Track) {
        track = t; pendingTrack = null; step = -1; chord = -1; samplesPerStep = 1; xf = 1f
    }

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
    private var track = Track(Style.LOFI, 0, 0)
    private var cur = MusicParams()
    private var samplesPerStep = 1
    private var sampleInStep = 0
    private var step = -1
    private var chord = -1
    private var xf = 1f        // track cross-fade gain
    private var xfDir = 0      // -1 fading out, +1 fading in

    // Per-sample decay factors (multiplying beats calling exp() every sample on a slow emulator CPU).
    private val kickFreqK = exp(-28f / sr); private val kickAmpK = exp(-7f / sr)
    private val snK = exp(-18f / sr); private val bassK = exp(-4f / sr); private val dropK = exp(-60f / sr)
    private val stabK = exp(-6f / sr); private val pluckK = exp(-3.5f / sr); private val arpK = exp(-9f / sr)
    private var kickF = 0f; private var kickA = 0f; private var snA = 0f; private var hatA = 0f; private var hatK = 1f
    private var bassA = 0f; private var dropA = 0f; private var padEnv = 1f; private var padK = 1f
    private var seed = 0x2545F491

    /** xorshift white noise in [-1, 1]; java.util.Random is too slow per sample. */
    private fun noise(): Float {
        seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5)
        return seed * 4.656613e-10f
    }

    private fun softClip(x: Float) = if (x > 3f) 1f else if (x < -3f) -1f else x * (27f + x * x) / (27f + 9f * x * x)

    private var kickT = 9f; private var kickPh = 0.0
    private var snT = 9f
    private var hatT = 9f; private var hatAmp = 0f; private var hatPan = 0f; private var hatLp = 0f
    private var bassT = 9f; private var bassPh = 0.0; private var bassHz = 55.0; private var bassLp = 0f
    private val padPh = DoubleArray(8); private val padHz = DoubleArray(8); private var padOsc = 6
    private val chordHz = DoubleArray(4); private var chordN = 3; private var arpIdx = 0
    private var padLp1 = 0f; private var padLp2 = 0f; private var padLevel = 0f
    private var rainLp = 0f; private var dropT = 9f; private var dropHz = 3000f; private var dropPan = 0f
    private var hissLp = 0f
    private var cutoff = 0.5f; private var feedback = 0.25f; private var pitchMul = 1.0
    private val dlL = FloatArray(sr); private val dlR = FloatArray(sr); private var dlPos = 0

    private fun loop() {
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val out = AudioTrack.Builder()
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
        out.play()
        if (wav != null) android.util.Log.i("DriveJam", "wav start ${System.currentTimeMillis()}")
        while (running) {
            render(buf, n)
            out.write(buf, 0, buf.size)
            if (wav != null) {
                bytes.clear(); bytes.asShortBuffer().put(buf); wav.write(bytes.array())
            }
        }
        out.pause(); out.flush(); out.release()
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

    private fun hz(semi: Int, base: Double) = base * Math.pow(2.0, (semi + track.key) / 12.0)

    private fun setChord() {
        val c = cur.scale.progressions[track.variant][chord]
        chordN = if (track.style.add9) 4 else 3
        for (k in 0 until 3) chordHz[k] = hz(c[k], 220.0)
        if (chordN == 4) chordHz[3] = hz(c[0] + 14, 220.0) // add9 colour
        // Sustained/stab chords: two detuned oscillators per note for triads, one per note for 4-note chords.
        // (PLUCK/ARP set their single note per step in onStep.)
        if (track.style.padMode == PadMode.SUSTAIN || track.style.padMode == PadMode.STAB) {
            padOsc = if (chordN == 3) 6 else 4
            for (o in 0 until padOsc) {
                val note = if (chordN == 3) o / 2 else o
                padHz[o] = chordHz[note] * if (chordN == 3) (if (o % 2 == 0) 0.997 else 1.003) else 1.0
            }
        }
        var r = ((c[0] + track.key) % 12 + 12) % 12
        if (r > 7) r -= 12
        bassHz = 55.0 * Math.pow(2.0, r / 12.0)
    }

    private fun trigHat(amp: Float, pan: Float, decay: Float) {
        hatT = 0f; hatAmp = amp; hatPan = pan; hatA = 1f; hatK = exp(-decay / sr)
    }

    private fun onStep(scratching: Boolean) {
        step = (step + 1) % 16
        stepNow = step
        val st = track.style
        if (step == 0) { // bar boundary: apply mood-level changes here only
            cur = params
            chord = (chord + 1) % 4
            setChord()
        }
        val base = sr * 60.0 / cur.bpm / 4
        samplesPerStep = (base * if (step % 2 == 0) 1 + st.swing else 1 - st.swing).toInt().coerceAtLeast(1)
        if (scratching) return
        val l = cur.layers
        if (l >= 2 && st.hit(st.kick, step)) { kickT = 0f; kickPh = 0.0; kickF = 1f; kickA = 1f }
        if (l >= 3 && st.hit(st.snare, step)) { snT = 0f; snA = 1f }
        if (l >= 4 && st.hit(st.hat, step)) trigHat(0.3f, 0f, 70f)
        if (l >= 5 && st.hit(st.openHat, step)) trigHat(0.35f, 0f, 14f)
        val turn = drive.turn
        if (turn != 0) trigHat(if (step % 4 == 0) 0.9f else 0.5f, 0.8f * turn, 45f) // blinker hi-hat, panned to its side
        if (l >= 2 && st.hit(st.bass, step)) { bassT = 0f; bassA = 1f }
        when (st.padMode) {
            PadMode.SUSTAIN -> { padEnv = 1f; padK = 1f }
            PadMode.STAB -> if (st.hit(st.comp, step)) { padEnv = 1f; padK = stabK }
            PadMode.PLUCK -> if (st.hit(st.comp, step)) {
                padEnv = 1f; padK = pluckK
                padOsc = 1; padHz[0] = chordHz[arpIdx % chordN] * if (arpIdx / chordN % 2 == 1) 2.0 else 1.0
                arpIdx = (arpIdx + 1) % (chordN * 2)
            }
            PadMode.ARP -> {
                padEnv = 1f; padK = arpK
                val i = step % (chordN * 2)
                val f = chordHz[i % chordN] * if (i >= chordN) 2.0 else 1.0
                padOsc = 2; padHz[0] = f * 0.996; padHz[1] = f * 1.004
            }
        }
    }

    private fun render(outBuf: ShortArray, n: Int) {
        val now = System.currentTimeMillis()
        val scratching = drive.scratchUntil > now
        val steer = drive.steer.coerceIn(-1f, 1f)
        val rain = drive.rain
        val st = track.style
        if (pendingTrack != null && xfDir >= 0) xfDir = -1
        val targetCut = ((cur.brightness * 0.8f + abs(steer) * 0.5f) * (if (cur.muffled) 0.6f else 1f) *
            (if (xfDir < 0) 0.3f + 0.7f * xf else 1f)).coerceIn(0.05f, 1f)
        val targetFb = if (drive.brake) 0.62f else 0.15f + st.space * 0.4f // brake = the car "holds its breath"
        val wet = 0.2f + st.space * 0.35f
        val targetPitch = if (scratching) 0.55 else 1.0
        val padPan = steer * 0.6f
        val delayLen = (samplesPerStep * 3).coerceIn(1, sr - 1)
        val g = gain
        val tri = st.wave == Wave.TRI
        for (i in 0 until n) {
            if (++sampleInStep >= samplesPerStep) { sampleInStep = 0; onStep(scratching) }
            cutoff += (targetCut - cutoff) * 0.0005f
            feedback += (targetFb - feedback) * 0.0002f
            pitchMul += (targetPitch - pitchMul) * 0.0004
            if (padLevel < 1f) padLevel += 0.00001f
            val barLen = samplesPerStep * 16f
            if (xfDir < 0) {
                xf -= 1f / barLen
                if (xf <= 0f) { // swap tracks at silence and restart the bar
                    xf = 0f; xfDir = 1
                    pendingTrack?.let { track = it }
                    pendingTrack = null
                    step = -1; chord = -1; arpIdx = 0; sampleInStep = samplesPerStep
                }
            } else if (xfDir > 0) {
                xf += 1f / (2 * barLen)
                if (xf >= 1f) { xf = 1f; xfDir = 0 }
            }

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
            if (hatT < 0.4f) {
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
            for (k in 0 until padOsc) {
                padPh[k] += padHz[k] * pitchMul * dt
                if (padPh[k] >= 1.0) padPh[k] -= 1.0
                val ph = padPh[k].toFloat()
                pad += if (tri) 2f * abs(2f * ph - 1f) - 1f else 2f * ph - 1f
            }
            padEnv *= padK
            val voices = 6f / padOsc.coerceAtLeast(1) // keep loudness similar across modes
            val coef = 0.01f + cutoff * cutoff * 0.35f
            padLp1 += (pad * 0.05f * voices.coerceAtMost(3f) * (if (tri) 1.6f else 1f) - padLp1) * coef
            padLp2 += (padLp1 - padLp2) * coef
            val padOut = padLp2 * padLevel * padEnv

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
            var vinyl = 0f
            if (st.crackle) { // lo-fi record crackle + hiss
                hissLp += (noise() - hissLp) * 0.05f
                vinyl = hissLp * 0.02f + if (noise() > 0.9992f) noise() * 0.2f else 0f
            }

            // Ping-pong delay as the "space" of the track; its tail rings through track changes.
            val rp = (dlPos - delayLen + sr) % sr
            val dL = dlL[rp]; val dR = dlR[rp]
            val send = (padOut * 0.6f + hat * 0.5f) * xf
            dlL[dlPos] = send + dR * feedback
            dlR[dlPos] = dL * feedback
            dlPos = (dlPos + 1) % sr

            val music = (drums * 0.8f + bass + vinyl) * xf
            var l = music + (padOut * (1 - padPan) + hat * (1 - hatPan)) * xf + rainL + dL * wet
            var r = music + (padOut * (1 + padPan) + hat * (1 + hatPan)) * xf + rainR + dR * wet
            if (scratching && (sampleInStep * 4 / samplesPerStep) % 2 == 1) { l *= 0.25f; r *= 0.25f } // stutter
            outBuf[2 * i] = (softClip(l * g * 0.9f) * 32000f).toInt().toShort()
            outBuf[2 * i + 1] = (softClip(r * g * 0.9f) * 32000f).toInt().toShort()
        }
    }
}
