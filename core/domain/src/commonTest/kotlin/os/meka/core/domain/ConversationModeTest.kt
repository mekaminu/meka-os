package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Talking to Obi like a conversation, slice 6: the microphone stays open after he speaks. */
class ConversationModeTest {
    @Test
    fun theMicStaysOpenEightSecondsAfterHerLine() {
        val at = 10_000L
        // The recogniser giving up early inside the window opens it again.
        assertTrue(ConversationModeRules.listenAgain(at, at + 3_000, heard = false))
        assertTrue(ConversationModeRules.listenAgain(at, at + 7_999, heard = false))
        assertFalse(ConversationModeRules.listenAgain(at, at + 8_000, heard = false))
        // Once he has said something, it's his turn, not a window.
        assertFalse(ConversationModeRules.listenAgain(at, at + 3_000, heard = true))
        // Not after one of his lines (he tapped the mic): the recogniser's own end stands.
        assertFalse(ConversationModeRules.listenAgain(null, at, heard = false))

        assertFalse(ConversationModeRules.lapsed(at, at + 7_999, heard = false))
        assertTrue(ConversationModeRules.lapsed(at, at + 8_000, heard = false))
        assertFalse(ConversationModeRules.lapsed(at, at + 30_000, heard = true))
        assertFalse(ConversationModeRules.lapsed(null, at + 30_000, heard = false))
    }

    @Test
    fun afterHerAnswerTalkListensAgainWithoutATap() {
        val asked = TalkFlow.heard(TalkFlow.start().session, "what's the weather tomorrow")
        val answered = TalkFlow.answered(
            asked.session, "what's the weather tomorrow",
            AskOutcome.Answered(AskAnswer("Dry and bright, about 14. Off anywhere nice?", emptyList())), today = 0,
        )
        val next = TalkFlow.spoke(answered.session)
        assertEquals(TalkPhase.LISTENING, next.session.phase)
        assertEquals(listOf<TalkEffect>(TalkEffect.Listen), next.effects)
        // Silence at the end of the window ends it quietly.
        assertEquals(TalkPhase.ENDED, TalkFlow.silence(next.session).session.phase)
    }

    @Test
    fun thanksAdaEndsTheConversation() {
        for (bye in listOf("Thanks, Obi", "thank you Obi", "cheers Obi", "that's all Obi", "bye Obi", "thanks Obie", "OK thanks Obi")) {
            assertTrue(TalkRules.isEnding(bye), bye)
            assertIs<TalkReply.End>(TalkRules.reply(bye, pendingCards = 0), bye)
        }
        // A question with his name in it is still a question.
        assertIs<TalkReply.Ask>(TalkRules.reply("Obi, what's on tomorrow?", pendingCards = 0))
        // "Yes please, Obi" with a card on offer confirms it.
        assertIs<TalkReply.Confirm>(TalkRules.reply("yes please Obi", pendingCards = 1))
    }

    @Test
    fun theOrbSaysCarryOnWhileTheWindowIsOpen() {
        assertEquals(ConversationModeRules.LINE, ConversationModeRules.label(TalkPhase.LISTENING, mac = false, talkOver = true, followOn = true))
        assertEquals("Listening…", ConversationModeRules.label(TalkPhase.LISTENING, mac = false, talkOver = true, followOn = false))
        assertEquals(TalkOrb.label(TalkPhase.SPEAKING, mac = true, talkOver = true),
            ConversationModeRules.label(TalkPhase.SPEAKING, mac = true, talkOver = true, followOn = true))
    }
}
