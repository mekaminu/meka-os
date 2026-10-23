package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PhraseGlueTest {
    private val nb = LifeTileRules.GLUE

    @Test
    fun aPhoneNumberIsKeptWhole() {
        assertEquals("Sam two doors down, 07700${nb}900123", PhraseGlue.kept("Sam two doors down, 07700 900123"))
        assertEquals("Dr Patel, +44${nb}7700${nb}900123", PhraseGlue.kept("Dr Patel, +44 7700 900123"))
    }

    @Test
    fun shortNumbersAndPlainWordsAreLeftAlone() {
        assertEquals("Ring 999 or 111", PhraseGlue.kept("Ring 999 or 111"))
        assertEquals("the grey box file in the loft", PhraseGlue.kept("the grey box file in the loft"))
    }

    @Test
    fun countsKeepTheirUnit() {
        assertEquals("Mon 26 – Fri${nb}30${nb}Oct · 5${nb}work${nb}days", PhraseGlue.kept("Mon 26 – Fri 30 Oct · 5 work days"))
        assertEquals("4${nb}of${nb}5 booked", PhraseGlue.kept("4 of 5 booked"))
        assertEquals("Fri${nb}13${nb}Nov · 1${nb}day", PhraseGlue.kept("Fri 13 Nov · 1 day"))
    }

    @Test
    fun rangesFindEachPhrase() {
        val line = "Sam, 07700 900123 · 5 work days"
        val found = PhraseGlue.ranges(PhraseGlue.kept(line)).map { line.substring(it.first, it.last + 1) }.toSet()
        assertEquals(setOf("07700 900123", "5 work days"), found)
    }

    @Test
    fun rowsDrawTheGluedWords() {
        val row = EmergencyRow("1", "call", "Neighbour", "Sam, 07700 900123", "07700900123", "Neighbour. Sam, 07700 900123")
        assertEquals("Sam, 07700${nb}900123", row.detailShown)
        assertNull(row.copy(detail = null).detailShown)
    }
}
