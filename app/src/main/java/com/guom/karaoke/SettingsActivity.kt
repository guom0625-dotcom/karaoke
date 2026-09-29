package com.guom.karaoke

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 앱의 유일한 화면 (알림의 "설정" 또는 첫 실행 시).
 * 서버 상태·동승자 주소·플레이어 열기·종료, 업데이트, 곡 DB, 브랜드, 자동 종료, 배터리, 재생 오류.
 * 예약 관리·검색은 차 화면 플레이어의 🔍 예약 패널에서 한다.
 */
class SettingsActivity : Activity() {
    private val scope = MainScope()
    private lateinit var ui: UiKit
    private val db by lazy { SongDb.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = UiKit(this)
        KaraokeService.showNotification(this)
        requestNotificationPermission()
        // 앱을 열 때 1회 업데이트 확인 (GitHub 비인증 API 한도: 시간당 60회)
        if (Updater.state.value == Updater.State.Idle) scope.launch { Updater.check(this@SettingsActivity) }

        val page = ui.page().apply {
            addView(statusCard())
            addView(updateCard())
            addView(songDbCard())
            addView(brandCard())
            addView(serverCard())
            addView(errorCard())
            addView(ui.hint("gomKaraoke v${packageManager.getPackageInfo(packageName, 0).versionName}"))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#f2f2f7"))
            addView(page)
        })
    }

    override fun onResume() {
        super.onResume()
        refreshBattery()
        addressView.text = Nav.guestAddress(this)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---- 서버 상태 ----
    private val addressView by lazy { ui.text(size = 13f).apply { setTextIsSelectable(true) } }

    private fun statusCard(): LinearLayout {
        val status = ui.text(size = 15f)
        scope.launch {
            KaraokeService.serverRunning.collect { on ->
                status.text = if (on) "🟢 서버 실행 중" else "⚪ 서버 꺼짐 (실행을 누르면 켜져요)"
            }
        }
        return ui.card(
            ui.title("gomKaraoke"),
            status,
            addressView,
            ui.hint("동승자는 핫스팟에 연결한 폰으로 차 화면의 QR을 찍으면 돼요. 예약 관리는 차 화면의 🔍 예약에서"),
            ui.button("실행 (서버 켜고 플레이어 열기)", primary = true) {
                KaraokeService.startServer(this)
                Nav.openPlayer(this)
            },
            ui.button("서버 종료") { confirmStop(quit = false) },
            ui.button("앱 완전 종료 (알림까지 끄기)", danger = true) { confirmStop(quit = true) },
        )
    }

    private fun confirmStop(quit: Boolean) {
        val q = QueueManager.state.value
        val busy = q.nowPlaying != null || q.queue.isNotEmpty()
        AlertDialog.Builder(this)
            .setTitle(if (quit) "앱을 완전히 종료할까요?" else "서버를 끌까요?")
            .setMessage(
                (if (busy) "재생 중인 곡과 예약 목록이 모두 사라져요.\n" else "") +
                    (if (quit) "알림도 사라져요. 앱 아이콘을 누르면 다시 떠요." else "알림은 남아 있어서 다시 실행할 수 있어요.")
            )
            .setPositiveButton("종료") { _, _ ->
                if (quit) {
                    KaraokeService.quit(this)
                    finishAndRemoveTask()
                } else {
                    KaraokeService.stopServer(this)
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Settings.setNotificationAsked(this)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    // ---- 앱 업데이트 ----
    private fun updateCard(): LinearLayout {
        val status = ui.text()
        lateinit var button: Button
        button = ui.button("업데이트 확인") { onUpdateButton() }
        scope.launch {
            Updater.state.collect { s ->
                status.text = when (s) {
                    Updater.State.Idle, Updater.State.UpToDate -> "최신 버전이에요"
                    Updater.State.Checking -> "업데이트 확인 중…"
                    is Updater.State.Available -> "새 버전 v${s.release.versionName}이 있어요\n" +
                        s.release.notes.lines().filter { it.isNotBlank() }.take(8).joinToString("\n")
                    is Updater.State.Downloading -> "v${s.release.versionName} 받는 중… ${s.percent}%"
                    Updater.State.WaitingForUser -> "설치 확인 화면에서 '업데이트'를 누르세요"
                    is Updater.State.Failed -> s.message
                }
                button.text = if (s is Updater.State.Available) "업데이트" else "업데이트 확인"
                button.isEnabled = s !is Updater.State.Checking && s !is Updater.State.Downloading
            }
        }
        return ui.card(ui.title("앱 업데이트"), status, button)
    }

    private fun onUpdateButton() {
        val s = Updater.state.value
        if (s !is Updater.State.Available) {
            scope.launch { Updater.check(this@SettingsActivity) }
            return
        }
        // 최초 1회: "출처를 알 수 없는 앱 설치" 허용 화면으로 안내
        if (!packageManager.canRequestPackageInstalls()) {
            ui.toast("이 앱의 '출처를 알 수 없는 앱 설치'를 허용한 뒤 다시 눌러 주세요")
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
        val start = { scope.launch { Updater.downloadAndInstall(this@SettingsActivity, s.release) }; Unit }
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

    // ---- 곡 DB ----
    private fun songDbCard(): LinearLayout {
        // 설정 화면이 차 화면에 미러링될 수 있으므로 키는 가리고, 저장 후엔 끝 4자리만 보여준다.
        val keyStatus = ui.text()
        fun showKey() {
            val key = Settings.apiKey(this)
            keyStatus.text = if (key.isNullOrBlank()) "저장된 API 키 없음" else "YouTube API 키: ••••${key.takeLast(4)}"
        }
        showKey()
        val keyInput = EditText(this).apply {
            hint = "새 API 키 (바꿀 때만 입력)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val syncStatus = ui.text(size = 13f)
        val syncButton = ui.button("채널 동기화") {
            if (SyncManager.isRunning()) SyncManager.cancel() else SyncManager.start(this)
        }
        scope.launch {
            syncStatus.text = withContext(Dispatchers.IO) { SyncManager.summary(db) }
            SyncManager.status.collect { s ->
                syncButton.text = if (s.running) "동기화 중지" else "채널 동기화"
                if (s.message.isNotEmpty()) syncStatus.text = s.message
            }
        }
        return ui.card(
            ui.title("곡 DB"),
            keyStatus,
            keyInput,
            ui.button("API 키 저장") {
                val key = keyInput.text.toString().trim()
                if (key.isEmpty()) return@button ui.toast("키를 입력하세요")
                Settings.setApiKey(this, key)
                keyInput.setText("")
                showKey()
                ui.toast("저장했어요")
            },
            syncButton,
            syncStatus,
            ui.hint("처음 한 번 전체를 받고, 이후엔 새 곡만 받아요. 채널 재생목록은 30일마다 다시 확인해요"),
        )
    }

    // ---- 브랜드 ----
    private fun brandCard(): LinearLayout {
        val card = ui.card(ui.title("브랜드"))
        for (channel in Channels.ALL) {
            lateinit var b: Button
            fun show() {
                val on = Settings.isBrandEnabled(this, channel.brand)
                b.text = "${channel.brand} 검색: ${if (on) "켬" else "끔"}"
            }
            b = ui.button("") {
                val on = Settings.isBrandEnabled(this, channel.brand)
                if (on && Settings.enabledChannels(this).size == 1) {
                    return@button ui.toast("최소 한 브랜드는 켜 두어야 해요")
                }
                Settings.setBrandEnabled(this, channel.brand, !on)
                show()
            }
            show()
            card.addView(b)
        }
        lateinit var preferred: Button
        fun showPreferred() {
            preferred.text = "같은 곡이면 기본 브랜드: ${Settings.preferredBrand(this)}"
        }
        preferred = ui.button("") {
            Settings.setPreferredBrand(this, if (Settings.preferredBrand(this) == "TJ") "KY" else "TJ")
            showPreferred()
        }
        showPreferred()
        card.addView(preferred)
        card.addView(ui.hint("꺼진 브랜드는 검색·대체 버전·동기화에서 빠져요 (TJ는 외부 재생이 막힌 곡이 많아 기본 끔)"))
        return card
    }

    // ---- 서버·동승자 ----
    private val batteryText by lazy { ui.text() }

    private fun serverCard(): LinearLayout {
        lateinit var autoStop: Button
        fun showAutoStop() {
            autoStop.text = "핫스팟 꺼지면 자동 종료: ${if (Settings.autoStopOnHotspotOff(this)) "켬" else "끔"}"
        }
        autoStop = ui.button("") {
            Settings.setAutoStopOnHotspotOff(this, !Settings.autoStopOnHotspotOff(this))
            showAutoStop()
        }
        showAutoStop()
        return ui.card(
            ui.title("서버 · 동승자"),
            ui.button("동승자 주소 초기화") {
                AlertDialog.Builder(this)
                    .setMessage("기존 QR·주소와 동승자 접속이 모두 끊겨요. 초기화할까요?")
                    .setPositiveButton("초기화") { _, _ ->
                        Sessions.resetRoom(this)
                        addressView.text = Nav.guestAddress(this)
                        ui.toast("새 주소를 만들었어요")
                    }
                    .setNegativeButton("취소", null)
                    .show()
            },
            autoStop,
            ui.hint("빅스비 루틴: '모바일 핫스팟 켜짐 → 앱 열기: gomKaraoke'만 만들면 돼요. 끌 때는 자동이에요"),
            batteryText,
            ui.button("배터리 최적화 예외 설정") { requestBatteryExemption() },
            ui.hint("삼성 폰은 설정 → 배터리 → 백그라운드 사용 제한 → 절전 예외 앱에도 추가해 두세요"),
        )
    }

    private fun refreshBattery() {
        val pm = getSystemService(PowerManager::class.java)
        batteryText.text = if (pm.isIgnoringBatteryOptimizations(packageName)) {
            "🔋 배터리 최적화 예외: 설정됨"
        } else {
            "🔋 배터리 최적화 예외: 안 됨 (백그라운드에서 서버가 꺼질 수 있어요)"
        }
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return ui.toast("이미 설정돼 있어요")
        startActivity(
            Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        )
    }

    // ---- 재생 오류 ----
    private fun errorCard(): LinearLayout {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lateinit var reset: Button
        suspend fun refreshUnplayable() {
            val n = withContext(Dispatchers.IO) { db.countUnplayable() }
            reset.text = "재생 불가 표시 초기화 (${n}곡)"
            reset.isEnabled = n > 0
        }
        reset = ui.button("재생 불가 표시 초기화") {
            scope.launch {
                withContext(Dispatchers.IO) { db.resetUnplayable() }
                refreshUnplayable()
                ui.toast("재생 불가 표시를 모두 풀었어요")
            }
        }
        scope.launch {
            PlaybackLog.entries.collect { entries ->
                list.removeAllViews()
                if (entries.isEmpty()) list.addView(ui.hint("최근 오류 없음"))
                for (e in entries) {
                    list.addView(ui.text(e.text, 13f).apply {
                        setPadding(0, ui.dp(4), 0, ui.dp(4))
                        // 원인 확인용: 같은 영상을 https 주소에서 임베드해 본다
                        setOnClickListener { Nav.openInChrome(this@SettingsActivity, "$EMBED_TEST_URL?v=${e.videoId}") }
                    })
                }
                refreshUnplayable()
            }
        }
        return ui.card(
            ui.title("재생 오류"),
            list,
            ui.hint("항목을 누르면 https 테스트 페이지에서 같은 영상을 열어요"),
            reset,
        )
    }

    companion object {
        /** 오류 원인 확인용 임베드 테스트 페이지 (GitHub Pages, docs/embed-test.html) */
        private const val EMBED_TEST_URL = "https://guom0625-dotcom.github.io/karaoke/embed-test.html"
    }
}
