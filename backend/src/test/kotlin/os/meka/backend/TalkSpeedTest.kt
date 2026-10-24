package os.meka.backend

import os.meka.core.domain.ServerSpeedRules
import os.meka.core.domain.ServerTurnTiming
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Talking to Obi like a conversation, slice 2: the server keeps its answer timings and the deploy job prints them. */
class TalkSpeedTest {
    private val now = 1_791_476_100_000L

    @Test
    fun theStoreKeepsTheNewestAndReadsTheLastDay() {
        val speed = TalkSpeed(InMemoryTalkSpeedStore(), nowMs = { now })
        speed.record("hh", ServerTurnTiming(now - ServerSpeedRules.WINDOW_MS - 1, false, true, "claude-haiku-4-5", doneMs = 900))
        repeat(ServerSpeedRules.KEPT + 3) { speed.record("hh", ServerTurnTiming(now - 1_000, false, true, "claude-haiku-4-5", firstTokenMs = it.toLong())) }
        speed.record("other", ServerTurnTiming(now, true, false, null))
        val read = speed.recent(DeviceIdentity("hh", "fold"))
        assertEquals(ServerSpeedRules.KEPT, read.size)
        assertEquals(3L, read.first().firstTokenMs)
    }

    @Test
    fun theDeployJobPrintsOneNoticePerKind() {
        val turns = listOf(
            TalkSpeed.wire(ServerTurnTiming(now - 60_000, false, true, "claude-haiku-4-5", firstTokenMs = 400, firstSentenceMs = 700, clipMs = 1_000, doneMs = 1_500)),
            TalkSpeed.wire(ServerTurnTiming(now - 30_000, true, false, "claude-sonnet-4-5", firstTokenMs = 900, firstSentenceMs = 1_400, doneMs = 3_000)),
        )
        val lines = ReleasePublisherCli.speedLines(turns, now)
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("::notice title=Talk speed (server, last day)::Quick look-ups (1, all spoken): first words 0.4 s"))
        assertTrue(lines[1].contains("Conversation (1): first words 0.9 s · first sentence 1.4 s, slow"))
        assertEquals(listOf("::notice title=Talk speed::no answers in the last day"), ReleasePublisherCli.speedLines(emptyList(), now))
        assertEquals(listOf("::notice title=Talk speed::this server doesn't time its answers yet"), ReleasePublisherCli.speedLines(null, now))
    }
}
