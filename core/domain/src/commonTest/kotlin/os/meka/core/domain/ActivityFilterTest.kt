package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActivityFilterTest {
    private var n = 0
    private fun row(kind: ActivityKind) = ActivityRow("r${n++}", "09:00", kind, "Something", null, "Why: a rule", false, null)

    private fun view(vararg days: List<ActivityKind>) = ActivityView(
        "This week: 2 reminders",
        days.mapIndexed { i, kinds -> ActivityDay(100L - i, "Day $i", kinds.map(::row)) },
        ActivityRules.EMPTY_LINE,
    )

    @Test
    fun chipsListAllThenEachKindPresentInAFixedOrderWithCounts() {
        val v = view(
            listOf(ActivityKind.SCREENED, ActivityKind.REMINDED, ActivityKind.CALL),
            listOf(ActivityKind.DIGEST, ActivityKind.CHANGED),
        )
        val chips = ActivityRules.filters(v)
        assertEquals(
            listOf("All", "Reminders · 2", "Done for you · 1", "Calls · 2"),
            chips.map { it.label },
        )
        assertEquals(5, chips.first().count)
        assertEquals("Calls, 2", chips.last().spoken)
    }

    @Test
    fun noChipsWhenThereIsOnlyOneKindOrNothing() {
        assertTrue(ActivityRules.filters(view(listOf(ActivityKind.REMINDED, ActivityKind.DIGEST))).isEmpty())
        assertTrue(ActivityRules.filters(ActivityView.EMPTY).isEmpty())
    }

    @Test
    fun aFilterKeepsOnlyItsKindsAndDropsEmptyDays() {
        val v = view(
            listOf(ActivityKind.REMINDED, ActivityKind.CALENDAR),
            listOf(ActivityKind.REMINDED),
            listOf(ActivityKind.CALENDAR, ActivityKind.FAMILY),
        )
        val cal = ActivityRules.filtered(v, ActivityFilter.CALENDAR)
        assertEquals(listOf("Day 0", "Day 2"), cal.days.map { it.label })
        assertTrue(cal.days.all { d -> d.rows.all { it.kind == ActivityKind.CALENDAR } })
        assertEquals(v.weekLine, cal.weekLine)
        val other = ActivityRules.filtered(v, ActivityFilter.OTHER)
        assertEquals(listOf(ActivityKind.FAMILY), other.days.flatMap { d -> d.rows.map { it.kind } })
    }

    @Test
    fun aFilterWithNothingLeftFallsBackToAll() {
        val v = view(listOf(ActivityKind.REMINDED, ActivityKind.CALENDAR))
        assertEquals(ActivityFilter.ALL, ActivityRules.chosen(v, ActivityFilter.CALLS))
        assertEquals(v, ActivityRules.filtered(v, ActivityFilter.CALLS))
        assertEquals(ActivityFilter.CALENDAR, ActivityRules.chosen(v, ActivityFilter.CALENDAR))
        assertEquals(v, ActivityRules.filtered(v, ActivityFilter.ALL))
    }

    @Test
    fun everyKindBelongsToExactlyOneFilterBesidesAll() {
        for (k in ActivityKind.entries) {
            assertEquals(1, ActivityFilter.entries.count { it != ActivityFilter.ALL && k in it.kinds }, k.name)
        }
        assertEquals(ActivityFilter.CALLS, ActivityRules.filterNamed("CALLS"))
        assertEquals(ActivityFilter.ALL, ActivityRules.filterNamed("NOPE"))
        assertEquals(ActivityFilter.ALL, ActivityRules.filterNamed(null))
    }

    // ---- Walk-through week 5, A17: the filter is one Show row opening a sheet, never an open chip row ----

    @Test
    fun theShowRowSaysWhatIsShownAndOfHowMany() {
        val v = view(
            listOf(ActivityKind.SCREENED, ActivityKind.REMINDED, ActivityKind.CALL),
            listOf(ActivityKind.DIGEST, ActivityKind.CHANGED),
        )
        val all = ActivityRules.showRow(v, ActivityFilter.ALL)!!
        assertEquals("Show", all.title)
        assertEquals("All · 5 entries", all.value)
        assertEquals("Calls · 2 of 5", ActivityRules.showRow(v, ActivityFilter.CALLS)!!.value)
        assertEquals("Done for you · 1 of 5", ActivityRules.showRow(v, ActivityFilter.DONE_FOR_YOU)!!.value)
        // A filter the days no longer have reads as All, as the days do.
        assertEquals("All · 5 entries", ActivityRules.showRow(v, ActivityFilter.CALENDAR)!!.value)
        assertTrue(ActivityRules.showRow(v, ActivityFilter.CALLS)!!.spoken.startsWith("Show, Calls, 2 of 5"))
    }

    @Test
    fun theShowSheetHasOneChoicePerChipWithTheShownOneLit() {
        val v = view(listOf(ActivityKind.REMINDED, ActivityKind.CALL, ActivityKind.CALL))
        val sheet = ActivityRules.showSheet(v, ActivityFilter.CALLS)!!
        assertEquals(ActivityRules.filters(v).map { it.filter }, sheet.choices.map { it.filter })
        assertEquals(listOf("All", "Reminders", "Calls"), sheet.choices.map { it.label })
        assertEquals(listOf("3 entries", "1 entry", "2 entries"), sheet.choices.map { it.line })
        assertEquals(listOf(ActivityFilter.CALLS), sheet.choices.filter { it.lit }.map { it.filter })
        assertTrue(sheet.choices.single { it.lit }.spoken.endsWith(", shown"))
        assertTrue(sheet.note.isNotBlank())
    }

    @Test
    fun noShowRowOrSheetWhenThereIsNothingToChoose() {
        val one = view(listOf(ActivityKind.REMINDED, ActivityKind.DIGEST))
        assertEquals(null, ActivityRules.showRow(one, ActivityFilter.ALL))
        assertEquals(null, ActivityRules.showSheet(one, ActivityFilter.ALL))
        assertEquals(null, ActivityRules.showRow(ActivityView.EMPTY, ActivityFilter.ALL))
    }
}
