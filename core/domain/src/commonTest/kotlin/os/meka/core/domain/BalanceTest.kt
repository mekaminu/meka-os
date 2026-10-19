package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Balance view, slice 1: a week's time across work, family, health and self against his targets. */
class BalanceTest {
    @BeforeTest
    fun household() { Household.useTyped("Max, U7\nFelix, U10", "", "Jordan") }

    @AfterTest
    fun noHousehold() { Household.set(HouseholdNames.EMPTY) }

    private val cal = LocalCalendar.fixedOffset(3_600_000L)
    private val mon = CivilDate.toEpochDay(2026, 10, 12)
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)
    private var n = 0
    private fun ev(title: String, day: Long, h: Int, m: Int, endH: Int, endM: Int = 0, allDay: Boolean = false, provider: String = "google") =
        CalendarEvent("e${n++}", title, at(day, h, m), at(day, endH, endM), allDay, null, provider, "a@example.com", "Personal")
    private val work = WorkHours(WorkSchedule.DEFAULT, HolidayCalendar.NONE)
    private val childOf: (CalendarEvent) -> String? = { if (it.title.contains("U7")) "Max" else null }

    private val week = listOf(
        ev("Client call", mon, 18, 0, 18, 30),
        ev("Pick up parcel", mon, 12, 0, 13),
        ev("Dentist", mon + 1, 14, 0, 15),
        ev("Gym", mon + 2, 7, 0, 8),
        ev("Gym class", mon + 2, 7, 30, 8, 15),
        ev("Date night", mon + 4, 19, 0, 22),
        ev("Max U7s v Hilltown", mon + 5, 10, 0, 11),
        ev("Jordan's dinner", mon + 5, 18, 0, 20),
        ev("Cinema", mon + 6, 15, 0, 17, 30),
        ev("Bank holiday", mon + 6, 0, 0, 23, allDay = true),
        ev("Barça v Getafe", mon + 6, 20, 0, 22, provider = "fixtures"),
    )
    private val habits = listOf(
        "Gym" to listOf(false, false, true, false, true, false, false),
        "Read 20 pages" to listOf(true, true, false, false, false, false, false),
        "Floss" to listOf(true, true, true, true, true, true, true),
    )

    private fun row(v: BalanceView, a: BalanceArea) = v.rows.first { it.area == a }

    @Test
    fun aFinishedWeekIsCountedFromTheCalendarTheHoursAndTheHabits() {
        val v = BalanceRules.view(mon, at(mon + 7, 9), cal, work, week, habits, emptyMap(), childOf)
        assertFalse(v.isCurrent)
        // Work: 40 h 30 of hours, less the dentist inside them, plus the client call after them.
        val w = row(v, BalanceArea.WORK)
        assertEquals(40 * 60, w.minutes)
        assertEquals("40 h of your 40 h 30", w.line)
        assertEquals("Within your hours", w.status)
        assertFalse(w.lit)
        // Family: the U7 match, date night and dinner with his partner.
        val f = row(v, BalanceArea.FAMILY)
        assertEquals(6 * 60, f.minutes)
        assertEquals("6 h of 8 h", f.line)
        assertEquals("2 h short", f.status)
        assertEquals("Date night · Fri · 3 h", f.items.first())
        // Health: the two overlapping gym events once, the dentist, and Friday's gym tick (Wednesday's had an event).
        val h = row(v, BalanceArea.HEALTH)
        assertEquals(75 + 60 + 45, h.minutes)
        assertEquals("Met", h.status)
        assertTrue(h.lit)
        assertEquals(100, h.pct)
        // Self: the cinema and two reading ticks; Floss names no area.
        val s = row(v, BalanceArea.SELF)
        assertEquals(150 + 60, s.minutes)
        assertEquals("3 h 30 of 2 h", s.line)
        assertEquals("2 of 3 targets met", v.line)
        assertEquals("1 event not counted · no clear area", v.uncounted)
    }

    @Test
    fun theWeekInProgressCountsOnlyWhatHasHappened() {
        val v = BalanceRules.view(mon, at(mon + 2, 12), cal, work, week, habits, emptyMap(), childOf)
        assertTrue(v.isCurrent)
        // Mon and Tue 8 h 30 each, Wednesday's first 3 h; the dentist out, the client call in.
        assertEquals(17 * 60 + 3 * 60 - 60 + 30, row(v, BalanceArea.WORK).minutes)
        assertEquals("Within your hours so far", row(v, BalanceArea.WORK).status)
        assertEquals(0, row(v, BalanceArea.FAMILY).minutes)
        assertEquals("8 h to go", row(v, BalanceArea.FAMILY).status)
        assertEquals("0 of 3 targets met so far", v.line)
        // Nothing yet in a week that hasn't started.
        assertTrue(BalanceRules.view(mon + 7, at(mon + 2, 12), cal, work, week, habits, emptyMap(), childOf).rows.all { it.minutes == 0 })
    }

    @Test
    fun targetsAreHisAndSynced() {
        val world = SyncWorld()
        val fold = world.device("android")
        val mac = world.device("mac")
        val t = BalanceTargets(fold.replica)
        assertEquals("Work: 35 h a week · on both devices", t.set(BalanceArea.WORK, 35 * 60))
        assertNull(t.set(BalanceArea.WORK, 35 * 60))
        assertNull(t.set(BalanceArea.FAMILY, 9 * 60)) // not a choice
        assertEquals("Family: 10 h a week · on both devices", t.set(BalanceArea.FAMILY, 10 * 60))
        fold.sync(); mac.sync()
        val targets = BalanceTargets(mac.replica).targets()
        assertEquals(mapOf(BalanceArea.WORK to 35 * 60, BalanceArea.FAMILY to 10 * 60), targets)
        val v = BalanceRules.view(mon, at(mon + 7, 9), cal, work, week, habits, targets, childOf)
        val w = row(v, BalanceArea.WORK)
        assertEquals("40 h of 35 h", w.line)
        assertEquals("5 h over", w.status)
        assertTrue(w.lit)
        assertFalse(w.defaultTarget)
        assertEquals("2 of 3 targets met · work 5 h over", v.line)
        assertEquals("6 h of 10 h", row(v, BalanceArea.FAMILY).line)
        // Your own hours again.
        assertEquals("Work: your own hours · on both devices", BalanceTargets(mac.replica).set(BalanceArea.WORK, BalanceRules.YOUR_HOURS))
        assertNull(BalanceTargets(mac.replica).targets()[BalanceArea.WORK])
    }

    @Test
    fun areasAreReadFromWholeWords() {
        assertEquals(BalanceArea.HEALTH, BalanceRules.area(ev("Parkrun", mon, 9, 0, 10), childOf))
        assertEquals(BalanceArea.SELF, BalanceRules.area(ev("Drinks with the lads", mon, 19, 0, 21), childOf))
        assertEquals(BalanceArea.FAMILY, BalanceRules.area(ev("Swimming · Felix", mon, 17, 0, 18), childOf))
        assertEquals(BalanceArea.WORK, BalanceRules.area(ev("1:1 with Sam", mon, 9, 0, 10), childOf))
        assertNull(BalanceRules.area(ev("Runway planning", mon, 9, 0, 10), childOf))
        assertEquals(BalanceArea.HEALTH, BalanceRules.habitArea("Morning walk"))
        assertNull(BalanceRules.habitArea("Floss"))
        assertEquals("6 h 05", BalanceRules.hours(365))
        assertEquals("45 min", BalanceRules.hours(45))
    }

    /** Slice 2: the reviewed week and the three before it, oldest first, with the line under each area. */
    @Test
    fun theTrendShowsFourWeeksWithHowOftenEachWasMet() {
        val earlier = listOf(
            ev("Gym", mon - 14, 7, 0, 10), // 3 h: health met two weeks back
            ev("Date night", mon - 7, 19, 0, 23), // 4 h of family last week
        )
        val events = week + earlier
        val habitsFor: (Long) -> List<Pair<String, List<Boolean>>> = { ws -> if (ws == mon) habits else emptyList() }
        val weeks = BalanceRules.trendMinutes(mon, at(mon + 7, 9), cal, work, events, habitsFor, childOf)
        assertEquals(listOf(mon - 21, mon - 14, mon - 7, mon), weeks.map { it.first })
        val v = BalanceRules.view(mon, at(mon + 7, 9), cal, work, events, habits, emptyMap(), childOf, weeks)
        val h = row(v, BalanceArea.HEALTH)
        assertEquals(listOf(0, 180, 0, 180), h.trend.map { it.minutes })
        assertEquals(listOf("21 Sep", "28 Sep", "5 Oct", "12 Oct"), h.trend.map { it.label })
        assertEquals(listOf(false, true, false, true), h.trend.map { it.met })
        assertEquals(100, h.trend[1].pct)
        assertEquals("Last 4 weeks · met in 2 · about 1 h 30 a week", h.trendLine)
        val f = row(v, BalanceArea.FAMILY)
        assertEquals(listOf(0, 0, 240, 360), f.trend.map { it.minutes })
        assertEquals("Last 4 weeks · not met yet · about 2 h 30 a week", f.trendLine)
        // Target 8 h on a scale of 8 h (more than the biggest week).
        assertEquals(100, f.trendTargetPct)
        assertEquals(75, f.trend[3].pct)
        // Work: his hours each week, never over.
        val w = row(v, BalanceArea.WORK)
        assertEquals("Last 4 weeks · never over · about 40 h a week", w.trendLine)
        assertTrue(w.spoken.endsWith("Last 4 weeks · never over · about 40 h a week"))
    }

    @Test
    fun aTrendWithNothingInItHasNoLine() {
        val weeks = BalanceRules.trendMinutes(mon, at(mon + 2, 9), cal, work, emptyList(), { emptyList() }, childOf)
        val v = BalanceRules.view(mon, at(mon + 2, 9), cal, work, emptyList(), emptyList(), emptyMap(), childOf, weeks)
        val s = row(v, BalanceArea.SELF)
        assertEquals(4, s.trend.size)
        assertTrue(s.trend.last().current)
        assertNull(s.trendLine)
        // Without a trend (slice 1's callers) the rows carry none.
        assertTrue(BalanceRules.view(mon, at(mon + 2, 9), cal, work, week, habits, emptyMap(), childOf).rows.all { it.trend.isEmpty() })
    }

    @Test
    fun theWeekAheadSaysWhatThePlanLeavesShort() {
        // Slice 3: the whole week as planned, even before it starts.
        assertEquals(
            "Balance as planned · family 6 h of 8 h · health 2 h 15 of 3 h",
            BalanceRules.weekAheadLine(mon, true, cal, work, week, emptyList(), emptyMap(), childOf),
        )
        // The rest of this week: habits already ticked count (Friday's gym makes health 3 h).
        assertEquals(
            "Balance with what's planned · family 6 h of 8 h",
            BalanceRules.weekAheadLine(mon, false, cal, work, week, habits, emptyMap(), childOf),
        )
        // Every target met, and work over the hours he set.
        val targets = mapOf(BalanceArea.FAMILY to 6 * 60, BalanceArea.WORK to 35 * 60)
        assertEquals(
            "Balance as planned · every target met · work 5 h over",
            BalanceRules.weekAheadLine(mon, true, cal, work, week, habits, targets, childOf),
        )
        assertEquals(
            "Balance as planned · every target met · work within your hours",
            BalanceRules.weekAheadLine(mon, true, cal, work, week, habits, mapOf(BalanceArea.FAMILY to 6 * 60), childOf),
        )
        // Read out after the week: hours in full.
        val ahead = WeekAheadView.EMPTY.copy(summary = "4 office days · 2 matches", spoken = "Your week ahead. 4 office days, 2 matches. Mon 12 Oct.")
            .withBalance("Balance as planned · family 6 h of 8 h · health 2 h 15 of 3 h")
        assertEquals("Balance as planned · family 6 h of 8 h · health 2 h 15 of 3 h", ahead.balance)
        assertEquals(
            "Your week ahead. 4 office days, 2 matches. Balance as planned, family 6 hours of 8 hours, health 2 hours 15 of 3 hours. Mon 12 Oct.",
            ahead.spoken,
        )
        assertNull(ahead.withBalance(null).balance)
    }

    @Test
    fun atMostOneNudgeAWeekOnThursdayEveningForTheAreaMostShort() {
        // As planned the week has family 6 h, health 3 h, self 3 h 30: with the default targets nothing is short enough.
        assertNull(BalanceRules.nudge(mon, cal, work, week, habits, emptyMap(), childOf))
        // Family 10 h and health 5 h: both 40% short; family is short by more, so it's named and health rides along.
        val targets = mapOf(BalanceArea.FAMILY to 10 * 60, BalanceArea.HEALTH to 5 * 60)
        val n = BalanceRules.nudge(mon, cal, work, week, habits, targets, childOf)!!
        assertEquals(BalanceArea.FAMILY, n.area)
        assertEquals("Family time is short this week", n.title)
        assertEquals("6 h of 10 h with what's planned · 4 h to find by Sunday · health short too", n.text)
        // Health alone: a different title.
        val h = BalanceRules.nudge(mon, cal, work, week, habits, mapOf(BalanceArea.HEALTH to 5 * 60), childOf)!!
        assertEquals("Time for your health is short this week", h.title)
        assertEquals("3 h of 5 h with what's planned · 2 h to find by Sunday", h.text)
        // Under an hour short is never named, even well under the share.
        assertNull(BalanceRules.nudge(mon, cal, work, week, habits, mapOf(BalanceArea.SELF to 4 * 60), childOf))

        val notices = BalanceRules.notices(n, at(mon, 9), cal)
        assertEquals(1, notices.size)
        val x = notices.single()
        assertEquals("balance:$mon", x.key)
        assertEquals(NoticeSource.BALANCE, x.source)
        assertEquals(NoticeTier.HEADS_UP, x.tier)
        assertEquals(NoticeTarget.REVIEW, x.target)
        assertEquals(at(mon + 3, 17), x.atMs)
        assertEquals(at(mon + 5, 0), x.expiresAtMs)
        // From Saturday it's gone, and no nudge means no notice.
        assertTrue(BalanceRules.notices(n, at(mon + 5, 9), cal).isEmpty())
        assertTrue(BalanceRules.notices(null, at(mon + 3, 17), cal).isEmpty())
        // Through the collector like every source.
        val all = NoticeSources.collect(
            ListsView.EMPTY, FastingView.EMPTY, ShutdownView.EMPTY, Today(emptyList(), null, emptyList(), emptyList()), at(mon + 3, 17), cal, balance = n,
        )
        assertEquals(1, all.count { it.source == NoticeSource.BALANCE })
    }

    @Test
    fun countAsChoosesTheAreaForEventsWithNoClearAreaByTitle() {
        val end = at(mon + 7, 9)
        val plain = BalanceRules.view(mon, end, cal, work, week, habits, emptyMap(), childOf)
        assertEquals(listOf("Pick up parcel"), plain.unclear.map { it.title })
        assertEquals("Mon · 1 h", plain.unclear.single().line)
        assertEquals("pick up parcel", plain.unclear.single().key)
        // Counted as family: it leaves work's hours and joins family; no longer listed or "not counted".
        val chosen = BalanceChoices.decode("pick up parcel=f")
        val v = BalanceRules.view(mon, end, cal, work, week, habits, emptyMap(), childOf, chosen = chosen)
        assertEquals(7 * 60, row(v, BalanceArea.FAMILY).minutes)
        assertEquals(39 * 60, row(v, BalanceArea.WORK).minutes)
        assertTrue(v.unclear.isEmpty())
        assertNull(v.uncounted)
        assertEquals(listOf("Pick up parcel" to "Family"), v.choices.map { it.title to it.label })
        // A choice wins over the word rules, and "don't count" leaves an event out entirely.
        val over = BalanceChoices.decode("cinema=n|pick up parcel=h")
        val o = BalanceRules.view(mon, end, cal, work, week, habits, emptyMap(), childOf, chosen = over)
        assertEquals(60, row(o, BalanceArea.SELF).minutes)
        assertEquals(75 + 60 + 45 + 60, row(o, BalanceArea.HEALTH).minutes)
        assertEquals("Not counted", o.choices.first().label)
        // The same title next week counts as planned too, and the trend counts it in every week.
        val nextWeek = week.map { it.copy(id = it.id + "n", startAtMs = it.startAtMs + 7 * 86_400_000L, endAtMs = it.endAtMs + 7 * 86_400_000L) }
        val line = BalanceRules.weekAheadLine(mon + 7, true, cal, work, nextWeek, emptyList(), emptyMap(), childOf, chosen)
        assertTrue(line!!.contains("family 7 h of 8 h"), line)
        val t = BalanceRules.trendMinutes(mon + 7, at(mon + 14, 9), cal, work, week + nextWeek, { emptyList() }, childOf, chosen)
        assertEquals(listOf(0, 0, 7 * 60, 7 * 60), t.map { it.second.getValue(BalanceArea.FAMILY) })
    }

    @Test
    fun choicesAreStoredAsOneShortTextNewestFirst() {
        val c = BalanceChoices.decode("swim club=h| team lunch =n|bad|x=z|=f|swim club=w")
        assertEquals(listOf("swim club", "team lunch"), c.map.keys.toList())
        assertEquals(BalanceArea.HEALTH, c.area("swim club"))
        assertTrue(c.has("team lunch"))
        assertNull(c.area("team lunch"))
        assertEquals("swim club=h|team lunch=n", BalanceChoices.encode(c.map))
        assertEquals("swim club", BalanceRules.key("  Swim   CLUB! "))
        assertEquals(BalanceChoices.NONE, BalanceChoices.decode(null))
        val many = (1..80).associate { "e$it" to BalanceArea.SELF as BalanceArea? }
        assertEquals(BalanceRules.MAX_CHOICES, BalanceChoices.decode(BalanceChoices.encode(many)).map.size)
    }
}
