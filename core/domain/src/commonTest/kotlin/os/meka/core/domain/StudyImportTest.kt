package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One-tap course import: a course link (gzip + base64url JSON) or its JSON read on the device, then Add. */
class StudyImportTest {
    private val world = SyncWorld()
    private var n = 0
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private val sFold = Study(fold.replica, { "s${n++}" }, { world.clock.nowMs })
    private val sMac = Study(mac.replica, { "s${n++}" }, { world.clock.nowMs })

    init { world.clock.nowMs = 1_793_008_800_000L }

    private fun link(d: String) = "navilife://study/import?v=1&d=$d"

    // Made with Python's gzip (level 9: fixed and dynamic blocks; level 0: a stored block), invented course names.
    private val small = "H4sIAAAAAAAC_2WPsQrCQBBEf2XZSiFFgrFJq9gJgoWFXLEmKzm83MndRpBw32Xvl3mJhYrdMPNmlxlQtBjGCnBNQkCWzF10HTAD7FzTGw4pPA4fbju5UFSwcb1tSLSzE57QMMqE44FN7TqGWZFDp-18zPdXz9SEllkCeD57TtrD8wHlcoRQxQy-Hq2cZwgXbczf-Z2-OQGhUzKhaGGR42-1JS_vDXqqlHlUUcUX7XKCeO8AAAA"
    private val big = "H4sIAAAAAAAC_53Wu2rDMBiG4VsRmkORdVbXrt06lgyx48HgQ4ntKeTemwTaLhJ90Sb4Xy3P9F3lNmxjL1-FfBuX_Sza0zp0qzwIOS3nfezX--nz-ld99N02LLNoHsn9vC7zM5Hvz7doXhpxapd9E9vyNXRi3qe2v4hGNE5Mw_z49VvqXKlzpcmVJlfaXGlzpcuVLlf6XOlzZciV4ac83g4iQ6kLlBpTakypMaXGlBpTakyp6yhNgdJgSoMpDaY0mNJgSoMpTR2lLVBaTGkxpcWUFlNaTGkxpa2jdAVKhykdpnSY0mFKhykdpnR1lL5A6TGlx5QeU3pM6TGlx5S-jjIUKAOmDJgyYMqAKQOmDJgy1FHGAmXElBFTRkwZMWXElBFTxjrKVKBMmDJhyoQpE6ZMmDJhylRH2ajSRld8pCu-0hWf6YrvdMWHuuJLXf1Lerx9A6f7ZAIcDQAA"
    private val stored = "H4sIAAAAAAAE_wHvABD_eyJ0aXRsZSI6ICJEYXRhIGFuYWx5dGljcyIsICJtb2R1bGVzIjogW3sidGl0bGUiOiAiTW9kdWxlIDE6IEZvdW5kYXRpb25zIiwgImxlc3NvbnMiOiBbIldlbGNvbWUgKDEwIG1pbikiLCAiU3ByZWFkc2hlZXRzIHJlZnJlc2hlciDCtyA0NSBtaW4iXX0sIHsidGl0bGUiOiAiQ29yZSBza2lsbHMiLCAibGVzc29ucyI6IFsiUGl2b3QgdGFibGVzIDFoIDMwIiwgeyJ0aXRsZSI6ICJDaGFydHMiLCAibWlucyI6IDQwfV19XX3tcoJ47wAAAA"

    @Test
    fun aGzippedLinkReadsAsTheSameModulesAndLessonsAsAPastedOutline() {
        val r = StudyImportRules.read(link(small))
        val c = assertNotNull(r.course, r.line)
        assertEquals("Data analytics", c.title)
        assertEquals(2, c.modules)
        assertEquals(
            listOf(
                StudyEntry("1 · Foundations", "Welcome", 10),
                StudyEntry("1 · Foundations", "Spreadsheets refresher", 45),
                StudyEntry("Core skills", "Pivot tables", 90),
                StudyEntry("Core skills", "Charts", 40),
            ),
            c.entries,
        )
    }

