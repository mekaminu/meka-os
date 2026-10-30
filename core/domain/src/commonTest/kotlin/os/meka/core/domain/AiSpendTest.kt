package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiSpendTest {
    @Test
    fun theMonthIsGroupedByWhatItWasForCostliestFirst() {
        val lines = AiSpendRules.lines(
            listOf(
                AiMonthUse("ask.talk", 40, 120), AiMonthUse("ask", 10, 30),
                AiMonthUse("mail.sort", 300, 60), AiMonthUse("mail.draft", 40, 25),
                AiMonthUse("extract.message", 1, 0), AiMonthUse("unused", 0, 0),
            ),
        )
        assertEquals(listOf("Talk", "Mail", "Ask", "Messages"), lines.map { it.label })
        assertEquals(listOf("40 calls · $1.20", "340 calls · 85¢", "10 calls · 30¢", "1 call · under 1¢"), lines.map { it.detail })
        assertEquals(120f / 235f, lines[0].share)
        assertEquals(0f, lines[3].share)
    }

    @Test
    fun nothingUsedShowsNothing() {
        assertTrue(AiSpendRules.lines(emptyList()).isEmpty())
        assertTrue(AiSpendRules.lines(listOf(AiMonthUse("ask", 0, 0))).isEmpty())
        assertNull(AiSpendRules.heading(emptyList(), 0))
        assertEquals("under 1¢", AiSpendRules.cost(0))
        assertEquals("$20", AiSpendRules.cost(2000))
    }

    @Test
    fun theStatusCarriesTheMonthAndARefusedKey() {
        val month = listOf(AiMonthUse("ask.talk", 40, 120), AiMonthUse("ask", 10, 85))
        val on = AskRules.statusView("on", null, 205, 2000, "ok", month)
        assertEquals("On · $2.05 of $20 this month", on.line)
        assertEquals("This month by feature · $2.05 in all", on.spendHeading)
        assertEquals(listOf("Talk", "Ask"), on.spend.map { it.label })
        assertFalse(on.refused)

        val refused = AskRules.statusView("failing", "Anthropic refused the key", 205, 2000, "ok", month, keyRefused = true)
        assertTrue(refused.refused && refused.lit)
        assertEquals(AiSpendRules.REFUSED_LINE, refused.line)
        assertEquals(2, refused.spend.size)
        // With no key at all it's simply off, never "refused".
        assertFalse(AskRules.statusView("off", "No AI key set", 0, 2000, "ok", keyRefused = true).refused)
        // An older server: the line is as before and nothing unfolds.
        assertEquals(AiStatusView("On · $1.20 of $20 this month", lit = false, canAsk = true), AskRules.statusView("on", null, 120, 2000, "ok"))
    }

    @Test
    fun navilifesVoiceIsCountedAsClips() {
        val month = listOf(AiMonthUse("ask.talk", 40, 120), AiMonthUse("voice.polly", 300, 60), AiMonthUse("voice.polly", 1, 0))
        val lines = AiSpendRules.lines(month)
        assertEquals(listOf("Talk", "Voice"), lines.map { it.label })
        assertEquals("301 clips · 60¢", lines[1].detail)
        assertEquals("1 clip · under 1¢", AiSpendRules.lines(listOf(AiMonthUse("voice.polly", 1, 0))).single().detail)
        assertEquals(AiTodayRules.Group.VOICE, AiTodayRules.groupOf("voice.polly"))
        assertEquals(
            "Navilife's AI today · Talk 3 · Voice 12 · 1¢",
            AiTodayRules.line(listOf(AiDayUse("voice.polly", 12, 4_000), AiDayUse("ask.talk", 3, 6_000))),
        )
    }

    @Test
    fun theRowsAlwaysAddUpToTheHeading() {
        // Walk-through A5: "$18.30 in all" over rows worth $2.82.
        val month = listOf(AiMonthUse("ask.talk", 40, 182), AiMonthUse("mail.sort", 300, 100))
        val view = AskRules.statusView("on", null, 1830, 2000, "ok", month)
        assertEquals("This month by feature · $18.30 in all", view.spendHeading)
        assertEquals(listOf("Talk", "Mail", AiSpendRules.UNSPLIT), view.spend.map { it.label })
        assertEquals("$15.48", view.spend.last().detail)
        assertEquals(1548f / 1830f, view.spend.last().share)
        assertEquals(182f / 1830f, view.spend.first().share)
        // When the rows already add up (or the server sends no total), there's no extra row.
        assertEquals(listOf("Talk", "Mail"), AskRules.statusView("on", null, 282, 2000, "ok", month).spend.map { it.label })
        assertEquals(listOf("Talk", "Mail"), AiSpendRules.lines(month).map { it.label })
        // A total under what the rows say (rounding) never makes a negative row.
        assertEquals(2, AiSpendRules.lines(month, 280).size)
    }
}
