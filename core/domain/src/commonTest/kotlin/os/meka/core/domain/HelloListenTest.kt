package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The orb speed pass, slice 7: the mic is open while the hello is still speaking. */
class HelloListenTest {
    private val hello = "Good evening, Alex. You're clear for the rest of the day. Anything you'd like?"

    @Test
    fun opensWithHeadphonesAndTalkOverOnly() {
        assertTrue(HelloListenRules.opensMic(talkOver = true, output = BargeInOutput.HEADPHONES))
        assertFalse(HelloListenRules.opensMic(talkOver = true, output = BargeInOutput.SPEAKER))
        assertFalse(HelloListenRules.opensMic(talkOver = false, output = BargeInOutput.HEADPHONES))
    }

    @Test
    fun theHellosEchoIsTakenOut() {
        assertEquals("what's on tomorrow", HelloListenRules.his("anything you'd like what's on tomorrow", hello))
        assertEquals("good thanks", HelloListenRules.his("Alex you're clear good thanks", hello))
        assertEquals("", HelloListenRules.his("good evening Alex", hello))
        assertTrue(HelloListenRules.onlyEcho("rest of the day", hello))
        assertFalse(HelloListenRules.onlyEcho("", hello))
    }

    @Test
    fun hisOwnWordsStayAsHeSaidThem() {
        // One word that is also in the hello stays his; so does everything after his first word of his own.
        assertEquals("good thanks, you?", HelloListenRules.his("good thanks, you?", hello))
        assertEquals("what's the day like", HelloListenRules.his("what's the day like", hello))
        assertEquals("add milk", HelloListenRules.his("add milk", ""))
    }

    @Test
    fun twoWordsStopTheHelloOneAfterIt() {
        assertFalse(HelloListenRules.isHim("anything you'd like um", hello, playing = true))
        assertTrue(HelloListenRules.isHim("anything you'd like what's on", hello, playing = true))
        assertTrue(HelloListenRules.isHim("weather", hello, playing = false))
        assertFalse(HelloListenRules.isHim("good evening", hello, playing = false))
    }

    @Test
    fun theSpeakerTriggersQuickerDuringTheHello() {
        assertEquals(240L, HelloListenRules.tuning(BargeInOutput.SPEAKER, hello = true).sustainMs)
        assertEquals(BargeInRules.SPEAKER, HelloListenRules.tuning(BargeInOutput.SPEAKER, hello = false))
        assertEquals(BargeInRules.HEADPHONES, HelloListenRules.tuning(BargeInOutput.HEADPHONES, hello = true))
    }

    @Test
    fun answeringOverTheHelloListensWithoutAFreshListen() {
        val greeted = TalkFlow.greet(hello)
        val over = TalkFlow.overHello(greeted.session)
        assertEquals(TalkPhase.LISTENING, over.session.phase)
        assertEquals(listOf<TalkEffect>(TalkEffect.StopSpeaking), over.effects)
        val ended = TalkFlow.spokeListening(greeted.session)
        assertEquals(TalkPhase.LISTENING, ended.session.phase)
        assertTrue(ended.effects.isEmpty())
        // Not speaking: nothing happens.
        assertTrue(TalkFlow.overHello(over.session).effects.isEmpty())
    }
}
