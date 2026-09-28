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
