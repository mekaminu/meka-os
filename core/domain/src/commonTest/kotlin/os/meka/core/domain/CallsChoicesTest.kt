package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walk-through week 5, A17: Obi on calls' cap and recordings are rows opening sheets; what callers may know is switch rows. */
class CallsChoicesTest {
    private val now = 1_792_599_300_000L

    private fun view(cap: Int = 20, keep: Int = 7, knows: Set<LiveCallRules.Knows> = setOf(LiveCallRules.Knows.BUSY)) =
        CallsSettingsRules.view(WorkModeState(
            atWork = false, source = WorkSource.SCHEDULE, schedule = WorkSchedule.DEFAULT, until = null, line = "Off work",
            callLiveCapGbp = cap, recordingDays = keep, callKnows = knows,
        ), emptyList(), now)

    @Test
    fun theRowsSayWhatIsChosen() {
        val v = view(cap = 30, keep = 0)
        assertEquals(NotifyChoiceRow("Monthly cap", "£30", "Monthly cap, £30. Opens the choices"), CallsChoiceRules.capRow(v))
        assertEquals("Don't keep", CallsChoiceRules.keepRow(v).value)
        assertEquals("7 days", CallsChoiceRules.keepRow(view()).value)
        assertEquals("Keep recordings, 7 days. Opens the choices", CallsChoiceRules.keepRow(view()).spoken)
    }

    @Test
    fun theSheetsOfferEveryChoiceWithTheOneInUseLit() {
        val cap = CallsChoiceRules.capSheet(view(cap = 50))
        assertEquals(listOf("£10", "£20", "£30", "£50"), cap.choices.map { it.label })
        assertEquals(listOf("£50"), cap.choices.filter { it.lit }.map { it.label })
        assertEquals(CallsSettingsRules.CAP_NOTE, cap.note)
        assertTrue(cap.choices.single { it.lit }.spoken.endsWith(", chosen"))
        assertEquals("£30, Live conversation for up to £30 a month", cap.choices[2].spoken)

        val keep = CallsChoiceRules.keepSheet(view(keep = 0))
        assertEquals(listOf("Don't keep", "7 days"), keep.choices.map { it.label })
        assertEquals(listOf("Don't keep"), keep.choices.filter { it.lit }.map { it.label })
        assertTrue((cap.choices + keep.choices).all { it.line.isNotBlank() })
    }

    @Test
    fun aChoiceReadsBackOnlyWhenOffered() {
        CallsChoiceRules.capSheet(view()).choices.forEach { assertEquals(it.name.toInt(), CallsChoiceRules.capNamed(it.name)) }
        CallsChoiceRules.keepSheet(view()).choices.forEach { assertEquals(it.name.toInt(), CallsChoiceRules.keepNamed(it.name)) }
        assertNull(CallsChoiceRules.capNamed("25"))
        assertNull(CallsChoiceRules.capNamed("lots"))
        assertNull(CallsChoiceRules.keepNamed("30"))
    }

    @Test
    fun everyThingCallersMayKnowHasALineAndReadsItsState() {
        val v = view()
        assertEquals(LiveCallRules.Knows.entries.size, v.knows.size)
        v.knows.forEach { assertTrue(CallsChoiceRules.knowsLine(it.key).isNotBlank(), it.key) }
        val busy = v.knows.first { it.key == LiveCallRules.Knows.BUSY.wire }
        assertEquals("You're busy, on. Obi may say you can't take the call right now", CallsChoiceRules.knowsSpoken(busy))
        val email = v.knows.first { it.key == LiveCallRules.Knows.EMAIL.wire }
        assertTrue(CallsChoiceRules.knowsSpoken(email).startsWith("Your work email, off."))
        assertEquals("", CallsChoiceRules.knowsLine("nope"))
    }
}
