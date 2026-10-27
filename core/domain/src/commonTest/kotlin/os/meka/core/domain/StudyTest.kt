package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Study plan, slice 1: reading an outline, a course's modules and lessons, ticks, progress, and both devices. */
class StudyTest {
    private val world = SyncWorld()
    private var n = 0
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private val sFold = Study(fold.replica, { "s${n++}" }, { world.clock.nowMs })
    private val sMac = Study(mac.replica, { "s${n++}" }, { world.clock.nowMs })

    init { world.clock.nowMs = 1_793_008_800_000L }

    private fun sync() { fold.sync(); mac.sync(); fold.sync() }

    private val outline = """
        Module 1: Foundations
        - Welcome (10 min)
        - Spreadsheets refresher · 45 min
        3. Cleaning data 1 h
        Module 2 Core skills
        Pivot tables 1h 30
        Charts that say something — 40m
        Week 3
        Dashboards
    """.trimIndent()

    @Test
    fun anOutlineSplitsIntoModulesAndLessonsWithTheirLengths() {
        val e = StudyRules.readOutline(outline, null)
        assertEquals(
            listOf(
                StudyEntry("1 · Foundations", "Welcome", 10),
                StudyEntry("1 · Foundations", "Spreadsheets refresher", 45),
                StudyEntry("1 · Foundations", "Cleaning data", 60),
                StudyEntry("2 · Core skills", "Pivot tables", 90),
                StudyEntry("2 · Core skills", "Charts that say something", 40),
                StudyEntry("Week 3", "Dashboards", null),
            ),
            e,
        )
    }

    @Test
    fun linesWithoutAHeaderGoInTheLastModuleOrLessons() {
        assertEquals(listOf(StudyEntry("Lessons", "Intro", null), StudyEntry("Lessons", "Setup", 30)), StudyRules.readOutline("Intro; Setup 30 mins", null))
        assertEquals("2 · Core skills", StudyRules.readOutline("Lesson 7", "2 · Core skills").single().module)
        assertTrue(StudyRules.readOutline("  \n - \n Module 4: Exam prep", null).isEmpty())
        // "Part 2 of the setup" is a lesson, not a module; "Basics:" is a module.
        assertEquals(listOf(StudyEntry("Basics", "Part 2 of the setup", null)), StudyRules.readOutline("Basics:\nPart 2 of the setup", null))
        assertEquals("Section: Getting started".let { StudyRules.moduleOf(it) }, "Getting started")
        assertNull(StudyRules.moduleOf("Unit testing basics"))
        assertEquals("Module 4", StudyRules.moduleOf("Module 4"))
    }

    @Test
    fun lengthsAndLabels() {
        assertEquals("Intro" to 45, StudyRules.lengthOf("Intro 45 min"))
        assertEquals("Intro (" to null, StudyRules.lengthOf("Intro (").let { it.first to it.second })
        assertEquals(90, StudyRules.lengthOf("Deep dive (1.5h)").second)
        assertEquals(null, StudyRules.lengthOf("45 min").second)
        assertEquals("45 min", StudyRules.minsLabel(45))
        assertEquals("1 h 30", StudyRules.minsLabel(90))
        assertEquals("2 h", StudyRules.minsLabel(120))
        assertEquals("about 18 h", StudyRules.aboutLabel(18 * 60 - 10))
        assertEquals("about 2½ h", StudyRules.aboutLabel(150))
        assertEquals("about 40 min", StudyRules.aboutLabel(40))
        assertEquals("Data analytics", StudyRules.courseTitle("course: data analytics"))
        assertNull(StudyRules.courseTitle("  - "))
    }

