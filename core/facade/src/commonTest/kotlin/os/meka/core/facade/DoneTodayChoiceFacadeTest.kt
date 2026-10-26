package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.DoneShow
import os.meka.core.domain.DoneTodayRules
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Calmer Today, slice 1, through the facade: Done today folded by default, his choice synced to the other app. */
class DoneTodayChoiceFacadeTest {
    private var now = 1_792_998_000_000L // Mon 26 Oct 2026, 07:00 in London (GMT)
    private val day = 86_400_000L
    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun foldedByDefaultOpenForTheDayAndTheChoiceReachesTheMac() = runTest {
        val fold = core("android")
        val mac = core("mac")
        val a = fold.addTask("Reply to audit")
        fold.addTask("Send the follow-up email")
        fold.complete(a)
        fold.tick()
        assertEquals(DoneShow.FOLDED, fold.doneTodayChoice.value.show)
        assertEquals("1 done today ›", DoneTodayRules.foldLine(fold.today.value.doneToday, fold.doneTodayChoice.value))

        // Opened on the Fold: open on the Mac too after a sync, for the rest of the day.
        assertTrue(fold.setDoneTodayOpen(true))
        assertFalse(fold.setDoneTodayOpen(true))
        assertTrue(fold.doneTodayChoice.value.openToday)
        assertEquals("1 done today · hide", DoneTodayRules.foldLine(fold.today.value.doneToday, fold.doneTodayChoice.value))
        fold.syncNow()
        mac.syncNow()
        assertTrue(mac.doneTodayChoice.value.openToday)

        // Hidden on the Mac: hidden on the Fold after a sync, with nothing at Today's foot.
        assertTrue(mac.setDoneTodayShow("H"))
        assertFalse(mac.setDoneTodayShow("H"))
        assertFalse(mac.setDoneTodayShow("?"))
        mac.syncNow()
        fold.syncNow()
        val v = fold.doneTodayChoice.value
        assertEquals(DoneShow.HIDDEN, v.show)
        assertNull(DoneTodayRules.foldLine(fold.today.value.doneToday, v))
        assertTrue(DoneTodayRules.rows(fold.today.value.doneToday, v).isEmpty())

        // The next day a fold opened yesterday is closed again.
        assertTrue(fold.setDoneTodayShow("F"))
        now += day
        fold.setDoneTodayShow("F") // nothing changes; the view follows the new day
        assertFalse(fold.doneTodayChoice.value.openToday)
    }
}
