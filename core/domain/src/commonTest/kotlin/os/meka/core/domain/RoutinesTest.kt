package os.meka.core.domain

import os.meka.core.sync.fv
import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Routines, slice 1: the starters, the list with its day tiles and next runs, the switch, and the hard rule. */
class RoutinesTest {
    private val hour = 3_600_000L
    private val world = SyncWorld()
    private val cal = LocalCalendar.fixedOffset(0)
    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)
    /** Fri 6 Nov 2026. */
    private val fri = d(11, 6)
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)
    private var n = 0
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private fun routines(dev: Device) = Routines(dev.replica, { "rt${n++}" }, { world.clock.nowMs }, cal)

    init { world.clock.nowMs = at(fri, 20) }

    private fun sync() { fold.sync(); mac.sync(); fold.sync() }

    @Test
    fun theDayTileAndWhenLineSayWhenItRuns() {
        assertEquals("SUN", RoutineRules.dayTile(RoutineSchedule(setOf(7), 19 * 60)))
        assertEquals("MON–FRI", RoutineRules.dayTile(RoutineSchedule(setOf(1, 2, 3, 4, 5), 7 * 60)))
        assertEquals("DAILY", RoutineRules.dayTile(RoutineSchedule((1..7).toSet(), 21 * 60)))
        assertEquals("WEEKENDS", RoutineRules.dayTile(RoutineSchedule(setOf(6, 7), 9 * 60)))
        assertEquals("MON · THU", RoutineRules.dayTile(RoutineSchedule(setOf(4, 1), 9 * 60)))
        assertEquals("3 DAYS", RoutineRules.dayTile(RoutineSchedule(setOf(1, 3, 5), 9 * 60)))
        assertEquals("PAYDAY", RoutineRules.dayTile(RoutineSchedule(emptySet(), 9 * 60, payday = true)))
        assertEquals("Every Sunday at 19:00", RoutineRules.whenLine(RoutineSchedule(setOf(7), 19 * 60)))
        assertEquals("Weekdays at 07:00", RoutineRules.whenLine(RoutineSchedule(setOf(1, 2, 3, 4, 5), 7 * 60)))
        assertEquals("Every day at 21:00", RoutineRules.whenLine(RoutineSchedule((1..7).toSet(), 21 * 60)))
        assertEquals("On payday at 09:00", RoutineRules.whenLine(RoutineSchedule(emptySet(), 9 * 60, payday = true)))
        assertEquals("Mon, Wed, Fri at 09:30", RoutineRules.whenLine(RoutineSchedule(setOf(5, 1, 3), 9 * 60 + 30)))
    }

    @Test
    fun theNextRunIsTodayOnlyBeforeItsTime() {
        val sunday = RoutineSchedule(setOf(7), 19 * 60)
        assertEquals(d(11, 8), RoutineRules.nextDay(sunday, fri, 20 * 60))
        assertEquals("Next: Sun 8 Nov", RoutineRules.nextLine(sunday, fri, 20 * 60))
        val nightly = RoutineSchedule((1..7).toSet(), 21 * 60)
        assertEquals("Next: tonight", RoutineRules.nextLine(nightly, fri, 20 * 60))
        assertEquals("Next: tomorrow", RoutineRules.nextLine(nightly, fri, 21 * 60))
        val weekdays = RoutineSchedule(setOf(1, 2, 3, 4, 5), 7 * 60)
        assertEquals("Next: Mon 9 Nov", RoutineRules.nextLine(weekdays, fri, 20 * 60))
        assertEquals("Next: today", RoutineRules.nextLine(weekdays, fri, 6 * 60))
        // Sunday's run at 19:00, on Sunday at 19:00 itself, is next week's.
        assertEquals(d(11, 15), RoutineRules.nextDay(sunday, d(11, 8), 19 * 60))
        val payday = RoutineSchedule(emptySet(), 9 * 60, payday = true)
        assertEquals("Next: on payday", RoutineRules.nextLine(payday, fri, 20 * 60))
        assertEquals("Next: Fri 27 Nov", RoutineRules.nextLine(payday, fri, 20 * 60, payday = d(11, 27)))
    }

    @Test
    fun aStepThatWouldSendPayBookOrTradeIsRefusedAndNeverReadBack() {
        assertNull(RoutineRules.refusal("Tidy the inbox"))
        assertNull(RoutineRules.refusal("Show the week ahead of bills"))
        assertNull(RoutineRules.refusal("Plan next week"))
        assertNotNull(RoutineRules.refusal("Pay the gas bill"))
        assertNotNull(RoutineRules.refusal("Text Sam I'm running late"))
        assertNotNull(RoutineRules.refusal("Send the weekly email"))
        assertNotNull(RoutineRules.refusal("Book the dentist"))
        assertNotNull(RoutineRules.refusal("Trade the morning dip"))
        assertNotNull(RoutineRules.refusal("Transfer £50 to savings"))
        // Every starter keeps the rule.
        RoutineRules.STARTERS.flatMap { it.steps }.forEach { assertNull(RoutineRules.refusal(it.title), it.title) }
        // A stored step that would pay is dropped; "Obi does it" outside what Navilife does by itself only asks.
        val steps = RoutineRules.decodeSteps("show|MONEY|What came in\ndoes|MONEY|Pay the card\ndoes|HOME|Put the bins out\ndoes|INBOX|Tidy the inbox\nbad line")
        assertEquals(
            listOf(
                RoutineStep("What came in", RoutinePlace.MONEY, RoutineStepMode.SHOW),
                RoutineStep("Put the bins out", RoutinePlace.HOME, RoutineStepMode.ASK),
                RoutineStep("Tidy the inbox", RoutinePlace.INBOX, RoutineStepMode.DOES),
            ),
            steps,
        )
        // The steps survive the round trip.
        val sunday = RoutineRules.STARTERS.first().steps
        assertEquals(sunday, RoutineRules.decodeSteps(RoutineRules.encodeSteps(sunday)))
    }

    @Test
    fun startersAreOfferedUntilAddedThenTheListIsSoonestFirstAndSynced() {
        val r = routines(fold)
        val empty = r.view()
        assertTrue(empty.isEmpty)
        assertEquals(RoutineRules.EMPTY_LINE, empty.summary)
        assertEquals(listOf("Sunday reset", "Morning brief", "Study night", "Payday check"), empty.starters.map { it.name })
        assertEquals("Every Sunday at 19:00", empty.starters.first().whenLine)

        val sunday = r.addStarter("sunday-reset")!!
        assertFalse(sunday.refused)
        assertEquals("Added Sunday reset · Every Sunday at 19:00", sunday.line)
        assertTrue(r.addStarter("sunday-reset")!!.refused)
        assertNull(r.addStarter("no-such-starter"))
        r.addStarter("study-night")
        r.addStarter("payday-check")

        val v = r.view()
        assertEquals("3 on", v.summary)
        // Tonight's study first, then Sunday, then payday (no date known yet).
        assertEquals(listOf("Study night", "Sunday reset", "Payday check"), v.rows.map { it.name })
        val row = v.rows[1]
        assertEquals("SUN", row.day)
        assertEquals("19:00", row.time)
        assertEquals("Bills, plan the week, inbox, bins", row.line)
        assertEquals("Next: Sun 8 Nov", row.next)
        assertEquals("Pause Sunday reset", row.switchSpoken)
        assertEquals(listOf("Morning brief"), v.starters.map { it.name })
        // Ask's Daily row: the next to run, lit tonight; none → the fixed words.
        assertEquals(DailyLine("Study night · tonight", true), RoutineRules.dailyLine(v))
        assertEquals(DailyLine("Study night · tonight", true), DailyRules.lines(DailyInputs(nowMinute = 0, routines = RoutineRules.dailyLine(v))).of(DailyRow.ROUTINES))
        assertNull(RoutineRules.dailyLine(RoutinesView.EMPTY))

        sync()
        val onMac = routines(mac)
        assertEquals(v.rows.map { it.name }, onMac.view().rows.map { it.name })
        // Paused on the Mac: off on the Fold too, last in the list, no next run.
        assertTrue(onMac.setOn(sunday.id!!, false))
        assertFalse(onMac.setOn(sunday.id!!, false))
        sync()
        val paused = r.view()
        assertEquals("2 on · 1 paused", paused.summary)
        assertEquals("Sunday reset", paused.rows.last().name)
        assertEquals("Paused · Bills, plan the week, inbox, bins", paused.rows.last().line)
        assertNull(paused.rows.last().next)
        assertEquals("Switch on Sunday reset", paused.rows.last().switchSpoken)

        // Removed: gone on both, and the starter is offered again.
        assertTrue(r.remove(sunday.id!!))
        assertFalse(r.remove(sunday.id!!))
        sync()
        assertEquals(listOf("Study night", "Payday check"), onMac.view().rows.map { it.name })
        assertTrue("Sunday reset" in onMac.view().starters.map { it.name })
    }

    @Test
    fun aRoutineWithASendingStepWrittenElsewhereKeepsOnlyItsSafeSteps() {
        fold.replica.commitLocal(EntityTypes.ROUTINE, "x", mapOf(
            RoutineFields.NAME to "Friday wind-down".fv(),
            RoutineFields.DAYS to "5".fv(),
            RoutineFields.MINUTE to (17L * 60).fv(),
            RoutineFields.STEPS to "show|TODAY|What's left today\nask|NEEDS_YOU|Reply to the boss".fv(),
        ))
        val item = routines(fold).items().single()
        assertEquals(listOf("What's left today"), item.steps.map { it.title })
        assertEquals("What's left today", item.summary)
        assertTrue(item.on)
        assertEquals("FRI", RoutineRules.dayTile(item.schedule))
        // No days and not payday, or no time: not a routine.
        fold.replica.commitLocal(EntityTypes.ROUTINE, "y", mapOf(RoutineFields.NAME to "Broken".fv(), RoutineFields.MINUTE to 60L.fv()))
        assertEquals(listOf("x"), routines(fold).items().map { it.id })
        assertTrue(EntityTypes.ROUTINE in DataExport.TYPES)
    }

    @Test
    fun aRoutinesPageShowsItsTilesAndStepsAndABadgeTapMovesTheLabelOn() {
        val r = routines(fold)
        val id = r.addStarter("sunday-reset")!!.id!!
        val d = r.view().detail(id)!!
        assertEquals("Sunday reset", d.name)
        assertEquals("Every Sunday at 19:00", d.whenLine)
        assertEquals("Sun 8 Nov", d.next)
        assertEquals("4", d.stepCount)
        assertEquals(RoutineRules.NOT_YET, d.lastRun)
        assertEquals(listOf("Just show", "Asks you", "Obi does it", "Just show"), d.steps.map { it.badge })
        assertEquals(listOf("Money", "Week board", "Inbox autopilot", "Home"), d.steps.map { it.place })
        // Outside the inbox a tap moves Just show ↔ Asks you; Obi does it is offered only for the inbox.
        assertEquals("Asks you", d.steps[0].nextBadge)
        assertEquals("ask", d.steps[0].nextCode)
        assertEquals("does", d.steps[2].modeCode)
        assertEquals("Just show", d.steps[1].nextBadge)
        assertEquals("Just show", d.steps[2].nextBadge)
        assertEquals("Step 1: Show the week ahead of bills, in Money. Just show. Tap to change to Asks you", d.steps[0].spoken)
        assertFalse(r.setStepMode(id, 0, RoutineStepMode.DOES))
        assertFalse(r.setStepMode(id, 0, RoutineStepMode.SHOW))
        assertFalse(r.setStepMode(id, 9, RoutineStepMode.ASK))
        assertTrue(r.setStepMode(id, 0, RoutineStepMode.ASK))
        assertTrue(r.setStepMode(id, 2, RoutineStepMode.SHOW))
        sync()
        val onMac = routines(mac).view().detail(id)!!
        assertEquals(listOf("Asks you", "Asks you", "Just show", "Just show"), onMac.steps.map { it.badge })
        assertEquals(RoutineRules.STARTERS.first().steps.map { it.title }, onMac.steps.map { it.title })
        // Paused: the NEXT tile says so; tonight's study says Tonight.
        routines(mac).setOn(id, false)
        assertEquals("Paused", routines(mac).view().detail(id)!!.next)
        val study = r.addStarter("study-night")!!.id!!
        assertEquals("Tonight", r.view().detail(study)!!.next)
    }
}
