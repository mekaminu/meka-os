package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Talk to MEKA (build plan V1, voice slice 1): what an utterance means, what MEKA says, and the turn-taking. */
class TalkTest {
    private val today = AskRules.parseDay("2026-10-08")!! // a Thursday

    private fun card(p: AskProposal) = AskRules.cardOf(p, today)
    private val add = card(AskProposal.AddTask("Call the dentist", today + 1, 9 * 60))
    private val timer = card(AskProposal.Timer(20))
    private val fast = card(AskProposal.StartFast(36))

    @Test
    fun goodbyesEndButOkOrAQuestionWithThankYouInItDoesNot() {
        listOf("That's all", "that's it, thanks!", "No thanks, bye", "OK cheers", "Thank you MEKA", "stop", "nothing else", "nope", "I'm done for now")
            .forEach { assertTrue(TalkRules.isEnding(it), it); assertEquals(TalkReply.End, TalkRules.reply(it, 0), it) }
        listOf("ok", "great", "thanks, and add milk", "stop the timer", "is that all for today?", "what's left")
            .forEach { assertFalse(TalkRules.isEnding(it), it) }
        assertEquals(TalkReply.Ask("stop the timer"), TalkRules.reply("stop the timer", 0))
        assertEquals(TalkReply.Silence, TalkRules.reply("   ", 0))
        assertEquals(TalkReply.Silence, TalkRules.reply("um", 1))
    }

    @Test
    fun aYesConfirmsTheCardsAndSaysWhichWhenThereAreSeveral() {
        assertEquals(TalkReply.Confirm(listOf(0)), TalkRules.reply("Yes please", 1))
        assertEquals(TalkReply.Confirm(listOf(0)), TalkRules.reply("ok", 1))
        assertEquals(TalkReply.Confirm(listOf(0)), TalkRules.reply("go ahead", 1))
        assertEquals(TalkReply.Which, TalkRules.reply("yes", 2))
        assertEquals(TalkReply.Confirm(listOf(1)), TalkRules.reply("the second one", 2))
        assertEquals(TalkReply.Confirm(listOf(1)), TalkRules.reply("yeah, the last one", 2))
        assertEquals(TalkReply.Confirm(listOf(0, 1)), TalkRules.reply("both", 2))
        assertEquals(TalkReply.Confirm(listOf(0, 1, 2)), TalkRules.reply("yes, all of them", 3))
        assertEquals(TalkReply.Confirm(listOf(0, 2)), TalkRules.reply("the first and the third", 3))
        // "Both" with three, or a third of two, isn't a choice: it's taken as a question.
        assertIs<TalkReply.Ask>(TalkRules.reply("both", 3))
        assertIs<TalkReply.Ask>(TalkRules.reply("the third one", 2))
        // No leaves them; a no-and-goodbye ends.
        assertEquals(TalkReply.Decline, TalkRules.reply("no, leave it", 1))
        assertEquals(TalkReply.Decline, TalkRules.reply("not now", 2))
        assertEquals(TalkReply.End, TalkRules.reply("no thanks, that's all", 1))
        // Without cards on offer a yes is just words to ask about; anything longer is a new question.
        assertEquals(TalkReply.Ask("yes"), TalkRules.reply("yes", 0))
        assertEquals(TalkReply.Ask("yes but make it 10"), TalkRules.reply("yes but make it 10", 1))
    }

    @Test
    fun answersAreSaidPlainlyWithTheOfferAfterThem() {
        assertEquals(
            "You have Standup 09:00 to 09:15, then the dentist. Training is at 19:00.",
            TalkRules.speakable("You have **Standup** 09:00–09:15 · then the dentist.\n- Training is at 19:00 ⚽"),
        )
        assertEquals("One. Two. Three.", TalkRules.speakable("One. Two. Three. Four."))
        assertTrue(TalkRules.speakable("word ".repeat(100)).length <= TalkRules.MAX_SPOKEN_CHARS)

        assertEquals("Sure. Shall I add Call the dentist for tomorrow at 09:00?", TalkRules.spoken(AskAnswer("Sure.", listOf(add)), today))
        assertEquals(
            "Here you go. I can set a timer for 20 minutes or start a 36-hour fast. Say which, or both.",
            TalkRules.spoken(AskAnswer("Here you go.", listOf(timer, fast)), today),
        )
        assertEquals("Nothing else today.", TalkRules.spoken(AskAnswer("Nothing else today.", emptyList()), today))
        assertEquals(
            "Moved Book dentist to Monday 12 October. Anything else?",
            TalkRules.spokenDone(listOf(AskProposal.MoveTask("x", "Book dentist", today + 4, null)), 0, today),
        )
        assertEquals("That couldn't be done any more. Anything else?", TalkRules.spokenDone(emptyList(), 1, today))
        assertEquals("Which one? Say the first or the second, or both.", TalkRules.spokenWhich(2))
        assertEquals("Which one? Say the first, the second or the third, or all of them.", TalkRules.spokenWhich(3))
        assertEquals("1 hour 30 minutes", TalkRules.durationWords(90))
        assertEquals("1 minute", TalkRules.durationWords(1))
        assertEquals("5-day", TalkRules.fastWords(120))
        assertEquals("set an alarm for 06:30", TalkRules.phrase(AskProposal.Alarm(390), today, past = false))
        assertEquals("ticked off Pay council tax", TalkRules.phrase(AskProposal.CompleteTask("x", "Pay “council” tax"), today, past = true))
    }

