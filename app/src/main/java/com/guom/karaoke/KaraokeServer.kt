package com.guom.karaoke

import android.content.Context
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.defaultForFilePath
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.time.Duration.Companion.seconds

val AppJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

@Serializable
private data class AddRequest(val videoId: String)

@Serializable
private data class NicknameRequest(val nickname: String)

@Serializable
private data class SessionResponse(val secret: String, val publicId: String, val nickname: String)

@Serializable
private data class MeResponse(val publicId: String, val nickname: String)

@Serializable
private data class StateMessage(
    val type: String = "state",
    val nowPlaying: QueueItem?,
    val queue: List<QueueItem>,
)

@Serializable
private data class ProgressMessage(val type: String = "progress", val progress: Progress?)

@Serializable
private data class CommandMessage(val type: String = "command", val action: String, val seconds: Double?)

/**
 * 로컬 HTTP + WebSocket 서버.
 * 인증 헤더: X-Host(호스트 토큰), X-Room(방 토큰), X-Session(동승자 세션 secret)
 */
class KaraokeServer(private val context: Context) {
    private var server: EmbeddedServer<*, *>? = null
    private val db = SongDb.get(context)

    fun start() {
        if (server != null) return
        Sessions.init(context)
        server = embeddedServer(CIO, port = PORT, host = "0.0.0.0") { module() }.start(wait = false)
    }

    fun stop() {
        server?.stop(500, 1000)
        server = null
    }

