package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walk-through week 5, A17: Work mode → Commute's chip rows are rows opening sheets. */
class CommuteChoicesTest {
    private fun NotifyChoiceSheet.lit() = choices.filter { it.lit }.map { it.label }
    private val guess = CommuteSetting(80, 80, chosen = false)
    private val own = CommuteSetting(75, 90, chosen = true)

    @Test
    fun eachDirectionIsARowSayingTheTimeInUse() {
        assertEquals(NotifyChoiceRow("To work", "1 h 15", "Commute to work, 1 h 15. Opens the choices"), CommuteChoiceRules.row(own, true))
        assertEquals("1 h 30", CommuteChoiceRules.row(own, false).value)
        val g = CommuteChoiceRules.row(guess, true)
        assertEquals("About 1 h 20", g.value)
        assertTrue("Navilife's guess" in g.spoken, g.spoken)
    }

    @Test
    fun theSheetLightsTheTimeInUseTheGuessToo() {
        val s = CommuteChoiceRules.sheet(own, true)
        assertEquals("Commute to work", s.title)
        assertEquals(listOf("1 h 15"), s.lit())
        // His own odd time is offered with the usual ones, in order.
        assertEquals(CommuteRules.choices(75).map(CommuteRules::label), s.choices.map { it.label })
        assertEquals("Leave home 1 h 15 before work starts", s.choices.first { it.lit }.line)
        assertTrue(s.choices.first { it.lit }.spoken.endsWith(", chosen"))
        assertEquals(CommuteRules.NOTE, s.note)
        val h = CommuteChoiceRules.sheet(guess, false)
        assertEquals("Commute home", h.title)
        assertEquals(listOf("1 h 20"), h.lit())
        assertEquals(CommuteChoiceRules.GUESS_LINE, h.choices.first { it.lit }.line)
        assertTrue(h.choices.filterNot { it.lit }.all { it.line.startsWith("Home ") && it.line.endsWith(" after work ends") })
    }

    @Test
    fun aChoiceWritesWhatTheChipDid() {
        assertEquals(60 to 90, CommuteChoiceRules.named(own, true, "60"))
        assertEquals(75 to 120, CommuteChoiceRules.named(own, false, "120"))
        assertNull(CommuteChoiceRules.named(own, true, "75"))
        // Picking the lit guess keeps it as his own.
        assertEquals(80 to 80, CommuteChoiceRules.named(guess, true, "80"))
        assertNull(CommuteChoiceRules.named(own, true, "55"))
        assertNull(CommuteChoiceRules.named(own, true, "soon"))
    }

    private val days = listOf(
        CommuteDayRow(20_000, "Thu 26 Nov", 80, 80, changed = false, line = "Thu 26 Nov · 1 h 20 each way"),
        CommuteDayRow(20_001, "Fri 27 Nov", 100, 80, changed = true, line = "Fri 27 Nov · 1 h 40 there · 1 h 20 home · just this day"),
    )

    @Test
    fun justOneDayIsARowOpeningTheComingOfficeDays() {
        assertEquals(NotifyChoiceRow("Just one day", "Choose a day", "Just one day, Choose a day. Opens the choices"), CommuteChoiceRules.dayRow(days, null))
        assertEquals("Fri 27 Nov •", CommuteChoiceRules.dayRow(days, 20_001).value)
        val s = CommuteChoiceRules.daySheet(days, null)
        assertEquals(listOf("No day", "Thu 26 Nov", "Fri 27 Nov •"), s.choices.map { it.label })
        assertEquals(listOf("No day"), s.lit())
        assertEquals("1 h 40 there · 1 h 20 home · just this day", s.choices[2].line)
        assertEquals(listOf("Thu 26 Nov"), CommuteChoiceRules.daySheet(days, 20_000).lit())
        // A day that has passed off the list reads as none picked.
        assertEquals(listOf("No day"), CommuteChoiceRules.daySheet(days, 19_000).lit())
        assertEquals(20_001L, CommuteChoiceRules.pickedDay(days, "20001"))
        assertNull(CommuteChoiceRules.pickedDay(days, CommuteChoiceRules.NO_DAY))
        assertNull(CommuteChoiceRules.pickedDay(days, "19000"))
    }

    @Test
    fun thePickedDaysTimesOpenTheirOwnSheets() {
        val usual = days[0]
        val ownDay = days[1]
        assertEquals("1 h 20", CommuteChoiceRules.oneDayRow(usual, true).value)
        assertTrue(CommuteChoiceRules.oneDayRow(ownDay, true).spoken.startsWith("Fri 27 Nov, commute to work, 1 h 40"))
        // Lit only when the day has its own times, as its chips were.
        assertEquals(emptyList(), CommuteChoiceRules.oneDaySheet(usual, true).lit())
        val s = CommuteChoiceRules.oneDaySheet(ownDay, true)
        assertEquals("Commute to work · Fri 27 Nov", s.title)
        assertEquals(listOf("1 h 40"), s.lit())
        assertEquals("Leave home 1 h 40 before work starts on Fri 27 Nov", s.choices.first { it.lit }.line)
        assertEquals(CommuteChoiceRules.DAY_NOTE, s.note)
        assertEquals(90 to 80, CommuteChoiceRules.oneDayNamed(usual, true, "90"))
        assertEquals(80 to 80, CommuteChoiceRules.oneDayNamed(usual, true, "80"))
        assertEquals(100 to 60, CommuteChoiceRules.oneDayNamed(ownDay, false, "60"))
        assertNull(CommuteChoiceRules.oneDayNamed(ownDay, true, "100"))
        assertNull(CommuteChoiceRules.oneDayNamed(ownDay, true, "55"))
    }
}
