package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Life admin hour, slice 1: one hour a week booked around the calendar, the admin jobs gathered into it. */
class LifeAdminTest {
    private val hour = 3_600_000L
    private val world = SyncWorld()
    private val cal = LocalCalendar.fixedOffset(hour)
    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)
    private val mon = d(10, 12)
    private val sat = d(10, 17)
    private fun at(day: Long, h: Int, min: Int = 0) = cal.toEpochMs(day, h * 60 + min)
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private val aFold = LifeAdmin(fold.replica, { world.clock.nowMs }, cal)
    private val aMac = LifeAdmin(mac.replica, { world.clock.nowMs }, cal)
    private var n = 0
    private val renewals = Renewals(fold.replica, { "R${n++}" }, { world.clock.nowMs }, cal)

    init { world.clock.nowMs = at(mon, 9) }

    private fun sync() { fold.sync(); mac.sync(); fold.sync() }

    private fun task(id: String, title: String, scheduled: Long? = null, lifecycle: Lifecycle = Lifecycle.ACTIVE, priority: Int = 0) =
        Task(id, title, null, lifecycle, null, scheduled, null, priority, null, null, createdAtMs = id.length.toLong(), completedAtMs = null, hasConflict = false)

    @Test
    fun adminTitlesAreRecognisedByTheirWords() {
        listOf("Call the GP", "Renew passport", "School trip form", "Reply to the council", "Car insurance", "fill in the HMRC thing", "Book dentist")
            .forEach { assertTrue(LifeAdminRules.isAdmin(it), it) }
        listOf("Gym", "Read a book", "Plan the garden", "Buy milk", "Formula 1 race", "").forEach { assertFalse(LifeAdminRules.isAdmin(it), it) }
    }

    @Test
    fun theHourIsBookedAroundTheWeekAndGathersTheJobs() {
        assertEquals(LifeAdminRules.OFF_LINE, LifeAdminRules.view(aFold.setting(), null, null, emptyList(), world.clock.nowMs).summary)
        assertFalse(aFold.set(8, 600))
        assertTrue(aFold.set(6, 10 * 60))
        assertFalse(aFold.set(6, 10 * 60))
        sync()
        val s = aMac.setting()!!
        assertTrue(s.on)

        // Saturday 10:00 is free: booked there. A party at 09:30–11:00 moves it to 11:15 (15 minutes either side).
        val free = LifeAdminRules.slot(s, mon, cal) { emptyList() }!!
        assertEquals(sat, free.day)
        assertEquals("Sat 17 Oct 10:00–11:00", free.whenLine)
        val busy = LifeAdminRules.slot(s, mon, cal) { day -> if (day == sat) listOf(DayPlanner.Slot(at(sat, 9, 15), at(sat, 11, 15))) else emptyList() }!!
        assertEquals("Sat 17 Oct 11:15–12:15", busy.whenLine)
        // Saturday full from 10:00: Sunday 10:00.
        val full = LifeAdminRules.slot(s, mon, cal) { day -> if (day == sat) listOf(DayPlanner.Slot(at(sat, 0), at(sat + 1, 0))) else emptyList() }!!
        assertEquals(sat + 1, full.day)
        val next = LifeAdminRules.slot(s, mon + 7, cal) { emptyList() }!!

        // The jobs: the radar's attention first, then admin tasks (not a set-time one outside the hour, not someday).
        renewals.add("Car insurance", ObligationKind.INSURANCE, mon + 5, RenewalRepeat.YEARLY)
        val jobs = LifeAdminRules.jobs(
            listOf(
                task("t1", "Call the GP"),
                task("t22", "Fill in the school trip form", priority = 1),
                task("t333", "Gym"),
                task("t4444", "Renew passport", lifecycle = Lifecycle.SOMEDAY),
                task("t55555", "Reply to the council", scheduled = at(mon, 14)),
                task("t666666", "Pay the window cleaner", scheduled = free.startMs + 15 * 60_000L),
            ),
            renewals.view().attention, mon, free, cal,
        )
        assertEquals(listOf("Car insurance", "Fill in the school trip form", "Call the GP", "Pay the window cleaner"), jobs.map { it.title })
        assertEquals("due Sat 17 Oct", jobs.first().line)

        val v = LifeAdminRules.view(s, free, next, jobs, world.clock.nowMs)
        assertEquals("Saturdays from 10:00 · this week Sat 17 Oct 10:00–11:00 · 4 jobs", v.summary)
        assertEquals(free, v.thisWeek)
        assertNull(LifeAdminRules.todayLine(v, mon, world.clock.nowMs, cal))
        assertEquals("Life admin hour at 10:00 · 4 jobs", LifeAdminRules.todayLine(v, sat, at(sat, 8), cal)?.text)
        assertEquals("Life admin hour now · 4 jobs", LifeAdminRules.todayLine(v, sat, at(sat, 10, 30), cal)?.text)

        // The heads-up at its start, gone once it's over.
        val notice = LifeAdminRules.notices(v, world.clock.nowMs).single()
        assertEquals(NoticeSource.LIFE_ADMIN, notice.source)
        assertEquals("Life admin hour · 4 jobs", notice.title)
        assertEquals("Car insurance · Fill in the school trip form · and 2 more", notice.text)
        assertEquals(free.startMs, notice.atMs)
        assertEquals(free.endMs, notice.expiresAtMs)
        assertTrue(LifeAdminRules.notices(v, free.endMs).isEmpty())

        // The planner keeps it clear on its day only.
        assertEquals(LifeAdminRules.TITLE, LifeAdminRules.block(s, free, sat)?.title)
        assertNull(LifeAdminRules.block(s, free, mon))
        val plan = DayPlanner.Plan(emptyList(), emptyList(), 0, emptyList(), meals = listOf(LifeAdminRules.block(s, free, sat)!!))
        assertEquals("◐ Your life admin hour is kept clear. Nothing is planned over it.", plan.keptLine)

        // Once it's over, next week's is next.
        val after = LifeAdminRules.view(s, free, next, jobs, free.endMs)
        assertNull(after.thisWeek)
        assertEquals("Saturdays from 10:00 · next Sat 24 Oct 10:00–11:00 · 4 jobs", after.summary)
    }

    @Test
    fun aWeekCanBeSkippedAndTheHourTurnedOff() {
        assertFalse(aFold.skipThisWeek(true)) // off
        assertTrue(aFold.set(6, 10 * 60))
        assertTrue(aFold.skipThisWeek(true))
        assertFalse(aFold.skipThisWeek(true))
        sync()
        val s = aMac.setting()!!
        val free = LifeAdminRules.slot(s, mon, cal) { emptyList() }!!
        val next = LifeAdminRules.slot(s, mon + 7, cal) { emptyList() }!!
        val v = LifeAdminRules.view(s, free, next, listOf(LifeAdminJob("task:a", LifeAdminJobKind.TASK, "Call the GP", null, "Call the GP")), world.clock.nowMs)
        assertTrue(v.skipped)
        assertEquals("Saturdays from 10:00 · skipped this week · next Sat 24 Oct 10:00–11:00 · 1 job", v.summary)
        assertNull(LifeAdminRules.kept(s, free))
        assertNull(LifeAdminRules.block(s, free, sat))
        assertTrue(LifeAdminRules.notices(v, world.clock.nowMs).isEmpty())
        assertNull(LifeAdminRules.todayLine(v, sat, at(sat, 8), cal))
        assertEquals("Skipped this week's life admin hour · Sat 17 Oct is free again", LifeAdminRules.skipLine(free, true))

        assertTrue(aMac.skipThisWeek(false))
        sync()
        assertFalse(LifeAdminRules.view(aFold.setting(), free, next, emptyList(), world.clock.nowMs).skipped)
        assertTrue(aFold.off())
        assertFalse(aFold.off())
        val off = LifeAdminRules.view(aFold.setting(), null, null, emptyList(), world.clock.nowMs)
        assertEquals(LifeAdminRules.OFF_LINE, off.summary)
        assertEquals(10 * 60, off.startMin)
        assertEquals(6, off.weekday)
    }

    @Test
    fun overdueAdminWaitsForTheHourAndTheHourClosesWithWhatWasTicked() {
        assertTrue(aFold.set(6, 10 * 60))
        val s = aFold.setting()!!
        val free = LifeAdminRules.slot(s, mon, cal) { emptyList() }!!
        val next = LifeAdminRules.slot(s, mon + 7, cal) { emptyList() }!!
        val late = task("t1", "Call the GP").copy(dueAtMs = at(mon - 2, 9))
        val jobs = LifeAdminRules.jobs(listOf(late, task("t22", "Renew passport")), emptyList(), mon, free, cal)
        assertEquals("overdue since Sat 10 Oct", jobs.first().line)
        assertTrue(jobs.first().overdue)

        // Held while an hour is to come; the heads-up counts them.
        val v = LifeAdminRules.view(s, free, next, jobs, world.clock.nowMs)
        assertEquals(setOf("t1", "t22"), v.heldTaskIds)
        assertTrue(LifeAdminRules.holdsOverdue(v, "t1"))
        assertFalse(LifeAdminRules.holdsOverdue(v, "other"))
        assertEquals("Life admin hour · 2 jobs · 1 overdue", LifeAdminRules.notices(v, world.clock.nowMs).single().title)
        // Through the governor's sources: the overdue digest line isn't there while held, and is once the hour is off.
        val today = Today(listOf(NeedsYouItem(late, NeedsYouReason.OVERDUE)), null, emptyList(), emptyList())
        fun overdue(admin: LifeAdminView?) = NoticeSources.collect(
            ListsView.EMPTY, FastingView.EMPTY, ShutdownView.EMPTY, today, world.clock.nowMs, cal, lifeAdmin = admin,
        ).filter { it.source == NoticeSource.OVERDUE }
        assertTrue(overdue(v).isEmpty())
        assertEquals(1, overdue(null).size)
        assertEquals(1, overdue(LifeAdminRules.view(null, null, null, emptyList(), world.clock.nowMs)).size)
        // No hour ahead (none free this week or next): nothing is held.
        assertTrue(LifeAdminRules.view(s, null, null, jobs, world.clock.nowMs).heldTaskIds.isEmpty())

        // The close: from the hour's end on its day. Two admin tasks ticked in it (one just after), one long after.
        fun done(id: String, title: String, atMs: Long) = task(id, title, lifecycle = Lifecycle.DONE).copy(completedAtMs = atMs)
        val ticked = listOf(
            done("a", "Call the GP", free.startMs + 10 * 60_000L),
            done("bb", "Pay the council", free.endMs + 20 * 60_000L),
            done("ccc", "Renew passport", free.endMs + 3 * hour),
            done("dddd", "Gym", free.startMs + 5 * 60_000L),
        )
        assertNull(LifeAdminRules.closeLine(s, free, ticked, 1, sat, free.endMs - 1))
        assertNull(LifeAdminRules.closeLine(s, free, ticked, 1, mon, free.endMs))
        val close = LifeAdminRules.closeLine(s, free, ticked, 1, sat, free.endMs + hour)!!
        assertEquals("✓ Life admin hour done · 2 jobs ticked · 1 left for next week", close.text)
        assertEquals("Life admin hour done: 2 jobs ticked, 1 left for next week.", close.spoken)
        assertEquals("Life admin hour over · 3 jobs wait for next week", LifeAdminRules.closeLine(s, free, emptyList(), 3, sat, free.endMs)?.text)
        assertNull(LifeAdminRules.closeLine(s, free, emptyList(), 0, sat, free.endMs))
        // Skipped: no close.
        assertTrue(aFold.skipThisWeek(true))
        assertNull(LifeAdminRules.closeLine(aFold.setting(), free, ticked, 1, sat, free.endMs + hour))
    }

    @Test
    fun anOverdueTaskComesFirstAfterTheRenewals() {
        assertTrue(aFold.set(6, 10 * 60))
        val free = LifeAdminRules.slot(aFold.setting()!!, mon, cal) { emptyList() }!!
        // The late form is the newest and the lowest priority; it still comes before the other tasks.
        val late = task("t55555", "Fill in the school form").copy(dueAtMs = at(mon - 2, 9))
        val jobs = LifeAdminRules.jobs(
            listOf(task("t1", "Call the GP", priority = 2), task("t22", "Renew passport"), late), emptyList(), mon, free, cal,
        )
        assertEquals(listOf("Fill in the school form", "Call the GP", "Renew passport"), jobs.map { it.title })
        assertEquals(listOf(true, false, false), jobs.map { it.overdue })
    }

    @Test
    fun askAndTalkKnowTheHourAndCanSkipThisWeeks() {
        assertTrue(aFold.set(6, 10 * 60))
        val s = aFold.setting()!!
        val free = LifeAdminRules.slot(s, mon, cal) { emptyList() }!!
        val next = LifeAdminRules.slot(s, mon + 7, cal) { emptyList() }!!
        val late = task("t1", "Call the GP").copy(dueAtMs = at(mon - 2, 12))
        val jobs = LifeAdminRules.jobs(
            listOf(late, task("t22", "Renew passport"), task("t333", "Pay the council"), task("t4444", "Post the forms"), task("t55555", "Book the MOT")),
            emptyList(), mon, free, cal,
        )
        val v = LifeAdminRules.view(s, free, next, jobs, world.clock.nowMs)

        // The line: when, and the jobs by name (four, then a count), overdue marked.
        assertEquals(
            "Life admin hour · Saturdays from 10:00 · this week Sat 17 Oct 10:00–11:00 · 5 jobs: Call the GP (overdue), Renew passport, Pay the council, Post the forms and 1 more",
            LifeAdminRules.askLine(v, world.clock.nowMs),
        )
        assertEquals(
            "Life admin hour · Saturdays from 10:00 · this week Sat 17 Oct 10:00–11:00 · on now · 5 jobs: Call the GP (overdue), Renew passport, Pay the council, Post the forms and 1 more",
            LifeAdminRules.askLine(v, free.startMs + 60_000L),
        )
        val empty = LifeAdminRules.view(s, free, next, emptyList(), world.clock.nowMs)
        assertEquals("Life admin hour · Saturdays from 10:00 · this week Sat 17 Oct 10:00–11:00 · nothing waiting", LifeAdminRules.askLine(empty, world.clock.nowMs))
        assertNull(LifeAdminRules.askLine(LifeAdminView.EMPTY, world.clock.nowMs))

        // The skip card: this week's hour, never one that's over or already skipped.
        val ctx = AskRules.context(Today(emptyList(), null, emptyList(), emptyList()), world.clock.nowMs, cal, lifeAdmin = LifeAdminRules.askLine(v, world.clock.nowMs))
        assertEquals(listOf(AskItemKind.LIFE_ADMIN), ctx.items.map { it.kind })
        assertFalse(ctx.untrusted)
        val card = AskRules.card(AskRawAction("skip_life_admin"), ctx, emptyMap(), world.clock.nowMs, cal, lifeAdmin = v)!!
        assertEquals(AskProposal.SkipLifeAdmin(mon, sat, "Sat 17 Oct 10:00–11:00"), card.proposal)
        assertEquals("Skip this week's life admin hour · Sat 17 Oct 10:00–11:00", card.line)
        assertEquals("Skip", card.button)
        assertEquals("Skipped this week's life admin hour · Sat 17 Oct is free again", AskRules.doneLine(card.proposal, mon))
        assertEquals("skip this week's life admin hour on Saturday 17 October", TalkRules.phrase(card.proposal, mon, past = false))
        assertEquals("skipped this week's life admin hour today", TalkRules.phrase(card.proposal, sat, past = true))
        assertNull(TrustRules.kindOf(card.proposal))
        assertNull(AskRules.card(AskRawAction("skip_life_admin"), ctx, emptyMap(), free.endMs, cal, lifeAdmin = v))
        assertNull(AskRules.card(AskRawAction("skip_life_admin"), ctx, emptyMap(), world.clock.nowMs, cal, lifeAdmin = v.copy(skipped = true)))
        assertNull(AskRules.card(AskRawAction("skip_life_admin"), ctx, emptyMap(), world.clock.nowMs, cal))

        // Only this week's: a card or Undo from last week does nothing.
        assertFalse(aFold.skipThisWeek(true, onlyWeek = mon - 7))
        assertTrue(aFold.skipThisWeek(true, onlyWeek = mon))
        assertFalse(aFold.skipThisWeek(false, onlyWeek = mon - 7))
        assertTrue(aFold.skipThisWeek(false, onlyWeek = mon))
    }
    /** Quality gate (Life admin's shots): the row's grey line names a renewal and keeps its date and last words whole. */
    @Test
    fun aJobsLineIsDrawnWithItsDateWhole() {
        val glue = LifeTileRules.GLUE[0]
        val renewal = LifeAdminJob("renewal:r", LifeAdminJobKind.RENEWAL, "Home insurance", "due Thu 12 Nov", "Home insurance, due Thu 12 Nov")
        val shown = renewal.lineShown!!
        assertEquals("Renewal · due Thu 12 Nov", DateGlue.plain(shown))
        assertTrue("Thu${glue}12${glue}Nov" in shown, shown)
        assertFalse(shown.startsWith("·"))
        val task = LifeAdminJob("task:t", LifeAdminJobKind.TASK, "Call the GP", null, "Call the GP")
        assertNull(task.lineShown)
        val due = LifeAdminJob("task:u", LifeAdminJobKind.TASK, "Fill in the form", "overdue since Mon 12 Oct", "x", overdue = true)
        assertEquals("overdue since Mon 12 Oct", DateGlue.plain(due.lineShown!!))
    }
}
