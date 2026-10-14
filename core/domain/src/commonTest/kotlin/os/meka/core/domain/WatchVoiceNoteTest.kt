package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A voice note on the Galaxy Watch (Voice notes into actions, slice 4). */
class WatchVoiceNoteTest {
    private val r = WatchVoiceNoteRules

    @Test
    fun captureHearsAVoiceNoteRequestAndNothingElse() {
        assertTrue(r.asks("take a voice note"))
        assertTrue(r.asks("Navilife, record a voice memo"))
        assertTrue(r.asks("voice note"))
        assertFalse(r.asks("buy milk"))
        assertFalse(r.asks("note"))
        assertFalse(r.asks("where did I park")) // Find my car isn't on the watch
        assertFalse(r.asks(null))
    }

    @Test
    fun itListensAgainUntilDonePausedFiveMinutesOrAMinuteOfQuiet() {
        assertTrue(r.listenAgain(elapsedMs = 30_000, paused = false, stopped = false, quietMs = 0))
        assertFalse(r.listenAgain(30_000, paused = true, stopped = false, quietMs = 0))
        assertFalse(r.listenAgain(30_000, paused = false, stopped = true, quietMs = 0))
        assertFalse(r.listenAgain(VoiceNoteRules.MAX_MS, paused = false, stopped = false, quietMs = 0))
        assertFalse(r.listenAgain(90_000, paused = false, stopped = false, quietMs = r.QUIET_STOP_MS))
        assertEquals("Recording · 0:42", r.recordingLine(42_000, false))
        assertEquals("Recording · 4:31 · stops at 5:00", r.recordingLine(271_000, false))
        assertTrue(r.nearEnd(271_000))
        assertFalse(r.nearEnd(42_000))
    }

    @Test
    fun theWordsShowTheirTailWhileRecordingAndGoOutAsSentences() {
        assertNull(r.live(emptyList(), " "))
        assertEquals("ring the garage tomorrow", r.live(listOf("ring the garage"), "tomorrow"))
        val long = r.live(listOf("so I need to ring the garage about the service and then pick up the dry cleaning on the way"), "home from the station")!!
        assertTrue(long.startsWith("…"))
        assertTrue(long.endsWith("home from the station"))
        assertTrue(long.length <= 91)
        assertEquals("Ring the garage. Buy milk.", r.words(listOf("ring the garage"), "buy milk"))
        assertEquals("", r.words(emptyList(), null))
    }

    @Test
    fun theResultSaysWhereTheCardsAreOrThatTheWordsWereKept() {
        val t = r.tidied(2, "Ring the garage about the service tomorrow. " + "Film night on Fridays with Sam ".repeat(5), listOf("Sam"))
        assertEquals("Tidied · 2 things to add", t.line)
        assertEquals("On your phone in Needs you", t.whereLine)
        assertEquals("Mentions Sam", t.peopleLine)
        assertTrue(t.tidy!!.endsWith("…") && t.tidy!!.length <= r.TIDY_MAX + 1)
        assertNull(t.taskId)
        val none = r.tidied(0, "", emptyList())
        assertEquals("Tidied · nothing in it to add", none.line)
        assertNull(none.whereLine)
        assertNull(none.tidy)
        val kept = r.keptAsTask("t1", offline = true)
        assertEquals("Navilife couldn't read it just now · your words are kept as a task", kept.line)
        assertEquals("t1", kept.taskId)
        assertEquals("Kept as a task", r.keptAsTask("t2", offline = false).line)
        assertEquals("Kept as a task. Undo for 8 seconds.", r.spoken(r.keptAsTask("t2", offline = false)))
        assertEquals("Tidied · nothing in it to add.", r.spoken(none))
    }
}
