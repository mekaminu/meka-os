package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "A morning brief worth listening to", slice 2: what the AI is given and how its brief is checked. */
class BriefAiTest {
    private val day = 20738L // Mon 12 Oct 2026

    private val v = MorningBriefView.EMPTY.copy(
        dateLabel = "Mon 12 Oct", workLine = "Work 09:00–17:30",
        weatherLine = "9–15°, light rain from 15:00 — take a coat",
        headlines = listOf(BriefHeadline("h1", "Barça win again", null, "BBC Sport")),
        story = BriefStory(
            epochDay = day, officeDay = true, workEndMin = 17 * 60 + 30,
            events = listOf(BriefEvent("Standup", 570, 600)),
            focus = listOf(BriefFocus("Renew passport", "it's overdue"), BriefFocus("Send the Q4 deck", "it moves your goal, Q4 plan")),
            smallerCount = 4,
            habits = listOf("gym, 1 of 3 this week"),
        ),
    )

    @Test
    fun theFactsAreTheBriefBeforeTheNews() {
        val facts = BriefAiRules.facts(v)
        assertEquals(BriefScriptRules.body(v), facts)
        assertTrue(facts.first().startsWith("Good morning, Meka."), facts.toString())
        assertTrue(facts.none { "Barça" in it || BriefScriptRules.CLOSE in it }, facts.toString())
        assertEquals(BriefScriptRules.script(v), (BriefScriptRules.body(v) + BriefScriptRules.tail(v)).joinToString(" "))
    }

    @Test
    fun aSentenceNamingAnythingNotInTheFactsIsDropped() {
        val facts = BriefAiRules.facts(v)
        val answer = "Good morning, Meka. It's Monday 12 October, an office day, and you finish at half past 5. " +
            "Your first meeting is Standup at half past 9. " +
            "Then lunch with Daniel at 1 o'clock. " + // Daniel and 1 aren't in the facts
            "Focus first on Renew passport, because it's overdue, then Send the Q4 deck for your Q4 plan goal. " +
            "There are seven smaller ones too. " + // the facts say 4
            "Today's habit is the gym, 1 of 3 this week."
        val kept = BriefAiRules.check(answer, facts)
        assertEquals(5, kept.size, kept.toString())
        assertTrue(kept.none { "Daniel" in it || "seven" in it }, kept.toString())
        val s = BriefAiRules.script(kept, v)!!
        assertTrue(s.startsWith("Good morning, Meka. It's Monday 12 October"), s)
        assertTrue(s.endsWith(BriefScriptRules.oneLiner(v.story) + " " + BriefScriptRules.CLOSE), s)
        assertTrue("Barça win again" in s, s)
    }

    @Test
    fun tooLittleLeftReadsMekasOwn() {
        val facts = BriefAiRules.facts(v)
        val kept = BriefAiRules.check("Good morning, Meka. Big day with Sarah in Leeds at 4. {\"script\": oops", facts)
        assertEquals(listOf("Good morning, Meka."), kept)
        assertNull(BriefAiRules.script(kept, v))
        assertNull(BriefAiRules.script(BriefAiRules.check("", facts), v))
    }

    @Test
    fun sentencesAndWordsAreReadByHand() {
        assertEquals(listOf("One.", "Two at 9.30 today!", "Three?", "And four."), BriefAiRules.sentences("One. Two at 9.30 today! Three?\nAnd four"))
        assertEquals(listOf("Rex’s", "U7s", "o'clock", "9", "30"), BriefAiRules.tokens("Rex’s U7s, o'clock — 9:30"))
    }

    @Test
    fun theBriefCountsOnItsOwnInActivity() {
        assertEquals(AiTodayRules.Group.BRIEF, AiTodayRules.groupOf("ask.brief"))
        assertEquals("Navilife's AI today · Ask 1 · Brief 1 · under 1¢", AiTodayRules.line(listOf(AiDayUse("ask", 1, 100), AiDayUse("ask.brief", 1, 200))))
    }
}
