package com.guom.karaoke

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings as SystemSettings
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 유튜브 앱 화면 위 구석의 작은 창: 다음 곡 + 예약 QR. 터치는 아래(유튜브)로 통과시킨다.
 * Android 15+ 에서는 이 창이 떠 있어야 백그라운드에서 유튜브 앱을 열 수 있다.
 */
class OverlayWindow(private val context: Context, private val scope: CoroutineScope) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private val jobs = mutableListOf<Job>()
    private var qrFor: String? = null

    fun show() {
        if (root != null || !canShow(context)) return
        val d = context.resources.displayMetrics.density
        val next = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 2
            maxWidth = (180 * d).toInt()
        }
        val qr = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams((84 * d).toInt(), (84 * d).toInt()).apply { topMargin = (4 * d).toInt() }
        }
        val caption = TextView(context).apply {
            setTextColor(Color.parseColor("#dddddd"))
            textSize = 10f
            text = "📱 찍어서 노래 예약"
        }
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
            background = GradientDrawable().apply {
                setColor(Color.argb(150, 0, 0, 0))
                cornerRadius = 10 * d
            }
            addView(next)
            addView(qr)
            addView(caption)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (8 * d).toInt()
            y = (8 * d).toInt()
        }
        runCatching { wm.addView(view, params) }.onFailure { return }
        root = view

        jobs += scope.launch {
            QueueManager.state.collect { s ->
                next.text = when {
                    s.queue.isNotEmpty() -> "다음: ${s.queue[0].title} · ${s.queue[0].nickname}"
                    s.nowPlaying != null -> "다음 곡 없음"
                    else -> "예약된 곡이 없어요"
                }
            }
        }
        // 핫스팟 IP 가 바뀔 수 있어 QR 은 주기적으로 다시 만든다
        jobs += scope.launch {
            while (isActive) {
                val url = Nav.guestUrl(context)
                if (url != qrFor) {
                    qrFor = url
                    qr.setImageBitmap(url?.let { QrCodes.bitmap(it, (84 * d).toInt()) })
                    qr.visibility = if (url == null) android.view.View.GONE else android.view.View.VISIBLE
                    caption.visibility = qr.visibility
                }
                delay(30_000)
            }
        }
    }

    fun hide() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        qrFor = null
    }

    companion object {
        fun canShow(context: Context) = SystemSettings.canDrawOverlays(context)
    }
}
