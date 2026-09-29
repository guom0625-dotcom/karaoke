package com.guom.karaoke

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 로컬 HTTP/WebSocket 서버를 유지하는 포그라운드 서비스.
 * 핫스팟이 켜져 있다가 꺼지면 스스로 종료한다 (설정). 알림의 "종료"로도 끌 수 있다.
 */
class KaraokeService : Service() {
    private lateinit var server: KaraokeServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        server = KaraokeServer(applicationContext)
        server.start()
        _running.value = true
        scope.launch { watchHotspot() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown(this)
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        server.stop()
        _running.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 핫스팟 인터페이스가 한 번이라도 켜진 걸 본 뒤 연속 2회(약 20초) 사라지면 종료.
     * 핫스팟 없이 와이파이로 테스트할 때는 켜진 적이 없으므로 종료하지 않는다.
     */
    private suspend fun watchHotspot() {
        var seenOn = false
        var offCount = 0
        while (scope.isActive) {
            val on = withContext(Dispatchers.IO) { Network.hotspotActive() }
            if (on) {
                seenOn = true
                offCount = 0
            } else if (seenOn && Settings.autoStopOnHotspotOff(this)) {
                if (++offCount >= 2) {
                    withContext(Dispatchers.Main) { shutdown(this@KaraokeService) }
                    return
                }
            }
            delay(10_000)
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "노래방 서버", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, KaraokeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("서버 실행 중 · 포트 ${KaraokeServer.PORT}")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "종료", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "server"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.guom.karaoke.STOP"

        private val _running = MutableStateFlow(false)
        /** 서버 실행 여부 (앱 화면 표시용) */
        val running = _running.asStateFlow()

        fun start(context: Context) {
            Sessions.init(context)
            context.startForegroundService(Intent(context, KaraokeService::class.java))
        }

        /** 서버 종료: 동기화 중지(다음에 이어서 진행), 예약 비우기, 서비스 정지 */
        fun shutdown(context: Context) {
            SyncManager.cancel()
            QueueManager.clear()
            context.stopService(Intent(context, KaraokeService::class.java))
        }
    }
}