    @Test
    fun aCourseShowsItsModulesProgressAndNext() {
        val added = sFold.addCourse("Data analytics")
        assertFalse(added.refused)
        val id = assertNotNull(added.courseId)
        val empty = sFold.view(0L).course(id)!!
        assertEquals(StudyRules.NO_LESSONS, empty.line)
        assertNull(empty.next)
        assertEquals(0, empty.pct)

        val r = sFold.addLessons(id, outline)
        assertEquals("Added 6 lessons in 3 modules", r.line)
        var v = sFold.view(0L).course(id)!!
        assertEquals(listOf("1 · Foundations", "2 · Core skills", "Week 3"), v.modules.map { it.name })
        assertEquals("0 of 6 lessons · about 4 h in all", v.line)
        assertEquals("Next: Welcome · 1 · Foundations", v.next)

        v.modules[0].lessons.forEach { assertTrue(sFold.tick(it.id, true)) }
        assertFalse(sFold.tick(v.modules[0].lessons[0].id, true))
        v = sFold.view(0L).course(id)!!
        assertEquals("Done", v.modules[0].count)
        assertTrue(v.modules[0].allDone)
        assertEquals("0 of 2", v.modules[1].count)
        assertEquals(50, v.pct)
        assertEquals("3 of 6 lessons · about 2 h left", v.line)
        assertEquals("Next: Pivot tables · 2 · Core skills", v.next)

        // A lesson typed under a module the course has goes at that module's end, before the next module.
        sFold.addLessons(id, "Module 1: Foundations\nShortcuts 15 min")
        v = sFold.view(0L).course(id)!!
        assertEquals(listOf("Welcome", "Spreadsheets refresher", "Cleaning data", "Shortcuts"), v.modules[0].lessons.map { it.title })
        assertEquals("3 of 4", v.modules[0].count)
        // With no header, a line goes in the last module.
        assertEquals("Added Final project to Week 3", sFold.addLessons(id, "Final project").line)
        assertTrue(sFold.addLessons(id, " - ").refused)
        assertTrue(sFold.addLessons("nope", "Intro").refused)
    }

    @Test
    fun ticksAndRemovalsReachTheOtherDeviceAndUndoBringsThemBack() {
        val id = sFold.addCourse("Data analytics\nIntro 30 min\nSetup 20 min").courseId!!
        assertEquals(2, sFold.view(0L).course(id)!!.total)
        val intro = sFold.view(0L).course(id)!!.modules.single().lessons.first()
        sFold.tick(intro.id, true)
        sync()
        val onMac = sMac.view(0L).course(id)!!
        assertEquals("1 of 2 lessons · about 20 min left", onMac.line)
        assertEquals("Next: Setup", onMac.next)

        val removed = assertNotNull(sMac.removeLesson(intro.id))
        assertEquals("Removed Intro", removed.line)
        assertEquals(1, sMac.view(0L).course(id)!!.total)
        assertTrue(sMac.undoRemove(removed.token))
        assertFalse(sMac.undoRemove(removed.token))
        sync()
        val back = sFold.view(0L).course(id)!!.modules.single().lessons
        assertEquals(listOf("Intro", "Setup"), back.map { it.title })
        assertTrue(back.first().done)

        val gone = assertNotNull(sFold.removeCourse(id))
        assertEquals("Removed Data analytics", gone.line)
        sync()
        assertTrue(sMac.view(0L).courses.isEmpty())
        assertTrue(sMac.undoRemove(gone.token))
        sync()
        assertEquals(2, sFold.view(0L).course(id)!!.total)
    }

    // ---- Study fixes (Meka 2026-10-26 23:21) ----

    @Test
    fun aLessonIsRenamedAndMovedToAnotherModuleOnBothDevices() {
        val id = sFold.addCourse("Data analytics\nModule 1: Foundations\nWelcome\nSetup\nModule 2: Core skills\nPivot tables").courseId!!
        val setup = sFold.view(0L).course(id)!!.modules[0].lessons[1]
        assertTrue(sFold.renameLesson(setup.id, "  setting up excel "))
        assertFalse(sFold.renameLesson(setup.id, "Setting up excel"))
        assertFalse(sFold.renameLesson(setup.id, " - "))
        assertTrue(sFold.moveLesson(setup.id, "2 · Core skills"))
        assertFalse(sFold.moveLesson(setup.id, "2 · core skills"))
        sync()
        val v = sMac.view(0L).course(id)!!
        assertEquals(listOf("Welcome"), v.modules[0].lessons.map { it.title })
        assertEquals(listOf("Pivot tables", "Setting up excel"), v.modules[1].lessons.map { it.title })
        // Back into the first module: it goes at that module's end, before the next module.
        assertTrue(sMac.moveLesson(setup.id, "1 · Foundations"))
        assertEquals(listOf("Welcome", "Setting up excel"), sMac.view(0L).course(id)!!.modules[0].lessons.map { it.title })
    }

