package com.drivejam

/** Chord instrument. */
enum class Keys {
    EP,      // FM electric piano (Rhodes-like) chords
    GUITAR,  // Karplus-Strong plucked string, arpeggiated
    SAW,     // filtered saw stabs / arpeggio (house, synthwave)
    PAD,     // soft sustained pad only (chill bed)
}

/** Melody instrument. */
enum class Lead { SQUARE, SAW, EP, STRING }

/** Bass instrument. */
enum class Bass { ROUND, PLUCK, SUB }

/**
 * A genre preset. Patterns are 16 steps.
 * Drums: 'x' hit, 'g' ghost (soft), '.' rest.  Bass: 'x' root, 'o' octave up, '5' fifth, '.' rest.
 * Comp: when the chord instrument plays. Mood moves tempo/mode/density inside the style;
 * car signals play on top of every style.
 */
enum class Style(
    val label: String,
    val baseBpm: Int,
    val kick: String,
    val snare: String,
    val hat: String,
    val openHat: String,
    val bass: String,
    val comp: String,
    val keys: Keys,
    val lead: Lead,
    val bassSound: Bass,
    val add9: Boolean,
    val swing: Float,
    val crackle: Boolean,
    val sidechain: Boolean,
    val space: Float,       // 0..1 echo amount
    val colors: IntArray,   // cover / UI palette
    val titles: List<String>,
) {
    CITY_POP(
        "시티팝", 104,
        "x.....x...x.....", "....x..g....x..g", "x.x.x.x.x.x.x.x.", "..............x.",
        "x..o..x.5.x..o..", "..x..x....x..x..", Keys.EP, Lead.SQUARE, Bass.PLUCK, true, 0.08f, false, false, 0.3f,
        intArrayOf(0xFFFF8A5B.toInt(), 0xFF3A86FF.toInt()),
        listOf("바다 쪽 창문", "여름 고속도로", "오렌지빛 신호", "토요일 오후 세 시", "파도 소리 라디오", "해 질 녘 드라이브"),
    ),
    HOUSE(
        "하우스", 122,
        "x...x...x...x...", "....x.......x...", "x.x.x.x.x.x.x.x.", "..x...x...x...x.",
        "..x...o...x...o.", "..x.....x.x.....", Keys.SAW, Lead.SQUARE, Bass.SUB, false, 0f, false, true, 0.25f,
        intArrayOf(0xFF7B2FF7.toInt(), 0xFFF107A3.toInt()),
        listOf("한밤의 톨게이트", "네 바퀴의 그루브", "가속 차선", "불빛 사이로", "새벽 두 시의 리듬", "고가도로 위에서"),
    ),
    LOFI(
        "로파이", 82,
        "x......x..x.....", "....x.......x..g", "x.x.x.x.x.x.x.x.", "................",
        "x.......x..5....", "x.......x...x...", Keys.EP, Lead.EP, Bass.ROUND, true, 0.28f, true, false, 0.35f,
        intArrayOf(0xFFC08552.toInt(), 0xFF2E2A26.toInt()),
        listOf("와이퍼 사이의 오후", "창가 자리", "느린 월요일", "따뜻한 커피 한 잔", "빗방울 카세트", "오래된 라디오"),
    ),
    SYNTHWAVE(
        "신스웨이브", 100,
        "x.......x.......", "....x.......x...", "x.x.x.x.x.x.x.x.", "................",
        "xoxoxoxoxoxoxoxo", "................", Keys.SAW, Lead.SAW, Bass.PLUCK, false, 0f, false, false, 0.45f,
        intArrayOf(0xFFFF2E97.toInt(), 0xFF1A1040.toInt()),
        listOf("네온 하이웨이", "자정의 터널", "보랏빛 계기판", "끝없는 차선", "레트로 선셋", "밤의 속도"),
    ),
    ACOUSTIC(
        "어쿠스틱", 96,
        "x.......x.......", "....x.......x...", "..x...x...x...x.", "................",
        "x.......x...5...", "x.x.x.x.x.x.x.x.", Keys.GUITAR, Lead.STRING, Bass.ROUND, false, 0.05f, false, false, 0.25f,
        intArrayOf(0xFF7FD8B4.toInt(), 0xFFF6C28B.toInt()),
        listOf("소풍 가는 길", "뒷좌석 노래", "초록 신호", "햇살 드는 창", "주말 아침", "느긋한 국도"),
    ),
    CHILL(
        "칠", 76,
        "x...............", "........x.......", "................", "................",
        "x.......x.......", "x.......x.......", Keys.PAD, Lead.EP, Bass.SUB, true, 0f, false, false, 0.6f,
        intArrayOf(0xFF2C5F8A.toInt(), 0xFF0B1320.toInt()),
        listOf("달빛 갓길", "조용한 해안도로", "천천히, 우리 둘", "안개 낀 다리", "늦은 밤 귀갓길", "구름 아래"),
    );

    fun hit(pattern: String, step: Int) = pattern[step] != '.'
}

