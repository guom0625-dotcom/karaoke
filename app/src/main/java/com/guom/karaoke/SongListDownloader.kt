package com.guom.karaoke

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * GitHub Release `songs-db` 의 곡 목록(Actions 가 매일 수집)을 받아 폰 곡 DB 에 합친다.
 * 검색·재생은 계속 폰 DB 로 하고, 이건 목록 갱신만 한다 (YouTube API 할당량 0).
 * 폰 쪽 상태(재생 불가 표시 등)는 덮어쓰지 않도록 새 영상만 추가한다.
 */
object SongListDownloader {
    private const val BASE = "https://github.com/guom0625-dotcom/karaoke/releases/download/songs-db"
    private const val MIN_DURATION_SEC = 61
    private const val CHECK_INTERVAL_MS = 20L * 60 * 60 * 1000

    private val _status = MutableStateFlow("")
    val status = _status.asStateFlow()
    private val mutex = Mutex()

    /** 서버를 켤 때: 마지막 확인 후 20시간이 지났으면 확인 */
    suspend fun checkIfDue(context: Context) {
        val last = Settings.songListCheckedAt(context)
        if (System.currentTimeMillis() - last >= CHECK_INTERVAL_MS) check(context, force = false)
    }

    /** force = 이미 받은 버전이어도 다시 합친다 */
    suspend fun check(context: Context, force: Boolean) = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) return@withContext
        try {
            _status.value = "GitHub 곡 목록 확인 중…"
            val meta = AppJson.parseToJsonElement(fetchText("$BASE/songs-meta.json")).jsonObject
            val updatedAt = meta["updatedAt"]?.jsonPrimitive?.content.orEmpty()
            Settings.setSongListCheckedAt(context, System.currentTimeMillis())
            if (!force && updatedAt == Settings.songListVersion(context)) {
                _status.value = "GitHub 곡 목록: 최신 (${updatedAt.take(10)} 수집본)"
                return@withContext
            }
            _status.value = "GitHub 곡 목록 받는 중…"
            val added = importFrom(context, "$BASE/songs.jsonl.gz")
            Settings.setSongListVersion(context, updatedAt)
            _status.value = "GitHub 곡 목록 반영 완료 · 새 곡 ${added}곡 (${updatedAt.take(10)} 수집본)\n${SyncManager.summary(SongDb.get(context))}"
        } catch (e: Exception) {
            _status.value = "GitHub 곡 목록을 받지 못했어요: ${e.message}"
        } finally {
            mutex.unlock()
        }
    }

    /** 한 줄씩 읽어 2천 개 단위로 넣는다 (메모리 절약) */
    private fun importFrom(context: Context, url: String): Int {
        val db = SongDb.get(context)
        var added = 0
        val batch = ArrayList<NewSong>(2000)
        open(url).use { conn ->
            GZIPInputStream(conn.inputStream).bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    toNewSong(line)?.let { batch += it }
                    if (batch.size >= 2000) {
                        added += db.insertSongs(batch)
                        batch.clear()
                    }
                }
            }
        }
        if (batch.isNotEmpty()) added += db.insertSongs(batch)
        return added
    }

    /** {"v","c","t","n","d","e","p"} → 앱의 제목 파서로 곡명·가수·버전을 뽑는다 (비노래방 영상은 제외) */
    private fun toNewSong(line: String): NewSong? = runCatching {
        val o = AppJson.parseToJsonElement(line).jsonObject
        val channel = o["c"]!!.jsonPrimitive.content
        if (Channels.ALL.none { it.id == channel }) return null
        val duration = o["d"]!!.jsonPrimitive.int
        if (duration < MIN_DURATION_SEC) return null
        val rawTitle = o["t"]!!.jsonPrimitive.content
        val no = o["n"]?.jsonPrimitive?.content?.takeIf { it != "null" }
        val parsed = TitleParser.parse(rawTitle, no?.let { "곡번호.$it" } ?: "") ?: return null
        NewSong(
            videoId = o["v"]!!.jsonPrimitive.content,
            channelId = channel,
            rawTitle = rawTitle,
            parsed = parsed,
            durationSec = duration,
            embeddable = (o["e"]?.jsonPrimitive?.intOrNull ?: 1) == 1,
            publishedAt = o["p"]?.jsonPrimitive?.content,
        )
    }.getOrNull()

    private fun fetchText(url: String) = open(url).use { it.inputStream.bufferedReader().readText() }

    /** GitHub 릴리스 자산은 https 간 리다이렉트 → HttpURLConnection 이 따라간다 */
    private fun open(url: String): Conn {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", "gomKaraoke")
        if (c.responseCode != 200) {
            c.disconnect()
            throw IllegalStateException(if (c.responseCode == 404) "아직 수집된 목록이 없어요" else "HTTP ${c.responseCode}")
        }
        return Conn(c)
    }

    private class Conn(val c: HttpURLConnection) : AutoCloseable {
        val inputStream get() = c.inputStream
        override fun close() = c.disconnect()
    }
}
