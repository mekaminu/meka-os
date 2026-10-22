package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The in-call scam warning (scam call fix, slice 2): an unknown caller who rings through gets a warning over the call
 * screen, a caller claiming an official body always does, and people he knows never do.
 */
class InCallWarningTest {
    private val t = 1_791_360_000_000L
    private val lists = PeopleLists(family = setOf("Alex"), alwaysNotify = setOf("Sam"))
        .withNumber("Alex", "+44 7700 900111")
        .withNumber("Sam", "07700 900222")

    private fun decide(
        number: String?,
        atWork: Boolean = false,
        on: Boolean = true,
        signals: CallSignals = CallSignals.NONE,
        recent: List<ScreenedCall> = emptyList(),
        paused: Boolean = false,
    ) = CallScreeningRules.decide(on, atWork, number, lists, recent, t, signals = signals, paused = paused)

    private fun warns(number: String?, signals: CallSignals = CallSignals.NONE, atWork: Boolean = false, on: Boolean = true,
                      recent: List<ScreenedCall> = emptyList(), paused: Boolean = false) =
        InCallWarningRules.shouldWarn(decide(number, atWork, on, signals, recent, paused), signals)

    @Test
    fun anUnknownNumberRingingThroughGetsTheWarning() {
        assertTrue(warns("01632 960123"))                 // off work
        assertTrue(warns("01632 960123", on = false))     // the assistant off
        assertTrue(warns(null))                           // withheld, off work
        // A stranger's redial at work rings on the repeat rule: still unknown, still warned.
        val key = CallScreeningRules.callerKey("01632 960123")
        assertTrue(warns("01632 960123", atWork = true, recent = listOf(ScreenedCall(key, t - 60_000L))))
        // Paused assistant: the stranger rings instead of going to it, so warned.
        assertTrue(warns("01632 960123", atWork = true, paused = true))
    }

    @Test
    fun peopleHeKnowsAndCallsThatDontRingGetNoWarning() {
        assertFalse(warns("+44 7700 900111"))             // family
        assertFalse(warns("07700 900222"))                // always notify
        assertFalse(warns("01632 960123", CallSignals(knownContact = true)))
        assertFalse(warns("01632 960123", CallSignals(calledRecently = true)))
        assertFalse(warns("01632 960123", CallSignals(household = true)))
        assertFalse(warns("01632 960123", CallSignals(fromAssistant = true)))
        assertFalse(warns("999"))                         // emergency
        assertFalse(warns("01632 960123", atWork = true)) // declined to the assistant: it never rang
        // Screening didn't answer in time, or it's his own number: nothing.
        assertFalse(InCallWarningRules.shouldWarn(null, CallSignals.NONE))
        assertFalse(InCallWarningRules.shouldWarn(decide("01632 960123"), CallSignals.NONE, ownNumber = true))
    }

    @Test
    fun aClaimedOfficialBodyAlwaysGetsTheWarningWithHowToCheck() {
        val police = CallSignals(networkName = "Metropolitan Police")
        val d = decide("020 7946 0000", atWork = true, signals = police)
        assertEquals(CallReason.OFFICIAL_CLAIM, d.reason)
        assertTrue(InCallWarningRules.shouldWarn(d, police))
        val w = InCallWarningRules.warning(police, withheld = false)
        assertEquals(OfficialClaim.POLICE, w.claim)
        assertEquals("Says it's the police · unknown number", w.title)
        assertNotNull(w.check)
        assertTrue(w.check!!.contains("101"))
        assertTrue(w.big.startsWith(InCallWarningRules.ADVICE))
        assertTrue(w.spoken.contains("the police"))
    }

    @Test
    fun theWordingIsPlainAndAdaOnlySpeaksIntoHeadphones() {
        val w = InCallWarningRules.warning(CallSignals.NONE, withheld = false)
        assertEquals("Unknown number", w.title)
        assertNull(w.check)
        assertEquals(w.line, w.big)
        assertTrue(w.line.contains("never give card details"))
        assertTrue(w.line.contains("101"))
        assertEquals("Withheld number", InCallWarningRules.warning(CallSignals.NONE, withheld = true).title)
        assertTrue(InCallWarningRules.speak(headphones = true))
        assertFalse(InCallWarningRules.speak(headphones = false))
    }
}
