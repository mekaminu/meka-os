package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The companion, slice 1: open turns go to the larger model, quick look-ups stay on the small one. */
class CompanionTest {
    @Test
    fun generalQuestionsAdviceAndIdeasAreOpen() {
        listOf(
            "Why is the sky blue?",
            "Explain how a heat pump works",
            "What's the difference between an ISA and a pension?",
            "Any ideas for the boys' half term?",
            "Should I train or drive to the match on Saturday?",
            "Tell me a joke",
            "What do you think of electric cars?",
            "Who invented the telephone?",
            "I've been thinking about learning Spanish, where would you start with only twenty minutes a day on the train",
        ).forEach { assertTrue(CompanionRules.isOpen(it), it) }
    }

    @Test
    fun lookUpsOfHisDayAndActionsStayQuick() {
        listOf(
            "What's on today?",
            "When's the dentist?",
            "Is it going to rain at training?",
            "Move the dentist to tomorrow morning",
            "Add milk to shopping",
            "What time is the train?",
            "What are my tasks today?",
            "How many days leave have I got left?",
            "",
        ).forEach { assertFalse(CompanionRules.isOpen(it), it) }
    }

    @Test
    fun aShortFollowUpCarriesOnAnOpenTurnOnly() {
        assertTrue(CompanionRules.isOpen("go on", listOf("What's on today?", "Tell me about the Romans")))
        assertTrue(CompanionRules.isOpen("And then?", listOf("Explain how a heat pump works")))
        assertFalse(CompanionRules.isOpen("go on", listOf("What's on today?")))
        assertFalse(CompanionRules.isOpen("go on"))
        // A look-up mid-conversation is still a look-up.
        assertFalse(CompanionRules.isOpen("What's on tomorrow?", listOf("Tell me about the Romans")))
    }

    @Test
    fun openTurnsAreCountedAsChatInActivity() {
        assertEquals(AiTodayRules.Group.CHAT, AiTodayRules.groupOf(CompanionRules.FEATURE))
        assertEquals(
            "Navilife's AI today · Talk 1 · Ask 1 · Chat 2 · under 1¢",
            AiTodayRules.line(listOf(AiDayUse("ask", 1, 1_000), AiDayUse(CompanionRules.FEATURE, 2, 3_000), AiDayUse("ask.talk", 1, 1_000))),
        )
    }

    @Test
    fun questionsAboutTheWorldAreSearchedAndHisOwnLifeNeverIs() {
        // The companion, slice 2: live world facts.
        listOf(
            "What's the latest news on the rail strike?",
            "Who won the Arsenal game?",
            "What's the weather in Madrid this weekend?",
            "Forecast for Lisbon on Friday",
            "What's the price of bitcoin right now?",
            "When does the new Bond film come out?",
            "Is the Science Museum open today?",
        ).forEach { assertEquals(it, LiveFactsRules.query(it), it) }
        listOf(
            "What's on today?",
            "Why is the sky blue?",
            "What's the latest on my car insurance?",
            "Any news from our school?",
            "What's the weather in the morning?",
            "Forecast for tomorrow",
            "Is it going to rain at training?",
            "Latest score for Barça?",
            "What's the latest news about Navilife",
            "Latest news on 07700 900123",
            "Any news for someone@example.com",
            "Weather in AB1 2CD right now",
            "",
        ).forEach { assertEquals(null, LiveFactsRules.query(it), it) }
        assertEquals(LiveFactsRules.MAX_QUERY, LiveFactsRules.query("latest news " + "about trains ".repeat(30))!!.length)
    }

    @Test
    fun sourcesAreHttpsOneASiteAndNamedByTheirSite() {
        assertEquals("bbc.co.uk", LiveFactsRules.site("https://www.bbc.co.uk/news/x?y=1"))
        assertEquals(null, LiveFactsRules.site("http://bbc.co.uk"))
        assertEquals(null, LiveFactsRules.site("javascript:alert(1)"))
        val s = LiveFactsRules.sources(listOf(
            "Storm Amy: what we know" to "https://www.bbc.co.uk/news/1",
            "Another BBC page" to "https://bbc.co.uk/news/2",
            "Old" to "http://plain.example.com",
            "  " to "https://met.example.com/forecast",
            "Third" to "https://third.example.com",
        ))
        assertEquals(listOf("bbc.co.uk", "met.example.com"), s.map { it.site })
        assertEquals("bbc.co.uk · Storm Amy: what we know", LiveFactsRules.line(s[0]))
        assertEquals("met.example.com", LiveFactsRules.line(s[1]))
    }
}
