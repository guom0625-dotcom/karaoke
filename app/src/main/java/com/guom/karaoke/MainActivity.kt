package com.guom.karaoke

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 호스트 화면 (임시): 플레이어 열기, API 키·동기화, 곡 검색·예약, 큐 확인 */
class MainActivity : Activity() {
    private val scope = MainScope()
    private val db by lazy { SongDb.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        startForegroundService(Intent(this, KaraokeService::class.java))

        val info = packageManager.getPackageInfo(packageName, 0)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val apiKeyInput = EditText(this).apply {
            hint = "YouTube Data API 키"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setText(Settings.apiKey(context).orEmpty())
        }
        val syncButton = Button(this)
        val syncStatus = TextView(this)
        val searchInput = EditText(this).apply {
            hint = "곡명, 가수, 번호 (초성 가능)"
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val queueView = TextView(this).apply { textSize = 15f }

        fun section(title: String) = TextView(this).apply {
            text = title
            textSize = 17f
            setPadding(0, pad, 0, pad / 4)
        }

        fun runSearch() {
            val q = searchInput.text.toString()
            scope.launch {
                val songs = withContext(Dispatchers.IO) { db.search(q) }
                results.removeAllViews()
                if (songs.isEmpty()) {
                    results.addView(TextView(this@MainActivity).apply { text = "결과 없음" })
                }
                for (song in songs) {
                    results.addView(TextView(this@MainActivity).apply {
                        text = song.label()
                        textSize = 15f
                        setPadding(0, pad / 2, 0, pad / 2)
                        setOnClickListener {
                            QueueManager.add(song)
                            toast("예약: ${song.title}")
                        }
                    })
                }
            }
        }

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

            addView(section("곡 DB"))
            addView(apiKeyInput)
            addView(Button(context).apply {
                text = "API 키 저장"
                setOnClickListener {
                    Settings.setApiKey(context, apiKeyInput.text.toString())
                    toast("저장했어요")
                }
            })
            addView(syncButton.apply {
                setOnClickListener {
                    if (SyncManager.isRunning()) SyncManager.cancel() else SyncManager.start(context)
                }
            })
            addView(syncStatus)

            addView(section("곡 검색 · 예약"))
            addView(searchInput.apply {
                setOnEditorActionListener { _, _, _ -> runSearch(); true }
            })
            addView(Button(context).apply {
                text = "검색"
                setOnClickListener { runSearch() }
            })
            addView(results)

            addView(section("예약 현황"))
            addView(Button(context).apply {
                text = "다음 곡으로 스킵"
                setOnClickListener { QueueManager.skip() }
            })
            addView(queueView)
        }
        setContentView(ScrollView(this).apply { addView(root) })

        scope.launch {
            syncStatus.text = withContext(Dispatchers.IO) { SyncManager.summary(db) }
            SyncManager.status.collect { s ->
                syncButton.text = if (s.running) "동기화 중지" else "채널 동기화"
                if (s.message.isNotEmpty()) syncStatus.text = s.message
            }
        }
        scope.launch {
            QueueManager.state.collect { s ->
                queueView.text = buildString {
                    append("▶ 재생 중: ").append(s.nowPlaying?.let { "${it.title} - ${it.artist}" } ?: "없음")
                    append("\n\n예약 ${s.queue.size}곡\n")
                    s.queue.forEachIndexed { i, item -> append("${i + 1}. ${item.title} - ${item.artist}\n") }
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun Song.label() = buildString {
        append(title).append(" - ").append(artist)
        if (variant != null) append(" [").append(variant).append("]")
        append("\n").append(brand)
        if (karaokeNo != null) append(" ").append(karaokeNo)
        append(" · ").append(durationSec / 60).append(":").append("%02d".format(durationSec % 60))
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
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private val PLAYER_URL = "http://127.0.0.1:${KaraokeServer.PORT}/player"
    }
}
