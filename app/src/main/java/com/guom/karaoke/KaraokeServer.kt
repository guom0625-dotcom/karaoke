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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
private data class StateMessage(
    val type: String = "state",
    val nowPlaying: QueueItem?,
    val queue: List<QueueItem>,
)

@Serializable
private data class CommandMessage(val type: String = "command", val action: String)

class KaraokeServer(private val context: Context) {
    private var server: EmbeddedServer<*, *>? = null

    fun start() {
        if (server != null) return
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
            get("/") { call.respondRedirect("/player") }
            get("/player") { call.respondAsset("web/player.html") }
            get("/static/{name}") {
                val name = call.parameters["name"].orEmpty()
                if (!SAFE_NAME.matches(name)) return@get call.respond(HttpStatusCode.NotFound)
                call.respondAsset("web/$name")
            }

            get("/api/queue") { call.respond(QueueManager.state.value) }
            post("/api/queue") {
                val req = call.receive<AddRequest>()
                val item = YouTube.enqueue(req.videoId)
                    ?: return@post call.respond(HttpStatusCode.BadRequest, "invalid videoId")
                call.respond(item)
            }
            delete("/api/queue/{id}") {
                val id = call.parameters["id"]?.toLongOrNull()
                val ok = id != null && QueueManager.remove(id)
                call.respond(if (ok) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
            }
            post("/api/player/{action}") {
                when (val action = call.parameters["action"]) {
                    "play", "pause" -> QueueManager.command(action)
                    "skip" -> QueueManager.skip()
                    else -> return@post call.respond(HttpStatusCode.NotFound)
                }
                call.respond(HttpStatusCode.NoContent)
            }

            webSocket("/ws") {
                val stateJob = launch {
                    QueueManager.state.collect { s ->
                        send(Frame.Text(AppJson.encodeToString(StateMessage.serializer(), StateMessage(nowPlaying = s.nowPlaying, queue = s.queue))))
                    }
                }
                val commandJob = launch {
                    QueueManager.commands.collect { action ->
                        send(Frame.Text(AppJson.encodeToString(CommandMessage.serializer(), CommandMessage(action = action))))
                    }
                }
                try {
                    for (frame in incoming) {
                        if (frame is Frame.Text) handleClientMessage(frame.readText())
                    }
                } finally {
                    stateJob.cancel()
                    commandJob.cancel()
                }
            }
        }
    }

    /** 플레이어 페이지 → 서버: ended / error / skip */
    private fun handleClientMessage(text: String) {
        runCatching {
            val msg = AppJson.parseToJsonElement(text).jsonObject
            val itemId = msg["itemId"]?.jsonPrimitive?.long
            when (msg["type"]?.jsonPrimitive?.content) {
                // 에러(100/101/150 등)도 일단 다음 곡으로 넘김. 재생불가 표시는 곡 DB 도입 후.
                "ended", "error" -> if (itemId != null) QueueManager.finish(itemId)
                "skip" -> QueueManager.skip()
            }
        }
    }

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
    }
}
