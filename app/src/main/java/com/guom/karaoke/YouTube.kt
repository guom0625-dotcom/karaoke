package com.guom.karaoke

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object YouTube {
    private val ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val ID_IN_URL = Regex("(?:[?&]v=|youtu\\.be/|/shorts/|/embed/|/live/)([A-Za-z0-9_-]{11})")

    /** 영상 ID 또는 유튜브 URL에서 11자리 영상 ID를 뽑는다. */
    fun parseVideoId(input: String): String? {
        val s = input.trim()
        if (ID.matches(s)) return s
        return ID_IN_URL.find(s)?.groupValues?.get(1)
    }

    /** oEmbed로 제목 조회 (API 키 불필요). 실패 시 null. */
    suspend fun fetchTitle(videoId: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val watch = URLEncoder.encode("https://www.youtube.com/watch?v=$videoId", "UTF-8")
            val conn = URL("https://www.youtube.com/oembed?format=json&url=$watch")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            try {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                AppJson.parseToJsonElement(body).jsonObject["title"]?.jsonPrimitive?.content
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /** 입력을 해석해 큐에 추가. 영상 ID를 못 찾으면 null. */
    suspend fun enqueue(input: String): QueueItem? {
        val id = parseVideoId(input) ?: return null
        return QueueManager.add(id, fetchTitle(id) ?: id)
    }
}
