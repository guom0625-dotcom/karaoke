package com.guom.karaoke

import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * "유튜브 앱" 재생 방식. 예약 순서는 앱(QueueManager)이 쥐고, 차례가 된 곡 하나만 유튜브 앱으로 연다.
 * 유튜브 앱 안에서 재생하므로 퍼가기(임베드) 제한을 받지 않고, 프리미엄도 그대로 적용된다.
 *
 * 유튜브 앱의 미디어 세션(알림 접근 권한 필요)을 1초마다 보고
 *  - 우리 곡이 재생되기 시작한 것 확인 → 진행 상황을 동승자 페이지로 보고
 *  - 끝까지 갔거나(끝 근처에서 멈춤) 자동재생으로 다른 영상으로 바뀌면 → 다음 곡
 *  - 일시정지·이동 명령은 transportControls 로 전달
 * 백그라운드에서 유튜브 앱을 열려면 "다른 앱 위에 표시" 권한 + 떠 있는 오버레이 창이 필요하다 (Android 15+).
 */
class YouTubeAppPlayer(private val context: Context, private val scope: CoroutineScope) {
    private val jobs = mutableListOf<Job>()

    private var loaded: QueueItem? = null
    private var loadedKey: String? = null
    private var launchedAt = 0L
    /** 우리 곡으로 확인된 유튜브 쪽 제목. null = 아직 확인 전 */
    private var ourTitle: String? = null
    /** 곡을 열기 직전 유튜브에 떠 있던 제목/영상 (이전 곡 상태와 구분용) */
    private var titleBefore: String? = null
    private var videoBefore: String? = null

    fun start() {
        jobs += scope.launch { QueueManager.state.collect { onQueue(it) } }
        jobs += scope.launch { QueueManager.commands.collect { onCommand(it) } }
        jobs += scope.launch {
            while (isActive) {
                runCatching { tick() }
                delay(1000)
            }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        loaded = null
        loadedKey = null
    }

    private fun controller(): MediaController? = try {
        context.getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(MediaListenerService.component(context))
            .firstOrNull { it.packageName == YOUTUBE_PACKAGE }
    } catch (e: SecurityException) {
        null // 알림 접근 권한 없음
    }

    private fun onQueue(s: PlayerState) {
        val np = s.nowPlaying
        if (np == null) {
            if (loaded != null) {
                // 예약이 비었다: 유튜브 자동재생 등으로 다른 영상이 이어지지 않게 멈춘다
                controller()?.transportControls?.pause()
                loaded = null
                loadedKey = null
            }
            return
        }
        val key = "${np.id}:${np.videoId}"
        if (key != loadedKey) open(np, key)
    }

    private fun open(item: QueueItem, key: String) {
        val c = controller()
        titleBefore = c?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        videoBefore = loaded?.videoId
        loaded = item
        loadedKey = key
        ourTitle = null
        launchedAt = SystemClock.elapsedRealtime()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${item.videoId}"))
            .setPackage(YOUTUBE_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            PlaybackLog.add("유튜브 앱을 열 수 없어요: ${e.message}", item.videoId)
        }
    }

    private fun tick() {
        val item = loaded ?: return
        val c = controller() ?: return
        val title = c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val durationMs = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val ps = c.playbackState ?: return
        val playing = ps.state == PlaybackState.STATE_PLAYING
        val pos = position(ps)
        val sinceLaunch = SystemClock.elapsedRealtime() - launchedAt

        if (ourTitle == null) {
            // 곡을 연 직후엔 이전 곡 상태가 남아 있다: 제목이 바뀌었거나(같은 곡 연속이면 처음부터) 재생 중일 때 우리 곡으로 인정
            val sameAsBefore = title == titleBefore && item.videoId != videoBefore
            if (sinceLaunch > 1500 && title != null && playing && pos < 20_000 && !sameAsBefore) {
                ourTitle = title
            } else if (sinceLaunch > 30_000) {
                // 30초 동안 시작되지 않으면 유튜브 쪽 문제로 보고 건너뛴다
                PlaybackLog.add("${item.title} · 유튜브 앱에서 30초 안에 재생되지 않아 건너뜀", item.videoId)
                QueueManager.finish(item.id)
            }
            return
        }

        // 자동재생 등으로 다른 영상이 됐다 = 우리 곡은 끝났다
        if (title != null && title != ourTitle) {
            QueueManager.finish(item.id)
            return
        }
        QueueManager.reportProgress(Progress(item.id, pos / 1000.0, durationMs / 1000.0, playing))
        val atEnd = durationMs > 0 && pos >= durationMs - 2000
        if (atEnd && (!playing || ps.state == PlaybackState.STATE_STOPPED)) {
            QueueManager.finish(item.id)
        } else if (ps.state == PlaybackState.STATE_ERROR) {
            PlaybackLog.add("${item.title} · 유튜브 앱 재생 오류", item.videoId)
            QueueManager.finish(item.id)
        }
    }

    /** 마지막 보고 위치 + 경과 시간 (ms) */
    private fun position(ps: PlaybackState): Long {
        if (ps.state != PlaybackState.STATE_PLAYING) return ps.position
        val elapsed = SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime
        return ps.position + (elapsed * ps.playbackSpeed).toLong()
    }

    private fun onCommand(cmd: PlayerCommand) {
        val c = controller() ?: return
        val t = c.transportControls
        when (cmd.action) {
            "play" -> t.play()
            "pause" -> t.pause()
            "seekBy" -> c.playbackState?.let { t.seekTo((position(it) + (cmd.seconds ?: 0.0) * 1000).toLong().coerceAtLeast(0)) }
            "seekTo" -> t.seekTo(((cmd.seconds ?: 0.0) * 1000).toLong().coerceAtLeast(0))
        }
    }

    companion object {
        const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    }
}