    @Test
    fun lessonsGoIntoTheModuleChosenAndANewModuleNeedsAFirstLesson() {
        val id = sFold.addCourse("Data analytics\nModule 1: Foundations\nWelcome\nModule 2: Core skills\nPivot tables").courseId!!
        assertEquals("Added 2 lessons to 1 · Foundations", sFold.addLessons(id, "Shortcuts\nCharts 20 min", into = "1 · Foundations").line)
        var v = sFold.view(0L).course(id)!!
        assertEquals(listOf("Welcome", "Shortcuts", "Charts"), v.modules[0].lessons.map { it.title })
        assertEquals(StudyRules.MODULE_NEEDS_LESSON, sFold.addModule(id, "Dashboards", "").line)
        assertEquals(StudyRules.MODULE_NOT_READ, sFold.addModule(id, " ", "Intro").line)
        assertEquals("Added Layout to 3 · Dashboards", sFold.addModule(id, "Module 3: Dashboards", "Layout").line)
        v = sFold.view(0L).course(id)!!
        assertEquals(listOf("1 · Foundations", "2 · Core skills", "3 · Dashboards"), v.modules.map { it.name })
        assertTrue(sFold.renameModule(id, "3 · Dashboards", "Reports"))
        assertEquals("Reports", sFold.view(0L).course(id)!!.modules[2].name)
    }

    @Test
    fun deletedLessonsWaitUnderRecentlyDeletedAndCanBeRestored() {
        val id = sFold.addCourse("Data analytics\nModule 1: Foundations\nWelcome\nSetup\nModule 2: Core skills\nPivot tables\nCharts").courseId!!
        val welcome = sFold.view(0L).course(id)!!.modules[0].lessons[0]
        sFold.tick(welcome.id, true)
        assertNotNull(sFold.removeLesson(welcome.id))
        var v = sFold.view(0L).course(id)!!
        assertEquals(listOf("Welcome"), v.deleted.map { it.title })
        assertEquals("From 1 · Foundations", v.deleted.single().line)
        sync()
        val onMac = sMac.view(0L).course(id)!!
        assertEquals(1, onMac.deleted.size)
        assertTrue(sMac.restoreLesson(onMac.deleted.single().id))
        assertFalse(sMac.restoreLesson(onMac.deleted.single().id))
        sync()
        v = sFold.view(0L).course(id)!!
        assertTrue(v.deleted.isEmpty())
        assertEquals(listOf("Welcome", "Setup"), v.modules[0].lessons.map { it.title })
        assertTrue(v.modules[0].lessons[0].done)

        // A whole module: one Undo brings every lesson back; deleted lessons older than 30 days drop off the list.
        val mod = assertNotNull(sFold.removeModule(id, "2 · Core skills"))
        assertEquals("Removed 2 · Core skills", mod.line)
        assertEquals(1, sFold.view(0L).course(id)!!.modules.size)
        assertEquals(2, sFold.view(0L).course(id)!!.deleted.size)
        assertTrue(sFold.undoRemove(mod.token))
        assertEquals(listOf("Pivot tables", "Charts"), sFold.view(0L).course(id)!!.modules[1].lessons.map { it.title })
        val old = StudyDeletedLesson("x", id, "Old", "1 · Foundations", 0L)
        assertTrue(StudyRules.deletedRows(id, listOf(old), 31L * 86_400_000L).isEmpty())
        assertEquals(1, StudyRules.deletedRows(id, listOf(old.copy(deletedAtMs = null)), 31L * 86_400_000L).size)
        assertEquals("Delete \u201CWelcome\u201D?", StudyRules.confirmDelete("Welcome"))
        assertEquals("Delete \u201C2 · Core skills\u201D and its 2 lessons?", StudyRules.confirmDeleteModule("2 · Core skills", 2))
    }

    // ---- Slice 2a: the plan ----

    private val today = 20_752L // Mon 26 Oct 2026

    /** The mockup's course: four modules of six lessons (30, 45, 45, 60, 40, 50 min), the first five ticked. */
    private fun mockupCourse(): String {
        val lines = (1..4).flatMap { m -> listOf("Module $m: Part $m") + listOf(30, 45, 45, 60, 40, 50).mapIndexed { i, mins -> "Lesson ${(m - 1) * 6 + i + 1} $mins min" } }
        val id = sFold.addCourse("Data analytics").courseId!!
        sFold.addLessons(id, lines.joinToString("\n"))
        sFold.view(today).course(id)!!.modules.flatMap { it.lessons }.take(5).forEach { sFold.tick(it.id, true) }
        return id
    }

