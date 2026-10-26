package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.StudyRules
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Study plan through the facade (slice 1): a course pasted and ticked on the Fold, the same on the Mac. */
class StudyFacadeTest {
    private var now = 1_793_008_800_000L // Mon 26 Oct 2026, 10:00 in London

    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun aCoursePastedAndTickedOnTheFoldShowsOnTheMac() = runTest {
        val fold = core("android")
        val mac = core("mac")
        assertTrue(fold.studyView.value.courses.isEmpty())
        assertTrue(fold.addStudyCourse("   ").refused)
        val added = fold.addStudyCourse("Data analytics")
        val id = assertNotNull(added.courseId)
        assertEquals(StudyRules.NO_LESSONS, fold.studyView.value.course(id)!!.line)

        val r = fold.addStudyLessons(id, "Module 1: Foundations\nWelcome 15 min\nSpreadsheets 45 min\nModule 2: Core skills\nPivot tables 1 h")
        assertEquals("Added 3 lessons in 2 modules", r.line)
        assertTrue(fold.addStudyLessons("nope", "Intro").refused)
        val v = fold.studyView.value.course(id)!!
        assertEquals("0 of 3 lessons · about 2 h in all", v.line)

        val welcome = v.modules.first().lessons.first().id
        assertTrue(fold.tickStudyLesson(welcome, true))
        fold.syncNow(); mac.syncNow()
        val onMac = mac.studyView.value.course(id)!!
        assertEquals(33, onMac.pct)
        assertEquals("1 of 2", onMac.modules.first().count)
        assertEquals("Next: Spreadsheets · 1 · Foundations", onMac.next)

        // Removed on the Mac, Undo puts it back (ticked), and the Fold sees it.
        val removed = assertNotNull(mac.removeStudyLesson(welcome))
        assertEquals("Removed Welcome", removed.line)
        assertEquals(2, mac.studyView.value.course(id)!!.total)
        assertTrue(mac.undoStudyRemoval(removed.token))
        mac.syncNow(); fold.syncNow()
        assertEquals("1 of 3 lessons · about 2 h left", fold.studyView.value.course(id)!!.line)

        val gone = assertNotNull(fold.removeStudyCourse(id))
        assertTrue(fold.studyView.value.courses.isEmpty())
        assertTrue(fold.undoStudyRemoval(gone.token))
        assertEquals(3, fold.studyView.value.course(id)!!.total)
    }

    @Test
    fun aPlanSetOnTheFoldShowsOnTheMac() = runTest {
        val fold = core("android")
        val mac = core("mac")
        val id = fold.addStudyCourse("Data analytics\nModule 1: Foundations\nWelcome 30 min\nSpreadsheets 45 min\nPivot tables 1 h").courseId!!
        var c = fold.studyView.value.course(id)!!
        assertEquals(StudyRules.PICK_DATE, c.plan.fit)
        val fourWeeks = c.plan.ends.first()
        assertEquals("Mon 23 Nov · 4 wks", fourWeeks.label)
        // What the Fold shows the moment the chip is tapped is what the core then writes.
        val tapped = StudyRules.plan(c.planInput.withFinish(fourWeeks.key.toLong()).withSlot("sat", true).withSession(30))
        assertTrue(fold.setStudyFinish(id, fourWeeks.key.toLong()))
        assertTrue(fold.setStudySlot(id, "sat", true))
        assertTrue(fold.setStudySession(id, 30))
        c = fold.studyView.value.course(id)!!
        assertEquals(tapped, c.plan)
        // 135 min over four weeks: 34 a week, two 30-minute sessions; Saturday alone is one slot.
        assertEquals("2 sessions a week", c.plan.perWeek)
        assertEquals("On track for Mon 23 Nov", c.plan.status)
        assertTrue(!c.plan.fits, c.plan.fit)
        assertEquals("With 45-minute sessions it fits the slots you picked.", c.plan.obi)
        fold.syncNow(); mac.syncNow()
        assertEquals(c.plan, mac.studyView.value.course(id)!!.plan)
    }
}
