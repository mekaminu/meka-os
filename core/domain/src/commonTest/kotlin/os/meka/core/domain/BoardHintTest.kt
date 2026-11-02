package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ada plans with you, part 2: Obi's ✦ thoughts on the Week board's cards. */
class BoardHintTest {
    private val cal = LocalCalendar.UTC
    /** Monday 26 October 2026. */
    private val mon = 20752L

    private fun task(
        id: String, title: String = "Title $id", estimate: Int? = null, due: Long? = null, repeat: Boolean = false,
        planned: Long? = null, asked: Long? = null, hintNo: String? = null,
    ) = Task(
        id, title, null, Lifecycle.ACTIVE, due?.let { it * 86_400_000L + 3_600_000L }, planned?.let { it * 86_400_000L + 3_600_000L },
        estimate, 0, null, null, 0, null, false, recurrenceRule = if (repeat) "FREQ=DAILY" else null,
        carryAskedDay = asked, boardHintNo = hintNo,
    )

    private fun card(t: Task) = BoardCard(t, t.title, null, null, false, false)

    private fun day(d: Long, today: Long, planned: Int, free: Int, cards: List<Task> = emptyList()) = BoardDay(
        d, LocalClock.DAY_SHORT[CivilDate.isoDayOfWeek(d) - 1], "${d - mon + 26}", d == today, d < today, null, null,
        emptyList(), 0, cards.map(::card), "", "", capacity = if (d >= today) BoardCapacity(planned, free, 0) else null,
    )

    private fun board(today: Long, days: List<BoardDay>, someday: List<Task> = emptyList()) =
        WeekBoard(today, listOf(BoardWeek(mon, "This week", "26 Oct – 1 Nov", days)), someday.map(::card))

    private fun quiet(today: Long) = (0..6).map { day(mon + it, today, 0, 420) }

    @Test
    fun anOverDaysCardIsOfferedItsLighterDay() {
        val gym = task("gym", estimate = 60)
        val days = quiet(mon).toMutableList()
        days[1] = day(mon + 1, mon, 300, 240, listOf(task("review", estimate = 240), gym))
        val hints = BoardHintRules.of(board(mon, days), mon, cal)
        val h = hints.single()
        assertEquals(BoardHintKind.OVER, h.kind)
        assertEquals("gym", h.taskId)
        assertEquals("Tomorrow is 1h over · move it to today?", h.line)
        assertEquals(mon, h.toDay)
        assertEquals("✦ Tomorrow is 1h over · move it to today?", h.shown)
        assertEquals("Obi suggests: Tomorrow is 1h over, move it to today?", h.spoken)
        assertEquals("Moved “Title gym” to today", BoardHintRules.appliedLine(h, gym.title))
    }

    @Test
    fun anEstimateWellUnderHisUsualAsksForTheUsual() {
        val mot = task("mot", "Book the MOT", estimate = 15)
        val days = quiet(mon).toMutableList()
        days[0] = day(mon, mon, 15, 420, listOf(mot))
        val others = listOf(task("a", "Book the MOT for the van", estimate = 60), task("b", "MOT paperwork", estimate = 60))
        val h = BoardHintRules.of(board(mon, days, others), mon, cal).single()
        assertEquals(BoardHintKind.LONGER, h.kind)
        assertEquals("Usually about 1h for you, not 15m", h.line)
        assertEquals("Make it 1h", h.action)
        assertEquals(60, h.minutes)
        assertEquals("“Book the MOT” · about 1h", BoardHintRules.appliedLine(h, mot.title))
        // Close to his usual: nothing to say.
        assertNull(BoardHintRules.longer(card(mot.copy(estimateMinutes = 45)), others))
    }

    @Test
    fun aMailTaskWithNoEstimateIsOfferedFifteenMinutes() {
        val days = quiet(mon).toMutableList()
        days[2] = day(mon + 2, mon, 0, 420, listOf(task("tm1", "Reply to Sam"), task("mine", "Fix the gate")))
        val h = BoardHintRules.of(board(mon, days), mon, cal).single()
        assertEquals("tm1", h.taskId)
        assertEquals(BoardHintKind.REPLY, h.kind)
        assertEquals("From an email · give it 15m for the reply?", h.line)
        assertEquals("15m", h.action)
    }

    @Test
    fun aKeptTaskStillSlippingIsOfferedSomeday() {
        val today = mon + 5
        val tax = task("tax", "Tax return", planned = mon, asked = mon + 3)
        val fresh = task("fresh", planned = mon + 3)
        val days = quiet(today).toMutableList()
        days[5] = day(today, today, 0, 420, listOf(tax, fresh))
        val h = BoardHintRules.of(board(today, days), mon, cal).single()
        assertEquals(BoardHintKind.SLIPPED, h.kind)
        assertEquals("Slipped 5 days · put it on Someday for now?", h.line)
        assertEquals("Someday", h.action)
        assertEquals("Moved “Tax return” to Someday", BoardHintRules.appliedLine(h, tax.title))
    }

    @Test
    fun putAwayThoughtsStayAwayAndAWeekShowsAtMostThree() {
        val days = quiet(mon).toMutableList()
        days[0] = day(mon, mon, 0, 420, (1..5).map { task("tm$it", "Reply $it") })
        val all = BoardHintRules.of(board(mon, days), mon, cal)
        assertEquals(listOf("tm1", "tm2", "tm3"), all.map { it.taskId })
        days[0] = day(mon, mon, 0, 420, (1..5).map { task("tm$it", "Reply $it", hintNo = if (it == 1) "other;reply" else null) })
        assertEquals(listOf("tm2", "tm3", "tm4"), BoardHintRules.of(board(mon, days), mon, cal).map { it.taskId })
        assertTrue(BoardHintRules.dismissed(task("x", hintNo = "a;reply"), "reply"))
        assertFalse(BoardHintRules.dismissed(task("x", hintNo = "replying"), "reply"))
        assertEquals("a;b", BoardHintRules.withDismissed("a", "b"))
        assertEquals("a;b", BoardHintRules.withDismissed("b;a", "b"))
    }

    @Test
    fun repeatingCardsAndWeeksGoneHaveNoThoughts() {
        val days = quiet(mon).toMutableList()
        days[0] = day(mon, mon, 0, 420, listOf(task("tm1", repeat = true)))
        assertTrue(BoardHintRules.of(board(mon, days), mon, cal).isEmpty())
        assertTrue(BoardHintRules.of(board(mon + 14, quiet(mon + 14)), mon, cal).isEmpty())
        assertTrue(BoardHintRules.of(board(mon, days), mon + 7, cal).isEmpty())
    }
}
