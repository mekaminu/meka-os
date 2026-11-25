package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Week 5's walk-through A16: Ask → Life admin hour in the page shape every other Life page has (both apps). */
class LifeAdminPageTest {
    private val hour = 3_600_000L
    private val cal = LocalCalendar.fixedOffset(0)
    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)
    private val mon = d(10, 19)
    private val wed = d(10, 21)
    private val sat = d(10, 24)
    private fun at(day: Long, h: Int) = cal.toEpochMs(day, h * 60)
    private val now = at(wed, 17)
    private val s = LifeAdminSetting(6, 10 * 60)
    private fun slot(day: Long, weekStart: Long) =
        LifeAdminSlot(day, weekStart, at(day, 10), at(day, 11), "${CivilDate.shortLabel(day)} 10:00–11:00")
    private val thisWeek = slot(sat, mon)
    private val nextWeek = slot(sat + 7, mon + 7)

    private fun job(i: Int, overdue: Boolean = false) =
        LifeAdminJob("task:t$i", LifeAdminJobKind.TASK, "Call number $i", if (overdue) "overdue since Mon 19 Oct" else null, "Call number $i", overdue)
    private val renewal = LifeAdminJob("renewal:r1", LifeAdminJobKind.RENEWAL, "Home insurance", "due Mon 26 Oct", "Home insurance, due Mon 26 Oct")

    private fun view(jobs: List<LifeAdminJob>, setting: LifeAdminSetting = s) = LifeAdminRules.view(setting, thisWeek, nextWeek, jobs, now)

    @Test
    fun offIsOneLineWithOneButtonAndNothingElse() {
        val off = LifeAdminRules.view(null, null, null, emptyList(), now)
        assertTrue(LifeAdminPageRules.blank(off))
        val p = LifeAdminPageRules.page(off, wed, cal)
        assertEquals("Life admin hour", p.title)
        assertTrue(p.tiles.isEmpty())
        assertTrue(p.cards.isEmpty())
        assertEquals(listOf(LifeAdminPageRules.ADD_HOUR), p.addChoices.map { it.key })
        // The ⓘ says what the hour does; the off line is said once, by the blank card.
        assertTrue(p.info.contains("Skip a week"))
        assertFalse(LifeAdminPageRules.EMPTY_LINE.contains("·"))
    }

    @Test
    fun theThreeNumbersAreHowSoonTheJobsAndTheOverdue() {
        val p = LifeAdminPageRules.page(view(listOf(renewal, job(1, overdue = true), job(2))), wed, cal)
        assertEquals(listOf("3 days", "3", "1"), p.tiles.map { it.number })
        assertEquals(listOf("to the hour", "jobs waiting", "overdue"), p.tiles.map { it.label })
        assertEquals(listOf(false, false, true), p.tiles.map { it.lit })
        assertEquals(listOf(LifeAdminPageRules.JOBS, LifeAdminPageRules.HOUR), p.cards.map { it.key })
        assertTrue(p.tiles.all(LifePageRules::readsWhole))

        val none = LifeAdminPageRules.page(view(emptyList()), wed, cal)
        assertEquals(listOf("None waiting", "None overdue"), none.tiles.drop(1).map { "${it.number} ${it.label}" })
        assertEquals(LifeAdminPageRules.JOBS_EMPTY, none.cards.first().empty)
        assertFalse(none.cards.first().showsCount)

        // On the day: "Today at 10:00", lit.
        val onTheDay = LifeAdminPageRules.page(LifeAdminRules.view(s, thisWeek, nextWeek, emptyList(), at(sat, 8)), sat, cal)
        assertEquals("Today at 10:00", onTheDay.tiles[0].let { "${it.number} ${it.label}" })
        assertTrue(onTheDay.tiles[0].lit)
    }

    @Test
    fun theJobsAreFiveCardsRowsWithSeeAllAndEveryOneBehindIt() {
        val jobs = listOf(renewal) + (1..10).map { job(it, overdue = it == 1) }
        val v = view(jobs)
        assertEquals(LifeAdminRules.SHOWN, v.jobs.size)
        val card = LifeAdminPageRules.page(v, wed, cal).cards.first()
        assertEquals(11, card.count)
        assertEquals(LifePageRules.MAX_ROWS, card.rows.size)
        assertEquals(LifePageRules.SEE_ALL, card.more)
        assertEquals("Home insurance", card.rows[0].name.replace('\u00A0', ' '))
        assertEquals("Renewal · due Mon 26 Oct", card.rows[0].line.replace(Regex("[\\u00A0\\u2060\\u202F]"), " "))
        // An overdue job says so once, in its line; nothing repeats it on the right.
        assertTrue(card.rows[1].line.contains("overdue"))
        assertEquals("", card.rows[1].whenText)

        val all = LifeAdminPageRules.page(v, wed, cal, all = true).cards.first()
        assertEquals(11, all.rows.size)
        assertEquals(LifeAdminPageRules.FEWER, all.more)
        assertEquals(job(10).id, LifeAdminPageRules.job(v, all.rows.last().key)?.id)
        assertEquals("t10", LifeAdminPageRules.taskId(LifeAdminPageRules.job(v, all.rows.last().key)!!))
        assertNull(LifeAdminPageRules.taskId(renewal))
        assertNull(LifeAdminPageRules.job(v, LifeAdminPageRules.WEEK_KEY))
    }

    @Test
    fun theHourCardSaysThisWeeksHourTheNextWhenSkippedAndTheSetting() {
        val rows = LifeAdminPageRules.page(view(emptyList()), wed, cal).cards[1]
        assertFalse(rows.showsCount)
        assertEquals(listOf(LifeAdminPageRules.WEEK_KEY, LifeAdminPageRules.EVERY_KEY), rows.rows.map { it.key })
        assertEquals("Sat 24 Oct 10:00–11:00", rows.rows[0].name)
        assertEquals(LifeAdminPageRules.BOOKED_LINE, rows.rows[0].line)
        assertEquals("Saturdays from 10:00", rows.rows[1].name)
        assertEquals(LifeAdminPageRules.CHANGE, rows.rows[1].whenText)
        assertEquals(LifeAdminRules.SKIP_LABEL, LifeAdminPageRules.skipLabel(view(emptyList())))

        val skipped = view(emptyList(), s.copy(skipped = setOf(mon)))
        val p = LifeAdminPageRules.page(skipped, wed, cal)
        assertEquals(listOf(LifeAdminPageRules.WEEK_KEY, LifeAdminPageRules.NEXT_KEY, LifeAdminPageRules.EVERY_KEY), p.cards[1].rows.map { it.key })
        assertEquals(LifeAdminPageRules.SKIPPED_LINE, p.cards[1].rows[0].line)
        assertEquals("Sat 31 Oct 10:00–11:00", p.cards[1].rows[1].name)
        assertEquals("10 days", p.tiles[0].number)
        assertEquals(LifeAdminRules.KEEP_LABEL, LifeAdminPageRules.skipLabel(skipped))
    }
}
