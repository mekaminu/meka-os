package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Edit your calendars, slice 2d-ii: natural typing in Add event's title ("Dentist Fri 3pm"). */
class EventTypingTest {
    private val hour = 3_600_000L
    /** Thursday 8 October 2026. */
    private val thu = CivilDate.toEpochDay(2026, 10, 8)
    private val fri = thu + 1
    private val bst = LocalCalendar.fixedOffset(hour)
    private val google = EditAccount("google", "meka@gmail.com")
    private val accounts = listOf(google)
    private val nowMinute = 10 * 60 + 7
    private val now = bst.toEpochMs(thu, nowMinute)

    private fun parse(text: String) = EventTypingRules.parse(text, thu, nowMinute)
    private fun form() = AddEventRules.start(thu, nowMinute, null, accounts, null)
    private fun typed(text: String) = text.indices.fold(form()) { f, i -> f.typeTitle(text.substring(0, i + 1)) }

    @Test
    fun dentistFriThreePmFillsTheDayAndTheStart() {
        val p = assertNotNull(parse("Dentist Fri 3pm"))
        assertEquals("Dentist", p.title)
        assertEquals("Fri 3pm", p.words)
        assertEquals(fri, p.day)
        assertEquals(15 * 60, p.minute)
        assertNull(p.lengthMin)

        val f = typed("Dentist Fri 3pm")
        assertEquals(fri, f.day)
        assertEquals(15 * 60, f.minute)
        assertEquals(60, f.lengthMin)
        assertEquals("Dentist Fri 3pm", f.title) // the field shows what was typed
        val v = AddEventRules.view(f, accounts, now, bst)
        assertEquals("Tomorrow · 15:00–16:00", v.summary)
        assertEquals("Saves as “Dentist”", v.typedLine)
        assertEquals("Keep “Fri 3pm” in the title", v.keepWordsLabel)
        assertTrue(v.canAdd)
        val d = AddEventRules.draft(f, bst)
        assertEquals("Dentist", d.title)
        assertEquals(bst.toEpochMs(fri, 15 * 60), d.startAtMs)
        assertEquals(bst.toEpochMs(fri, 16 * 60), d.endAtMs)
    }

    @Test
    fun timesInTheirUsualShapes() {
        assertEquals(15 * 60 + 30, parse("Call 3:30pm")?.minute)
        assertEquals(15 * 60 + 30, parse("Call 3.30pm")?.minute)
        assertEquals(15 * 60 + 30, parse("Call 3:30 p.m.")?.minute)
        assertEquals(15 * 60, parse("Call 15:00")?.minute)
        assertEquals(9 * 60 + 30, parse("Call 9:30")?.minute)
        assertEquals(15 * 60, parse("Call at 3pm")?.minute)
        assertEquals(15 * 60, parse("Call @3pm")?.minute)
        assertEquals(0, parse("Call 12am")?.minute)
        assertEquals(12 * 60, parse("Call 12pm")?.minute)
        assertEquals(12 * 60, parse("Lunch at noon")?.minute)
        assertEquals("Lunch", parse("Lunch midday")?.title)
        // Not times: a bare number, a dotted number without am/pm, out of range.
        assertNull(parse("Room 3"))
        assertNull(parse("Release v2.10"))
        assertNull(parse("Call 13pm"))
        assertNull(parse("Call 25:00"))
        assertNull(parse("Call 9:75"))
    }

    @Test
    fun rangesSetTheLength() {
        parse("Training 3-4pm")!!.let { assertEquals(15 * 60, it.minute); assertEquals(60, it.lengthMin); assertEquals("Training", it.title) }
        parse("Training 3:30-5pm")!!.let { assertEquals(15 * 60 + 30, it.minute); assertEquals(90, it.lengthMin) }
        parse("Workshop 11-1pm")!!.let { assertEquals(11 * 60, it.minute); assertEquals(120, it.lengthMin) }
        parse("Workshop 9-5pm")!!.let { assertEquals(9 * 60, it.minute); assertEquals(8 * 60, it.lengthMin) }
        parse("Standup 15:00–16:30")!!.let { assertEquals(15 * 60, it.minute); assertEquals(90, it.lengthMin) }
        parse("Party from 3 to 5pm")!!.let { assertEquals(15 * 60, it.minute); assertEquals(120, it.lengthMin); assertEquals("Party", it.title) }
        parse("Night out 10pm-1am")!!.let { assertEquals(22 * 60, it.minute); assertEquals(180, it.lengthMin) }
        // Two bare numbers say nothing.
        assertNull(parse("Score 3-4"))

        val f = typed("Training 3-4pm tomorrow")
        assertEquals(fri, f.day)
        assertEquals(15 * 60, f.minute)
        assertEquals(60, f.lengthMin)
        assertEquals("Tomorrow · 15:00–16:00", AddEventRules.view(f, accounts, now, bst).summary)
    }

