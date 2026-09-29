package com.guom.karaoke

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings as SystemSettings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 유튜브 앱 화면 위 구석의 작은 창: 다음 곡 + 예약 QR + 🔍 예약 버튼.
 * 🔍 예약을 누르면 화면 오른쪽에 리모컨 패널(호스트 리모컨 페이지를 WebView 로)이 뜨고,
 * 왼쪽에선 유튜브 영상이 계속 재생된다. 패널 밖 터치는 유튜브로 간다.
 * (WebView 에는 우리 리모컨 페이지만 띄운다. 유튜브 영상은 유튜브 앱이 재생)
 * Android 15+ 에서는 이 창이 떠 있어야 백그라운드에서 유튜브 앱을 열 수 있다.
 */
class OverlayWindow(private val base: Context, private val scope: CoroutineScope) {
    /** 목표 디스플레이(Tesor 차 화면 등) 기준 컨텍스트 — show() 때마다 다시 고른다 */
    private var context: Context = base
    private var wm: WindowManager = base.getSystemService(WindowManager::class.java)
    /** 지금 창이 붙어 있는 디스플레이 */
    var displayId: Int = -1
        private set
    private var root: LinearLayout? = null
    private var panel: LinearLayout? = null
    private var webView: WebView? = null
    private val jobs = mutableListOf<Job>()
    private var qrFor: String? = null

    fun show() {
        if (root != null || !canShow(base)) return
        context = Displays.overlayContext(base)
        wm = context.getSystemService(WindowManager::class.java)
        displayId = Displays.targetId(base)
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
            addView(Button(context).apply {
                text = "🔍 예약"
                textSize = 13f
                isAllCaps = false
                setTextColor(Color.BLACK)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#ffcc33"))
                    cornerRadius = 8 * d
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (36 * d).toInt()
                ).apply { topMargin = (6 * d).toInt() }
                setOnClickListener { openPanel() }
            })
        }
        // 작은 창만 터치를 받는다 (창 밖은 유튜브로). 위치는 끌어서 옮기고 기억한다.
        val metrics = context.resources.displayMetrics
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val saved = Settings.overlayPosition(context)
            x = saved?.first ?: (metrics.widthPixels - (210 * d).toInt())
            y = saved?.second ?: (8 * d).toInt()
        }
        view.setOnTouchListener(DragToMove(params, view))
        runCatching { wm.addView(view, params) }.onFailure { return }
        root = view
        active = true

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
                    qr.visibility = if (url == null) View.GONE else View.VISIBLE
                    caption.visibility = qr.visibility
                }
                delay(30_000)
            }
        }
    }

    /** 작은 창 끌어서 옮기기: 조금 움직이면 무시(탭), 화면 밖으로는 못 나가게, 놓으면 위치 저장 */
    private inner class DragToMove(
        private val params: WindowManager.LayoutParams,
        private val view: View,
    ) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(v: View, e: android.view.MotionEvent): Boolean {
            val slop = 8 * context.resources.displayMetrics.density
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    dragging = false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) dragging = true
                    if (dragging) {
                        val m = context.resources.displayMetrics
                        params.x = (startX + dx.toInt()).coerceIn(0, maxOf(0, m.widthPixels - view.width))
                        params.y = (startY + dy.toInt()).coerceIn(0, maxOf(0, m.heightPixels - view.height))
                        runCatching { wm.updateViewLayout(view, params) }
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (dragging) Settings.setOverlayPosition(context, params.x, params.y)
                }
            }
            return true
        }
    }

    /** 리모컨 패널: 화면 오른쪽 약 절반, 키보드 입력 가능, 패널 밖 터치는 유튜브로 */
    private fun openPanel() {
        if (panel != null) return
        val d = context.resources.displayMetrics.density
        val screenW = context.resources.displayMetrics.widthPixels
        val width = maxOf((360 * d).toInt(), (screenW * 0.45).toInt()).coerceAtMost(screenW)
        Sessions.init(context)
        val web = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(Color.parseColor("#111318"))
            webViewClient = WebViewClient() // 링크를 외부 브라우저로 넘기지 않음
            loadUrl("http://127.0.0.1:${KaraokeServer.PORT}/guest?host=${Sessions.hostToken}")
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val close = Button(context).apply {
            text = "✕ 닫기 (영상으로 돌아가기)"
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2b2f3a"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (44 * d).toInt())
            setOnClickListener { closePanel() }
        }
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111318"))
            addView(close)
            addView(web)
        }
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, // 포커스는 받되(키보드) 패널 밖 터치는 유튜브로
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        runCatching { wm.addView(view, params) }.onFailure { web.destroy(); return }
        panel = view
        webView = web
        root?.visibility = View.GONE
    }

    private fun closePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        webView?.destroy()
        panel = null
        webView = null
        root?.visibility = View.VISIBLE
    }

    /** 표시할 디스플레이가 바뀌었으면 그 화면으로 옮긴다 */
    fun moveIfNeeded() {
        if (root != null && displayId != Displays.targetId(base)) {
            hide()
            show()
        }
    }

    fun hide() {
        closePanel()
        jobs.forEach { it.cancel() }
        jobs.clear()
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        active = false
        displayId = -1
        qrFor = null
    }

    companion object {
        fun canShow(context: Context) = SystemSettings.canDrawOverlays(context)

        /** 오버레이 창이 떠 있는지 — 크롬 플레이어는 이때 페이지 안 QR·🔍 예약을 숨긴다 */
        @Volatile
        var active = false
            private set
    }
}
