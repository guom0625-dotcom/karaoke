package com.guom.karaoke

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * YouTube Data API v3 (API 키만 사용). 키는 안드로이드 앱 제한이 걸려 있으므로
 * 요청마다 패키지명과 서명 인증서 SHA-1 헤더를 붙인다.
 * search.list 는 쓰지 않는다 (호출당 100유닛).
 */
class YouTubeApi(
    private val apiKey: String,
    private val packageName: String,
    private val certSha1: String,
) {
    class ApiException(val httpCode: Int, val reason: String, message: String) : Exception(message)

    data class ChannelInfo(val uploadsPlaylistId: String, val videoCount: Long)
    data class PlaylistEntry(
        val videoId: String,
        val title: String,
        val description: String,
        val publishedAt: String?,
        /** 영상을 올린 채널. 채널 재생목록에는 다른 채널 영상이 섞일 수 있다. */
        val ownerChannelId: String?,
    )
    data class PlaylistInfo(val id: String, val title: String, val itemCount: Long)
    data class PlaylistPage(val items: List<PlaylistEntry>, val nextPageToken: String?)
    data class VideoDetails(val durationSec: Int, val embeddable: Boolean)
    data class VideoInfo(
        val id: String,
        val channelId: String,
        val title: String,
        val description: String,
        val publishedAt: String?,
        val durationSec: Int,
        val embeddable: Boolean,
    )

    /** 이번 실행에서 쓴 할당량 (목록 호출은 모두 1유닛) */
    var unitsUsed = 0
        private set

    fun channel(channelId: String): ChannelInfo {
        val item = get("channels", mapOf("part" to "contentDetails,statistics", "id" to channelId))
            .arr("items").firstOrNull()?.jsonObject
            ?: throw ApiException(404, "channelNotFound", "채널을 찾을 수 없음: $channelId")
        return ChannelInfo(
            uploadsPlaylistId = item.obj("contentDetails").obj("relatedPlaylists").str("uploads")!!,
            videoCount = item.obj("statistics").str("videoCount")?.toLongOrNull() ?: 0,
        )
    }

    fun playlistItems(playlistId: String, pageToken: String?): PlaylistPage {
        val params = mutableMapOf("part" to "snippet", "playlistId" to playlistId, "maxResults" to "50")
        if (pageToken != null) params["pageToken"] = pageToken
        val res = get("playlistItems", params)
        val items = res.arr("items").mapNotNull { el ->
            val snippet = el.jsonObject.obj("snippet")
            val videoId = snippet.obj("resourceId").str("videoId") ?: return@mapNotNull null
            PlaylistEntry(
                videoId = videoId,
                title = snippet.str("title").orEmpty(),
                description = snippet.str("description").orEmpty(),
                publishedAt = snippet.str("publishedAt"),
                ownerChannelId = snippet.str("videoOwnerChannelId"),
            )
        }
        return PlaylistPage(items, res.str("nextPageToken"))
    }

    /** 채널이 직접 만든 공개 재생목록 전체 (50개당 1유닛) */
    fun channelPlaylists(channelId: String): List<PlaylistInfo> {
        val result = mutableListOf<PlaylistInfo>()
        var token: String? = null
        do {
            val params = mutableMapOf("part" to "snippet,contentDetails", "channelId" to channelId, "maxResults" to "50")
            if (token != null) params["pageToken"] = token
            val res = get("playlists", params)
            for (el in res.arr("items")) {
                val p = el.jsonObject
                result += PlaylistInfo(
                    id = p.str("id") ?: continue,
                    title = p.obj("snippet").str("title").orEmpty(),
                    itemCount = p.obj("contentDetails").str("itemCount")?.toLongOrNull() ?: 0,
                )
            }
            token = res.str("nextPageToken")
        } while (token != null)
        return result
    }

    /**
     * 채널 안에서 키워드 검색 (호출당 100유닛 — 곡 DB 에 없는 곡을 찾을 때만 쓴다).
     * 영상 ID 목록을 돌려준다.
     */
    fun search(query: String, channelId: String, maxResults: Int = 25): List<String> =
        get(
            "search",
            mapOf("part" to "id", "q" to query, "channelId" to channelId, "type" to "video", "maxResults" to "$maxResults")
        ).arr("items").mapNotNull { it.jsonObject.obj("id").str("videoId") }

    /** 영상 상세 (제목·전체 설명·채널·길이·퍼가기). 50개당 1유닛 */
    fun videoInfos(ids: List<String>): List<VideoInfo> {
        if (ids.isEmpty()) return emptyList()
        return get("videos", mapOf("part" to "snippet,contentDetails,status", "id" to ids.joinToString(","), "maxResults" to "50"))
            .arr("items").mapNotNull { el ->
                val v = el.jsonObject
                val snippet = v.obj("snippet")
                VideoInfo(
                    id = v.str("id") ?: return@mapNotNull null,
                    channelId = snippet.str("channelId").orEmpty(),
                    title = snippet.str("title").orEmpty(),
                    description = snippet.str("description").orEmpty(),
                    publishedAt = snippet.str("publishedAt"),
                    durationSec = parseIsoDuration(v.obj("contentDetails").str("duration").orEmpty()),
                    embeddable = v.obj("status")["embeddable"]?.jsonPrimitive?.content == "true",
                )
            }
    }

    fun videos(ids: List<String>): Map<String, VideoDetails> {
        if (ids.isEmpty()) return emptyMap()
        return get("videos", mapOf("part" to "contentDetails,status", "id" to ids.joinToString(","), "maxResults" to "50"))
            .arr("items").associate { el ->
                val v = el.jsonObject
                v.str("id")!! to VideoDetails(
                    durationSec = parseIsoDuration(v.obj("contentDetails").str("duration").orEmpty()),
                    embeddable = v.obj("status")["embeddable"]?.jsonPrimitive?.content == "true",
                )
            }
    }

    private fun get(resource: String, params: Map<String, String>): JsonObject {
        val query = (params + ("key" to apiKey)).entries.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
        val conn = URL("https://www.googleapis.com/youtube/v3/$resource?$query").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("X-Android-Package", packageName)
        conn.setRequestProperty("X-Android-Cert", certSha1)
        try {
            val code = conn.responseCode
            unitsUsed++
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val err = runCatching { AppJson.parseToJsonElement(body).jsonObject.obj("error") }.getOrNull()
                val reason = err?.arr("errors")?.firstOrNull()?.jsonObject?.str("reason") ?: "http$code"
                throw ApiException(code, reason, err?.str("message") ?: "HTTP $code")
            }
            return AppJson.parseToJsonElement(body).jsonObject
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** 저장된 API 키로 클라이언트 생성 (키가 없으면 null) */
        fun create(context: Context): YouTubeApi? {
            val key = Settings.apiKey(context)?.takeIf { it.isNotBlank() } ?: return null
            return YouTubeApi(key, context.packageName, signingCertSha1(context))
        }

        /** API 키의 안드로이드 앱 제한에 쓰이는 서명 인증서 SHA-1 (대문자 hex, 구분자 없음) */
        fun signingCertSha1(context: Context): String {
            val pm = context.packageManager
            val cert = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo!!.apkContentsSigners[0]
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures!![0]
            }
            return MessageDigest.getInstance("SHA-1").digest(cert.toByteArray())
                .joinToString("") { "%02X".format(it) }
        }
    }
}

private fun JsonObject.obj(key: String): JsonObject = this[key]?.jsonObject ?: JsonObject(emptyMap())
private fun JsonObject.arr(key: String): JsonArray = this[key]?.jsonArray ?: JsonArray(emptyList())
private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content
