package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Routines, slice 4: New routine said or typed in one sentence, turned into steps he can relabel, then saved. */
class RoutineSentenceTest {
    private val world = SyncWorld()
    private val cal = LocalCalendar.fixedOffset(0)
    private val fri = CivilDate.toEpochDay(2026, 11, 6)
    private var n = 0
    private val fold = world.device("android")
    private val mac = world.device("mac")

    init { world.clock.nowMs = cal.toEpochMs(fri, 20 * 60) }

    private fun steps(d: RoutineDraft) = d.steps.map { Triple(it.title, it.place, it.badge) }

    @Test
    fun theMockupsSentenceBecomesItsFourSteps() {
        val d = RoutineSentence.draft(RoutineSentence.HINT)
        assertNull(d.problem)
        assertEquals(RoutineSchedule(setOf(7), 19 * 60), d.schedule)
        assertEquals("Sundays 19:00", d.whenTile)
        assertEquals("A nudge", d.startsWith)
        assertEquals("Sunday evening", d.name)
        assertEquals(
            listOf(
                Triple("Show my bills for the week", "Money", "Just show"),
                Triple("Plan next week", "Week board", "Asks you"),
                Triple("Tidy my inbox", "Inbox autopilot", "Obi does it"),
                Triple("Remind me about bins", "Home", "Just show"),
            ),
            steps(d),
        )
        assertEquals("show,ask,does,show", d.modes)
        assertNull(d.leftOut)
    }

    @Test
    fun whenItRunsIsReadFromTheWordsHeUses() {
        fun w(t: String) = RoutineSentence.draft(t).schedule
        assertEquals(RoutineSchedule(Recurrence.WEEKDAYS, 7 * 60), w("Weekdays at 7, show the weather and trains"))
        assertEquals(RoutineSchedule(Recurrence.WEEKDAYS, 7 * 60 + 30), w("Monday to Friday at 7:30am give me today's calendar"))
        assertEquals(RoutineSchedule((1..7).toSet(), 21 * 60), w("Every night at 9 open tonight's lessons"))
        assertEquals(RoutineSchedule((1..7).toSet(), 22 * 60), w("At 10pm tidy my inbox"))
        assertEquals(RoutineSchedule(setOf(1, 4), 18 * 60 + 30), w("Mondays and Thursdays at 18:30 plan the week"))
        assertEquals(RoutineSchedule(setOf(6, 7), 9 * 60), w("At the weekend at 9am check the garden"))
        assertEquals(RoutineSchedule(emptySet(), 9 * 60, payday = true), w("On payday show what came in and my savings goal"))
        assertEquals(RoutineSchedule(setOf(3), 19 * 60), w("Every Wednesday evening put the bins out"))
        assertEquals(RoutineSchedule((1..7).toSet(), 12 * 60), w("Every day at noon stretch for ten minutes"))
        assertEquals("Mon · Thu 18:30", RoutineSentence.draft("Mondays and Thursdays at 18:30 plan the week").whenTile)
        assertEquals("Weekday morning", RoutineSentence.draft("Weekdays at 7, show the weather").name)
        assertEquals("Payday 09:00", RoutineSentence.draft("On payday show what came in").whenTile)
    }

    @Test
    fun aNumberThatIsntATimeStaysInTheStep() {
        val d = RoutineSentence.draft("Every day at 6pm read for 20 minutes and tick 3 lessons")
        assertEquals(18 * 60, d.schedule?.minute)
        assertEquals(listOf("Read for 20 minutes", "Tick 3 lessons"), d.steps.map { it.title })
        assertEquals("Study", d.steps[1].place)
    }

    @Test
    fun aNameHeGivesIsKept() {
        val d = RoutineSentence.draft("A routine called Bin night, every Wednesday at 8pm, remind me about bins")
        assertEquals("Bin night", d.name)
        assertEquals(listOf("Remind me about bins"), d.steps.map { it.title })
    }

