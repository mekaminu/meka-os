package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AiTodayTest {
    @Test
    fun talkLeadsThenAskThenWhatMekaReadWithTheDaysCost() {
        val line = AiTodayRules.line(
            listOf(
                AiDayUse("ask", 2, 6_000),
                AiDayUse("triage.message", 3, 9_000),
                AiDayUse("digest.groups", 1, 4_000),
                AiDayUse("ask.talk", 6, 12_000),
                AiDayUse("extract.callscam", 1, 1_000),
                AiDayUse("extract.email", 1, 1_000),
            ),
        )
        assertEquals("MEKA's AI today · Talk 6 · Ask 2 · Messages 4 · Calls 1 · Other 1 · 3¢", line)
    }

    @Test
    fun nothingAskedTodayIsNoLine() {
        assertNull(AiTodayRules.line(emptyList()))
        assertNull(AiTodayRules.line(listOf(AiDayUse("ask.talk", 0, 0))))
        assertEquals("MEKA's AI today · Talk 1 · under 1¢", AiTodayRules.line(listOf(AiDayUse("ask.talk", 1, 1_800))))
    }

    @Test
    fun theCostRoundsToCentsThenDollars() {
        assertEquals("under 1¢", AiTodayRules.cost(9_999))
        assertEquals("1¢", AiTodayRules.cost(10_000))
        assertEquals("2¢", AiTodayRules.cost(15_000))
        assertEquals("99¢", AiTodayRules.cost(994_999))
        assertEquals("\$1", AiTodayRules.cost(995_000))
        assertEquals("\$1.20", AiTodayRules.cost(1_200_000))
        assertEquals("under 1¢", AiTodayRules.cost(-5))
    }
}
