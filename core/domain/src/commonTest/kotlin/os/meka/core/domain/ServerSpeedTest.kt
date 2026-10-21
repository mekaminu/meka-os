package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Talking to Ada like a conversation, slice 2: the server's own timings and the lines Speed and the report show. */
class ServerSpeedTest {
    private val now = 1_791_476_100_000L

    private fun t(open: Boolean, tok: Long, sen: Long?, clip: Long?, done: Long, spoken: Boolean = true, web: Long? = null, ago: Long = 60_000, model: String? = "claude-haiku-4-5") =
        ServerTurnTiming(now - ago, open, spoken, model, web, tok, sen, clip, done)

    @Test
    fun theStopwatchCountsEachMarkOnceFromTheQuestion() {
        val w = ServerTurnStopwatch(startMs = 1_000, open = false, spoken = true)
        w.firstToken(1_400); w.firstToken(1_500)
        w.firstSentence(1_800)
        w.clip(2_300)
        val t = w.finish(3_000, wallMs = now, model = " Claude-Haiku-4-5 ", answered = true)
        assertEquals(400L, t.firstTokenMs)
        assertEquals(800L, t.firstSentenceMs)
        assertEquals(1_300L, t.clipMs)
        assertEquals(2_000L, t.doneMs)
        assertNull(t.webMs)
        assertEquals("claude-haiku-4-5", t.model)
        // A clock jump never makes a mark; nor does anything that isn't a plain model id.
        val odd = ServerTurnStopwatch(5_000, true, false).apply { firstToken(4_000) }.finish(5_000 + ServerSpeedRules.MAX_MS + 1, now, "my name is Alex", false)
        assertNull(odd.firstTokenMs); assertNull(odd.doneMs); assertNull(odd.model)
    }

    @Test
    fun theLinesAreTheMiddleOfEachStepPerKind() {
        val list = listOf(
            t(false, 300, 600, 900, 1_500), t(false, 500, 1_200, 1_600, 2_000), t(false, 400, 800, 1_100, 1_800),
            t(true, 900, 1_500, 2_000, 4_000, web = 700, model = "claude-sonnet-4-5"),
        )
        val lines = ServerSpeedRules.lines(list, now)
        assertEquals(
            "Quick look-ups (3, all spoken): first words 0.4 s · first sentence 0.8 s · voice ready 1.1 s · whole answer 1.8 s · claude-haiku-4-5",
            lines[0],
        )
        assertEquals(
            "Conversation (1, all spoken): first words 0.9 s · first sentence 1.5 s, slow · voice ready 2.0 s · whole answer 4.0 s · web 0.7 s (1) · claude-sonnet-4-5",
            lines[1],
        )
    }

    @Test
    fun onlyTheLastDayCountsAndNoneSaysSo() {
        val old = t(false, 300, 600, null, 900, spoken = false, ago = ServerSpeedRules.WINDOW_MS + 1)
        val v = ServerSpeedRules.view(listOf(old), now)
        assertTrue(v.lines.isEmpty())
        assertEquals(ServerSpeedRules.EMPTY, v.empty)
        val typed = ServerSpeedRules.lines(listOf(t(false, 300, 600, null, 900, spoken = false), ServerTurnTiming(now, false, true, null, answered = false)), now)
        assertEquals("Quick look-ups (2, spoken 1, 1 not answered): first words 0.3 s · first sentence 0.6 s · whole answer 0.9 s · claude-haiku-4-5", typed.single())
    }

    @Test
    fun theServerKeepsTheNewestTwoHundred() {
        var list = emptyList<ServerTurnTiming>()
        repeat(ServerSpeedRules.KEPT + 5) { i -> list = ServerSpeedRules.add(list, t(false, i.toLong(), null, null, 1_000)) }
        assertEquals(ServerSpeedRules.KEPT, list.size)
        assertEquals(5L, list.first().firstTokenMs)
    }
}
