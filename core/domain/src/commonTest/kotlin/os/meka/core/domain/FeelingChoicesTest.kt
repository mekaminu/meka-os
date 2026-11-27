package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Walk-through week 5, A17: Shut down's feeling is a row opening a sheet, not an open chip row. */
class FeelingChoicesTest {
    @Test fun theRowSaysTheFeelingPickedOrNotSaid() {
        val none = FeelingChoiceRules.row(null)
        assertEquals("How are you feeling?", none.title)
        assertEquals("Not said", none.value)
        assertEquals("Tired", FeelingChoiceRules.row(Feeling.TIRED).value)
        assertEquals("How are you feeling, Tired. Opens the choices", FeelingChoiceRules.row(Feeling.TIRED).spoken)
    }

    @Test fun theSheetSaysWhatEachFeelingOffers() {
        val sheet = FeelingChoiceRules.sheet(Feeling.STRETCHED)
        assertEquals(listOf("Good", "Tired", "Stretched"), sheet.choices.map { it.label })
        assertEquals(listOf("Stretched"), sheet.choices.filter { it.lit }.map { it.label })
        assertEquals("Tomorrow stays as it is", sheet.choices[0].line)
        assertEquals("Offers to move up to 2 of tomorrow's tasks to lighter days", sheet.choices[1].line)
        assertEquals("Stretched, " + sheet.choices[2].line + ", chosen", sheet.choices[2].spoken)
        assertEquals(emptyList(), FeelingChoiceRules.sheet(null).choices.filter { it.lit })
    }

    @Test fun pickingTheFeelingAlreadyPickedDoesNothing() {
        assertEquals(Feeling.TIRED, FeelingChoiceRules.named(null, "TIRED"))
        assertEquals(Feeling.GOOD, FeelingChoiceRules.named(Feeling.TIRED, "GOOD"))
        assertNull(FeelingChoiceRules.named(Feeling.TIRED, "TIRED"))
        assertNull(FeelingChoiceRules.named(null, "ELATED"))
    }
}
