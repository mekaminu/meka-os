package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
