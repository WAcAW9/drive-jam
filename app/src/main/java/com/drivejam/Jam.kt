package com.drivejam

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import java.util.Calendar

/**
 * The player: one per process, shared by the screen (MainActivity) and the car's media system (JamService).
 * Owns the car signals, the conductor state, the current track and its history.
 */
object Jam {
    val drive = DriveState()
    val engine = MusicEngine(drive)
    private lateinit var app: Context
    private lateinit var hub: SignalHub
    private val ui = Handler(Looper.getMainLooper())
    private val rnd = java.util.Random()

    var company = Company.SOLO; private set
    var feeling = Feeling.AUTO; private set
    var track: Track? = null; private set
    private val history = ArrayDeque<Track>()
    var trackStartedAt = 0L; private set
    /** Last "~한 음악으로" request as understood, shown back to the listener. */
    var understood: String? = null; private set

    private var start = Mood(0f, 0f)
    private var request = Mood(0f, 0f)
    var target = Mood(0f, 0f); private set
    var mood = Mood(0f, 0f); private set
    var params = MusicParams(); private set
    private var startedAt = 0L
    private var duration = 480f
    var fastTrajectory = false

    private var focus: AudioFocusRequest? = null
    private val listeners = mutableSetOf<() -> Unit>()
    val failures get() = hub.failures

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        hub = SignalHub(app, drive).also { it.start() }
        ui.post(ticker)
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = listeners.toList().forEach { it() }

    val isPlaying get() = engine.isPlaying
    val parked get() = drive.gear == "P" || drive.gear == "-" || drive.speedKmh < 1f

