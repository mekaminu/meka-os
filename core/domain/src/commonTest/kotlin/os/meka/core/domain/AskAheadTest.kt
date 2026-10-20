package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The orb speed pass, slice 6: a quick look-up asked ahead with the words heard so far. */
class AskAheadTest {
    @Test
    fun plainLookUpsGoAhead() {
        assertTrue(AskAheadRules.worthIt("What's on tomorrow?"))
        assertTrue(AskAheadRules.worthIt("when's the bin day"))
        assertTrue(AskAheadRules.worthIt("is it going to rain this afternoon"))
        assertTrue(AskAheadRules.worthIt("are the trains OK tomorrow"))
        assertTrue(AskAheadRules.worthIt("how long until the dentist"))
        assertTrue(AskAheadRules.worthIt("any parcels coming today"))
    }

    @Test
    fun anythingThatActsWaitsForTheFinalWords() {
        assertFalse(AskAheadRules.worthIt("add milk to the shopping"))
        assertFalse(AskAheadRules.worthIt("can you remind me to call Sam"))
        assertFalse(AskAheadRules.worthIt("will you move the dentist to Friday"))
        assertFalse(AskAheadRules.worthIt("is it OK to pause Navilife"))
        assertFalse(AskAheadRules.worthIt("do I need to send the form"))
        assertFalse(AskAheadRules.worthIt("pause Navilife for an hour"))
    }

    @Test
    fun openTurnsMoneyAndShortWordsWait() {
        assertFalse(AskAheadRules.worthIt("what do you think about heat pumps"))
        assertFalse(AskAheadRules.worthIt("who is the best striker ever"))
        assertFalse(AskAheadRules.worthIt("how much did I spend on takeaways"))
        assertFalse(AskAheadRules.worthIt("what's on"))
        assertFalse(AskAheadRules.worthIt(""))
        // A follow-up to an open turn is open too.
        assertFalse(AskAheadRules.worthIt("why is that then", listOf("explain how a heat pump works")))
    }

    @Test
    fun theFinalWordsMatchWhateverTheirPunctuation() {
        assertTrue(AskAheadRules.same("whats on tomorrow", "What's on tomorrow?"))
        assertTrue(AskAheadRules.same("When's  the bin day", "when's the bin day."))
        assertFalse(AskAheadRules.same("what's on tomorrow", "what's on tomorrow evening"))
        assertFalse(AskAheadRules.same("", ""))
        assertEquals("whats on tomorrow", AskAheadRules.norm("What’s on, tomorrow?"))
    }

    @Test
    fun theFlowUsesTheEarlyQuestionOnlyWhenTheSame() {
        assertTrue(AskAheadRules.use("what's on tomorrow", "What's on tomorrow?"))
        assertFalse(AskAheadRules.use(null, "What's on tomorrow?"))
        assertFalse(AskAheadRules.use("what's on tomorrow", "what's on Thursday"))
    }
}
