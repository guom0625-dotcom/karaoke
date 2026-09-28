package com.guom.karaoke

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * 화면 없이 서버만 켜는 런처 항목 "노래방 시작".
 * 빅스비(모드 및 루틴)에서 "핫스팟 켜짐 → 앱 열기: 노래방 시작" 으로 쓴다.
 */
class StartServerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Sessions.init(this)
        startForegroundService(Intent(this, KaraokeService::class.java))
        Toast.makeText(this, "노래방 서버를 켰어요", Toast.LENGTH_SHORT).show()
        finish()
    }
}

/**
 * 서버를 끄는 런처 항목 "노래방 종료". 루틴의 "앱 닫기"는 화면만 닫고 서비스는 남기 때문에 따로 둔다.
 * 빅스비(모드 및 루틴)에서 "핫스팟 꺼짐 → 앱 열기: 노래방 종료" 로 쓴다.
 */
class StopServerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SyncManager.cancel() // 중단돼도 다음에 이어서 진행된다
        QueueManager.clear()
        stopService(Intent(this, KaraokeService::class.java))
        Toast.makeText(this, "노래방 서버를 껐어요", Toast.LENGTH_SHORT).show()
        finish()
    }
}
