package os.meka.core.domain

import os.meka.core.sync.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * School emails first, slice 4b: a date that can't be read for sure (the weekday and the date disagree, "next Friday")
 * stays a card to confirm, never a guess. Invented school (rule 13).
 */
class SchoolMailUnclearTest {
    /** Tue 24 Nov 2026 (Sat 28 Nov, Fri 4 Dec). */
    private val today = CivilDate.toEpochDay(2026, 11, 24)
    private val sat = today + 4

    private fun find(id: String, what: String, day: Long, unclear: Boolean = false) = MailFind(
        id = id, kind = SchoolMailRules.KIND_SCHOOL, account = "Personal", merchant = "Oakfield Primary", renewDay = day,
        pence = null, obligationKind = ObligationKind.SUBSCRIPTION, repeat = RenewalRepeat.NONE, receivedDay = today - 1,
        subject = "News", state = MailFindState.OPEN, decidedAtMs = 0L, what = what,
        endDay = day, schoolKind = SchoolKind.DAY.wire, unclearDate = unclear,
    )

    @Test
    fun aWeekdayThatIsNotTheDatesOrNextFridayIsUnclear() {
        assertTrue(SchoolBodyRules.unclear("The fair is on Friday 28 November", today))
        assertTrue(SchoolBodyRules.unclear("Fri, 28th Nov", today))
        assertTrue(SchoolBodyRules.unclear("Our class assembly is next Friday", today))
        assertFalse(SchoolBodyRules.unclear("The fair is on Saturday 28th November", today))
        assertFalse(SchoolBodyRules.unclear("Sat 28 Nov, 12pm-4pm", today))
        assertFalse(SchoolBodyRules.unclear("Saturday the 28th of November", today))
        assertFalse(SchoolBodyRules.unclear("next Friday, 4 December", today))
        assertFalse(SchoolBodyRules.unclear("next Friday 4th December", today))
        assertFalse(SchoolBodyRules.unclear("The fair is this Saturday", today))
        assertFalse(SchoolBodyRules.unclear("Next week we have our fair", today))
    }

    @Test
    fun theBodyMarksTheUnclearDateAndItsFieldSaysSo() {
        val body = """
            Dear Parents,
            Our Christmas fair is on Friday 28 November, 12pm-4pm in the school hall.
            The school disco is on Friday 11 December.
        """.trimIndent()
        val r = SchoolBodyRules.read("Dates for your diary", body, today)
        val fair = r.finds.first { it.find.startDay == sat }
        assertTrue(fair.unclear, r.finds.toString())
        assertEquals(FieldValue.Bool(true), SchoolBodyRules.findFields(fair)[MailFindFields.UNCLEAR_DATE])
        val panto = r.finds.first { it.find.startDay == CivilDate.toEpochDay(2026, 12, 11) }
        assertFalse(panto.unclear)
        assertNull(SchoolBodyRules.findFields(panto)[MailFindFields.UNCLEAR_DATE])
    }

    @Test
    fun anUnclearDateStaysACardToConfirmAndIsNotAddedOnItsOwn() {
        val unsure = find("a", "Christmas fair", sat, unclear = true)
        val sure = find("b", "Pantomime", today + 17)
        assertFalse(SchoolAutoRules.eligible(unsure, emptyList(), today))
        assertTrue(SchoolAutoRules.eligible(sure, emptyList(), today))
        assertEquals(listOf("b"), SchoolAutoRules.due(listOf(unsure, sure), emptyList(), today, 0).map { it.id })
        val row = SchoolMailRules.row(unsure, today)
        assertTrue(row.line.endsWith(" · " + SchoolBodyRules.CHECK_DATE), row.line)
        assertTrue(row.spoken.contains(SchoolBodyRules.CHECK_DATE))
        assertFalse(SchoolMailRules.row(sure, today).line.contains(SchoolBodyRules.CHECK_DATE))
        // Still showable, so Add (his confirmation) adds it.
        assertTrue(SchoolMailRules.showable(unsure, emptyList(), today))
    }
}
