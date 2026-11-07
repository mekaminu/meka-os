package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Routines, slice 5: IDEAS FROM YOUR WEEK from his bins, his late Friday ticks and his study evenings. */
class RoutineIdeasTest {
    private val world = SyncWorld()
    private val cal = LocalCalendar.fixedOffset(0)
    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)
    /** Sat 7 Nov 2026. */
    private val sat = d(11, 7)
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)
    private var n = 0
    private val fold = world.device("android")
    private fun routines() = Routines(fold.replica, { "rt${n++}" }, { world.clock.nowMs }, cal)

    init { world.clock.nowMs = at(sat, 10) }

    private val lateFridays = listOf(d(10, 30), d(11, 6)).flatMap { f -> listOf(at(f, 17), at(f, 18, 15)) }
    private val week = RoutineWeek(
        binDays = setOf(2), binMinute = 19 * 60, ticksMs = lateFridays, studySessions = listOf(1 to 20 * 60, 3 to 20 * 60 + 30),
    )

    @Test
    fun eachIdeaSaysWhyAndOpensASentenceNewRoutineReads() {
        val ideas = RoutineIdeas.ideas(emptyList(), week, sat, cal)
        assertEquals(listOf("Bin night", "Friday wind-down", "Study evenings"), ideas.map { it.name })
        assertEquals("Bins go out on Tuesdays", ideas[0].why)
        assertEquals("You tick tasks off late on Fridays", ideas[1].why)
        assertEquals("You study on Mon and Wed evenings", ideas[2].why)

        val bin = RoutineSentence.draft(ideas[0].sentence)
        assertNull(bin.problem)
        assertEquals("Bin night", bin.name)
        assertEquals("Mondays 19:00", bin.whenTile)
        assertEquals(listOf("Show which bins go out tomorrow"), bin.steps.map { it.title })
        assertEquals("Home", bin.steps[0].place)

        val fri = RoutineSentence.draft(ideas[1].sentence)
        assertEquals("Friday wind-down", fri.name)
        assertEquals("Fridays 16:30", fri.whenTile)
        assertEquals(listOf("Show what's left today", "Plan next week"), fri.steps.map { it.title })

        val study = RoutineSentence.draft(ideas[2].sentence)
        assertEquals("Study evenings", study.name)
        assertEquals("Mon · Wed 20:00", study.whenTile)
        assertTrue(study.steps.all { it.place == "Study" })
        // The hard rule: no idea makes a step that would send, pay, book or trade.
        ideas.forEach { i ->
            val dr = RoutineSentence.draft(i.sentence)
            assertNull(dr.leftOut)
            assertTrue(dr.steps.none { RoutineRules.refusal(it.title) != null })
        }
    }

    @Test
    fun ideasNeedEnoughOfTheWeekToGoOn() {
        assertTrue(RoutineIdeas.ideas(emptyList(), RoutineWeek.EMPTY, sat, cal).isEmpty())
        // One late Friday, early ticks, and one study evening aren't a habit.
        val thin = RoutineWeek(ticksMs = listOf(at(d(11, 6), 17), at(d(11, 6), 18), at(d(10, 30), 10), at(d(10, 30), 11)), studySessions = listOf(2 to 20 * 60, 4 to 7 * 60))
        assertTrue(RoutineIdeas.ideas(emptyList(), thin, sat, cal).isEmpty())
        // Ticks older than four weeks don't count.
        val old = RoutineWeek(ticksMs = listOf(d(9, 18), d(9, 25)).flatMap { listOf(at(it, 17), at(it, 18)) })
        assertEquals(0, RoutineIdeas.lateFridays(old.ticksMs, sat, cal))
        // A Monday collection's night before is Sunday; two collection days make two nights.
        val two = RoutineIdeas.ideas(emptyList(), RoutineWeek(binDays = setOf(1, 4)), sat, cal).single()
        assertEquals("Bins go out on Mondays and Thursdays", two.why)
        assertEquals("Wed · Sun 19:00", RoutineSentence.draft(two.sentence).whenTile)
    }

    @Test
    fun savingAnIdeaKeepsItFromComingBackAndRoutinesThatDoTheJobHideIt() {
        val r = routines()
        val bin = RoutineIdeas.ideas(emptyList(), week, sat, cal).first()
        val said = r.addFromSentence(bin.sentence, "", idea = bin.key)
        assertFalse(said.refused)
        val saved = r.items().single()
        assertEquals("Bin night", saved.name)
        assertEquals("idea:bin-night", saved.starter)
        assertEquals(listOf("Friday wind-down", "Study evenings"), r.view(week = week).ideas.map { it.name })

        // A Friday routine from 15:00 does wind-down's job; Study night's study steps do Study evenings'.
        r.addStarter("study-night")
        r.addFromSentence("every friday at 5pm plan next week", "")
        assertTrue(r.view(week = week).ideas.isEmpty())
        // A bad idea key is never stored.
        r.addFromSentence("every day at 8am show the weather", "", idea = "../x")
        assertNull(r.items().first { it.name == "Every morning" }.starter)
    }

    @Test
    fun sundayResetsBinStepCoversAMondayCollection() {
        val r = routines()
        r.addStarter("sunday-reset")
        assertTrue(r.view(week = RoutineWeek(binDays = setOf(1))).ideas.isEmpty())
        assertEquals(listOf("Bin night"), r.view(week = RoutineWeek(binDays = setOf(3))).ideas.map { it.name })
    }
}
