package com.drivejam

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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

/** Now-playing screen. All playback state lives in [Jam]; this only shows it and forwards taps. */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val drive get() = Jam.drive

    private val bgBase = Color.parseColor("#0E1116")
    private val card = Color.parseColor("#1A1F29")
    private val chipOff = Color.parseColor("#262C38")
    private val grey = Color.parseColor("#9AA4B2")
    private var accent = Color.parseColor("#4FD1C5")

    private lateinit var root: LinearLayout
    private lateinit var contextPill: TextView
    private lateinit var setupCard: LinearLayout
    private lateinit var cover: ImageView
    private lateinit var styleTag: TextView
    private lateinit var titleText: TextView
    private lateinit var artistText: TextView
    private lateinit var hintText: TextView
    private lateinit var elapsedText: TextView
    private lateinit var playBtn: TransportIcon
    private lateinit var parkedOnly: LinearLayout
    private lateinit var drivingNote: TextView
    private lateinit var understoodText: TextView
    private lateinit var input: EditText
    private lateinit var devBox: LinearLayout
    private lateinit var signalText: TextView
    private val pills = mutableListOf<TextView>()
    private val companyChips = mutableListOf<TextView>()
    private val feelingChips = mutableListOf<TextView>()
    private val quickChips = mutableListOf<TextView>()
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
        if (Jam.track != null) { everPlayed = true; setupCard.visibility = View.GONE } // reopened while playing
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
        setupCard.visibility = View.GONE
        Jam.play()
    }

    private fun submit(text: String) {
        if (text.isBlank()) return
        Jam.request(text)
        everPlayed = true; setupCard.visibility = View.GONE
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
        }
        playBtn.kind = if (Jam.isPlaying) TransportIcon.Kind.PAUSE else TransportIcon.Kind.PLAY
        contextPill.text = "${Jam.company.label}${if (Jam.company == Company.SOLO) "" else "과"} · ${Jam.feeling.label}  ▾"
        hintText.text = if (Jam.isPlaying) "●  " + Jam.hint(now) else if (t == null) "" else "일시정지됨"
        val sec = if (t == null) 0 else ((now - Jam.trackStartedAt) / 1000).toInt()
        elapsedText.text = if (t == null) "" else String.format(Locale.US, "%d:%02d", sec / 60, sec % 60)
        val active = listOf(drive.turn != 0, kotlin.math.abs(drive.steerDeg) > 15f, drive.brake, drive.rain > 0f, drive.night)
        pills.forEachIndexed { i, p -> paint(p, active[i]) }
        val parked = Jam.parked
        parkedOnly.visibility = if (parked) View.VISIBLE else View.GONE
        drivingNote.visibility = if (parked) View.GONE else View.VISIBLE
        understoodText.text = Jam.understood?.let { "이렇게 이해했어요 · $it" } ?: ""
        demo?.let { it.mood = Jam.mood; it.target = Jam.target; it.params = Jam.params }
        if (devBox.visibility == View.VISIBLE) signalText.text = signalSummary(now)
    }

    private fun showTrack(t: Track) {
        shownTrack = t
        accent = accentOf(t.style)
        cover.setImageBitmap(CoverArt.of(t))
        styleTag.text = t.style.label
        styleTag.background = round(accent, 10)
        titleText.text = t.title
        artistText.text = "드라이브 잼 · ${t.style.label}"
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(blend(t.style.colors[0], bgBase, 0.72f), bgBase, bgBase),
        )
        (playBtn.background as GradientDrawable).setColor(accent)
        listOf(companyChips, feelingChips).forEach { chips -> chips.forEachIndexed { i, c -> paint(c, c.tag == true) } }
        quickChips.forEach { (it.background as GradientDrawable).setColor(chipOff) }
    }

    private fun accentOf(s: Style) = Color.parseColor(
        when (s) {
            Style.CITY_POP -> "#FFB86B"; Style.HOUSE -> "#C77DFF"; Style.LOFI -> "#E0B084"
            Style.SYNTHWAVE -> "#FF5CAD"; Style.ACOUSTIC -> "#7FD8B4"; Style.CHILL -> "#8EC3F0"
        }
    )

    // ---------------- layout (code-built) ----------------
    private fun buildUi(): View {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgBase)
            setPadding(dp(44), dp(28), dp(44), dp(28))
        }

        // header
        val header = row()
        header.addView(text("드라이브 잼", 20f, grey, bold = true).apply {
            setOnLongClickListener { devBox.visibility = if (devBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE; true }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        contextPill = text("", 18f, Color.WHITE).apply {
            background = round(chipOff, 40); setPadding(dp(20), dp(10), dp(20), dp(10))
            setOnClickListener { setupCard.visibility = if (setupCard.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        header.addView(contextPill)
        root.addView(header)

        // pre-drive setup
        setupCard = vbox(card).apply {
            addView(text("오늘의 드라이브", 26f, Color.WHITE, bold = true))
            addView(label("누구와"))
            addView(chipRow(Company.entries.map { it.label }, companyChips, Jam.company.ordinal) { Jam.setCompany(Company.entries[it]) })
            addView(label("기분"))
            addView(chipRow(Feeling.entries.map { it.label }, feelingChips, Jam.feeling.ordinal) { Jam.setFeeling(Feeling.entries[it]) })
            addView(text("이대로 출발", 22f, bgBase, bold = true).apply {
                gravity = Gravity.CENTER; background = round(accent, 40)
                setPadding(dp(24), dp(16), dp(24), dp(16))
                setOnClickListener { startJam() }
            }, lp(-1, -2, top = 22))
        }
        root.addView(setupCard, lp(-1, -2, top = 20))

        // now playing
        val player = row().apply { gravity = Gravity.CENTER_VERTICAL }
        cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = round(card, 28)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(28).toFloat())
            }
            clipToOutline = true
            elevation = dp(12).toFloat()
        }
        player.addView(cover, LinearLayout.LayoutParams(dp(480), dp(480)))
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        styleTag = text("", 16f, bgBase, bold = true).apply { setPadding(dp(14), dp(6), dp(14), dp(6)) }
        info.addView(styleTag, LinearLayout.LayoutParams(-2, -2))
        titleText = text("오늘의 드라이브", 46f, Color.WHITE, bold = true).also { info.addView(it, lp(-1, -2, top = 14)) }
        artistText = text("", 22f, grey).also { info.addView(it, lp(-1, -2, top = 4)) }
        hintText = text("", 21f, Color.WHITE).also { info.addView(it, lp(-1, -2, top = 26)) }
        val pillRow = row()
        listOf("깜빡이", "핸들", "브레이크", "와이퍼", "전조등").forEach { name ->
            val p = text(name, 15f, Color.WHITE).apply { setPadding(dp(14), dp(6), dp(14), dp(6)) }
            pills += p
            pillRow.addView(p, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        }
        info.addView(pillRow, lp(-2, -2, top = 14))
        val controls = row().apply { gravity = Gravity.CENTER_VERTICAL }
        controls.addView(transport(TransportIcon.Kind.PREV, 96, chipOff) { Jam.previous() })
        playBtn = transport(TransportIcon.Kind.PLAY, 128, accent) { if (Jam.track == null || !everPlayed) startJam() else Jam.toggle() }
        controls.addView(playBtn, LinearLayout.LayoutParams(dp(128), dp(128)).apply { leftMargin = dp(24); rightMargin = dp(24) })
        controls.addView(transport(TransportIcon.Kind.NEXT, 96, chipOff) { Jam.next() })
        elapsedText = text("", 18f, grey).apply { setPadding(dp(28), 0, 0, 0) }
        controls.addView(elapsedText)
        info.addView(controls, lp(-2, -2, top = 30))
        player.addView(info, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(48) })
        root.addView(player, lp(-1, -2, top = 28))

        // "~한 음악으로"
        val req = vbox(card)
        req.addView(text("이런 음악으로", 20f, Color.WHITE, bold = true))
        val quick = row()
        listOf("더 신나게", "더 차분하게", "완전 다른 곡").forEach { q ->
            val c = chip(q) { submit(q) }
            quickChips += c
            quick.addView(c, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
        }
        req.addView(quick, lp(-1, -2, top = 14))
        parkedOnly = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sugg = row()
        listOf("비 오는 날 카페 같은 음악", "몽환적인 밤 드라이브", "여름 바다 시티팝", "잔잔한 어쿠스틱").forEach { q ->
            sugg.addView(chip(q, outline = true) { submit(q) }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
        }
        parkedOnly.addView(sugg, lp(-1, -2, top = 12))
        val inputRow = row().apply { gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "예: 비 오는 날 카페 같은 잔잔한 음악으로"
            setHintTextColor(Color.parseColor("#6B7584")); setTextColor(Color.WHITE); textSize = 20f
            background = round(Color.parseColor("#232936"), 18)
            setPadding(dp(20), dp(14), dp(20), dp(14))
            inputType = InputType.TYPE_CLASS_TEXT; imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { submit(text.toString()); true } else false }
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        inputRow.addView(text("바꾸기", 20f, bgBase, bold = true).apply {
            background = round(Color.WHITE, 18); setPadding(dp(26), dp(14), dp(26), dp(14))
            setOnClickListener { submit(input.text.toString()) }
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        parkedOnly.addView(inputRow, lp(-1, -2, top = 12))
        req.addView(parkedOnly)
        drivingNote = text("주행 중에는 버튼으로만 바꿀 수 있어요. 스티어링 휠의 다음 곡 버튼도 됩니다.", 16f, grey)
        req.addView(drivingNote, lp(-1, -2, top = 10))
        understoodText = text("", 17f, grey).also { req.addView(it, lp(-1, -2, top = 10)) }
        root.addView(req, lp(-1, -2, top = 30))

        // developer view (long-press the app name)
        devBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        demo?.let { devBox.addView(it, LinearLayout.LayoutParams(-1, dp(640))) }
        signalText = text("", 15f, Color.parseColor("#C7CED9")).also { devBox.addView(it, lp(-1, -2, top = 12)) }
        devBox.addView(devPanel(), lp(-1, -2, top = 12))
        root.addView(devBox, lp(-1, -2, top = 24))

        return ScrollView(this).apply { setBackgroundColor(bgBase); addView(root) }
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
        val p = vbox(card)
        p.addView(text("DEV · 시연용 입력", 14f, grey))
        val speedLabel = text("DEV 차속: 0 km/h", 15f, Color.WHITE)
        p.addView(speedLabel)
        p.addView(seek(140, 0) { v -> drive.speedFromDev = true; drive.speedKmh = v.toFloat(); speedLabel.text = "DEV 차속: $v km/h" })
        val steerLabel = text("DEV 핸들: 0°", 15f, Color.WHITE)
        p.addView(steerLabel)
        p.addView(seek(1080, 540) { v -> drive.steerDeg = (v - 540).toFloat(); steerLabel.text = "DEV 핸들: ${v - 540}°" })
        val r = row()
        r.addView(chip("깜빡이") { drive.turn = when (drive.turn) { 0 -> -1; -1 -> 1; else -> 0 } })
        r.addView(chip("브레이크(누르기)").apply {
            setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> drive.brake = true
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { drive.brake = false; v.performClick() }
                }
                true
            }
        })
        r.addView(chip("와이퍼") { drive.rain = when { drive.rain == 0f -> 0.5f; drive.rain < 1f -> 1f; else -> 0f } })
        r.addView(chip("전조등") { drive.night = !drive.night })
        r.addView(chip("궤적 1분") { Jam.fastTrajectory = !Jam.fastTrajectory })
        p.addView(r, lp(-1, -2, top = 8))
        return p
    }

    // ---------------- small view helpers ----------------
    private fun transport(kind: TransportIcon.Kind, size: Int, color: Int, onClick: () -> Unit) = TransportIcon(this).apply {
        this.kind = kind
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        fg = if (kind == TransportIcon.Kind.PLAY || kind == TransportIcon.Kind.PAUSE) bgBase else Color.WHITE
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        setOnClickListener { onClick() }
    }

    private fun chipRow(labels: List<String>, out: MutableList<TextView>, selected: Int, onPick: (Int) -> Unit): View {
        val r = row()
        labels.forEachIndexed { i, l ->
            val c = text(l, 20f, Color.WHITE).apply {
                setPadding(dp(24), dp(12), dp(24), dp(12))
                setOnClickListener { out.forEachIndexed { k, o -> o.tag = k == i; paint(o, k == i) }; onPick(i) }
            }
            c.tag = i == selected
            paint(c, i == selected)
            out += c
            r.addView(c, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
        }
        return r
    }

    private fun chip(label: String, outline: Boolean = false, onClick: (() -> Unit)? = null) = text(label, 18f, Color.WHITE).apply {
        background = if (outline) GradientDrawable().apply { setStroke(dp(2), chipOff); cornerRadius = dp(40).toFloat() } else round(chipOff, 40)
        setPadding(dp(20), dp(10), dp(20), dp(10))
        onClick?.let { f -> setOnClickListener { f() } }
    }

    private fun paint(v: TextView, on: Boolean) {
        v.background = round(if (on) accent else chipOff, 40)
        v.setTextColor(if (on) bgBase else Color.WHITE)
    }

    private fun seek(max: Int, start: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
        this.max = max; progress = start
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, v: Int, user: Boolean) { if (user) onChange(v) }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun vbox(color: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = round(color, 24); setPadding(dp(28), dp(22), dp(28), dp(24))
    }

    private fun label(t: String) = text(t, 16f, grey).apply { layoutParams = lp(-1, -2, top = 16).apply { bottomMargin = dp(8) } }

    private fun text(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    private fun blend(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
        (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
        (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt(),
    )

    private fun round(color: Int, r: Int = 14) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
    private fun lp(w: Int, h: Int, top: Int = 0) = LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
