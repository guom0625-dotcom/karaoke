package com.guom.karaoke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 실제 채널 영상 제목(2026-09 수집) 기준 */
class TitleParserTest {
    private val tjDesc = "당신만을 사랑해  --  혜은이\nTJ 노래방 곡번호.328\n\nTJ KARAOKE 유튜브 노래방으로 ..."

    @Test
    fun tjBasic() {
        val p = TitleParser.parse("[TJ노래방] 당신만을 사랑해 - 혜은이 / TJ Karaoke", tjDesc)!!
        assertEquals("당신만을 사랑해", p.title)
        assertEquals("혜은이", p.artist)
        assertEquals("328", p.karaokeNo)
        assertNull(p.variant)
    }

    @Test
    fun tjVariants() {
        val female = TitleParser.parse("[TJ노래방 / 여자키] 괜찮아도괜찮아(That's okay) - 도경수(D.O.) / TJ Karaoke")!!
        assertEquals("괜찮아도괜찮아(That's okay)", female.title)
        assertEquals("도경수(D.O.)", female.artist)
        assertEquals("여자키", female.variant)

        val mr = TitleParser.parse("[TJ노래방 / MR Live] A Boy From The Moon (2026) - MC THE MAX / TJ Karaoke")!!
        assertEquals("A Boy From The Moon (2026)", mr.title)
        assertEquals("MC THE MAX", mr.artist)
        assertEquals("MR Live", mr.variant)
    }

    @Test
    fun kyWithNumberInTitle() {
        val p = TitleParser.parse("[멜로디제거] 멍 때리다 - 태인 (KY.84784) / KY KARAOKE")!!
        assertEquals("멍 때리다", p.title)
        assertEquals("태인", p.artist)
        assertEquals("84784", p.karaokeNo)
        assertEquals("멜로디제거", p.variant)
    }

    @Test
    fun dashInsideParenthesesIsNotSeparator() {
        val p = TitleParser.parse(
            "[멜로디제거] 아프니까 사랑이죠 - 민경훈(It's love Because it hurts - Min Kyung-hoon) (KY.84772) / KY Karaoke"
        )!!
        assertEquals("아프니까 사랑이죠", p.title)
        assertEquals("민경훈(It's love Because it hurts - Min Kyung-hoon)", p.artist)
        assertEquals("84772", p.karaokeNo)
    }

    @Test
    fun quotesInTitle() {
        val p = TitleParser.parse("[멜로디제거] 그대 뒤에서 (드라마\"명가\") - AS ONE (KY.84774) / KY KARAOKE")!!
        assertEquals("그대 뒤에서 (드라마\"명가\")", p.title)
        assertEquals("AS ONE", p.artist)
    }

    @Test
    fun olderTitleFormats() {
        val tj = TitleParser.parse("[TJ노래방] 좋니 - 윤종신 / TJ Karaoke / 1000만뷰")!!
        assertEquals("좋니", tj.title)
        assertEquals("윤종신", tj.artist)
        assertNull(tj.variant)

        val ky = TitleParser.parse("안동역에서 - 진성 (KY.87706) / Karaoke")!!
        assertEquals("안동역에서", ky.title)
        assertEquals("진성", ky.artist)
        assertEquals("87706", ky.karaokeNo)
        assertNull(ky.variant)

        val noBracket = TitleParser.parse("남자라는 이유로 - 조항조 (KY.5282) / KY Karaoke")!!
        assertEquals("남자라는 이유로", noBracket.title)
        assertEquals("5282", noBracket.karaokeNo)

        val jp = TitleParser.parse(
            "베텔게우스 (베텔기우스) (드라마\"슈퍼 리치\") - 유리 (ベテルギウス (ドラマ\"SUPER RICH\") - 優里) (KY.44746) / KY KARAOKE"
        )!!
        assertEquals("베텔게우스 (베텔기우스) (드라마\"슈퍼 리치\")", jp.title)
        assertEquals("유리 (ベテルギウス (ドラマ\"SUPER RICH\") - 優里)", jp.artist)
        assertEquals("44746", jp.karaokeNo)
    }

    @Test
    fun nonKaraokeTitlesRejected() {
        assertNull(TitleParser.parse("TJ노래방 공식 유튜브채널 신곡 소개"))
        assertNull(TitleParser.parse("Deleted video"))
        assertNull(TitleParser.parse("[TJ노래방] 제목만있음 / TJ Karaoke"))
        assertNull(TitleParser.parse("KARAOKE - E LÀ KHÔNG THỂ | mất 1 ngày để yêu 1 người"))
    }

    @Test
    fun isoDuration() {
        assertEquals(205, parseIsoDuration("PT3M25S"))
        assertEquals(3720, parseIsoDuration("PT1H2M"))
        assertEquals(59, parseIsoDuration("PT59S"))
        assertEquals(0, parseIsoDuration("P0D"))
        assertEquals(0, parseIsoDuration("garbage"))
    }

    @Test
    fun hangulSearchKeys() {
        assertEquals("당신만을사랑해혜은이", Hangul.normalize("당신만을 사랑해 - 혜은이"))
        assertEquals("abc123", Hangul.normalize("A.B C-123!"))
        assertEquals("ㄷㅅㅁㅇㅅㄹㅎ", Hangul.chosung("당신만을사랑해"))
        assertEquals("ㄷㄱㅅd.o.", Hangul.chosung("도경수d.o."))
        assertTrue(Hangul.isChosungOnly("ㄷㅅㅁ"))
        assertFalse(Hangul.isChosungOnly("ㄷ신"))
        assertFalse(Hangul.isChosungOnly(""))
    }
}
