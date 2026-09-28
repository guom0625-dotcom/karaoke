package com.guom.karaoke

data class ParsedTitle(
    val title: String,
    val artist: String,
    val karaokeNo: String?,
    /** 여자키, 남자키, 멜로디제거, MR Live 등. 기본 반주면 null */
    val variant: String?,
)

/**
 * 노래방 채널 영상 제목 파서. 형식이 맞지 않으면(홍보 영상 등) null.
 *  TJ: "[TJ노래방 / 여자키] 곡명 - 가수 / TJ Karaoke"   (번호는 설명란 "곡번호.328")
 *  KY: "[멜로디제거] 곡명 - 가수 (KY.84784) / KY KARAOKE"
 * 오래된 영상은 "/ TJ Karaoke / 1000만뷰", "(KY.87706) / Karaoke" 처럼 조금씩 다르다.
 */
object TitleParser {
    private val BRACKET = Regex("^\\[([^\\]]*)]\\s*")
    private val SUFFIX = Regex("\\s*/\\s*(?:TJ|KY)?\\s*Karaoke(?:\\s*/.*)?$", RegexOption.IGNORE_CASE)
    private val KY_NO = Regex("\\s*\\(KY\\.(\\d+)\\)\\s*$")
    private val DESC_NO = Regex("곡번호\\.?\\s*(\\d+)")

    fun parse(rawTitle: String, description: String = ""): ParsedTitle? {
        var s = rawTitle.trim()

        var variant: String? = null
        BRACKET.find(s)?.let { m ->
            variant = m.groupValues[1].split("/")
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.contains("노래방") }
                .joinToString(" ")
                .ifEmpty { null }
            s = s.substring(m.range.last + 1)
        }

        val suffix = SUFFIX.find(s) ?: return null
        s = s.substring(0, suffix.range.first)

        var no: String? = null
        KY_NO.find(s)?.let {
            no = it.groupValues[1]
            s = s.substring(0, it.range.first)
        }
        if (no == null) no = DESC_NO.find(description)?.groupValues?.get(1)

        val sep = lastTopLevelDash(s)
        if (sep < 0) return null
        val title = s.substring(0, sep).trim()
        val artist = s.substring(sep + 3).trim()
        if (title.isEmpty() || artist.isEmpty()) return null
        return ParsedTitle(title, artist, no, variant)
    }

    /** 괄호 밖에 있는 마지막 " - " 위치. 가수명 괄호 안의 "-" 는 무시한다. */
    private fun lastTopLevelDash(s: String): Int {
        var depth = 0
        var found = -1
        for (i in s.indices) {
            when (s[i]) {
                '(' -> depth++
                ')' -> if (depth > 0) depth--
                ' ' -> if (depth == 0 && s.startsWith(" - ", i)) found = i
            }
        }
        return found
    }
}

/** ISO 8601 기간(PT3M25S 등)을 초로 변환. 형식이 다르면 0. */
fun parseIsoDuration(s: String): Int {
    val m = Regex("P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?").matchEntire(s) ?: return 0
    val (d, h, min, sec) = m.destructured
    return (d.toIntOrNull() ?: 0) * 86400 + (h.toIntOrNull() ?: 0) * 3600 +
        (min.toIntOrNull() ?: 0) * 60 + (sec.toIntOrNull() ?: 0)
}
