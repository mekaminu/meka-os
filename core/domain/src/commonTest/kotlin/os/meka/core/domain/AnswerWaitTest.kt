package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The orb speed pass, slice 4: the thinking tick and the holding line while an answer is on its way. */
class AnswerWaitTest {
    @Test
    fun everyHoldingLineSaysHisName() {
        val lines = AnswerWaitRules.holdLines("Alex Smith")
        assertEquals("One sec, Alex…", lines.first())
        assertTrue(lines.size >= 4)
        assertTrue(lines.all { it.contains("Alex") && it.endsWith("…") })
    }

    @Test
    fun withoutANameTheLineIsJustTheWords() {
        assertEquals("One sec…", AnswerWaitRules.holdLines(null).first())
        assertEquals("One sec…", AnswerWaitRules.holdLines("  ").first())
    }

    @Test
    fun eachHoldingLineIsOneSpokenPiece() {
        AnswerWaitRules.holdLines("Alex").forEach { assertEquals(listOf(it), SpeechRules.pieces(it)) }
    }

    @Test
    fun theLinesTakeTurnsNeverTheSameTwiceRunning() {
        var last: Int? = null
        val seen = (1..8).map { AnswerWaitRules.nextIndex(last, 4).also { last = it } }
        assertEquals(listOf(0, 1, 2, 3, 0, 1, 2, 3), seen)
        seen.zipWithNext().forEach { (a, b) -> assertTrue(a != b) }
        assertEquals(0, AnswerWaitRules.nextIndex(0, 1))
    }

    @Test
    fun theTickWaitsBrieflySoQuickAnswersHaveNone() {
        assertFalse(AnswerWaitRules.cues(299, answerStarted = false))
        assertTrue(AnswerWaitRules.cues(300, answerStarted = false))
        assertFalse(AnswerWaitRules.cues(900, answerStarted = true))
    }

    @Test
    fun theHoldingLineComesAfterOnePointTwoSecondsOnlyOnce() {
        assertFalse(AnswerWaitRules.holds(1_199, answerStarted = false, held = false))
        assertTrue(AnswerWaitRules.holds(1_200, answerStarted = false, held = false))
        assertFalse(AnswerWaitRules.holds(2_000, answerStarted = false, held = true))
        assertFalse(AnswerWaitRules.holds(2_000, answerStarted = true, held = false))
    }
}
