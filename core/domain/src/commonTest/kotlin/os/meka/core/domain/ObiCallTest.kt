package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObiCallTest {
    @Test
    fun everyGreetingOpensWithHisNameAndThatHesAnAi() {
        assertEquals("Hi, this is Obi, Meka's AI assistant.", ObiCallRules.OPENING)
        assertTrue(CallAssistantScript.greeting(atWork = true).startsWith(ObiCallRules.OPENING))
        assertTrue(CallAssistantScript.greeting(atWork = false).startsWith(ObiCallRules.OPENING))
        assertFalse("Navilife" in CallAssistantScript.GREETING)
        assertEquals("I am, yes, Meka's AI assistant.", ObiCallRules.AI_ANSWER)
    }

    @Test
    fun theWaysPeopleAskIfTheyreTalkingToAMachine() {
        listOf(
            "Are you a robot?", "are you real", "Are you AI?", "are you an AI", "Is this a recording?", "Is this a robot or a person?",
            "is that a real person", "Am I talking to a real person?", "am I speaking with a machine", "Is there a real person there?",
            "You're a bot, aren't you?", "hello? are you a human?", "Is this an automated service?", "is this AI", "Are you a computer?",
        ).forEach { assertTrue(ObiCallRules.asksIfAi(it), it) }
    }

    @Test
    fun ordinaryWordsAreNotTheQuestion() {
        listOf(
            null, "", "Hi, it's the garage about your car", "yes it is", "no it can wait", "Is it urgent? Not really",
            "it's real urgent, call me", "This is Sam from Oakfield School", "are you around tomorrow", "is this Meka's phone",
            "you are welcome", "it's a person from the bank",
        ).forEach { assertFalse(ObiCallRules.asksIfAi(it), it.toString()) }
    }

    @Test
    fun whatsLeftOnceTheQuestionIsTakenOut() {
        assertNull(ObiCallRules.withoutQuestion("Hello? Are you a robot?"))
        assertNull(ObiCallRules.withoutQuestion("is this a real person or not"))
        assertEquals("yes", ObiCallRules.withoutQuestion("are you a robot? yes"))
        assertEquals("it's sam from the garage", ObiCallRules.withoutQuestion("Are you a robot? It's Sam from the garage"))
        // So "is it urgent?" still reads the yes or no that came with the question.
        assertEquals(true, CallAssistantRules.isUrgentAnswer(null, ObiCallRules.withoutQuestion("Am I talking to a machine? Yes it's urgent")))
        assertNull(CallAssistantRules.isUrgentAnswer(null, ObiCallRules.withoutQuestion("Am I talking to a machine?")))
        // Who they said they were still counts as an introduction.
        assertTrue(CallIntroRules.real(ObiCallRules.withoutQuestion("Are you a robot? It's Sam from the garage")))
        assertFalse(CallIntroRules.real(ObiCallRules.withoutQuestion("hello are you a robot")))
    }
}
