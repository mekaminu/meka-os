package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlsoTodayTest {
    private val day = 20_400L

    private fun renewal(id: String, title: String, state: RenewalState, due: Long, cancelBy: Long? = null) = RenewalItem(
        id = id, title = title, kind = ObligationKind.SUBSCRIPTION, subject = null, notes = null, dueDay = due,
        cancelByDay = cancelBy, leadDays = 14, costPence = null, repeats = RenewalRepeat.MONTHLY, repeatLabel = "Every month",
        state = state, meta = "", doneLabel = "Renewed", stopLabel = "Cancelled it", hasConflict = false,
    )

    private val empty = Today(needsYou = emptyList(), upNext = null, yourDay = emptyList(), doneToday = emptyList())

    @Test
    fun aRenewalSaysWhatAndByWhen() {
        val r = AlsoTodayRules.renewal(listOf(renewal("r1", "Streaming app", RenewalState.CANCEL_BY, day + 9, day + 4)), day)!!
        assertEquals("RENEWAL", r.key)
        assertEquals("Streaming app", r.value)
        assertEquals("cancel by ${CivilDate.shortLabel(day + 4)}", r.line)
        assertTrue(r.lit)
        assertEquals("r1", r.targetId)
        val two = AlsoTodayRules.renewal(listOf(
            renewal("r1", "Car tax", RenewalState.OVERDUE, day - 2), renewal("r2", "Gym", RenewalState.SOON, day + 3),
        ), day)!!
        assertEquals("overdue · was ${CivilDate.shortLabel(day - 2)} · 1 more", two.line)
        assertTrue(two.spoken.contains("1 more renewal needs you"))
        val soon = AlsoTodayRules.renewal(listOf(renewal("r2", "Gym", RenewalState.SOON, day + 1)), day)!!
        assertEquals("renews tomorrow", soon.line)
        assertEquals(false, soon.lit)
        assertNull(AlsoTodayRules.renewal(emptyList(), day))
    }

    @Test
    fun oneLineNotesBecomeAValueAndALine() {
        val bins = AlsoTodayRules.bins(BinLine("Black bin and food caddy out tonight", "Black bin and food caddy go out tonight."))
        assertEquals("Black bin and food caddy", bins.value)
        assertEquals("out tonight", bins.line)
        assertTrue(bins.lit)
        val held = AlsoTodayRules.bins(BinLine("Garden bin moved to Saturday · not out tonight", "x"))
        assertEquals("Garden bin moved to Saturday", held.value)
        assertEquals(false, held.lit)
        val study = AlsoTodayRules.split(AlsoKind.STUDY, "Study today · 3 lessons · about 1 h 30 · next: Pivot tables", "s", false, drop = "Study today", id = "c1")
        assertEquals("3 lessons", study.value)
        assertEquals("about 1 h 30 · next: Pivot tables", study.line)
        assertEquals("c1", study.targetId)
        val dinner = AlsoTodayRules.dinner(MealLine("Dinner tonight: Chilli", "Dinner tonight is chilli."))
        assertEquals("Chilli", dinner.value)
        assertEquals("dinner tonight", dinner.line)
        val one = AlsoTodayRules.reflection(ReflectionWeekLine("This week's one thing · Run 5k", "s", lit = true))
        assertEquals("Run 5k", one.value)
        assertEquals("this week's one thing", one.line)
        val plain = AlsoTodayRules.split(AlsoKind.DATE_NIGHT, "Date night tonight from 19:00", "s", lit = true, drop = "Date night")
        assertEquals("Date night tonight from 19:00", plain.value)
        assertEquals("", plain.line)
    }

    @Test
    fun litTilesComeFirstThenTheMockupsOrder() {
        val today = empty.copy(
            weekAhead = WeekAheadToday("Your week ahead · 4 office days · 2 matches", "w"),
            study = StudyDayLine("Study today · 2 lessons", "s", left = 2, done = 0, lit = false),
            bins = BinLine("Blue bin out tonight", "b"),
            dinner = MealLine("Dinner tonight: Chilli", "d"),
        )
        val tiles = AlsoTodayRules.tiles(today, listOf(renewal("r1", "Streaming app", RenewalState.SOON, day + 5)), day)
        assertEquals(listOf(AlsoKind.BINS, AlsoKind.STUDY, AlsoKind.RENEWAL, AlsoKind.WEEK_AHEAD, AlsoKind.DINNER), tiles.map { it.kind })
        assertEquals("4 office days", tiles[3].value)
        assertEquals(emptyList(), AlsoTodayRules.tiles(empty, emptyList(), day))
    }

    @Test
    fun theHeadersOtherNotesFoldIntoTiles() {
        val clash = AlsoTodayRules.clash("Training at 16:55 while you're at work — who's taking Alex?")
        assertEquals("HEADS UP", clash.key)
        assertEquals("Training at 16:55 while you're at work", clash.value)
        assertEquals("who's taking Alex?", clash.line)
        assertTrue(clash.lit)
        val school = AlsoTodayRules.colon(AlsoKind.SCHOOL, "Alex: PE today", "Alex has PE today.", lit = false)
        assertEquals("PE today", school.value)
        assertEquals("Alex", school.line)
        val goals = AlsoTodayRules.colon(AlsoKind.GOALS, "This week: 2 of 3 goals moving", "g", lit = false)
        assertEquals("2 of 3 goals moving", goals.value)
        assertEquals("this week", goals.line)
        val today = empty.copy(
            trainQueue = QueueLine("For the train · 2 articles · 1 podcast", "q"),
            trading = TradingTodayLine("Trading · up today · 2 positions open", "t", lit = false),
            mailReplies = MailRepliesLine("3 emails need a reply · 2 drafted", "r", lit = true),
            school = SchoolLine("Alex: PE today", "s"),
        )
        val tiles = AlsoTodayRules.tiles(today, emptyList(), day)
        assertEquals(listOf(AlsoKind.TRAIN_QUEUE, AlsoKind.REPLIES, AlsoKind.SCHOOL, AlsoKind.TRADING), tiles.map { it.kind })
        assertEquals("2 articles", tiles[0].value)
        assertEquals("up today", tiles[3].value)
        assertEquals("q", tiles[0].spoken)
        val more = AlsoTodayRules.withHeader(tiles, RouteLine("South Rail · good service", lit = false, spoken = "Trains: good service."), "This week: 2 of 3 goals moving")
        assertEquals(listOf(AlsoKind.TRAIN_QUEUE, AlsoKind.REPLIES, AlsoKind.TRAINS, AlsoKind.SCHOOL, AlsoKind.TRADING, AlsoKind.GOALS), more.map { it.kind })
        assertEquals(more, AlsoTodayRules.withHeader(more, RouteLine("x", lit = true, spoken = "x"), "This week: 1 of 3 goals moving"))
        assertEquals(tiles, AlsoTodayRules.withHeader(tiles, null, null))
    }

    @Test
    fun meetingPrepAndAStackingDayAreTilesThatUnfold() {
        val prep = MeetingPrep(
            eventId = "e1", startAtMs = 0L, headline = "Prep · Quote review at 14:00 · in 12 min",
            whoLine = "With Sam Taylor and Alex Green", people = emptyList(), lines = listOf("Sam · Last email Tue 13:02"),
            spoken = "Prep. Quote review at 14:00. In 12 min. With Sam Taylor and Alex Green.",
        )
        val p = AlsoTodayRules.prep(prep)
        assertEquals("PREP", p.key)
        assertEquals("Quote review at 14:00", p.value)
        assertEquals("in 12 min · with Sam Taylor and Alex Green", p.line)
        assertEquals("e1", p.targetId)
        assertTrue(p.lit)
        assertTrue(p.spoken.endsWith(AlsoTodayRules.UNFOLD_PREP))
        val fix = PressureFix("t1", "Gym", day + 2, "Move “Gym” to Sat", earlier = false)
        val stacking = DayPressure(day + 1, "Tomorrow", evening = false, items = emptyList(),
            line = "Tomorrow is stacking up: Trip form, Council tax and Gym", short = "s",
            spoken = "Tomorrow is stacking up: Trip form, Council tax and Gym.", fixes = listOf(fix))
        val s = AlsoTodayRules.pressure(stacking)
        assertEquals("STACKING UP", s.key)
        assertEquals("Tomorrow", s.value)
        assertEquals("Trip form, Council tax and Gym", s.line)
        assertTrue(s.lit)
        assertFalse(AlsoTodayRules.pressure(stacking.copy(fixes = emptyList())).lit)
        assertEquals("This evening", AlsoTodayRules.pressure(stacking.copy(dayName = "This evening", evening = true)).value)
        assertTrue(AlsoTodayRules.unfolds(AlsoKind.PREP) && AlsoTodayRules.unfolds(AlsoKind.PRESSURE))
        assertFalse(AlsoTodayRules.unfolds(AlsoKind.BINS))
        // Both lit: prep first (minutes away), then the clash, then the stacking day, then the rest.
        val clash = AlsoTodayRules.clash("Training at 16:55 while you're at work — who's taking Alex?")
        val all = AlsoTodayRules.withHeader(listOf(clash), null, null, prep = prep, pressure = stacking)
        assertEquals(listOf(AlsoKind.PREP, AlsoKind.CLASH, AlsoKind.PRESSURE), all.map { it.kind })
    }

    @Test
    fun theReceiptIsOneQuietLine() {
        val r = DailyReceipt("Navilife handled 63 things today", "52 changes · 9 reminders · 2 digests", 63)
        assertEquals("Navilife sorted 63 things for you today", r.sortedLine)
        assertEquals("Navilife sorted 63 things for you today: 52 changes, 9 reminders, 2 digests. Opens Activity.", r.spoken)
        assertEquals("Navilife sorted 1 thing for you today. Opens Activity.", DailyReceipt("x", "", 1).spoken)
    }

    private fun checkinRow(id: String, week: String, next: String) =
        StudyCheckinRow(id, "Course $id", week, status = null, behind = false, next = next, spoken = "Course $id.")

    @Test
    fun theStudyCheckInIsATileThatUnfolds() {
        val c = StudyCheckin(
            key = StudyCheckinRules.key(day), headline = StudyCheckinRules.HEADLINE, sub = "", obi = "", spoken = "",
            rows = listOf(
                checkinRow("a", "This week · " + StudyCheckinRules.NOTHING_TICKED, "Next week · " + StudyCheckinRules.NO_SESSIONS),
                checkinRow("b", "This week · 2 lessons · about 1 h", "Next week · 2 sessions · Mon, Wed · 3 lessons"),
            ),
        )
        val t = AlsoTodayRules.checkin(c)!!
        assertEquals(AlsoKind.STUDY_WEEK, t.kind)
        assertEquals("STUDY WEEK", t.key)
        assertEquals("1 of 2 courses", t.value)
        assertEquals("this week · plan next", t.line)
        assertTrue(t.lit)
        assertTrue(AlsoTodayRules.unfolds(AlsoKind.STUDY_WEEK))
        assertTrue(t.spoken.endsWith(AlsoTodayRules.UNFOLD_STUDY_WEEK))
        val set = AlsoTodayRules.checkin(c.copy(rows = c.rows.drop(1)))!!
        assertEquals("1 of 1 course", set.value)
        assertEquals("this week · next week set", set.line)
        assertNull(AlsoTodayRules.checkin(c.copy(rows = emptyList())))
        // In the row it comes with the other lit tiles, never twice.
        val row = AlsoTodayRules.withHeader(AlsoTodayRules.withHeader(emptyList(), null, null, checkin = c), null, null, checkin = c)
        assertEquals(listOf(AlsoKind.STUDY_WEEK), row.map { it.kind })
    }

    @Test
    fun mondaysReviewIsATileThatOpensReview() {
        val card = ReviewCard(offered = true, weekStart = day - 7, offset = -1, reviewed = false,
            title = "Review last week", line = "12 done · 2 still open")
        val t = AlsoTodayRules.review(card)!!
        assertEquals("REVIEW", t.key)
        assertEquals("Review last week", t.value)
        assertEquals("12 done · 2 still open", t.line)
        assertTrue(t.lit)
        assertFalse(AlsoTodayRules.unfolds(AlsoKind.REVIEW))
        assertEquals("Review last week: 12 done, 2 still open. Opens Review.", t.spoken)
        assertNull(AlsoTodayRules.review(card.copy(offered = false)))
        assertNull(AlsoTodayRules.review(ReviewCard.NONE))
        val row = AlsoTodayRules.withHeader(emptyList(), null, "This week: 2 of 3 goals moving", review = card)
        assertEquals(listOf(AlsoKind.REVIEW, AlsoKind.GOALS), row.map { it.kind })
    }
}
