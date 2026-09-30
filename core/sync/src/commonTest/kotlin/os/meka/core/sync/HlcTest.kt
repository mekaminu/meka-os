package os.meka.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HlcTest {
    @Test
    fun localTimestampsAreStrictlyIncreasingEvenWhenWallClockStalls() {
        var t = 1_000L
        val c = HlcClock("a", { t })
        val a = c.now(); val b = c.now()
        t = 500 // wall clock went backwards
        val d = c.now()
        assertTrue(a < b && b < d)
        assertEquals(1_000L, d.wallMs)
    }

    @Test
    fun receiveMovesPastRemote() {
        val c = HlcClock("a", { 1_000L })
        c.receive(Hlc(5_000, 3, "b"))
        val next = c.now()
        assertTrue(next > Hlc(5_000, 3, "b"))
    }

    @Test
    fun remoteFarInTheFutureIsRejected() {
        val c = HlcClock("a", { 1_000L }, maxDriftMs = 10_000)
        assertFailsWith<ClockDriftException> { c.receive(Hlc(1_000 + 10_001, 0, "b")) }
    }

    @Test
    fun encodingPreservesOrderAndRoundTrips() {
        val xs = listOf(Hlc(2, 0, "a"), Hlc(1, 99, "z"), Hlc(1, 99, "b"), Hlc(1_790_000_000_000, 1, "mac"))
        assertEquals(xs.sorted(), xs.sortedBy { it.encode() })
        xs.forEach { assertEquals(it, Hlc.decode(it.encode())) }
    }

    @Test
    fun restoreNeverGoesBackwards() {
        val c = HlcClock("a", { 10L })
        c.restore(Hlc(50_000, 7, "a"))
        assertTrue(c.now() > Hlc(50_000, 7, "a"))
    }
}
