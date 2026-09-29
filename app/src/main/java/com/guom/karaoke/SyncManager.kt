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
 * 채널 동기화. API 가 재생목록 하나에서 약 2만 개까지만 돌려주므로 채널마다 여러 목록을 합친다.
 *  - 최신 업로드(UU…): 최초엔 끝까지, 이후엔 이미 저장된 영상이 나오는 페이지까지만 (증분)
 *  - 인기 영상(UULP…, 비공식 자동 재생목록): 30일마다 다시 훑는다. API 에 없으면 건너뜀
 *  - 채널이 만든 재생목록 전체: 30일마다 다시 훑는다. 다른 채널 영상은 제외
 * 페이지마다 pageToken 을 저장해 중단(할당량 초과 등)돼도 이어서 진행한다.
 */
object SyncManager {
    data class Status(val running: Boolean = false, val message: String = "")

    private const val MIN_DURATION_SEC = 61 // 쇼츠 등 짧은 영상 제외
    private const val REFRESH_MS = 30L * 24 * 60 * 60 * 1000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _status = MutableStateFlow(Status())
    val status = _status.asStateFlow()

    /** 이번 실행의 목록별 결과 (완료·중지·오류 메시지에 함께 표시) */
    private val report = mutableListOf<String>()

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
        report.clear()
        _status.value = Status(true, "동기화 시작")
        fun finish(head: String) = "$head · 사용 ${api.unitsUsed}유닛\n${report.joinToString("\n")}\n${summary(db)}"
        try {
            for (channel in Settings.enabledChannels(context)) syncChannel(api, db, channel)
            _status.value = Status(false, finish("완료"))
        } catch (e: CancellationException) {
            _status.value = Status(false, finish("중지됨 (다음에 이어서 진행)"))
            throw e
        } catch (e: YouTubeApi.ApiException) {
            val hint = when (e.reason) {
                "quotaExceeded" -> "오늘 할당량을 다 썼어요. 내일 이어서 진행하세요"
                "keyInvalid", "badRequest" -> "API 키가 올바르지 않아요"
                "forbidden", "ipRefererBlocked", "accessNotConfigured" ->
                    "키 제한 또는 API 사용 설정을 확인하세요 (${e.reason})"
                else -> e.reason
            }
            _status.value = Status(false, finish("오류: $hint (${e.message})"))
        } catch (e: IOException) {
            _status.value = Status(false, finish("네트워크 오류: ${e.message}"))
        }
    }

    private suspend fun syncChannel(api: YouTubeApi, db: SongDb, channel: Channel) {
        val info = api.channel(channel.id)
        val suffix = channel.id.removePrefix("UC") // 채널 ID "UC…" → 자동 재생목록 "<prefix>…"

        // 1) 최신 업로드
        val latest = syncPlaylist(api, db, channel, "${channel.brand} 최신", "UU$suffix", refresh = false)
        report += "${channel.brand} 최신: ${latest.describe()} (채널 전체 ${info.videoCount}개)"

        // 2) 인기 영상 (비공식)
        report += "${channel.brand} 인기: " + try {
            syncPlaylist(api, db, channel, "${channel.brand} 인기", "UULP$suffix", refresh = true).describe()
        } catch (e: YouTubeApi.ApiException) {
            if (e.httpCode != 404) throw e
            "API에서 목록을 찾을 수 없음 (${e.reason})"
        }

        // 3) 채널이 만든 재생목록
        val playlists = api.channelPlaylists(channel.id)
        var total = SourceResult()
        playlists.forEachIndexed { i, p ->
            val label = "${channel.brand} 재생목록 ${i + 1}/${playlists.size} '${p.title}'"
            total += try {
                syncPlaylist(api, db, channel, label, p.id, refresh = true)
            } catch (e: YouTubeApi.ApiException) {
                if (e.httpCode != 404) throw e
                SourceResult()
            }
        }
        report += "${channel.brand} 재생목록 ${playlists.size}개: ${total.describe()}"
    }

    private data class SourceResult(val scanned: Int = 0, val added: Int = 0, val skipped: Boolean = false) {
        operator fun plus(o: SourceResult) = SourceResult(scanned + o.scanned, added + o.added)
        fun describe() = if (skipped) "최근에 확인함 (30일마다 다시 확인)" else "${scanned}개 확인, 새 곡 ${added}곡"
    }

    /**
     * @param refresh false = 최신 업로드(증분), true = 끝까지 훑고 30일 뒤 다시 훑는 목록
     */
    private suspend fun syncPlaylist(
        api: YouTubeApi,
        db: SongDb,
        channel: Channel,
        label: String,
        playlistId: String,
        refresh: Boolean,
    ): SourceResult {
        val initial = db.syncState(playlistId)
        if (refresh && initial.fullDone && System.currentTimeMillis() - initial.completedAt < REFRESH_MS) {
            return SourceResult(skipped = true)
        }
        val incremental = !refresh && initial.fullDone
        var token = if (initial.fullDone) null else initial.pageToken
        var scanned = 0
        var added = 0

        while (true) {
            coroutineContext.ensureActive()
            val page = api.playlistItems(playlistId, token)
            scanned += page.items.size

            val known = db.knownIds(page.items.map { it.videoId })
            val candidates = page.items
                .filter { it.videoId !in known }
                .filter { it.ownerChannelId == null || it.ownerChannelId == channel.id }
                .mapNotNull { e -> TitleParser.parse(e.title, e.description)?.let { e to it } }
            if (candidates.isNotEmpty()) {
                val details = api.videos(candidates.map { it.first.videoId })
                val songs = candidates.mapNotNull { (e, parsed) ->
                    val d = details[e.videoId] ?: return@mapNotNull null
                    if (d.durationSec < MIN_DURATION_SEC) return@mapNotNull null
                    // 임베드 불가 영상도 저장해 두어 다음 동기화 때 다시 조회하지 않는다 (검색에선 제외)
                    NewSong(e.videoId, channel.id, e.title, parsed, d.durationSec, d.embeddable, e.publishedAt)
                }
                added += db.insertSongs(songs)
            }

            token = page.nextPageToken
            if (!incremental) {
                val done = token == null
                db.saveSyncState(playlistId, SyncState(done, token, if (done) System.currentTimeMillis() else 0))
            }

            _status.value = Status(
                true,
                "$label: ${scanned}개 확인, 새 곡 ${added}곡 · 사용 ${api.unitsUsed}유닛\n" +
                    report.joinToString("\n") + "\n" + summary(db)
            )

            if (token == null) break
            if (incremental && known.isNotEmpty()) break
        }
        return SourceResult(scanned, added)
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

    /** 같은 곡이 TJ·금영에 모두 있을 때 기본으로 예약할 브랜드 (TJ 는 외부 재생이 막힌 곡이 많아 KY 기본) */
    fun preferredBrand(context: Context): String = prefs(context).getString("preferred_brand", "KY") ?: "KY"

    /**
     * 사용할 브랜드. 꺼진 브랜드는 검색·대체 후보·동기화에서 빠진다 (곡 데이터는 유지).
     * 기본값: TJ 끔 — 실사용에서 TJ 영상 다수가 "TJ에서 외부 재생 차단"(오류 150)으로 확인됨.
     */
    fun isBrandEnabled(context: Context, brand: String): Boolean =
        prefs(context).getBoolean("brand_enabled_$brand", brand != "TJ")

    fun setBrandEnabled(context: Context, brand: String, enabled: Boolean) {
        prefs(context).edit().putBoolean("brand_enabled_$brand", enabled).apply()
    }

    /** 핫스팟이 켜져 있다가 꺼지면 서버 자동 종료 (빅스비 루틴은 "핫스팟 켜짐 → 앱 열기"만 있으면 됨) */
    fun autoStopOnHotspotOff(context: Context): Boolean = prefs(context).getBoolean("auto_stop_hotspot", true)

    fun setAutoStopOnHotspotOff(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("auto_stop_hotspot", on).apply()
    }

    /** "유튜브에서 더 찾기" 하루 사용 횟수 (search.list 는 호출당 100유닛이라 제한) */
    fun onlineSearchCount(context: Context, day: String): Int =
        if (prefs(context).getString("online_search_day", "") == day) prefs(context).getInt("online_search_count", 0) else 0

    /** 한도 안이면 1회 사용으로 기록하고 true */
    @Synchronized
    fun tryUseOnlineSearch(context: Context, day: String, limit: Int): Boolean {
        val used = onlineSearchCount(context, day)
        if (used >= limit) return false
        prefs(context).edit().putString("online_search_day", day).putInt("online_search_count", used + 1).apply()
        return true
    }

    fun notificationAsked(context: Context): Boolean = prefs(context).getBoolean("notification_asked", false)

    fun setNotificationAsked(context: Context) {
        prefs(context).edit().putBoolean("notification_asked", true).apply()
    }

    fun enabledChannels(context: Context): List<Channel> =
        Channels.ALL.filter { isBrandEnabled(context, it.brand) }

    fun setPreferredBrand(context: Context, brand: String) {
        prefs(context).edit().putString("preferred_brand", brand).apply()
    }
}
