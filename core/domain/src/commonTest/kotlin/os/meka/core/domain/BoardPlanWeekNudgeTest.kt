package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Week board, item (6) part 2: Sunday's Plan your week card and "plan my week" said or typed. */
class BoardPlanWeekNudgeTest {
    private val dayMs = CivilDate.DAY_MS
    private val cal = LocalCalendar.UTC
    private val mon = CivilDate.toEpochDay(2026, 10, 19)
    private val sun = mon + 6
    private val nextMon = mon + 7

    private fun at(day: Long, hour: Int, minute: Int = 0) = day * dayMs + hour * 3_600_000L + minute * 60_000L

    private fun task(id: String, scheduled: Long? = null, week: Long? = null) = Task(
        id, id, null, Lifecycle.ACTIVE, null, scheduled, null, 0, null, null, 0, null, false, boardWeek = week,
    )

    private fun plan(now: Long, tasks: List<Task>): WeekPlan =
        PlanWeekRules.plan(WeekBoardRules.build(tasks, emptyList(), now, cal), emptyList(), cal.epochDayOf(now), cal)

    @Test
    fun theNudgeRunsFromSundaysWeekAheadTimeToMondayNoon() {
        val on = WeekAheadSetting.DEFAULT
        assertNull(PlanWeekNudgeRules.weekDue(at(sun, 17, 59), cal, on))
        assertEquals(nextMon, PlanWeekNudgeRules.weekDue(at(sun, 18), cal, on))
        assertEquals(nextMon, PlanWeekNudgeRules.weekDue(at(nextMon, 11, 59), cal, on))
        assertNull(PlanWeekNudgeRules.weekDue(at(nextMon, 12), cal, on))
        assertNull(PlanWeekNudgeRules.weekDue(at(mon + 3, 18), cal, on))
        // His own week-ahead time, and none when the week ahead is off.
        assertNull(PlanWeekNudgeRules.weekDue(at(sun, 18), cal, WeekAheadSetting(true, 19 * 60)))
        assertEquals(nextMon, PlanWeekNudgeRules.weekDue(at(sun, 19), cal, WeekAheadSetting(true, 19 * 60)))
        assertNull(PlanWeekNudgeRules.weekDue(at(sun, 19), cal, WeekAheadSetting(false, 18 * 60)))
    }

    @Test
    fun theCardShowsOnlyWithSomethingToPlaceAndLeavesOncePlanned() {
        val now = at(sun, 18, 30)
        val tasks = listOf(task("Book MOT", scheduled = at(mon + 2, 10)), task("Sort photos", week = mon))
        val p = plan(now, tasks)
        assertEquals(nextMon, p.weekStart)
        val card = PlanWeekNudgeRules.card(p, now, cal, WeekAheadSetting.DEFAULT, doneWeek = null)!!
        assertEquals(DecisionKind.PLAN_WEEK, card.kind)
        assertEquals("Plan next week", card.title)
        assertEquals("Sunday evening · 2 tasks to place · about 5 minutes", card.why)
        assertEquals(DecisionEffect.OPEN_PLAN_WEEK, card.effect(DecisionMove.YES))
        assertEquals(DecisionEffect.SET_ASIDE, card.effect(DecisionMove.LATER))
        assertEquals(DecisionEffect.OPEN_PLAN_WEEK, card.effect(DecisionMove.OPEN))
        assertEquals("Plan next week", NeedsYouStackRules.message(card, DecisionMove.YES))
        // Planned on any device: gone for that week; nothing to place: no card.
        assertNull(PlanWeekNudgeRules.card(p, now, cal, WeekAheadSetting.DEFAULT, doneWeek = nextMon))
        assertTrue(PlanWeekNudgeRules.card(p, now, cal, WeekAheadSetting.DEFAULT, doneWeek = mon) != null)
        assertNull(PlanWeekNudgeRules.card(plan(now, emptyList()), now, cal, WeekAheadSetting.DEFAULT, null))
        // Monday morning it's this week's plan.
        val monNow = at(nextMon, 8)
        val monCard = PlanWeekNudgeRules.card(plan(monNow, tasks), monNow, cal, WeekAheadSetting.DEFAULT, null)!!
        assertEquals("Plan this week", monCard.title)
        assertTrue(monCard.why.startsWith("Monday morning · "))
    }

