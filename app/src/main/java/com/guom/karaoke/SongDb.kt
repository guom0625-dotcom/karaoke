package com.guom.karaoke

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.Serializable

/** 화이트리스트 채널. 채널을 추가하려면 여기에 등록만 하면 된다. */
data class Channel(val id: String, val brand: String)

object Channels {
    val ALL = listOf(
        Channel("UCZUhx8ClCv6paFW7qi3qljg", "TJ"),
        Channel("UCDqaUIUSJP5EVMEI178Zfag", "KY"),
    )

    fun brandOf(channelId: String) = ALL.firstOrNull { it.id == channelId }?.brand ?: "?"
}

@Serializable
data class Song(
    val videoId: String,
    val channelId: String,
    val brand: String,
    val title: String,
    val artist: String,
    val karaokeNo: String?,
    val variant: String?,
    val durationSec: Int,
)

/** 재생목록 하나의 동기화 진행 상태. completedAt 은 마지막으로 끝까지 훑은 시각(ms). */
/** 검색 대상: 전체(제목+가수+번호) / 제목만 / 가수만 */
enum class SearchField(val column: String, val chosungColumn: String) {
    ALL("search_key", "chosung_key"),
    TITLE("title_key", "title_chosung"),
    ARTIST("artist_key", "artist_chosung");

    companion object {
        fun parse(value: String?) = when (value) {
            "title" -> TITLE
            "artist" -> ARTIST
            else -> ALL
        }
    }
}

data class SyncState(val fullDone: Boolean, val pageToken: String?, val completedAt: Long = 0)

/**
 * 곡 DB. 한국어 부분 일치·띄어쓰기 차이를 처리하기 위해 FTS 대신
 * 정규화된 검색 키에 LIKE 를 쓴다 (채널 합계 약 10만 행 규모).
 */
