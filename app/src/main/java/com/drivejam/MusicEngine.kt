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
 * Rule-based generative band rendered on one audio thread: modelled 808/909-style drums, an FM electric piano,
 * a plucked string, saw synths, bass and a melody that follows the chords, arranged in sections
 * (intro / verse / chorus / break). The [Track] picks style, key and motif; mood-level [params] and track changes
 * apply on bar boundaries; car signals are read live from [drive] and play on top of every style.
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
    /** Test only: render the backing chorus alone. */
    internal var chorusOnly = false
    /** Current sequencer step (0-15), for the demo visualizer. */
    @Volatile var stepNow = 0
        private set

    val isPlaying get() = running

    /** Load the first track (call while stopped). */
    fun load(t: Track) {
        track = t; melody = Song.melody(t.seed); pendingTrack = null
        step = -1; chord = -1; bar = -1; samplesPerStep = 1; xf = 1f; xfDir = 0
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

    private companion object {
        const val TN = 4096
        val SIN = FloatArray(TN) { sin(2 * PI * it / TN).toFloat() }
        val HATF = doubleArrayOf(533.8, 791.4, 961.0, 1359.0, 1404.0, 2080.0) // 808-style metallic partials
        const val EPV = 8
        const val KSV = 5
        const val KSMAX = 2048
    }

    /** Sine by table lookup; [p] in cycles. */
    private fun sn(p: Double): Float {
        var x = p - p.toLong()
        if (x < 0) x += 1.0
        return SIN[(x * TN).toInt() and (TN - 1)]
    }

    // ---------------- audio thread only ----------------
    private val sr = 44100
    private val dt = 1f / sr
    private fun k(perSec: Float) = exp(-perSec / sr)
    private var track = Track(Style.LOFI, 0, 0)
    private var melody = Song.melody(0)
    private var cur = MusicParams()
    private var samplesPerStep = 1
    private var sampleInStep = 0
    private var step = -1
    private var chord = -1
    private var bar = -1
    private var xf = 1f        // track cross-fade gain
    private var xfDir = 0      // -1 fading out, +1 fading in

    private var seed = 0x2545F491
    /** xorshift white noise in [-1, 1]; java.util.Random is too slow per sample. */
    private fun noise(): Float {
        seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5)
        return seed * 4.656613e-10f
    }
    private fun rnd01() = (noise() + 1f) * 0.5f
    private fun softClip(x: Float) = if (x > 3f) 1f else if (x < -3f) -1f else x * (27f + x * x) / (27f + 9f * x * x)

    // drums
    private var kickT = 9f; private var kickPh = 0.0; private var kickF = 0f; private var kickA = 0f; private var kickVel = 1f
    private val kickFK = k(30f); private val kickAK = k(6.5f)
    private var snT = 9f; private var snPh = 0.0; private var snTone = 0f; private var snA = 0f; private var snVel = 1f
    private var snLp1 = 0f; private var snLp2 = 0f
    private val snToneK = k(28f); private val snK = k(15f)
    private var hatT = 9f; private val hatPh = DoubleArray(6); private var hatA = 0f; private var hatK = 1f
    private var hatAmp = 0f; private var hatPan = 0f; private var hatLp = 0f
    private var crashA = 0f; private var crashLp = 0f; private val crashK = k(1.6f)
    // bass
    private var bassT = 9f; private var bassPh = 0.0; private var bassHz = 55.0; private var bassRoot = 55.0; private var bassA = 0f; private var bassVel = 1f
    private var bassCut = 0f; private var bassLp = 0f; private var bassK = k(4f); private val bassCutK = k(14f)
    // saw / tri synth (house stabs, synthwave arp, chill pad)
    private val padPh = DoubleArray(8); private val padHz = DoubleArray(8); private var padOsc = 0
    private var padLp1 = 0f; private var padLp2 = 0f; private var padEnv = 0f; private var padK = 1f; private var padLevel = 0f
    private val stabK = k(7f); private val arpK = k(10f)
    // FM electric piano voices
    private val epF = DoubleArray(EPV); private val epC = DoubleArray(EPV); private val epM = DoubleArray(EPV)
    private val epA = FloatArray(EPV); private val epI = FloatArray(EPV); private var epNext = 0
    private var epK = k(2f); private val epIK = k(9f); private var tremPh = 0.0
    // Karplus-Strong strings (0..3 arpeggio, 4 = melody)
    private val ks = Array(KSV) { FloatArray(KSMAX) }; private val ksLen = IntArray(KSV) { 2 }
    private val ksIdx = IntArray(KSV); private val ksAmp = FloatArray(KSV); private var ksNext = 0
    // melody
    private var leadPh = 0.0; private var leadM = 0.0; private var leadHz = 440.0; private var leadA = 0f; private var leadK = k(3f)
    private var leadLp = 0f; private var leadT = 0f; private var leadI = 0f
    // sunset brass swell: three detuned saws on the chord, slow attack, filter opening with the envelope
    private val brPh = DoubleArray(3); private var brT = 9f; private var brA = 0f; private var brLp = 0f; private var brLevel = 0f
    private val brK = k(1.1f); private var wetNow = 0.3f
    // backing chorus ("우 · 음 · 밥 · 워우"): three voices on the chord tones through one shared vowel filter
    private val chPh = DoubleArray(3); private val chHz = DoubleArray(3)
    private lateinit var chSyl: Syllable; private var chT = 9f; private var chLen = 1f; private var chVel = 0f
    private var chAmp = 0f; private var chAmpS = 0f; private var chBreath = 0f; private var chVoice = 1f; private var chSrc = 0f; private var chCtl = 0
    private var chorusNotes: List<Chorus.Note> = emptyList()
    private val fz = FloatArray(6); private val fa1 = FloatArray(3); private val fa2 = FloatArray(3)
    private val fin = FloatArray(3); private val fout = FloatArray(3)
    // humanizing: each singer scoops into the note, wanders a little, comes in a few ms late, vibrato blooms late
    private val chScoop = FloatArray(3); private val chDrift = FloatArray(3); private val chLate = IntArray(3)
    private val chFlow = FloatArray(3); private val chVibRate = floatArrayOf(5.1f, 5.6f, 4.7f)
    private var chLp = 0f
    private val chBuf = FloatArray(2048); private var chPos = 0; private var ensPh = 0.0 // ensemble (moving short delays)
    // chords
    private val chordHz = DoubleArray(4); private var chordN = 3; private var arpIdx = 0
    // fx
    private var rainLp = 0f; private var dropT = 9f; private var dropHz = 3000f; private var dropPan = 0f; private var dropA = 0f
    private val dropK = k(60f); private var hissLp = 0f
    private var cutoff = 0.5f; private var feedback = 0.25f; private var pitchMul = 1.0
    private val dlL = FloatArray(sr); private val dlR = FloatArray(sr); private var dlPos = 0

    private fun loop() {
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val out = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
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
        for (i in 0 until 3) chordHz[i] = hz(c[i], 220.0)
        if (chordN == 4) chordHz[3] = hz(c[0] + 14, 220.0) // add9 colour
        if (track.style.keys == Keys.PAD || track.style.comp.contains('x') && track.style.keys == Keys.SAW) {
            padOsc = if (chordN == 3) 6 else 4 // two detuned oscillators per triad note, one per 4-note chord
            for (o in 0 until padOsc) {
                val note = if (chordN == 3) o / 2 else o
                padHz[o] = chordHz[note] * if (chordN == 3) (if (o % 2 == 0) 0.997 else 1.003) else 1.0
            }
        }
        var r = ((c[0] + track.key) % 12 + 12) % 12
        if (r > 7) r -= 12
        bassRoot = 55.0 * Math.pow(2.0, r / 12.0)
    }

    private fun vel(step: Int) = (if (step % 4 == 0) 1f else if (step % 2 == 0) 0.8f else 0.65f) * (0.88f + 0.24f * rnd01())

    private fun trigHat(amp: Float, pan: Float, decay: Float) {
        hatT = 0f; hatAmp = amp; hatPan = pan; hatA = 1f; hatK = k(decay)
    }

    private fun trigEpChord(v: Float) {
        for (i in 0 until chordN) {
            val s = epNext; epNext = (epNext + 1) % EPV
            epF[s] = chordHz[i]; epC[s] = 0.0; epM[s] = 0.0
            epA[s] = v * (if (i == 0) 1f else 0.85f); epI[s] = 0.9f + cur.brightness * 1.4f
        }
    }

    private fun trigString(voice: Int, f: Double, v: Float) {
        val len = (sr / f).toInt().coerceIn(2, KSMAX - 1)
        val b = ks[voice]
        var prev = 0f
        for (i in 0 until len) { val nz = noise(); prev += (nz - prev) * 0.6f; b[i] = prev } // softened pluck
        ksLen[voice] = len; ksIdx[voice] = 0; ksAmp[voice] = v
    }

    private fun trigLead(f: Double, v: Float) {
        leadHz = f; leadT = 0f; leadI = 1.6f
        if (track.style.lead == Lead.STRING) trigString(KSV - 1, f, v * 0.9f) else leadA = v
        leadK = k(when (track.style.lead) { Lead.SQUARE -> 3.5f; Lead.SAW -> 1.8f; Lead.EP -> 2.4f; Lead.STRING -> 1f })
    }

    private fun onStep(scratching: Boolean) {
        step = (step + 1) % 16
        stepNow = step
        val st = track.style
        if (step == 0) { // bar boundary: apply mood-level changes here only
            cur = params
            bar++
            chord = (chord + 1) % 4
            setChord()
            epK = k(if (st == Style.CITY_POP) 3.2f else if (st == Style.CHILL) 1.2f else 1.8f)
            bassK = k(when (st.bassSound) { Bass.PLUCK -> if (st == Style.SYNTHWAVE) 14f else 6f; Bass.SUB -> 5f; Bass.ROUND -> 3.5f })
            val sec = Song.section(bar)
            if ((sec == Song.Section.CHORUS || sec == Song.Section.CHORUS2) && Song.section(bar - 1) != sec && cur.layers >= 4) crashA = 1f
            if (cur.sunset && sec != Song.Section.INTRO) { brT = 0f; brA = 1f }
        }
        val base = sr * 60.0 / cur.bpm / 4
        samplesPerStep = (base * if (step % 2 == 0) 1 + st.swing else 1 - st.swing).toInt().coerceAtLeast(1)
        if (scratching) return

        val sec = Song.section(bar)
        val l = cur.layers
        val groove = sec != Song.Section.INTRO && sec != Song.Section.BREAK
        val fill = Song.fillBar(bar) && step >= 12 && l >= 3 && st.snare.contains('x')
        if (groove && l >= 2 && st.hit(st.kick, step) && !fill) { kickT = 0f; kickPh = 0.0; kickF = 1f; kickA = 1f; kickVel = vel(step) }
        if (fill) { snT = 0f; snTone = 1f; snA = 1f; snVel = 0.45f + 0.17f * (step - 12) }
        else if (groove && l >= 3 && st.hit(st.snare, step)) { snT = 0f; snTone = 1f; snA = 1f; snVel = if (st.snare[step] == 'g') 0.25f else vel(step) }
        if (l >= 4 && st.hit(st.hat, step)) trigHat(0.32f * vel(step), 0f, if (st == Style.ACOUSTIC) 90f else 55f)
        if (l >= 5 && (sec == Song.Section.CHORUS || sec == Song.Section.CHORUS2) && st.hit(st.openHat, step)) trigHat(0.3f, 0f, 9f)
        val turn = drive.turn
        if (turn != 0) trigHat(if (step % 4 == 0) 0.8f else 0.45f, 0.8f * turn, 40f) // blinker hi-hat, panned to its side
        if (groove && l >= 2 && st.hit(st.bass, step)) {
            bassT = 0f; bassA = 1f; bassCut = 1f; bassVel = vel(step); bassPh = 0.0
            bassHz = bassRoot * when (st.bass[step]) { 'o' -> 2.0; '5' -> 1.4983; else -> 1.0 }
        }

        when (st.keys) {
            Keys.EP -> if (st.hit(st.comp, step)) trigEpChord(if (sec == Song.Section.INTRO) 0.7f else vel(step))
            Keys.PAD -> { padEnv = 1f; padK = 1f; if (st.hit(st.comp, step)) trigEpChord(0.45f) }
            Keys.GUITAR -> if (st.hit(st.comp, step)) {
                val i = arpIdx % (chordN * 2)
                trigString(ksNext, chordHz[i % chordN] * if (i >= chordN) 2.0 else 1.0, 0.55f * vel(step))
                ksNext = (ksNext + 1) % (KSV - 1); arpIdx++
            }
            Keys.SAW -> if (st.comp.contains('x')) {
                if (st.hit(st.comp, step)) { padEnv = 1f; padK = stabK }
            } else { // arpeggio
                padEnv = 1f; padK = arpK
                val i = step % (chordN * 2)
                val f = chordHz[i % chordN] * if (i >= chordN) 2.0 else 1.0
                padOsc = 2; padHz[0] = f * 0.996; padHz[1] = f * 1.004
            }
        }

        if (step == 0) chorusNotes = if (cur.chorus && (l >= 3 || cur.traffic)) Chorus.bar(st, track.seed, bar, cur.traffic) else emptyList()
        for (nt in chorusNotes) if (nt.step == step) sing(nt.syl, nt.len, nt.vel)

        val barIn = Song.melodyBar(bar)
        if (barIn >= 0) {
            val note = melody[barIn * 16 + step]
            if (note >= 0) trigLead(chordHz[note % chordN] * (if (note >= chordN) 4.0 else 2.0), if (sec == Song.Section.BREAK) 0.6f else 0.85f)
        }
    }

    /** Formants (Hz) F1-F3 with bandwidths and gains. */
    private class Vowel(val f: FloatArray, val bw: FloatArray, val g: FloatArray)
    private fun vw(f1: Float, f2: Float, f3: Float, g2: Float = 0.6f, g3: Float = 0.2f) =
        Vowel(floatArrayOf(f1, f2, f3), floatArrayOf(70f, 90f, 160f), floatArrayOf(1f, g2, g3))
    private val vU = vw(320f, 800f, 2500f, 0.5f, 0.15f)
    private val vO = vw(450f, 800f, 2800f, 0.5f, 0.15f)
    private val vA = vw(800f, 1200f, 2800f, 0.7f, 0.25f)
    private val vEo = vw(600f, 1000f, 2600f)
    private val vI = vw(300f, 2300f, 3000f, 0.5f, 0.3f)
    private val vE = vw(450f, 1900f, 2600f, 0.6f, 0.25f)
    private val vM = Vowel(floatArrayOf(260f, 1300f, 2600f), floatArrayOf(60f, 300f, 400f), floatArrayOf(1f, 0.08f, 0.03f)) // lips closed
    private val vD = vw(300f, 1700f, 2700f, 0.4f, 0.15f) // tongue at the teeth
    private val vL = vw(350f, 1100f, 2700f, 0.5f, 0.2f)
    private val vSh = vw(400f, 2200f, 3000f, 0.8f, 0.8f)

    private enum class On { NONE, B, D, N, L, W, Y, SH }
    private enum class Coda { NONE, P, M }
    /** onset consonant, vowel (+ optional vowel it glides into), closing consonant. */
    private class Syllable(val on: On, val v: Vowel, val glide: Vowel?, val coda: Coda)
    private val SYL = mapOf(
        "우" to Syllable(On.NONE, vU, null, Coda.NONE), "음" to Syllable(On.NONE, vM, null, Coda.NONE),
        "아" to Syllable(On.NONE, vA, null, Coda.NONE), "오" to Syllable(On.NONE, vO, null, Coda.NONE),
        "밥" to Syllable(On.B, vA, null, Coda.P), "바" to Syllable(On.B, vA, null, Coda.NONE),
        "두" to Syllable(On.D, vU, null, Coda.NONE), "다" to Syllable(On.D, vA, null, Coda.NONE),
        "둠" to Syllable(On.D, vU, null, Coda.M), "라" to Syllable(On.L, vA, null, Coda.NONE),
        "나" to Syllable(On.N, vA, null, Coda.NONE), "워우" to Syllable(On.W, vEo, vU, Coda.NONE),
        "와" to Syllable(On.W, vA, null, Coda.NONE), "예" to Syllable(On.Y, vE, null, Coda.NONE),
        "야" to Syllable(On.Y, vA, null, Coda.NONE), "샤" to Syllable(On.SH, vA, null, Coda.NONE),
    ).also { m -> require(Chorus.SYLLABLES.all { it in m }) { "chorus syllable without a sound" } }

    private fun sing(name: String, steps: Int, v: Float) {
        chSyl = SYL.getValue(name); chT = 0f; chVel = v
        chLen = steps * samplesPerStep.toFloat() / sr
        for (i in 0 until 3) {
            chHz[i] = chordHz[i] * if (chSyl.v === vM && i == 0) 0.5 else 1.0 // hum: root an octave down
            chScoop[i] = if (chSyl.on == On.NONE || chSyl.on == On.N) 0.965f else 0.983f // ~60 / 30 cents under
            chLate[i] = if (i == 0) 0 else (rnd01() * 0.028f * sr).toInt()
        }
    }

    /** Control rate: where the mouth is now (formants) and how loud (envelope), from the syllable's progress. */
    private fun vowelNow() {
        val t = chT; val len = chLen; val s = chSyl
        fun ramp(x: Float) = x.coerceIn(0f, 1f)
        // onset: start shape and how long it takes to reach the vowel
        val (from, onT) = when (s.on) {
            On.NONE -> s.v to 0f
            On.B -> vM to 0.04f
            On.D -> vD to 0.025f
            On.N -> vM to 0.06f
            On.L -> vL to 0.05f
            On.W -> vU to minOf(0.35f * len, 0.15f)
            On.Y -> vI to minOf(0.3f * len, 0.12f)
            On.SH -> vSh to 0.07f
        }
        val close = if (s.coda == Coda.NONE) len else len * 0.7f
        var a: Vowel; var b: Vowel; var x: Float
        if (t < onT) { a = from; b = s.v; x = t / onT }
        else if (t >= close) { a = s.glide ?: s.v; b = vM; x = (t - close) / 0.03f }
        else if (s.glide != null && t > len * 0.6f) { a = s.v; b = s.glide; x = (t - len * 0.6f) / (len * 0.4f) }
        else { a = s.v; b = s.v; x = 0f }
        x = ramp(x)
        // envelope: soft swell for open vowels, quick for consonants; P cuts off, the rest fade out
        val attack = when (s.on) { On.NONE -> ramp(t / 0.09f); On.N -> 0.6f + 0.4f * ramp(t / 0.06f); On.SH -> ramp((t - 0.05f) / 0.03f); else -> 0.35f + 0.65f * ramp(t / 0.03f) }
        val release = if (s.coda == Coda.P) ramp(1f - (t - close) / 0.045f) else ramp((len - t) / (if (s.v === vM) 0.25f else 0.13f))
        chAmp = attack * release
        chVoice = if (s.on == On.SH && t < 0.05f) 0f else 1f
        chBreath = if (s.on == On.SH && t < 0.07f) 0.35f else if (s.v === vM) 0.04f else 0.09f
        for (i in 0 until 3) {
            val f = a.f[i] + (b.f[i] - a.f[i]) * x
            val bw = a.bw[i] + (b.bw[i] - a.bw[i]) * x
            val r = exp(-PI.toFloat() * bw / sr)
            fa1[i] = 2f * r * sn(f.toDouble() / sr + 0.25)
            fa2[i] = r * r
            fin[i] = (1f - r) * 2f * sn(f.toDouble() / sr) // ~unity gain at the formant peak, whatever its frequency
            fout[i] = a.g[i] + (b.g[i] - a.g[i]) * x
        }
    }

    internal fun render(outBuf: ShortArray, n: Int) { // internal: rendered offline by the audio test
        val now = System.currentTimeMillis()
        val scratching = drive.scratchUntil > now
        val steer = drive.steer.coerceIn(-1f, 1f)
        val rain = drive.rain
        val st = track.style
        if (pendingTrack != null && xfDir >= 0) xfDir = -1
        val sec = Song.section(bar)
        val sectionCut = when (sec) {
            Song.Section.INTRO -> 0.45f + 0.25f * bar.coerceIn(0, 1)
            Song.Section.BREAK -> 0.65f
            else -> 1f
        }
        val targetCut = ((cur.brightness * 0.8f + abs(steer) * 0.5f) * sectionCut * (if (cur.muffled) 0.6f else 1f) *
            (if (xfDir < 0) 0.3f + 0.7f * xf else 1f)).coerceIn(0.05f, 1f)
        val tunnel = if (cur.tunnel) 1f else 0f // tunnel = the room gets bigger
        val targetFb = if (drive.brake) 0.62f else 0.15f + st.space * 0.4f + 0.22f * tunnel // brake = the car "holds its breath"
        val targetWet = 0.2f + st.space * 0.35f + 0.3f * tunnel
        val brTarget = if (cur.sunset) 1f else 0f
        val targetPitch = if (scratching) 0.55 else 1.0
        val pan = steer * 0.6f
        val delayLen = (samplesPerStep * 3).coerceIn(1, sr - 1)
        val g = gain
        val tri = st.keys == Keys.PAD
        val chill = st == Style.CHILL
        for (i in 0 until n) {
            if (++sampleInStep >= samplesPerStep) { sampleInStep = 0; onStep(scratching) }
            cutoff += (targetCut - cutoff) * 0.0005f
            feedback += (targetFb - feedback) * 0.0002f
            pitchMul += (targetPitch - pitchMul) * 0.0004
            if (padLevel < 1f) padLevel += 0.00001f
            wetNow += (targetWet - wetNow) * 0.0001f
            brLevel += (brTarget - brLevel) * 0.00002f
            val barLen = samplesPerStep * 16f
            if (xfDir < 0) {
                xf -= 1f / barLen
                if (xf <= 0f) { // swap tracks at silence and restart from the intro
                    xf = 0f; xfDir = 1
                    pendingTrack?.let { track = it; melody = Song.melody(it.seed) }
                    pendingTrack = null
                    step = -1; chord = -1; bar = -1; arpIdx = 0; sampleInStep = samplesPerStep
                }
            } else if (xfDir > 0) {
                xf += 1f / (2 * barLen)
                if (xf >= 1f) { xf = 1f; xfDir = 0 }
            }

            // ---- drums ----
            var drums = 0f
            if (kickT < 0.6f) {
                kickPh += (48f + 125f * kickF) * dt
                var kv = sn(kickPh) * kickA
                if (kickT < 0.004f) kv += noise() * (1f - kickT / 0.004f) * 0.35f // beater click
                drums += kv * kickVel
                kickF *= kickFK; kickA *= kickAK; kickT += dt
            }
            if (snT < 0.35f) {
                snPh += 190.0 * dt
                val nz = noise()
                snLp1 += (nz - snLp1) * 0.12f
                snLp2 += ((nz - snLp1) - snLp2) * 0.55f // band-passed noise
                drums += (sn(snPh) * snTone * 0.45f + snLp2 * snA * 1.2f) * snVel * 0.5f
                snTone *= snToneK; snA *= snK; snT += dt
            }
            var hat = 0f
            if (hatT < 0.6f) {
                var m = 0f
                for (h in 0 until 6) {
                    hatPh[h] += HATF[h] * 2.2 * dt
                    if (hatPh[h] >= 1.0) hatPh[h] -= 1.0
                    m += if (hatPh[h] < 0.5) 1f else -1f
                }
                m = m * 0.11f + noise() * 0.3f
                hatLp += (m - hatLp) * 0.55f
                hat = (m - hatLp) * hatAmp * hatA
                hatA *= hatK; hatT += dt
            }
            var crash = 0f
            if (crashA > 0.002f) {
                val nz = noise()
                crashLp += (nz - crashLp) * 0.45f
                crash = (nz - crashLp) * crashA * 0.14f
                crashA *= crashK
            }

            // ---- bass ----
            val duck = if (st.sidechain) 1f - 0.6f * kickA else 1f
            var bass = 0f
            if (bassT < 0.9f) {
                bassPh += bassHz * pitchMul * dt
                if (bassPh >= 1.0) bassPh -= 1.0
                val ph = bassPh.toFloat()
                bass = when (st.bassSound) {
                    Bass.ROUND -> sn(bassPh) * 0.85f + (2f * abs(2f * ph - 1f) - 1f) * 0.25f
                    Bass.SUB -> sn(bassPh) * 0.9f + (2f * ph - 1f) * 0.12f
                    Bass.PLUCK -> {
                        bassLp += ((2f * ph - 1f) - bassLp) * (0.02f + 0.25f * bassCut)
                        bassCut *= bassCutK
                        bassLp * 1.3f
                    }
                } * bassA * bassVel * 0.5f
                bassA *= bassK; bassT += dt
            }

            // ---- keys ----
            var synth = 0f
            if (padOsc > 0 && (st.keys == Keys.SAW || st.keys == Keys.PAD)) {
                var p = 0f
                for (o in 0 until padOsc) {
                    padPh[o] += padHz[o] * pitchMul * dt
                    if (padPh[o] >= 1.0) padPh[o] -= 1.0
                    val ph = padPh[o].toFloat()
                    p += if (tri) 2f * abs(2f * ph - 1f) - 1f else 2f * ph - 1f
                }
                padEnv *= padK
                val coef = 0.01f + cutoff * cutoff * 0.35f
                padLp1 += (p * (if (tri) 0.07f else 0.05f) * (6f / padOsc).coerceAtMost(3f) - padLp1) * coef
                padLp2 += (padLp1 - padLp2) * coef
                synth = padLp2 * padLevel * padEnv
            }
            var brass = 0f
            if (brLevel > 0.001f && brA > 0.001f) {
                var p = 0f
                for (o in 0 until 3) {
                    brPh[o] += chordHz[o] * (1.0 + 0.003 * (o - 1)) * pitchMul * dt
                    if (brPh[o] >= 1.0) brPh[o] -= 1.0
                    p += 2f * brPh[o].toFloat() - 1f
                }
                val env = (brT / 0.35f).coerceAtMost(1f) * brA
                brLp += (p - brLp) * (0.012f + 0.1f * env * (0.4f + cutoff))
                brass = brLp * env * brLevel * 0.08f
                brA *= brK; brT += dt
            }
            var ep = 0f
            for (v in 0 until EPV) {
                val a = epA[v]
                if (a < 0.0005f) continue
                val f = epF[v] * pitchMul * dt
                epC[v] += f; epM[v] += f
                ep += sn(epC[v] + epI[v] * sn(epM[v]) * 0.16) * a
                epA[v] = a * epK; epI[v] *= epIK
            }
            if (chill) { tremPh += 4.5 * dt; ep *= 0.85f + 0.15f * sn(tremPh) }
            ep *= 0.16f
            var str = 0f
            for (v in 0 until KSV - 1) str += pluck(v)

            // ---- melody ----
            var lead = 0f
            leadT += dt
            when (st.lead) {
                Lead.STRING -> lead = pluck(KSV - 1)
                else -> if (leadA > 0.0005f) {
                    val vib = if (leadT > 0.15f) 1.0 + 0.004 * sn(leadT * 5.5) else 1.0
                    val f = leadHz * pitchMul * vib * dt
                    leadPh += f
                    val raw = when (st.lead) {
                        Lead.SQUARE -> if (leadPh - leadPh.toLong() < 0.5) 0.6f else -0.6f
                        Lead.SAW -> ((leadPh - leadPh.toLong()) * 2 - 1).toFloat() * 0.6f
                        else -> { leadM += f; leadI *= epIK; sn(leadPh + leadI * sn(leadM) * 0.16) }
                    }
                    leadLp += (raw - leadLp) * (0.06f + 0.4f * cutoff)
                    lead = leadLp * leadA * 0.22f
                    leadA *= leadK
                }
            }

            // ---- backing chorus ----
            var choir = 0f
            if (chVel > 0f && chT < chLen + 0.05f) {
                if (--chCtl <= 0) { chCtl = 32; vowelNow() }
                chAmpS += (chAmp - chAmpS) * 0.02f
                var src = 0f; var breath = 0f
                for (v in 0 until 3) {
                    val tv = chT - chLate[v] * dt
                    if (tv < 0f) continue
                    chScoop[v] += (1f - chScoop[v]) * 0.0005f            // glide up into the note (~50 ms)
                    chDrift[v] += (noise() * 0.004f - chDrift[v]) * 0.0003f // slow wander, ~±0.2 %
                    val vib = 0.006f * ((tv - 0.25f) / 0.4f).coerceIn(0f, 1f) * sn((tv * chVibRate[v]).toDouble())
                    val f = chHz[v] * chScoop[v] * (1f + chDrift[v] + vib)
                    chPh[v] += f * pitchMul * dt
                    if (chPh[v] >= 1.0) chPh[v] -= 1.0
                    // Rosenberg glottal pulse (opens over 40 % of the cycle, closes over 16 %); its derivative drives the vocal tract
                    val ph = chPh[v].toFloat()
                    val flow = if (ph < 0.4f) 0.5f * (1f - sn(ph / 0.8 + 0.25)) else if (ph < 0.56f) sn((ph - 0.4) / 0.64 + 0.25) else 0f
                    src += (flow - chFlow[v]) * (sr / f.toFloat()) * 0.12f * (1f + chDrift[v] * 20f)
                    chFlow[v] = flow
                    breath += flow
                }
                val exc = src * chVoice + noise() * chBreath * (0.6f + breath)
                for (f in 0 until 3) {
                    val y = fin[f] * exc + fa1[f] * fz[2 * f] - fa2[f] * fz[2 * f + 1]
                    fz[2 * f + 1] = fz[2 * f]; fz[2 * f] = y
                    choir += y * fout[f]
                }
                choir *= chAmpS * chVel * 0.62f
                chT += dt
            } else chAmpS = 0f
            chLp += (choir - chLp) * 0.45f // take the edge off above ~4 kHz
            choir = chLp
            // ensemble: two slowly moving short delays (18 / 24 ms) make three synths sound like a group in a room
            chBuf[chPos] = choir
            ensPh += dt
            fun tap(ms: Double): Float {
                val d = ms * sr / 1000.0; val p = chPos - d + 2048
                val i0 = p.toInt(); val fr = (p - i0).toFloat()
                return chBuf[i0 and 2047] * (1 - fr) + chBuf[(i0 + 1) and 2047] * fr
            }
            val choirL = choir * 0.7f + tap(18.0 + 3.0 * sn(ensPh * 0.43)) * 0.5f
            val choirR = choir * 0.7f + tap(24.0 + 4.0 * sn(ensPh * 0.61 + 0.3)) * 0.5f
            chPos = (chPos + 1) and 2047

            // ---- rain, vinyl ----
            var rainL = 0f; var rainR = 0f
            if (rain > 0f) {
                val nz = noise()
                rainLp += (nz - rainLp) * 0.2f
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
            if (st.crackle) {
                hissLp += (noise() - hissLp) * 0.05f
                vinyl = hissLp * 0.02f + if (noise() > 0.9992f) noise() * 0.2f else 0f
            }

            // ---- mix: ping-pong delay as the "space"; its tail rings through track changes ----
            val tonal = (synth + ep + str + brass) * duck
            val melodyOut = lead * duck
            val rp = (dlPos - delayLen + sr) % sr
            val dL = dlL[rp]; val dR = dlR[rp]
            val send = (tonal * 0.45f + melodyOut * 0.6f + hat * 0.35f + choir * 0.7f) * xf
            dlL[dlPos] = send + dR * feedback
            dlR[dlPos] = dL * feedback
            dlPos = (dlPos + 1) % sr

            val center = (drums * 0.85f + bass + vinyl + crash) * xf
            var l = center + (tonal * (1 - pan) + melodyOut * (1 + pan * 0.5f) + hat * (1 - hatPan)) * xf + rainL + dL * wetNow
            var r = center + (tonal * (1 + pan) + melodyOut * (1 - pan * 0.5f) + hat * (1 + hatPan)) * xf + rainR + dR * wetNow
            l += choirL * xf; r += choirR * xf
            if (chorusOnly) { l = (choirL + dL * wetNow) * xf; r = (choirR + dR * wetNow) * xf }
            if (scratching && (sampleInStep * 4 / samplesPerStep) % 2 == 1) { l *= 0.25f; r *= 0.25f } // stutter
            outBuf[2 * i] = (softClip(l * g * 0.85f) * 32000f).toInt().toShort()
            outBuf[2 * i + 1] = (softClip(r * g * 0.85f) * 32000f).toInt().toShort()
        }
    }

    /** One Karplus-Strong step: a short noise burst ringing through a damped delay line = plucked string. */
    private fun pluck(v: Int): Float {
        val a = ksAmp[v]
        if (a < 0.0005f) return 0f
        val b = ks[v]; val len = ksLen[v]; val i = ksIdx[v]
        val j = if (i + 1 >= len) 0 else i + 1
        val y = b[i]
        b[i] = (y + b[j]) * 0.4985f
        ksIdx[v] = j
        ksAmp[v] = a * 0.99998f
        return y * a * 0.5f
    }
}