    @Test
    fun lengthsNeedAUnit() {
        assertEquals(120, parse("Meeting 3pm for 2h")?.lengthMin)
        assertEquals(90, parse("Meeting for 90 min at 3pm")?.lengthMin)
        assertEquals(90, parse("Meeting 3pm for 1h30")?.lengthMin)
        assertEquals(60, parse("Meeting 3pm for an hour")?.lengthMin)
        assertEquals(30, parse("Chat tomorrow for half an hour")?.lengthMin)
        // "Dinner for 2" is a table for two.
        val p = parse("Dinner for 2 Sat 7pm")!!
        assertEquals("Dinner for 2", p.title)
        assertNull(p.lengthMin)
        assertEquals(19 * 60, p.minute)
    }

    @Test
    fun daysAndDates() {
        assertEquals(thu, parse("Gym today")?.day)
        assertEquals(fri, parse("Gym tomorrow")?.day)
        assertEquals(fri, parse("Gym tmrw")?.day)
        parse("Takeaway tonight")!!.let { assertEquals(thu, it.day); assertEquals(19 * 60, it.minute) }
        assertEquals(20 * 60, parse("Takeaway tonight 8pm")?.minute)
        // A weekday is the coming one, today included.
        assertEquals(thu, parse("Gym Thursday")?.day)
        assertEquals(thu + 4, parse("Dentist on Monday")?.day)
        assertEquals(thu + 5, parse("Dentist tues")?.day)
        // "next Fri" is Friday of next week; "this Fri" the coming one.
        assertEquals(fri + 7, parse("Dentist next Fri")?.day)
        assertEquals(fri, parse("Dentist this Fri")?.day)
        assertEquals(thu + 4, parse("Dentist next Mon")?.day)
        // Dates: day month, month day, with a weekday or "the", d/m (UK), with a year.
        val oct12 = CivilDate.toEpochDay(2026, 10, 12)
        assertEquals(oct12, parse("Holiday 12 Oct")?.day)
        assertEquals(oct12, parse("Holiday October 12th")?.day)
        assertEquals(oct12, parse("Holiday on the 12th of October")?.day)
        assertEquals(oct12, parse("Holiday Mon 12 Oct")?.day)
        assertEquals(oct12, parse("Holiday 12/10")?.day)
        assertEquals(CivilDate.toEpochDay(2027, 3, 1), parse("Holiday 1/3/2027")?.day)
        assertEquals(CivilDate.toEpochDay(2027, 3, 1), parse("Holiday 1/3/27")?.day)
        // A date already gone this year is next year's.
        assertEquals(CivilDate.toEpochDay(2027, 1, 5), parse("Holiday 5 Jan")?.day)
        assertEquals(CivilDate.toEpochDay(2027, 10, 1), parse("Holiday 1 Oct")?.day)
        // No such date, or a given year in the past: nothing read.
        assertNull(parse("Holiday 31 Feb"))
        assertNull(parse("Holiday 1/3/2025"))
    }

    @Test
    fun aTimeThatHasPassedMovesOn() {
        // 08:00 has passed at 10:07: with no day, tomorrow; on a plain weekday that is today, next week.
        val f = typed("Run 8am")
        assertEquals(fri, f.day)
        assertEquals(8 * 60, f.minute)
        assertEquals(thu + 7, parse("Run Thu 8am")?.day)
        assertEquals(thu, parse("Run Thu 6pm")?.day)
        // "today" is taken at its word (an event earlier today can still be added, as from the chips).
        val t = typed("Run today 8am")
        assertEquals(thu, t.day)
        assertEquals(8 * 60, t.minute)
    }