    @Test
    fun anythingThatWouldSendPayBookOrTradeIsLeftOutAndNamed() {
        val d = RoutineSentence.draft("Every Friday at 5pm, text Sam I'm on my way, pay the gas bill, show my spending and plan next week")
        assertEquals(listOf("Show my spending", "Plan next week"), d.steps.map { it.title })
        assertEquals("Left out: Text Sam I'm on my way · Pay the gas bill. Routines never send messages, pay, book or trade.", d.leftOut)
        assertNull(d.problem)
        val only = RoutineSentence.draft("Every Monday at 9 book the gym and buy milk")
        assertEquals(RoutineSentence.ONLY_REFUSED, only.problem)
        assertFalse(only.canSave)
        // "Check my emails" is the inbox, not sending one.
        assertEquals("Check my inbox", RoutineSentence.draft("Daily at 8 check my emails").steps.single().title)
    }

    @Test
    fun whatsMissingIsSaid() {
        assertEquals(RoutineSentence.NEED_WORDS, RoutineSentence.draft("  ").problem)
        assertEquals(RoutineSentence.NEED_WHEN, RoutineSentence.draft("show my bills and plan next week").problem)
        assertEquals(RoutineSentence.NEED_DAY, RoutineSentence.draft("Every week show my bills").problem)
        assertEquals(RoutineSentence.NEED_STEPS, RoutineSentence.draft("Every Sunday at 7pm").problem)
    }

    @Test
    fun aTapMovesALabelOnAndObiDoesItOnlyForTheInbox() {
        val d = RoutineSentence.draft(RoutineSentence.HINT)
        assertEquals("ask", d.steps[0].nextCode)
        assertEquals("show", d.steps[1].nextCode) // Plan isn't Navilife's to do by itself
        assertEquals("show", d.steps[2].nextCode)
        val m = RoutineSentence.relabel(d, 1)
        assertEquals("show,show,does,show", m)
        val again = RoutineSentence.draft(RoutineSentence.HINT, m)
        assertEquals("Just show", again.steps[1].badge)
        // A label a step can't have is ignored.
        assertEquals("Asks you", RoutineSentence.draft(RoutineSentence.HINT, "show,does").steps[1].badge)
    }

    @Test
    fun savedOnTheFoldItShowsOnTheMacAndStartsAtItsTime() {
        val r = Routines(fold.replica, { "rt${n++}" }, { world.clock.nowMs }, cal)
        val refused = r.addFromSentence("show my bills", "")
        assertTrue(refused.refused)
        assertEquals(RoutineSentence.NEED_WHEN, refused.line)
        val said = r.addFromSentence(RoutineSentence.HINT, "show,show,does,show")
        assertFalse(said.refused)
        assertEquals("Saved Sunday evening · first run Sun 8 Nov at 19:00", said.line)
        val again = r.addFromSentence(RoutineSentence.HINT, "")
        assertTrue(again.line.startsWith("Saved Sunday evening 2 · "))
        fold.sync(); mac.sync(); fold.sync()
        val onMac = Routines(mac.replica, { "m${n++}" }, { world.clock.nowMs }, cal)
        val item = assertNotNull(onMac.items().firstOrNull { it.id == said.id })
        assertEquals("Sunday evening", item.name)
        assertEquals(RoutineStepMode.SHOW, item.steps[1].mode)
        assertEquals(RoutineStepMode.DOES, item.steps[2].mode)
        assertTrue(item.on)
        assertEquals("Show my bills for the week, Plan next week, Tidy my inbox, Remind me about bins", item.summary)
        // Sunday 19:00: it starts by itself (slice 3b).
        world.clock.nowMs = cal.toEpochMs(fri + 2, 19 * 60 + 1)
        assertTrue(said.id!! in onMac.startDue())
    }

    @Test
    fun savedTonightRunsTonight() {
        val r = Routines(fold.replica, { "rt${n++}" }, { world.clock.nowMs }, cal)
        assertEquals("Saved Every night · first run tonight at 21:00", r.addFromSentence("Every night at 9pm open tonight's lessons", "").line)
    }
}
