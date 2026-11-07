package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.MoneyPlanMore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Plan's lower half, slice 1, through the facade: Add a £ goal from Goals' empty card, only while Money is open. */
class MoneyPlanMoreFacadeTest {
    private val now = 1_792_144_800_000L // Fri 16 Oct 2026, 11:00 in London

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(7), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val statement = "Date,Description,Amount\n" +
        "05/10/2026,SALARY OAKFIELD LTD,2500.00\n" +
        "06/10/2026,TESCO STORES 2041,-40.00\n"

    @Test
    fun addAPoundGoalFromTheEmptyCard() = runTest(timeout = 5.minutes) {
        val fold = core()
        assertTrue(fold.moneyAddGoal("Holiday", "£2,000").refused, "locked: nothing added")
        fold.openMoney()
        fold.importMoneyStatement("Current account", statement)
        val gv = fold.moneyView.value.goals
        assertNotNull(MoneyPlanMore.goalsEmpty(gv, hasSave = true, hasClear = true), "no £ goal yet: the start card")

        val bad = fold.moneyAddGoal("Holiday", "lots")
        assertTrue(bad.refused)
        assertEquals(MoneyPlanMore.ADD_BAD_AMOUNT, bad.line)

        val made = fold.moneyAddGoal("Holiday", "£2,000")
        assertFalse(made.refused)
        assertEquals("Holiday · £2,000 added to Goals", made.line)
        assertTrue(fold.goalsView.value.goals.any { it.title == "Holiday" })
        assertEquals(listOf("Holiday"), fold.moneyView.value.goals?.rows?.map { it.title })

        assertTrue(fold.undoMoneyCategory(made.undo!!))
        assertFalse(fold.goalsView.value.goals.any { it.title == "Holiday" })
    }
}
