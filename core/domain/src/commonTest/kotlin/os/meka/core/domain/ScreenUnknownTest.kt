package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "Screen unknown callers all the time" (Meka 2026-10-22: a scam caller claiming to be the police rang through outside
 * work hours): with it on, unknown numbers go to the assistant evenings and weekends too; people he knows still ring.
 */
class ScreenUnknownTest {
    private val t = 1_791_360_000_000L
    private val min = 60_000L
    private val lists = PeopleLists(family = setOf("Alex"), alwaysNotify = setOf("Sam"))
        .withNumber("Alex", "+44 7700 900111")
        .withNumber("Sam", "07700 900222")

    private fun decide(
        number: String?,
        always: Boolean = true,
        on: Boolean = true,
        atWork: Boolean = false,
        recent: List<ScreenedCall> = emptyList(),
        signals: CallSignals = CallSignals.NONE,
        paused: Boolean = false,
        now: Long = t,
    ) = CallScreeningRules.decide(on, atWork, number, lists, recent, now, signals = signals, paused = paused, screenUnknownAlways = always)

    @Test
    fun offWorkAnUnknownNumberGoesToTheAssistantOnlyWithTheSettingOn() {
        val d = decide("01632 960123")
        assertEquals(CallVerdict.DECLINE, d.verdict)
        assertEquals(CallReason.UNKNOWN_ALWAYS, d.reason)
        assertTrue(CallScreeningRules.remembers(d))
        // Off (the default): it rings, as before.
        assertEquals(CallReason.OFF_WORK, decide("01632 960123", always = false).reason)
        // The setting needs the call assistant on: with it off every call rings.
        assertEquals(CallReason.SWITCHED_OFF, decide("01632 960123", on = false).reason)
        // A withheld number too, off work.
        assertEquals(CallReason.UNKNOWN_ALWAYS, decide(null).reason)
    }

    @Test
    fun peopleHeKnowsStillRing() {
        assertEquals(CallReason.OFF_WORK, decide("07700900111").reason) // family
        assertEquals(CallReason.OFF_WORK, decide("+447700900222").reason) // always notify
        assertTrue(decide("01632 960123", signals = CallSignals(knownContact = true)).rings)
        assertTrue(decide("01632 960123", signals = CallSignals(calledRecently = true)).rings)
        assertTrue(decide("01632 960123", signals = CallSignals(household = true)).rings)
    }

    @Test
    fun aRedialWithinThreeMinutesRingsAndEmergencyNumbersAreNeverScreened() {
        val first = decide("01632 960123")
        val recent = CallScreeningRules.remember(emptyList(), first, t)
        val again = decide("+441632960123", recent = recent, now = t + 2 * min)
        assertTrue(again.rings)
        assertEquals(CallReason.REPEAT, CallScreeningRules.decide(true, true, "+441632960123", lists, recent, t + 2 * min).reason)
        assertEquals(CallReason.OFF_WORK, again.reason)
        assertEquals(CallReason.UNKNOWN_ALWAYS, decide("01632 960123", recent = recent, now = t + 3 * min + 1).reason)
        assertEquals(CallReason.EMERGENCY, decide("999").reason)
        assertEquals(CallReason.EMERGENCY, decide("101").reason)
    }

    @Test
    fun atWorkNothingChangesAndAPausedAssistantLetsItRing() {
        assertEquals(CallReason.AT_WORK, decide("01632 960123", atWork = true).reason)
        assertEquals(CallReason.PAUSED, decide("01632 960123", paused = true).reason)
    }

    @Test
    fun theWorkScreenAndActivitySayWhy() {
        assertEquals(
            "Screening unknown numbers · people you know ring as usual",
            CallScreeningRules.statusLine(true, false, true, false, screenUnknownAlways = true),
        )
        assertEquals("On at work · calls ring as usual now · spam goes to the assistant", CallScreeningRules.statusLine(true, false, true))
        val (summary, why) = CallScreeningRules.activityLine(decide("01632 960123"), "01632 960123")!!
        assertTrue(summary.startsWith("Sent a call from "))
        assertEquals(ScreenUnknownRules.ACTIVITY_WHY, why)
        assertEquals("Sent a withheld call to the assistant", CallScreeningRules.activityLine(decide(null), null)!!.first)
        assertTrue(ScreenUnknownRules.line(on = true, assistantOn = false).startsWith("Turn the call assistant on first"))
        assertTrue(ScreenUnknownRules.line(on = true, assistantOn = true).startsWith("On ·"))
        assertTrue(ScreenUnknownRules.line(on = false, assistantOn = true).startsWith("Off ·"))
    }

    @Test
    fun theSettingIsSyncedAndOffUntilTurnedOn() {
        val world = SyncWorld()
        val fold = world.device("android")
        val mac = world.device("mac")
        val clock = LocalClock(6, 18 * 60)
        val onFold = WorkMode(fold.replica) { world.clock.nowMs }
        val onMac = WorkMode(mac.replica) { world.clock.nowMs }
        assertFalse(onFold.state(clock).screenUnknownAlways)
        assertTrue(onFold.setScreenUnknownAlways(true))
        assertFalse(onFold.setScreenUnknownAlways(true))
        fold.sync(); mac.sync()
        assertTrue(onMac.state(clock).screenUnknownAlways)
        world.clock.nowMs += 1_000
        assertTrue(onMac.setScreenUnknownAlways(false))
        mac.sync(); fold.sync()
        assertFalse(onFold.state(clock).screenUnknownAlways)
    }
}