    @Test
    fun aConversationListensThinksSpeaksAndListensAgainUntilSilence() {
        var step = TalkFlow.start()
        assertEquals(listOf(TalkEffect.Listen), step.effects)
        step = TalkFlow.heard(step.session, "add call the dentist tomorrow at 9")
        val ask = assertIs<TalkEffect.Ask>(step.effects.single())
        assertEquals(TalkPhase.THINKING, step.session.phase)
        // While thinking, anything heard is ignored.
        assertEquals(step.session, TalkFlow.heard(step.session, "hello").session)

        step = TalkFlow.answered(step.session, ask.question, AskOutcome.Answered(AskAnswer("Sure.", listOf(add))), today)
        assertEquals(TalkPhase.SPEAKING, step.session.phase)
        assertEquals(listOf(add), step.session.pending)
        // Barge-in: talking over MEKA stops it and listens.
        step = TalkFlow.bargeIn(step.session)
        assertEquals(listOf(TalkEffect.StopSpeaking, TalkEffect.Listen), step.effects)
        step = TalkFlow.heard(step.session, "yes")
        assertEquals(TalkEffect.Do(listOf(add)), step.effects.single())
        step = TalkFlow.did(step.session, listOf(add.proposal), listOf("Added “Call the dentist”"), 0, today)
        assertEquals(TalkEffect.Speak("Added Call the dentist for tomorrow at 09:00. Anything else?"), step.effects.single())
        assertTrue(step.session.pending.isEmpty())
        step = TalkFlow.spoke(step.session)
        assertEquals(listOf(TalkEffect.Listen), step.effects)

        // The next question carries the first exchange and what was done after it.
        step = TalkFlow.heard(step.session, "and remind me at 8")
        val next = assertIs<TalkEffect.Ask>(step.effects.single())
        assertEquals(listOf(TalkTurn("add call the dentist tomorrow at 9", "Sure.", listOf("Added “Call the dentist”"))), next.history)

        step = TalkFlow.answered(step.session, next.question, AskOutcome.Answered(AskAnswer("I can't set reminders yet.", emptyList())), today)
        step = TalkFlow.spoke(step.session)
        step = TalkFlow.silence(step.session)
        assertEquals(TalkPhase.ENDED, step.session.phase)
        assertEquals(listOf(TalkEffect.End), step.effects)
        // A late answer after the end changes nothing.
        assertEquals(step.session, TalkFlow.answered(step.session, "x", AskOutcome.Unavailable("x"), today).session)
    }

    @Test
    fun noAndWhichKeepTheConversationGoingAndUnavailableEndsIt() {
        var s = TalkFlow.answered(TalkFlow.heard(TalkFlow.start().session, "help").session, "help", AskOutcome.Answered(AskAnswer("Two ideas.", listOf(timer, fast))), today).session
        s = TalkFlow.spoke(s).session
        var step = TalkFlow.heard(s, "yes")
        assertEquals(TalkEffect.Speak(TalkRules.spokenWhich(2)), step.effects.single())
        assertEquals(2, step.session.pending.size)
        step = TalkFlow.heard(TalkFlow.spoke(step.session).session, "no")
        assertEquals(TalkEffect.Speak(TalkRules.LEFT_IT), step.effects.single())
        assertTrue(step.session.pending.isEmpty())
        // With nothing on offer, "no" is a goodbye.
        assertEquals(TalkEffect.End, TalkFlow.heard(TalkFlow.spoke(step.session).session, "no").effects.single())

        // AI off: said once, then the conversation ends (a barge-in ends it at once).
        var off = TalkFlow.heard(TalkFlow.start().session, "what's next").session
        off = TalkFlow.answered(off, "what's next", AskOutcome.Unavailable("MEKA's AI is off"), today).session
        assertTrue(off.endAfterSpeaking)
        assertEquals(listOf(TalkEffect.End), TalkFlow.spoke(off).effects)
        assertEquals(listOf(TalkEffect.StopSpeaking, TalkEffect.End), TalkFlow.bargeIn(off).effects)

        // Stop works from anywhere; stopping twice does nothing more.
        val stopped = TalkFlow.stop(TalkFlow.start().session)
        assertEquals(listOf(TalkEffect.End), stopped.effects)
        assertTrue(TalkFlow.stop(stopped.session).effects.isEmpty())
    }

    @Test
    fun aLongConversationStopsByItselfAndSendsOnlyTheLastExchanges() {
        var s = TalkFlow.start().session
        repeat(TalkRules.MAX_QUESTIONS) { i ->
            s = TalkFlow.heard(s, "question $i").session
            s = TalkFlow.answered(s, "question $i", AskOutcome.Answered(AskAnswer("answer $i", emptyList())), today).session
            s = TalkFlow.spoke(s).session
        }
        assertEquals(TalkRules.MAX_HISTORY, s.conversation.history.size)
        assertEquals("question ${TalkRules.MAX_QUESTIONS - 1}", s.conversation.history.last().question)
        val step = TalkFlow.heard(s, "one more")
        assertEquals(TalkEffect.Speak(TalkRules.TOO_MANY), step.effects.single())
        assertEquals(listOf(TalkEffect.End), TalkFlow.spoke(step.session).effects)
        // Done lines are capped per exchange.
        val c = Conversation().answered("q", "a").did(listOf("1", "2")).did(listOf("3", "4"))
        assertEquals(listOf("2", "3", "4"), c.turns.single().done)
        assertEquals(Conversation(), Conversation().did(listOf("x")))
    }
}
