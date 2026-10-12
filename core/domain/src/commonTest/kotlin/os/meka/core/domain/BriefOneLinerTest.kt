package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "A morning brief worth listening to", slice 6: the AI's one-liner, checked, and the last 30 days remembered. */
class BriefOneLinerTest {
    private val day = 20738L // Mon 12 Oct 2026
    private val facts = listOf(
        "Good morning, Meka. It's Monday 12 October, an office day, finishing at half past 5.",
        "Focus first on Renew passport, it's overdue.",
        "Today's habit is gym, 1 of 3 this week.",
    )

    @Test
    fun theAisLineIsKeptOnlyWhenShortGroundedAndNew() {
        assertEquals("Renew passport before lunch, then the day is yours.",
            BriefOneLinerRules.check("“Renew passport before lunch, then the day is yours.”", facts, emptyList()))
        assertEquals("Finish strong. Gym tonight keeps 3 this week in reach.",
            BriefOneLinerRules.check("Finish strong. Gym tonight keeps 3 this week in reach.", facts, emptyList()))
        // A name or number not in the facts, a quote, an attribution, too long, three sentences: MEKA's own instead.
        assertNull(BriefOneLinerRules.check("Call Priya before lunch.", facts, emptyList()))
        assertNull(BriefOneLinerRules.check("Seven small wins beat one big one.", facts, emptyList()))
        assertNull(BriefOneLinerRules.check("The secret of getting ahead is getting started — Mark Twain", facts, emptyList()))
        assertNull(BriefOneLinerRules.check("As they say, \"carpe diem\".", facts, emptyList()))
        assertNull(BriefOneLinerRules.check("Go. " + "Keep going and going ".repeat(8) + ".", facts, emptyList()))
        assertNull(BriefOneLinerRules.check("Go. Go on. Go now.", facts, emptyList()))
        assertNull(BriefOneLinerRules.check("", facts, emptyList()))
        // Like one said this month: refused.
        assertNull(BriefOneLinerRules.check("Renew passport before lunch, then the day is yours!", facts,
            listOf("Renew passport before lunch, then the day's yours.", "Small steps still count.")))
    }

    @Test
    fun thirtyDaysAreRememberedOneLinePerDay() {
        var lines = emptyList<BriefLineMemory>()
        for (d in 0 until 40) lines = BriefOneLinerRules.remember(lines, day + d, "Line $d.")
        assertEquals(BriefOneLinerRules.KEEP, lines.size)
        assertEquals(day + 10, lines.first().day)
        val raw = BriefOneLinerRules.encode(lines)
        assertEquals(lines, BriefOneLinerRules.decode(raw))
        assertEquals("Line 39.", BriefOneLinerRules.today(lines, day + 39))
        val recent = BriefOneLinerRules.recent(lines, day + 39)
        assertEquals(29, recent.size)
        assertEquals("Line 38.", recent.first())
        assertFalse("Line 39." in recent)
        // Junk rows are skipped; a later row for the same day wins.
        assertEquals(listOf(BriefLineMemory(5, "B")), BriefOneLinerRules.decode("x\ty\n5\tA\n\t\n5\tB\nnope"))
        assertTrue(BriefOneLinerRules.decode(null).isEmpty())
    }

    @Test
    fun mekasOwnSkipsALineSaidThisMonth() {
        val s = BriefStory(epochDay = day)
        val usual = BriefScriptRules.ownLine(s)
        assertEquals(usual, BriefOneLinerRules.pick(s, emptyList()))
        assertEquals(usual, BriefScriptRules.oneLiner(s))
        val other = BriefOneLinerRules.pick(s, listOf(usual))
        assertFalse(BriefOneLinerRules.similar(usual, other), other)
        assertEquals(BriefScriptRules.ownLine(s, day + 1), other)
        // Once today has a line, that one closes the brief.
        assertEquals("Renew passport first.", BriefScriptRules.oneLiner(s.copy(oneLiner = "Renew passport first.")))
    }

    @Test
    fun theBriefRemembersTheFirstLineSaidToday() {
        val world = SyncWorld()
        val fold = world.device("fold")
        val brief = MorningBrief(fold.replica, { 0L })
        assertEquals("One.", brief.rememberLine(day, "One."))
        assertEquals("One.", brief.rememberLine(day, "Two."))
        assertEquals("Three.", brief.rememberLine(day + 1, "Three."))
        assertEquals(listOf(BriefLineMemory(day, "One."), BriefLineMemory(day + 1, "Three.")), brief.lines())
    }
}
