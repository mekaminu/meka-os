package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.CivilDate
import os.meka.core.domain.SchoolRules
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** School rhythm through the facade (slice 1): typed lines, the cover question, work from home and Undo, on two devices. */
class SchoolFacadeTest {
    private var now = 1_791_622_800_000L // Sat 10 Oct 2026, 10:00 in London

    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun anInsetDayInTheOfficeAsksWhosCoveringAndWorkingFromHomeAnswersItOnBothApps() = runTest {
        val fold = core("android")
        val mac = core("mac")
        val monday = CivilDate.toEpochDay(2026, 10, 12)
        assertEquals(SchoolRules.EMPTY_LINE, fold.schoolView.value.summary)
        assertEquals("Added INSET day · Mon 12 Oct · Rex and Logan", fold.addSchool("INSET 12 Oct"))
        assertEquals("Added PE · every Tuesday · Rex", fold.addSchool("Rex PE Tue"))
        assertNull(fold.addSchool("Half term"))
        assertEquals("Next day off: INSET day · Mon 12 Oct", fold.schoolView.value.summary)
        assertEquals("Next: Tue 13 Oct", fold.schoolView.value.weekly.single().note)

        fold.syncNow(); mac.syncNow()
        val cover = mac.schoolView.value.covers.single()
        assertEquals("Rex and Logan are off Mon 12 Oct", cover.title)
        assertEquals("INSET day · in 2 days", cover.line)
        val done = mac.coverSchool(cover.id, home = true)!!
        assertEquals("Working from home Mon 12 Oct", done.line)
        assertEquals(listOf(monday), done.homeDays)
        assertTrue(mac.schoolView.value.covers.isEmpty())
        assertNull(mac.coverSchool(cover.id, home = false)) // already answered

        mac.syncNow(); fold.syncNow()
        assertTrue(fold.schoolView.value.covers.isEmpty())
        assertEquals("You're working from home Mon 12 Oct", fold.schoolView.value.off.single().note)

        // Undo on the Fold: the question is back and Monday is an office day again.
        assertTrue(fold.undoSchoolCover(done))
        assertEquals(1, fold.schoolView.value.covers.size)
        assertEquals("Covered · INSET day", fold.coverSchool(cover.id, home = false)?.line)
        assertEquals("Covered", fold.schoolView.value.off.single().note)

        assertTrue(fold.removeSchool(cover.id))
        assertTrue(fold.schoolView.value.off.isEmpty())
    }

    @Test
    fun theSchoolDayShowsOnTodayAndInTheBriefAndThePeKitTheEveningBefore() = runTest {
        val fold = core("android")
        assertNull(fold.today.value.school)
        fold.addSchool("Logan's football tournament Sat 10 Oct")
        fold.addSchool("Rex PE Mon")
        assertEquals("Logan: Football tournament today", fold.today.value.school?.text)
        assertEquals("Logan: Football tournament today", fold.briefView.value.school?.text)
        assertTrue(fold.briefView.value.cardLine.contains("Logan: Football tournament today"), fold.briefView.value.cardLine)
        assertTrue(os.meka.core.domain.BriefSpeech.script(fold.briefView.value).contains("School today: Logan has Football tournament."))
        assertNull(fold.shutdownView.value.tomorrow.school) // Sunday tomorrow

        now += 33 * 3_600_000L // Sun 11 Oct, 19:00
        fold.tick()
        assertNull(fold.today.value.school)
        assertEquals("Tomorrow · Rex: PE kit", fold.shutdownView.value.tomorrow.school?.text)
    }
    @Test
    fun theCoverQuestionIsAHeadsUpOnBothAppsUntilItIsAnswered() = runTest {
        val fold = core("android")
        val mac = core("mac")
        fold.addSchool("INSET 12 Oct")
        fold.addSchool("Trip payment by 14 Oct")
        fold.syncNow(); mac.syncNow()
        fun school(g: os.meka.core.domain.GovernorResult) = g.post.filter { it.source == os.meka.core.domain.NoticeSource.SCHOOL }
        val gov = mac.governNotifications(null, os.meka.core.domain.DeviceAlerts.ALL)
        assertEquals(listOf("Rex and Logan are off Mon 12 Oct" to "You're in the office that day. Who's covering?"),
            school(gov).map { it.title to it.text })
        assertEquals(listOf("Reminder Mon 12 Oct, 09:00"), mac.schoolView.value.dates.map { it.note })
        // Posted once; answered on the Fold, the Mac has nothing left to ask.
        assertTrue(school(mac.governNotifications(gov.stateEncoded, os.meka.core.domain.DeviceAlerts.ALL)).isEmpty())
        val cover = fold.schoolView.value.covers.single()
        fold.coverSchool(cover.id, home = false)
        fold.syncNow(); mac.syncNow()
        assertTrue(school(mac.governNotifications(null, os.meka.core.domain.DeviceAlerts.ALL)).isEmpty())
        // Monday 09:00: the payment's heads-up.
        now += 2 * 24 * 3_600_000L - 3_600_000L + 60_000L
        mac.syncNow()
        assertEquals(listOf("Due Wed 14 Oct · Trip payment"), school(mac.governNotifications(null, os.meka.core.domain.DeviceAlerts.ALL)).map { it.title })
    }
}
