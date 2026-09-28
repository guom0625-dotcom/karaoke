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
 * 채널 동기화. API 가 재생목록을 약 2만 개까지만 돌려주므로 채널마다 두 목록을 합친다.
 *  - 최신 업로드(UU…): 최초엔 끝까지, 이후엔 이미 저장된 영상이 나오는 페이지까지만 (증분)
 *  - 인기 영상(UULP…, 비공식 자동 재생목록): 순위가 바뀌므로 30일마다 끝까지 다시 훑는다
 * 페이지마다 pageToken 을 저장해 중단(할당량 초과 등)돼도 이어서 진행한다.
 */
object SyncManager {
    data class Status(val running: Boolean = false, val message: String = "")

    private enum class Source(val label: String, val prefix: String) {
        LATEST("최신", "UU"),
        POPULAR("인기", "UULP"),
    }

    private const val MIN_DURATION_SEC = 61 // 쇼츠 등 짧은 영상 제외
    private const val POPULAR_REFRESH_MS = 30L * 24 * 60 * 60 * 1000

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
        for (source in Source.entries) {
            // 채널 ID "UC…" → 자동 재생목록 ID "<prefix>…"
            val playlistId = source.prefix + channel.id.removePrefix("UC")
            try {
                syncPlaylist(api, db, channel, info.videoCount, source, playlistId)
            } catch (e: YouTubeApi.ApiException) {
                // 인기 목록은 비공식이라 없을 수 있다. 최신 목록 오류는 그대로 올린다.
                if (source == Source.LATEST || e.httpCode != 404) throw e
            }
        }
    }

    private suspend fun syncPlaylist(
        api: YouTubeApi,
        db: SongDb,
        channel: Channel,
        channelVideoCount: Long,
        source: Source,
        playlistId: String,
    ) {
        val initial = db.syncState(playlistId)
        val now = System.currentTimeMillis()
        val incremental = when (source) {
            Source.LATEST -> initial.fullDone
            Source.POPULAR -> {
                if (initial.fullDone && now - initial.completedAt < POPULAR_REFRESH_MS) return
                false
            }
        }
        var token = if (initial.fullDone) null else initial.pageToken
        var scanned = 0

        while (true) {
            coroutineContext.ensureActive()
            val page = api.playlistItems(playlistId, token)
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
            if (!incremental) {
                val done = token == null
                db.saveSyncState(playlistId, SyncState(done, token, if (done) System.currentTimeMillis() else 0))
            }

            _status.value = Status(
                true,
                "${channel.brand} ${source.label}: ${scanned}개 확인 · 검색 가능 ${db.countPlayable(channel.id)}곡" +
                    " / 채널 전체 ${channelVideoCount}개 · 사용 ${api.unitsUsed}유닛"
            )

            if (token == null) break
            if (incremental && known.isNotEmpty()) break
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
