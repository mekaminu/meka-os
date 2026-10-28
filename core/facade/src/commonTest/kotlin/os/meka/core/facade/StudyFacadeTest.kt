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
    fun aLessonDeletedOnTheFoldIsRestoredFromRecentlyDeletedOnTheMac() = runTest {
        // Study fixes (Meka 2026-10-26 23:21): nothing is lost; lessons go into the module chosen; a sheet renames and moves.
        val fold = core("android")
        val mac = core("mac")
        val id = fold.addStudyCourse("Data analytics\nModule 1: Foundations\nWelcome\nModule 2: Core skills\nPivot tables").courseId!!
        assertEquals("Added Shortcuts to 1 · Foundations", fold.addStudyLessonsTo(id, "1 · Foundations", "Shortcuts").line)
        assertEquals("Added Layout to Dashboards", fold.addStudyModule(id, "Dashboards", "Layout").line)
        val shortcuts = fold.studyView.value.course(id)!!.modules[0].lessons[1].id
        assertTrue(fold.renameStudyLesson(shortcuts, "Keyboard shortcuts"))
        assertTrue(fold.moveStudyLesson(shortcuts, "Dashboards"))
        assertTrue(fold.renameStudyModule(id, "Dashboards", "Reports"))
        val removed = assertNotNull(fold.removeStudyLesson(shortcuts))
        assertEquals("Removed Keyboard shortcuts", removed.line)
        fold.syncNow(); mac.syncNow()
        val onMac = mac.studyView.value.course(id)!!
        assertEquals(listOf("Keyboard shortcuts"), onMac.deleted.map { it.title })
        assertEquals("From Reports", onMac.deleted.single().line)
        assertTrue(mac.restoreStudyLesson(onMac.deleted.single().id))
        mac.syncNow(); fold.syncNow()
        val back = fold.studyView.value.course(id)!!
        assertTrue(back.deleted.isEmpty())
        assertEquals(listOf("Layout", "Keyboard shortcuts"), back.modules[2].lessons.map { it.title })
        val mod = assertNotNull(fold.removeStudyModule(id, "Reports"))
        assertEquals(2, fold.studyView.value.course(id)!!.modules.size)
        assertTrue(fold.undoStudyRemoval(mod.token))
        assertEquals(3, fold.studyView.value.course(id)!!.modules.size)
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

    @Test
    fun aLessonPlannedOnTheFoldShowsOnTodayAndInTheTomorrowSheetOnTheMac() = runTest {
        // Meka 2026-10-26 23:21: "how do you plan an item for today, tomorrow…"
        val fold = core("android")
        val mac = core("mac")
        val id = fold.addStudyCourse("Data analytics\nModule 1: Foundations\nWelcome 15 min\nPivot tables 1 h").courseId!!
        val (welcome, pivot) = fold.studyView.value.course(id)!!.modules.single().lessons
        val today = fold.todayEpochDay()
        assertEquals("Planned \u201CWelcome\u201D for today", fold.planStudyLesson(welcome.id, today))
        assertEquals("Planned \u201CPivot tables\u201D for tomorrow", fold.planStudyLesson(pivot.id, today + 1))
        assertEquals(null, fold.planStudyLesson(pivot.id, today + 1))
        fold.syncNow(); mac.syncNow()
        mac.tick()
        assertEquals("Study today · 1 lesson · about 15 min · next: Welcome", mac.today.value.study?.text)
        assertEquals("Study · 1 lesson · about 1 h · Pivot tables", mac.shutdownView.value.tomorrow.study?.text)
        assertEquals(listOf("Today", "Tomorrow"), mac.studyView.value.course(id)!!.modules.single().lessons.map { it.planned })
        // And under their days on the Week board, a tap away from the course.
        assertEquals("Study · 1 lesson · about 15 min", mac.weekBoard.value.day(today)?.study?.text)
        assertEquals("Study · 1 lesson · about 1 h", mac.weekBoard.value.day(today + 1)?.study?.text)
        assertEquals(id, mac.weekBoard.value.day(today + 1)?.study?.courseId)

        // Ticked on the Mac: Today says it's done; taken off the plan, tomorrow has nothing to study.
        assertTrue(mac.tickStudyLesson(welcome.id, true))
        assertTrue(mac.planStudyLesson(pivot.id, null)!!.endsWith("is no longer planned"))
        mac.tick()
        assertEquals("Study today · done", mac.today.value.study?.text)
        assertEquals(null, mac.shutdownView.value.tomorrow.study)
        assertEquals("Study · done", mac.weekBoard.value.day(today)?.study?.text)
        assertEquals(null, mac.weekBoard.value.day(today + 1)?.study)
    }

    @Test
    fun severalLessonsPlannedAtOnceOnTheFoldShowOnThatDayOnTheMac() = runTest {
        // Study fixes (3) slice 3: select several lessons → Plan for…, including a day picked past this week.
        val fold = core("android")
        val mac = core("mac")
        val id = fold.addStudyCourse("Data analytics\nModule 1: Foundations\nWelcome 15 min\nPivot tables 1 h\nDashboards 30 min").courseId!!
        val ls = fold.studyView.value.course(id)!!.modules.single().lessons
        val today = fold.todayEpochDay()
        val ids = ls.map { it.id }
        assertEquals("Planned 3 lessons for tomorrow", fold.planStudyLessons(ids, today + 1))
        assertEquals(null, fold.planStudyLessons(ids, today + 1))
        // A day gone by, or more than a year ahead, isn't planned.
        assertEquals(null, fold.planStudyLessons(ids, today - 1))
        assertEquals(null, fold.planStudyLesson(ls[0].id, today + 400))
        assertEquals("Planned 2 lessons for " + StudyRules.plannedLabel(today + 20, today), fold.planStudyLessons(ids.drop(1), today + 20))
        fold.syncNow(); mac.syncNow()
        mac.tick()
        val planned = mac.studyView.value.course(id)!!.modules.single().lessons.map { it.plannedDay }
        assertEquals(listOf(today + 1, today + 20, today + 20), planned)
        assertEquals("Study · 1 lesson · about 15 min · Welcome", mac.shutdownView.value.tomorrow.study?.text)
        assertEquals("3 lessons are no longer planned", mac.planStudyLessons(ids, null))
    }

    @Test
    fun lessonsNotPlannedAreSuggestedForTheTimesPickedAndHandPlannedOnesStay() = runTest {
        // Study plan, slice 2b part 1: the auto-schedule fills the days he hasn't planned, never moving ones he set.
        val fold = core("android")
        val mac = core("mac")
        val id = fold.addStudyCourse("Data analytics\nModule 1: Foundations\nWelcome 15 min\nPivot tables 1 h\nDashboards 30 min").courseId!!
        val today = fold.todayEpochDay() // Mon 26 Oct
        assertTrue(fold.setStudySession(id, 60))
        assertTrue(fold.setStudySlot(id, "sat", true))
        val (welcome, pivot, dash) = fold.studyView.value.course(id)!!.modules.single().lessons
        assertEquals(listOf("Sat 31 Oct · suggested", "Sat 7 Nov · suggested", null), listOf(welcome, pivot, dash).map { it.planned })
        // Pivot tables planned by hand for tomorrow: it stays there; Welcome and Dashboards (45 min) share Saturday.
        fold.planStudyLesson(pivot.id, today + 1)
        fold.syncNow(); mac.syncNow()
        mac.tick()
        val onMac = mac.studyView.value.course(id)!!
        assertEquals(listOf("Sat 31 Oct · suggested", "Tomorrow", "Sat 31 Oct · suggested"), onMac.modules.single().lessons.map { it.planned })
        assertEquals("2 lessons suggested for your next session · Sat · from the times you picked", onMac.suggested)
        assertEquals("Study · 2 lessons · about 45 min", mac.weekBoard.value.day(today + 5)?.study?.text)
        assertEquals("Study · 1 lesson · about 1 h · Pivot tables", mac.shutdownView.value.tomorrow.study?.text)
    }

    @Test
    fun startAndDoneOnASessionTickItsLessonsOnBothDevicesWithUndo() = runTest {
        // Study plan, slice 2b part 2: THIS WEEK with Start and Done.
        val fold = core("android")
        val mac = core("mac")
        val id = fold.addStudyCourse("Data analytics\nModule 1: Foundations\nWelcome 15 min\nPivot tables 45 min\nDashboards 30 min").courseId!!
        val today = fold.todayEpochDay() // Mon 26 Oct
        assertTrue(fold.setStudySession(id, 60))
        assertTrue(fold.setStudySlot(id, "eve", true)) // Mon and Wed evenings
        val week = fold.studyView.value.course(id)!!.week
        assertEquals(listOf("Today", "Wed"), week.map { it.dayLabel })
        assertEquals("20:30", week.first().time)
        assertEquals("Welcome + 1 more · 1 h", week.first().lessons)
        assertEquals("Evening", week.first().place)
        assertEquals(os.meka.core.domain.StudyWeekState.START, week.first().state)

        // Start: on, on this device only.
        assertTrue(fold.startStudySession(week.first().key))
        assertEquals(false, fold.startStudySession(week.first().key))
        val on = fold.studyView.value.course(id)!!.week.first()
        assertEquals(os.meka.core.domain.StudyWeekState.ON, on.state)
        assertEquals("Started 10:00", on.note)

        // Done: both lessons ticked, the row quiet, and the Mac sees them ticked and the session done.
        val done = fold.finishStudySession(on.key)
        assertEquals("Ticked 2 lessons", done.line)
        assertEquals(os.meka.core.domain.StudyWeekState.DONE, fold.studyView.value.course(id)!!.week.first().state)
        fold.syncNow(); mac.syncNow()
        val onMac = mac.studyView.value.course(id)!!
        assertEquals(2, onMac.done)
        assertEquals(os.meka.core.domain.StudyWeekState.DONE, onMac.week.first().state)
        assertEquals("Dashboards · 30 min", onMac.week[1].lessons)

        // Done ahead on Wednesday's from the Mac: Dashboards is ticked and counts on today; Undo puts it back.
        val ahead = mac.finishStudySession(onMac.week[1].key)
        assertEquals("Ticked \u201CDashboards\u201D · done ahead of Wed", ahead.line)
        assertEquals(3, mac.studyView.value.course(id)!!.done)
        assertTrue(mac.undoStudySessionDone(ahead.token))
        assertEquals(2, mac.studyView.value.course(id)!!.done)
        assertEquals("Dashboards · 30 min", mac.studyView.value.course(id)!!.week[1].lessons)
        assertEquals(os.meka.core.domain.StudyWeekRules.NOTHING_TO_TICK, fold.finishStudySession(on.key).line)
    }

    @Test
    fun theNudgeShowsTenMinutesBeforeASessionThenStartAndDoneAndNotNow() = runTest {
        // Study plan, slice 2b rest: "Nudge me 10 minutes before · A card from Obi, not out loud".
        val fold = core("android")
        val mac = core("mac")
        val id = fold.addStudyCourse("Data analytics\nModule 1: Foundations\nWelcome 15 min\nPivot tables 45 min\nDashboards 30 min").courseId!!
        assertTrue(fold.setStudySession(id, 60))
        assertTrue(fold.setStudySlot(id, "eve", true)) // Mon and Wed evenings, 20:30
        assertEquals(null, fold.today.value.studyNudge) // 10:00: too early
        now += (10 * 60 + 19) * 60_000L // 20:19
        fold.tick()
        assertEquals(null, fold.today.value.studyNudge)
        now += 60_000L // 20:20
        fold.tick()
        val n = assertNotNull(fold.today.value.studyNudge)
        assertEquals("Study in 10 min · Data analytics", n.headline)
        assertEquals("20:30 · Welcome + 1 more · 1 h · Evening", n.line)
        assertEquals(os.meka.core.domain.StudyWeekRules.START, n.button)
        // Start from the card: it says so, with Done.
        assertTrue(fold.startStudySession(n.key))
        val on = assertNotNull(fold.today.value.studyNudge)
        assertEquals("Studying · Data analytics", on.headline)
        assertEquals("Started 20:20 · Welcome + 1 more · 1 h · Evening", on.line)
        assertEquals(os.meka.core.domain.StudyWeekRules.DONE, on.button)
        // Still there at the session's end, gone once done.
        now += 70 * 60_000L // 21:30
        fold.tick()
        assertNotNull(fold.today.value.studyNudge)
        assertEquals("Ticked 2 lessons", fold.finishStudySession(on.key).line)
        assertEquals(null, fold.today.value.studyNudge)

        // On the Mac (not started there): past the 15 minutes after the start it's gone; Not now puts it away.
        fold.syncNow(); mac.syncNow()
        now -= 70 * 60_000L // 20:20 again
        mac.tick()
        assertEquals(null, mac.today.value.studyNudge) // its lessons are ticked
        assertTrue(mac.setStudyNudge(id, false))
        assertEquals(false, mac.setStudyNudge(id, false))
        mac.syncNow(); fold.syncNow(); fold.tick()
        assertEquals(false, fold.studyView.value.course(id)!!.nudge)
        assertTrue(fold.setStudyNudge(id, true))
        // Wednesday 20:25: Dashboards' session.
        now += 2 * os.meka.core.domain.CivilDate.DAY_MS + 5 * 60_000L
        fold.tick()
        val wed = assertNotNull(fold.today.value.studyNudge)
        assertEquals("Study in 5 min · Data analytics", wed.headline)
        now += 20 * 60_000L // 20:45: 15 min after the start, not started
        fold.tick()
        assertEquals(null, fold.today.value.studyNudge)
        now -= 10 * 60_000L // 20:35
        fold.tick()
        assertEquals("Study now · Data analytics", fold.today.value.studyNudge?.headline)
        assertTrue(fold.putAwayStudyNudge(wed.key))
        assertEquals(false, fold.putAwayStudyNudge(wed.key))
        assertEquals(null, fold.today.value.studyNudge)
    }
}
