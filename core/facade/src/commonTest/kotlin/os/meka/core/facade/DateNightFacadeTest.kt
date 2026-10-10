package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.CivilDate
import os.meka.core.domain.DateNightRules
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Date night through the facade (slice 1): set on one app, seen on the other, kept clear by Plan my day, Today's line. */
class DateNightFacadeTest {
    private var now = 1_791_622_800_000L // Sat 10 Oct 2026, 10:00 in London

    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun dateNightIsSetOnceKeptClearAndSkippableFromEitherApp() = runTest {
        val fold = core("android")
        val mac = core("mac")
        val fri16 = CivilDate.toEpochDay(2026, 10, 16)
        assertEquals(DateNightRules.OFF_LINE, fold.dateNightView.value.summary)
        assertFalse(fold.dateNightView.value.on)

        assertEquals("Every other Friday from 19:00 · next Fri 16 Oct", fold.setDateNight(5, 19 * 60, fri16))
        assertNull(fold.setDateNight(5, 19 * 60, fri16)) // nothing changed
        fold.syncNow(); mac.syncNow()
        val v = mac.dateNightView.value
        assertTrue(v.on)
        assertEquals(listOf(fri16, fri16 + 14, fri16 + 28, fri16 + 42), v.nights.map { it.day })

        // On the night: Today's header says it, and Plan my day keeps the evening clear.
        now += 6 * 86_400_000L + 8 * 3_600_000L + 45 * 60_000L // Fri 16 Oct, 18:45: 15 minutes before it starts
        mac.addTask("Sort the garage")
        mac.tick()
        assertEquals("Date night tonight from 19:00", mac.today.value.dateNight?.text)
        val plan = mac.planDay()
        // Kept free beside the fasting plan's last meal (the default eating window closes in the evening).
        assertTrue("Date night" in plan.meals.map { it.title }, "date night kept free")
        assertEquals(listOf("Sort the garage"), plan.unplaced.map { it.title })
        assertTrue(plan.keptLine.orEmpty().contains("date night", ignoreCase = true), "the note under the plan says so")

        // Skipped on the Fold: the Mac's evening is free to plan again.
        assertEquals("Skipped tonight · the evening is free to plan", fold.skipDateNight(fri16, true))
        fold.syncNow(); mac.syncNow()
        mac.tick()
        assertNull(mac.today.value.dateNight)
        assertFalse("Date night" in mac.planDay().meals.map { it.title }, "a skipped night is free to plan")
        assertEquals("Every other Friday from 19:00 · next Fri 30 Oct", mac.dateNightView.value.summary)
        assertEquals("Tonight is kept clear again", mac.skipDateNight(fri16, false))

        assertTrue(mac.dateNightOff())
        mac.tick()
        assertEquals(DateNightRules.OFF_LINE, mac.dateNightView.value.summary)
        assertNull(mac.today.value.dateNight)
    }

    /** Slice 2: a week before, Needs you's card and the 09:00 heads-up on both apps; Booked on one clears both; Undo asks again. */
    @Test
    fun theWeekBeforeCardAndHeadsUpGoOnceBooked() = runTest {
        val fold = core("android")
        val mac = core("mac")
        val fri23 = CivilDate.toEpochDay(2026, 10, 23)
        fold.setDateNight(5, 19 * 60, fri23)
        fold.syncNow(); mac.syncNow()
        mac.tick()
        assertNull(mac.dateNightView.value.nudge) // 13 days off

        // Fri 16 Oct, 09:30: the card on the Mac and the heads-up through the governor.
        now += 6 * 86_400_000L - 30 * 60_000L
        mac.tick()
        val card = mac.dateNightView.value.nudge!!
        assertEquals(fri23, card.day)
        assertEquals("Date night · Fri 23 Oct", card.title)
        fun dateNight(r: os.meka.core.domain.GovernorResult) = r.post.filter { it.source == os.meka.core.domain.NoticeSource.DATE_NIGHT }
        val gov = mac.governNotifications(null, os.meka.core.domain.DeviceAlerts.ALL)
        assertEquals(listOf("Date night · Fri 23 Oct" to "Book somewhere and arrange cover · from 19:00"), dateNight(gov).map { it.title to it.text })
        assertTrue(dateNight(mac.governNotifications(gov.stateEncoded, os.meka.core.domain.DeviceAlerts.ALL)).isEmpty()) // once

        // Booked on the Fold: gone from the Mac's Needs you and its governor.
        assertEquals("Date night Fri 23 Oct · booked", fold.bookDateNight(fri23, true))
        assertNull(fold.bookDateNight(fri23, true))
        fold.syncNow(); mac.syncNow()
        mac.tick()
        assertNull(mac.dateNightView.value.nudge)
        assertTrue(dateNight(mac.governNotifications(null, os.meka.core.domain.DeviceAlerts.ALL)).isEmpty())
        assertEquals("19:00 · kept clear · booked", mac.dateNightView.value.nights.first().line)

        // Undo on the Fold's bar: the card is back.
        assertEquals("Date night Fri 23 Oct · not booked yet", fold.bookDateNight(fri23, false))
        fold.syncNow(); mac.syncNow()
        mac.tick()
        assertEquals(fri23, mac.dateNightView.value.nudge?.day)
    }
}