    @Test
    fun allDay() {
        parse("Holiday Fri all day")!!.let { assertTrue(it.allDay); assertEquals(fri, it.day); assertNull(it.minute) }
        val f = typed("Holiday 12 Oct all-day")
        assertTrue(f.isAllDay)
        assertEquals("Mon 12 Oct · all day", AddEventRules.view(f, accounts, now, bst).summary)
        assertEquals("Holiday", AddEventRules.draft(f, bst).title)
        // All day and a time say two things: nothing read.
        assertNull(parse("Holiday all day 3pm"))
        // A time typed after all day was chosen turns all day off.
        val g = form().withAllDay(true).typeTitle("Dentist 3pm")
        assertFalse(g.isAllDay)
        assertEquals(15 * 60, g.minute)
    }

    @Test
    fun onlyTheWordsAtTheEndCount() {
        assertNull(parse("Monday's team lunch"))
        assertNull(parse("Talk about Friday plans"))
        assertEquals("Lunch with Monday team", parse("Lunch with Monday team Fri 1pm")?.title)
        // The whole title is a time: nothing to save it as.
        assertNull(parse("Fri 3pm"))
        assertNull(parse("tomorrow"))
        // A trailing comma or dash goes with the words.
        assertEquals("Dentist", parse("Dentist, Fri 3pm")?.title)
        assertEquals("Dentist", parse("Dentist - Fri 3pm")?.title)
        // Lone lower-case sun/sat aren't days ("Walk in the sun"); capitalised or with "this"/"next" they are.
        assertNull(parse("Walk in the sun"))
        assertNull(parse("Lie-in sat"))
        assertEquals(thu + 3, parse("Walk Sun")?.day)
        assertEquals(thu + 2, parse("Walk this sat")?.day)
        // Each kind once: a second day stays in the title.
        assertEquals("Dentist Fri", parse("Dentist Fri tomorrow")?.title)
    }

    @Test
    fun choicesMadeAfterTypingStayUntilTheWordsChange() {
        var f = typed("Dentist Fri 3pm")
        f = f.stepTime(1) // 15:15
        // Editing earlier in the title keeps the same words: the choice stays.
        f = f.typeTitle("Dentists Fri 3pm")
        assertEquals(15 * 60 + 15, f.minute)
        assertEquals("Dentists", AddEventRules.draft(f, bst).title)
        // New words fill the fields afresh.
        f = f.typeTitle("Dentists Fri 4pm")
        assertEquals(16 * 60, f.minute)
        // Words that stop reading as a time leave the fields and the title whole.
        f = f.typeTitle("Dentists Fri 4")
        assertNull(f.typed)
        assertEquals(16 * 60, f.minute)
        assertEquals(fri, f.day)
        assertEquals("Dentists Fri 4", AddEventRules.draft(f, bst).title)
        assertNull(AddEventRules.view(f, accounts, now, bst).typedLine)
    }

    @Test
    fun keepInTheTitle() {
        var f = typed("Book club Fri 7pm")
        f = f.keepTypedWords()
        assertNull(f.typed)
        assertEquals("Book club Fri 7pm", AddEventRules.draft(f, bst).title)
        assertEquals(fri, f.day) // the fields keep what they were set to
        assertNull(AddEventRules.view(f, accounts, now, bst).typedLine)
        // Nothing more is read until the title is cleared.
        f = f.typeTitle("Book club Fri 8pm")
        assertNull(f.typed)
        assertEquals(19 * 60, f.minute)
        f = f.typeTitle("").typeTitle("Gym 6pm")
        assertEquals(18 * 60, f.minute)
        assertEquals("Gym", AddEventRules.draft(f, bst).title)
    }

    @Test
    fun editingAnEventReadsNothing() {
        // The edit form sets its title with withTitle, which never reads words.
        val f = form().withTitle("Dentist Fri 3pm")
        assertNull(f.typed)
        assertEquals("Dentist Fri 3pm", AddEventRules.draft(f, bst).title)
        assertEquals(thu, f.day)
        // And withTitle drops words read while adding.
        assertNull(typed("Dentist Fri 3pm").withTitle("Dentist").typed)
    }
}