class SongDb private constructor(context: Context) :
    SQLiteOpenHelper(context, "songs.db", null, 3) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE song (
                video_id TEXT PRIMARY KEY,
                channel_id TEXT NOT NULL,
                raw_title TEXT NOT NULL,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                karaoke_no TEXT,
                variant TEXT,
                duration_sec INTEGER NOT NULL,
                embeddable INTEGER NOT NULL,
                playable INTEGER NOT NULL DEFAULT 1,
                published_at TEXT,
                title_key TEXT NOT NULL,
                search_key TEXT NOT NULL,
                chosung_key TEXT NOT NULL,
                artist_key TEXT NOT NULL DEFAULT '',
                title_chosung TEXT NOT NULL DEFAULT '',
                artist_chosung TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX song_channel ON song(channel_id)")
        createSyncState(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v1: 채널 단위 상태 → v2: 재생목록 단위. 곡은 유지하고 동기화 상태만 초기화해
            // 파서 개선으로 새로 인식되는 영상을 다시 훑는다.
            db.execSQL("DROP TABLE IF EXISTS sync_state")
            createSyncState(db)
        }
        if (oldVersion < 3) {
            // v3: 제목·가수 따로 검색 → 가수 키와 제목/가수별 초성 키를 추가하고 기존 곡에 채운다.
            db.execSQL("ALTER TABLE song ADD COLUMN artist_key TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE song ADD COLUMN title_chosung TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE song ADD COLUMN artist_chosung TEXT NOT NULL DEFAULT ''")
            val rows = db.rawQuery("SELECT video_id, title, artist FROM song", null).use { c ->
                buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getString(2))) }
            }
            val update = db.compileStatement(
                "UPDATE song SET artist_key = ?, title_chosung = ?, artist_chosung = ? WHERE video_id = ?"
            )
            for ((id, title, artist) in rows) {
                val artistKey = Hangul.normalize(artist)
                update.bindString(1, artistKey)
                update.bindString(2, Hangul.chosung(Hangul.normalize(title)))
                update.bindString(3, Hangul.chosung(artistKey))
                update.bindString(4, id)
                update.executeUpdateDelete()
            }
        }
    }

    private fun createSyncState(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE sync_state (playlist_id TEXT PRIMARY KEY, full_done INTEGER NOT NULL, page_token TEXT, completed_at INTEGER NOT NULL DEFAULT 0)"
        )
    }

    /**
     * 새 영상만 추가 (이미 있는 영상은 playable 등 상태를 보존하기 위해 무시).
     * 새로 추가된 검색 가능 곡 수를 돌려준다.
     */
    fun insertSongs(songs: List<NewSong>): Int {
        var added = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (s in songs) {
                val cv = ContentValues().apply {
                    put("video_id", s.videoId)
                    put("channel_id", s.channelId)
                    put("raw_title", s.rawTitle)
                    put("title", s.parsed.title)
                    put("artist", s.parsed.artist)
                    put("karaoke_no", s.parsed.karaokeNo)
                    put("variant", s.parsed.variant)
                    put("duration_sec", s.durationSec)
                    put("embeddable", if (s.embeddable) 1 else 0)
                    put("published_at", s.publishedAt)
                    put("title_key", Hangul.normalize(s.parsed.title))
                    val key = Hangul.normalize(s.parsed.title + s.parsed.artist)
                    put("search_key", key + (s.parsed.karaokeNo ?: ""))
                    put("chosung_key", Hangul.chosung(key))
                    val artistKey = Hangul.normalize(s.parsed.artist)
                    put("artist_key", artistKey)
                    put("title_chosung", Hangul.chosung(Hangul.normalize(s.parsed.title)))
                    put("artist_chosung", Hangul.chosung(artistKey))
                }
                val row = db.insertWithOnConflict("song", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                if (row != -1L && s.embeddable) added++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return added
    }

    fun knownIds(ids: List<String>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        val placeholders = ids.joinToString(",") { "?" }
        return readableDatabase.rawQuery(
            "SELECT video_id FROM song WHERE video_id IN ($placeholders)", ids.toTypedArray()
        ).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
    }

    /** 곡 단위로 묶기 전 원본 결과라 넉넉히 가져온다 (버전·브랜드별 중복 포함). */
    fun search(query: String, channels: List<Channel>, field: SearchField = SearchField.ALL, limit: Int = 300): List<Song> {
        val terms = query.trim().split(Regex("\\s+")).map { Hangul.normalize(it) }.filter { it.isNotEmpty() }
        if (terms.isEmpty() || channels.isEmpty()) return emptyList()

        // normalize 가 기호를 모두 제거하므로 LIKE 의 %, _ 는 검색어에 남지 않는다.
        val where = StringBuilder("embeddable = 1 AND playable = 1")
        val args = mutableListOf<String>()
        where.append(" AND channel_id IN (${channels.joinToString(",") { "?" }})")
        args += channels.map { it.id }
        for (t in terms) {
            val col = if (Hangul.isChosungOnly(t)) field.chosungColumn else field.column
            where.append(" AND $col LIKE ?")
            args += "%$t%"
        }
        val whole = terms.joinToString("")
        args += whole
        args += "$whole%"

        return readableDatabase.rawQuery(
            """
            SELECT $COLUMNS FROM song WHERE $where
            ORDER BY (title_key = ?) DESC, (title_key LIKE ?) DESC, (variant IS NULL) DESC, published_at DESC
            LIMIT $limit
            """.trimIndent(),
            args.toTypedArray()
        ).use { c -> buildList { while (c.moveToNext()) add(c.toSong()) } }
    }

    fun get(videoId: String): Song? =
        readableDatabase.rawQuery(
            "SELECT $COLUMNS FROM song WHERE video_id = ? AND embeddable = 1 AND playable = 1",
            arrayOf(videoId)
        ).use { c -> if (c.moveToFirst()) c.toSong() else null }

    fun hasSongs(): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM song LIMIT 1", null).use { it.moveToFirst() }

    /** 재생 불가로 표시된 곡인지 (예약 실패 이유 안내용) */
    fun isMarkedUnplayable(videoId: String): Boolean =
        readableDatabase.rawQuery("SELECT playable FROM song WHERE video_id = ?", arrayOf(videoId))
            .use { c -> c.moveToFirst() && c.getInt(0) == 0 }

    fun countUnplayable(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM song WHERE playable = 0", null)
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** 재생 오류로 표시된 곡을 모두 되살린다 (테스트 중 잘못 표시된 경우 등) */
    fun resetUnplayable() {
        writableDatabase.execSQL("UPDATE song SET playable = 1 WHERE playable = 0")
    }

    fun markUnplayable(videoId: String) {
        writableDatabase.execSQL("UPDATE song SET playable = 0 WHERE video_id = ?", arrayOf(videoId))
    }

    /** 검색 가능한 곡 수 */
    fun countPlayable(channelId: String): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM song WHERE channel_id = ? AND embeddable = 1 AND playable = 1",
            arrayOf(channelId)
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun syncState(playlistId: String): SyncState =
        readableDatabase.rawQuery(
            "SELECT full_done, page_token, completed_at FROM sync_state WHERE playlist_id = ?", arrayOf(playlistId)
        ).use { c ->
            if (c.moveToFirst()) SyncState(c.getInt(0) == 1, c.getString(1), c.getLong(2)) else SyncState(false, null)
        }

    fun saveSyncState(playlistId: String, state: SyncState) {
        val cv = ContentValues().apply {
            put("playlist_id", playlistId)
            put("full_done", if (state.fullDone) 1 else 0)
            put("page_token", state.pageToken)
            put("completed_at", state.completedAt)
        }
        writableDatabase.insertWithOnConflict("sync_state", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun Cursor.toSong(): Song {
        val channelId = getString(1)
        return Song(
            videoId = getString(0),
            channelId = channelId,
            brand = Channels.brandOf(channelId),
            title = getString(2),
            artist = getString(3),
            karaokeNo = getString(4),
            variant = getString(5),
            durationSec = getInt(6),
        )
    }

    companion object {
        private const val COLUMNS = "video_id, channel_id, title, artist, karaoke_no, variant, duration_sec"

        @Volatile
        private var instance: SongDb? = null

        fun get(context: Context): SongDb =
            instance ?: synchronized(this) {
                instance ?: SongDb(context.applicationContext).also { instance = it }
            }
    }
}

data class NewSong(
    val videoId: String,
    val channelId: String,
    val rawTitle: String,
    val parsed: ParsedTitle,
    val durationSec: Int,
    val embeddable: Boolean,
    val publishedAt: String?,
)
