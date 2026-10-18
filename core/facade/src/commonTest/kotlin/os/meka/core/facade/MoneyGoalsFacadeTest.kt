package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.GoalHorizon
import os.meka.core.domain.MoneyGoals
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Money manager (6) through the facade: a £ goal follows a savings account's balance, on both devices, counted once. */
class MoneyGoalsFacadeTest {
    private var now = 1_792_144_800_000L // Fri 16 Oct 2026, 11:00 in London
    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode() + 7), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val savings = "Date,Description,Amount,Balance\n" +
        "01/10/2026,TRANSFER FROM CURRENT,200.00,600.00\n" +
        "12/10/2026,TRANSFER FROM CURRENT,200.00,800.00\n"

    @Test
    fun aPoundGoalFollowsTheSavingsBalanceOnBothDevices() = runTest(timeout = 5.minutes) {
        val fold = core("android")
        val mac = core("mac")
        val goal = fold.addGoal("Holiday fund", null, GoalHorizon.YEAR)
        assertTrue(fold.setGoalNumber(goal, "£2,000"))
        fold.openMoney()
        assertTrue(fold.importMoneyStatement("Savings", savings).line.startsWith("Added 2"))
        val row = fold.moneyView.value.goals!!.rows.single()
        assertEquals("£0 of £2,000 · 0%", row.line)
        assertNull(row.follows)
        val acct = row.choices.single { it.name == "Savings" }.accountId

        val r = fold.moneyGoalFollow(goal, acct)
        assertEquals("“Holiday fund” follows Savings · £800 of £2,000 now", r.line)
        val m = fold.goalsView.value.goals.first { it.id == goal }.metric!!
        assertEquals(80_000L, m.currentX100)
        assertEquals(MoneyGoals.followsInGoals("Savings"), m.moneyLine)
        assertEquals("Follows Savings · its balance £800 on Mon 12 Oct", fold.moneyView.value.goals!!.rows.single().follows)

        // The Mac opening Money the same day writes the same update: still £800, not £1,600.
        fold.syncNow(); mac.syncNow()
        mac.openMoney()
        mac.syncNow(); fold.syncNow()
        assertEquals(80_000L, mac.goalsView.value.goals.first { it.id == goal }.metric!!.currentX100)
        assertEquals(80_000L, fold.goalsView.value.goals.first { it.id == goal }.metric!!.currentX100)

        // A new balance typed moves the goal; a day later another one adds a second update.
        fold.moneyBalance(acct, "£950")
        assertEquals(95_000L, fold.goalsView.value.goals.first { it.id == goal }.metric!!.currentX100)
        now += 86_400_000L
        fold.closeMoney()
        fold.moneyBalance(acct, "£1,000") // locked: nothing
        fold.openMoney()
        fold.moneyBalance(acct, "£1,000")
        val after = fold.goalsView.value.goals.first { it.id == goal }.metric!!
        assertEquals(100_000L, after.currentX100)
        assertEquals(2, after.recent.size)

        // Stop following: the number stays; Undo follows again.
        val off = fold.moneyGoalFollow(goal, null)
        assertEquals(MoneyGoals.unlinkedSaid("Holiday fund"), off.line)
        val g = fold.goalsView.value.goals.first { it.id == goal }.metric!!
        assertEquals(100_000L, g.currentX100)
        assertNull(g.moneyLine)
        assertTrue(fold.undoMoneyCategory(off.undo!!))
        assertNotNull(fold.goalsView.value.goals.first { it.id == goal }.metric!!.moneyLine)
    }

    @Test
    fun onlyPoundGoalsAndNotWhileLocked() = runTest(timeout = 5.minutes) {
        val fold = core("android")
        val books = fold.addGoal("Read more", null, GoalHorizon.YEAR)
        assertTrue(fold.setGoalNumber(books, "12 books"))
        assertTrue(fold.moneyGoalFollow(books, "a-savings").refused) // locked
        fold.openMoney()
        assertEquals(MoneyGoals.EMPTY, fold.moneyView.value.goals!!.empty)
        fold.importMoneyStatement("Savings", savings)
        assertTrue(fold.moneyGoalFollow(books, fold.moneyView.value.accounts.single().id).refused)
    }
}
