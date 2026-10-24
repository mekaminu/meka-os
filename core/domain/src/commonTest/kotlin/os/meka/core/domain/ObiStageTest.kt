package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Meka 2026-10-24 14:34: the orb's stage closed after two seconds and didn't listen. */
class ObiStageTest {
    private fun call(
        phase: TalkPhase, started: Boolean = true, spoke: Boolean = false, needsAsk: Boolean = false,
        problem: TalkProblem? = null, since: Long = 3_000, idle: Long? = null,
    ) = StageRules.call(phase, started, spoke, needsAsk, problem, since, idle)

    @Test
    fun aConversationThatEndsBeforeHeSpeaksNoLongerClosesTheStage() {
        // The 14:34 case: the conversation ended two seconds in with nothing heard. It used to close; now it stays.
        assertEquals(StageNext.CLOSE, HelloEntrance.next(TalkPhase.ENDED, started = true, needsAsk = false, msSinceGreet = 2_000))
        val c = call(TalkPhase.ENDED, idle = 0)
        assertEquals(StageNext.STAY, c.next)
        assertEquals(StageNote.NOTHING_HEARD, c.note)
        assertEquals("I didn't hear anything. Tap to talk, or swipe down to close.", StageRules.line(c.note, null))
        // A minute later with still nothing, it shrinks back and says why.
        assertEquals(StageCall(StageNext.CLOSE, StageNote.NOTHING_HEARD, StageClose.NO_SPEECH), call(TalkPhase.ENDED, idle = StageRules.IDLE_CLOSE_MS))
    }

    @Test
    fun aMicrophoneThatCantStartSaysSoOnTheStage() {
        val off = call(TalkPhase.ENDED, problem = TalkProblem.NO_PERMISSION, idle = 0)
        assertEquals(StageNext.STAY, off.next)
        assertEquals(StageNote.PROBLEM, off.note)
        assertEquals("I can't hear you: the microphone is off for Navilife.", StageRules.line(off.note, TalkProblem.NO_PERMISSION))
        assertEquals("Open settings", StageRules.button(off.note, TalkProblem.NO_PERMISSION))
        assertFalse(StageRules.tapRetries(off.note, TalkProblem.NO_PERMISSION))
        // Busy or failed: the reason, and a tap tries again.
        val busy = call(TalkPhase.ENDED, problem = TalkProblem.BUSY, idle = 0)
        assertTrue(StageRules.line(busy.note, TalkProblem.BUSY)!!.endsWith("Tap to try again."))
        assertNull(StageRules.button(busy.note, TalkProblem.BUSY))
        assertTrue(StageRules.tapRetries(busy.note, TalkProblem.BUSY))
        // Even after he spoke, a problem keeps the stage up to say so.
        assertEquals(StageNext.STAY, call(TalkPhase.ENDED, spoke = true, problem = TalkProblem.FAILED, idle = 0).next)
        assertEquals(StageClose.PROBLEM, call(TalkPhase.ENDED, problem = TalkProblem.FAILED, idle = 61_000).close)
    }

    @Test
    fun aHelloThatNeverBeganSaysSoInsteadOfVanishing() {
        assertEquals(StageCall(StageNext.STAY), call(TalkPhase.ENDED, started = false, since = 1_000))
        val stuck = call(TalkPhase.ENDED, started = false, since = HelloEntrance.NO_START_MS)
        assertEquals(StageNext.STAY, stuck.next)
        assertEquals(StageNote.NOT_STARTED, stuck.note)
        assertEquals("I couldn't start listening. Tap to try again, or swipe down to close.", StageRules.line(stuck.note, null))
        assertTrue(StageRules.tapRetries(stuck.note, null))
        assertEquals(StageClose.NOT_STARTED, call(TalkPhase.ENDED, started = false, since = HelloEntrance.NO_START_MS + StageRules.IDLE_CLOSE_MS).close)
    }

    @Test
    fun itClosesOnceHeHasSpokenOrAnAnswerNeedsAsk() {
        assertEquals(StageCall(StageNext.CLOSE, close = StageClose.SPOKE), call(TalkPhase.ENDED, spoke = true, idle = 0))
        assertEquals(StageCall(StageNext.CLOSE, close = StageClose.OPENED_ASK), call(TalkPhase.LISTENING, needsAsk = true))
        for (p in listOf(TalkPhase.SPEAKING, TalkPhase.LISTENING, TalkPhase.THINKING, TalkPhase.DOING)) {
            assertEquals(StageCall(StageNext.STAY), call(p, since = 120_000))
        }
    }

