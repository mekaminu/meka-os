package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScreenSenseRulesTest {
    private fun whys(text: String) = ScreenSenseRules.problemsIn(text).map { it.why }

    @Test
    fun theFastingZeroesAreCaught() {
        val p = ScreenSenseRules.problems(listOf("Last fast 0 m · average 0 m · longest 0 m"))
        assertEquals(3, p.size)
        assertTrue(p.all { it.why.startsWith("a 0 length") })
        // A real length with a 0 in it, and a timer at rest, read fine.
        assertTrue(whys("longest 1h 0m").isEmpty())
        assertTrue(whys("Average 10 m").isEmpty())
        assertTrue(whys("0 m").isEmpty())
    }

    @Test
    fun impossibleAndEmptyCounts() {
        assertEquals(listOf("more done than there are (“3 of 2”)"), whys("3 of 2 done"))
        assertEquals(listOf("an empty count shown as a figure (“0 of 0”)"), whys("0 of 0 done"))
        assertTrue(whys("2 of 5 done").isEmpty())
        assertTrue(whys("1 of 3").isEmpty())
    }

    @Test
    fun dashesCodeValuesTemplatesAndNegatives() {
        assertEquals(listOf("a dash where a figure belongs"), whys(" — "))
        assertEquals(listOf("a programming value on screen"), whys("Next: null"))
        assertEquals(listOf("a programming value on screen"), whys("NaN h free"))
        assertEquals(listOf("an unfilled template"), whys("Hello, %s"))
        assertEquals(listOf("an unfilled template"), whys("Morning, {name}"))
        assertEquals(listOf("a negative amount"), whys("-15 m free"))
        // Ranges, times, money and percentages are fine.
        assertTrue(whys("19:00–21:00 · 2h free").isEmpty())
        assertTrue(whys("Spending -5% vs last month").isEmpty())
        assertTrue(whys("£-12.40").isEmpty())
        assertTrue(whys("09:30 · Dentist").isEmpty())
        assertTrue(whys("A-12 bus").isEmpty())
        assertTrue(whys("").isEmpty())
    }

    @Test
    fun theReportNamesEachOne() {
        val r = ScreenSenseRules.report(ScreenSenseRules.problems(listOf("—", "fine", "3 of 2")))
        assertEquals("• “—”: a dash where a figure belongs\n• “3 of 2”: more done than there are (“3 of 2”)", r)
    }
}
