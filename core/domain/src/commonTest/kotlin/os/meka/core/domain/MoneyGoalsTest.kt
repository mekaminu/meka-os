package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
            listOf(savings, card, current), setOf("a-card"),
        )
        assertEquals(listOf("g1"), v.rows.map { it.id })
        assertEquals(listOf("Current account", "Savings"), v.rows.single().choices.map { it.name })
        assertNull(v.empty)
    }

    @Test
    fun followsSaysTheBalanceOrThatThereIsNone() {
        val v = MoneyGoals.view(listOf(goal("g1", "£", 80_000L, "a-savings"), goal("g2", "£", 0, "a-current")), listOf(savings, current), emptySet())
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
        assertEquals(MoneyGoals.EMPTY, MoneyGoals.view(emptyList(), listOf(savings), emptySet()).empty)
        assertEquals(MoneyGoals.NO_ACCOUNTS, MoneyGoals.view(listOf(goal("g1", "£", 0)), emptyList(), emptySet()).empty)
    }
}
