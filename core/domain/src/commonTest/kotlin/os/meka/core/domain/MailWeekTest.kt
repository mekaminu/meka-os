package os.meka.core.domain

import os.meka.core.sync.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Inbox autopilot, slice 9b: the week ahead reads the sorted mail. */
class MailWeekTest {
    // Sunday 11 October 2026, British Summer Time (UTC+1); the week shown is Mon 12 – Sun 18 Oct.
    private val offset = 3_600_000L
    private val cal = LocalCalendar.fixedOffset(offset)
    private val sun = CivilDate.toEpochDay(2026, 10, 11)
    private val mon = sun + 1
    private val week = (mon..mon + 6).toList()
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)
    private fun window(day: Long) = DayWindow(at(day, 0), at(day + 1, 0), offset)
    private val now = at(sun, 18)

    private fun item(
        id: String, receivedMs: Long, from: String, subject: String, preview: String? = null, extra: Map<String, FieldValue> = emptyMap(),
    ) = MailNeedsRules.read(id, MailNeedsRules.serverFields("Personal", from, "x@example.com", subject, preview, receivedMs, 1) + extra)!!

    private fun waiting(id: String, who: String, title: String, chaseDay: Long?) =
        WaitingItem(id, title, who, null, 0L, null, chaseDay, if (chaseDay != null && chaseDay <= sun) DueState.DUE else DueState.LATER, "", false)

    @Test
    fun aDayIsReadFromDatesWeekdaysAndTomorrow() {
        val fri = sun - 2 // Fri 9 Oct
        assertEquals(mon + 3, MailWeekRules.dayOf("Parents' evening Thu 15 Oct", fri))
        assertEquals(mon + 3, MailWeekRules.dayOf("Reminder: 15th October", fri))
        assertEquals(mon + 3, MailWeekRules.dayOf("Due October 15, 2026", fri))
        // A weekday name is the first such day on or after the email.
        assertEquals(mon + 4, MailWeekRules.dayOf("Trip on Friday", sun))
        assertEquals(fri, MailWeekRules.dayOf("Non-uniform day this Friday", fri))
        assertEquals(mon, MailWeekRules.dayOf("See you Monday", fri))
        assertEquals(mon, MailWeekRules.dayOf("Your delivery is coming tomorrow", sun))
        assertEquals(sun, MailWeekRules.dayOf("Tonight's training is off", sun))
        // An explicit date wins over a weekday name.
        assertEquals(mon + 3, MailWeekRules.dayOf("Friday's trip moved to 15 Oct", fri))
        // A December email about early January is next year's.
        val dec = CivilDate.toEpochDay(2026, 12, 20)
        assertEquals(CivilDate.toEpochDay(2027, 1, 4), MailWeekRules.dayOf("Back on 4 Jan", dec))
        // No day: a month without a number, "may" as a word, a bare number.
        assertNull(MailWeekRules.dayOf("Your October statement", fri))
        assertNull(MailWeekRules.dayOf("Invoice 15 attached", fri))
        assertNull(MailWeekRules.dayOf("Thanks for your order", fri))
    }

    @Test
    fun emailsNamingADayOfTheWeekShowOnThatDay() {
        val items = listOf(
            item("trip", at(sun, 9), "Oakfield School", "Trip on Friday"),
            item("pe", at(sun - 2, 9), "Oakfield School", "Kit reminder", "PE moves to Wednesday 14 October this week"),
            item("done", at(sun, 8), "Alex Green", "Dinner Saturday?", extra = MailNeedsRules.doneFields(at(sun, 10))),
            item("past", at(sun - 3, 9), "Sam Taylor", "Call on Friday"), // Fri 9 Oct: before the week
            item("none", at(sun, 7), "Sam Taylor", "Invoice for October"),
        )
        val lines = MailWeekRules.lines(items, emptyList(), week, now, cal)
        assertEquals(listOf("mw-trip" to mon + 4, "mw-pe" to mon + 2), lines.map { it.id to it.day })
        assertEquals("Oakfield School · “Trip on Friday”", lines[0].text)
        assertEquals("Email from Oakfield School: Trip on Friday", lines[0].spoken)
    }

    @Test
    fun chasesOfEmailsShowOnTheirDayAndALateOneOnTheFirst() {
        val items = listOf(
            item("a", at(sun - 5, 9), "Sam Taylor", "Invoice", extra = mapOf(MailItemFields.WAITING_ID to FieldValue.Text("w1")) + MailNeedsRules.doneFields(at(sun - 5, 10))),
            item("b", at(sun - 9, 9), "Acme Billing", "Refund", extra = mapOf(MailItemFields.WAITING_ID to FieldValue.Text("w2")) + MailNeedsRules.doneFields(at(sun - 9, 10))),
        )
        val w = listOf(waiting("w1", "Sam Taylor", "Invoice for October", mon + 2), waiting("w2", "", "Refund", sun - 1))
        val lines = MailWeekRules.lines(items, w, week, now, cal)
        assertEquals(listOf(mon to "Chase Acme Billing · Refund · was due Sat 10 Oct", mon + 2 to "Chase Sam Taylor · Invoice for October"),
            lines.map { it.day to it.text })
        assertEquals(2, MailWeekRules.chaseCount(items, w, week))
    }

    @Test
    fun theWeeksMailLineSaysWhoAndHowManyToChase() {
        val items = listOf(
            item("a", at(sun, 9), "Oakfield School", "Trip on Friday"),
            item("b", at(sun, 8), "Sam Taylor", "Invoice"),
            item("c", at(sun, 7), "Alex Green", "Hello"),
            item("w", at(sun - 5, 9), "Pat Lee", "Quote", extra = mapOf(MailItemFields.WAITING_ID to FieldValue.Text("w1")) + MailNeedsRules.doneFields(at(sun - 5, 10))),
        )
        val w = listOf(waiting("w1", "Pat Lee", "Quote", mon + 1))
        val line = MailWeekRules.line(items, w, week, now, "Next week")
        assertEquals("Mail · 3 emails need you, from Oakfield School, Sam Taylor and 1 more · 1 to chase next week", line)
        assertEquals("In your email, 3 emails need you, from Oakfield School, Sam Taylor and 1 more; 1 to chase next week.", MailWeekRules.spokenLine(line!!))
        assertEquals("3 emails need you · 1 to chase", MailWeekRules.noticeBit(items, w, week, now))
        assertEquals("Mail · 1 email needs you, from Sam Taylor", MailWeekRules.line(listOf(items[1]), emptyList(), week, now, "This week"))
        assertNull(MailWeekRules.line(emptyList(), emptyList(), week, now, "Next week"))
        assertNull(MailWeekRules.noticeBit(emptyList(), emptyList(), week, now))
    }

    @Test
    fun theWeekAheadCarriesTheMail() {
        val items = listOf(
            item("trip", at(sun, 9), "Oakfield School", "Trip on Friday"),
            item("b", at(sun, 8), "Sam Taylor", "Invoice"),
        )
        val inputs = WeekAheadInputs(
            events = emptyList(),
            work = WorkHours(WorkSchedule.DEFAULT, HolidayCalendar.NONE),
            holidays = HolidayCalendar.NONE,
            mail = items,
        )
        val v = WeekAheadRules.view(inputs, now, cal, ::window)
        val fri = v.days.first { it.epochDay == mon + 4 }
        val l = fri.lines.single()
        assertEquals(WeekAheadKind.MAIL, l.kind)
        assertEquals("Oakfield School · “Trip on Friday”", l.text)
        assertEquals("Mail · 2 emails need you, from Oakfield School and Sam Taylor", v.mail)
        assertTrue("In your email, 2 emails need you, from Oakfield School and Sam Taylor." in v.spoken, v.spoken)
        assertTrue("Email from Oakfield School: Trip on Friday" in v.spoken, v.spoken)
        // The heads-up says the counts only, never who or what.
        val n = WeekAheadRules.notices(v, at(sun, 9), cal).single()
        assertTrue(n.text.startsWith("${v.summary} · 2 emails need you"), n.text)
        assertTrue("Oakfield" !in n.text && "Trip" !in n.text, n.text)
        // No mail: as before.
        val quiet = WeekAheadRules.view(inputs.copy(mail = emptyList()), now, cal, ::window)
        assertNull(quiet.mail)
        assertEquals(quiet.summary, WeekAheadRules.notices(quiet, at(sun, 9), cal).single().text)
    }
}
