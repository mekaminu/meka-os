package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Talking to Ada like a conversation, slice 1: no holding line, only the orb and one late soft tone. */
class AnswerWaitTest {
    @Test
    fun adaNeverSpeaksWhileWorkingOutAnAnswer() {
        assertFalse(AnswerWaitRules.SPEAKS_WHILE_WAITING)
    }

    @Test
    fun theSoftToneWaitsTwoAndAHalfSeconds() {
        assertFalse(AnswerWaitRules.cues(300, answerStarted = false))
        assertFalse(AnswerWaitRules.cues(1_200, answerStarted = false))
        assertFalse(AnswerWaitRules.cues(2_499, answerStarted = false))
        assertTrue(AnswerWaitRules.cues(2_500, answerStarted = false))
    }

    @Test
    fun noToneOnceTheAnswerHasStarted() {
        assertFalse(AnswerWaitRules.cues(4_000, answerStarted = true))
    }
}
