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
import android.provider.Browser
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

/** 호스트 화면 (임시): 플레이어 열기, 동승자 접속, API 키·동기화, 곡 검색·예약, 큐 관리 */
class MainActivity : Activity() {
    private val scope = MainScope()
    private val db by lazy { SongDb.get(this) }
    private val pad by lazy { (16 * resources.displayMetrics.density).toInt() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        Sessions.init(this)
        startForegroundService(Intent(this, KaraokeService::class.java))

        val info = packageManager.getPackageInfo(packageName, 0)

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
        var searchField = SearchField.ALL
        val fieldButton = Button(this)
        fun showField() {
            fieldButton.text = "검색 대상: " + when (searchField) {
                SearchField.ALL -> "전체"
                SearchField.TITLE -> "제목"
                SearchField.ARTIST -> "가수"
            } + " (눌러서 변경)"
        }
        showField()
        val updateStatus = TextView(this)
        val updateButton = Button(this)
        val nowPlayingView = TextView(this).apply { textSize = 15f }
        val queueList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val errorLog = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        resetButton = Button(this)
        val brandButton = Button(this)
        fun showBrand() {
            brandButton.text = "같은 곡이면 기본 브랜드: ${Settings.preferredBrand(this)} (눌러서 변경)"
        }
        showBrand()

        fun section(title: String) = TextView(this).apply {
            text = title
            textSize = 17f
            setPadding(0, pad, 0, pad / 4)
        }

        fun runSearch() {
            val q = searchInput.text.toString()
            scope.launch {
                val brand = Settings.preferredBrand(this@MainActivity)
                val groups = withContext(Dispatchers.IO) { SongGrouping.group(db.search(q, Settings.enabledChannels(this@MainActivity), searchField), brand) }
                results.removeAllViews()
                if (groups.isEmpty()) {
                    results.addView(TextView(this@MainActivity).apply { text = "결과 없음" })
                }
                for (g in groups) {
                    results.addView(TextView(this@MainActivity).apply {
                        val more = if (g.versions.size > 1) "  (버전 ${g.versions.size}개 · 길게 눌러 선택)" else ""
                        text = "${g.title} - ${g.artist}\n${g.versions[0].versionLabel()}$more"
                        textSize = 15f
                        setPadding(0, pad / 2, 0, pad / 2)
                        setOnClickListener { reserve(g.versions[0]) }
                        setOnLongClickListener { chooseVersion(g); true }
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
            addView(updateStatus)
            addView(updateButton.apply { setOnClickListener { onUpdateButton() } })
            addView(Button(context).apply {
                text = "크롬에서 플레이어 열기"
                setOnClickListener { openInChrome(playerUrl(), newTab = false) }
            })

            addView(section("동승자 접속"))
            addView(TextView(context).apply {
                text = guestAddresses()
                setTextIsSelectable(true)
            })
            addView(TextView(context).apply {
                text = "※ 앱을 다시 시작하면 주소(방 토큰)가 바뀌어요"
                textSize = 12f
            })
            addView(section("이 폰에서 동승자 테스트"))
            addView(Button(context).apply {
                text = "게스트1로 열기"
                setOnClickListener { openInChrome(localGuestUrl("1"), newTab = true) }
            })
            addView(Button(context).apply {
                text = "게스트2로 열기"
                setOnClickListener { openInChrome(localGuestUrl("2"), newTab = true) }
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
            addView(brandButton.apply {
                setOnClickListener {
                    val next = if (Settings.preferredBrand(context) == "TJ") "KY" else "TJ"
                    Settings.setPreferredBrand(context, next)
                    showBrand()
                }
            })
            // 브랜드 사용 켜기/끄기: 꺼진 브랜드는 검색·대체 후보·동기화에서 빠진다
            for (channel in Channels.ALL) {
                addView(Button(context).apply {
                    fun show() {
                        val on = Settings.isBrandEnabled(context, channel.brand)
                        text = "${channel.brand} 사용: ${if (on) "켬" else "끔"} (눌러서 ${if (on) "끄기" else "켜기"})"
                    }
                    show()
                    setOnClickListener {
                        val on = Settings.isBrandEnabled(context, channel.brand)
                        if (on && Settings.enabledChannels(context).size == 1) {
                            return@setOnClickListener toast("최소 한 브랜드는 켜 두어야 해요")
                        }
                        Settings.setBrandEnabled(context, channel.brand, !on)
                        show()
                    }
                })
            }
            addView(fieldButton.apply {
                setOnClickListener {
                    searchField = SearchField.entries[(searchField.ordinal + 1) % SearchField.entries.size]
                    showField()
                    if (searchInput.text.isNotBlank()) runSearch()
                }
            })
            addView(searchInput.apply {
                setOnEditorActionListener { _, _, _ -> runSearch(); true }
            })
            addView(Button(context).apply {
                text = "검색"
                setOnClickListener { runSearch() }
            })
            addView(results)

            addView(section("재생 오류"))
            addView(errorLog)
            addView(resetButton.apply {
                setOnClickListener {
                    scope.launch {
                        withContext(Dispatchers.IO) { db.resetUnplayable() }
                        refreshUnplayable()
                        toast("재생 불가 표시를 모두 풀었어요")
                    }
                }
            })

            addView(section("예약 현황 (눌러서 순서 변경·삭제)"))
            addView(Button(context).apply {
                text = "다음 곡으로 스킵"
                setOnClickListener { QueueManager.skip(Actor.Host) }
            })
            addView(nowPlayingView)
            addView(queueList)
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
        scope.launch {
            PlaybackLog.entries.collect { list ->
                errorLog.removeAllViews()
                if (list.isEmpty()) errorLog.addView(TextView(this@MainActivity).apply { text = "최근 오류 없음" })
                for (e in list) {
                    errorLog.addView(TextView(this@MainActivity).apply {
                        text = "${e.text}\n  ↳ 눌러서 https 테스트 페이지에서 열기"
                        textSize = 13f
                        setPadding(0, pad / 4, 0, pad / 4)
                        setOnClickListener { openInChrome("$EMBED_TEST_URL?v=${e.videoId}", newTab = true) }
                    })
                }
                refreshUnplayable()
            }
        }
        // 앱 실행 시 1회 확인 (GitHub 비인증 API 한도: 시간당 60회)
        if (Updater.state.value == Updater.State.Idle) scope.launch { Updater.check(this@MainActivity) }

        scope.launch {
            QueueManager.state.collect { s ->
                nowPlayingView.text = "▶ 재생 중: " +
                    (s.nowPlaying?.let { "${it.title} - ${it.artist} · ${it.nickname}" } ?: "없음") +
                    "\n예약 ${s.queue.size}곡"
                queueList.removeAllViews()
                s.queue.forEachIndexed { i, item ->
                    queueList.addView(TextView(this@MainActivity).apply {
                        text = "${i + 1}. ${item.title} - ${item.artist} · ${item.nickname}"
                        textSize = 15f
                        setPadding(0, pad / 3, 0, pad / 3)
                        setOnClickListener { manageItem(item) }
                    })
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private lateinit var resetButton: Button

    private suspend fun refreshUnplayable() {
        val n = withContext(Dispatchers.IO) { db.countUnplayable() }
        resetButton.text = "재생 불가 표시 초기화 (${n}곡)"
        resetButton.isEnabled = n > 0
    }

    private fun reserve(song: Song) {
        QueueManager.add(song, Actor.Host)
        toast("예약: ${song.title}")
    }

    private fun chooseVersion(g: SongGroup) {
        AlertDialog.Builder(this)
            .setTitle("${g.title} - ${g.artist}")
            .setItems(g.versions.map { it.versionLabel() }.toTypedArray()) { _, which -> reserve(g.versions[which]) }
            .show()
    }

    private fun manageItem(item: QueueItem) {
        AlertDialog.Builder(this)
            .setTitle("${item.title} · ${item.nickname}")
            .setItems(arrayOf("위로", "아래로", "맨 앞으로", "삭제")) { _, which ->
                when (which) {
                    0 -> QueueManager.move(item.id, -1, Actor.Host)
                    1 -> QueueManager.move(item.id, 1, Actor.Host)
                    2 -> QueueManager.move(item.id, -1000, Actor.Host)
                    3 -> QueueManager.cancel(item.id, Actor.Host)
                }
            }
            .show()
    }

    private fun Song.versionLabel() = buildString {
        append(brand)
        if (karaokeNo != null) append(" ").append(karaokeNo)
        append(" · ").append(variant ?: "기본 반주")
        append(" · ").append(durationSec / 60).append(":").append("%02d".format(durationSec % 60))
    }

    private fun playerUrl() = "http://127.0.0.1:${KaraokeServer.PORT}/player?host=${Sessions.hostToken}"

    private fun localGuestUrl(profile: String) =
        "http://127.0.0.1:${KaraokeServer.PORT}/guest?room=${Sessions.roomToken}&profile=$profile"

    /** 핫스팟 인터페이스 후보별 동승자 주소 (첫 줄이 가장 유력) */
    private fun guestAddresses(): String {
        val addrs = Network.candidates()
        if (addrs.isEmpty()) return "네트워크 주소를 찾지 못했어요 (핫스팟을 켜 주세요)"
        return addrs.joinToString("\n") {
            "http://${it.ip}:${KaraokeServer.PORT}/guest?room=${Sessions.roomToken}  (${it.iface})"
        }
    }

    /** 플레이어는 프리미엄 로그인이 적용되도록 반드시 크롬으로 연다 (WebView 불가). */
    private fun openInChrome(url: String, newTab: Boolean) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        if (newTab) intent.putExtra(Browser.EXTRA_CREATE_NEW_TAB, true)
        try {
            startActivity(Intent(intent).setPackage("com.android.chrome"))
        } catch (e: ActivityNotFoundException) {
            toast("크롬이 없어 기본 브라우저로 엽니다")
            startActivity(intent)
        }
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

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        /** 오류 150 원인 확인용: 같은 영상을 https 주소에서 임베드해 본다 (GitHub Pages, docs/embed-test.html) */
        private const val EMBED_TEST_URL = "https://guom0625-dotcom.github.io/karaoke/embed-test.html"
    }
}
