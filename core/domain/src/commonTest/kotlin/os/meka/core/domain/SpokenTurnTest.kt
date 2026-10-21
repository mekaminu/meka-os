package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Talking to Ada like a conversation, slice 5: spoken turns are talk, not written answers. */
class SpokenTurnTest {
    @Test
    fun aChattyTurnIsLeftAsItIs() {
        val turn = "Oh nice, Meka. Chilli's on the list for Friday, so how about fajitas tonight? Quick, and the boys love them."
        assertEquals(turn, SpokenTurnRules.tidy(turn, "what should I cook tonight?"))
        assertFalse(SpokenTurnRules.isListy(turn))
        assertTrue(SpokenTurnRules.wordCount(turn) <= SpokenTurnRules.MAX_WORDS)
    }

    @Test
    fun aListWithAHeadingIsSaidAsPlainSentencesWithNoMarkers() {
        val written = "Here are some options:\n- Fajitas, quick and easy\n- Chilli from the freezer\n1. Pasta bake"
        assertTrue(SpokenTurnRules.isListy(written))
        val said = SpokenTurnRules.tidy(written, "what should I cook tonight?")
        assertEquals("Fajitas, quick and easy. Chilli from the freezer. Pasta bake.", said)
        assertFalse(SpokenTurnRules.isListy(said))
        listOf("- ", "• ", "1. ", "**", "#", "Here are").forEach { assertFalse(it in said, it) }
        assertEquals("Heat pumps or a boiler.", SpokenTurnRules.tidy("## **Heat pumps**\nHeat pumps or a boiler.", "and?"))
    }

    @Test
    fun aLongTurnIsCutToWholeSentencesUnderFortyWordsKeepingTheHandBack() {
        val long = "Ha, fair. A heat pump moves warmth from the outside air into the house, a bit like a fridge in reverse. " +
            "It runs on electricity and works best with good insulation and bigger radiators. " +
            "Running costs depend a lot on your tariff and how well the house holds heat. " +
            "Want me to note it so we can pick it up later?"
        assertTrue(SpokenTurnRules.wordCount(long) > SpokenTurnRules.MAX_WORDS)
        val said = SpokenTurnRules.tidy(long, "what's a heat pump?")
        assertTrue(said.startsWith("Ha, fair. A heat pump moves warmth"), said)
        assertTrue(said.endsWith("Want me to note it so we can pick it up later?"), said)
        assertFalse("Running costs" in said, said)
        assertTrue(SpokenTurnRules.wordCount(said) <= SpokenTurnRules.MAX_WORDS + SpokenTurnRules.HAND_BACK_SLACK)
        // Asked for detail, it is all kept (the device still says at most three sentences).
        assertEquals(long, SpokenTurnRules.tidy(long, "Explain how a heat pump works in detail"))
        // A look-up of his own day is never cut, so no event is lost.
        assertEquals(long, SpokenTurnRules.tidy(long, "what's on tomorrow?", cut = false))
        assertTrue(SpokenTurnRules.wantsDetail("tell me more"))
        assertTrue(SpokenTurnRules.wantsDetail("Walk me through it"))
        assertFalse(SpokenTurnRules.wantsDetail("what's on tomorrow?"))
    }

    @Test
    fun theFirstSentenceIsAlwaysKeptEvenWhenItIsLong() {
        val one = (1..50).joinToString(" ") { "word" } + "."
        assertEquals(one, SpokenTurnRules.tidy("$one Second sentence here.", "hi"))
        assertEquals("", SpokenTurnRules.tidy("  ", "hi"))
    }
}
