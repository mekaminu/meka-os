package os.meka.core.domain

import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** School dates filled in, slice 1: a council's holidays on his pick, marked with their source, and pasted INSET days. */
class SchoolTermsTest {
    private val hour = 3_600_000L
    private val world = SyncWorld()
    private val cal = LocalCalendar.fixedOffset(hour)
    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)
    private val wed = d(10, 14)
    private fun at(day: Long, h: Int) = cal.toEpochMs(day, h * 60)
    private var n = 0
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private fun school(dev: Device) = School(dev.replica, { "sch${n++}" }, { world.clock.nowMs }, cal)
    private fun terms(dev: Device) = SchoolTermsStore(dev.replica, { "sch${n++}" }, { world.clock.nowMs }, cal)
    private val work = WorkHours(WorkSchedule.DEFAULT)
    private val council = SchoolTerms.CENTRAL_BEDFORDSHIRE.id

    init { world.clock.nowMs = at(wed, 10) }

    private fun sync() { fold.sync(); mac.sync(); fold.sync() }

    @Test
    fun theTableIsInOrderWithinTheSchoolYearsAndKeepsTheCouncilsOwnDays() {
        val p = SchoolTerms.CENTRAL_BEDFORDSHIRE.periods
        assertTrue(p.zipWithNext().all { (a, b) -> a.endDay < b.startDay })
        assertTrue(p.all { it.endDay >= it.startDay && it.endDay - it.startDay < 60 })
        assertEquals(d(10, 26) to d(10, 30), p.first().let { it.startDay to it.endDay })
        // The council's own training days are there; no other INSET day is made up.
        assertEquals(listOf(d(4, 12, 2027), d(6, 7, 2027)), p.filter { it.title == "Training day" }.map { it.startDay })
        assertEquals(d(10, 14), SchoolTerms.CENTRAL_BEDFORDSHIRE.checkedDay)
    }

    @Test
    fun nothingIsFilledInUntilHePicksTheCouncilThenEachHolidayOnceOnBothApps() {
        val v0 = school(fold).view(work)
        assertTrue(v0.isEmpty)
        val card = assertNotNull(v0.terms)
        assertNull(card.filled)
        assertEquals(listOf("Central Bedfordshire"), card.choices.map { it.name })
        assertEquals("2026–27 and 2027–28 · 14 dates · checked 14 Oct 2026", card.choices.single().line)

        val (line, count) = assertNotNull(terms(fold).fill(council))
        assertEquals(14, count)
        assertEquals("Filled in 14 holidays from Central Bedfordshire · add the school's INSET days below", line)
        val v = school(fold).view(work)
        assertEquals("October half term", v.off.first().title)
        assertEquals("Mon 26 – Fri 30 Oct · Rex and Logan", v.off.first().line)
        assertEquals("Council dates · checked 14 Oct 2026 · check with the school", v.off.first().source)
        assertEquals("Central Bedfordshire", v.terms?.filled?.name)
        assertTrue(v.terms!!.line.startsWith("Central Bedfordshire · 14 holidays to come"), v.terms!!.line)

        // Filling in again (or on the Mac at the same time) adds nothing twice.
        assertEquals(SchoolTerms.NOT_FILLED to 0, terms(fold).fill(council))
        assertEquals(14, terms(mac).fill(council)?.second)
        sync()
        assertEquals(14, school(mac).items().size)
        assertEquals(14, school(fold).items().size)
        assertNull(terms(fold).fill("somewhere-else"))
    }

    @Test
    fun hisTypedDaysOffWinAndRemoveAllThenFillAgainBringsThemBack() {
        school(fold).add("Half term 26–30 Oct")
        assertEquals(13, terms(fold).fill(council)?.second)
        val off = school(fold).items().filter { it.startDay == d(10, 26) }
        assertEquals(listOf<String?>(null), off.map { it.source })

        assertEquals(13, terms(fold).removeAll(council))
        assertEquals(1, school(fold).items().size)
        assertNull(school(fold).view(work).terms?.filled)
        // Undo: filled in again under new ids (the removed ones stay removed on every device).
        assertEquals(13, terms(fold).fill(council)?.second)
        sync()
        assertEquals(14, school(mac).items().size)
    }

    @Test
    fun onlyHolidaysStillToComeAreFilledInAndTheCoverQuestionFollows() {
        world.clock.nowMs = at(d(3, 1, 2027), 10)
        assertEquals(11, terms(fold).fill(council)?.second) // from Easter 2027
        world.clock.nowMs = at(d(10, 19), 10)
        val fresh = SyncWorld().device("x")
        val s = School(fresh.replica, { "f${n++}" }, { world.clock.nowMs }, cal)
        SchoolTermsStore(fresh.replica, { "f${n++}" }, { world.clock.nowMs }, cal).fill(council)
        val cover = s.view(work).covers.single()
        assertEquals("Rex and Logan are off Mon 26 – Fri 30 Oct", cover.title)
        assertEquals("October half term · in 7 days", cover.line)
        assertTrue(SchoolRules.askLines(s.items(), d(10, 19), HolidayCalendar.NONE).first().endsWith("council's date, the school may differ"))
    }

    @Test
    fun insetDaysArePastedFromAListOrTheSchoolsLetter() {
        val letter = "Dear parents, our INSET days this year are Monday 2 November, 4 January and 12–13 April. " +
            "School closes on 18/12 as usual."
        val found = SchoolTerms.readInset(letter, wed)
        assertEquals(
            listOf(d(11, 2) to d(11, 2), d(12, 18) to d(12, 18), d(1, 4, 2027) to d(1, 4, 2027), d(4, 12, 2027) to d(4, 13, 2027)),
            found.map { it.startDay to it.endDay },
        )
        assertEquals(listOf("INSET day", "INSET day", "INSET day", "INSET days"), found.map { it.title })
        assertTrue(found.all { it.kind == SchoolKind.OFF && it.who.isEmpty() })
        assertTrue(SchoolTerms.readInset("no dates here", wed).isEmpty())
        // A date already gone this year with its year typed is skipped, not the whole paste.
        assertEquals(listOf(d(11, 2)), SchoolTerms.readInset("1 Sep 2026, 2 Nov", wed).map { it.startDay })

        assertNull(terms(fold).addInset("nothing"))
        terms(fold).fill(council)
        // 4 Jan is in the council's Christmas holidays already.
        assertEquals("Added 2 INSET days · Mon 2 Nov, Fri 18 Dec · 1 already in", terms(fold).addInset("2 Nov, 18 Dec, 4 Jan"))
        assertEquals(16, school(fold).items().size)
    }
}
