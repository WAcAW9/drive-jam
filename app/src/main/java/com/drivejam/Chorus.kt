package com.drivejam

/**
 * Wordless backing-vocal arrangement: what the three-voice chorus sings in each bar.
 * Pads in the verse, a style-flavoured groove in the chorus (doo-wop, chops, long "예~"), a hum in the break.
 * Same track + same bar = same phrase, so a chorus comes back like a song's chorus does.
 */
object Chorus {
    data class Note(val step: Int, val syl: String, val len: Int, val vel: Float)

    /** Every syllable the engine can sing. */
    val SYLLABLES = listOf("우", "음", "아", "오", "밥", "바", "두", "다", "둠", "라", "나", "워우", "와", "예", "야", "샤")

    /** "step:syllable:length" separated by spaces. */
    private fun p(s: String, vel: Float = 0.75f) = s.split(' ').map {
        val (st, sy, ln) = it.split(':')
        Note(st.toInt(), sy, ln.toInt(), vel)
    }

    private val PADS = listOf("0:우:14", "0:아:14", "0:오:7 8:우:7", "0:음:14", "0:우:7 8:아:7").map { p(it, 0.5f) }
    private val HUMS = listOf("0:음:16", "0:우:8 8:오:8", "0:음:8 8:아:8").map { p(it, 0.55f) }
    private val GROOVES = mapOf(
        'A' to "0:워우:7 10:밥:2 12:밥:3",
        'B' to "0:아:6 8:두:2 10:두:2 12:둠:3",
        'C' to "2:샤:2 4:라:4 10:라:2 12:라:3",
        'D' to "0:예:6 8:예:6",
        'E' to "0:다:2 3:다:2 6:다:3 10:와:5",
        'F' to "0:우:14",
        'G' to "0:나:3 4:나:3 8:워우:7",
        'H' to "0:야:4 6:바:2 8:바:2 12:와:4",
    ).mapValues { p(it.value) }

    private fun groovesFor(s: Style) = when (s) {
        Style.CITY_POP -> "ABEGH"
        Style.HOUSE -> "CEDB"
        Style.LOFI -> "FDB"
        Style.SYNTHWAVE -> "DFCA"
        Style.ACOUSTIC -> "ABGEH"
        Style.CHILL -> "FDF"
    }

    fun bar(style: Style, seed: Int, bar: Int, traffic: Boolean): List<Note> {
        if (traffic) return HUMS[0] // stuck in traffic: just a hum
        if (bar < Song.INTRO_BARS) return emptyList()
        val rel = (bar - Song.INTRO_BARS) % Song.CYCLE
        val h = (seed and 0xFFFF) * 7 + rel
        return when (Song.section(bar)) {
            Song.Section.VERSE -> PADS[h % PADS.size]
            Song.Section.CHORUS, Song.Section.CHORUS2 -> groovesFor(style).let { GROOVES.getValue(it[h % it.length]) }
            Song.Section.BREAK -> HUMS[h % HUMS.size]
            Song.Section.INTRO -> emptyList()
        }
    }

    /** For tests: every phrase, so each syllable in them can be checked against [SYLLABLES]. */
    internal val allNotes get() = (PADS + HUMS + GROOVES.values).flatten()
}
