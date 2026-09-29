package com.guom.karaoke

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Browser
import android.view.Menu
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 메인 화면: 서버 상태, 동승자 주소, 플레이어 열기, 예약 현황, 종료.
 * 곡 검색·예약은 차 화면 플레이어의 🔍 예약 패널에서 한다. 나머지는 ⚙ 설정.
 */
class MainActivity : Activity() {
    private val scope = MainScope()
    private lateinit var ui: UiKit

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = UiKit(this)
        requestNotificationPermission()
        KaraokeService.start(this)

        val status = ui.text(size = 15f)
        val address = ui.text(size = 13f).apply { setTextIsSelectable(true) }
        val updateBanner = ui.text(color = "#3478f6").apply {
            setOnClickListener { startActivity(Intent(context, SettingsActivity::class.java)) }
        }
        val nowPlaying = ui.text(size = 15f)
        val queueList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val page = ui.page().apply {
            addView(updateBanner)
            addView(ui.card(
                ui.title("노래방 서버"),
                status,
                address,
                ui.hint("동승자는 핫스팟에 연결한 폰으로 차 화면의 QR을 찍으면 돼요"),
                ui.button("크롬에서 플레이어 열기", primary = true) { openPlayer() },
            ))
            addView(ui.card(
                ui.title("예약 현황"),
                nowPlaying,
                queueList,
                ui.hint("곡을 누르면 순서 변경·삭제. 곡 검색·예약은 차 화면의 🔍 예약에서"),
                ui.button("다음 곡으로 스킵") { QueueManager.skip(Actor.Host) },
            ))
            addView(ui.button("노래방 종료", danger = true) { confirmShutdown() })
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#f2f2f7"))
            addView(page)
        })

        scope.launch {
            KaraokeService.running.collect { on ->
                status.text = if (on) "🟢 실행 중" else "⚪ 꺼져 있음"
                address.text = if (on) guestAddress(this@MainActivity) else ""
            }
        }
        scope.launch {
            Updater.state.collect { s ->
                updateBanner.text =
                    if (s is Updater.State.Available) "⬆ 새 버전 v${s.release.versionName}이 있어요 — 눌러서 설정에서 업데이트\n" else ""
            }
        }
        // 앱 실행 시 1회 업데이트 확인 (GitHub 비인증 API 한도: 시간당 60회)
        if (Updater.state.value == Updater.State.Idle) scope.launch { Updater.check(this@MainActivity) }

        scope.launch {
            QueueManager.state.collect { s ->
                nowPlaying.text = "▶ " + (s.nowPlaying?.let { "${it.title} - ${it.artist} · ${it.nickname}" } ?: "재생 중인 곡 없음")
                queueList.removeAllViews()
                if (s.queue.isEmpty()) queueList.addView(ui.hint("대기 중인 곡이 없어요"))
                s.queue.forEachIndexed { i, item ->
                    queueList.addView(ui.text("${i + 1}. ${item.title} - ${item.artist} · ${item.nickname}").apply {
                        setPadding(0, ui.dp(6), 0, ui.dp(6))
                        setOnClickListener { manageItem(item) }
                    })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 알림의 "종료"나 핫스팟 자동 종료 뒤 앱을 다시 열면 켠다
        if (!KaraokeService.running.value) KaraokeService.start(this)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_SETTINGS, 0, "설정").apply {
            setIcon(android.R.drawable.ic_menu_preferences)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_SETTINGS) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun confirmShutdown() {
        val q = QueueManager.state.value
        val busy = q.nowPlaying != null || q.queue.isNotEmpty()
        AlertDialog.Builder(this)
            .setTitle("노래방을 종료할까요?")
            .setMessage(if (busy) "재생 중인 곡과 예약 목록이 모두 사라져요." else "서버를 끄고 앱을 닫아요.")
            .setPositiveButton("종료") { _, _ ->
                KaraokeService.shutdown(this)
                finishAndRemoveTask()
            }
            .setNegativeButton("취소", null)
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

    /** 플레이어는 프리미엄 로그인이 적용되도록 반드시 크롬으로 연다 (WebView 불가). */
    private fun openPlayer() {
        openInChrome(this, "http://127.0.0.1:${KaraokeServer.PORT}/player?host=${Sessions.hostToken}")
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    companion object {
        private const val MENU_SETTINGS = 1

        /** 현재 핫스팟(없으면 와이파이) 기준 동승자 주소 */
        fun guestAddress(context: Context): String {
            Sessions.init(context)
            val a = Network.candidates().firstOrNull()
                ?: return "핫스팟(또는 와이파이)을 켜면 동승자 주소가 생겨요"
            val kind = if (Network.isHotspotInterface(a.iface)) "핫스팟" else "와이파이"
            return "동승자 주소 ($kind)\nhttp://${a.ip}:${KaraokeServer.PORT}/guest?room=${Sessions.roomToken}"
        }

        fun openInChrome(context: Context, url: String, newTab: Boolean = false) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            if (newTab) intent.putExtra(Browser.EXTRA_CREATE_NEW_TAB, true)
            try {
                context.startActivity(Intent(intent).setPackage("com.android.chrome"))
            } catch (e: ActivityNotFoundException) {
                context.startActivity(intent)
            }
        }
    }
}
