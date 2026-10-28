package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Study plan slice 3 part 1: the outline from a screenshot of a course's contents page. */
class StudyScanTest {
    @Test
    fun aCurriculumPageBecomesModulesAndTimedLessons() {
        val read = """
            Course content
            Section 1: Getting started 3 / 5 | 32min
            1. Welcome to the course
            Play
            2min
            2. Installing the tools
            12min
            ☑ 3. Your first sheet · Video · 18 min
            Section 2: Formulas 0 / 2 | 1hr 5min
            4. Cell references 25:40
            5. Lookups (40:10)
            Completed
            25% complete
        """.trimIndent()
        val s = StudyScanRules.scan(read)
        assertEquals(
            listOf(
                "Section 1: Getting started",
                "1. Welcome to the course 2 min",
                "2. Installing the tools 12 min",
                "3. Your first sheet 18 min",
                "Section 2: Formulas",
                "4. Cell references 26 min",
                "5. Lookups 40 min",
            ),
            s.outline.lines(),
        )
        assertEquals(5, s.lessons)
        assertEquals(2, s.modules)
        assertTrue(s.found)
        assertEquals("Read 5 lessons in 2 modules, all with a time · check them, then Add lessons · the picture isn't kept", s.line)
        val entries = StudyRules.readOutline(s.outline, null)
        assertEquals("1 · Getting started", entries.first().module)
        assertEquals("Welcome to the course", entries.first().title)
        assertEquals(2, entries.first().mins)
        assertEquals("2 · Formulas", entries.last().module)
        assertEquals(40, entries.last().mins)
    }

    @Test
    fun weekStyleListsWithKindsOnTheirOwnLines() {
        val read = """
            Week 1 · 4 videos · 1 h
            Why data matters
            Video • 6 min
            Asking good questions
            Reading • 10 minutes
            Practice quiz
            Quiz · 30 min
            Week 2
            Cleaning a table
        """.trimIndent()
        val s = StudyScanRules.scan(read)
        assertEquals(
            listOf("Week 1", "Why data matters 6 min", "Asking good questions 10 min", "Practice quiz 30 min", "Week 2", "Cleaning a table"),
            s.outline.lines(),
        )
        assertEquals(4, s.lessons)
        assertEquals(2, s.modules)
        assertEquals("Read 4 lessons in 2 modules, 3 with a time · check them, then Add lessons · the picture isn't kept", s.line)
    }

    @Test
    fun aTimeIsNeverGivenTwiceOrToAHeader() {
        val s = StudyScanRules.scan("Module 3: Charts\n45 min\nBar charts 10 min\n5 min\nPie charts")
        assertEquals(listOf("Module 3: Charts", "Bar charts 10 min", "Pie charts"), s.outline.lines())
    }

    @Test
    fun furnitureAndProgressGo() {
        val s = StudyScanRules.scan("Overview\nCurriculum\n4 of 38 complete\n12 sections • 89 lectures • 10h 30m total length\nLesson 3 of 10\nResume\nIntro to pivots\nIntro to pivots")
        assertEquals(listOf("Intro to pivots"), s.outline.lines())
        assertEquals(0, s.modules)
        assertEquals("Read 1 lesson · check them, then Add lessons · the picture isn't kept", s.line)
    }

    @Test
    fun nothingLikeAnOutline() {
        val s = StudyScanRules.scan("Play\n2min\n50%\nVideo")
        assertFalse(s.found)
        assertEquals(StudyScanRules.NONE_LINE, s.line)
        assertEquals("kept", StudyScanRules.merge("kept", s))
    }

    @Test
    fun aSecondScreenshotAddsUnderWhatsInTheField() {
        val s = StudyScanRules.scan("Section 4: Dashboards\nLayout basics 9 min")
        assertEquals("Section 4: Dashboards\nLayout basics 9 min", StudyScanRules.merge("", s))
        assertEquals("Lesson a\nSection 4: Dashboards\nLayout basics 9 min", StudyScanRules.merge("Lesson a\n", s))
    }

    @Test
    fun clockTimes() {
        assertEquals(2, StudyScanRules.timeOnly("02:14"))
        assertEquals(63, StudyScanRules.timeOnly("1:02:30"))
        assertEquals(1, StudyScanRules.timeOnly("0:10"))
        assertEquals(65, StudyScanRules.timeOnly("1h 5m"))
        assertEquals(5, StudyScanRules.timeOnly("5 min left"))
        assertEquals(null, StudyScanRules.timeOnly("Lesson 5"))
        assertEquals(null, StudyScanRules.timeOnly("12:75"))
    }
}
