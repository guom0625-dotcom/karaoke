package com.guom.karaoke

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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

        // 호스트 화면이 차 화면에 미러링될 수 있으므로 키는 가리고, 저장 후엔 끝 4자리만 보여준다.
        val apiKeyStatus = TextView(this)
        fun showSavedKey() {
            val key = Settings.apiKey(this)
            apiKeyStatus.text = if (key.isNullOrBlank()) "저장된 API 키 없음" else "저장된 키: ••••${key.takeLast(4)}"
        }
        showSavedKey()
        val apiKeyInput = EditText(this).apply {
            hint = "YouTube Data API 키 (새로 입력 시에만)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val syncButton = Button(this)
        val syncStatus = TextView(this)
        val searchInput = EditText(this).apply {
            hint = "곡명, 가수, 번호 (초성 가능)"
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val updateStatus = TextView(this)
        val updateButton = Button(this)
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
            addView(updateStatus)
            addView(updateButton.apply { setOnClickListener { onUpdateButton() } })
            addView(Button(context).apply {
                text = "크롬에서 플레이어 열기"
                setOnClickListener { openPlayerInChrome() }
            })

            addView(section("곡 DB"))
            addView(apiKeyStatus)
            addView(apiKeyInput)
            addView(Button(context).apply {
                text = "API 키 저장"
                setOnClickListener {
                    val key = apiKeyInput.text.toString().trim()
                    if (key.isEmpty()) return@setOnClickListener toast("키를 입력하세요")
                    Settings.setApiKey(context, key)
                    apiKeyInput.setText("")
                    showSavedKey()
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
            Updater.state.collect { s ->
                updateStatus.text = when (s) {
                    Updater.State.Idle, Updater.State.UpToDate -> "최신 버전이에요"
                    Updater.State.Checking -> "업데이트 확인 중…"
                    is Updater.State.Available -> "새 버전 v${s.release.versionName}이 있어요\n" +
                        s.release.notes.lines().filter { it.isNotBlank() }.take(8).joinToString("\n")
                    is Updater.State.Downloading -> "v${s.release.versionName} 받는 중… ${s.percent}%"
                    Updater.State.WaitingForUser -> "설치 확인 화면에서 '업데이트'를 누르세요"
                    is Updater.State.Failed -> s.message
                }
                updateButton.text = if (s is Updater.State.Available) "업데이트" else "업데이트 확인"
                updateButton.isEnabled = s !is Updater.State.Checking && s !is Updater.State.Downloading
            }
        }
        // 앱 실행 시 1회 확인 (GitHub 비인증 API 한도: 시간당 60회)
        if (Updater.state.value == Updater.State.Idle) scope.launch { Updater.check(this@MainActivity) }

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

    private fun onUpdateButton() {
        val s = Updater.state.value
        if (s !is Updater.State.Available) {
            scope.launch { Updater.check(this@MainActivity) }
            return
        }
        // 최초 1회: "출처를 알 수 없는 앱 설치" 허용 화면으로 안내
        if (!packageManager.canRequestPackageInstalls()) {
            toast("이 앱의 '출처를 알 수 없는 앱 설치'를 허용한 뒤 다시 눌러 주세요")
            startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
            return
        }
        // 설치하면 앱이 재시작되어 서버·예약 목록·동기화가 끊긴다
        val q = QueueManager.state.value
        val busy = buildList {
            if (q.nowPlaying != null) add("재생 중인 곡")
            if (q.queue.isNotEmpty()) add("예약 ${q.queue.size}곡")
            if (SyncManager.isRunning()) add("진행 중인 동기화")
        }
        val start = { scope.launch { Updater.downloadAndInstall(this@MainActivity, s.release) }; Unit }
        if (busy.isEmpty()) {
            start()
        } else {
            AlertDialog.Builder(this)
                .setTitle("지금 업데이트할까요?")
                .setMessage("업데이트하면 앱이 다시 시작되어 ${busy.joinToString(", ")}이(가) 끊겨요. (동기화는 나중에 이어서 진행돼요)")
                .setPositiveButton("업데이트") { _, _ -> start() }
                .setNegativeButton("나중에", null)
                .show()
        }
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