    // ---------------- transport ----------------
    fun play() {
        if (isPlaying) return
        val am = app.getSystemService(AudioManager::class.java)
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            .setOnAudioFocusChangeListener { change ->
                when (change) { // navigation prompts and Gleo always win
                    AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> ui.post { pause() }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> engine.gain = 0.3f
                    AudioManager.AUDIOFOCUS_GAIN -> engine.gain = 1f
                }
            }
            .build()
        if (am.requestAudioFocus(req) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        focus = req
        val s = situation()
        if (track == null) setTrack(pick(emptySet()))
        if (startedAt == 0L) { start = MoodConductor.start(feeling, s); startedAt = System.currentTimeMillis() }
        target = MoodConductor.target(company, s, request)
        engine.params = MoodConductor.params(at(System.currentTimeMillis()), drive.speedKmh, track!!.style.baseBpm)
        engine.load(track!!)
        engine.gain = 1f
        engine.start()
        runCatching { app.startService(Intent(app, JamService::class.java)) } // keeps the media session alive
        changed()
    }

    fun pause() {
        if (!isPlaying) return
        engine.stop()
        focus?.let { app.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focus = null
        changed()
    }

    fun toggle() = if (isPlaying) pause() else play()

    /** ⏭ — another song: a different style that still fits who's in the car. Skips teach the picker. */
    fun next(forced: Style? = null, fromRequest: Boolean = false) {
        if (!fromRequest) understood = null
        val cur = track
        if (cur != null) {
            learn(cur.style, liked = System.currentTimeMillis() - trackStartedAt > 90_000)
            history.addLast(cur)
            while (history.size > 20) history.removeFirst()
        }
        val avoid = setOfNotNull(cur?.style, history.dropLast(1).lastOrNull()?.style)
        setTrack(pick(avoid, forced))
    }

    /** ⏮ — back to the previous song, exactly as it was (same seed). */
    fun previous() {
        val prev = history.removeLastOrNull() ?: return
        setTrack(prev)
    }

    /** "~한 음악으로" — free text from the request box, a chip, or MediaSession.onPlayFromSearch. */
    fun request(text: String): MusicRequest {
        val r = RequestParser.parse(text)
        request = Mood((request.v + r.mood.v).coerceIn(-0.5f, 0.5f), (request.a + r.mood.a).coerceIn(-0.6f, 0.6f))
        understood = r.summary
        replan(seconds = 20f) // a request should be heard now, not over 8 minutes
        if (r.newTrack) next(forced = r.style, fromRequest = true) else changed()
        if (!isPlaying) play()
        return r
    }

    fun setCompany(c: Company) { company = c; request = Mood(0f, 0f); replan(); changed() }
    fun setFeeling(f: Feeling) {
        feeling = f
        if (isPlaying && f.mood != null) { start = MoodConductor.start(f, situation()); startedAt = System.currentTimeMillis(); duration = trajectory() }
        changed()
    }

    private fun setTrack(t: Track) {
        track = t
        trackStartedAt = System.currentTimeMillis()
        if (isPlaying) engine.pendingTrack = t else engine.load(t)
        changed()
    }

    private fun pick(avoid: Set<Style>, forced: Style? = null) =
        TrackPicker.pick(company, situation(), ::personal, avoid, rnd, forced)

    // ---------------- personalization: per (company, style) weight, EMA ----------------
    private val prefs by lazy { app.getSharedPreferences("jam_taste", Context.MODE_PRIVATE) }
    private fun key(s: Style) = "w_${company.name}_${s.name}"
    private fun personal(s: Style) = prefs.getFloat(key(s), 1f)
    private fun learn(s: Style, liked: Boolean) {
        val w = personal(s) * 0.8f + 0.2f * (if (liked) 1.4f else 0.4f)
        prefs.edit().putFloat(key(s), w.coerceIn(0.3f, 2f)).apply()
    }

    // ---------------- conductor ----------------
    private fun trajectory() = if (fastTrajectory) 60f else 480f

    private fun at(now: Long) = MoodConductor.at(start, target, (now - startedAt) / 1000f, duration)

    private fun replan(seconds: Float = trajectory()) {
        target = MoodConductor.target(company, situation(), request)
        if (startedAt == 0L) return // not started yet: play() starts the journey from the chosen feeling
        val now = System.currentTimeMillis()
        start = at(now); startedAt = now; duration = seconds
    }

    fun situation(): Situation {
        val c = Calendar.getInstance()
        val dow = c.get(Calendar.DAY_OF_WEEK)
        return Situation(
            hour = c.get(Calendar.HOUR_OF_DAY),
            weekday = dow != Calendar.SATURDAY && dow != Calendar.SUNDAY,
            rain = drive.rain > 0f, night = drive.night, driveMode = drive.driveMode,
        )
    }

    private val ticker = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            hub.tick(now)
            val s = situation()
            if (startedAt == 0L) { // preview before the first play
                mood = MoodConductor.start(feeling, s); target = MoodConductor.target(company, s, request)
            } else {
                target = MoodConductor.target(company, s, request) // rain/night/mode can change mid-drive
                mood = at(now)
            }
            params = MoodConductor.params(mood, drive.speedKmh, track?.style?.baseBpm ?: 100)
            if (isPlaying) engine.params = params
            ui.postDelayed(this, 100)
        }
    }

    /** One plain sentence about why the music sounds like this right now. */
    fun hint(now: Long = System.currentTimeMillis()): String = when {
        drive.scratchUntil > now -> "급정거해서 곡이 잠깐 멈칫했어요"
        drive.turn != 0 -> "깜빡이를 켜서 하이햇이 들어왔어요"
        kotlin.math.abs(drive.steerDeg) > 60f -> "핸들을 돌려서 소리가 그쪽으로 움직였어요"
        drive.brake -> "브레이크를 밟아서 소리를 잠시 붙잡아 뒀어요"
        params.muffled -> "속도를 높여서 소리를 살짝 먹먹하게 했어요"
        drive.rain > 0f -> "와이퍼가 움직여서 빗소리를 깔았어요"
        understood != null && now - trackStartedAt < 15_000 -> "요청하셔서 바꿨어요 · $understood"
        drive.night -> "전조등을 켜서 조금 차분하게 바꿨어요"
        feeling.mood != null && now - startedAt < 40_000 -> "'${feeling.label}' 기분이라서 천천히 시작했어요"
        company != Company.SOLO -> "${company.label}과 함께라서 ${if (target.a > 0.2f) "들뜬" else "편안한"} 분위기로 골랐어요"
        else -> "지금 운전에 맞춰 연주하고 있어요"
    }
}
