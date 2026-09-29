package com.guom.karaoke

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrCodes {
    /** QR 비트맵 (오버레이 창용) */
    fun bitmap(text: String, sizePx: Int): android.graphics.Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8")
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(m.width * m.height) { i ->
            if (m.get(i % m.width, i / m.width)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
        return android.graphics.Bitmap.createBitmap(pixels, m.width, m.height, android.graphics.Bitmap.Config.ARGB_8888)
    }

    /** QR 을 SVG 로 (검은 칸 = 1×1 사각형 경로). 배경 흰색, 여백 2칸. */
    fun svg(text: String): String {
        val hints = mapOf(
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        )
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val path = StringBuilder()
        for (y in 0 until m.height) for (x in 0 until m.width) {
            if (m.get(x, y)) path.append("M$x ${y}h1v1h-1z")
        }
        return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${m.width} ${m.height}" shape-rendering="crispEdges">""" +
            """<rect width="100%" height="100%" fill="#fff"/><path d="$path" fill="#000"/></svg>"""
    }
}
