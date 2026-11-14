package com.drivejam

/**
 * "비 오는 날 카페 같은 음악으로" → style + mood nudge. On-device keyword dictionary (no network, no lag).
 * The same parser will take STT text once SpeechToText is released, and MediaSession.onPlayFromSearch now.
 */
data class MusicRequest(
    val style: Style?,       // null = keep the style (unless newTrack)
    val mood: Mood,          // nudge added to the target (energy = a, brightness = v)
    val newTrack: Boolean,   // switch to another song even without a named style
    val summary: String,     // "더 차분한 로파이" — shown back to the listener
)

object RequestParser {
    private val styleWords = linkedMapOf(
        Style.CITY_POP to listOf("시티팝", "시티 팝", "레트로", "80년대", "90년대", "청량", "여름", "바다"),
        Style.HOUSE to listOf("하우스", "클럽", "edm", "댄스", "파티", "춤", "디제잉"),
        Style.LOFI to listOf("로파이", "lofi", "lo-fi", "카페", "공부", "재즈", "비 오는", "빗소리", "커피"),
        Style.SYNTHWAVE to listOf("신스웨이브", "신스", "몽환", "네온", "사이버", "밤 드라이브", "레트로웨이브"),
        Style.ACOUSTIC to listOf("어쿠스틱", "기타", "포크", "소풍", "동요", "따뜻한"),
        Style.CHILL to listOf("칠", "앰비언트", "명상", "고요", "새벽", "힐링"),
    )
    private val up = listOf("신나", "신난", "흥겨", "텐션", "에너지", "빠르", "빠른", "업템포", "들뜬")
    private val down = listOf("차분", "잔잔", "조용", "편안", "느리", "느린", "릴렉스", "쉬고", "나른", "부드러")
    private val bright = listOf("밝", "행복", "상쾌", "기분 좋", "산뜻", "경쾌")
    private val dark = listOf("우울", "슬픈", "감성", "센치", "쓸쓸", "어두")
    private val change = listOf("다른", "바꿔", "완전", "새로운", "말고", "다음")

    fun parse(text: String): MusicRequest {
        val t = text.lowercase()
        val style = styleWords.entries
            .map { (s, ws) -> s to ws.filter { it in t } }
            .filter { it.second.isNotEmpty() }
            .maxWithOrNull(compareBy<Pair<Style, List<String>>> { it.second.size }.thenBy { -t.indexOf(it.second.first()) })
            ?.first
        fun has(ws: List<String>) = ws.any { it in t }
        val a = (if (has(up)) 0.3f else 0f) - (if (has(down)) 0.3f else 0f)
        val v = (if (has(bright)) 0.25f else 0f) - (if (has(dark)) 0.25f else 0f)
        val wantsChange = has(change)
        val nothing = style == null && a == 0f && v == 0f
        val newTrack = style != null || wantsChange || nothing // naming a style = "play me that kind of song"

        val energy = when { a > 0f -> "더 신나는"; a < 0f -> "더 차분한"; else -> "" }
        val color = when { v > 0f -> "밝은"; v < 0f -> "감성적인"; else -> "" }
        val what = style?.label ?: if (newTrack) "다른 곡" else "분위기"
        val summary = listOf(energy, color, what).filter { it.isNotEmpty() }.joinToString(" ")
        return MusicRequest(style, Mood(v, a), newTrack, summary)
    }
}
