package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** "The mic should just add it", slice 2: what the capture sheet's 🎙 heard comes to. */
class SpokenCaptureTest {
    // Saturday 17 October 2026, 10:00.
    private val today = CivilDate.toEpochDay(2026, 10, 17)
    private val now = 10 * 60

    @Test fun plainWordsAreATaskInHisWords() {
        assertEquals(AskProposal.AddTask("Buy milk", null, null), SpokenCaptureRules.proposal("Buy milk", today, now))
        assertEquals(AskProposal.AddTask("Ring the dentist", null, null), SpokenCaptureRules.proposal("ring the dentist", today, now))
    }

    @Test fun aDaySaidIsTheDueDay() {
        assertEquals(AskProposal.AddTask("Buy milk", today + 1, null), SpokenCaptureRules.proposal("Buy milk tomorrow", today, now))
        assertEquals(AskProposal.AddTask("Call the garage", today + 1, null), SpokenCaptureRules.proposal("tomorrow call the garage", today, now))
    }

    @Test fun anAddPhraseReadsAsAsksDoes() {
        assertEquals(AskProposal.AddTask("Call the garage", today + 1, null), SpokenCaptureRules.proposal("Add call the garage tomorrow", today, now))
        assertEquals(AskProposal.AddShopping(listOf("milk", "eggs")), SpokenCaptureRules.proposal("Add milk and eggs to the shopping", today, now))
    }

    @Test fun onlyDayWordsLeaveNothingToAdd() {
        assertNull(SpokenCaptureRules.proposal("Tomorrow", today, now))
    }

    @Test fun cleaning() {
        assertEquals("Buy milk", SpokenCaptureRules.clean("  buy   milk. "))
        assertNull(SpokenCaptureRules.clean("   "))
        assertNull(SpokenCaptureRules.clean(null))
        assertEquals(SpokenCaptureRules.MAX_CHARS, SpokenCaptureRules.clean("a".repeat(400))!!.length)
    }

    @Test fun whatAScreenReaderSays() {
        assertEquals("Added “Buy milk”. Undo or Edit for 4 seconds.", SpokenCaptureRules.spoken(SpokenCaptured("Added “Buy milk”", "Buy milk", activityId = "a")))
    }
}
