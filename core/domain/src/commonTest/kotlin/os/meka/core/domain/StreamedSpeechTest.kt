package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The companion, slice 9: the answer's first sentence is said while the rest is still being written. */
class StreamedSpeechTest {
    private val today = AskRules.parseDay("2026-10-08")!!

    @Test
    fun theAnswersWordsAreReadFromTheModelsJsonAsItIsWritten() {
        assertNull(StreamedSpeechRules.partialAnswer(""))
        assertNull(StreamedSpeechRules.partialAnswer("""{"answ"""))
        assertEquals(StreamedSpeechRules.Partial("", false), StreamedSpeechRules.partialAnswer("""{"answer": """"))
        assertEquals(StreamedSpeechRules.Partial("It's \"blue\"\nbecause", false), StreamedSpeechRules.partialAnswer("""{"answer":"It's \"blue\"\nbecause"""))
        // An escape cut off at the end is left out until it is whole.
        assertEquals(StreamedSpeechRules.Partial("Caf", false), StreamedSpeechRules.partialAnswer("""{"answer":"Caf\u00"""))
        assertEquals(StreamedSpeechRules.Partial("Café", false), StreamedSpeechRules.partialAnswer("""{"answer":"Café"""))
        assertEquals(StreamedSpeechRules.Partial("Back slash", false), StreamedSpeechRules.partialAnswer("{\"answer\":\"Back slash\\"))
        assertEquals(StreamedSpeechRules.Partial("Done.", true), StreamedSpeechRules.partialAnswer("""{"answer":"Done.","actions":["""))
        // In a code fence too; not plain words, and not "answer" inside another field's words.
        assertEquals("Hi", StreamedSpeechRules.partialAnswer("```json\n{\"answer\":\"Hi")?.text)
        assertNull(StreamedSpeechRules.partialAnswer("The answer is 42"))
        assertNull(StreamedSpeechRules.partialAnswer("""Sure: "answer":"x" {"""))
    }

    @Test
    fun theFirstSentenceGoesOnlyOnceItIsWholeAndTheNextHasBegun() {
        val full = """{"answer":"The sky looks blue because of Rayleigh scattering. Shorter blue light bounces off the air far more than red, so it reaches you from every direction.","actions":[]}"""
        // Still writing the first sentence, or it has ended but the next hasn't begun: nothing yet.
        assertNull(StreamedSpeechRules.earlyPiece(full.take(40)))
        assertNull(StreamedSpeechRules.earlyPiece(full.substringBefore(" Shorter") + " "))
        // The next sentence has begun: the first piece, exactly as the device will say it first.
        val early = StreamedSpeechRules.earlyPiece(full.substringBefore("light") )
        assertEquals("The sky looks blue because of Rayleigh scattering.", early)
        assertEquals(TalkRules.firstPiece(AskCodecLike.answer(full)), early)
        // A one-sentence answer goes once its words close.
        assertEquals("Done.", StreamedSpeechRules.earlyPiece("""{"answer":"Done.""""))
        assertNull(StreamedSpeechRules.earlyPiece("""{"answer":"Done"""))
        // Times and list marks are said as the device says them ("10:00 to 11:00").
        assertEquals("It's on Friday, 10:00 to 11:00.", StreamedSpeechRules.earlyPiece("""{"answer":"It's on Friday · 10:00–11:00. Bring yo"""))
        // "e.g. " and "3.5 " don't end a sentence.
        assertNull(StreamedSpeechRules.earlyPiece("""{"answer":"Try a hobby, e.g. painting"""))
        assertNull(StreamedSpeechRules.earlyPiece("""{"answer":"""""))
    }

    @Test
    fun theRestIsWhatFollowsTheEarlyPieceNeverItAgain() {
        assertEquals("Shorter blue light scatters more. Shall I add Read up for tomorrow?",
            StreamedSpeechRules.rest("Rayleigh scattering.  Shorter blue light scatters more. Shall I add Read up for tomorrow?", "Rayleigh scattering."))
        assertEquals("", StreamedSpeechRules.rest("Done.", "Done."))
        // The device's checks changed the words: everything after its own first piece.
        assertEquals("Then this.", StreamedSpeechRules.rest("Something else. Then this.", "Rayleigh scattering."))
    }

    @Test
    fun talkSpeaksTheEarlyPieceThenOnlyTheRestThenListens() {
        var step = TalkFlow.heard(TalkFlow.start().session, "why is the sky blue")
        val ask = assertIs<TalkEffect.Ask>(step.effects.single())
        step = TalkFlow.early(step.session, "Rayleigh scattering.")
        assertEquals(TalkPhase.SPEAKING, step.session.phase)
        assertEquals(listOf<TalkEffect>(TalkEffect.SpeakEarly("Rayleigh scattering.")), step.effects)
        // Only once a turn; and the early piece ending isn't the line's end.
        assertTrue(TalkFlow.early(step.session, "Again.").effects.isEmpty())
        assertTrue(TalkFlow.spoke(step.session).effects.isEmpty())
        step = TalkFlow.answered(step.session, ask.question, AskOutcome.Answered(AskAnswer("Rayleigh scattering. Blue scatters most.", emptyList())), today)
        assertEquals(listOf<TalkEffect>(TalkEffect.SpeakRest("Blue scatters most.")), step.effects)
        assertNull(step.session.early)
        assertEquals("Rayleigh scattering. Blue scatters most.", step.session.conversation.history.last().answer)
        step = TalkFlow.spoke(step.session)
        assertEquals(listOf<TalkEffect>(TalkEffect.Listen), step.effects)
    }

    @Test
    fun anEarlyPieceWithNothingMoreOrAFailureOrBargeIn() {
        val thinking = TalkFlow.heard(TalkFlow.start().session, "is it done").session
        val early = TalkFlow.early(thinking, "Done.").session
        // Nothing more to say: an empty rest still reports the end, so Talk listens again.
        val done = TalkFlow.answered(early, "is it done", AskOutcome.Answered(AskAnswer("Done.", emptyList())), today)
        assertEquals(listOf<TalkEffect>(TalkEffect.SpeakRest("")), done.effects)
        // A failure after the early piece says its whole line, and ends.
        val failed = TalkFlow.answered(early, "is it done", AskOutcome.Unavailable("Navilife's AI is off."), today)
        assertEquals(listOf<TalkEffect>(TalkEffect.SpeakRest("Navilife's AI is off.")), failed.effects)
        assertTrue(failed.session.endAfterSpeaking)
        // Talking over it: Navilife stops and listens, and the answer arriving later is left.
        val over = TalkFlow.bargeIn(early)
        assertEquals(TalkPhase.LISTENING, over.session.phase)
        assertNull(over.session.early)
        assertTrue(TalkFlow.answered(over.session, "is it done", AskOutcome.Answered(AskAnswer("Done.", emptyList())), today).effects.isEmpty())
        // Not while listening or speaking something else.
        assertTrue(TalkFlow.early(TalkFlow.start().session, "Hi.").effects.isEmpty())
        assertNull(TalkFlow.stop(early).session.early)
    }

    /** The model's words as the server reads them once the answer is whole (AskCodec lives in core/wire). */
    private object AskCodecLike {
        fun answer(json: String): String = StreamedSpeechRules.partialAnswer(json)!!.text
    }
}
