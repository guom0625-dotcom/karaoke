package com.guom.karaoke

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * 채널 동기화. 업로드 재생목록을 최신순으로 훑는다.
 *  - 최초: 끝까지 전체 수집. 페이지마다 pageToken 을 저장해 중단돼도 이어서 진행
 *  - 이후: 이미 저장된 영상이 나오는 페이지까지만 (증분)
 */
object SyncManager {
    data class Status(val running: Boolean = false, val message: String = "")

    private const val MIN_DURATION_SEC = 61 // 쇼츠 등 짧은 영상 제외

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _status = MutableStateFlow(Status())
    val status = _status.asStateFlow()

    fun isRunning() = job?.isActive == true

    fun start(context: Context) {
        if (isRunning()) return
        val app = context.applicationContext
        job = scope.launch { run(app) }
    }

    fun cancel() {
        job?.cancel()
    }

    private suspend fun run(context: Context) {
        val key = Settings.apiKey(context)
        if (key.isNullOrBlank()) {
            _status.value = Status(false, "API 키를 먼저 저장하세요")
            return
        }
        val api = YouTubeApi(key, context.packageName, YouTubeApi.signingCertSha1(context))
        val db = SongDb.get(context)
        _status.value = Status(true, "동기화 시작")
        try {
            for (channel in Channels.ALL) syncChannel(api, db, channel)
            _status.value = Status(false, "완료 · 사용 ${api.unitsUsed}유닛\n${summary(db)}")
        } catch (e: CancellationException) {
            _status.value = Status(false, "중지됨 (다음에 이어서 진행) · 사용 ${api.unitsUsed}유닛\n${summary(db)}")
            throw e
        } catch (e: YouTubeApi.ApiException) {
            val hint = when (e.reason) {
                "quotaExceeded" -> "오늘 할당량을 다 썼어요. 내일 이어서 진행하세요"
                "keyInvalid", "badRequest" -> "API 키가 올바르지 않아요"
                "forbidden", "ipRefererBlocked", "accessNotConfigured" ->
                    "키 제한 또는 API 사용 설정을 확인하세요 (${e.reason})"
                else -> e.reason
            }
            _status.value = Status(false, "오류: $hint\n${e.message}\n${summary(db)}")
        } catch (e: IOException) {
            _status.value = Status(false, "네트워크 오류: ${e.message}\n${summary(db)}")
        }
    }

    private suspend fun syncChannel(api: YouTubeApi, db: SongDb, channel: Channel) {
        val info = api.channel(channel.id)
        val initial = db.syncState(channel.id)
        var token = if (initial.fullDone) null else initial.pageToken
        var scanned = 0

        while (true) {
            coroutineContext.ensureActive()
            val page = api.playlistItems(info.uploadsPlaylistId, token)
            scanned += page.items.size

            val known = db.knownIds(page.items.map { it.videoId })
            val candidates = page.items
                .filter { it.videoId !in known }
                .mapNotNull { e -> TitleParser.parse(e.title, e.description)?.let { e to it } }
            if (candidates.isNotEmpty()) {
                val details = api.videos(candidates.map { it.first.videoId })
                val songs = candidates.mapNotNull { (e, parsed) ->
                    val d = details[e.videoId] ?: return@mapNotNull null
                    if (d.durationSec < MIN_DURATION_SEC) return@mapNotNull null
                    // 임베드 불가 영상도 저장해 두어 다음 동기화 때 다시 조회하지 않는다 (검색에선 제외)
                    NewSong(e.videoId, channel.id, e.title, parsed, d.durationSec, d.embeddable, e.publishedAt)
                }
                db.insertSongs(songs)
            }

            token = page.nextPageToken
            if (!initial.fullDone) db.saveSyncState(channel.id, SyncState(token == null, token))

            _status.value = Status(
                true,
                "${channel.brand}: ${scanned}개 확인 · 검색 가능 ${db.countPlayable(channel.id)}곡" +
                    " / 채널 전체 ${info.videoCount}개 · 사용 ${api.unitsUsed}유닛"
            )

            if (token == null) break
            if (initial.fullDone && known.isNotEmpty()) break
        }
    }

    fun summary(db: SongDb) =
        Channels.ALL.joinToString(" · ") { "${it.brand} ${db.countPlayable(it.id)}곡" }
}

object Settings {
    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun apiKey(context: Context): String? = prefs(context).getString("api_key", null)

    fun setApiKey(context: Context, key: String) {
        prefs(context).edit().putString("api_key", key.trim()).apply()
    }
}
