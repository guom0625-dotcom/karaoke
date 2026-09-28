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
    val durationSec: Int,
    /** 예약자 공개 ID ("host" = 호스트). 세션 secret 이 아니다. */
    val ownerId: String,
    val nickname: String,
    val addedAt: Long,
    /** 원래 영상이 막혀 다른 버전으로 바뀐 경우, 원래 브랜드 (예: "TJ") */
    val replacedFrom: String? = null,
)

@Serializable
data class PlayerState(
    val nowPlaying: QueueItem? = null,
    val queue: List<QueueItem> = emptyList(),
)

/** 플레이어 페이지가 보고하는 재생 위치 (초) */
@Serializable
data class Progress(val itemId: Long, val position: Double, val duration: Double, val playing: Boolean)

/** 서버 → 플레이어 페이지 재생 제어 */
@Serializable
data class PlayerCommand(val action: String, val seconds: Double? = null)

enum class Outcome { OK, NOT_FOUND, FORBIDDEN }

const val HOST_OWNER_ID = "host"

/**
 * 예약 큐 (메모리). 재생 순서는 예약한 시간 순.
 * 권한: 대기곡 취소·재생 중인 곡 제어(스킵·일시정지·이동)는 예약자 본인 + 호스트, 순서 변경은 호스트만.
 */
open class KaraokeQueue(private val clock: () -> Long = System::currentTimeMillis) {
    private val nextId = AtomicLong(1)

    private val _state = MutableStateFlow(PlayerState())
    val state = _state.asStateFlow()

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress = _progress.asStateFlow()

    private val _commands = MutableSharedFlow<PlayerCommand>(extraBufferCapacity = 16)
    val commands = _commands.asSharedFlow()

    fun add(song: Song, by: Actor): QueueItem {
        val (ownerId, nickname) = when (by) {
            Actor.Host -> HOST_OWNER_ID to "호스트"
            is Actor.Guest -> by.id to by.nickname
        }
        val item = QueueItem(
            nextId.getAndIncrement(), song.videoId, song.title, song.artist, song.brand,
            song.karaokeNo, song.variant, song.durationSec, ownerId, nickname, clock(),
        )
        _state.update { s ->
            if (s.nowPlaying == null) s.copy(nowPlaying = item) else s.copy(queue = s.queue + item)
        }
        return item
    }

    fun cancel(itemId: Long, by: Actor): Outcome {
        var outcome = Outcome.NOT_FOUND
        _state.update { s ->
            val item = s.queue.firstOrNull { it.id == itemId }
            outcome = when {
                item == null -> Outcome.NOT_FOUND
                !owns(by, item) -> Outcome.FORBIDDEN
                else -> Outcome.OK
            }
            if (outcome == Outcome.OK) s.copy(queue = s.queue.filterNot { it.id == itemId }) else s
        }
        return outcome
    }

    /** 순서 변경 (호스트만). delta 가 음수면 앞으로. */
    fun move(itemId: Long, delta: Int, by: Actor): Outcome {
        if (by != Actor.Host) return Outcome.FORBIDDEN
        var outcome = Outcome.NOT_FOUND
        _state.update { s ->
            val from = s.queue.indexOfFirst { it.id == itemId }
            if (from < 0) {
                outcome = Outcome.NOT_FOUND
                return@update s
            }
            outcome = Outcome.OK
            val to = (from + delta).coerceIn(0, s.queue.lastIndex)
            val list = s.queue.toMutableList()
            list.add(to, list.removeAt(from))
            s.copy(queue = list)
        }
        return outcome
    }

    /** 재생 중인 곡을 제어할 수 있는지 (예약자 본인 또는 호스트) */
    fun canControl(by: Actor): Boolean {
        val np = _state.value.nowPlaying ?: return false
        return owns(by, np)
    }

    fun skip(by: Actor): Outcome {
        var outcome = Outcome.NOT_FOUND
        _state.update { s ->
            val np = s.nowPlaying
            outcome = when {
                np == null -> Outcome.NOT_FOUND
                !owns(by, np) -> Outcome.FORBIDDEN
                else -> Outcome.OK
            }
            if (outcome == Outcome.OK) advance(s) else s
        }
        if (outcome == Outcome.OK) _progress.value = null
        return outcome
    }

    /** play / pause / seekBy(초) / seekTo(초) */
    fun control(command: PlayerCommand, by: Actor): Outcome {
        if (_state.value.nowPlaying == null) return Outcome.NOT_FOUND
        if (!canControl(by)) return Outcome.FORBIDDEN
        _commands.tryEmit(command)
        return Outcome.OK
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
        if (finished != null) _progress.value = null
        return finished
    }

    /** 재생 중인 곡을 같은 곡의 다른 버전으로 바꾼다 (예약·예약자는 그대로). */
    fun replaceCurrent(itemId: Long, song: Song): QueueItem? {
        var replaced: QueueItem? = null
        _state.update { s ->
            val np = s.nowPlaying
            if (np == null || np.id != itemId) {
                replaced = null
                s
            } else {
                val r = np.copy(
                    videoId = song.videoId, title = song.title, artist = song.artist, brand = song.brand,
                    karaokeNo = song.karaokeNo, variant = song.variant, durationSec = song.durationSec,
                    replacedFrom = np.replacedFrom ?: np.brand,
                )
                replaced = r
                s.copy(nowPlaying = r)
            }
        }
        if (replaced != null) _progress.value = null
        return replaced
    }

    /** 현재 곡의 진행 상황만 받는다 (늦게 도착한 이전 곡 보고는 무시) */
    fun reportProgress(p: Progress) {
        if (_state.value.nowPlaying?.id == p.itemId) _progress.value = p
    }

    fun rename(ownerId: String, nickname: String) {
        _state.update { s ->
            fun QueueItem.renamed() = if (this.ownerId == ownerId) copy(nickname = nickname) else this
            s.copy(nowPlaying = s.nowPlaying?.renamed(), queue = s.queue.map { it.renamed() })
        }
    }

    private fun owns(by: Actor, item: QueueItem) = when (by) {
        Actor.Host -> true
        is Actor.Guest -> item.ownerId == by.id
    }

    private fun advance(s: PlayerState) =
        PlayerState(nowPlaying = s.queue.firstOrNull(), queue = s.queue.drop(1))
}

/** 앱 전체가 공유하는 큐 (서버·호스트 화면) */
object QueueManager : KaraokeQueue()
