package os.meka.core.domain

import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Date night, slice 1: the fortnightly evening, kept clear by the planner, skippable, and Today's line on the day. */
class DateNightTest {
    private val hour = 3_600_000L
    private val world = SyncWorld()
    private val cal = LocalCalendar.fixedOffset(hour)
    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)
    private val sat = d(10, 10)
    private val fri16 = d(10, 16)
    private fun at(day: Long, h: Int, min: Int = 0) = cal.toEpochMs(day, h * 60 + min)
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private fun nights(dev: Device) = DateNight(dev.replica, { world.clock.nowMs }, cal)
    private val nFold = nights(fold)
    private val nMac = nights(mac)

    init { world.clock.nowMs = at(sat, 10) }

    private fun sync() { fold.sync(); mac.sync(); fold.sync() }

    @Test
    fun everyOtherFridayIsChosenOnceAndItsFortnightCanMove() {
        assertEquals(DateNightRules.OFF_LINE, DateNightRules.view(nFold.setting(), sat).summary)
        assertEquals(fri16, DateNightRules.firstNight(5, sat))
        assertEquals(fri16 + 7, DateNightRules.firstNight(5, sat, nextWeek = true))
        assertFalse(nFold.set(5, 19 * 60, sat)) // not a Friday
        assertFalse(nFold.set(5, 19 * 60, fri16 - 7)) // gone by
        assertTrue(nFold.set(5, 19 * 60, fri16))
        assertFalse(nFold.set(5, 19 * 60, fri16 + 14)) // the same nights

        val v = DateNightRules.view(nFold.setting(), sat)
        assertTrue(v.on)
        assertEquals("Every other Friday from 19:00 · next Fri 16 Oct", v.summary)
        assertEquals(listOf(fri16, fri16 + 14, fri16 + 28, fri16 + 42), v.nights.map { it.day })
        assertEquals("19:00 · kept clear", v.nights.first().line)
        assertEquals(listOf("Fri 16 Oct" to true, "Fri 23 Oct" to false), v.starts.map { it.label to it.chosen })

        // The other fortnight, and a later start, from the Mac.
        sync()
        assertTrue(nMac.set(5, 19 * 60 + 30, fri16 + 7))
        sync()
        assertEquals("Every other Friday from 19:30 · next Fri 23 Oct", DateNightRules.view(nFold.setting(), sat).summary)
        assertFalse(DateNightRules.kept(nFold.setting(), fri16))
        assertTrue(DateNightRules.kept(nFold.setting(), fri16 + 21))

        // On the day it says tonight; turned off, the evenings are free and the choice is remembered for the chips.
        assertEquals("Every other Friday from 19:30 · tonight", DateNightRules.view(nFold.setting(), fri16 + 7).summary)
        assertTrue(nFold.off())
        assertFalse(nFold.off())
        val off = DateNightRules.view(nFold.setting(), sat)
        assertEquals(DateNightRules.OFF_LINE, off.summary)
        assertEquals(5, off.weekday)
        assertEquals(19 * 60 + 30, off.startMin)
        assertNull(DateNightRules.slot(nFold.setting(), fri16 + 7, cal))
    }

    @Test
    fun aSkippedNightIsFreeToPlanOnBothDevicesAndCanBeKeptAgain() {
        nFold.set(5, 19 * 60, fri16)
        sync()
        assertTrue(nMac.skip(fri16, true))
        assertFalse(nMac.skip(fri16, true))
        assertFalse(nMac.skip(fri16 + 7, true)) // not a date night
        assertEquals("Skipped Fri 16 Oct · the evening is free to plan", DateNightRules.skipLine(fri16, sat, true))
        sync()
        val v = DateNightRules.view(nFold.setting(), sat)
        assertEquals("Every other Friday from 19:00 · next Fri 30 Oct", v.summary)
        assertTrue(v.nights.first().skipped)
        assertEquals("Skipped · the evening is free to plan", v.nights.first().line)
        assertNull(DateNightRules.slot(nFold.setting(), fri16, cal))
        assertNull(DateNightRules.todayLine(nFold.setting(), fri16, 13 * 60))

        assertTrue(nFold.skip(fri16, false))
        assertEquals("Fri 16 Oct is kept clear again", DateNightRules.skipLine(fri16, sat, false))
        assertEquals(DayPlanner.Slot(at(fri16, 19), at(fri16, 23)), DateNightRules.slot(nFold.setting(), fri16, cal))
    }

    @Test
    fun theEveningIsKeptClearInThePlannerAndTodaySaysSo() {
        nFold.set(5, 21 * 60, fri16)
        // A late start is kept until midnight, not past it.
        assertEquals(DayPlanner.Slot(at(fri16, 21), at(fri16 + 1, 0)), DateNightRules.slot(nFold.setting(), fri16, cal))
        nFold.set(5, 19 * 60, fri16)
        val s = nFold.setting()

        val now = at(fri16, 17)
        val day = DayWindow(at(fri16, 0), at(fri16 + 1, 0), hour)
        fun task(id: String, minutes: Int) = Task(id, id, null, Lifecycle.ACTIVE, null, null, minutes, 0, null, null, 0, null, false)
        val plan = DayPlanner.plan(
            listOf(task("Short", 60), task("Long", 180)), emptyList(), now, day,
            meals = listOfNotNull(DateNightRules.block(s, fri16, cal)),
        )
        assertEquals(listOf("Short"), plan.placements.map { it.task.title })
        assertEquals(at(fri16, 17), plan.placements.single().startMs)
        assertEquals(listOf("Long"), plan.unplaced.map { it.title })
        assertEquals(listOf(DayPlanner.MealBlock("Date night", at(fri16, 19), at(fri16, 23))), plan.meals)
        assertEquals("◐ Date night is kept clear. Nothing is planned over it.", plan.keptLine)
        assertEquals(
            "◐ Kept free for your fast's meals and date night. Nothing is planned over them.",
            plan.copy(meals = plan.meals + DayPlanner.MealBlock("Break your fast", 0, 1)).keptLine,
        )
        assertNull(plan.copy(meals = emptyList()).keptLine)

        assertNull(DateNightRules.todayLine(s, fri16, 11 * 60 + 59))
        assertEquals("Date night tonight from 19:00", DateNightRules.todayLine(s, fri16, 12 * 60)?.text)
        assertEquals("Date night tonight", DateNightRules.todayLine(s, fri16, 20 * 60)?.text)
        assertNull(DateNightRules.todayLine(s, fri16, 23 * 60))
        assertNull(DateNightRules.todayLine(s, fri16 + 7, 13 * 60))
    }

    /** Slice 2: the nudge a week before — a Needs you card and a 09:00 heads-up — gone once booked (on either device) or skipped. */
    @Test
    fun theWeekBeforeNudgeAsksToBookUntilBookedOrSkipped() {
        nFold.set(5, 19 * 60, fri16 + 7) // Fri 23 Oct, 13 days off
        assertNull(DateNightRules.nudge(nFold.setting(), sat, 10 * 60))
        assertTrue(DateNightRules.notices(nFold.setting(), at(sat, 10), cal).isEmpty())

        // From Fri 16 Oct, a week before: the card, and the heads-up due at 09:00 that day until the evening starts.
        val n = DateNightRules.nudge(nFold.setting(), fri16, 8 * 60)!!
        assertEquals(fri16 + 7, n.day)
        assertEquals("In 7 days · from 19:00", n.line)
        assertEquals("Date night · Fri 23 Oct", n.title)
        assertEquals("Book somewhere and arrange cover", n.question)
        assertEquals("Date night on Fri 23 Oct from 19:00. Book somewhere and arrange cover.", n.spoken)
        val notice = DateNightRules.notices(nFold.setting(), at(fri16, 8), cal).single()
        assertEquals(NoticeSource.DATE_NIGHT, notice.source)
        assertEquals(NoticeTarget.NEEDS_YOU, notice.target)
        assertEquals(at(fri16, 9), notice.atMs)
        assertEquals(at(fri16 + 7, 19), notice.expiresAtMs)
        assertEquals("Tomorrow · from 19:00", DateNightRules.nudge(nFold.setting(), fri16 + 6, 12 * 60)!!.line)
        assertEquals("Tonight · from 19:00", DateNightRules.nudge(nFold.setting(), fri16 + 7, 18 * 60)!!.line)
        assertNull(DateNightRules.nudge(nFold.setting(), fri16 + 7, 19 * 60)) // the evening has started
        assertEquals(n, DateNightRules.view(nFold.setting(), fri16, 8 * 60).nudge)

        // Booked on the Mac: gone from both apps, the night's row says so; taking it back brings the card back.
        world.clock.nowMs = at(fri16, 10)
        sync()
        assertTrue(nMac.book(fri16 + 7, true))
        assertFalse(nMac.book(fri16 + 7, true))
        assertFalse(nMac.book(fri16 + 8, true)) // not a date night
        sync()
        assertNull(DateNightRules.nudge(nFold.setting(), fri16, 10 * 60))
        assertTrue(DateNightRules.notices(nFold.setting(), at(fri16, 10), cal).isEmpty())
        assertEquals("19:00 · kept clear · booked", DateNightRules.view(nFold.setting(), fri16).nights.first().line)
        assertEquals("Date night Fri 23 Oct · booked", DateNightRules.bookedLine(fri16 + 7, fri16, true))
        assertEquals("Date night tomorrow · booked", DateNightRules.bookedLine(fri16 + 7, fri16 + 6, true))
        assertTrue(nFold.book(fri16 + 7, false))
        assertEquals(fri16 + 7, DateNightRules.nudge(nFold.setting(), fri16, 10 * 60)?.day)

        // Skipped: no card, no heads-up; the fortnight after is asked about a week before it.
        assertTrue(nFold.skip(fri16 + 7, true))
        assertNull(DateNightRules.nudge(nFold.setting(), fri16, 10 * 60))
        assertEquals(fri16 + 21, DateNightRules.nudge(nFold.setting(), fri16 + 14, 10 * 60)?.day)

        // A new rhythm starts with nothing booked.
        nFold.book(fri16 + 21, true)
        assertTrue(nFold.set(5, 19 * 60, fri16 + 14))
        assertTrue(nFold.setting()!!.booked.isEmpty())
    }

    // ---- Slice 3: Ask and Talk know the nights ----

    private fun skipCard(date: String?, now: Long = world.clock.nowMs) =
        AskRules.card(AskRawAction("skip_date_night", date = date), AskContext.EMPTY, emptyMap(), now, cal, dateNight = nFold.setting())

    @Test
    fun askHearsTheComingNightsAndSkipIsACardForAKeptOne() {
        assertNull(DateNightRules.askLine(nFold.setting(), sat, 10 * 60))
        assertNull(skipCard(null))
        assertTrue(nFold.set(5, 19 * 60, fri16))
        assertTrue(nFold.book(fri16, true))
        assertTrue(nFold.skip(fri16 + 14, true))
        val line = DateNightRules.askLine(nFold.setting(), sat, 10 * 60)!!
        assertEquals("Date night · every other Friday from 19:00 · coming: Fri 16 Oct (booked), Fri 30 Oct (skipped), Fri 13 Nov, Fri 27 Nov", line)
        // On the night it says tonight, and once the evening is over it moves on.
        assertTrue(DateNightRules.askLine(nFold.setting(), fri16, 12 * 60)!!.endsWith("coming: Fri 16 Oct (tonight, booked), Fri 30 Oct (skipped), Fri 13 Nov, Fri 27 Nov"))
        assertTrue(DateNightRules.askLine(nFold.setting(), fri16, 23 * 60 + 30)!!.endsWith("coming: Fri 30 Oct (skipped), Fri 13 Nov, Fri 27 Nov, Fri 11 Dec"))

        // It goes with a question as one date_night line, never untrusted.
        val today = TodayProjection.project(emptyList(), world.clock.nowMs, DayWindow(at(sat, 0), at(sat + 1, 0)))
        val ctx = AskRules.context(today, world.clock.nowMs, cal, dateNight = line)
        assertEquals(listOf(line), ctx.items.filter { it.kind == AskItemKind.DATE_NIGHT }.map { it.line })
        assertFalse(ctx.untrusted)

        // A kept night by its date; no date means the next kept one.
        val fri = skipCard("2026-10-16")!!
        assertEquals(AskProposal.SkipDateNight(fri16), fri.proposal)
        assertEquals("Skip date night · Fri 16 Oct", fri.line)
        assertEquals("Skip", fri.button)
        assertEquals(fri.proposal, skipCard(null)!!.proposal)
        assertEquals(AskProposal.SkipDateNight(fri16 + 28), skipCard("2026-11-13")!!.proposal)
        assertEquals("Skipped Fri 16 Oct · the evening is free to plan", AskRules.doneLine(fri.proposal, sat))
        // Not a date night, already skipped, gone, or a bad date: no card.
        assertNull(skipCard("2026-10-23"))
        assertNull(skipCard("2026-10-30"))
        assertNull(skipCard("2026-10-09"))
        assertNull(skipCard("next Friday"))
        // On the night, tonight's card until the evening is over; then the next kept one.
        assertEquals("Skip date night · tonight", skipCard(null, at(fri16, 18))!!.line)
        assertEquals(AskProposal.SkipDateNight(fri16 + 28), skipCard(null, at(fri16, 23, 30))!!.proposal)
        assertTrue("skip_date_night" in AskRules.KINDS)
        // Talk says it in words.
        assertEquals("skip date night on Friday 16 October", TalkRules.phrase(fri.proposal, sat, past = false))
        assertEquals("skipped date night tonight", TalkRules.phrase(fri.proposal, fri16, past = true))
    }
}
