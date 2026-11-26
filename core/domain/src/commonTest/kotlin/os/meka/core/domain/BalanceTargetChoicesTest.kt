package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walk-through week 5, A17: Balance's targets are a row per area opening a sheet, not an open chip row. */
class BalanceTargetChoicesTest {
    private fun row(area: BalanceArea, minutes: Int, target: Int? = null) = BalanceRow(
        area = area, label = area.label, minutes = minutes, targetMin = target ?: area.defaultMin ?: (37 * 60 + 30), pct = 0,
        line = "", status = null, lit = false, items = emptyList(), moreItems = 0,
        choices = if (area == BalanceArea.WORK) listOf(BalanceRules.YOUR_HOURS) + area.choices else area.choices,
        defaultTarget = target == null, spoken = area.label,
    )

    @Test
    fun theRowSaysWhatIsChosenAndOpensTheChoices() {
        val family = BalanceTargetChoiceRules.row(row(BalanceArea.FAMILY, 6 * 60 + 30))
        assertEquals("Family", family.title)
        assertEquals("8 h a week", family.value)
        assertTrue(family.spoken.endsWith("Opens its choices"))
        assertEquals("Your hours", BalanceTargetChoiceRules.row(row(BalanceArea.WORK, 36 * 60)).value)
        assertEquals("40 h a week", BalanceTargetChoiceRules.row(row(BalanceArea.WORK, 36 * 60, 40 * 60)).value)
    }

    @Test
    fun theSheetOffersTheChipsInOrderTheChosenOneLitEachSayingWhatItMeans() {
        val s = BalanceTargetChoiceRules.sheet(row(BalanceArea.FAMILY, 6 * 60 + 30))
        assertEquals("Family target a week", s.title)
        assertEquals(listOf("4 h", "6 h", "8 h", "10 h", "14 h"), s.choices.map { it.label })
        assertEquals(listOf("240", "360", "480", "600", "840"), s.choices.map { it.name })
        assertEquals(listOf("8 h"), s.choices.filter { it.lit }.map { it.label })
        assertEquals("6 h 30 counted · met", s.choices[1].line)
        assertEquals("6 h 30 counted · 1 h 30 short · the usual", s.choices[2].line)
        assertTrue(s.note.contains("both devices"))
    }

    @Test
    fun worksSheetStartsWithYourHoursAndSaysOverOrWithin() {
        val s = BalanceTargetChoiceRules.sheet(row(BalanceArea.WORK, 42 * 60))
        assertEquals(listOf("Your hours", "35 h", "40 h", "45 h", "50 h"), s.choices.map { it.label })
        assertEquals(listOf("Your hours"), s.choices.filter { it.lit }.map { it.label })
        assertEquals(BalanceTargetChoiceRules.YOUR_HOURS_LINE, s.choices[0].line)
        assertEquals("42 h counted · 2 h over", s.choices[2].line)
        assertEquals("42 h counted · within it", s.choices[3].line)
        // A set 40 h lights 40 h, not Your hours.
        val set = BalanceTargetChoiceRules.sheet(row(BalanceArea.WORK, 42 * 60, 40 * 60))
        assertEquals(listOf("40 h"), set.choices.filter { it.lit }.map { it.label })
    }

    @Test
    fun aChoiceGoesBackAsItsMinutesOnlyWhenItChangesSomething() {
        val r = row(BalanceArea.HEALTH, 60)
        assertEquals(4 * 60, BalanceTargetChoiceRules.named(r, "240"))
        assertNull(BalanceTargetChoiceRules.named(r, "180"), "already the target")
        assertNull(BalanceTargetChoiceRules.named(r, "999"))
        assertNull(BalanceTargetChoiceRules.named(r, "Health"))
        assertEquals(BalanceRules.YOUR_HOURS, BalanceTargetChoiceRules.named(row(BalanceArea.WORK, 0, 40 * 60), "0"))
    }
}
