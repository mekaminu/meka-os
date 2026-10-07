package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The call assistant's screening (build plan M1, Needs Meka #9): who rings during work and who is declined. */
class CallScreeningTest {
    private val t = 1_791_360_000_000L
    private val min = 60_000L
    private val lists = PeopleLists(family = setOf("Mum"), alwaysNotify = setOf("Ada", "Tom"))
        .withNumber("Mum", "+44 7700 900111")
        .withNumber("Ada", "07700 900222")

    private fun decide(number: String?, recent: List<ScreenedCall> = emptyList(), on: Boolean = true, atWork: Boolean = true, now: Long = t) =
        CallScreeningRules.decide(on, atWork, number, lists, recent, now)

    @Test
    fun everyCallRingsWhenSwitchedOffOrOffWork() {
        assertEquals(CallReason.SWITCHED_OFF, decide("07700 900999", on = false).reason)
        assertTrue(decide("07700 900999", on = false).rings)
        assertEquals(CallReason.OFF_WORK, decide("07700 900999", atWork = false).reason)
        assertTrue(decide(null, atWork = false).rings)
    }

    @Test
    fun familyAndAlwaysNotifyRingMatchedByNumberInAnyFormat() {
        val mum = decide("07700900111")
        assertTrue(mum.rings)
        assertEquals(CallReason.FAMILY, mum.reason)
        assertEquals("Mum", mum.listedName)
        val ada = decide("+447700900222")
        assertEquals(CallReason.ALWAYS_RING, ada.reason)
        assertEquals("Ada", ada.listedName)
        // Tom is listed by name only (picked before numbers were kept): his calls can't be recognised.
        assertFalse(lists.hasNumber("Tom"))
        assertTrue(lists.hasNumber("Mum"))
    }

    @Test
    fun othersAreDeclinedAndASecondCallWithinThreeMinutesRings() {
        val first = decide("07700 900999")
        assertEquals(CallVerdict.DECLINE, first.verdict)
        assertEquals(CallReason.AT_WORK, first.reason)
        var recent = CallScreeningRules.remember(emptyList(), first, t)
        assertEquals(listOf(ScreenedCall("tel:7700900999", t)), recent)

        val again = decide("+44 7700 900999", recent, now = t + 2 * min)
        assertTrue(again.rings)
        assertEquals(CallReason.REPEAT, again.reason)
        recent = CallScreeningRules.remember(recent, again, t + 2 * min)
        assertTrue(recent.isEmpty()) // let through: the next call starts over

        // Too late for the repeat rule: declined again.
        val late = decide("07700 900999", listOf(ScreenedCall("tel:7700900999", t)), now = t + 3 * min + 1)
        assertEquals(CallVerdict.DECLINE, late.verdict)
        // Another caller doesn't count as a repeat.
        assertEquals(CallVerdict.DECLINE, decide("07700 900888", listOf(ScreenedCall("tel:7700900999", t)), now = t + min).verdict)
    }

    @Test
    fun withheldNumbersGetTheRepeatRuleToo() {
        val first = decide(null)
        assertEquals(CallVerdict.DECLINE, first.verdict)
        assertEquals(CallScreeningRules.WITHHELD, first.callerKey)
        val recent = CallScreeningRules.remember(emptyList(), first, t)
        assertTrue(decide("", recent, now = t + min).rings)
        assertNull(lists.nameForNumber("Private"))
    }

    @Test
    fun recentDeclinesForgetOldOnesAndRoundTrip() {
        val old = ScreenedCall("tel:1111111111", t - 10 * min)
        val d = decide("07700 900999", now = t)
        val recent = CallScreeningRules.remember(listOf(old), d, t)
        assertEquals(listOf(ScreenedCall("tel:7700900999", t)), recent)
        val both = recent + ScreenedCall(CallScreeningRules.WITHHELD, t + 1)
        assertEquals(both, CallScreeningRules.decode(CallScreeningRules.encode(both)))
        assertEquals(emptyList(), CallScreeningRules.decode(null))
        assertEquals(emptyList(), CallScreeningRules.decode("garbage;@12;x@y"))
    }

    @Test
    fun numbersFollowTheirNames() {
        val l = lists.copy(alwaysNotify = setOf("Tom")).pruned()
        assertEquals(setOf("Mum"), l.numbers.keys)
        assertNull(l.nameForNumber("07700 900222"))
        assertEquals(lists, lists.withNumber("Mum", "  "))
    }

    @Test
    fun statusLines() {
        assertEquals("Off · calls ring as usual", CallScreeningRules.statusLine(false, true, true))
        assertEquals("On · allow MEKA to screen calls on the Fold", CallScreeningRules.statusLine(true, true, false))
        assertEquals("Screening calls · family, always-notify and repeat callers ring", CallScreeningRules.statusLine(true, true, true))
        assertEquals("On at work · calls ring as usual now", CallScreeningRules.statusLine(true, false, null))
    }

    @Test
    fun theSwitchSyncsBetweenDevicesAndStartsOff() {
        val world = SyncWorld()
        val fold = world.device("android")
        val mac = world.device("mac")
        val clock = LocalClock(3, 10 * 60)
        val onFold = WorkMode(fold.replica) { world.clock.nowMs }
        val onMac = WorkMode(mac.replica) { world.clock.nowMs }
        assertFalse(onFold.state(clock).callAssistant)
        onFold.setCallAssistant(true)
        fold.sync(); mac.sync()
        assertTrue(onMac.state(clock).callAssistant)
        world.clock.nowMs += 1_000
        onMac.setCallAssistant(false) // one switch turns it off, from either app
        mac.sync(); fold.sync()
        assertFalse(onFold.state(clock).callAssistant)
    }
}
