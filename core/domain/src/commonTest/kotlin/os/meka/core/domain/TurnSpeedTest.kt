package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TurnSpeedTest {
    private val cal = object : LocalCalendar {
        override fun epochDayOf(epochMs: Long) = epochMs.floorDiv(86_400_000L)
        override fun minuteOfDay(epochMs: Long) = (epochMs.mod(86_400_000L) / 60_000L).toInt()
        override fun toEpochMs(epochDay: Long, minuteOfDay: Int) = epochDay * 86_400_000L + minuteOfDay * 60_000L
    }
    private val at = cal.toEpochMs(CivilDate.toEpochDay(2026, 10, 20), 20 * 60 + 41)

    @Test
    fun aTalkTurnSplitsTheWaitIntoItsSteps() {
        val w = TurnStopwatch(TurnKind.TALK, startMs = 1_000)
        w.sent(1_120)
        w.firstPiece(2_300)
        w.clip(2_310, tookMs = 0, audio = true) // the early sentence came with the answer
        w.clip(3_000, tookMs = 450, audio = true)
        w.firstPiece(9_999) // counted once
        val t = w.finish(at)!!
        assertEquals(1_310, t.toVoiceMs)
        assertEquals(120, t.toSentMs)
        assertEquals(1_180, t.toFirstPieceMs)
        assertEquals(10, t.toClipMs)
        assertEquals(listOf(0L, 450L), t.clipMs)
        assertEquals("20:41 · Talk · 1.3 s · sent 0.1 s · answer 1.2 s · voice 0.1 s · clips ready, 0.5 s", TurnSpeedRules.row(t, cal))
    }

    @Test
    fun aHelloTurnIsTapToVoiceAndSlowOverItsAim() {
        val w = TurnStopwatch(TurnKind.HELLO, startMs = 0)
        w.clip(820, tookMs = 560, audio = true)
        val t = w.finish(at)!!
        assertEquals(820, t.toVoiceMs)
        assertNull(t.toSentMs)
        assertNull(t.toClipMs)
        assertEquals("20:41 · Hello · 0.8 s, slow · clips 0.6 s", TurnSpeedRules.row(t, cal))
    }

    @Test
    fun theDevicesOwnVoiceIsNamed() {
        val w = TurnStopwatch(TurnKind.HELLO, startMs = 0)
        w.clip(300, tookMs = 300, audio = false)
        val t = w.finish(at)!!
        assertTrue(t.deviceVoice)
        assertTrue(TurnSpeedRules.row(t, cal, device = "Mac").contains("Mac's voice"))
    }

    @Test
    fun aTurnWithNoVoiceOrAClockJumpIsNotKept() {
        assertNull(TurnStopwatch(TurnKind.TALK, 0).apply { sent(10) }.finish(at))
        assertNull(TurnStopwatch(TurnKind.TALK, 5_000).apply { clip(1_000, 0, true) }.finish(at))
        assertTrue(TurnStopwatch(TurnKind.TALK, 0).open(59_000))
        assertTrue(!TurnStopwatch(TurnKind.TALK, 0).open(61_000))
    }

    @Test
    fun keepsTheLastTwentyNewestFirst() {
        var all = emptyList<TurnTrace>()
        repeat(25) { i -> all = TurnSpeedRules.add(all, TurnTrace(TurnKind.TALK, at + i * 60_000L, 1_000L + i)) }
        assertEquals(TurnSpeedRules.KEPT, all.size)
        val v = TurnSpeedRules.view(all, cal)
        assertEquals(20, v.rows.size)
        assertTrue(v.rows.first().startsWith("21:05 · Talk · 1.0 s"))
        assertEquals(TurnSpeedRules.CLEAR, v.clear)
        assertNull(v.empty)
        val none = TurnSpeedRules.view(emptyList(), cal)
        assertEquals(TurnSpeedRules.EMPTY, none.empty)
        assertNull(none.summary)
        assertNull(none.clear)
    }

    @Test
    fun theSummaryNamesTheMiddlesAndWhereTheTimeGoes() {
        val traces = listOf(
            TurnTrace(TurnKind.HELLO, at, 500),
            TurnTrace(TurnKind.TALK, at, 1_900, toSentMs = 100, toFirstPieceMs = 1_300, toClipMs = 500),
            TurnTrace(TurnKind.TALK, at, 1_700, toSentMs = 100, toFirstPieceMs = 1_100, toClipMs = 500),
            TurnTrace(TurnKind.TALK, at, 2_400, toSentMs = 200, toFirstPieceMs = 1_600, toClipMs = 600),
        )
        assertEquals(
            "Hello middle 0.5 s (aim 0.6 s) · answers middle 1.9 s (aim 1.5 s) · most of the wait: the answer",
            TurnSpeedRules.summary(traces),
        )
    }

    @Test
    fun theStoreHoldsNumbersOnlyAndReadsBack() {
        val traces = listOf(
            TurnTrace(TurnKind.HELLO, at, 500, clipMs = listOf(0)),
            TurnTrace(TurnKind.TALK, at + 1, 1_900, 100, 1_300, 500, listOf(0, 420), deviceVoice = true),
        )
        val text = TurnSpeedRules.encode(traces)
        assertTrue(text.all { it.isDigit() || it in "|,\nhtd" })
        assertEquals(traces, TurnSpeedRules.decode(text))
        assertEquals(emptyList(), TurnSpeedRules.decode("garbage\nx|1"))
        assertEquals(emptyList(), TurnSpeedRules.decode(null))
    }
}
