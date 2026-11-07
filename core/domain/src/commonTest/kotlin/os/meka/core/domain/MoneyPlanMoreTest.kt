package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Plan's lower half, slice 1: Goals' start tiles and the forecast as tiles and bars. Invented statements only. */
class MoneyPlanMoreTest {
    private val oct16 = CivilDate.toEpochDay(2026, 10, 16)
    private fun d(m: Int, day: Int) = CivilDate.toEpochDay(2026, m, day)
    private fun line(day: Long, pence: Long, merchant: String, c: MoneyCategory) = MoneyLine(day, pence, merchant, c)

    /** Jul–Sep: £2,500 in, £2,060 out a month (as MoneyForecastTest), so £440 left over. */
    private fun months(vararg ms: Int) = ms.flatMap { m ->
        listOf(
            line(d(m, 1), 250_000, "SALARY OAKFIELD", MoneyCategory.INCOME),
            line(d(m, 2), -160_000, "RENT", MoneyCategory.BILLS),
            line(d(m, 10), -30_000, "NANDOS", MoneyCategory.EATING_OUT),
            line(d(m, 12), -15_000, "TESCO STORES", MoneyCategory.GROCERIES),
            line(d(m, 28), -1_000, "COSTA COFFEE", MoneyCategory.COFFEE),
        )
    }

    @Test
    fun tilesBarsAndHorizons() {
        val f = MoneyForecast.view(months(7, 8, 9), oct16)!!
        val t = MoneyPlanMore.forecast(f, 12, 0)!!
        assertEquals(listOf("£2,500", "£2,060", "£440"), t.tiles.map { it.value })
        assertTrue(t.tiles[2].lit)
        assertEquals(12, t.bars.size)
        assertEquals("Nov", t.bars.first().month)
        assertEquals("Oct", t.bars.last().month)
        assertEquals(listOf(5, 11), t.bars.withIndex().filter { it.value.key }.map { it.index })
        assertEquals("+£2.6k", t.bars[5].tag)
        assertEquals("+£5.3k", t.bars[11].tag)
        assertEquals(1f, t.bars[11].share)
        assertEquals(listOf("BY APR 2027", "BY OCT 2027"), t.by.map { it.label })
        assertEquals(listOf("+£2,640", "+£5,280"), t.by.map { it.value })
        assertEquals(MoneyPlanMore.CUT_NONE, t.cutLabel)
        assertEquals(MoneyPlanMore.CUT_HINT, t.cutNote)
        // Six months: six bars, the tallest the 6th.
        val six = MoneyPlanMore.forecast(f, 6, 0)!!
        assertEquals(6, six.bars.size)
        assertEquals(1f, six.bars.last().share)
    }

    @Test
    fun theSliderAndWhatIfsMoveEverything() {
        val f = MoneyForecast.view(months(7, 8, 9), oct16, MoneyWhatIf(extraPence = 10_000))!!
        val t = MoneyPlanMore.forecast(f, 12, 160)!!
        // £160 snaps to £150; £440 + £100 extra + £150 = £690 a month.
        assertEquals(15_000L, t.cutPence)
        assertEquals("£690", t.tiles[2].value)
        assertEquals("+£8,280", t.by[1].value)
        assertEquals("£150 a month", t.cutLabel)
        assertEquals("That adds £1,800 by Oct 2027", t.cutNote)
        assertEquals(60_000L, MoneyPlanMore.cutPence(9_999))
    }

    @Test
    fun moreOutThanInWearsTheAlarm() {
        val lines = months(8, 9) + listOf(line(d(8, 15), -80_000, "CURRYS", MoneyCategory.SHOPPING), line(d(9, 15), -80_000, "CURRYS", MoneyCategory.SHOPPING))
        val t = MoneyPlanMore.forecast(MoneyForecast.view(lines, oct16)!!, 12, 0)!!
        assertEquals("−£360", t.tiles[2].value)
        assertTrue(t.tiles[2].alarm)
        assertFalse(t.tiles[2].lit)
        assertTrue(t.bars.all { it.below })
        assertEquals("−£2,160", t.by[0].value)
    }

    @Test
    fun notReadyOrNoPayKeepsTheSentences() {
        assertNull(MoneyPlanMore.forecast(MoneyForecast.view(months(9), oct16)!!, 12, 0))
        val noPay = months(7, 8, 9).filter { it.category != MoneyCategory.INCOME }
        assertNull(MoneyPlanMore.forecast(MoneyForecast.view(noPay, oct16)!!, 12, 0))
    }

    @Test
    fun shortTags() {
        assertEquals("+£950", MoneyPlanMore.short(95_000))
        assertEquals("+£6.3k", MoneyPlanMore.short(630_000))
        assertEquals("+£12k", MoneyPlanMore.short(1_200_000))
        assertEquals("−£1.2k", MoneyPlanMore.short(-120_000))
        assertEquals("+£125k", MoneyPlanMore.short(12_500_000))
    }

    @Test
    fun goalStartTiles() {
        val none = MoneyGoalsView(MoneyGoals.HEAD, emptyList(), MoneyGoals.EMPTY, MoneyGoals.FOOT)
        val e = MoneyPlanMore.goalsEmpty(none, hasSave = true, hasClear = true)!!
        assertEquals(listOf("Save £ a month", "Clear my cards", "Add a £ goal"), e.tiles.map { it.name })
        assertEquals(listOf("Add a £ goal"), MoneyPlanMore.goalsEmpty(none, hasSave = false, hasClear = false)!!.tiles.map { it.name })
        val one = none.copy(rows = listOf(MoneyGoalRow("g", "Holiday", "£0 of £600", 0, null, emptyList(), "Holiday")))
        assertNull(MoneyPlanMore.goalsEmpty(one, true, true))
        assertNull(MoneyPlanMore.goalsEmpty(null, true, true))
    }

    @Test
    fun aNewGoal() {
        assertEquals(MoneyPlanMore.NewGoal("Holiday", "£2,000"), MoneyPlanMore.newGoal("  Holiday ", "£2,000").getOrNull())
        assertEquals("£1,500", MoneyPlanMore.newGoal("Car", "1.5k").getOrNull()?.target)
        assertEquals(MoneyPlanMore.ADD_BAD_AMOUNT, MoneyPlanMore.newGoal("Car", "lots").exceptionOrNull()?.message)
        assertEquals(MoneyPlanMore.ADD_BAD_AMOUNT, MoneyPlanMore.newGoal("Car", "0").exceptionOrNull()?.message)
        assertEquals(MoneyPlanMore.ADD_NO_NAME, MoneyPlanMore.newGoal(" ", "£200").exceptionOrNull()?.message)
        assertEquals("Holiday · £2,000 added to Goals", MoneyPlanMore.addedSaid(MoneyPlanMore.NewGoal("Holiday", "£2,000")))
    }
}