/** One "song": a style in a key with a seeded variation. Same track = same music (reproducible for ⏮). */
data class Track(val style: Style, val key: Int, val seed: Int) {
    val title get() = style.titles[Math.floorMod(seed, style.titles.size)]
    val artist get() = "Drive JAM · ${style.label}"
    val variant get() = Math.floorMod(seed ushr 4, 3) // chord progression from the scale's pool
}

object TrackPicker {
    /** How well each style fits who is in the car (index = Style.ordinal). */
    private val fit = mapOf(
        Company.SOLO to floatArrayOf(0.6f, 0.3f, 1.0f, 0.6f, 0.5f, 0.7f),
        Company.FRIENDS to floatArrayOf(1.0f, 1.0f, 0.3f, 0.7f, 0.4f, 0.2f),
        Company.PARTNER to floatArrayOf(0.7f, 0.2f, 0.8f, 0.5f, 0.5f, 1.0f),
        Company.FAMILY to floatArrayOf(0.7f, 0.3f, 0.4f, 0.3f, 1.0f, 0.5f),
    )

    fun weight(style: Style, company: Company, s: Situation, personal: Float): Float {
        var w = fit.getValue(company)[style.ordinal] * personal
        if (s.night && (style == Style.SYNTHWAVE || style == Style.CHILL)) w *= 1.5f
        if (s.rain && (style == Style.LOFI || style == Style.CHILL)) w *= 1.5f
        return w
    }

    /** Weighted pick that never repeats a style in [avoid]; [forced] wins when given. */
    fun pick(
        company: Company, s: Situation, personal: (Style) -> Float,
        avoid: Set<Style>, rnd: java.util.Random, forced: Style? = null,
    ): Track {
        val style = forced ?: run {
            val pool = Style.entries.filter { it !in avoid }.ifEmpty { Style.entries }
            val ws = pool.map { weight(it, company, s, personal(it)) }
            var r = rnd.nextFloat() * ws.sum()
            pool.indices.firstOrNull { i -> r -= ws[i]; r <= 0f }?.let { pool[it] } ?: pool.last()
        }
        return Track(style, key = rnd.nextInt(12) - 5, seed = rnd.nextInt())
    }
}

/** Song form: sections by bar, and the melody motif a track's seed writes. Pure, unit-testable. */
object Song {
    enum class Section { INTRO, VERSE, CHORUS, BREAK, CHORUS2 }

    const val INTRO_BARS = 2
    const val CYCLE = 24

    /** 2-bar intro once, then verse 4 · chorus 8 · break 4 · chorus 8, repeating from the verse (hook within ~15 s). */
    fun section(bar: Int): Section {
        if (bar < INTRO_BARS) return Section.INTRO
        return when ((bar - INTRO_BARS) % CYCLE) {
            in 0..3 -> Section.VERSE
            in 4..11 -> Section.CHORUS
            in 12..15 -> Section.BREAK
            else -> Section.CHORUS2
        }
    }

    /** Bar inside the current melody section (0..7), or -1 where there is no melody. */
    fun melodyBar(bar: Int): Int {
        if (bar < INTRO_BARS) return -1
        val rel = (bar - INTRO_BARS) % CYCLE
        return when (section(bar)) {
            Section.CHORUS -> rel - 4; Section.BREAK -> rel - 12; Section.CHORUS2 -> rel - 16; else -> -1
        }
    }

    /** Last bar before a chorus gets a drum fill. */
    fun fillBar(bar: Int) = bar >= INTRO_BARS && ((bar - INTRO_BARS) % CYCLE).let { it == 3 || it == 15 }

    private val rhythmA = listOf("x..x..x...x.x...", "..x.x...x..x.x..", "x...x.x...x..x..", "x.x...x.x.x.....")
    private val rhythmB = listOf("x..x..x.........", "..x..x..x.x.x...", "x...x...x.......", "x.x.x.......x...")

    /**
     * 8-bar melody as 128 steps: -1 = rest, otherwise a chord-tone index (0..5 = two octaves of the chord),
     * so the tune follows the harmony. Phrases: motif, motif', motif, answer (resolves to the root).
     */
    fun melody(seed: Int): IntArray {
        val r = java.util.Random(seed.toLong() * 31 + 7)
        val a = rhythmA[r.nextInt(rhythmA.size)]
        val b = rhythmB[r.nextInt(rhythmB.size)]
        val motif = (a + b).toCharArray()
        val notes = IntArray(32) { -1 }
        var n = 2 + r.nextInt(2)
        for (i in 0 until 32) if (motif[i] == 'x') {
            notes[i] = n
            n = (n + listOf(-1, 1, 1, -2, 2, 0)[r.nextInt(6)]).coerceIn(0, 5)
        }
        val out = IntArray(128) { -1 }
        for (phrase in 0 until 4) for (i in 0 until 32) {
            var v = notes[i]
            if (v >= 0 && phrase == 1 && i >= 24) v = (v + 1).coerceAtMost(5)        // motif': lift the ending
            if (phrase == 3 && i >= 16) v = if (motif[i] == 'x') (if (i >= 24) 0 else 2) else -1 // answer → root
            out[phrase * 32 + i] = v
        }
        val last = (96 until 128).lastOrNull { out[it] >= 0 }
        if (last != null) out[last] = 0 // the answer always lands on the root
        return out
    }
}
