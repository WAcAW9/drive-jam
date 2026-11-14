package com.drivejam

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.TransitionDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale

/** Now-playing screen ("Ambient Glass"). All playback state lives in [Jam]; this only shows it and forwards taps. */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val drive get() = Jam.drive

    private val ink = Color.parseColor("#1B1830")
    private val dim = 0xC7FFFFFF.toInt()       // white .78
    private val glassFill = 0x24FFFFFF          // white .14
    private val glassLine = 0x3DFFFFFF          // white .24
    private val solid = 0xE0FFFFFF.toInt()      // white .88: "on" pills

    private lateinit var scroller: ScrollView
    private lateinit var contextPill: TextView
    private lateinit var setupCard: LinearLayout
    private lateinit var cover: ImageView
    private lateinit var titleText: TextView
    private lateinit var artistText: TextView
    private lateinit var hintRow: LinearLayout
    private lateinit var hintDot: View
    private lateinit var hintText: TextView
    private lateinit var playBtn: TransportIcon
    private lateinit var suggestions: LinearLayout
    private lateinit var input: EditText
    private lateinit var sendBtn: TextView
    private lateinit var drivingNote: TextView
    private lateinit var understoodText: TextView
    private lateinit var devBox: LinearLayout
    private lateinit var signalText: TextView
    private val pills = mutableListOf<TextView>()
    private val companyChips = mutableListOf<TextView>()
    private val feelingChips = mutableListOf<TextView>()
    private var demo: DemoView? = null
    private var shownTrack: Track? = null
    private var everPlayed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Jam.init(this)
        val demoMode = isDebuggable() && intent.getBooleanExtra("demo", false)
        if (demoMode) {
            intent.getStringExtra("company")?.let { n -> Company.entries.find { it.name == n }?.let(Jam::setCompany) }
            intent.getStringExtra("feeling")?.let { n -> Feeling.entries.find { it.name == n }?.let(Jam::setFeeling) }
            Jam.fastTrajectory = true
            Jam.engine.recordTo = java.io.File(filesDir, "jam.wav")
        }
        demo = DemoView(this, drive, Jam.engine)
        setContentView(buildUi())
        if (Jam.track != null) { everPlayed = true; showSetup(false) } // reopened while playing
        if (demoMode && intent.getBooleanExtra("autoplay", false)) ui.postDelayed({ startJam() }, 1500)
        ui.post(ticker)
    }

    override fun onDestroy() {
        ui.removeCallbacks(ticker)
        super.onDestroy()
    }

    /** Demo-script hooks (debug builds only): play / stop / next / prev / request_b64. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!isDebuggable()) return
        if (intent.getBooleanExtra("play", false)) startJam()
        if (intent.getBooleanExtra("stop", false)) Jam.pause()
        if (intent.getBooleanExtra("next", false)) Jam.next()
        if (intent.getBooleanExtra("prev", false)) Jam.previous()
        intent.getStringExtra("request_b64")?.let { submit(String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8)) }
    }

    private fun isDebuggable() = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun startJam() {
        everPlayed = true
        showSetup(false)
        Jam.play()
    }

    /** The setup card takes the cover's place, so the controls never get pushed off screen. */
    private fun showSetup(show: Boolean) {
        setupCard.visibility = if (show) View.VISIBLE else View.GONE
        cover.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun submit(text: String) {
        if (text.isBlank()) return
        Jam.request(text)
        everPlayed = true; showSetup(false)
        input.setText("")
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken, 0)
    }

    // ---------------- refresh ----------------
    private val ticker = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 200) }
    }

    private fun refresh() {
        val now = System.currentTimeMillis()
        val t = Jam.track
        if (t != shownTrack && t != null) showTrack(t)
        if (t == null) {
            titleText.text = "오늘의 드라이브"
            artistText.text = "출발 전에 누구와, 어떤 기분인지 골라 주세요"
        } else {
            val sec = ((now - Jam.trackStartedAt) / 1000).toInt()
            artistText.text = String.format(Locale.US, "드라이브 잼 · %s · %d:%02d", t.style.label, sec / 60, sec % 60)
        }
        playBtn.kind = if (Jam.isPlaying) TransportIcon.Kind.PAUSE else TransportIcon.Kind.PLAY
        contextPill.text = "${Jam.company.label}${if (Jam.company == Company.SOLO) "" else "과"} · ${Jam.feeling.label}  ▾"
        hintText.text = if (Jam.isPlaying) Jam.hint(now) else if (t == null) "" else "일시정지됨"
        hintRow.visibility = if (hintText.text.isEmpty()) View.INVISIBLE else View.VISIBLE
        val active = listOf(drive.turn != 0, kotlin.math.abs(drive.steerDeg) > 15f, drive.brake, drive.rain > 0f, drive.night)
        pills.forEachIndexed { i, p -> paint(p, active[i]) }
        val parked = Jam.parked
        suggestions.visibility = if (parked) View.VISIBLE else View.INVISIBLE
        input.visibility = if (parked) View.VISIBLE else View.GONE
        sendBtn.visibility = input.visibility
        drivingNote.visibility = if (parked) View.GONE else View.VISIBLE
        understoodText.text = Jam.understood?.let { "이렇게 이해했어요 · $it" } ?: ""
        demo?.let { it.mood = Jam.mood; it.target = Jam.target; it.params = Jam.params }
        if (devBox.visibility == View.VISIBLE) signalText.text = signalSummary(now)
    }

    private fun showTrack(t: Track) {
        shownTrack = t
        val (c0, c1) = t.style.colors[0] to t.style.colors[1]
        cover.setImageBitmap(CoverArt.of(t))
        cover.outlineSpotShadowColor = c0
        cover.outlineAmbientShadowColor = c0
        titleText.text = t.title
        // the whole screen takes on the cover's colours, crossfading from whatever is showing now
        val from = scroller.background.let { if (it is TransitionDrawable) it.getDrawable(1) else it }
        scroller.background = TransitionDrawable(arrayOf(from, Ambient(c0, c1))).apply { isCrossFadeEnabled = true; startTransition(900) }
    }

    // ---------------- layout (code-built) ----------------
    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(56), dp(36), dp(56), dp(28))
        }

        // header
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("드라이브 잼", 20f, dim, bold = true).apply {
            setOnLongClickListener { devBox.visibility = if (devBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE; true }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        contextPill = text("", 17f, Color.WHITE).apply {
            background = glass(); setPadding(dp(22), dp(12), dp(22), dp(12))
            setOnClickListener { showSetup(setupCard.visibility != View.VISIBLE) }
        }
        header.addView(contextPill)
        root.addView(header, LinearLayout.LayoutParams(-1, -2))

        // pre-drive setup (stands in for the cover until the first play)
        setupCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = glass(32); setPadding(dp(40), dp(32), dp(40), dp(36))
            addView(label("누구와"))
            addView(chipRow(Company.entries.map { it.label }, companyChips, Jam.company.ordinal) { Jam.setCompany(Company.entries[it]) })
            addView(label("기분"))
            addView(chipRow(Feeling.entries.map { it.label }, feelingChips, Jam.feeling.ordinal) { Jam.setFeeling(Feeling.entries[it]) })
            addView(text("이대로 출발", 22f, ink, bold = true).apply {
                gravity = Gravity.CENTER; background = pill(Color.WHITE)
                setPadding(dp(48), dp(18), dp(48), dp(18))
                setOnClickListener { startJam() }
            }, lp(-2, -2, top = 28))
        }
        root.addView(setupCard, lp(dp(760), -2, top = 24))

        cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(32).toFloat())
            }
            clipToOutline = true
            elevation = dp(40).toFloat()
            visibility = View.GONE
        }
        root.addView(cover, LinearLayout.LayoutParams(dp(400), dp(400)).apply { topMargin = dp(24) })

        titleText = text("오늘의 드라이브", 54f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER; letterSpacing = -0.02f }
        root.addView(titleText, lp(-1, -2, top = 26))
        artistText = text("", 20f, dim).apply { gravity = Gravity.CENTER }
        root.addView(artistText, lp(-1, -2, top = 4))

        hintRow = row().apply {
            gravity = Gravity.CENTER_VERTICAL; background = glass(); setPadding(dp(22), dp(12), dp(24), dp(12))
        }
        hintDot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFFD08A.toInt()) } }
        hintRow.addView(hintDot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(12) })
        hintText = text("", 20f, Color.WHITE).also { hintRow.addView(it) }
        root.addView(hintRow, lp(-2, -2, top = 22))

        val controls = row().apply { gravity = Gravity.CENTER_VERTICAL }
        controls.addView(transport(TransportIcon.Kind.PREV, 84) { Jam.previous() })
        playBtn = transport(TransportIcon.Kind.PLAY, 128) { if (Jam.track == null || !everPlayed) startJam() else Jam.toggle() }
        controls.addView(playBtn, LinearLayout.LayoutParams(dp(128), dp(128)).apply { leftMargin = dp(44); rightMargin = dp(44) })
        controls.addView(transport(TransportIcon.Kind.NEXT, 84) { Jam.next() })
        root.addView(controls, lp(-2, -2, top = 24))

        val pillRow = row()
        listOf("깜빡이", "핸들", "브레이크", "와이퍼", "전조등").forEachIndexed { i, name ->
            val p = text(name, 16f, Color.WHITE).apply { setPadding(dp(18), dp(9), dp(18), dp(9)) }
            pills += p
            pillRow.addView(p, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) leftMargin = dp(14) })
        }
        root.addView(pillRow, lp(-2, -2, top = 24))

        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f)) // pushes the request bar to the bottom

        // "~한 음악으로": parked-only suggestions above a glass request bar
        suggestions = row()
        listOf("비 오는 날 카페 같은 음악", "몽환적인 밤 드라이브", "여름 바다 시티팝", "잔잔한 어쿠스틱").forEachIndexed { i, q ->
            suggestions.addView(chip(q, glass(), Color.WHITE) { submit(q) }, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) leftMargin = dp(10) })
        }
        root.addView(suggestions, lp(-2, -2, top = 20))

        val bar = row().apply {
            gravity = Gravity.CENTER_VERTICAL; background = glass(); setPadding(dp(28), dp(12), dp(12), dp(12))
        }
        bar.addView(text("이런 음악으로", 18f, Color.WHITE, bold = true))
        input = EditText(this).apply {
            hint = "비 오는 날 카페 같은 잔잔한 음악으로"
            setHintTextColor(0x99FFFFFF.toInt()); setTextColor(Color.WHITE); textSize = 19f
            background = null; setPadding(dp(20), dp(12), dp(12), dp(12))
            inputType = InputType.TYPE_CLASS_TEXT; imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { submit(text.toString()); true } else false }
        }
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        drivingNote = text("주행 중에는 버튼과 스티어링 휠로 바꿀 수 있어요", 17f, dim).apply { setPadding(dp(20), 0, dp(12), 0) }
        bar.addView(drivingNote, LinearLayout.LayoutParams(0, -2, 1f))
        sendBtn = chip("바꾸기", pill(0x29FFFFFF), Color.WHITE) { submit(input.text.toString()) }
        bar.addView(sendBtn, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
        listOf("더 신나게", "더 차분하게").forEach { q ->
            bar.addView(chip(q, pill(0x29FFFFFF), Color.WHITE) { submit(q) }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
        }
        bar.addView(chip("완전 다른 곡", pill(Color.WHITE), ink, bold = true) { submit("완전 다른 곡") })
        root.addView(bar, lp(-1, -2, top = 14))
        understoodText = text("", 16f, dim).apply { gravity = Gravity.CENTER }
        root.addView(understoodText, lp(-1, -2, top = 10))

        // developer view (long-press the app name)
        devBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        demo?.let { devBox.addView(it, LinearLayout.LayoutParams(-1, dp(640))) }
        signalText = text("", 15f, Color.parseColor("#C7CED9")).also { devBox.addView(it, lp(-1, -2, top = 12)) }
        devBox.addView(devPanel(), lp(-1, -2, top = 12))
        root.addView(devBox, lp(-1, -2, top = 24))

        scroller = ScrollView(this).apply {
            isFillViewport = true
            background = Ambient(Style.CITY_POP.colors[0], Style.CITY_POP.colors[1])
            addView(root)
        }
        return scroller
    }

    private fun signalSummary(now: Long): String {
        val p = Jam.params
        val fails = Jam.failures.takeIf { it.isNotEmpty() }?.joinToString(", ", "\n미지원·실패: ") ?: ""
        return String.format(
            Locale.US,
            "%s · %d BPM %s 레이어 %d · 무드 (%.2f, %.2f) → (%.2f, %.2f)\n깜빡이 %d · 핸들 %.0f° · 브레이크 %s%s · 와이퍼 %.1f · 전조등 %s · 모드 %d · 기어 %s · %.0f km/h%s",
            drive.link, p.bpm, p.scale.label, p.layers, Jam.mood.v, Jam.mood.a, Jam.target.v, Jam.target.a,
            drive.turn, drive.steerDeg, if (drive.brake) "ON" else "끔", if (drive.scratchUntil > now) " (급제동)" else "",
            drive.rain, if (drive.night) "ON" else "끔", drive.driveMode, drive.gear, drive.speedKmh, fails,
        )
    }

    /** DEV controls: for emulators without the Virtual Console, and for speed. */
    private fun devPanel(): View {
        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = glass(24); setPadding(dp(28), dp(22), dp(28), dp(24))
        }
        p.addView(text("DEV · 시연용 입력", 14f, dim))
        val speedLabel = text("DEV 차속: 0 km/h", 15f, Color.WHITE)
        p.addView(speedLabel)
        p.addView(seek(140, 0) { v -> drive.speedFromDev = true; drive.speedKmh = v.toFloat(); speedLabel.text = "DEV 차속: $v km/h" })
        val steerLabel = text("DEV 핸들: 0°", 15f, Color.WHITE)
        p.addView(steerLabel)
        p.addView(seek(1080, 540) { v -> drive.steerDeg = (v - 540).toFloat(); steerLabel.text = "DEV 핸들: ${v - 540}°" })
        val r = row()
        r.addView(chip("깜빡이", glass(), Color.WHITE) { drive.turn = when (drive.turn) { 0 -> -1; -1 -> 1; else -> 0 } })
        r.addView(chip("브레이크(누르기)", glass(), Color.WHITE).apply {
            setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> drive.brake = true
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { drive.brake = false; v.performClick() }
                }
                true
            }
        })
        r.addView(chip("와이퍼", glass(), Color.WHITE) { drive.rain = when { drive.rain == 0f -> 0.5f; drive.rain < 1f -> 1f; else -> 0f } })
        r.addView(chip("전조등", glass(), Color.WHITE) { drive.night = !drive.night })
        r.addView(chip("궤적 1분", glass(), Color.WHITE) { Jam.fastTrajectory = !Jam.fastTrajectory })
        p.addView(r, lp(-1, -2, top = 8))
        return p
    }

    // ---------------- small view helpers ----------------
    private fun transport(kind: TransportIcon.Kind, size: Int, onClick: () -> Unit) = TransportIcon(this).apply {
        this.kind = kind
        val main = kind == TransportIcon.Kind.PLAY || kind == TransportIcon.Kind.PAUSE
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (main) setColor(Color.WHITE) else { setColor(glassFill); setStroke(dp(1), glassLine) }
        }
        fg = if (main) ink else Color.WHITE
        if (main) elevation = dp(16).toFloat()
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        setOnClickListener { onClick() }
    }

    private fun chipRow(labels: List<String>, out: MutableList<TextView>, selected: Int, onPick: (Int) -> Unit): View {
        val r = row().apply { gravity = Gravity.CENTER_HORIZONTAL }
        labels.forEachIndexed { i, l ->
            val c = text(l, 20f, Color.WHITE).apply {
                setPadding(dp(26), dp(13), dp(26), dp(13))
                setOnClickListener { out.forEachIndexed { k, o -> paint(o, k == i) }; onPick(i) }
            }
            paint(c, i == selected)
            out += c
            r.addView(c, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) leftMargin = dp(10) })
        }
        return r
    }

    private fun chip(label: String, bg: GradientDrawable, fg: Int, bold: Boolean = false, onClick: (() -> Unit)? = null) =
        text(label, 17f, fg, bold).apply {
            background = bg
            setPadding(dp(20), dp(13), dp(20), dp(13))
            onClick?.let { f -> setOnClickListener { f() } }
        }

    private fun paint(v: TextView, on: Boolean) {
        v.background = if (on) pill(solid) else glass()
        v.setTextColor(if (on) ink else Color.WHITE)
    }

    /** Frosted pill/card: translucent white with a hairline edge. ponytail: no real backdrop blur, the ambient glow is already soft. */
    private fun glass(r: Int = 999) = GradientDrawable().apply {
        setColor(glassFill); setStroke(dp(1), glassLine); cornerRadius = dp(r).toFloat()
    }

    private fun pill(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(999).toFloat() }

    private fun seek(max: Int, start: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
        this.max = max; progress = start
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, v: Int, user: Boolean) { if (user) onChange(v) }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun label(t: String) = text(t, 16f, dim).apply {
        gravity = Gravity.CENTER; layoutParams = lp(-1, -2, top = 18).apply { bottomMargin = dp(10) }
    }

    private fun text(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    private fun lp(w: Int, h: Int, top: Int = 0) = LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
