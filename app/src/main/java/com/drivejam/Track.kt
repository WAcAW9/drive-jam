package com.drivejam


/** How the chord instrument plays. */
enum class PadMode { SUSTAIN, STAB, ARP, PLUCK }

enum class Wave { SAW, TRI }

/**
 * A genre preset. Patterns are 16 steps: 'x' = hit, '.' = rest.
 * Mood still moves tempo/mode/density inside the style; car signals play on top of every style.
 */
enum class Style(
    val label: String,
    val baseBpm: Int,
    val kick: String,
    val snare: String,
    val hat: String,
    val openHat: String,
    val bass: String,
    val comp: String,       // chord rhythm for STAB / PLUCK
    val padMode: PadMode,
    val wave: Wave,
    val add9: Boolean,
    val swing: Float,
    val crackle: Boolean,
    val space: Float,       // 0..1 echo amount
    val colors: IntArray,   // cover / UI palette
    val titles: List<String>,
) {
    CITY_POP(
        "시티팝", 104,
        "x.....x...x.....", "....x.......x...", "x.x.x.x.x.x.x.x.", "..............x.",
        "x..x..x...x.x..x", "..x..x....x..x..", PadMode.STAB, Wave.SAW, true, 0.08f, false, 0.3f,
        intArrayOf(0xFFFF8A5B.toInt(), 0xFF3A86FF.toInt()),
        listOf("바다 쪽 창문", "여름 고속도로", "오렌지빛 신호", "토요일 오후 세 시", "파도 소리 라디오", "해 질 녘 드라이브"),
    ),
    HOUSE(
        "하우스", 122,
        "x...x...x...x...", "....x.......x...", "x.x.x.x.x.x.x.x.", "..x...x...x...x.",
        "..x...x...x...x.", "..x.....x.x.....", PadMode.STAB, Wave.SAW, false, 0f, false, 0.25f,
        intArrayOf(0xFF7B2FF7.toInt(), 0xFFF107A3.toInt()),
        listOf("한밤의 톨게이트", "네 바퀴의 그루브", "가속 차선", "불빛 사이로", "새벽 두 시의 리듬", "고가도로 위에서"),
    ),
    LOFI(
        "로파이", 82,
        "x......x..x.....", "....x.......x...", "x.x.x.x.x.x.x.x.", "................",
        "x.......x..x....", "x.......x.......", PadMode.PLUCK, Wave.TRI, true, 0.28f, true, 0.35f,
        intArrayOf(0xFFC08552.toInt(), 0xFF2E2A26.toInt()),
        listOf("와이퍼 사이의 오후", "창가 자리", "느린 월요일", "따뜻한 커피 한 잔", "빗방울 카세트", "오래된 라디오"),
    ),
    SYNTHWAVE(
        "신스웨이브", 100,
        "x.......x.x.....", "....x.......x...", "x.x.x.x.x.x.x.x.", "................",
        "xxxxxxxxxxxxxxxx", "................", PadMode.ARP, Wave.SAW, false, 0f, false, 0.45f,
        intArrayOf(0xFFFF2E97.toInt(), 0xFF1A1040.toInt()),
        listOf("네온 하이웨이", "자정의 터널", "보랏빛 계기판", "끝없는 차선", "레트로 선셋", "밤의 속도"),
    ),
    ACOUSTIC(
        "어쿠스틱", 96,
        "x.......x.......", "....x.......x...", "..x...x...x...x.", "................",
        "x.......x...x...", "x.x.x.x.x.x.x.x.", PadMode.PLUCK, Wave.TRI, false, 0.05f, false, 0.25f,
        intArrayOf(0xFF7FD8B4.toInt(), 0xFFF6C28B.toInt()),
        listOf("소풍 가는 길", "뒷좌석 노래", "초록 신호", "햇살 드는 창", "주말 아침", "느긋한 국도"),
    ),
    CHILL(
        "칠", 76,
        "x...............", "........x.......", "................", "................",
        "x.......x.......", "................", PadMode.SUSTAIN, Wave.TRI, true, 0f, false, 0.6f,
        intArrayOf(0xFF2C5F8A.toInt(), 0xFF0B1320.toInt()),
        listOf("달빛 갓길", "조용한 해안도로", "천천히, 우리 둘", "안개 낀 다리", "늦은 밤 귀갓길", "구름 아래"),
    );

    fun hit(pattern: String, step: Int) = pattern[step] == 'x'
}

/** One "song": a style in a key with a seeded variation. Same track = same music (reproducible for ⏮). */
data class Track(val style: Style, val key: Int, val seed: Int) {
    val title get() = style.titles[Math.floorMod(seed, style.titles.size)]
    val artist get() = "드라이브 잼 · ${style.label}"
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