    /** Tap → hello → listen → answer → close, frame by frame as the stage sees it. */
    @Test
    fun tapHelloListenAnswerClose() {
        data class Frame(val ms: Long, val phase: TalkPhase, val heard: String)
        val frames = listOf(
            Frame(0, TalkPhase.ENDED, ""), // the greeting is due; Ask hasn't started it yet
            Frame(300, TalkPhase.SPEAKING, ""), // "Afternoon, Meka. It's Obi."
            Frame(2_000, TalkPhase.LISTENING, ""), // the hello's end: listening (the 14:34 stage closed about here)
            Frame(9_000, TalkPhase.LISTENING, "what's on tomorrow"),
            Frame(9_800, TalkPhase.THINKING, "what's on tomorrow"),
            Frame(11_000, TalkPhase.SPEAKING, "what's on tomorrow"),
            Frame(14_000, TalkPhase.LISTENING, ""), // conversation mode's window
            Frame(22_000, TalkPhase.ENDED, ""), // nothing more: it ends
        )
        var started = false
        var spoke = false
        var endedAt: Long? = null
        val calls = frames.map { f ->
            if (f.phase != TalkPhase.ENDED) started = true
            if (f.heard.isNotBlank()) spoke = true
            if (f.phase == TalkPhase.ENDED && started) endedAt = endedAt ?: f.ms
            f.ms to StageRules.call(f.phase, started, spoke, false, null, f.ms, endedAt?.let { f.ms - it })
        }
        assertTrue(calls.dropLast(1).all { it.second.next == StageNext.STAY }, "the stage stays through the whole turn")
        assertEquals(StageCall(StageNext.CLOSE, close = StageClose.SPOKE), calls.last().second)
    }

    @Test
    fun askBringsTheFieldBackWhenScrolledPastIt() {
        assertFalse(StageRules.fieldHidden(0))
        assertFalse(StageRules.fieldHidden(1))
        assertTrue(StageRules.fieldHidden(2))
        assertTrue(StageRules.fieldHidden(14))
    }

    @Test
    fun healthSaysWhyItLastClosedAndTheStoreKeepsNoWords() {
        val cal = LocalCalendar.UTC
        val day = CivilDate.toEpochDay(2026, 10, 24)
        val now = day * CivilDate.DAY_MS + (14 * 60 + 40) * HealthRules.MIN
        val at = now - 6 * HealthRules.MIN
        val c = StageClosed(StageClose.NO_SPEECH, at)
        assertEquals("Last conversation closed 14:34: no speech heard for a minute", StageRules.healthLine(c, now, cal))
        assertNull(StageRules.healthLine(null, now, cal))
        assertEquals("$at NO_SPEECH", StageRules.encode(c))
        assertEquals(c, StageRules.decode(StageRules.encode(c)))
        assertNull(StageRules.decode("garbage"))
        assertNull(StageRules.decode(null))
        assertNull(StageRules.decode("12 WHATEVER"))

        val server = ServerHealth(ServerHealth.PUSH_ON, calls = true, speech = true, atMs = now)
        fun voice(d: DeviceHealth) = HealthRules.view(
            HealthFacts(device = d, connected = true, lastSyncedMs = now, server = server, accounts = emptyList(),
                ai = AiStatusView("On", lit = false, canAsk = true), callAssistantOn = true, nowMs = now, aboutYou = null, bankSync = null),
            cal,
        ).rows.first { it.key == "voice" }
        val fold = DeviceHealth(mac = false, batteryExempt = true, notificationAccess = true, callRoleHeld = true, stageClosed = c)
        assertEquals("Last conversation closed 14:34: no speech heard for a minute", voice(fold).detail)
        val withFace = voice(fold.copy(face = FaceHealth(true, 7, null)))
        assertEquals("Face · 7 of 7 files on this phone\nLast conversation closed 14:34: no speech heard for a minute", withFace.detail)
    }
}
