package com.guom.karaoke

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicLong

@Serializable
data class QueueItem(
    val id: Long,
    val videoId: String,
    val title: String,
    val artist: String,
    val brand: String,
    val karaokeNo: String?,
    val variant: String?,
)

@Serializable
data class PlayerState(
    val nowPlaying: QueueItem? = null,
    val queue: List<QueueItem> = emptyList(),
)

/** 메모리 내 예약 큐. 서버(WebSocket/API)와 호스트 화면이 공유한다. */
object QueueManager {
    private val nextId = AtomicLong(1)

    private val _state = MutableStateFlow(PlayerState())
    val state = _state.asStateFlow()

    /** 플레이어 페이지로 보내는 재생 제어 (play / pause) */
    private val _commands = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val commands = _commands.asSharedFlow()

    fun add(song: Song): QueueItem {
        val item = QueueItem(
            nextId.getAndIncrement(), song.videoId, song.title, song.artist,
            song.brand, song.karaokeNo, song.variant,
        )
        _state.update { s ->
            if (s.nowPlaying == null) s.copy(nowPlaying = item) else s.copy(queue = s.queue + item)
        }
        return item
    }

    fun remove(id: Long): Boolean {
        var removed = false
        _state.update { s ->
            val next = s.queue.filterNot { it.id == id }
            removed = next.size != s.queue.size
            s.copy(queue = next)
        }
        return removed
    }

    /**
     * 현재 곡이 itemId일 때만 다음 곡으로 넘어간다 (중복 종료 통보 방지).
     * 실제로 넘어갔으면 끝난 항목을 돌려준다.
     */
    fun finish(itemId: Long): QueueItem? {
        var finished: QueueItem? = null
        _state.update { s ->
            if (s.nowPlaying?.id == itemId) {
                finished = s.nowPlaying
                advance(s)
            } else {
                finished = null
                s
            }
        }
        return finished
    }

    fun skip() {
        _state.update { s -> if (s.nowPlaying != null) advance(s) else s }
    }

    fun command(action: String) {
        _commands.tryEmit(action)
    }

    private fun advance(s: PlayerState) =
        PlayerState(nowPlaying = s.queue.firstOrNull(), queue = s.queue.drop(1))
}
