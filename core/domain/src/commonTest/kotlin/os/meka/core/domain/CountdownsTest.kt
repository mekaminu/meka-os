package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Countdowns, slice 1: typed countdowns, the ones from Gifts, Trips and Christmas, Days or Sleeps, the one on Today. */
class CountdownsTest {
    private val hour = 3_600_000L
    private val world = SyncWorld()
    private val cal = LocalCalendar.fixedOffset(0)
    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)
    private val thu = d(11, 12)
    private var n = 0
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private val cFold = Countdowns(fold.replica, { "c${n++}" }, { world.clock.nowMs }, cal)
    private val cMac = Countdowns(mac.replica, { "c${n++}" }, { world.clock.nowMs }, cal)

    init { at(thu) }

    private fun at(day: Long, minute: Int = 10 * 60) { world.clock.nowMs = cal.toEpochMs(day, minute) }
    private fun sync() { fold.sync(); mac.sync(); fold.sync() }
    private fun view(c: Countdowns = cFold, occasions: List<Occasion> = emptyList(), trips: List<TripItem> = emptyList(), today: Long = thu) =
        CountdownRules.view(c.items(), occasions, trips, c.settings(), today)

    @Test
    fun aCountdownIsReadFromOneLine() {
        assertEquals(CountdownEntry("Holiday", 2, 13, 2027), CountdownRules.read("Holiday 13 Feb", thu))
        assertEquals(CountdownEntry("Holiday", 2, 13, 2027), CountdownRules.read("13th February: holiday", thu))
        assertEquals(CountdownEntry("Sports day", 7, 3, 2027), CountdownRules.read("Sports day 3/7/2027", thu))
        assertEquals(CountdownEntry("Sports day", 7, 3, 2027), CountdownRules.read("Sports day 3 July 2027", thu))
        assertEquals(CountdownEntry("Pancake day", 2, 17, 0), CountdownRules.read("Pancake day 17 Feb every year", thu))
        assertEquals(CountdownEntry("Back to school", 1, 5, 2027), CountdownRules.read("Back to school on 5 Jan", thu))
        // Later this year stays this year; today counts.
        assertEquals(CountdownEntry("Bonfire", 11, 12, 2026), CountdownRules.read("bonfire 12 nov", thu))
        assertEquals(CountdownEntry("", 12, 1, 2026), CountdownRules.read("1 Dec", thu))
        assertNull(CountdownRules.read("Holiday soon", thu))
        assertNull(CountdownRules.read("Holiday 31 Feb", thu))
        assertNull(CountdownRules.read("Holiday 30 Feb every year", thu))
    }

    @Test
    fun addingSaysWhatItAddedAndRefusesWhatItCant() {
        assertEquals("Added Holiday · Sat 13 Feb · 93 days to go", cFold.add("Holiday 13 Feb").line)
        assertEquals("Updated Holiday · Sun 14 Feb · 94 days to go", cFold.add("holiday 14 Feb").line)
        assertEquals(1, cFold.items().size)
        assertEquals("Added Pancake day · Wed 17 Feb · 97 days to go · every year", cFold.add("Pancake day 17 Feb every year").line)
        assertEquals(CountdownAdded(CountdownRules.NOT_READ, refused = true), cFold.add("Holiday soon"))
        assertEquals(CountdownAdded(CountdownRules.NO_NAME, refused = true), cFold.add("13 Feb"))
        assertEquals(CountdownAdded(CountdownRules.PASSED, refused = true), cFold.add("Fireworks 5 Nov 2026"))
        assertEquals(2, cFold.items().size)
    }

    @Test
    fun theSoonestLeadsAndChristmasIsAlwaysThere() {
        val empty = view()
        assertEquals("Christmas", empty.hero?.name)
        assertEquals("43", empty.hero?.number)
        assertEquals("days", empty.hero?.unit)
        assertEquals("Fri 25 Dec", empty.hero?.dateLine)
        assertEquals(CountdownIcon.GIFT, empty.hero?.icon)
        assertFalse(empty.heroOnToday)
        assertEquals(LifeTileLine("Christmas in 43 days", lit = false), empty.tileLine)

        cFold.add("Holiday 13 Feb")
        cFold.add("School breaks up 18 Dec")
        val v = view(
            occasions = listOf(Occasion("o1", "Sam", OccasionKind.BIRTHDAY, 12, 2, 2017, 3, 0, 0)),
            trips = listOf(TripItem("t1", "Lisbon", d(3, 30, 2027), d(4, 3, 2027), 0)),
        )
        assertEquals("Sam's birthday", v.hero?.name)
        assertEquals("20", v.hero?.number)
        assertEquals("turns 9", v.hero?.extra)
        assertEquals(CountdownIcon.CAKE, v.hero?.icon)
        assertEquals(listOf("School breaks up", "Christmas", "Holiday", "Lisbon"), v.rows.map { it.name })
        assertEquals(CountdownIcon.PLANE, v.rows[2].icon)
        assertEquals("4 nights", v.rows[3].extra)
        // The ring fills as the day gets closer.
        assertTrue(v.hero!!.fill > v.rows[1].fill)
        assertTrue(v.rows.all { it.fill in 0.0..1.0 })
        // A typed Christmas replaces the built-in one.
        cFold.add("Christmas 25 Dec every year")
        assertEquals(1, view().let { listOfNotNull(it.hero) + it.rows }.count { it.name == "Christmas" })
    }

    @Test
    fun sleepsForTheChildrenAndTheDayItself() {
        cFold.add("Holiday 13 Nov")
        assertTrue(cFold.setSleeps(true))
        assertFalse(cFold.setSleeps(true))
        val v = view()
        assertTrue(v.sleeps)
        assertEquals("1", v.hero?.number)
        assertEquals("sleep", v.hero?.unit)
        assertEquals("43 sleeps", v.rows.first().let { "${it.number} ${it.unit}" })
        assertEquals(LifeTileLine("Holiday · 1 sleep", lit = false), v.tileLine)
        val onTheDay = view(today = d(11, 13))
        assertEquals("Today", onTheDay.hero?.number)
        assertEquals("", onTheDay.hero?.unit)
        assertEquals(1.0, onTheDay.hero?.fill)
        assertEquals(LifeTileLine("Holiday today", lit = true), onTheDay.tileLine)
        // A one-off that has passed leaves the screen.
        assertTrue(view(today = d(11, 14)).let { listOfNotNull(it.hero) + it.rows }.none { it.name == "Holiday" })
    }

    @Test
    fun oneShowsOnTodayAndAutomaticOnesHideAndComeBack() {
        cFold.add("Holiday 13 Feb")
        val holiday = cFold.items().single()
        assertTrue(cFold.pin(CountdownRules.ownKey(holiday.id)))
        val v = view()
        assertEquals("Holiday", v.hero?.name)
        assertTrue(v.heroOnToday)
        assertEquals(listOf("Christmas"), v.rows.map { it.name })
        assertEquals(LifeTileLine("Holiday in 93 days", lit = false), v.tileLine)

        assertTrue(cFold.remove(CountdownRules.CHRISTMAS_KEY))
        assertFalse(cFold.remove(CountdownRules.CHRISTMAS_KEY))
        assertEquals(emptyList(), view().rows)
        assertEquals(1, view().hiddenCount)
        assertTrue(cFold.showHidden())
        assertEquals(0, view().hiddenCount)
        assertEquals(listOf("Christmas"), view().rows.map { it.name })

        // Removing the one on Today: gone for good, and Today falls back to the soonest.
        assertTrue(cFold.remove(CountdownRules.ownKey(holiday.id)))
        assertEquals("", cFold.settings().pinnedKey)
        assertEquals("Christmas", view().hero?.name)
        assertFalse(cFold.remove("c:nope"))
        assertFalse(cFold.remove("nonsense"))
    }

    @Test
    fun countdownsAndTheChoicesSyncToTheMac() {
        cFold.add("Holiday 13 Feb")
        cFold.setSleeps(true)
        cFold.pin(CountdownRules.CHRISTMAS_KEY)
        sync()
        val v = view(c = cMac)
        assertTrue(v.sleeps)
        assertEquals("Christmas", v.hero?.name)
        assertTrue(v.heroOnToday)
        assertEquals(listOf("Holiday"), v.rows.map { it.name })
        // Removed on the Mac while the Fold renames it: removed wins.
        val id = cMac.items().single().id
        cMac.remove(CountdownRules.ownKey(id))
        cFold.add("Holiday 20 Feb")
        sync()
        assertTrue(cFold.items().isEmpty())
        assertTrue(cMac.items().isEmpty())
    }

    @Test
    fun aNameSuggestsItsIcon() {
        assertEquals(CountdownIcon.GIFT, CountdownRules.iconFor("Christmas Eve"))
        assertEquals(CountdownIcon.CAKE, CountdownRules.iconFor("Sam's birthday party"))
        assertEquals(CountdownIcon.HEART, CountdownRules.iconFor("Our anniversary"))
        assertEquals(CountdownIcon.PLANE, CountdownRules.iconFor("Holiday"))
        assertEquals(CountdownIcon.STAR, CountdownRules.iconFor("Sports day"))
    }

    /** Slice 2: the one chosen with Show on Today is an Also today tile; nothing is on Today until one is chosen. */
    @Test
    fun theChosenCountdownIsAnAlsoTodayTile() {
        assertNull(CountdownRules.alsoTile(view()), "the soonest only leads Countdowns; Today waits to be asked")
        cFold.add("Holiday 13 Feb")
        assertNull(CountdownRules.alsoTile(view()))
        assertTrue(cFold.pin(CountdownRules.CHRISTMAS_KEY))
        val t = CountdownRules.alsoTile(view())!!
        assertEquals(AlsoKind.COUNTDOWN, t.kind)
        assertEquals("COUNTDOWN", t.key)
        assertEquals("Christmas", t.value)
        assertEquals("43 days to go · Fri 25 Dec", t.line)
        assertFalse(t.lit)
        assertEquals("Countdown: Christmas, 43 days to go, Fri 25 Dec. Opens Countdowns.", t.spoken)
        assertEquals(AlsoPlace.COUNTDOWNS, AlsoTodayRules.place(t))
        assertEquals("Opens Countdowns", AlsoTodayRules.hint(t))

        // Sleeps for the boys; the day before; on the day it's lit.
        cFold.setSleeps(true)
        assertEquals("43 sleeps to go · Fri 25 Dec", CountdownRules.alsoTile(view())!!.line)
        assertEquals("1 sleep to go · Fri 25 Dec", CountdownRules.alsoTile(view(today = d(12, 24)))!!.line)
        cFold.setSleeps(false)
        assertEquals("tomorrow · Fri 25 Dec", CountdownRules.alsoTile(view(today = d(12, 24)))!!.line)
        val onTheDay = CountdownRules.alsoTile(view(today = d(12, 25)))!!
        assertEquals("today", onTheDay.line)
        assertTrue(onTheDay.lit)
        assertEquals("Countdown: Christmas, today. Opens Countdowns.", onTheDay.spoken)

        // A birthday says the age after the date.
        val o = Occasion("o1", "Sam", OccasionKind.BIRTHDAY, 12, 2, 2017, 3, 0, 0)
        cFold.pin(CountdownRules.giftKey("o1"))
        assertEquals("20 days to go · Wed 2 Dec · turns 9", CountdownRules.alsoTile(view(occasions = listOf(o)))!!.line)

        // Set back to none: Today has no countdown again.
        cFold.pin("")
        assertNull(CountdownRules.alsoTile(view()))
    }

    private fun off(id: String, title: String, start: Long, end: Long, who: String = "") =
        SchoolItem(id, SchoolKind.OFF, title, who, start, end, 0, null, emptyList(), 0L)

    @Test
    fun theNextSchoolHolidayAppearsWithItsBreakingUpDay() {
        // An INSET day doesn't count; the holiday from Mon 21 Dec breaks up the Friday before; the one after waits.
        val school = listOf(
            off("inset", "INSET day", d(11, 27), d(11, 27)),
            off("xmas", "Christmas holidays", d(12, 21), d(1, 1, 2027)),
            off("feb", "February half term", d(2, 15, 2027), d(2, 19, 2027)),
            SchoolItem("pe", SchoolKind.WEEKLY, "PE", "Alex", thu, thu, 2, null, emptyList(), 0L),
        )
        val v = CountdownRules.view(cFold.items(), emptyList(), emptyList(), cFold.settings(), thu, school)
        val all = listOfNotNull(v.hero) + v.rows
        val hol = all.single { it.source == CountdownSource.SCHOOL }
        assertEquals(CountdownRules.schoolKey("xmas"), hol.key)
        assertEquals("Christmas holidays", hol.name)
        assertEquals("Mon 21 Dec", hol.dateLine)
        assertEquals(39L, hol.days)
        assertEquals("breaks up Fri 18 Dec", hol.extra)
        assertFalse(hol.canRemove)
        assertEquals(CountdownIcon.GIFT, hol.icon)
        // It leads (sooner than Christmas) and Christmas is still there.
        assertEquals(hol.key, v.hero!!.key)
        assertTrue(v.rows.any { it.key == CountdownRules.CHRISTMAS_KEY })

        // Once it has started, the next holiday takes its place; on its first day, breaking up has passed.
        val later = CountdownRules.view(cFold.items(), emptyList(), emptyList(), cFold.settings(), d(12, 22), school)
        val next = (listOfNotNull(later.hero) + later.rows).single { it.source == CountdownSource.SCHOOL }
        assertEquals("February half term", next.name)
        assertEquals("breaks up Fri 12 Feb", next.extra)
        val first = CountdownRules.view(cFold.items(), emptyList(), emptyList(), cFold.settings(), d(2, 15, 2027), school)
        val onIt = (listOfNotNull(first.hero) + first.rows).single { it.source == CountdownSource.SCHOOL }
        assertEquals("Today", onIt.number)
        assertNull(onIt.extra)
    }

    @Test
    fun oneBoysHolidayIsNamedAndBothBoysWinOnTheSameDay() {
        val one = listOf(off("u7", "Trip week", d(12, 7), d(12, 11), who = "Sam"))
        val v = CountdownRules.view(cFold.items(), emptyList(), emptyList(), cFold.settings(), thu, one)
        assertEquals("breaks up Fri 4 Dec · Sam only", v.hero!!.extra)
        val both = one + off("both", "Winter break", d(12, 7), d(12, 18))
        val v2 = CountdownRules.view(cFold.items(), emptyList(), emptyList(), cFold.settings(), thu, both)
        assertEquals(CountdownRules.schoolKey("both"), v2.hero!!.key)
    }

    @Test
    fun theSchoolHolidayCanBeHiddenShownAgainAndPutOnToday() {
        val school = listOf(off("xmas", "Christmas holidays", d(12, 21), d(1, 1, 2027)))
        fun v() = CountdownRules.view(cFold.items(), emptyList(), emptyList(), cFold.settings(), thu, school)
        val key = CountdownRules.schoolKey("xmas")
        cFold.pin(key)
        assertEquals("39 days to go · Mon 21 Dec · breaks up Fri 18 Dec", CountdownRules.alsoTile(v())!!.line)
        assertTrue(cFold.remove(key))
        assertTrue((listOfNotNull(v().hero) + v().rows).none { it.key == key })
        assertNull(CountdownRules.alsoTile(v()))
        assertEquals(1, v().hiddenCount)
        assertTrue(cFold.showHidden())
        assertTrue((listOfNotNull(v().hero) + v().rows).any { it.key == key })
    }

    @Test
    fun theWidgetSaysTodayTomorrowAndAsksWhenThereIsNothing() {
        // Countdowns, slice 4: the home-screen widget.
        cFold.add("Bonfire 13 Nov")
        cFold.add("Fair 12 Nov")
        val w = CountdownWidgetRules.view(view())
        assertEquals("Fair", w.name)
        assertEquals("Today", w.number)
        assertEquals("", w.unit)
        assertTrue(w.lit)
        assertEquals(100, w.ringPercent)
        assertEquals(listOf("Bonfire" to "tomorrow", "Christmas" to "43 days"), w.next.map { it.name to it.count })
        assertTrue(w.spoken.startsWith("Countdown. Fair, today. Then Bonfire, tomorrow."))
        assertEquals(1, CountdownWidgetRules.view(view(), next = 1).next.size)
        // With Sleeps on, the day before is "1 sleep".
        cFold.setSleeps(true)
        assertEquals("1 sleep", CountdownWidgetRules.view(view()).next.first().count)
        // Nothing at all (Christmas hidden, nothing typed): the widget asks for one.
        val none = CountdownWidgetRules.view(CountdownsView.EMPTY)
        assertTrue(none.empty)
        assertEquals("Countdowns. ${CountdownWidgetRules.EMPTY}. ${CountdownRules.OPENS}", none.spoken)
        assertEquals(CountdownWidgetRules.DESK_EMPTY_HINT, CountdownWidgetRules.emptyHint(desk = true))
    }

    /** Obi reads them: a question about a countdown carries every one, soonest first; anything else the counts line. */
    @Test
    fun obiReadsEveryCountdownWhenAskedAndOneLineOtherwise() {
        assertEquals(emptyList(), CountdownAskRules.askLines(CountdownsView.EMPTY, full = true))
        // Christmas alone sends nothing unless asked; asked, it's there.
        assertEquals(emptyList(), CountdownAskRules.askLines(view(), full = false))
        assertEquals("Christmas · Fri 25 Dec · in 43 days", CountdownAskRules.askLines(view(), full = true).last())
        cFold.add("Holiday 13 Feb")
        val sam = Occasion("o1", "Sam", OccasionKind.BIRTHDAY, 12, 2, 2017, 3, 0, 0)
        var v = view(occasions = listOf(sam))
        val soonest = v.hero!!.name
        assertEquals(listOf("Countdowns · 3 · the soonest: $soonest in 20 days"), CountdownAskRules.askLines(v, full = false))
        assertEquals(listOf(
            "Countdowns · 3 · the soonest: $soonest in 20 days",
            "$soonest · Wed 2 Dec · in 20 days · from Gifts · turns 9",
            "Christmas · Fri 25 Dec · in 43 days",
            "Holiday · Sat 13 Feb · in 93 days · typed",
        ), CountdownAskRules.askLines(v, full = true))
        // The one on Today leads the counts line and is marked; Sleeps is said.
        cFold.pin(v.rows.first { it.name == "Holiday" }.key)
        cFold.setSleeps(true)
        v = view(occasions = listOf(sam))
        val lines = CountdownAskRules.askLines(v, full = true)
        assertEquals("Countdowns · 3 · on Today: Holiday in 93 days · counted in sleeps (a sleep a day)", lines.first())
        assertEquals("Holiday · Sat 13 Feb · in 93 days · typed · on Today", lines.last())
        assertEquals(4, lines.size)
        // What counts as asked.
        val names = CountdownAskRules.names(v)
        listOf("How many sleeps till Christmas?", "how many days until the holiday", "Show my countdowns", "When's the holiday?", "how long until we go?")
            .forEach { assertTrue(CountdownAskRules.asked(it, names), it) }
        listOf("Why is the sky blue?", "What's on today?").forEach { assertFalse(CountdownAskRules.asked(it, names), it) }
    }
}
