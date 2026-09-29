package com.guom.karaoke

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Browser
import android.widget.Toast

/**
 * 앱 아이콘(빅스비 루틴 "앱 열기" 포함): 알림창에 gomKaraoke 알림(실행·종료·설정)을 띄운다.
 * 첫 설정(API 키·곡 DB·알림 권한)이 안 됐으면 설정 화면을 연다.
 * 런처 항목 이름(MainActivity)은 홈 화면 아이콘·루틴이 깨지지 않도록 유지한다.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KaraokeService.showNotification(this)
        if (needsSetup()) {
            startActivity(Intent(this, SettingsActivity::class.java))
        } else {
            Toast.makeText(this, "알림창의 gomKaraoke에서 '실행'을 누르세요", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun needsSetup(): Boolean {
        if (Settings.apiKey(this).isNullOrBlank()) return true
        if (!SongDb.get(this).hasSongs()) return true
        // 알림 권한은 처음 한 번만 설정 화면에서 묻는다 (거절해도 계속 묻지 않음)
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !Settings.notificationAsked(this)
    }
}

/** 알림의 "실행": 서버를 켜고 크롬 플레이어를 연다 (알림에서 서비스가 화면을 직접 열 수 없어 거쳐 간다) */
class RunActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KaraokeService.startServer(this)
        Nav.openMain(this)
        finish()
    }
}

/** 화면 이동·주소 도우미 */
object Nav {
    /** 플레이어는 프리미엄 로그인이 적용되도록 반드시 크롬으로 연다 (WebView 불가). */
    fun openPlayer(context: Context) {
        Sessions.init(context)
        openInChrome(context, "http://127.0.0.1:${KaraokeServer.PORT}/player?host=${Sessions.hostToken}", reuseTab = true)
    }

    /**
     * reuseTab: 이 앱이 연 크롬 탭을 다시 써서 탭이 쌓이지 않게 한다 (그 탭은 새로 고쳐진다).
     * 아니면 새 탭으로 연다.
     */
    fun openInChrome(context: Context, url: String, reuseTab: Boolean = false) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (reuseTab) intent.putExtra(Browser.EXTRA_APPLICATION_ID, context.packageName)
        else intent.putExtra(Browser.EXTRA_CREATE_NEW_TAB, true)
        try {
            context.startActivity(Intent(intent).setPackage("com.android.chrome"))
        } catch (e: ActivityNotFoundException) {
            context.startActivity(intent)
        }
    }

    /** 동승자 예약 페이지 주소 (현재 핫스팟, 없으면 와이파이 IP). 주소가 없으면 null */
    fun guestUrl(context: Context): String? {
        Sessions.init(context)
        val ip = Network.candidates().firstOrNull()?.ip ?: return null
        return "http://$ip:${KaraokeServer.PORT}/guest?room=${Sessions.roomToken}"
    }

    /** 호스트용 리모컨 (동승자 페이지를 호스트 권한으로) — 유튜브 앱 재생 방식에서 예약·관리용 */
    fun openHostRemote(context: Context) {
        Sessions.init(context)
        openInChrome(context, "http://127.0.0.1:${KaraokeServer.PORT}/guest?host=${Sessions.hostToken}", reuseTab = true)
    }

    /** 알림의 "실행" 등: 재생 방식에 따라 크롬 플레이어 또는 호스트 리모컨을 연다 */
    fun openMain(context: Context) {
        if (Settings.playbackMode(context) == Settings.MODE_APP) openHostRemote(context) else openPlayer(context)
    }

    /** 현재 핫스팟(없으면 와이파이) 기준 동승자 주소 */
    fun guestAddress(context: Context): String {
        Sessions.init(context)
        val a = Network.candidates().firstOrNull()
            ?: return "핫스팟(또는 와이파이)을 켜면 동승자 주소가 생겨요"
        val kind = if (Network.isHotspotInterface(a.iface)) "핫스팟" else "와이파이"
        return "동승자 주소 ($kind)\nhttp://${a.ip}:${KaraokeServer.PORT}/guest?room=${Sessions.roomToken}"
    }
}
