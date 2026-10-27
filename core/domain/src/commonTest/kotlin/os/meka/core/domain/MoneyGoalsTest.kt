package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoneyGoalsTest {
    private val day = 20_740L

    private fun goal(id: String, unit: String, current: Long, account: String? = null, done: Boolean = false) = GoalItem(
        id = id, title = "Goal $id", target = null, horizon = GoalHorizon.YEAR, progressPct = 0, counted = true,
        tasksDone = 0, tasksTotal = 0, habitCount = 0, done = done, meta = "", hasConflict = false,
        metric = GoalMetric(200_000L, unit, current, day - 10, null, emptyList(), moneyAccountId = account, moneyAccount = account?.let { "Savings" }),
    )

    private val savings = MoneyAccount("a-savings", "Savings", "csv", null, MoneyBalance(80_000L, day, false))
    private val card = MoneyAccount("a-card", "Card", "csv", null, MoneyBalance(-5_000L, day, false))
    private val current = MoneyAccount("a-current", "Current account", "csv", null, null)

    @Test
    fun onlyOpenPoundGoalsAndNeverCards() {
        val v = MoneyGoals.view(
            listOf(goal("g1", "£", 0), goal("g2", "books", 300), goal("g3", "£", 0, done = true)),
            listOf(savings, card, current), setOf("a-card"), day,
        )
        assertEquals(listOf("g1"), v.rows.map { it.id })
        assertEquals(listOf("Current account", "Savings"), v.rows.single().choices.map { it.name })
        assertNull(v.empty)
    }

    @Test
    fun followsSaysTheBalanceOrThatThereIsNone() {
        val v = MoneyGoals.view(listOf(goal("g1", "£", 80_000L, "a-savings"), goal("g2", "£", 0, "a-current")), listOf(savings, current), emptySet(), day)
        assertEquals("£800 of £2,000 · 40%", v.rows[0].line)
        assertEquals("Follows Savings · its balance £800 on ${CivilDate.shortLabel(day)}", v.rows[0].follows)
        assertEquals("Follows Current account · it has no balance yet: type one under Net worth", v.rows[1].follows)
    }

    @Test
    fun syncsOnlyFollowedGoalsWithABalance() {
        val gs = listOf(goal("g1", "£", 0, "a-savings"), goal("g2", "£", 0, "a-current"), goal("g3", "£", 0, "a-card"), goal("g4", "£", 0))
        assertEquals(listOf("g1" to 80_000L), MoneyGoals.toSync(gs, listOf(savings, current, card), setOf("a-card")))
    }

    @Test
    fun emptyStates() {
        assertEquals(MoneyGoals.EMPTY, MoneyGoals.view(emptyList(), listOf(savings), emptySet(), day).empty)
        assertEquals(MoneyGoals.NO_ACCOUNTS, MoneyGoals.view(listOf(goal("g1", "£", 0)), emptyList(), emptySet(), day).empty)
    }

    private fun metric(target: Long, current: Long, since: Long, due: Long?) =
        GoalMetric(target, "£", current, since, due, emptyList())

    @Test
    fun theCardRowSaysWhatItNeedsAMonthAndWhetherItIsOnPace() {
        val today = CivilDate.toEpochDay(2026, 10, 27)
        val due = CivilDate.toEpochDay(2026, 12, 15)
        // £600 by 15 Dec from 1 Oct with £240 in: on pace (expected about £230), £360 left over 2 months → £180 a month.
        val on = MoneyGoals.note(metric(60_000, 24_000, CivilDate.toEpochDay(2026, 10, 1), due), today)
        assertEquals("£180 a month to 15 Dec · on track", on.first)
        assertFalse(on.second)
        // £3,000 by 31 Jul 2027 from 1 Jul with £600 in: about £896 expected, so £297 behind; £2,400 over 10 months.
        val behind = MoneyGoals.note(metric(300_000, 60_000, CivilDate.toEpochDay(2026, 7, 1), CivilDate.toEpochDay(2027, 7, 31)), today)
        assertEquals("£240 a month to 31 Jul · £297 behind", behind.first)
        assertTrue(behind.second)
        assertEquals("£400 to go · no date yet" to false, MoneyGoals.note(metric(60_000, 20_000, today - 5, null), today))
        assertEquals("£400 to go · was due 1 Oct" to true, MoneyGoals.note(metric(60_000, 20_000, today - 60, CivilDate.toEpochDay(2026, 10, 1)), today))
        assertEquals(MoneyGoals.REACHED to false, MoneyGoals.note(metric(60_000, 61_000, today - 60, due), today))
        // Pennies round up to the pound: £100.01 left over one month is £101.
        assertEquals("£101 a month to 15 Nov · on track", MoneyGoals.note(metric(10_001, 0, today, CivilDate.toEpochDay(2026, 11, 15)), today).first)
    }

    @Test
    fun theCardRowsCarryTheBarTheirColourAndWhatTheyFollow() {
        val v = MoneyGoals.view(listOf(goal("g1", "£", 80_000L, "a-savings"), goal("g2", "£", 300_000L)), listOf(savings), emptySet(), day)
        assertEquals("£800 of £2,000", v.rows[0].amount)
        assertEquals(0.4f, v.rows[0].share)
        assertEquals(1f, v.rows[1].share)
        assertEquals(listOf(0, 1), v.rows.map { it.tone })
        assertEquals("Savings", v.rows[0].followsName)
        assertNull(v.rows[1].followsName)
        assertEquals(MoneyGoals.REACHED, v.rows[1].note)
        assertEquals("£1,200 to go · no date yet", v.rows[0].note)
        assertTrue(v.rows[0].spoken.contains("follows Savings"))
        assertEquals(MoneyTab.PLAN, MoneyTabs.tabOf(MoneySection.GOALS))
        assertTrue(MoneySection.GOALS.ordinal == MoneySection.CLEAR.ordinal + 1)
    }
}
