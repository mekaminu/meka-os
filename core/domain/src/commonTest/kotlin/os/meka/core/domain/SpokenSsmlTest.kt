package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Talking to Obi like a conversation, slice 7: a little pause after a reaction, a touch quicker, nothing becomes a tag. */
class SpokenSsmlTest {
    @Test
    fun aReactionThatOpensAPieceGetsASmallPause() {
        assertEquals("Oh nice.", SpokenSsmlRules.reaction("Oh nice. What sort are you thinking of?"))
        assertEquals("Ah, that's annoying.", SpokenSsmlRules.reaction("Ah, that's annoying. Want me to move it?"))
        assertEquals("Honestly?", SpokenSsmlRules.reaction("Honestly? I'd go for the heat pump."))
        // A longer first sentence, a reaction alone (nothing follows), times and numbers: no pause.
        assertNull(SpokenSsmlRules.reaction("The dentist is at three tomorrow. Shall I remind you?"))
        assertNull(SpokenSsmlRules.reaction("Oh nice."))
        assertNull(SpokenSsmlRules.reaction("At 3.30 then."))
        assertNull(SpokenSsmlRules.reaction(""))
        assertEquals(
            """<speak><prosody rate="106%">Oh nice.<break time="250ms"/> What sort are you thinking of?</prosody></speak>""",
            SpokenSsmlRules.ssml("Oh nice.  What sort are you thinking of?"),
        )
        assertEquals("""<speak><prosody rate="106%">Your train is on time.</prosody></speak>""", SpokenSsmlRules.ssml("Your train is on time."))
    }

    @Test
    fun nothingInTheWordsCanBecomeATag() {
        val s = SpokenSsmlRules.ssml("Fish & chips <break time=\"9s\"/> at Sam's \"place\" > 3")
        assertFalse("<break time=\"9s\"" in s, s)
        assertTrue("Fish &amp; chips &lt;break time=&quot;9s&quot;/&gt; at Sam&apos;s &quot;place&quot; &gt; 3" in s, s)
        assertEquals(1, Regex("<speak>").findAll(s).count())
    }
}
