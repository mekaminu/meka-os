package os.meka.core.domain

import os.meka.core.testing.Device
import os.meka.core.testing.InventedCouncil
import os.meka.core.testing.SyncWorld
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** School dates filled in, slice 1: a council's holidays on his pick, marked with their source, and pasted INSET days. */
class SchoolTermsTest {
    @BeforeTest
    fun household() {
        // Rule 13, release 2: the children and clubs come from About you; these are invented.
        Household.useTyped("Max, U7\nFelix, U10", "OTFC Lindenbrook United, HRFC")
        // Release 2 slice 5: the council's table is his data too; this one is invented.
        Household.useCouncil(InventedCouncil.OAKFIELD)
    }

    @AfterTest
    fun noHousehold() { Household.set(HouseholdNames.EMPTY) }

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
    private val council = InventedCouncil.OAKFIELD.id

    init { world.clock.nowMs = at(wed, 10) }

    private fun sync() { fold.sync(); mac.sync(); fold.sync() }

    @Test
    fun theTableIsInOrderWithinTheSchoolYearsAndKeepsTheCouncilsOwnDays() {
        val p = InventedCouncil.OAKFIELD.periods
        assertTrue(p.zipWithNext().all { (a, b) -> a.endDay < b.startDay })
        assertTrue(p.all { it.endDay >= it.startDay && it.endDay - it.startDay < 60 })
        assertEquals(d(10, 26) to d(10, 30), p.first().let { it.startDay to it.endDay })
        // The council's own training days are there; no other INSET day is made up.
        assertEquals(listOf(d(4, 12, 2027), d(6, 7, 2027)), p.filter { it.title == "Training day" }.map { it.startDay })
        assertEquals(d(10, 14), InventedCouncil.OAKFIELD.checkedDay)
    }

