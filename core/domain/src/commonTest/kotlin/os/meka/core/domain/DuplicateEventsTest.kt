package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fold review 2026-10-09, item 5: the same event on several calendars is one row with the calendars listed. */
class DuplicateEventsTest {
    private val hour = 3_600_000L
    private val min = 60_000L
    // Tue 6 Oct 2026 in London (BST, +1 h).
    private val oct6Utc = 1_791_244_800_000L
    private val start = oct6Utc - hour
    private val day = DayWindow(startMs = start, endMs = start + 24 * hour, utcOffsetMs = hour)
    private fun at(h: Int, m: Int = 0) = start + h * hour + m * min

    private fun ev(
        id: String, title: String, from: Long = at(18), to: Long = at(19), calendar: String? = "Personal",
        location: String? = null, allDay: Boolean = false, account: String? = "meka@gmail.com",
    ) = CalendarEvent(id, title, from, to, allDay, location, "google", account, calendar)

    private val meka = ev("a", "Training", calendar = "Personal")
    private val kids = ev("b", "Training", calendar = "Kids")
    private val club = ev("c", "Training - 3G", calendar = "Club", location = "SG18")

    @Test
    fun theFoldReviewsTrainingIsOneRowWithItsCalendars() {
        val merged = DuplicateEvents.merge(listOf(meka, kids, club))
        assertEquals(1, merged.size)
        val row = merged.single()
        // The fullest title is the row; the others ride along.
        assertEquals("c", row.id)
        assertEquals("Training - 3G", row.title)
        assertEquals(listOf("a", "b"), row.alsoOn.map { it.id })
        assertEquals(listOf("Club", "Personal", "Kids"), row.calendarLabels)
        assertEquals("Personal · Kids", row.alsoOnLine)
    }

    @Test
    fun differentTimesOrTitlesStaySeparate() {
        val later = ev("d", "Training", from = at(19), to = at(20))
        val longer = ev("e", "Training", to = at(19, 30))
        val other = ev("f", "Dinner")
        val merged = DuplicateEvents.merge(listOf(meka, later, longer, other))
        assertEquals(listOf("a", "d", "e", "f"), merged.map { it.id })
        assertTrue(merged.all { it.alsoOn.isEmpty() })
        assertNull(merged.first().alsoOnLine)
        // A timed event and an all-day one never merge.
        val allDay = ev("g", "Training", from = at(18), to = at(19), allDay = true)
        assertEquals(2, DuplicateEvents.merge(listOf(meka, allDay)).size)
    }

    @Test
    fun titlesMatchAsWholeWordsIgnoringCaseAndPunctuation() {
        assertTrue(DuplicateEvents.titlesMatch("Training", "training"))
        assertTrue(DuplicateEvents.titlesMatch("Training", "Training - 3G"))
        assertTrue(DuplicateEvents.titlesMatch("Ada's parents' evening", "ADAS PARENTS EVENING"))
        assertTrue(DuplicateEvents.titlesMatch("Swim", "Kids: swim (Leisure centre)"))
        assertFalse(DuplicateEvents.titlesMatch("Train", "Training"))
        assertFalse(DuplicateEvents.titlesMatch("Training - 3G", "Training - Gym"))
        assertFalse(DuplicateEvents.titlesMatch("Call", "Standup"))
    }

    @Test
    fun twoSpecificTitlesUnderOneShortOneStayApart() {
        // "Training" matches both, but "Training - 3G" and "Training - Gym" don't match each other.
        val gym = ev("d", "Training - Gym", calendar = "Gym")
        val merged = DuplicateEvents.merge(listOf(club, gym, meka))
        assertEquals(2, merged.size)
        assertEquals(listOf("a"), merged.first { it.id == "c" }.alsoOn.map { it.id })
        assertTrue(merged.first { it.id == "d" }.alsoOn.isEmpty())
    }

    @Test
    fun theRowTakesThePlaceOfTheFirstAndEveryDevicePicksTheSameOne() {
        val dinner = ev("z", "Dinner", from = at(17), to = at(18))
        val a = DuplicateEvents.merge(listOf(kids, dinner, meka))
        val b = DuplicateEvents.merge(listOf(meka, dinner, kids))
        // Same title: the lowest id wins, wherever it was in the list.
        assertEquals(listOf("a", "z"), a.map { it.id })
        assertEquals(listOf("a", "z"), b.map { it.id })
        // A place wins over none when the titles are as full.
        val withPlace = ev("y", "Training", calendar = "Kids", location = "Pitch 2")
        assertEquals("y", DuplicateEvents.merge(listOf(meka, withPlace)).single().id)
    }

