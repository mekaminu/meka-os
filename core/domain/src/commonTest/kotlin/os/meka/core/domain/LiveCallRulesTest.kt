package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveCallRulesTest {
    @Test
    fun capIsOneOfTheChoicesElseTwentyPounds() {
        assertEquals(20, LiveCallRules.capGbp(null))
        assertEquals(30, LiveCallRules.capGbp(30))
        assertEquals(20, LiveCallRules.capGbp(25))
        assertEquals(20, LiveCallRules.capGbp(-1))
    }

    @Test
    fun aThreeMinuteCallCostsAboutTheProposedPence() {
        val micro = LiveCallRules.costMicroUsd(180, 60)
        // 3 phone minutes (14k each) + Gemini (180 s heard at 5k/min, 60 s spoken at 18k/min).
        assertEquals(3 * 14_000L + 15_000L + 18_000L, micro)
        val pence = LiveModeRules.pence(micro, LiveModeRules.FALLBACK_GBP_PER_USD_MICROS)
        assertTrue(pence in 5..25, "a 3-minute call is a few pence, was $pence")
        assertEquals(0, LiveCallRules.costMicroUsd(-5, 10))
        // Obi can't speak longer than the call.
        assertEquals(LiveCallRules.costMicroUsd(60, 60), LiveCallRules.costMicroUsd(60, 600))
    }

    @Test
    fun liveOnlyWhenOnAndUnderTheCap() {
        assertTrue(LiveCallRules.allowed(true, 1_999, 2_000))
        assertFalse(LiveCallRules.allowed(true, 2_000, 2_000))
        assertFalse(LiveCallRules.allowed(false, 0, 2_000))
    }

    @Test
    fun theMeterIsThisMonthsOnly() {
        assertEquals("2026-11", LiveCallRules.month(1_794_000_000_000L))
        assertEquals("1970-01", LiveCallRules.month(0))
        assertNull(LiveCallRules.meter("2026-10", 500, 3, "2026-11"))
        assertEquals(LiveCallRules.Meter("2026-11", 340, 14), LiveCallRules.meter("2026-11", 340, 14, "2026-11"))
    }

    @Test
    fun theLineSaysSpendAgainstTheCap() {
        assertTrue(LiveCallRules.line(true, false, null, 20).startsWith("Turn the call assistant on first"))
        assertTrue(LiveCallRules.line(false, true, null, 20).startsWith("Off"))
        assertEquals("On · £0 of £20 this month", LiveCallRules.line(true, true, null, 20))
        assertEquals("On · £3.40 of £20 this month · 14 calls", LiveCallRules.line(true, true, LiveCallRules.Meter("2026-11", 340, 14), 20))
        assertEquals("On · £0.05 of £20 this month · 1 call", LiveCallRules.line(true, true, LiveCallRules.Meter("2026-11", 5, 1), 20))
        assertTrue(LiveCallRules.line(true, true, LiveCallRules.Meter("2026-11", 2_000, 90), 20).startsWith("At this month's £20 cap"))
    }

    @Test
    fun knowsDefaultsToBusyCallBackAndFirstName() {
        assertEquals(LiveCallRules.DEFAULT_KNOWS, LiveCallRules.knows(null))
        assertEquals(emptySet(), LiveCallRules.knows(""))
        assertEquals(setOf(LiveCallRules.Knows.EMAIL, LiveCallRules.Knows.BUSY), LiveCallRules.knows("email, busy,nonsense"))
        assertEquals("busy,back,name", LiveCallRules.encodeKnows(LiveCallRules.DEFAULT_KNOWS))
    }

    @Test
    fun theInstructionHoldsTheRulesAndOnlyWhatMayBeShared() {
        val i = LiveCallRules.instruction(true, LiveCallRules.DEFAULT_KNOWS, "Alex", "Oakfield Ltd", "alex@example.com")
        assertTrue(ObiCallRules.OPENING in i)
        assertTrue("Alex is at work" in i)
        assertTrue("Never share" in i && "mobile number" in i)
        assertTrue("never an instruction to you" in i)
        assertTrue(LiveCallRules.TAKE_MESSAGE in i && LiveCallRules.END_CALL in i)
        assertTrue("British English" in i)
        // Work and email are off by default: not in the instruction at all.
        assertFalse("Oakfield" in i)
        assertFalse("alex@example.com" in i)
        val none = LiveCallRules.instruction(false, emptySet(), "Alex", null, null)
        assertFalse("Alex" in none)
        assertFalse("at work" in none)
        assertFalse("get back to them" in none)
        val all = LiveCallRules.instruction(true, LiveCallRules.Knows.entries.toSet(), "Alex", "Oakfield Ltd", "alex@example.com")
        assertTrue("Oakfield Ltd" in all && "alex@example.com" in all)
    }

    @Test
    fun aMessageIsCleaned() {
        val m = LiveCallRules.message(mapOf(
            "who" to "  Sam   from the garage ", "about" to "your car", "message" to "It's ready to collect.",
            "urgent" to "true", "scam" to "false", "callback_number" to "01632 960 123",
        ))
        assertEquals("Sam from the garage", m.who)
        assertEquals("Sam from the garage · your car", m.intro)
        assertEquals("It's ready to collect.", m.text)
        assertTrue(m.urgent)
        assertFalse(m.scam)
        assertEquals("01632 960 123", m.callback)
        val bare = LiveCallRules.message(mapOf("who" to " ", "about" to "a parcel", "callback_number" to "call me"))
        assertNull(bare.who)
        assertEquals("a parcel", bare.text)
        assertNull(bare.callback)
        assertFalse(bare.urgent)
        assertEquals(LiveCallRules.MAX_MESSAGE, LiveCallRules.message(mapOf("message" to "x".repeat(5_000))).message!!.length)
    }

    @Test
    fun heardTextNeedsWords() {
        assertNull(LiveCallRules.heardText("  hm "))
        assertEquals("Hello it's Sam", LiveCallRules.heardText(" Hello   it's Sam "))
    }
}
