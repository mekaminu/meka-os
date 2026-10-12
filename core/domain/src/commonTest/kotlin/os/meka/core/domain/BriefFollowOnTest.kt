package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The brief pane's Listen handing over to Talk (build plan V1, "A morning brief worth listening to", slice 7). */
class BriefFollowOnTest {
    private val script = "Good morning, Meka. It's Monday, an office day. Keep going. " + BriefScriptRules.CLOSE

    @Test
    fun onlyAFinishedReadThatEndsAskingHandsOverWithTheMicAllowed() {
        assertTrue(BriefFollowOnRules.handOff(script, finished = true, micAllowed = true))
        assertTrue(BriefFollowOnRules.handOff("$script \n", finished = true, micAllowed = true))
        // Stopped, Got it, closed, a length change: never.
        assertFalse(BriefFollowOnRules.handOff(script, finished = false, micAllowed = true))
        // The brief never asks for the microphone.
        assertFalse(BriefFollowOnRules.handOff(script, finished = true, micAllowed = false))
        // A brief that doesn't end asking (nothing to answer).
        assertFalse(BriefFollowOnRules.handOff("Good morning, Meka.", finished = true, micAllowed = true))
    }

    @Test
    fun theWindowIsEightSecondsAndOnlyWhileNothingWasSaid() {
        val at = 1_000L
        assertFalse(BriefFollowOnRules.windowLapsed(at, at + 7_999, speechBegan = false))
        assertTrue(BriefFollowOnRules.windowLapsed(at, at + 8_000, speechBegan = false))
        assertFalse(BriefFollowOnRules.windowLapsed(at, at + 20_000, speechBegan = true))
        // The recogniser timing out early inside the window listens again; after it, or once Meka spoke, it doesn't.
        assertTrue(BriefFollowOnRules.listenAgain(at, at + 5_000, speechBegan = false))
        assertFalse(BriefFollowOnRules.listenAgain(at, at + 8_000, speechBegan = false))
        assertFalse(BriefFollowOnRules.listenAgain(at, at + 5_000, speechBegan = true))
    }

    @Test
    fun afterTheBriefTalkListensRemembersItAndTakesPlanMyDayAndMoreNews() {
        val start = TalkFlow.afterBrief()
        assertEquals(TalkPhase.LISTENING, start.session.phase)
        assertEquals(listOf<TalkEffect>(TalkEffect.Listen), start.effects)
        val turn = start.session.conversation.turns.single()
        assertEquals(BriefFollowOnRules.ASKED, turn.question)
        assertEquals(ReadOutRules.remembered(ReadOut(ReadOut.Kind.BRIEF)), turn.answer)
        assertEquals(0, start.session.questions) // a question asked next still has the full allowance

        // "More news": the headlines, read as in Talk.
        val more = TalkFlow.heard(start.session, "more news")
        assertEquals(TalkPhase.SPEAKING, more.session.phase)
        assertEquals(ReadOut.Kind.HEADLINES, assertIs<TalkEffect.ReadOut>(more.effects.single()).read.kind)

        // "Plan my day": MEKA's line, then the conversation ends and the planner opens.
        val plan = TalkFlow.heard(start.session, "plan my day")
        assertEquals(TalkEffect.Speak(TalkOpenRules.OPENING_PLAN), plan.effects.single())
        val opened = TalkFlow.spoke(plan.session)
        assertEquals(listOf(TalkEffect.End, TalkEffect.Open(TalkPlace.PLAN)), opened.effects)

        // A question goes to MEKA with the brief in its history; silence ends quietly.
        val q = TalkFlow.heard(start.session, "what time is my first meeting?")
        val ask = assertIs<TalkEffect.Ask>(q.effects.single())
        assertEquals(BriefFollowOnRules.ASKED, ask.history.single().question)
        assertEquals(listOf<TalkEffect>(TalkEffect.End), TalkFlow.silence(start.session).effects)
    }
}