    @Test
    fun eventsBeingEditedInMekaAreNeverMerged() {
        val provisional = ev(PendingEditRules.PROVISIONAL_PREFIX + "1", "Training", calendar = "Kids")
        val moving = kids.copy(pendingEditId = "edit1")
        assertEquals(2, DuplicateEvents.merge(listOf(meka, provisional)).size)
        assertEquals(2, DuplicateEvents.merge(listOf(meka, moving)).size)
    }

    @Test
    fun hidingAnyOfThemHidesTheRowAndShowingBringsAllBack() {
        val marks = EventMarks(hidden = setOf("b"), prepTasks = emptyMap())
        assertTrue(marks.visible(listOf(meka, kids, club)).isEmpty())
        assertEquals(listOf("c"), EventMarks.NONE.visible(listOf(meka, kids, club)).map { it.id })
        assertEquals(listOf("c", "a", "b"), DuplicateEvents.idsWith("b", listOf(meka, kids, club)))
        assertEquals(listOf("x"), DuplicateEvents.idsWith("x", listOf(meka, kids, club)))
    }

    @Test
    fun aHiddenCalendarsCopyDoesntCountAndTheOthersStillMerge() {
        val marks = EventMarks(hidden = emptySet(), prepTasks = emptyMap(), hiddenCalendars = mapOf(CalendarRules.key(club) to "Club"))
        val shown = marks.visible(listOf(meka, kids, club))
        assertEquals(listOf("a"), shown.map { it.id })
        assertEquals("Kids", shown.single().alsoOnLine)
    }

    @Test
    fun todaysTimelineShowsOneRowWithTheCalendarsListed() {
        val events = EventMarks.NONE.visible(listOf(meka, kids, club))
        val tl = TodayProjection.project(emptyList(), at(10), day, events).timeline
        val rows = tl.rows.filter { it.kind == TimelineKind.EVENT }
        assertEquals(listOf("e-c"), rows.map { it.id })
        assertEquals("Training - 3G", rows.single().title)
        assertEquals("SG18 · Club · Personal · Kids", rows.single().detail)
        // A lone event keeps its quiet line.
        assertEquals("Camp Nou · Outlook", TimelineRules.eventDetail(meka.copy(location = "Camp Nou", provider = "microsoft")))
    }

    @Test
    fun theAllDayGroupListsEveryCalendarOfAMergedEntry() {
        val d0 = oct6Utc
        val d1 = oct6Utc + 24 * hour
        val inset = ev("h", "INSET day", from = d0, to = d1, allDay = true, calendar = "School")
        val inset2 = ev("i", "Inset day", from = d0, to = d1, allDay = true, calendar = "Family")
        val events = EventMarks.NONE.visible(listOf(inset, inset2))
        val tl = TodayProjection.project(emptyList(), at(10), day, events).timeline
        assertEquals("All day", tl.allDayLabel)
        assertEquals(listOf("School · Family"), tl.allDayItems.map { it.line })
    }

    @Test
    fun theCalendarTabShowsOneRowAndTheHiddenListOne() {
        val cal = LocalCalendar.UTC
        val nowMs = oct6Utc + 10 * hour
        val e1 = ev("a", "Training", from = oct6Utc + 18 * hour, to = oct6Utc + 19 * hour)
        val e2 = ev("b", "Training", from = oct6Utc + 18 * hour, to = oct6Utc + 19 * hour, calendar = "Kids")
        val v = CalendarAgenda.build(emptyList(), listOf(e1, e2), nowMs, cal)
        val rows = v.sections.flatMap { it.rows }.filter { it.kind == TimelineKind.EVENT }
        assertEquals(listOf("e-a"), rows.map { it.id })
        assertEquals("Personal · Kids", rows.single().detail)
        val hiddenView = CalendarAgenda.build(emptyList(), listOf(e1, e2), nowMs, cal, hidden = setOf("b"))
        assertTrue(hiddenView.sections.flatMap { it.rows }.none { it.kind == TimelineKind.EVENT })
        assertEquals(listOf("a"), hiddenView.sections.flatMap { it.hidden }.map { it.id })
    }

    @Test
    fun theDetailPaneSaysWhereElseItIs() {
        val row = DuplicateEvents.merge(listOf(meka, kids)).single()
        val v = EventDetails.build(row, at(10), LocalCalendar.UTC)
        assertEquals("Personal · meka@gmail.com · also on Kids", v.calendarLine)
        val hidden = EventDetails.build(row, at(10), LocalCalendar.UTC, EventMarks(setOf("b"), emptyMap()))
        assertTrue(hidden.hidden)
    }
}