    @Test
    fun nothingIsFilledInUntilHePicksTheCouncilThenEachHolidayOnceOnBothApps() {
        val v0 = school(fold).view(work)
        assertTrue(v0.isEmpty)
        val card = assertNotNull(v0.terms)
        assertNull(card.filled)
        assertEquals(listOf("Oakfield"), card.choices.map { it.name })
        assertEquals("2026–27 and 2027–28 · 14 dates · checked 14 Oct 2026", card.choices.single().line)

        val (line, count) = assertNotNull(terms(fold).fill(council))
        assertEquals(14, count)
        assertEquals("Filled in 14 holidays from Oakfield · add the school's INSET days below", line)
        val v = school(fold).view(work)
        assertEquals("October half term", v.off.first().title)
        assertEquals("Mon 26 – Fri 30 Oct · Max and Felix", v.off.first().line)
        assertEquals("Council dates · checked 14 Oct 2026 · check with the school", v.off.first().source)
        assertEquals("Oakfield", v.terms?.filled?.name)
        assertTrue(v.terms!!.line.startsWith("Oakfield · 14 holidays to come"), v.terms!!.line)

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
        assertEquals("Max and Felix are off Mon 26 – Fri 30 Oct", cover.title)
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

    // ---- Slice 2: the yearly look at next year's dates ----

    @Test
    fun newDatesInALaterTableAreOfferedOnceAndAHolidayHeRemovedStaysRemoved() {
        val cal = InventedCouncil.OAKFIELD
        // As if an older Navilife had filled in 2026–27 only: the table's 2027–28 dates are new.
        cal.periods.filter { SchoolTerms.schoolYearOf(it.startDay) == 2026 }.forEach { p ->
            fold.replica.commitLocal(EntityTypes.SCHOOL_ITEM, SchoolTerms.idFor(cal, p), SchoolTerms.fields(cal, p, world.clock.nowMs))
        }
        // He removed one of next year's from another device before (its fixed id is gone for good).
        val removed = cal.periods.first { SchoolTerms.schoolYearOf(it.startDay) == 2027 }
        fold.replica.commitLocal(EntityTypes.SCHOOL_ITEM, SchoolTerms.idFor(cal, removed), SchoolTerms.fields(cal, removed, world.clock.nowMs))
        school(fold).remove(SchoolTerms.idFor(cal, removed))

        val r = assertNotNull(school(fold).view(work).terms?.refresh)
        assertEquals("New dates from Oakfield · 2027–28 · 5 holidays", r.line)
        assertEquals(SchoolTerms.ADD_THEM, r.action)
        assertEquals(council, r.councilId)

        val added = assertNotNull(terms(fold).addNew(council))
        assertEquals("Added 5 new holidays from Oakfield · 2027–28", added.line)
        assertNull(school(fold).view(work).terms?.refresh)
        assertNull(terms(fold).addNew(council))
        assertTrue(school(fold).items().none { it.startDay == removed.startDay })
        sync()
        assertEquals(13, school(mac).items().size)

        // Undo takes exactly those out; nothing else goes.
        assertEquals(5, terms(mac).removeIds(added.ids))
        assertEquals(8, school(mac).items().size)
    }

    @Test
    fun fromJuneNextSchoolYearsHolidaysNotInYetAreAskedForOnceUntilOneIsIn() {
        assertEquals(2026, SchoolTerms.schoolYearOf(wed))
        assertEquals(2026, SchoolTerms.schoolYearOf(d(6, 3, 2027)))
        assertEquals("2027–28", SchoolTerms.yearLabel(2027))
        assertEquals("2099–00", SchoolTerms.yearLabel(2099))

        // Nothing in School: nothing asked, at any time.
        world.clock.nowMs = at(d(6, 10, 2027), 10)
        assertNull(school(fold).view(work).terms?.refresh)
        assertTrue(SchoolTerms.notices(school(fold).items(), world.clock.nowMs, cal).isEmpty())

        // Only this year's holidays typed: in May nothing; from 1 June, the line and one heads-up until 1 September.
        world.clock.nowMs = at(d(10, 14), 10)
        school(fold).add("Half term 31 May – 4 Jun 2027")
        world.clock.nowMs = at(d(5, 31, 2027), 10)
        assertNull(school(fold).view(work).terms?.refresh)
        assertTrue(SchoolTerms.notices(school(fold).items(), world.clock.nowMs, cal).isEmpty())
        world.clock.nowMs = at(d(6, 10, 2027), 10)
        val r = assertNotNull(school(fold).view(work).terms?.refresh)
        // The council's table has 2027–28, so the card points at it.
        assertEquals("2027–28's holidays aren't in yet · pick the school's council above, or type them below", r.line)
        assertNull(r.action)
        val n = SchoolTerms.notices(school(fold).items(), world.clock.nowMs, cal).single()
        assertEquals("school:year:2027", n.key)
        assertEquals("2027–28's school holidays aren't in yet", n.title)
        assertEquals(at(d(6, 1, 2027), 9), n.atMs)
        assertEquals(cal.toEpochMs(d(9, 1, 2027), 0), n.expiresAtMs)

        // One day off in next school year quietens it.
        school(fold).add("INSET 6 Sep 2027")
        assertNull(school(fold).view(work).terms?.refresh)
        assertTrue(SchoolTerms.notices(school(fold).items(), world.clock.nowMs, cal).isEmpty())
    }

    @Test
    fun withTheCouncilsDatesInTheLookPointsAtTheCouncilsPageOnceTheTableRunsOut() {
        terms(fold).fill(council)
        // June 2027: 2027–28 is in from the table, so nothing to say.
        world.clock.nowMs = at(d(6, 10, 2027), 10)
        assertNull(school(fold).view(work).terms?.refresh)
        // June 2028: 2028–29 isn't in this table: check the council's page.
        world.clock.nowMs = at(d(6, 10, 2028), 10)
        val r = assertNotNull(school(fold).view(work).terms?.refresh)
        assertTrue(r.line.startsWith("2028–29's holidays aren't in yet · Oakfield"), r.line)
        assertEquals(SchoolTerms.COUNCIL_PAGE, r.action)
        assertEquals(InventedCouncil.OAKFIELD.sourceUrl, r.url)
        val n = SchoolTerms.notices(school(fold).items(), world.clock.nowMs, cal).single()
        assertEquals("Check Oakfield's dates and the school's INSET days · Ask → More → School", n.text)
    }

    // ---- Release 2 slice 5: the table lives in his data ----

    @Test
    fun withNoCouncilInHisDataTheCardOffersNoneAndSaysToTypeTheDates() {
        Household.useCouncil(null)
        val card = assertNotNull(school(fold).view(work).terms)
        assertTrue(card.choices.isEmpty())
        assertNull(card.filled)
        // Nothing to pick, so the line doesn't ask him to pick one (quality gate slice 16's findings).
        assertEquals(SchoolTerms.NO_COUNCIL_LINE, card.line)
        assertFalse(card.line.contains("Pick"))
        assertNull(terms(fold).fill(council))
        assertTrue(SchoolTerms.councils().isEmpty())
    }

    @Test
    fun theTableRoundTripsThroughTheProfileTextAndBadLinesAreSkipped() {
        val cal = InventedCouncil.OAKFIELD
        assertEquals(cal, SchoolTerms.decode(SchoolTerms.encode(cal)))
        assertNull(SchoolTerms.decode(null))
        assertNull(SchoolTerms.decode("Oakfield"))
        assertNull(SchoolTerms.decode("Oak Field|Oakfield|https://x.example|1|y"))
        assertNull(SchoolTerms.decode("oakfield|Oakfield|http://x.example|1|y"))
        val odd = SchoolTerms.decode("oakfield|Oakfield|https://x.example|1|y\nHalf term|20|10\nnope\nB|5|6\nA|1|2")!!
        assertEquals(listOf("A", "B"), odd.periods.map { it.title })
        // A '|' or line break in a name can't break the table.
        val named = cal.copy(name = "Oak|field", periods = listOf(TermPeriod("Half\nterm", 1, 2)))
        assertEquals("Oak field", SchoolTerms.decode(SchoolTerms.encode(named))?.name)
        assertEquals("Half term", SchoolTerms.decode(SchoolTerms.encode(named))?.periods?.single()?.title)
    }

    @Test
    fun theFoldCopiesTheTableIntoTheProfileOnceAndBothAppsReadIt() {
        val about = AboutYouStore(fold.replica) { world.clock.nowMs }
        // No profile yet: nothing is written.
        assertTrue(!about.seedCouncilOnce(InventedCouncil.OAKFIELD))
        about.set(AboutField.HOME, "Oakfield")
        assertTrue(about.seedCouncilOnce(InventedCouncil.OAKFIELD))
        assertTrue(!about.seedCouncilOnce(InventedCouncil.OAKFIELD.copy(name = "Elsewhere")))
        sync()
        val onMac = AboutYouStore(mac.replica) { world.clock.nowMs }.profile()
        assertEquals(InventedCouncil.OAKFIELD, onMac.councilDates)
        assertEquals(InventedCouncil.OAKFIELD, HouseholdNames.of(onMac).council)
        // Not a typed line: a profile holding only the table still counts as empty.
        assertTrue(AboutYou(councilDates = InventedCouncil.OAKFIELD).isEmpty)
        // The publish job's gate says it's copied (never what it says).
        val h = AboutYouRules.held(mac.replica.entity(EntityTypes.HOUSEHOLD_PROFILE, AboutYouRules.ENTITY_ID)?.fields)!!
        assertEquals(true, h.council)
        assertEquals(false, h.clubs)
        assertEquals("the profile is empty", AboutYouRules.gateLine(h.copy(filled = false), { "t" }))
        assertTrue(AboutYouRules.gateLine(h, { "t" }).endsWith("release 2 waits · clubs NOT copied yet · council dates copied · route NOT copied yet"))
    }
}
