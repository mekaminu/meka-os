package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The orb says hello like a person, slice 3: Obi answers "good thanks, you?" like a person, then hands back. */
class HelloReplyTest {
    private val asked = "Evening, Alex. Good day? What can I do for you?"
    private val plain = "Morning, Alex. What's first?"

    @Test
    fun smallTalkIsReadForItsMoodAndWhetherHeAskedBack() {
        assertEquals(HelloSmallTalk(HelloMood.GOOD, true), HelloReplyRules.smallTalk("Good thanks, you?"))
        assertEquals(HelloSmallTalk(HelloMood.GOOD, false), HelloReplyRules.smallTalk("I'm good, thanks"))
        assertEquals(HelloSmallTalk(HelloMood.GOOD, true), HelloReplyRules.smallTalk("yeah not bad at all, how about you?"))
        assertEquals(HelloSmallTalk(HelloMood.OK, false), HelloReplyRules.smallTalk("Not bad"))
        assertEquals(HelloSmallTalk(HelloMood.OK, true), HelloReplyRules.smallTalk("alright, and yourself?"))
        assertEquals(HelloSmallTalk(HelloMood.LOW, false), HelloReplyRules.smallTalk("Shattered, it's been a long day"))
        assertEquals(HelloSmallTalk(HelloMood.LOW, true), HelloReplyRules.smallTalk("not great honestly, you?"))
        assertEquals(HelloSmallTalk(HelloMood.GOOD, false), HelloReplyRules.smallTalk("busy but good"))
        assertEquals(HelloSmallTalk(HelloMood.GOOD, false), HelloReplyRules.smallTalk("Thank you, I'm very well"))
    }

    @Test
    fun anythingMoreThanSmallTalkIsNotTaken() {
        listOf(
            "good thanks, what's on tomorrow?", "fine, add milk to the shopping", "no thanks, bye", "that's all",
            "yes", "thanks", "you", "what can you do?", "how's the weather?", "I'm good, can you call Sam",
        ).forEach { assertNull(HelloReplyRules.smallTalk(it), it) }
    }

    @Test
    fun theHelloMustHaveAskedHowHeIsUnlessHeAskedAfterAda() {
        assertTrue(HelloReplyRules.askedAfterHim(asked))
        assertTrue(!HelloReplyRules.askedAfterHim(plain))
        assertTrue(!HelloReplyRules.askedAfterHim("Good evening, Alex. Nothing needs you. Your evening's clear. Anything you'd like?"))
        assertTrue(HelloReplyRules.askedAfterHim("Hey Alex, how's the evening treating you? Your train is at 07:42. Anything I can help with?"))
        assertTrue(HelloReplyRules.reply(asked, "I'm good, thanks")!!.endsWith("What can I do for you?"))
        assertNull(HelloReplyRules.reply(plain, "I'm good, thanks"))
        assertTrue(HelloReplyRules.reply(plain, "good, you?")!!.contains("thank"))
    }

    @Test
    fun theLinesSuitTheMoodHandBackAndNameNobody() {
        HelloMood.entries.forEach { mood ->
            listOf(false, true).forEach { back ->
                (0..1).forEach { seed ->
                    val line = HelloReplyRules.answer(HelloSmallTalk(mood, back), seed)
                    assertTrue(line.endsWith("?"), line)
                    assertTrue(!line.contains("{n}") && !line.contains("Alex"), line)
                    if (back) assertTrue(line.contains("thank"), line)
                    if (mood == HelloMood.LOW) assertTrue(line.contains("Sorry") || line.contains("easy"), line)
                }
            }
        }
        // The two lines of a pool both come up.
        val talk = HelloSmallTalk(HelloMood.GOOD, false)
        assertTrue(HelloReplyRules.answer(talk, 0) != HelloReplyRules.answer(talk, 1))
        // Every piece is made ahead.
        val warm = HelloReplyRules.warmLines()
        assertTrue(warm.isNotEmpty())
        assertTrue(SpeechRules.pieces(HelloReplyRules.answer(HelloSmallTalk(HelloMood.LOW, true), 0)).all { it in warm })
    }

    @Test
    fun talkAnswersTheHelloAndListensAgainWithoutAsking() {
        val greeted = TalkFlow.spoke(TalkFlow.greet(asked).session).session
        val step = TalkFlow.heard(greeted, "Not bad, you?")
        val say = assertIs<TalkEffect.Speak>(step.effects.single())
        assertTrue(say.text.contains("thank"))
        assertEquals(TalkPhase.SPEAKING, step.session.phase)
        assertEquals(0, step.session.questions)
        assertEquals("Not bad, you?", step.session.conversation.turns.last().question)
        // Then he asks: a question as usual, with the hello and the small talk in its history.
        val listening = TalkFlow.spoke(step.session).session
        val ask = assertIs<TalkEffect.Ask>(TalkFlow.heard(listening, "what's on tomorrow?").effects.single())
        assertEquals(2, ask.history.size)
        // A second "I'm good, thanks" later in the conversation is a goodbye again.
        assertEquals(listOf<TalkEffect>(TalkEffect.End), TalkFlow.heard(listening, "I'm good, thanks").effects)
    }

    @Test
    fun aGoodbyeOrAQuestionAfterTheHelloIsAsBefore() {
        val greeted = TalkFlow.spoke(TalkFlow.greet(asked).session).session
        assertEquals(listOf<TalkEffect>(TalkEffect.End), TalkFlow.heard(greeted, "no thanks, bye").effects)
        assertIs<TalkEffect.Ask>(TalkFlow.heard(greeted, "good thanks, what's on tomorrow?").effects.single())
        // A hello that didn't ask how he is: "I'm good, thanks" still means no thanks.
        val plainly = TalkFlow.spoke(TalkFlow.greet(plain).session).session
        assertEquals(listOf<TalkEffect>(TalkEffect.End), TalkFlow.heard(plainly, "I'm good, thanks").effects)
        // Not after a plain Talk start either.
        assertEquals(listOf<TalkEffect>(TalkEffect.End), TalkFlow.heard(TalkFlow.start().session, "I'm good, thanks").effects)
    }
}
