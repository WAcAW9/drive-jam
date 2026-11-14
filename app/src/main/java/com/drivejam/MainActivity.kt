package com.drivejam

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.util.Calendar
import java.util.Locale

class MainActivity : Activity() {
    private val drive = DriveState()
    private val engine = MusicEngine(drive)
    private lateinit var hub: SignalHub
    private val ui = Handler(Looper.getMainLooper())

    private var company = Company.SOLO
    private var feeling = Feeling.AUTO
    private var start = Mood(0f, 0f)
    private var target = Mood(0f, 0f)
    private var startedAt = 0L
    private var fastTrajectory = false
    private var focus: AudioFocusRequest? = null

    private lateinit var playBtn: TextView
    private lateinit var nowText: TextView
    private lateinit var signalText: TextView
    private val companyChips = mutableListOf<TextView>()
    private val feelingChips = mutableListOf<TextView>()

    private val accent = Color.parseColor("#4FD1C5")
    private val chipOff = Color.parseColor("#2A2F3A")
    private val bg = Color.parseColor("#12151C")

    private var demo: DemoView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val demoMode = isDebuggable() && intent.getBooleanExtra("demo", false)
        if (demoMode) {
            intent.getStringExtra("company")?.let { n -> Company.entries.find { it.name == n }?.let { company = it } }
            intent.getStringExtra("feeling")?.let { n -> Feeling.entries.find { it.name == n }?.let { feeling = it } }
            fastTrajectory = true
            engine.recordTo = java.io.File(filesDir, "jam.wav")
            demo = DemoView(this, drive, engine)
        }
        setContentView(buildUi())
        hub = SignalHub(this, drive)
        hub.start()
        ui.post(ticker)
        if (demoMode && intent.getBooleanExtra("autoplay", false)) ui.postDelayed({ startPlayback() }, 1500)
    }

    /** Demo script hooks: `--ez play true` starts, `--ez stop true` ends playback cleanly (finalizes the WAV). */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (!isDebuggable()) return
        if (intent.getBooleanExtra("play", false) && !engine.isPlaying) startPlayback()
        if (intent.getBooleanExtra("stop", false)) stopPlayback()
    }

    private fun isDebuggable() = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    override fun onDestroy() {
        ui.removeCallbacks(ticker)
        stopPlayback()
        hub.release()
        super.onDestroy()
    }

    // ---------------- conductor loop ----------------
    private val ticker = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            hub.tick(now)
            var line = "대기 중 — 주차 상태에서 '누구와'와 '기분'을 고르고 잼을 시작하세요"
            if (engine.isPlaying) {
                val duration = if (fastTrajectory) 60f else 8 * 60f
                val m = MoodConductor.at(start, target, (now - startedAt) / 1000f, duration)
                target = MoodConductor.target(company, situation()) // rain/night/mode can change mid-drive
                val p = MoodConductor.params(m, drive.speedKmh)
                engine.params = p
                demo?.let { it.mood = m; it.target = target; it.params = p }
                line = String.format(
                    Locale.US, "♪ %d BPM · %s · 레이어 %d%s\n무드 (%.2f, %.2f) → 목표 (%.2f, %.2f)",
                    p.bpm, p.scale.label, p.layers, if (p.muffled) " · 고속 먹먹" else "",
                    m.v, m.a, target.v, target.a,
                )
            }
            if (!engine.isPlaying) demo?.let { // preview the planned journey before the jam starts
                val s = situation()
                it.mood = MoodConductor.start(feeling, s); it.target = MoodConductor.target(company, s)
            }
            nowText.text = line
            signalText.text = signalSummary(now)
            ui.postDelayed(this, 100)
        }
    }

    private fun situation(): Situation {
        val c = Calendar.getInstance()
        val dow = c.get(Calendar.DAY_OF_WEEK)
        return Situation(
            hour = c.get(Calendar.HOUR_OF_DAY),
            weekday = dow != Calendar.SATURDAY && dow != Calendar.SUNDAY,
            rain = drive.rain > 0f,
            night = drive.night,
            driveMode = drive.driveMode,
        )
    }

    private fun signalSummary(now: Long): String {
        val turn = when (drive.turn) { -1 -> "◀ 좌"; 1 -> "우 ▶"; else -> "끔" }
        val rain = when { drive.rain >= 1f -> "연속"; drive.rain > 0f -> "간헐"; else -> "끔" }
        val mode = when (drive.driveMode) { -1 -> "ECO"; 1 -> "SPORT"; else -> "NORMAL" }
        val speedSrc = if (drive.speedFromDev) "DEV" else "주행거리 계산"
        val fails = hub.failures.takeIf { it.isNotEmpty() }?.joinToString(", ", "\n미지원·실패: ") ?: ""
        return String.format(
            Locale.US,
            "%s\n깜빡이 %s · 핸들 %.0f° · 브레이크 %s%s · 와이퍼 %s · 전조등 %s · 주행모드 %s · 기어 %s · 차속 %.0f km/h (%s)%s",
            drive.link, turn, drive.steerDeg, if (drive.brake) "ON" else "끔",
            if (drive.scratchUntil > now) " (급제동!)" else "", rain, if (drive.night) "ON" else "끔",
            mode, drive.gear, drive.speedKmh, speedSrc, fails,
        )
    }

    // ---------------- playback ----------------
    private fun togglePlay() {
        if (engine.isPlaying) stopPlayback() else startPlayback()
    }

    private fun startPlayback() {
        val am = getSystemService(AudioManager::class.java)
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> ui.post { stopPlayback() }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> engine.gain = 0f // ponytail: silent "pause"; real pause with MediaSession later
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> engine.gain = 0.3f
                    AudioManager.AUDIOFOCUS_GAIN -> engine.gain = 1f
                }
            }
            .build()
        if (am.requestAudioFocus(req) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        focus = req
        val s = situation()
        start = MoodConductor.start(feeling, s)
        target = MoodConductor.target(company, s)
        startedAt = System.currentTimeMillis()
        engine.params = MoodConductor.params(start, drive.speedKmh)
        engine.gain = 1f
        engine.start()
        playBtn.text = "■  잼 멈추기"
    }

    private fun stopPlayback() {
        engine.stop()
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focus = null
        if (::playBtn.isInitialized) playBtn.text = "▶  잼 시작"
    }

    /** Changing company/feeling mid-drive restarts the trajectory from where the music is now. */
    private fun replan() {
        if (!engine.isPlaying) return
        val now = System.currentTimeMillis()
        val s = situation()
        start = MoodConductor.at(start, target, (now - startedAt) / 1000f, if (fastTrajectory) 60f else 480f)
        if (feeling.mood != null) start = MoodConductor.start(feeling, s)
        target = MoodConductor.target(company, s)
        startedAt = now
    }

    // ---------------- UI (code-built, no XML) ----------------
    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(32), dp(24), dp(32), dp(24))
        }
        root.addView(text("드라이브 잼", 34f, Color.WHITE, bold = true))
        root.addView(text("누구와, 어디로, 어떤 기분으로 — 차가 이 드라이브의 음악을 만듭니다", 16f, Color.parseColor("#9AA4B2")))
        demo?.let { root.addView(it, LinearLayout.LayoutParams(-1, dp(760)).apply { topMargin = dp(12) }) }

        root.addView(label("누구와"))
        root.addView(chipRow(Company.entries.map { it.label }, companyChips) { i ->
            company = Company.entries[i]; replan()
        }.also { select(companyChips, company.ordinal) })

        root.addView(label("기분"))
        root.addView(chipRow(Feeling.entries.map { it.label }, feelingChips) { i ->
            feeling = Feeling.entries[i]; replan()
        }.also { select(feelingChips, feeling.ordinal) })

        playBtn = text("▶  잼 시작", 24f, bg, bold = true).apply {
            gravity = Gravity.CENTER
            background = round(accent)
            setPadding(dp(24), dp(18), dp(24), dp(18))
            setOnClickListener { togglePlay() }
        }
        root.addView(playBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })

        nowText = text("", 20f, accent, bold = true).also { root.addView(it, margin(16)) }
        signalText = text("", 15f, Color.parseColor("#C7CED9")).also { root.addView(it, margin(12)) }

        root.addView(devPanel(), margin(24))
        return ScrollView(this).apply { setBackgroundColor(bg); addView(root) }
    }

    /** DEV controls: for emulators without the Virtual Console, and for speed (no speed card there). */
    private fun devPanel(): View {
        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(Color.parseColor("#1B2029"))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        p.addView(text("DEV · 시연용 입력 (실차에서는 차량 신호가 대신합니다)", 14f, Color.parseColor("#7C8696")))

        val speedLabel = text("DEV 차속: 0 km/h", 15f, Color.WHITE)
        p.addView(speedLabel)
        p.addView(SeekBar(this).apply {
            max = 140
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, v: Int, user: Boolean) {
                    if (!user) return
                    drive.speedFromDev = true; drive.speedKmh = v.toFloat(); speedLabel.text = "DEV 차속: $v km/h"
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        })

        val steerLabel = text("DEV 핸들: 0°", 15f, Color.WHITE)
        p.addView(steerLabel)
        p.addView(SeekBar(this).apply {
            max = 1080; progress = 540
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, v: Int, user: Boolean) {
                    if (!user) return
                    drive.steerDeg = (v - 540).toFloat(); steerLabel.text = "DEV 핸들: ${v - 540}°"
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(devButton("깜빡이") { drive.turn = when (drive.turn) { 0 -> -1; -1 -> 1; else -> 0 } })
        row.addView(devButton("브레이크(누르기)").apply {
            setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> drive.brake = true
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { drive.brake = false; v.performClick() }
                }
                true
            }
        })
        row.addView(devButton("와이퍼") { drive.rain = when { drive.rain == 0f -> 0.5f; drive.rain < 1f -> 1f; else -> 0f } })
        row.addView(devButton("전조등") { drive.night = !drive.night })
        row.addView(devButton("궤적 1분") { fastTrajectory = !fastTrajectory; replan() })
        p.addView(row, margin(8))
        return p
    }

    private fun chipRow(labels: List<String>, out: MutableList<TextView>, onPick: (Int) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        labels.forEachIndexed { i, l ->
            val chip = text(l, 20f, Color.WHITE).apply {
                setPadding(dp(22), dp(12), dp(22), dp(12))
                setOnClickListener { select(out, i); onPick(i) }
            }
            out += chip
            row.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
        }
        return row
    }

    private fun select(chips: List<TextView>, i: Int) = chips.forEachIndexed { k, c ->
        c.background = round(if (k == i) accent else chipOff)
        c.setTextColor(if (k == i) bg else Color.WHITE)
    }

    private fun devButton(label: String, onClick: (() -> Unit)? = null) = text(label, 15f, Color.WHITE).apply {
        background = round(chipOff)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        onClick?.let { f -> setOnClickListener { f() } }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) }
    }

    private fun label(t: String) = text(t, 15f, Color.parseColor("#7C8696")).apply {
        layoutParams = margin(18).apply { bottomMargin = dp(6) }
    }

    private fun text(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun round(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(14).toFloat() }
    private fun margin(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