    @Test
    fun aPlanWorksOutSessionsAWeekAndWhetherTheyFit() {
        val id = mockupCourse()
        var p = sFold.view(today).course(id)!!.plan
        assertEquals(StudyRules.PICK_DATE, p.fit)
        assertNull(p.perWeek)
        assertNull(p.status)
        assertEquals(listOf("Mon 23 Nov · 4 wks", "Mon 7 Dec · 6 wks", "Mon 21 Dec · 8 wks"), p.ends.map { it.label })
        assertEquals(listOf("30 min", "45 min", "1 h"), p.lens.map { it.label })
        assertEquals(listOf(false, true, false), p.lens.map { it.on })

        assertTrue(sFold.setFinish(id, today + 42, today))
        assertFalse(sFold.setFinish(id, today + 42, today))
        p = sFold.view(today).course(id)!!.plan
        // 860 min left over six weeks: 144 a week, four 45-minute sessions.
        assertEquals("4 sessions a week", p.perWeek)
        assertEquals("about 2 h 24 · 19 lessons left", p.perWeekSub)
        assertEquals(StudyRules.PICK_SLOTS, p.fit)
        assertEquals("On track for Mon 7 Dec", p.status)
        assertEquals(listOf(false, true, false), p.ends.map { it.on })

        listOf("train", "thu", "sat").forEach { assertTrue(sFold.setSlot(id, it, true)) }
        assertFalse(sFold.setSlot(id, "sat", true))
        assertFalse(sFold.setSlot(id, "moon", true))
        p = sFold.view(today).course(id)!!.plan
        assertTrue(p.fits)
        assertEquals("Fits: you picked 5 slots a week.", p.fit)
        assertEquals("That leaves 1 spare slot a week to catch up if one slips.", p.obi)

        // Only Saturday mornings: longer sessions aren't enough, a later date is.
        listOf("train", "thu").forEach { sFold.setSlot(id, it, false) }
        p = sFold.view(today).course(id)!!.plan
        assertFalse(p.fits)
        assertEquals("Doesn’t fit: you need 4 slots a week but picked 1. Add a slot, make sessions longer or move the date.", p.fit)
        assertEquals("Move the date to Mon 15 Mar and it fits the slots you picked.", p.obi)
        // Hour-long sessions with the train mornings added: 144 min a week is three sessions, and four slots are picked.
        sFold.setSlot(id, "train", true)
        assertTrue(sFold.setSession(id, 60))
        assertFalse(sFold.setSession(id, 5))
        p = sFold.view(today).course(id)!!.plan
        assertEquals("3 sessions a week", p.perWeek)
        assertTrue(p.fits)
        assertEquals(listOf(false, false, true), p.lens.map { it.on })

        // The plan reaches the Mac.
        sync()
        assertEquals(p, sMac.view(today).course(id)!!.plan)
    }

    @Test
    fun onTrackOrBehindIsMeasuredFromTheDayTheDateWasSet() {
        val id = mockupCourse()
        sFold.setFinish(id, today + 42, today)
        sFold.setSlot(id, "sat", true)
        // Fifteen days on with nothing ticked: 19 lessons over 42 days is 6.8 by now.
        var p = sFold.view(today + 15).course(id)!!.plan
        assertTrue(p.behind)
        assertEquals("6 lessons behind · catch up Saturday", p.status)
        // The chosen date is no longer one of the choices, so it shows first, picked.
        assertEquals("Mon 7 Dec · 4 wks", p.ends.first().label)
        assertTrue(p.ends.first().on)
        assertEquals(4, p.ends.size)
        sFold.view(today).course(id)!!.modules.flatMap { it.lessons }.filter { !it.done }.take(6).forEach { sFold.tick(it.id, true) }
        p = sFold.view(today + 14).course(id)!!.plan
        assertFalse(p.behind)
        assertEquals("On track for Mon 7 Dec", p.status)
        sFold.setSlot(id, "sat", false)
        assertEquals("1 lesson behind · an extra session catches you up", sFold.view(today + 17).course(id)!!.plan.status)
        // Past the date.
        p = sFold.view(today + 43).course(id)!!.plan
        assertEquals(StudyRules.PASSED, p.status)
        assertNull(p.perWeek)
        // A new date starts the measure again from that day.
        sFold.setFinish(id, today + 43 + 28, today + 43)
        assertEquals("On track for Tue 5 Jan", sFold.view(today + 43).course(id)!!.plan.status)
    }

    @Test
    fun aTappedChoiceIsWorkedOutBeforeItIsWritten() {
        val id = mockupCourse()
        val input = sFold.view(today).course(id)!!.planInput
        val tapped = StudyRules.plan(input.withFinish(today + 28).withSlot("eve", true).withSession(30))
        sFold.setFinish(id, today + 28, today); sFold.setSlot(id, "eve", true); sFold.setSession(id, 30)
        assertEquals(sFold.view(today).course(id)!!.plan, tapped)
        // Lessons that don't say how long count as the course's average.
        val other = sFold.addCourse("Spanish\nGreetings\nNumbers 30 min\nFood 60 min").courseId!!
        assertEquals(135, StudyRules.leftMins(sFold.view(today).course(other)!!.planInput))
    }
}
