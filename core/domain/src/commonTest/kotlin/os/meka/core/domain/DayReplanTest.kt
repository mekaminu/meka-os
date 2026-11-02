package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ada plans with you, part 1 slice 3: "I lost the afternoon, re-plan" — the rest of today as Obi's draft. */
class DayReplanTest {
    private val dayMs = CivilDate.DAY_MS
    private val cal = LocalCalendar.UTC
    // Wed 21 Oct 2026; 18:00 leaves 3h of free time before the day's end (21:00).
    private val today = CivilDate.toEpochDay(2026, 10, 21)
    private val evening = today * dayMs + 18 * 3_600_000L
    private val morning = today * dayMs + 10 * 3_600_000L
    private val fri = today + 2

    private fun at(day: Long, hour: Int) = day * dayMs + hour * 3_600_000L

    private fun task(id: String, title: String, est: Int, priority: Int = 0, due: Long? = null) = Task(
        id, title, null, Lifecycle.ACTIVE, due, at(today, 9), est, priority, null, null, 0, null, false,
    )

    private val tasks = listOf(
        task("call", "Call the bank", 60, due = at(today, 17)),
        task("photos", "Sort photos", 60),
        task("mot", "Book MOT", 60, priority = 2),
        task("form", "Trip form", 60, due = at(fri, 17)),
        task("tidy", "Tidy desk", 30),
    )

    @Test
    fun whatNoLongerFitsMovesToTheDaysAheadAsFaintCards() {
        val board = WeekBoardRules.build(tasks, emptyList(), evening, cal)
        val plan = DayReplanRules.plan(board, emptyList(), today, cal)!!
        // Due today stays; then due soonest (the form) and the higher priority (the MOT) fill the 3h; the rest move.
        assertEquals(listOf("photos", "tidy"), plan.rows.map { it.taskId })
        assertEquals(today, plan.replanDay)
        assertEquals(today + 1, plan.rows[0].chosen)
        assertEquals(today + 2, plan.rows[1].chosen)
        assertTrue(plan.rows.all { it.why == DayReplanRules.WHY })

        val draft = WeekDraftRules.draft(plan)!!
        assertTrue(draft.isReplan)
        assertEquals("✦ Obi's re-plan · 2 tasks off today, over 2 days", draft.line)
        assertEquals(listOf("photos"), WeekDraftRules.ghostsOn(draft, board, today + 1).map { it.taskId })
        assertTrue(WeekDraftRules.showing(draft, board))
        assertTrue(!draft.said.contains("kept light"), draft.said)
        assertEquals(
            "I've moved what won't fit today. Sort photos tomorrow, and Tidy desk on Friday. The rest still fits today. " +
                WeekDraftRules.SPOKEN_CLOSE,
            DayReplanRules.spoken(draft),
        )
        assertEquals("Today re-planned · 2 tasks moved", DayReplanRules.keptLine(2))
        assertEquals("Today re-planned · 1 task moved", DayReplanRules.keptLine(1))
    }

    @Test
    fun aTaskIsNeverMovedPastItsDueDay() {
        val board = WeekBoardRules.build(tasks, emptyList(), evening, cal)
        val busy = tasks.map { if (it.id == "mot") it.copy(estimateMinutes = 180) else it }
        val plan = DayReplanRules.plan(WeekBoardRules.build(busy, emptyList(), evening, cal), emptyList(), today, cal)!!
        val form = plan.rows.firstOrNull { it.taskId == "form" }
        // With the MOT taking the evening the form moves too, but only to a day before its due day.
        if (form != null) assertTrue(form.chosen!! <= fri && form.days.all { it <= fri }, form.toString())
        assertTrue(plan.rows.none { it.taskId == "call" })
        assertTrue(board.day(today) != null)
    }

    @Test
    fun whenEverythingStillFitsThereIsNoReplan() {
        val board = WeekBoardRules.build(tasks, emptyList(), morning, cal)
        assertNull(DayReplanRules.plan(board, emptyList(), today, cal))
        assertEquals(DayReplanRules.SPOKEN_FITS, DayReplanRules.spoken(null))
        // Only a task due today left over: it stays (moving it would make it late).
        val late = listOf(task("call", "Call the bank", 600, due = at(today, 17)))
        assertNull(DayReplanRules.plan(WeekBoardRules.build(late, emptyList(), evening, cal), emptyList(), today, cal))
    }

    @Test
    fun iLostTheAfternoonSaidToObiReplansThenSaysItThenOpensTheBoard() {
        for (said in listOf(
            "I lost the afternoon", "Obi, I've lost my afternoon, re-plan", "re-plan my day", "can you replan the rest of today please",
            "my afternoon's gone", "I lost the morning, replan my day",
        )) {
            assertEquals(TalkPlace.REPLAN_DAY, TalkOpenRules.request(said, TalkOpenRules.FOLD), said)
            assertEquals(TalkPlace.REPLAN_DAY, TalkOpenRules.request(said, TalkOpenRules.MAC), said)
        }
        assertEquals(TalkPlace.PLAN, TalkOpenRules.request("plan my day", TalkOpenRules.FOLD))
        assertNull(TalkOpenRules.request("I lost my keys", TalkOpenRules.FOLD))

        val listening = TalkFlow.start().session
        val asked = TalkFlow.heard(listening, "I lost the afternoon, re-plan", TalkOpenRules.FOLD)
        assertEquals(listOf<TalkEffect>(TalkEffect.ReplanDay), asked.effects)
        assertEquals(TalkPhase.THINKING, asked.session.phase)

        val line = "I've moved what won't fit today. Sort photos tomorrow."
        val said = TalkFlow.replanned(asked.session, line)
        assertEquals(listOf<TalkEffect>(TalkEffect.Speak(line)), said.effects)
        assertEquals(listOf(TalkEffect.End, TalkEffect.Open(TalkPlace.REPLAN_DAY)), TalkFlow.spoke(said.session).effects)
        assertEquals(listOf<TalkEffect>(TalkEffect.Speak(DayReplanRules.SPOKEN_NONE)), TalkFlow.replanned(asked.session, "").effects)
        assertTrue(TalkFlow.replanned(TalkFlow.stop(asked.session).session, "late").effects.isEmpty())
        assertEquals(DayReplanRules.OPENED, TalkOpenRules.remembered(TalkPlace.REPLAN_DAY))
    }
}
