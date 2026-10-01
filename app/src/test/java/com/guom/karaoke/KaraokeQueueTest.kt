package com.guom.karaoke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KaraokeQueueTest {
    private val alice = Actor.Guest("a", "앨리스")
    private val bob = Actor.Guest("b", "밥")

    private fun song(id: String, dur: Int = 200) = Song(id, "UC", "TJ", "곡$id", "가수", null, null, dur)

    @Test
    fun playsInReservationOrder() {
        val q = KaraokeQueue()
        val a1 = q.add(song("1"), alice)
        val b1 = q.add(song("2"), bob)
        val a2 = q.add(song("3"), alice)
        assertEquals(a1.id, q.state.value.nowPlaying?.id)
        assertEquals(listOf(b1.id, a2.id), q.state.value.queue.map { it.id })
        assertEquals("앨리스", a1.nickname)
        assertEquals("a", a1.ownerId)

        q.finish(a1.id)
        assertEquals(b1.id, q.state.value.nowPlaying?.id)
    }

    @Test
    fun onlyOwnerOrHostCanCancel() {
        val q = KaraokeQueue()
        q.add(song("1"), alice)
        val b1 = q.add(song("2"), bob)
        val a2 = q.add(song("3"), alice)

        assertEquals(Outcome.FORBIDDEN, q.cancel(b1.id, alice))
        assertEquals(Outcome.OK, q.cancel(b1.id, bob))
        assertEquals(Outcome.OK, q.cancel(a2.id, Actor.Host))
        assertEquals(Outcome.NOT_FOUND, q.cancel(999, Actor.Host))
        assertTrue(q.state.value.queue.isEmpty())
    }

    @Test
    fun onlyCurrentSingerOrHostCanSkipAndControl() {
        val q = KaraokeQueue()
        val a1 = q.add(song("1"), alice)
        val b1 = q.add(song("2"), bob)

        assertFalse(q.canControl(bob))
        assertEquals(Outcome.FORBIDDEN, q.skip(bob))
        assertEquals(Outcome.FORBIDDEN, q.control(PlayerCommand("seekBy", 10.0), bob))
        assertEquals(Outcome.OK, q.control(PlayerCommand("seekBy", 10.0), alice))
        assertEquals(Outcome.OK, q.skip(alice))
        assertEquals(b1.id, q.state.value.nowPlaying?.id)

        // 이제 밥 차례: 호스트는 언제든 가능
        assertEquals(Outcome.OK, q.skip(Actor.Host))
        assertNull(q.state.value.nowPlaying)
        assertEquals(Outcome.NOT_FOUND, q.skip(Actor.Host))
        assertTrue(a1.id < b1.id)
    }

    @Test
    fun duplicateEndedIsIgnored() {
        val q = KaraokeQueue()
        val a1 = q.add(song("1"), alice)
        val b1 = q.add(song("2"), bob)
        q.add(song("3"), bob)
        assertEquals(a1.id, q.finish(a1.id)?.id)
        assertNull(q.finish(a1.id)) // 같은 곡 종료 보고가 두 번 와도 한 곡만 넘어감
        assertEquals(b1.id, q.state.value.nowPlaying?.id)
    }

    @Test
    fun onlyHostCanReorder() {
        val q = KaraokeQueue()
        q.add(song("1"), alice)
        val x = q.add(song("2"), alice)
        val y = q.add(song("3"), bob)
        val z = q.add(song("4"), bob)

        assertEquals(Outcome.FORBIDDEN, q.move(z.id, -1, bob))
        assertEquals(Outcome.OK, q.move(z.id, -1000, Actor.Host))
        assertEquals(listOf(z.id, x.id, y.id), q.state.value.queue.map { it.id })
        assertEquals(Outcome.OK, q.move(z.id, 1, Actor.Host))
        assertEquals(listOf(x.id, z.id, y.id), q.state.value.queue.map { it.id })
    }

    private val carol = Actor.Guest("c", "캐럴")

    private fun KaraokeQueue.owners() = state.value.queue.map { it.ownerId }

    @Test
    fun rotateInterleavesByRegistrationOrder() {
        val q = KaraokeQueue()
        listOf("a", "b", "c").forEach { q.register(it) }
        q.setRotate(true)
        // 앨리스 혼자면 연속으로
        repeat(4) { q.add(song("a$it"), alice) }
        assertEquals("a", q.state.value.nowPlaying?.ownerId)
        assertEquals(listOf("a", "a", "a"), q.owners())

        // 캐럴이 먼저 예약해도 차례는 등록 순 (앨리스가 부르는 중 → 밥 → 캐럴 → 앨리스)
        q.add(song("c0"), carol)
        assertEquals(listOf("c", "a", "a", "a"), q.owners())
        q.add(song("b0"), bob)
        q.add(song("b1"), bob)
        assertEquals(listOf("b", "c", "a", "b", "a", "a"), q.owners())

        // 넘어가도 차례가 이어진다
        q.finish(q.state.value.nowPlaying!!.id)
        assertEquals("b", q.state.value.nowPlaying?.ownerId)
        assertEquals(listOf("c", "a", "b", "a", "a"), q.owners())
    }

    @Test
    fun rotateToggleAndCancel() {
        val q = KaraokeQueue()
        q.add(song("1"), alice)
        val a2 = q.add(song("2"), alice)
        val a3 = q.add(song("3"), alice)
        val b1 = q.add(song("4"), bob)
        assertEquals(listOf(a2.id, a3.id, b1.id), q.state.value.queue.map { it.id }) // 꺼짐: 예약 순

        q.setRotate(true) // 켜면 바로 섞는다
        assertEquals(listOf(b1.id, a2.id, a3.id), q.state.value.queue.map { it.id })
        assertTrue(q.state.value.rotate)

        // 같은 사람 곡끼리만 자리 바꿈
        assertEquals(Outcome.OK, q.move(a3.id, -1, Actor.Host))
        assertEquals(listOf(b1.id, a3.id, a2.id), q.state.value.queue.map { it.id })
        assertEquals(Outcome.OK, q.move(b1.id, 1, Actor.Host))
        assertEquals(listOf(b1.id, a3.id, a2.id), q.state.value.queue.map { it.id })

        assertEquals(Outcome.OK, q.cancel(b1.id, bob))
        assertEquals(listOf(a3.id, a2.id), q.state.value.queue.map { it.id })

        q.clear()
        assertTrue(q.state.value.rotate) // 서버를 껐다 켜도 유지
    }

    @Test
    fun progressOnlyForCurrentSongAndClearedOnAdvance() {
        val q = KaraokeQueue()
        val a1 = q.add(song("1"), alice)
        val b1 = q.add(song("2"), bob)
        q.reportProgress(Progress(b1.id, 10.0, 200.0, true))
        assertNull(q.progress.value)
        q.reportProgress(Progress(a1.id, 10.0, 200.0, true))
        assertEquals(10.0, q.progress.value!!.position, 0.0)
        q.finish(a1.id)
        assertNull(q.progress.value)
    }

    @Test
    fun replaceCurrentKeepsReservationAndOwner() {
        val q = KaraokeQueue()
        val a1 = q.add(song("1"), alice)
        val b1 = q.add(song("2"), bob)
        val alt = Song("k1", "UCKY", "KY", "곡1", "가수", "123", null, 210)

        assertNull(q.replaceCurrent(b1.id, alt)) // 재생 중인 곡이 아니면 무시
        val r = q.replaceCurrent(a1.id, alt)!!
        assertEquals(a1.id, r.id)
        assertEquals("k1", r.videoId)
        assertEquals("KY", r.brand)
        assertEquals("TJ", r.replacedFrom)
        assertEquals("a", r.ownerId)
        assertEquals(listOf(b1.id), q.state.value.queue.map { it.id })

        // 두 번 대체돼도 처음 브랜드를 기억
        assertEquals("TJ", q.replaceCurrent(a1.id, song("t9"))!!.replacedFrom)
    }

    @Test
    fun renameUpdatesOwnItems() {
        val q = KaraokeQueue()
        q.add(song("1"), alice)
        q.add(song("2"), bob)
        q.add(song("3"), alice)
        q.rename("a", "앨리")
        val s = q.state.value
        assertEquals("앨리", s.nowPlaying?.nickname)
        assertEquals(listOf("밥", "앨리"), s.queue.map { it.nickname })
    }
}
