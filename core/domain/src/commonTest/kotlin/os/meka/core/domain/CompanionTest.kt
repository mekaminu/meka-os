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
}
