package com.guom.karaoke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodesTest {
    @Test
    fun svgIsSquareQr() {
        val svg = QrCodes.svg("http://192.168.43.1:8080/guest?room=abcdEFGH")
        assertTrue(svg.startsWith("<svg"))
        val (w, h) = Regex("""viewBox="0 0 (\d+) (\d+)"""").find(svg)!!.destructured
        assertEquals(w, h)
        assertTrue(w.toInt() >= 21 + 4) // 최소 버전(21칸) + 여백
        assertTrue(svg.contains("h1v1h-1z"))
    }
}
