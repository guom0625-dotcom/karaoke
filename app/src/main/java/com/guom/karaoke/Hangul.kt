package com.guom.karaoke

object Hangul {
    private const val CHOSUNG = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"

    /** 검색 키: 소문자로 바꾸고 공백·기호를 뺀다 ("당신만을 사랑해" == "당신만을사랑해") */
    fun normalize(s: String): String = buildString {
        for (c in s.lowercase()) if (c.isLetterOrDigit()) append(c)
    }

    /** 한글 음절을 초성으로 바꾼다. 나머지 문자는 그대로. */
    fun chosung(s: String): String = buildString {
        for (c in s) append(if (c in '가'..'힣') CHOSUNG[(c - '가') / 588] else c)
    }

    fun isChosungOnly(s: String) = s.isNotEmpty() && s.all { it in CHOSUNG }
}
