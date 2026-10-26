package os.meka.core.facade

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import os.meka.core.domain.MoneyOpenRules
import os.meka.core.domain.MoneyRules
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Speed (4c), through the facade: Money shows something the moment the fingerprint says yes, even while the core's one
 * thread is busy — the last view of the session (3,000 payments) marked as being brought up to date, or "Opening your
 * money…" the first time — and a fingerprint that says no is said on the page.
 */
class MoneyOpenFacadeTest {
    private val now = 1_792_144_800_000L // Fri 16 Oct 2026, 11:00 in London

    @Volatile private var holding = false
    @Volatile private var release = false

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(InMemoryServerOpStore())),
        secureRandom = Random(7), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    /** 3,000 payments from 1 Aug to 15 Oct 2026, Meka-sized. */
    private fun bigStatement(): String = buildString {
        append("Date,Description,Amount\n")
        val days = (1..31).map { "%02d/08/2026".fmt(it) } + (1..30).map { "%02d/09/2026".fmt(it) } + (1..15).map { "%02d/10/2026".fmt(it) }
        val shops = listOf("TESCO STORES 2041", "COSTA COFFEE", "TFL TRAVEL", "AMAZON MARKETPLACE", "SHELL 4411", "PRET A MANGER")
        for (i in 0 until 3_000) append(days[i % days.size]).append(',').append(shops[i % shops.size]).append(" ").append(i).append(",-").append(1 + i % 40).append(".25\n")
    }

    private fun String.fmt(n: Int) = replace("%02d", n.toString().padStart(2, '0'))

    /** Holds the core's one thread until [release]; returns once it is held. */
    private suspend fun holdCore(core: MekaCore, scope: kotlinx.coroutines.CoroutineScope) {
        holding = false; release = false
        scope.launch(Dispatchers.Default) { core.onCoreForTest { holding = true; while (!release) { /* spin */ } } }
        withContext(Dispatchers.Default) { while (!holding) { /* spin */ } }
    }

    @Test
    fun theLastViewShowsAtOnceWhileTheCoreIsBusyThenTheFreshOneReplacesIt() = runTest(timeout = 5.minutes) {
        val c = core()
        c.openMoney()
        assertFalse(c.importMoneyStatement("Current account", bigStatement()).refused)
        val built = c.moneyView.value
        assertNull(built.opening)
        assertEquals("October so far", built.month!!.label)
        c.closeMoney()
        assertTrue(c.moneyView.value.locked)

        holdCore(c, this)
        val open = launch(start = CoroutineStart.UNDISPATCHED) { c.openMoney() }
        // The core hasn't run anything yet, and the screen already shows his month.
        val shown = c.moneyView.value
        assertFalse(shown.locked)
        assertFalse(shown.skeleton)
        assertEquals(MoneyOpenRules.UPDATING, shown.opening)
        assertEquals(built.month, shown.month)
        assertEquals(built.summary, shown.summary)

        release = true
        open.join()
        val fresh = c.moneyView.value
        assertFalse(fresh.locked)
        assertNull(fresh.opening)
        assertEquals("October so far", fresh.month!!.label)
    }

    @Test
    fun theFirstOpenOfASessionSaysOpeningWithNothingToTap() = runTest(timeout = 5.minutes) {
        val c = core()
        holdCore(c, this)
        val open = launch(start = CoroutineStart.UNDISPATCHED) { c.openMoney() }
        val shown = c.moneyView.value
        assertFalse(shown.locked)
        assertTrue(shown.skeleton)
        assertEquals(MoneyOpenRules.OPENING, shown.summary)
        assertTrue(shown.accounts.isEmpty())
        assertNull(shown.month)
        release = true
        open.join()
        assertFalse(c.moneyView.value.skeleton)
        assertEquals(MoneyRules.EMPTY, c.moneyView.value.empty)
    }

    @Test
    fun aMonthStepIsNotKeptAsTheLastViewAndLockingDropsTheFigures() = runTest(timeout = 5.minutes) {
        val c = core()
        c.openMoney()
        c.importMoneyStatement("Current account", bigStatement())
        c.moneyStep(-1)
        assertTrue(c.moneyView.value.month!!.label.startsWith("September"), c.moneyView.value.month!!.label)
        c.closeMoney()
        assertNull(c.moneyView.value.month)
        holdCore(c, this)
        val open = launch(start = CoroutineStart.UNDISPATCHED) { c.openMoney() }
        // This month, not the September he stepped to.
        assertEquals("October so far", c.moneyView.value.month!!.label)
        release = true
        open.join()
    }

    /** Waits (on real time) until the core has started [n] money builds; asking proves the core's thread is free. */
    private suspend fun awaitBuilds(c: MekaCore, n: Int) = withContext(Dispatchers.Default) {
        while (c.onCoreForTest { c.moneyViewBuilds } < n) delay(5)
    }

    @Test
    fun theFreshViewIsBuiltOffTheCoreSoOtherWorkDoesntWaitBehindIt() = runTest(timeout = 5.minutes) {
        val c = core()
        c.openMoney()
        val before = c.moneyViewBuilds
        assertEquals(MoneyRules.EMPTY, c.moneyView.value.empty)
        val gate = CompletableDeferred<Unit>()
        c.moneyBuildGateForTest = gate
        val import = launch(Dispatchers.Default) { assertFalse(c.importMoneyStatement("Current account", bigStatement()).refused) }
        // The 3,000-payment build is on its way and held, yet the core's thread answers other work at once.
        awaitBuilds(c, before + 1)
        assertEquals(42, c.onCoreForTest { 42 })
        assertFalse(import.isCompleted)
        // Meanwhile the screen keeps what it showed; the import's call returns only once the fresh view is showing.
        assertEquals(MoneyRules.EMPTY, c.moneyView.value.empty)
        gate.complete(Unit)
        import.join()
        c.moneyBuildGateForTest = null
        assertEquals("October so far", c.moneyView.value.month!!.label)
        assertNull(c.moneyView.value.opening)
    }

    @Test
    fun aBuildStillOnItsWayWhenMoneyLocksNeverShowsBehindTheLock() = runTest(timeout = 5.minutes) {
        val c = core()
        c.openMoney()
        val before = c.moneyViewBuilds
        val gate = CompletableDeferred<Unit>()
        c.moneyBuildGateForTest = gate
        val import = launch(Dispatchers.Default) { c.importMoneyStatement("Current account", bigStatement()) }
        awaitBuilds(c, before + 1)
        c.closeMoney()
        assertTrue(c.moneyView.value.locked)
        gate.complete(Unit)
        import.join()
        c.moneyBuildGateForTest = null
        assertTrue(c.moneyView.value.locked)
        assertNull(c.moneyView.value.month)
        // Opening again builds afresh with the imported payments.
        c.openMoney()
        assertEquals("October so far", c.moneyView.value.month!!.label)
    }

    @Test
    fun aRefusedFingerprintIsSaidOnTheLockedPage() = runTest(timeout = 5.minutes) {
        val c = core()
        c.moneyUnlockRefused()
        assertTrue(c.moneyView.value.locked)
        assertEquals(MoneyOpenRules.REFUSED, c.moneyView.value.summary)
        c.moneyUnlockRefused(mac = true)
        assertEquals(MoneyOpenRules.REFUSED_MAC, c.moneyView.value.summary)
        c.openMoney()
        assertFalse(c.moneyView.value.locked)
        // Once open, a late "no" changes nothing.
        c.moneyUnlockRefused()
        assertFalse(c.moneyView.value.locked)
        assertNotNull(c.moneyView.value.empty)
    }
}
