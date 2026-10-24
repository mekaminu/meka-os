package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The assistant becomes Obi, slice 3: his character, his set phrases, and what of a spoken answer is shown, not said. */
class ObiCharacterTest {
    @Test
    fun theCharacterSaysWhoHeIsAndWhatHeNeverDoes() {
        val b = ObiCharacter.BIBLE
        listOf("a man", "Igbo", "wry", "British", "Right, done.", "great question", "read a list out loud", "say yes, plainly").forEach {
            assertTrue(it in b, it)
        }
        // Rule 13: no personal details in the character.
        assertTrue("Meka" !in b)
    }

    @Test
    fun aFinishedActionStartsWithRightDoneAndAFailureDoesNot() {
        val today = 20_000L
        val done = TalkRules.spokenDone(listOf(AskProposal.Timer(10)), 0, today)
        assertEquals("Right, done. Set a timer for 10 minutes. Anything else?", done)
        val mixed = TalkRules.spokenDone(listOf(AskProposal.Timer(10)), 1, today)
        assertTrue(!mixed.startsWith(ObiCharacter.DONE), mixed)
    }

    @Test
    fun theModelsDetailIsShownCleanAndNeverRepeatsWhatIsSaid() {
        val said = "Three things tomorrow, the dentist first at ten."
        val shown = ObiCharacter.shown(listOf("- 10:00 Dentist\n2. 14:30 **Parents' evening**", "", "# 18:00 Football", "18:00 Football"), said, said)
        assertEquals(listOf("10:00 Dentist", "14:30 Parents' evening", "18:00 Football"), shown)
        assertEquals(emptyList(), ObiCharacter.shown(listOf(said), said, said))
        val many = ObiCharacter.shown((1..20).map { "Line $it" }, said, said)
        assertEquals(ObiCharacter.MAX_SHOWN, many.size)
    }

    @Test
    fun withNoDetailTheSentencesCutFromWhatIsSaidAreShown() {
        val words = "Ha, fair. A heat pump moves warmth from outside into the house. Running costs depend on your tariff."
        val said = "Ha, fair. A heat pump moves warmth from outside into the house."
        assertEquals(listOf("Running costs depend on your tariff."), ObiCharacter.shown(emptyList(), words, said))
        assertEquals(emptyList(), ObiCharacter.shown(emptyList(), said, said))
    }

    @Test
    fun aLongLineIsCutAtAWord() {
        val long = "word ".repeat(60).trim()
        val line = ObiCharacter.line(long)
        assertTrue(line.length <= ObiCharacter.MAX_SHOWN_LINE, line)
        assertTrue(line.endsWith("…"))
        assertTrue(!line.contains("wor…"), line)
    }
}
