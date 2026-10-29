package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.PhoneCalendarRow
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Week board (j): study sessions kept in the phone's own calendar show on the board and in Study, Fold only. */
class PhoneCalendarsFacadeTest {
    private var now = 1_793_008_800_000L // Mon 26 Oct 2026, 10:00 in London
    private val hour = 3_600_000L
    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun aStudySessionInThePhonesCalendarShowsOnTheBoardAndInStudyOnTheFoldOnly() = runTest {
        val fold = core("android")
        val mac = core("mac")
        val id = fold.addStudyCourse("Data analytics\nModule 1: Foundations\nWelcome 30 min\nPivot tables 1 h").courseId!!
        val today = fold.todayEpochDay()
        val thu = now + 3 * 24 * hour + 11 * hour // Thu 29 Oct 21:00
        assertNull(fold.phoneCalendars.value)

        fold.setPhoneCalendars(
            listOf(
                PhoneCalendarRow(41, "My calendar", "com.osp.app.signin", "Study · Data analytics", thu, thu + 2 * hour, false),
                PhoneCalendarRow(42, "Personal", "com.google", "Gym", thu - 3 * hour, thu - 2 * hour, false),
            ),
            listOf("My calendar" to "com.osp.app.signin", "Personal" to "com.google"),
        )
        assertEquals(listOf("My calendar · 1 event in the next 5 weeks"), fold.phoneCalendars.value!!.map { it.line })
        assertEquals(listOf("Study · Data analytics"), fold.weekBoard.value.day(today + 3)!!.chips.map { it.title })
        val week = fold.studyView.value.course(id)!!.week
        assertEquals("Thu", week.first().dayLabel)
        assertEquals("21:00", week.first().time)
        assertTrue(week.first().place.contains("Study · Data analytics"))

        // Kept on the Fold: the Mac never sees the phone's calendar.
        fold.syncNow(); mac.syncNow()
        assertTrue(mac.weekBoard.value.day(today + 3)!!.chips.isEmpty())

        // Access taken away: gone at once.
        fold.setPhoneCalendars(emptyList(), emptyList())
        assertTrue(fold.weekBoard.value.day(today + 3)!!.chips.isEmpty())
        assertEquals(emptyList<os.meka.core.domain.PhoneCalendarLine>(), fold.phoneCalendars.value)
    }
}
