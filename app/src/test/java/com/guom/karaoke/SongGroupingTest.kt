package com.guom.karaoke

import org.junit.Assert.assertEquals
import org.junit.Test

class SongGroupingTest {
    private fun song(id: String, brand: String, title: String, artist: String, variant: String? = null) =
        Song(id, "UC$brand", brand, title, artist, null, variant, 240)

    private val songs = listOf(
        song("t1f", "TJ", "괜찮아도괜찮아(That's okay)", "도경수(D.O.)", "여자키"),
        song("k1", "KY", "괜찮아도 괜찮아", "도경수"),
        song("t1", "TJ", "괜찮아도괜찮아(That's okay)", "도경수(D.O.)"),
        song("k1m", "KY", "괜찮아도 괜찮아", "도경수", "멜로디제거"),
        song("t2", "TJ", "좋니", "윤종신"),
    )

    @Test
    fun groupsSameSongAcrossBrandsAndVariants() {
        val groups = SongGrouping.group(songs, preferredBrand = "TJ")
        assertEquals(2, groups.size)
        assertEquals(4, groups[0].versions.size)
        assertEquals("좋니", groups[1].title)
    }

    @Test
    fun defaultIsPreferredBrandBasicThenOtherBrandBasic() {
        val tj = SongGrouping.group(songs, preferredBrand = "TJ")[0]
        assertEquals(listOf("t1", "k1", "t1f", "k1m"), tj.versions.map { it.videoId })

        val ky = SongGrouping.group(songs, preferredBrand = "KY")[0]
        assertEquals(listOf("k1", "t1", "k1m", "t1f"), ky.versions.map { it.videoId })

        // 선호 브랜드에 기본 반주가 없으면 다른 브랜드 기본 반주
        val onlyKyBasic = SongGrouping.group(songs.filter { it.videoId != "t1" }, preferredBrand = "TJ")[0]
        assertEquals("k1", onlyKyBasic.versions[0].videoId)
    }

    @Test
    fun alternativesPreferSameVariantThenBasicThenOtherBrand() {
        val failed = QueueItem(1, "t1f", "괜찮아도괜찮아(That's okay)", "도경수(D.O.)", "TJ", null, "여자키", 240, "a", "앨리스", 0)
        val extra = song("k1f", "KY", "괜찮아도 괜찮아", "도경수", "여자키")
        val alts = SongGrouping.alternatives(failed, songs + extra + song("x", "KY", "다른곡", "도경수"), tried = setOf("t1f"))
        assertEquals(listOf("k1f", "k1", "t1", "k1m"), alts.map { it.videoId })

        // 이미 실패한 영상은 다시 고르지 않음
        val next = SongGrouping.alternatives(failed, songs + extra, tried = setOf("t1f", "k1f", "k1"))
        assertEquals("t1", next.first().videoId)
    }

    @Test
    fun alternativeQueryDropsParenthesizedNotes() {
        assertEquals("괜찮아도괜찮아 도경수", SongGrouping.alternativeQuery("괜찮아도괜찮아(That's okay)", "도경수(D.O.)"))
    }

    @Test
    fun differentArtistsAreNotMerged() {
        val groups = SongGrouping.group(
            listOf(song("a", "TJ", "사랑", "가수A"), song("b", "TJ", "사랑", "가수B")),
            preferredBrand = "TJ",
        )
        assertEquals(2, groups.size)
    }
}
