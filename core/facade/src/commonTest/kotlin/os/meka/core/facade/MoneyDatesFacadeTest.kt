package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.DeviceAlerts
import os.meka.core.domain.NoticeSource
import os.meka.core.domain.WeekAheadKind
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** UK money deadlines through the facade: added on the Fold, on the Mac too; the switch nudge and the week ahead. */
class MoneyDatesFacadeTest {
    private var now = 1_791_795_600_000L // Mon 12 Oct 2026, 10:00 in London

    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun moneyDatesAddedOnTheFoldNudgeOnBothApps() = runTest {
        val fold = core("android")
        val mac = core("mac")
        assertEquals("Tax, ISA, contracts and pension · dates only", fold.moneyDates().summary)
        fold.addMoneyDate("isa")!!
        assertNull(fold.addMoneyDate("isa"))
        assertNull(fold.addMoneyDate("premium bonds"))
        val energy = fold.addMoneyDate("energy")!!
        // The energy deal ends in six weeks: the switch nudge is today at 09:00, not the radar's digest line.
        fold.setRenewalDue(energy, fold.todayEpochDay() + 42)
        fold.syncNow(); mac.syncNow()
        assertEquals("2 of 5 on the radar", mac.moneyDates().summary)
        val g = mac.governNotifications(null, DeviceAlerts.ALL)
        val money = g.post.filter { it.source == NoticeSource.MONEY }
        assertEquals(listOf("Energy contract ends Mon 23 Nov" to "In 6 weeks · time to compare deals before it ends"), money.map { it.title to it.text })
        assertTrue(g.digest?.lines.orEmpty().none { it.startsWith("Energy") })
        assertTrue(mac.governNotifications(g.stateEncoded, DeviceAlerts.ALL).post.none { it.source == NoticeSource.MONEY })

        // Five weeks on, Saturday: next week's week ahead shows the end on Monday.
        now += 40 * 24 * 3_600_000L
        mac.tick()
        assertTrue(mac.weekAheadView.value.days.flatMap { it.lines }.any { it.kind == WeekAheadKind.DUE && it.text.startsWith("Energy contract") })
    }
}
