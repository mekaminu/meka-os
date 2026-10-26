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
        val empty = sFold.view().course(id)!!
        assertEquals(StudyRules.NO_LESSONS, empty.line)
        assertNull(empty.next)
        assertEquals(0, empty.pct)

        val r = sFold.addLessons(id, outline)
        assertEquals("Added 6 lessons in 3 modules", r.line)
        var v = sFold.view().course(id)!!
        assertEquals(listOf("1 · Foundations", "2 · Core skills", "Week 3"), v.modules.map { it.name })
        assertEquals("0 of 6 lessons · about 4 h in all", v.line)
        assertEquals("Next: Welcome · 1 · Foundations", v.next)

        v.modules[0].lessons.forEach { assertTrue(sFold.tick(it.id, true)) }
        assertFalse(sFold.tick(v.modules[0].lessons[0].id, true))
        v = sFold.view().course(id)!!
        assertEquals("Done", v.modules[0].count)
        assertTrue(v.modules[0].allDone)
        assertEquals("0 of 2", v.modules[1].count)
        assertEquals(50, v.pct)
        assertEquals("3 of 6 lessons · about 2 h left", v.line)
        assertEquals("Next: Pivot tables · 2 · Core skills", v.next)

        // A lesson typed under a module the course has goes at that module's end, before the next module.
        sFold.addLessons(id, "Module 1: Foundations\nShortcuts 15 min")
        v = sFold.view().course(id)!!
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
        assertEquals(2, sFold.view().course(id)!!.total)
        val intro = sFold.view().course(id)!!.modules.single().lessons.first()
        sFold.tick(intro.id, true)
        sync()
        val onMac = sMac.view().course(id)!!
        assertEquals("1 of 2 lessons · about 20 min left", onMac.line)
        assertEquals("Next: Setup", onMac.next)

        val removed = assertNotNull(sMac.removeLesson(intro.id))
        assertEquals("Removed Intro", removed.line)
        assertEquals(1, sMac.view().course(id)!!.total)
        assertTrue(sMac.undoRemove(removed.token))
        assertFalse(sMac.undoRemove(removed.token))
        sync()
        val back = sFold.view().course(id)!!.modules.single().lessons
        assertEquals(listOf("Intro", "Setup"), back.map { it.title })
        assertTrue(back.first().done)

        val gone = assertNotNull(sFold.removeCourse(id))
        assertEquals("Removed Data analytics", gone.line)
        sync()
        assertTrue(sMac.view().courses.isEmpty())
        assertTrue(sMac.undoRemove(gone.token))
        sync()
        assertEquals(2, sFold.view().course(id)!!.total)
    }
}
