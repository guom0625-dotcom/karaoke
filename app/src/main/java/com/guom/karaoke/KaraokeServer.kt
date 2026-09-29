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
internal data class AddRequest(val videoId: String)

@Serializable
internal data class NicknameRequest(val nickname: String)

@Serializable
internal data class SessionResponse(val secret: String, val publicId: String, val nickname: String)

@Serializable
internal data class MeResponse(val publicId: String, val nickname: String)

@Serializable
internal data class StateMessage(
    val type: String = "state",
    val nowPlaying: QueueItem?,
    val queue: List<QueueItem>,
)

@Serializable
internal data class LinkRequest(val url: String)

@Serializable
internal data class OnlineSearchResponse(val groups: List<SongGroup>, val added: Int, val remainingToday: Int)

@Serializable
internal data class JoinInfo(val guestUrl: String?)

@Serializable
internal data class ProgressMessage(val type: String = "progress", val progress: Progress?)

@Serializable
internal data class CommandMessage(val type: String = "command", val action: String, val seconds: Double?)

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
            get("/player") {
                // 유튜브 앱 재생 방식에선 크롬 플레이어가 같이 재생하지 않도록 호스트 리모컨으로 보낸다
                if (Settings.playbackMode(context) == Settings.MODE_APP) return@get call.respondRedirect("/guest?hostmode=1")
                call.respondAsset("web/player.html")
            }
            get("/guest") { call.respondAsset("web/guest.html") }
            get("/static/{name}") {
                val name = call.parameters["name"].orEmpty()
                if (!SAFE_NAME.matches(name)) return@get call.respond(HttpStatusCode.NotFound)
                call.respondAsset("web/$name")
            }

            // ---- 접속 안내 (플레이어 화면 QR): 호스트만. <img> 는 헤더를 못 붙여 ?host= 로도 받는다 ----
            get("/api/join-info") {
                if (!call.isHostRequest()) return@get call.respond(HttpStatusCode.Forbidden)
                call.respond(joinInfo())
            }
            get("/qr/{kind}") {
                if (!call.isHostRequest()) return@get call.respond(HttpStatusCode.Forbidden)
                val text = when (call.parameters["kind"]) {
                    "guest.svg" -> joinInfo().guestUrl
                    else -> null
                } ?: return@get call.respond(HttpStatusCode.NotFound)
                call.response.header("Cache-Control", "no-cache")
                call.respondBytes(QrCodes.svg(text).toByteArray(), ContentType.Image.SVG)
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
                val field = SearchField.parse(call.request.queryParameters["field"])
                call.respond(withContext(Dispatchers.IO) { SongGrouping.group(db.search(q, Settings.enabledChannels(context), field), brand) })
            }

            // ---- 곡 DB 에 없는 곡: 유튜브에서 더 찾기 (하루 횟수 제한) ----
            post("/api/search/online") {
                if (!call.roomOk()) return@post call.respond(HttpStatusCode.Forbidden, "room")
                val q = call.request.queryParameters["q"].orEmpty().trim()
                if (q.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, "q")
                val field = SearchField.parse(call.request.queryParameters["field"])
                when (val r = withContext(Dispatchers.IO) { OnlineLookup.search(context, q) }) {
                    is OnlineLookup.Result.Found -> {
                        // 유튜브 검색은 느슨하게 관련된 영상까지 돌려준다. 찾은 곡은 모두 DB 에 저장하되(정식 노래방 영상),
                        // 화면엔 평소 검색과 같은 규칙으로 검색어가 실제로 들어간 곡만 보여준다.
                        val songs = withContext(Dispatchers.IO) { db.search(q, Settings.enabledChannels(context), field) }
                        val newIds = r.newSongs.map { it.videoId }.toSet()
                        call.respond(
                            OnlineSearchResponse(
                                SongGrouping.group(songs, Settings.preferredBrand(context)),
                                songs.count { it.videoId in newIds },
                                r.remainingToday,
                            )
                        )
                    }
                    OnlineLookup.Result.NoApiKey -> call.respond(HttpStatusCode.ServiceUnavailable, "nokey")
                    OnlineLookup.Result.DailyLimitReached -> call.respond(HttpStatusCode.TooManyRequests, "limit")
                    is OnlineLookup.Result.Failed -> call.respond(HttpStatusCode.BadGateway, r.reason)
                }
            }
            // 유튜브 링크로 곡 추가 (호스트만)
            post("/api/songs/by-link") {
                if (call.actor() != Actor.Host) return@post call.respond(HttpStatusCode.Forbidden)
                val url = call.receive<LinkRequest>().url
                when (val r = withContext(Dispatchers.IO) { OnlineLookup.addByLink(context, url) }) {
                    is OnlineLookup.LinkResult.Ok -> call.respond(r.song)
                    is OnlineLookup.LinkResult.Error -> call.respond(HttpStatusCode.UnprocessableEntity, r.message)
                }
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
                    handlePlaybackError(itemId, msg["code"]?.jsonPrimitive?.content?.toIntOrNull())
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

    /** 예약 항목별로 이미 실패한 영상 (대체 버전을 돌아가며 시도할 때 반복 방지) */
    private val triedVideos = java.util.concurrent.ConcurrentHashMap<Long, MutableSet<String>>()

    /**
     * 재생 오류: 막힌 영상이면 검색에서 빼고, 같은 곡의 다른 버전(TJ↔금영 등)으로 바꿔 계속 재생한다.
     * 대체 버전이 없을 때만 다음 곡으로 넘어간다.
     */
    private fun handlePlaybackError(itemId: Long, code: Int?) {
        val current = QueueManager.state.value.nowPlaying?.takeIf { it.id == itemId } ?: return
        val blocked = code != null && code in UNPLAYABLE_CODES
        if (blocked) db.markUnplayable(current.videoId)

        if (triedVideos.size > 200) triedVideos.clear()
        val tried = triedVideos.getOrPut(itemId) { mutableSetOf() }.apply { add(current.videoId) }
        val alt = SongGrouping.alternatives(
            current, db.search(SongGrouping.alternativeQuery(current.title, current.artist), Settings.enabledChannels(context)), tried
        ).firstOrNull()

        val what = "${current.title} - ${current.artist} (${current.brand} ${current.variant ?: "기본"}) 오류 $code"
        if (alt != null && QueueManager.replaceCurrent(itemId, alt) != null) {
            PlaybackLog.add("$what → ${alt.brand} ${alt.variant ?: "기본"} 버전으로 대체", current.videoId)
        } else {
            QueueManager.finish(itemId)
            triedVideos.remove(itemId)
            PlaybackLog.add("$what → 대체 버전 없음, 다음 곡으로", current.videoId)
        }
    }

    /** 현재 핫스팟 IP 기준 동승자 주소. IP 는 핫스팟을 켤 때마다 바뀔 수 있어 매번 계산한다. */
    private fun joinInfo(): JoinInfo {
        val ip = Network.candidates().firstOrNull()?.ip
        return JoinInfo(guestUrl = ip?.let { "http://$it:$PORT/guest?room=${Sessions.roomToken}" })
    }

    private fun RoutingCall.isHostRequest() =
        (request.headers["X-Host"] ?: request.queryParameters["host"]) == Sessions.hostToken

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
    data class Entry(val text: String, val videoId: String)

    private val _entries = kotlinx.coroutines.flow.MutableStateFlow<List<Entry>>(emptyList())
    val entries = _entries.asStateFlow()

    fun add(text: String, videoId: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA).format(java.util.Date())
        _entries.update { (listOf(Entry("$time $text", videoId)) + it).take(10) }
    }
}
