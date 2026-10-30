package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.NeedsYouMeanwhileRules
import os.meka.core.domain.NowAction
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fold review 2026-10-12 17:56, slice 4: Needs you's Coming up and Up next's Break it down, through the facade. */
class ComingUpFacadeTest {
    private var now = 1_791_622_800_000L // Sat 10 Oct 2026, 10:00 in London
    private val hour = 3_600_000L

    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun remindersSetOnTheFoldAreComingUpOnTheMac() = runTest {
        val fold = core("android")
        val mac = core("mac")
        val a = fold.addTask("renew passport")
        val b = fold.addTask("Call the garage")
        fold.addTask("No reminder")
        fold.setReminder(a, now + 8 * hour) // 18:00 today
        fold.setReminder(b, now + 23 * hour) // 09:00 tomorrow
        assertEquals(listOf(a, b), fold.today.value.comingUp.map { it.id })
        fold.syncNow(); mac.syncNow()
        val m = NeedsYouMeanwhileRules.build(null, null, mac.today.value.comingUp)
        assertEquals(listOf("Renew passport", "Call the garage"), m.comingUp.map { it.title })
        assertEquals("Reminder · Today · 18:00", m.comingUp[0].meta)
        mac.complete(a)
        assertEquals(listOf(b), mac.today.value.comingUp.map { it.id })
    }

    @Test
    fun aTaskLeftTwoDaysOffersBreakItDownUntilItHasSteps() = runTest {
        val fold = core("android")
        val id = fold.addTask("Sort the loft")
        assertTrue(fold.upNextCard()!!.offers(NowAction.OPEN_TASK))
        now += 2 * 24 * hour
        fold.tick()
        assertEquals(listOf(NowAction.DONE, NowAction.BREAK_DOWN, NowAction.TOMORROW), fold.upNextCard()!!.actions)
        fold.addSteps(id, "boxes\nbin bags")
        assertTrue(fold.upNextCard()!!.offers(NowAction.OPEN_TASK))
    }

    @Test
    fun aComingUpReminderOpensItsTaskForTheDetail() = runTest {
        val fold = core("android")
        val id = fold.addTask("Check the passport dates")
        fold.setReminder(id, now + 23 * hour) // 09:00 tomorrow: under Coming up, not one of Today's own tasks
        assertEquals(listOf(id), fold.today.value.comingUp.map { it.id })
        assertEquals("Check the passport dates", fold.taskForDetail(id)?.title)
        fold.delete(id)
        assertNull(fold.taskForDetail(id))
        assertNull(fold.taskForDetail("nothing"))
    }
}