    private fun Application.module() {
        install(WebSockets) { pingPeriod = 15.seconds }
        install(ContentNegotiation) { json(AppJson) }

        routing {
            get("/") { call.respondRedirect("/guest") }
            get("/player") { call.respondAsset("web/player.html") }
            get("/guest") { call.respondAsset("web/guest.html") }
            get("/static/{name}") {
                val name = call.parameters["name"].orEmpty()
                if (!SAFE_NAME.matches(name)) return@get call.respond(HttpStatusCode.NotFound)
                call.respondAsset("web/$name")
            }

            // ---- 동승자 세션 ----
            post("/api/session") {
                if (!call.roomOk()) return@post call.respond(HttpStatusCode.Forbidden, "room")
                val nickname = Sessions.cleanNickname(call.receive<NicknameRequest>().nickname)
                    ?: return@post call.respond(HttpStatusCode.BadRequest, "nickname")
                val s = Sessions.create(nickname)
                call.respond(SessionResponse(s.secret, s.publicId, s.nickname))
            }
            get("/api/me") {
                val s = Sessions.find(call.request.headers["X-Session"])
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)
                call.respond(MeResponse(s.publicId, s.nickname))
            }
            post("/api/me") {
                val s = Sessions.find(call.request.headers["X-Session"])
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val nickname = Sessions.cleanNickname(call.receive<NicknameRequest>().nickname)
                    ?: return@post call.respond(HttpStatusCode.BadRequest, "nickname")
                s.nickname = nickname
                QueueManager.rename(s.publicId, nickname)
                call.respond(MeResponse(s.publicId, s.nickname))
            }

            // ---- 곡 검색 (곡 단위로 묶음) ----
            get("/api/search") {
                if (!call.roomOk()) return@get call.respond(HttpStatusCode.Forbidden, "room")
                val q = call.request.queryParameters["q"].orEmpty()
                val brand = Settings.preferredBrand(context)
                call.respond(withContext(Dispatchers.IO) { SongGrouping.group(db.search(q), brand) })
            }

            // ---- 예약 큐 ----
            get("/api/queue") { call.respond(QueueManager.state.value) }
            post("/api/queue") {
                val actor = call.actor() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val req = call.receive<AddRequest>()
                // 곡 DB(화이트리스트 채널)에 있는 재생 가능한 곡만 예약할 수 있다.
                // 같은 곡을 여러 번 예약하는 것은 허용한다.
                val song = withContext(Dispatchers.IO) { db.get(req.videoId) }
                if (song == null) {
                    val unplayable = withContext(Dispatchers.IO) { db.isMarkedUnplayable(req.videoId) }
                    return@post call.respond(HttpStatusCode.Conflict, if (unplayable) "unplayable" else "unknown song")
                }
                call.respond(QueueManager.add(song, actor))
            }
            delete("/api/queue/{id}") {
                val actor = call.actor() ?: return@delete call.respond(HttpStatusCode.Unauthorized)
                val id = call.parameters["id"]?.toLongOrNull() ?: return@delete call.respond(HttpStatusCode.NotFound)
                call.respondOutcome(QueueManager.cancel(id, actor))
            }
            post("/api/queue/{id}/move") {
                val actor = call.actor() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val id = call.parameters["id"]?.toLongOrNull() ?: return@post call.respond(HttpStatusCode.NotFound)
                val delta = call.request.queryParameters["delta"]?.toIntOrNull() ?: 0
                call.respondOutcome(QueueManager.move(id, delta, actor))
            }

            // ---- 재생 제어: 재생 중인 곡의 예약자 본인 + 호스트 ----
            post("/api/player/{action}") {
                val actor = call.actor() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val seconds = call.request.queryParameters["seconds"]?.toDoubleOrNull()
                val outcome = when (val action = call.parameters["action"]) {
                    "skip" -> QueueManager.skip(actor)
                    "play", "pause" -> QueueManager.control(PlayerCommand(action), actor)
                    "seekBy", "seekTo" ->
                        if (seconds == null) return@post call.respond(HttpStatusCode.BadRequest, "seconds")
                        else QueueManager.control(PlayerCommand(action, seconds), actor)
                    else -> return@post call.respond(HttpStatusCode.NotFound)
                }
                call.respondOutcome(outcome)
            }

            webSocket("/ws") {
                val isHost = call.request.queryParameters["host"] == Sessions.hostToken
                val jobs = listOf(
                    launch {
                        QueueManager.state.collect { s ->
                            send(Frame.Text(AppJson.encodeToString(StateMessage.serializer(), StateMessage(nowPlaying = s.nowPlaying, queue = s.queue))))
                        }
                    },
                    launch {
                        QueueManager.progress.collect { p ->
                            send(Frame.Text(AppJson.encodeToString(ProgressMessage.serializer(), ProgressMessage(progress = p))))
                        }
                    },
                    // 재생 제어 명령은 플레이어(호스트) 페이지에만 보낸다
                    if (isHost) launch {
                        QueueManager.commands.collect { c ->
                            send(Frame.Text(AppJson.encodeToString(CommandMessage.serializer(), CommandMessage(action = c.action, seconds = c.seconds))))
                        }
                    } else null,
                ).filterNotNull()
                try {
                    for (frame in incoming) {
                        // 재생 종료·오류·진행 보고는 플레이어(호스트)만 보낼 수 있다
                        if (isHost && frame is Frame.Text) handlePlayerMessage(frame.readText())
                    }
                } finally {
                    jobs.forEach { it.cancel() }
                }
            }
        }
    }

    /** 플레이어 페이지 → 서버: ended / error / progress / skip */
    private fun handlePlayerMessage(text: String) {
        runCatching {
            val msg = AppJson.parseToJsonElement(text).jsonObject
            val itemId = msg["itemId"]?.jsonPrimitive?.long
            when (msg["type"]?.jsonPrimitive?.content) {
                "ended" -> if (itemId != null) QueueManager.finish(itemId)
                // 다음 곡으로 넘긴다. 영상 자체 문제(100 없음/비공개, 101·150 퍼가기 불가)만
                // 재생 불가로 표시해 검색에서 뺀다. 2·5·153 등은 일시적·환경 문제일 수 있어 표시하지 않는다.
                "error" -> if (itemId != null) {
                    val code = msg["code"]?.jsonPrimitive?.content?.toIntOrNull()
                    QueueManager.finish(itemId)?.let { item ->
                        val marked = code != null && code in UNPLAYABLE_CODES
                        if (marked) db.markUnplayable(item.videoId)
                        PlaybackLog.add("${item.title} - ${item.artist} · 오류 $code${if (marked) " (검색에서 제외)" else ""}")
                    }
                }
                "progress" -> if (itemId != null) QueueManager.reportProgress(
                    Progress(
                        itemId = itemId,
                        position = msg["position"]!!.jsonPrimitive.double,
                        duration = msg["duration"]!!.jsonPrimitive.double,
                        playing = msg["playing"]!!.jsonPrimitive.boolean,
                    )
                )
                "skip" -> QueueManager.skip(Actor.Host)
            }
        }
    }

    private fun RoutingCall.actor(): Actor? {
        if (request.headers["X-Host"] == Sessions.hostToken) return Actor.Host
        val s = Sessions.find(request.headers["X-Session"]) ?: return null
        return Actor.Guest(s.publicId, s.nickname)
    }

    private fun RoutingCall.roomOk() =
        request.headers["X-Room"] == Sessions.roomToken || request.headers["X-Host"] == Sessions.hostToken

    private suspend fun RoutingCall.respondOutcome(outcome: Outcome) = respond(
        when (outcome) {
            Outcome.OK -> HttpStatusCode.NoContent
            Outcome.NOT_FOUND -> HttpStatusCode.NotFound
            Outcome.FORBIDDEN -> HttpStatusCode.Forbidden
        }
    )

    private suspend fun RoutingCall.respondAsset(path: String) {
        val bytes = withContext(Dispatchers.IO) {
            runCatching { context.assets.open(path).use { it.readBytes() } }.getOrNull()
        } ?: return respond(HttpStatusCode.NotFound)
        response.header("Cache-Control", "no-cache")
        respondBytes(bytes, ContentType.defaultForFilePath(path))
    }

    companion object {
        const val PORT = 8080
        private val SAFE_NAME = Regex("^[A-Za-z0-9._-]+$")
        private val UNPLAYABLE_CODES = setOf(100, 101, 150)
    }
}

/** 최근 재생 오류 (호스트 화면에서 원인 확인용) */
object PlaybackLog {
    private val _entries = kotlinx.coroutines.flow.MutableStateFlow<List<String>>(emptyList())
    val entries = _entries.asStateFlow()

    fun add(text: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA).format(java.util.Date())
        _entries.update { (listOf("$time $text") + it).take(10) }
    }
}
