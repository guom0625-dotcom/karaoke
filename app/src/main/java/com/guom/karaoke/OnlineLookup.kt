package com.guom.karaoke

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 곡 DB 에 없는 곡을 유튜브에서 직접 찾아 DB 에 추가한다.
 * 목록 API 가 채널당 약 2만 개까지만 돌려줘서 오래된 곡이 빠지는 빈틈을 메우는 용도.
 *  - 유튜브에서 더 찾기: search.list (브랜드당 100유닛) → 하루 횟수 제한
 *  - 링크로 추가: videos.list (1유닛)
 */
object OnlineLookup {
    const val DAILY_LIMIT = 30
    private const val MIN_DURATION_SEC = 61

    sealed interface Result {
        data class Found(val newSongs: List<Song>, val remainingToday: Int) : Result
        data object NoApiKey : Result
        data object DailyLimitReached : Result
        data class Failed(val reason: String) : Result
    }

    /** 오늘 이미 유튜브에서 찾아본 검색어 (정규화) — 같은 검색어는 다시 호출하지 않는다 */
    private val searchedToday = mutableSetOf<String>()
    private var searchedDay = ""

    /** 켜진 브랜드 채널 안에서 검색해, 새로 찾은 곡을 DB 에 넣고 돌려준다 */
    @Synchronized
    fun search(context: Context, query: String): Result {
        val api = YouTubeApi.create(context) ?: return Result.NoApiKey
        val day = today()
        if (day != searchedDay) {
            searchedDay = day
            searchedToday.clear()
        }
        val key = Hangul.normalize(query) + "|" + Settings.enabledChannels(context).joinToString(",") { it.brand }
        if (key in searchedToday) {
            return Result.Found(emptyList(), DAILY_LIMIT - Settings.onlineSearchCount(context, day))
        }
        if (!Settings.tryUseOnlineSearch(context, day, DAILY_LIMIT)) return Result.DailyLimitReached
        val db = SongDb.get(context)
        return try {
            val ids = Settings.enabledChannels(context).flatMap { api.search(query, it.id) }.distinct()
            val known = db.knownIds(ids)
            val unknown = ids.filterNot { it in known }
            val found = importSongs(db, api.videoInfos(unknown))
            searchedToday += key
            Result.Found(found, DAILY_LIMIT - Settings.onlineSearchCount(context, day))
        } catch (e: YouTubeApi.ApiException) {
            Result.Failed(if (e.reason == "quotaExceeded") "오늘 유튜브 API 할당량을 다 썼어요" else e.reason)
        } catch (e: Exception) {
            Result.Failed(e.message ?: "네트워크 오류")
        }
    }

    sealed interface LinkResult {
        data class Ok(val song: Song) : LinkResult
        data class Error(val message: String) : LinkResult
    }

    /** 유튜브 링크(또는 영상 ID)의 곡을 DB 에 추가하고 돌려준다. 화이트리스트 채널만. */
    fun addByLink(context: Context, input: String): LinkResult {
        val id = parseVideoId(input) ?: return LinkResult.Error("영상 ID를 찾을 수 없어요")
        val db = SongDb.get(context)
        db.get(id)?.let { return LinkResult.Ok(it) }
        if (db.isMarkedUnplayable(id)) return LinkResult.Error("재생 오류가 났던 영상이에요 (설정에서 초기화 가능)")
        val api = YouTubeApi.create(context) ?: return LinkResult.Error("API 키가 없어요")
        return try {
            val info = api.videoInfos(listOf(id)).firstOrNull() ?: return LinkResult.Error("영상을 찾을 수 없어요")
            if (Channels.ALL.none { it.id == info.channelId }) return LinkResult.Error("TJ·금영 공식 채널 영상만 추가할 수 있어요")
            if (!info.embeddable) return LinkResult.Error("외부 재생이 막힌 영상이에요")
            importSongs(db, listOf(info)).firstOrNull()?.let { LinkResult.Ok(it) }
                ?: LinkResult.Error("노래방 영상 제목 형식이 아니에요")
        } catch (e: YouTubeApi.ApiException) {
            LinkResult.Error(e.reason)
        } catch (e: Exception) {
            LinkResult.Error(e.message ?: "네트워크 오류")
        }
    }

    /** 동기화와 같은 규칙(화이트리스트·제목 형식·길이)으로 걸러 DB 에 넣고, 검색 가능한 곡을 돌려준다 */
    private fun importSongs(db: SongDb, infos: List<YouTubeApi.VideoInfo>): List<Song> {
        val songs = infos.mapNotNull { v ->
            if (Channels.ALL.none { it.id == v.channelId }) return@mapNotNull null
            if (v.durationSec < MIN_DURATION_SEC) return@mapNotNull null
            val parsed = TitleParser.parse(v.title, v.description) ?: return@mapNotNull null
            NewSong(v.id, v.channelId, v.title, parsed, v.durationSec, v.embeddable, v.publishedAt)
        }
        db.insertSongs(songs)
        return songs.filter { it.embeddable }.mapNotNull { db.get(it.videoId) }
    }

    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val VIDEO_ID_IN_URL = Regex("(?:[?&]v=|youtu\\.be/|/shorts/|/embed/|/live/)([A-Za-z0-9_-]{11})")

    fun parseVideoId(input: String): String? {
        val s = input.trim()
        if (VIDEO_ID.matches(s)) return s
        return VIDEO_ID_IN_URL.find(s)?.groupValues?.get(1)
    }

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())
}