    @Test
    fun theCardGoesAfterTheTasksBeforeMailAndLists() {
        val lists = DecisionCard(NeedsYouStackRules.LISTS_ID, DecisionKind.LISTS, "From your lists", "2 due", null,
            "Go through", "Later", "Open Lists", DecisionEffect.OPEN_LISTS, DecisionEffect.SET_ASIDE, DecisionEffect.OPEN_LISTS, false)
        val overdue = lists.copy(id = "t", kind = DecisionKind.OVERDUE)
        val now = at(sun, 19)
        val card = PlanWeekNudgeRules.card(plan(now, listOf(task("a", week = mon))), now, cal, WeekAheadSetting.DEFAULT, null)!!
        val stack = NeedsYouStackRules.withPlanWeek(NeedsYouStack(listOf(overdue, lists)), card)
        assertEquals(listOf("t", PlanWeekNudgeRules.CARD_ID, NeedsYouStackRules.LISTS_ID), stack.cards.map { it.id })
        // Replaced, not doubled; and gone when there's no card.
        assertEquals(3, NeedsYouStackRules.withPlanWeek(stack, card).cards.size)
        assertEquals(listOf("t", NeedsYouStackRules.LISTS_ID), NeedsYouStackRules.withPlanWeek(stack, null).cards.map { it.id })
    }

    @Test
    fun planMyWeekOpensTheFlowAndOnlyThat() {
        listOf(
            "plan my week", "Hey Ada, let's plan next week", "can you help me plan out my week please", "plan the week ahead",
            "weekly plan", "open plan your week", "sort out my week", "plan this week",
        ).forEach {
            assertEquals(TalkPlace.PLAN_WEEK, TalkOpenRules.request(it, TalkOpenRules.FOLD), it)
            assertEquals(TalkPlace.PLAN_WEEK, TalkOpenRules.request(it, TalkOpenRules.MAC), it)
            assertTrue(PlanWeekNudgeRules.isRequest(it), it)
        }
        listOf("plan my week around the match", "what's the plan for next week", "how was my week", "plan my day", "week plan")
            .forEach { assertTrue(TalkOpenRules.request(it, TalkOpenRules.FOLD) != TalkPlace.PLAN_WEEK, it) }
        assertEquals(TalkPlace.PLAN, TalkOpenRules.request("plan my day", TalkOpenRules.FOLD))
        assertEquals(TalkPlace.REFLECT, TalkOpenRules.request("reflect on my week", TalkOpenRules.FOLD))
        assertNull(TalkOpenRules.request("plan my week", TalkOpenRules.WATCH))
        assertEquals(PlanWeekNudgeRules.OPENING, TalkOpenRules.opening(TalkPlace.PLAN_WEEK))
        assertEquals(PlanWeekNudgeRules.OPENED, TalkOpenRules.remembered(TalkPlace.PLAN_WEEK))
        assertEquals(TalkPlace.PLAN_WEEK, (TalkRules.reply("plan my week", 0, TalkOpenRules.FOLD) as? TalkReply.Open)?.place)
    }

    @Test
    fun typedItIsAnsweredWithTheSpreadAndWhereToAccept() {
        val now = at(sun, 19)
        val said = PlanWeekNudgeRules.answer(plan(now, listOf(task("a", week = mon), task("b", week = mon))), mac = false)
        assertTrue(said.startsWith("Plan next week, "), said)
        assertTrue("2 tasks to place, suggested 2 tasks" in said, said)
        assertTrue(said.endsWith("Change any day and accept in Calendar → Board → Plan your week; nothing moves till then."), said)
        val none = PlanWeekNudgeRules.answer(plan(now, emptyList()), mac = true)
        assertTrue("nothing left over" in none, none)
        assertTrue(none.endsWith("(⇧⌘P)."), none)
    }
}
