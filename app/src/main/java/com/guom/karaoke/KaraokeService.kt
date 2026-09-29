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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 상시 알림을 유지하는 포그라운드 서비스. 알림 버튼: 실행 · 종료 · 설정.
 *  - 실행: 로컬 서버 시작 + 크롬 플레이어 열기 ([RunActivity] 경유 — 알림에서 서비스가 화면을 직접 못 연다)
 *  - 종료: 서버만 끈다 (알림은 남아 다시 실행 가능). 핫스팟이 꺼져도 서버만 끈다 (설정)
 *  - 알림까지 없애려면 설정의 "앱 완전 종료"
 */
class KaraokeService : Service() {
    private var server: KaraokeServer? = null
    private var hotspotJob: Job? = null
    private var appPlayer: YouTubeAppPlayer? = null
    private var overlay: OverlayWindow? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        // 알림 문구: 서버 상태 + 새 버전 여부
        scope.launch {
            if (Updater.state.value == Updater.State.Idle) Updater.check(applicationContext)
        }
        // GitHub 곡 목록(Actions 가 매일 수집): 하루 한 번 새 수집본이 있으면 받아 합친다
        scope.launch { SongListDownloader.checkIfDue(applicationContext) }
        scope.launch {
            _serverRunning.combine(Updater.state) { on, u -> on to u }.collect { (on, u) ->
                val update = (u as? Updater.State.Available)?.release?.versionName
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(on, update))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVER -> startServer()
            ACTION_STOP_SERVER -> stopServer()
            ACTION_APPLY_MODE -> if (server != null) applyMode()
            ACTION_QUIT -> {
                stopServer()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopServer()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startServer() {
        if (server != null) return
        server = KaraokeServer(applicationContext).also { it.start() }
        _serverRunning.value = true
        hotspotJob = scope.launch { watchHotspot() }
        applyMode()
    }

    /** 재생 방식에 맞춰 유튜브 앱 제어기·오버레이를 켜거나 끈다 (예약 목록은 유지) */
    private fun applyMode() {
        // 오버레이(다음 곡·QR·🔍 예약)는 두 재생 방식 모두에서 띄운다.
        // 권한을 나중에 허용한 경우에도 다시 부르면 창이 뜬다 (show 는 중복 호출 안전)
        (overlay ?: OverlayWindow(this, scope).also { overlay = it }).show()
        val app = Settings.playbackMode(this) == Settings.MODE_APP
        if (app) {
            if (appPlayer == null) appPlayer = YouTubeAppPlayer(this, scope).also { it.start() }
        } else {
            appPlayer?.stop()
            appPlayer = null
        }
        refreshNotification()
    }

    private fun refreshNotification() {
        val update = (Updater.state.value as? Updater.State.Available)?.release?.versionName
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(_serverRunning.value, update))
    }

    /** 서버만 끈다: 예약·재생 상태를 비운다. 동기화는 서버와 별개라 계속 진행된다. */
    private fun stopServer() {
        hotspotJob?.cancel()
        hotspotJob = null
        appPlayer?.stop()
        appPlayer = null
        overlay?.hide()
        overlay = null
        server?.stop()
        server = null
        QueueManager.clear()
        _serverRunning.value = false
    }

    /**
     * 핫스팟 인터페이스가 한 번이라도 켜진 걸 본 뒤 연속 2회(약 20초) 사라지면 서버 종료.
     * 핫스팟 없이 와이파이로 테스트할 때는 켜진 적이 없으므로 끄지 않는다.
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
                    stopServer()
                    return
                }
            }
            delay(10_000)
        }
    }

    private fun buildNotification(serverOn: Boolean, updateVersion: String?): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE
        val settings = PendingIntent.getActivity(
            this, 1, Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags
        )
        val run = PendingIntent.getActivity(
            this, 2, Intent(this, RunActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags
        )
        val stop = PendingIntent.getService(
            this, 3, Intent(this, KaraokeService::class.java).setAction(ACTION_STOP_SERVER), flags
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("${getString(R.string.app_name)} · ${if (serverOn) "🟢 서버 실행 중" else "서버 꺼짐"}")
            .setContentText(
                when {
                    updateVersion != null -> "새 버전 v$updateVersion 있음 · 설정에서 업데이트"
                    serverOn && Settings.playbackMode(this) == Settings.MODE_APP && !appModeReady(this) ->
                        "유튜브 앱 재생에 필요한 권한이 없어요 · 설정에서 확인"
                    serverOn -> "실행을 누르면 ${mainLabel(this)}를 다시 열어요"
                    else -> "실행을 누르면 서버를 켜고 ${mainLabel(this)}를 열어요"
                }
            )
            .setContentIntent(settings)
            .addAction(Notification.Action.Builder(null, "실행", run).build())
            .addAction(Notification.Action.Builder(null, "종료", stop).build())
            .addAction(Notification.Action.Builder(null, "설정", settings).build())
            .setOngoing(true)
            .build()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "노래방 서버", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = buildNotification(serverOn = false, updateVersion = null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "server"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_START_SERVER = "com.guom.karaoke.START_SERVER"
        private const val ACTION_STOP_SERVER = "com.guom.karaoke.STOP_SERVER"
        private const val ACTION_QUIT = "com.guom.karaoke.QUIT"
        private const val ACTION_APPLY_MODE = "com.guom.karaoke.APPLY_MODE"

        /** 유튜브 앱 재생 방식에 필요한 권한 (알림 접근, 다른 앱 위에 표시) */
        fun appModeReady(context: Context) = MediaListenerService.isEnabled(context) && OverlayWindow.canShow(context)

        private fun mainLabel(context: Context) =
            if (Settings.playbackMode(context) == Settings.MODE_APP) "유튜브" else "플레이어"

        private val _serverRunning = MutableStateFlow(false)
        /** 로컬 서버 실행 여부 */
        val serverRunning = _serverRunning.asStateFlow()

        private fun send(context: Context, action: String?) {
            Sessions.init(context)
            val intent = Intent(context, KaraokeService::class.java)
            if (action != null) intent.action = action
            context.startForegroundService(intent)
        }

        /** 알림만 띄운다 (서버는 켜지 않음) */
        fun showNotification(context: Context) = send(context, null)

        fun startServer(context: Context) = send(context, ACTION_START_SERVER)

        fun stopServer(context: Context) = send(context, ACTION_STOP_SERVER)

        /** 재생 방식이 바뀌었을 때 (서버가 켜져 있으면 바로 반영) */
        fun applyMode(context: Context) = send(context, ACTION_APPLY_MODE)

        /** 서버를 끄고 알림까지 없앤다 */
        fun quit(context: Context) = send(context, ACTION_QUIT)
    }
}
