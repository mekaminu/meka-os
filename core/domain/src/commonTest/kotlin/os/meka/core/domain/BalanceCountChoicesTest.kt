package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walk-through week 5, A17: Balance's "Count as" is a row per event opening a sheet, not an open chip row. */
class BalanceCountChoicesTest {
    private fun row(area: BalanceArea, minutes: Int) = BalanceRow(
        area = area, label = area.label, minutes = minutes, targetMin = area.defaultMin ?: 0, pct = 0, line = "", status = null,
        lit = false, items = emptyList(), moreItems = 0, choices = area.choices, defaultTarget = true, spoken = area.label,
    )

    private val swim = BalanceUnclear("swim club", "Swim club", "2 events · Tue, Thu · 2 h", 120)
    private val rows = listOf(row(BalanceArea.WORK, 36 * 60), row(BalanceArea.FAMILY, 6 * 60 + 30), row(BalanceArea.HEALTH, 0))

    @Test
    fun theRowNamesTheEventAndWhenAndOpensTheChoices() {
        val r = BalanceCountChoiceRules.row(swim)
        assertEquals("Swim club", r.title)
        assertEquals("2 events · Tue, Thu · 2 h", r.value)
        assertTrue(r.spoken.endsWith("not counted. Opens Count as"))
    }

    @Test
    fun theSheetOffersTheFiveChipsInOrderEachSayingWhatItDoesToTheWeek() {
        val s = BalanceCountChoiceRules.sheet(swim, rows)
        assertEquals("Count “Swim club” as", s.title)
        assertEquals(BalanceRules.countAsCodes(), s.choices.map { it.name })
        assertEquals(listOf("Family", "Health", "Self", "Work", "Don't count"), s.choices.map { it.label })
        assertEquals("Adds 2 h · Family 6 h 30 → 8 h 30", s.choices[0].line)
        assertEquals("Adds 2 h · Health 0 h → 2 h", s.choices[1].line)
        // An area with no row this week starts from nothing.
        assertEquals("Adds 2 h · Self 0 h → 2 h", s.choices[2].line)
        assertEquals("Adds 2 h · Work 36 h → 38 h", s.choices[3].line)
        assertEquals(BalanceCountChoiceRules.DONT_COUNT_LINE, s.choices[4].line)
        assertTrue(s.choices.none { it.lit })
        assertTrue(s.note.contains("Undo"))
    }

    @Test
    fun aChoiceGoesBackAsItsChipsCodeAndNothingElse() {
        assertEquals("HEALTH", BalanceCountChoiceRules.named("HEALTH"))
        assertEquals(BalanceRules.DONT_COUNT, BalanceCountChoiceRules.named(BalanceRules.DONT_COUNT))
        assertNull(BalanceCountChoiceRules.named("CLEAR"))
        assertNull(BalanceCountChoiceRules.named("Health"))
    }

    @Test
    fun aLongTitleIsShortInTheSheetsTitle() {
        val long = swim.copy(title = "A".repeat(70))
        assertEquals("Count “${"A".repeat(40)}” as", BalanceCountChoiceRules.title(long))
    }
}
