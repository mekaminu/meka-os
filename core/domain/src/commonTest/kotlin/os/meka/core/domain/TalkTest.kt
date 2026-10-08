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

    @Test
    fun theVoiceIsBritishOnTheDeviceAndTheBestInstalled() {
        val voices = listOf(
            VoiceCandidate("en-us-x-iol-local", "en-US", 400),
            VoiceCandidate("en-gb-x-gbd-network", "en-GB", 500, needsNetwork = true),
            VoiceCandidate("en-gb-x-rjs-local", "en_GB", 300),
            VoiceCandidate("en-gb-x-gba-local", "en-GB", 400),
            VoiceCandidate("en-gb-x-gbc-local", "en-GB", 500, installed = false),
            VoiceCandidate("fr-fr-x-local", "fr-FR", 500),
        )
        // Never one that sends the text away or isn't installed; British first, then quality.
        assertEquals("en-gb-x-gba-local", TalkVoice.best(voices)?.name)
        assertEquals("en-us-x-iol-local", TalkVoice.best(voices.filter { !it.language.lowercase().replace('_', '-').startsWith("en-gb") || it.needsNetwork })?.name)
        // Any other English after the preferred ones; nothing English means the engine's default.
        assertEquals("en-in", TalkVoice.best(listOf(VoiceCandidate("en-in", "en-IN", 500), VoiceCandidate("fr", "fr-FR", 500)))?.name)
        assertEquals(null, TalkVoice.best(listOf(VoiceCandidate("fr", "fr-FR", 500), VoiceCandidate("gb-net", "en-GB", 500, needsNetwork = true))))
        // A tie on quality goes by name, so it's the same voice every time.
        assertEquals("a", TalkVoice.best(listOf(VoiceCandidate("b", "en-GB", 400), VoiceCandidate("a", "en-GB", 400)))?.name)
    }

    private fun near(expected: Float, actual: Float) = assertTrue(kotlin.math.abs(expected - actual) < 1e-3f, "$expected ≠ $actual")

    @Test
    fun theOrbSwellsWithTheVoiceBreathesWhileThinkingAndRipplesWhileSpeaking() {
        assertEquals(0f, TalkOrb.level(-5f))
        assertEquals(0f, TalkOrb.level(-2f))
        assertEquals(0.5f, TalkOrb.level(4f))
        assertEquals(1f, TalkOrb.level(12f))
        // Up quickly, down slowly.
        near(0.6f, TalkOrb.smooth(0f, 1f))
        near(0.85f, TalkOrb.smooth(1f, 0f))
        assertEquals(1f, TalkOrb.scale(TalkPhase.LISTENING, 0f, 0.3f))
        near(1.22f, TalkOrb.scale(TalkPhase.LISTENING, 1f, 0.3f))
        assertEquals(1f, TalkOrb.scale(TalkPhase.SPEAKING, 1f, 0f))
        near(0.92f, TalkOrb.scale(TalkPhase.THINKING, 1f, 0f))
        near(1f, TalkOrb.scale(TalkPhase.ENDED, 0f, 1f))
        // Three ripples spaced evenly through one life, looping.
        assertEquals(0f, TalkOrb.ripple(0, 0))
        near(1f / 3, TalkOrb.ripple(1, 0))
        near(2f / 3, TalkOrb.ripple(2, 0))
        near(0.5f, TalkOrb.ripple(0, 700))
        assertEquals(0f, TalkOrb.ripple(0, TalkOrb.RIPPLE_MS))
        assertEquals("Listening…", TalkOrb.label(TalkPhase.LISTENING))
        assertEquals("Tap to interrupt", TalkOrb.label(TalkPhase.SPEAKING))
        assertEquals("Tap the mic to talk", TalkOrb.label(TalkPhase.ENDED))
    }

    @Test
    fun aSpokenYesIsOneUndoBarAndTheProblemsSayWhatToDo() {
        val did = TalkDid(listOf(add.proposal, timer.proposal), listOf("Added “Call the dentist”", "Timer · 20 min"), emptyList(), 0)
        assertEquals("Added “Call the dentist” · Timer · 20 min", did.barLine)
        assertEquals(TalkRules.NOT_HEARD_CARD, TalkDid(emptyList(), emptyList(), emptyList(), 1).barLine)
        // Nothing is ever sent away to be transcribed, and the line says so.
        assertTrue(TalkProblem.NO_ON_DEVICE.line.contains("nothing is sent away"))
        assertTrue(TalkProblem.NO_PERMISSION.line.contains("microphone"))
    }

    @Test
    fun theMacSaysClickAndWhereToLookAndMeasuresItsOwnMicrophone() {
        assertEquals("Click to interrupt", TalkOrb.label(TalkPhase.SPEAKING, mac = true))
        assertEquals("Click the mic to talk", TalkOrb.label(TalkPhase.ENDED, mac = true))
        assertEquals("Listening…", TalkOrb.label(TalkPhase.LISTENING, mac = true))
        TalkProblem.entries.forEach { p ->
            assertTrue(p.macLine.isNotBlank())
            assertTrue("This phone" !in p.macLine && "Tap" !in p.macLine && "Apps →" !in p.macLine, p.name)
        }
        assertTrue(TalkProblem.NO_ON_DEVICE.macLine.contains("nothing is sent away"))
        assertTrue(TalkProblem.NO_PERMISSION.macLine.contains("System Settings"))
        // The Mac's level comes from its own buffers in dB full scale.
        assertEquals(0f, TalkOrb.levelDbfs(-160f))
        assertEquals(0f, TalkOrb.levelDbfs(-50f))
        near(0.5f, TalkOrb.levelDbfs(-32f))
        assertEquals(1f, TalkOrb.levelDbfs(-3f))
        assertEquals(0f, TalkOrb.levelDbfs(Float.NaN))
        assertEquals(-160f, TalkOrb.dbfs(0f))
        near(0f, TalkOrb.dbfs(1f))
        near(-20f, TalkOrb.dbfs(0.1f))
    }

    @Test
    fun theMacEndsAQuestionAfterAPauseAndGivesUpOnSilence() {
        val t0 = 1_000_000L
        assertEquals(ListenStep.KEEP, TalkEndpoint.step(t0, false, t0, t0 + 3_000))
        assertEquals(ListenStep.SILENCE, TalkEndpoint.step(t0, false, t0, t0 + TalkEndpoint.NOTHING_MS))
        // Words came at 2 s: still talking at 3 s, done once 1.5 s pass with nothing new.
        assertEquals(ListenStep.KEEP, TalkEndpoint.step(t0, true, t0 + 2_000, t0 + 3_000))
        assertEquals(ListenStep.FINISH, TalkEndpoint.step(t0, true, t0 + 2_000, t0 + 3_500))
        // Once words came, a long wait is never silence: it's the end of the question.
        assertEquals(ListenStep.FINISH, TalkEndpoint.step(t0, true, t0 + 2_000, t0 + 20_000))
        // Talking on and on stops at the recogniser's limit.
        assertEquals(ListenStep.FINISH, TalkEndpoint.step(t0, true, t0 + 55_000, t0 + TalkEndpoint.LONGEST_MS))
    }
}
