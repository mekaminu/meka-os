package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Week board by hand, slice 6: the Sunsama tick on Today (a ticked row stays a moment, then sits in the done group). */
class TodayTickTest {
    private fun task(id: String, title: String = id, completed: Long? = null) =
        Task(id, title, null, Lifecycle.DONE, null, null, null, 0, null, null, 0, completed, false)

    @Test
    fun aTickedRowStaysPutForTheSettleThenMovesMotionOffAtOnce() {
        // The check draws, then the row holds its place until the settle is up.
        assertEquals(BoardHandRules.SETTLE_MS - 300, TodayTickRules.settleWaitMs(300, reduced = false))
        assertEquals(0L, TodayTickRules.settleWaitMs(5_000, reduced = false))
        assertEquals(0L, TodayTickRules.settleWaitMs(0, reduced = true))
    }

    @Test
    fun theDoneGroupShowsTheLatestFirstAndFoldsTheRest() {
        val done = (1..10).map { task("t$it", completed = 100L - it) }
        assertEquals("10 done today", TodayTickRules.label(done))
        assertEquals(TodayTickRules.MAX_SHOWN, TodayTickRules.shown(done).size)
        assertEquals("t1", TodayTickRules.shown(done).first().id)
        assertEquals("+2 more done", TodayTickRules.moreLine(done))
        // Opened: all of them, no more line.
        assertEquals(10, TodayTickRules.shown(done, open = true).size)
        assertNull(TodayTickRules.moreLine(done, open = true))
    }

    @Test
    fun nothingDoneMeansNoHeadingAndFewDoneNoMoreLine() {
        assertNull(TodayTickRules.label(emptyList()))
        assertNull(TodayTickRules.moreLine(listOf(task("a", completed = 1))))
        assertEquals("1 done today", TodayTickRules.label(listOf(task("a", completed = 1))))
    }

    @Test
    fun theDoneRowSaysWhatATapDoesAndTheBarSaysWhatHappened() {
        assertEquals("Done: Book MOT. Tap to un-tick", TodayTickRules.untickLabel("Book MOT"))
        assertEquals("Not done “Book MOT”", TodayTickRules.untickedLine("Book MOT"))
    }
}
