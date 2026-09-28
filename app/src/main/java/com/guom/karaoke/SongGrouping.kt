package com.guom.karaoke

import kotlinx.serialization.Serializable

/** 검색 결과 한 줄 = 한 곡. versions[0] 이 탭했을 때 예약되는 기본 버전. */
@Serializable
data class SongGroup(val title: String, val artist: String, val versions: List<Song>)

/**
 * 같은 곡(TJ·금영, 기본·여자키·멜로디제거 등)을 한 줄로 묶는다.
 * 같은 곡 판단: 괄호 안 표기(영문 병기, 드라마명 등)를 뺀 곡명 + 가수명을 정규화해 비교.
 * 기본 버전: 선호 브랜드 기본 반주 > 다른 브랜드 기본 반주 > 선호 브랜드 버전 > 나머지.
 */
object SongGrouping {
    private val PARENS = Regex("\\([^)]*\\)|\\[[^]]*]")

    fun key(s: Song): String {
        fun part(text: String) = Hangul.normalize(text.replace(PARENS, "")).ifEmpty { Hangul.normalize(text) }
        return part(s.title) + "|" + part(s.artist)
    }

    /** songs 는 검색 순위 순. 그룹 순서는 그룹에서 가장 먼저 나온 곡의 순위를 따른다. */
    fun group(songs: List<Song>, preferredBrand: String, limit: Int = 30): List<SongGroup> {
        val groups = LinkedHashMap<String, MutableList<Song>>()
        for (s in songs) groups.getOrPut(key(s)) { mutableListOf() }.add(s)
        return groups.values.take(limit).map { versions ->
            val sorted = versions.sortedWith(
                compareBy<Song>({ it.variant != null }, { it.brand != preferredBrand })
            )
            SongGroup(sorted.first().title, sorted.first().artist, sorted)
        }
    }
}
