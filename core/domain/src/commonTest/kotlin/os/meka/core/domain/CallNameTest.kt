package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One name everywhere (Fix first, 2026-10-21): what everything calls him, and the hello asking once when it's empty. */
class CallNameTest {
    private val r = CallNameRules

    @Test
    fun todayAndTheOrbSayTheSameName() {
        // Today's greeting and the orb's hello both read Your name's first word.
        assertEquals("Good evening, Alex", r.todayGreeting(19, "Alex Smith"))
        val hello = HelloRules.greeting(HelloFacts(minuteOfDay = 19 * 60, name = "Alex Smith"))
        assertTrue(hello.opening.startsWith("Good evening, Alex."), hello.opening)
        // Empty: neither invents one.
        assertEquals("Good evening", r.todayGreeting(19, null))
        assertEquals("Good evening", r.todayGreeting(19, "  "))
        assertEquals("Good evening.", HelloRules.greeting(HelloFacts(minuteOfDay = 19 * 60)).opening)
        // Today's own hours: the small hours are "Good night".
        assertEquals("Good night, Alex", r.todayGreeting(2, "Alex"))
        assertEquals("Good morning, Alex", r.todayGreeting(5, "Alex"))
        assertEquals("Good afternoon, Alex", r.todayGreeting(17, "Alex"))
    }

    @Test
    fun theBriefUsesTheNameOrLeavesItOut() {
        assertTrue(BriefScriptRules.script(MorningBriefView.EMPTY, "Alex").startsWith("Good morning, Alex."))
        assertTrue(BriefScriptRules.script(MorningBriefView.EMPTY, "").startsWith("Good morning. "))
        assertTrue(BriefSpeech.script(MorningBriefView.EMPTY, "").startsWith("Good morning. "))
        assertEquals("Good morning.", r.opening("Good morning", null))
    }

    @Test
    fun anEmptyNameAsksOnce() {
        assertTrue(r.asks(null, askedBefore = false))
        assertFalse(r.asks(null, askedBefore = true))
        assertFalse(r.asks("Alex", askedBefore = false))
        val g = r.askGreeting(19 * 60)
        assertEquals("Good evening. ${CallNameRules.ASK}", g.spoken)
        assertTrue(g.points.isEmpty())
        assertTrue(r.askGreeting(19 * 60, welcome = true).opening.startsWith(WelcomeBackRules.OPENING))
        assertTrue(r.pending(1_000L, 1_000L + CallNameRules.PENDING_MS))
        assertFalse(r.pending(1_000L, 1_001L + CallNameRules.PENDING_MS))
        assertFalse(r.pending(null, 1_000L))
    }

    @Test
    fun theAnswerIsReadAsAName() {
        // Just after the ask: a bare name, or "it's …", "I'm …", "just …", capitalised.
        assertEquals("Alex", r.heard("alex", pending = true))
        assertEquals("Alex", r.heard("It's Alex.", pending = true))
        assertEquals("Alex", r.heard("I'm Alex", pending = true))
        assertEquals("Alex", r.heard("just Alex please", pending = true))
        assertEquals("Mary-Ann", r.heard("mary-ann", pending = true))
        assertEquals("O'Brien", r.heard("o’brien", pending = true))
        assertEquals("Sam Jones", r.heard("call me Sam Jones", pending = true))
        // Not names.
        assertNull(r.heard("no thanks", pending = true))
        assertNull(r.heard("what's on tomorrow", pending = true))
        assertNull(r.heard("I'm tired", pending = true))
        assertNull(r.heard("hello", pending = true))
        assertNull(r.heard("call me at 5", pending = true))
        assertNull(r.heard("remind me to buy milk tomorrow", pending = true))
        // Any time, only the explicit forms.
        assertEquals("Alex", r.heard("Call me Alex", pending = false))
        assertEquals("Alex", r.heard("my name is alex", pending = false))
        assertNull(r.heard("Alex", pending = false))
        assertNull(r.heard("I'm Alex", pending = false))
        assertNull(r.heard("call me back", pending = false))
        assertNull(r.heard("call me a taxi", pending = false))
        // Declines.
        assertTrue(r.declined("No thanks."))
        assertTrue(r.declined("not now"))
        assertFalse(r.declined("Alex"))
    }

    @Test
    fun theCardWaitsForHisTapAndSaysWhatItDid() {
        val card = r.card("Alex")
        assertEquals(AskProposal.SetName("Alex"), card.proposal)
        assertEquals("Call you “Alex” · kept in About you", card.line)
        assertEquals("Save", card.button)
        assertEquals(card, AskRules.cardOf(card.proposal, 0))
        assertEquals("I'll call you Alex · saved in About you", AskRules.doneLine(card.proposal, 0))
        assertTrue(r.answer("Alex").contains("Alex"))
        // Never earned trust: he always sees his name before it's saved.
        assertNull(TrustRules.kindOf(card.proposal))
    }
}
