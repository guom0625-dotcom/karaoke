package com.guom.karaoke

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 1단계 테스트용 호스트 화면: 서버 시작, 유튜브 링크로 큐 추가, 크롬에서 플레이어 열기 */
class MainActivity : Activity() {
    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        startForegroundService(Intent(this, KaraokeService::class.java))

        val info = packageManager.getPackageInfo(packageName, 0)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val input = EditText(this).apply { hint = "유튜브 링크 또는 영상 ID" }
        val queueView = TextView(this).apply { textSize = 15f }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(TextView(context).apply {
                textSize = 20f
                text = "${getString(R.string.app_name)} v${info.versionName}"
            })
            addView(TextView(context).apply { text = "서버: $PLAYER_URL" })
            addView(Button(context).apply {
                text = "크롬에서 플레이어 열기"
                setOnClickListener { openPlayerInChrome() }
            })
            addView(input)
            addView(Button(context).apply {
                text = "예약 추가"
                setOnClickListener {
                    val text = input.text.toString()
                    scope.launch {
                        val item = YouTube.enqueue(text)
                        if (item == null) {
                            toast("영상 ID를 찾을 수 없어요")
                        } else {
                            input.setText("")
                            toast("추가: ${item.title}")
                        }
                    }
                }
            })
            addView(Button(context).apply {
                text = "다음 곡으로 스킵"
                setOnClickListener { QueueManager.skip() }
            })
            addView(queueView)
        }
        setContentView(ScrollView(this).apply { addView(root) })

        scope.launch {
            QueueManager.state.collect { s ->
                queueView.text = buildString {
                    append("\n▶ 재생 중: ").append(s.nowPlaying?.title ?: "없음").append("\n\n")
                    append("예약 ${s.queue.size}곡\n")
                    s.queue.forEachIndexed { i, item -> append("${i + 1}. ${item.title}\n") }
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** 프리미엄 로그인이 적용되도록 반드시 크롬으로 연다 (WebView 불가). */
    private fun openPlayerInChrome() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(PLAYER_URL))
        try {
            startActivity(Intent(intent).setPackage("com.android.chrome"))
        } catch (e: ActivityNotFoundException) {
            toast("크롬이 없어 기본 브라우저로 엽니다")
            startActivity(intent)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private val PLAYER_URL = "http://127.0.0.1:${KaraokeServer.PORT}/player"
    }
}
