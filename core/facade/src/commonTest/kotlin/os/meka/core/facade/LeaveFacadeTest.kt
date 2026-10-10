package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.LeaveRules
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Annual leave through the facade (slice 1): booked on the Fold, counted on the Mac, and a day off for work mode. */
class LeaveFacadeTest {
    private var now = 1_791_622_800_000L // Sat 10 Oct 2026, 10:00 in London

    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun leaveBookedOnTheFoldIsCountedOnTheMacAndIsADayOffForWork() = runTest {
        val fold = core("android")
        val mac = core("mac")
        assertEquals(LeaveRules.EMPTY_LINE, fold.leaveView.value.summary)
        assertNull(fold.addLeave("some time soon"))
        assertEquals("Booked Annual leave · Tue 27 – Fri 30 Oct · 4 days", fold.addLeave("27–30 Oct")?.line)
        assertTrue(fold.addLeave("Sat 17 Oct")!!.refused)
        assertTrue(fold.setLeaveAllowance(27, 1))
        assertEquals("4 of 27 days booked · 23 days left", fold.leaveView.value.summary)

        // The school's half term: four of its five work days are booked; booking it takes the Monday.
        assertEquals("Added Half term · Mon 26 – Fri 30 Oct · Rex and Logan", fold.addSchool("Half term 26–30 Oct"))
        val half = fold.leaveView.value.holidays.single()
        assertEquals("4 of 5 booked", half.note)
        assertEquals("Book 1 day", half.bookLabel)
        assertEquals("Booked Half term · Mon 26 Oct · 1 day", fold.bookLeaveForHoliday(half.id)?.line)
        assertEquals("Booked", fold.leaveView.value.holidays.single().note)

        fold.syncNow(); mac.syncNow()
        val v = mac.leaveView.value
        assertEquals("5 of 27 days booked · 22 days left", v.summary)
        assertEquals(listOf("Half term", "Annual leave"), v.booked.map { it.title })

        // On Tue 27 Oct (GMT by then) Meka is off work on both apps, and the school asks no cover question.
        now = 1_793_095_200_000L
        assertFalse(mac.currentWorkMode().atWork)
        assertTrue(mac.currentWorkMode().line.contains("Annual leave"))
        assertTrue(fold.schoolView.value.covers.isEmpty())

        assertTrue(mac.removeLeave(v.booked.first { it.title == "Annual leave" }.id))
        mac.syncNow(); fold.syncNow()
        assertTrue(fold.currentWorkMode().atWork)
    }

    /** Slice 2: the heads-up through the governor (once), and the brief's and shutdown's lines either side of leave. */
    @Test
    fun theNudgeIsAHeadsUpAndTheBriefSaysTheLastDayBeforeLeave() = runTest {
        val fold = core("android")
        fold.addSchool("Half term 26–30 Oct")
        fun leave(r: os.meka.core.domain.GovernorResult) = r.post.filter { it.source == os.meka.core.domain.NoticeSource.LEAVE }
        val gov = fold.governNotifications(null, os.meka.core.domain.DeviceAlerts.ALL)
        assertEquals(listOf("Half term · nothing booked" to "Mon 26 – Fri 30 Oct · 5 work days · book before the good weeks go"),
            leave(gov).map { it.title to it.text })
        assertEquals(os.meka.core.domain.NoticeTarget.LEAVE, leave(gov).single().target)
        assertTrue(leave(fold.governNotifications(gov.stateEncoded, os.meka.core.domain.DeviceAlerts.ALL)).isEmpty()) // once

        // Booking it ends the nudge.
        fold.bookLeaveForHoliday(fold.leaveView.value.holidays.single().id)
        assertTrue(leave(fold.governNotifications(null, os.meka.core.domain.DeviceAlerts.ALL)).isEmpty())
        fold.addLeave("2 Nov")
        // Fri 23 Oct, 08:00 BST: the brief says it's the last day before leave, and when Meka is back.
        now = 1_792_738_800_000L
        fold.tick()
        assertEquals("Work 09:00–17:30 · last day before leave · back Tue 3 Nov", fold.briefView.value.workLine)
        // Sun 25 Oct, 20:00 GMT: the shutdown's tomorrow; Mon 26 Oct, 08:00: the brief on leave.
        now = 1_792_958_400_000L
        fold.tick()
        assertEquals("Annual leave · no work · back Tue 3 Nov", fold.shutdownView.value.tomorrow.workLine)
        now = 1_793_001_600_000L
        fold.tick()
        assertEquals("Annual leave · no work · back Tue 3 Nov", fold.briefView.value.workLine)
    }
}
