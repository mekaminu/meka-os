package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Money: his own categories in Plan — Obi's money check and the forecast's what-ifs see them. Invented statements only. */
class MoneyOwnPlanTest {
    private val oct16 = CivilDate.toEpochDay(2026, 10, 16)
    private fun d(m: Int, day: Int) = CivilDate.toEpochDay(2026, m, day)
    private fun line(day: Long, pence: Long, merchant: String, c: MoneyCategory) = MoneyLine(day, pence, merchant, c)

    private val vape = MoneyOwnCategory("k1", "Vape", 2, MoneyIcon.BAG)
    private val plan = MoneyOwnPlan(listOf(vape), mapOf("NEWS ON THE" to "k1"))

    /** £20 a month Jul–Sep, then ten £6 buys in Oct 7–16 (£60). */
    private val vapes = listOf(d(7, 5), d(8, 5), d(9, 5)).map { line(it, -2_000, "NEWS ON THE", MoneyCategory.OTHER) } +
        (0 until 10).map { line(oct16 - it, -600, "NEWS ON THE", MoneyCategory.OTHER) }

    @Test
    fun aWantOfHisBoughtOftenIsAHabitIdea() {
        val i = MoneyCheck.ownHabits(vapes, oct16, plan).single()
        assertEquals("habit-own-k1", i.id)
        assertEquals("Vape 10× in 30 days", i.title)
        assertEquals("~£360/yr", i.figure)
        assertEquals(36_000L, i.savePence)
        assertEquals(MoneyCheck.ACT_CAP, i.actKey)
        assertEquals(MoneyCheck.BEHIND_MAX, i.behind.size)
        assertTrue(MoneyCheck.validId(i.id))
        // Marked a need: no habit idea.
        val need = plan.copy(needMerchants = mapOf("NEWS ON THE" to true))
        assertTrue(MoneyCheck.ownHabits(vapes, oct16, need).isEmpty())
        // Without his category the payee is only Other: nothing.
        assertTrue(MoneyCheck.ownHabits(vapes, oct16, MoneyOwnPlan()).isEmpty())
    }

    @Test
    fun overUsualThisMonthAgainstItsAverage() {
        val o = MoneyCheck.ownOver(vapes, oct16, plan).single()
        assertEquals("over-own-k1", o.id)
        assertEquals("Vape over usual this month", o.title)
        assertEquals("£40 over", o.figure)
        assertEquals(0L, o.savePence)
        assertEquals("Vape £40 over your 3-month average. £60 so far in October · 15 days to go.", o.why)
        assertTrue(o.lit)
        // Only one month before: no average to be over.
        assertTrue(MoneyCheck.ownOver(vapes.filter { it.day >= d(9, 1) }, oct16, plan).isEmpty())
        // A built-in category's line in the same payee isn't his.
        assertTrue(MoneyCheck.ownOver(vapes.map { it.copy(category = MoneyCategory.SHOPPING) }, oct16, plan).isEmpty())
    }

    @Test
    fun theMoneyCheckSaysEachOnce() {
        val ideas = MoneyCheck.ideas(vapes, oct16, emptyList(), plan = plan)
        // Already a habit, so not said again as over usual.
        assertEquals(listOf("habit-own-k1"), ideas.map { it.id })
        val v = assertNotNull(MoneyCheck.view(vapes, oct16, emptyList(), MoneyIdeaState(), plan = plan))
        assertEquals("£360 a year", v.total)
        // Put away like any idea (and, like a built-in habit, its over-usual twin isn't said instead).
        val away = MoneyCheck.view(vapes, oct16, emptyList(), MoneyIdeaState(notFor = setOf("habit-own-k1")), plan = plan)!!
        assertTrue(away.ideas.isEmpty())
        assertEquals("1 put away", away.away)
        // Fewer buys (not a habit): over usual is the idea.
        val fewer = vapes.filter { it.day < d(10, 1) } + listOf(line(d(10, 3), -3_000, "NEWS ON THE", MoneyCategory.OTHER), line(d(10, 9), -3_000, "NEWS ON THE", MoneyCategory.OTHER))
        assertEquals(listOf("over-own-k1"), MoneyCheck.ideas(fewer, oct16, emptyList(), plan = plan).map { it.id })
    }

    @Test
    fun halveVapeIsAWhatIf() {
        val base = listOf(7, 8, 9).flatMap { m ->
            listOf(
                line(d(m, 1), 250_000, "SALARY OAKFIELD", MoneyCategory.INCOME),
                line(d(m, 10), -30_000, "NANDOS", MoneyCategory.EATING_OUT),
                line(d(m, 12), -15_000, "TESCO STORES", MoneyCategory.GROCERIES),
                line(d(m, 14), -1_000, "COSTA COFFEE", MoneyCategory.COFFEE),
            )
        } + vapes
        val plain = MoneyForecast.view(base, oct16)!!
        assertFalse(plain.cuts.any { it.key == vape.key })
        val f = MoneyForecast.view(base, oct16, plan = plan)!!
        assertEquals(listOf("eating_out", "groceries", "own:k1"), f.cuts.map { it.key })
        val c = f.cuts.last()
        assertEquals("Halve Vape", c.label)
        assertEquals("+£10 a month", c.line)
        // Its pace and totals don't move; on, it adds its half to the rows.
        assertEquals(plain.pace, f.pace)
        val on = MoneyForecast.view(base, oct16, MoneyWhatIf(cuts = setOf(vape.key)), plan = plan)!!
        assertTrue(on.cuts.last().on)
        assertTrue(on.rows.first().line.contains("+£60 with your what-ifs"), on.rows.first().line)
    }
}
