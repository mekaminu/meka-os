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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Car on autopilot through the facade (slice 1): the registration and the MOT typed on the Fold, on the Mac too; the reminders. */
class CarFacadeTest {
    private var now = 1_791_795_600_000L // Mon 12 Oct 2026, 10:00 in London

    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun theCarTypedOnTheFoldRemindsAMonthAndAWeekBeforeOnBothApps() = runTest {
        val fold = core("android")
        val mac = core("mac")
        assertEquals("MOT, tax, insurance and service · type the registration once", fold.carView.value.summary)
        assertFalse(fold.setCarRegistration("not a plate"))
        val mot = fold.addCarDate("mot")!!
        assertNull(fold.addCarDate("mot"))
        assertTrue(fold.setCarRegistration("ab12 cde"))
        assertEquals("AB12 CDE · 1 of 4 on the radar", fold.carView.value.summary)
        // The MOT added before the registration takes it as its subject.
        assertEquals("AB12 CDE", fold.listsView.value.renewals.all.single { it.id == mot }.subject)

        // Due in four weeks: the reminder is today at 09:00, and the radar's digest line leaves it to the car's.
        fold.setRenewalDue(mot, fold.todayEpochDay() + 28)
        fold.syncNow(); mac.syncNow()
        assertEquals("AB12 CDE", mac.carView.value.registration)
        val g = mac.governNotifications(null, DeviceAlerts.ALL)
        val car = g.post.filter { it.source == NoticeSource.CAR }
        assertEquals(listOf("MOT due Mon 9 Nov · AB12 CDE" to "In 4 weeks · an MOT up to a month early keeps the same date"), car.map { it.title to it.text })
        assertTrue(g.post.none { it.source == NoticeSource.RENEWAL } && g.digest?.lines.orEmpty().none { it.startsWith("MOT") })
        assertTrue(mac.governNotifications(g.stateEncoded, DeviceAlerts.ALL).post.none { it.source == NoticeSource.CAR })

        // Three weeks on: the week-before reminder.
        now += 21 * 24 * 3_600_000L
        mac.tick()
        val w = mac.governNotifications(g.stateEncoded, DeviceAlerts.ALL)
        assertEquals(listOf("In a week · an MOT up to a month early keeps the same date"), w.post.filter { it.source == NoticeSource.CAR }.map { it.text })
        // Saturday: next week's week ahead shows the MOT on Monday.
        now += 5 * 24 * 3_600_000L
        mac.tick()
        assertTrue(mac.weekAheadView.value.days.flatMap { it.lines }.any { it.kind == WeekAheadKind.DUE && it.text.startsWith("MOT due") })
    }
}
