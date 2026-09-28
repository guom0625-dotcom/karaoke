package com.guom.karaoke

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrCodes {
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

    /**
     * 와이파이 접속 QR 문자열. 안드로이드·iOS 카메라가 인식하는 표준 형식.
     * SSID·비밀번호의 \ ; , : " 는 역슬래시로 이스케이프한다.
     */
    fun wifiPayload(ssid: String, password: String): String {
        fun esc(s: String) = s.replace(Regex("""([\\;,:"])"""), """\\$1""")
        return if (password.isEmpty()) "WIFI:T:nopass;S:${esc(ssid)};;"
        else "WIFI:T:WPA;S:${esc(ssid)};P:${esc(password)};;"
    }
}