    @Test
    fun aStoredBlockAndALongerDynamicOneReadToo() {
        assertEquals(StudyImportRules.read(link(small)).course, StudyImportRules.read(link(stored)).course)
        val c = assertNotNull(StudyImportRules.read(link(big)).course)
        assertEquals("Cloud basics", c.title)
        assertEquals(10, c.modules)
        assertEquals(70, c.entries.size)
        assertEquals(StudyEntry("Section 10", "Lesson 10.7 about topic number 7", 15), c.entries.last())
    }

    @Test
    fun theQuestionNamesTheCourseItsModulesAndLessonsAndSaysWhenItIsThereAlready() {
        val r = StudyImportRules.read(link(small))
        val o = StudyImportRules.offer(r, emptyList())
        assertTrue(o.ok)
        assertEquals("Add Data analytics · 2 modules · 4 lessons?", o.line)
        assertFalse(o.again)
        val again = StudyImportRules.offer(r, listOf("data analytics"))
        assertTrue(again.again)
        assertEquals("You already have Data analytics · add it again with 2 modules · 4 lessons?", again.line)
    }

    @Test
    fun theJsonSharedAsTextAndAnUnzippedLinkReadTheSame() {
        val json = """{"title":"Sam's course","modules":[{"title":"Basics","lessons":["One","Two \u00b7 5 min"]}]}"""
        assertTrue(StudyImportRules.isImport(json))
        val c = assertNotNull(StudyImportRules.read(json).course)
        assertEquals(listOf(StudyEntry("Basics", "One", null), StudyEntry("Basics", "Two", 5)), c.entries)
        val made = StudyImportRules.link("Sam's course", listOf("Basics" to listOf("One", "Two · 5 min")))
        assertTrue(StudyImportRules.isImport(made))
        assertEquals(c, StudyImportRules.read(made).course)
        assertFalse(StudyImportRules.isImport("Buy milk"))
        assertFalse(StudyImportRules.isImport("https://example.com/study/import"))
    }

    @Test
    fun anythingMalformedOrTooBigIsAPlainLineAndNothingElse() {
        assertEquals(StudyImportRules.NOT_READ, StudyImportRules.read("navilife://study/import?v=1").line)
        assertEquals(StudyImportRules.NOT_READ, StudyImportRules.read(link("!!!")).line)
        assertEquals(StudyImportRules.NOT_READ, StudyImportRules.read(link(small.dropLast(20))).line)
        assertEquals(StudyImportRules.NOT_READ, StudyImportRules.read("{\"title\":\"X\",\"modules\": [1, 2]}").line)
        assertEquals(StudyImportRules.NO_TITLE, StudyImportRules.read("{\"modules\":[]}").line)
        assertEquals(StudyImportRules.NO_LESSONS, StudyImportRules.read("{\"title\":\"X\",\"modules\":[{\"title\":\"A\",\"lessons\":[]}]}").line)
        assertEquals(StudyImportRules.VERSION, StudyImportRules.read("navilife://study/import?v=2&d=abc").line)
        assertEquals(StudyImportRules.TOO_BIG, StudyImportRules.read(link("A".repeat(33 * 1024))).line)
        val many = StudyImportRules.link("Huge", listOf("A" to List(201) { "Lesson $it" }))
        assertEquals(StudyImportRules.TOO_MANY, StudyImportRules.read(many).line)
        val refused = StudyImportRules.offer(StudyImportRules.read("{nope"), emptyList())
        assertFalse(refused.ok)
        assertEquals(StudyImportRules.NOT_READ, refused.line)
    }

    @Test
    fun addWritesOneCourseWithItsLessonsInOrderAndTheMacSeesIt() {
        val c = assertNotNull(StudyImportRules.read(link(small)).course)
        val added = sFold.importCourse(c)
        assertFalse(added.refused)
        assertEquals("Added Data analytics with 4 lessons", added.line)
        val id = assertNotNull(added.courseId)
        fold.sync(); mac.sync()
        val course = sMac.courses().single()
        assertEquals("Data analytics", course.title)
        val lessons = sMac.lessons().filter { it.courseId == id }.sortedBy { it.pos }
        assertEquals(listOf("Welcome", "Spreadsheets refresher", "Pivot tables", "Charts"), lessons.map { it.title })
        assertEquals(listOf("1 · Foundations", "1 · Foundations", "Core skills", "Core skills"), lessons.map { it.module })
        assertNull(lessons.firstOrNull { it.done })
    }
}
