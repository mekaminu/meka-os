package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandBarTest {
    private val ctx = CommandBarContext(atWork = false, fasting = false, fastGoalHours = 16, connected = true, appearance = "dark")

    private fun actions(query: String, c: CommandBarContext = ctx) = CommandBarRules.run(query, c).rows.map { it.action }

    @Test
    fun emptyQueryListsTheCatalogueBySection() {
        val r = CommandBarRules.run("", ctx)
        assertEquals(listOf("Go to", "Do", "Open", "Appearance"), r.groups.map { it.label })
        assertEquals(
            listOf(
                CommandBarAction.GO_TODAY, CommandBarAction.GO_NEEDS_YOU, CommandBarAction.GO_CALENDAR, CommandBarAction.GO_ASK,
                CommandBarAction.GO_LISTS, CommandBarAction.GO_GOALS, CommandBarAction.GO_REVIEW, CommandBarAction.GO_VAULT,
            ),
            r.groups[0].rows.map { it.action },
        )
        // Nothing typed: no Search or Add rows.
        assertFalse(r.rows.any { it.action == CommandBarAction.SEARCH_FOR || it.action == CommandBarAction.ADD_TASK })
        // Blank counts as nothing.
        assertEquals(r, CommandBarRules.run("   ", ctx))
    }

    @Test
    fun onlyTheRightHalfOfEachPairIsOffered() {
        val off = actions("")
        assertTrue(CommandBarAction.WORK_START in off && CommandBarAction.WORK_FINISH !in off)
        assertTrue(CommandBarAction.FAST_START in off && CommandBarAction.FAST_END !in off)
        assertTrue(CommandBarAction.APPEARANCE_DARK !in off)
        assertTrue(CommandBarAction.APPEARANCE_LIGHT in off && CommandBarAction.APPEARANCE_AUTO in off)

        val on = actions("", ctx.copy(atWork = true, fasting = true, appearance = "system"))
        assertTrue(CommandBarAction.WORK_FINISH in on && CommandBarAction.WORK_START !in on)
        assertTrue(CommandBarAction.FAST_END in on && CommandBarAction.FAST_START !in on)
        assertTrue(CommandBarAction.APPEARANCE_DARK in on && CommandBarAction.APPEARANCE_AUTO !in on)
    }

    @Test
    fun calendarsAndSyncOnlyOnceConnected() {
        val offline = actions("", ctx.copy(connected = false))
        assertTrue(CommandBarAction.CALENDARS !in offline)
        assertTrue(CommandBarAction.SYNC_NOW !in offline)
        assertTrue(CommandBarAction.CALENDARS in actions(""))
    }

    @Test
    fun aFewLettersFindTheCommandFirst() {
        assertEquals(CommandBarAction.GO_REVIEW, actions("rev").first())
        assertEquals(CommandBarAction.FAST_START, actions("fast").first())
        assertEquals(CommandBarAction.YOUR_DATA, actions("exp").first())
        assertEquals(CommandBarAction.GO_NEEDS_YOU, actions("needs").first())
        assertEquals(CommandBarAction.PLAN_DAY, actions("plan my").first())
        // One letter is enough for commands (Search needs two).
        val p = actions("p")
        assertEquals(CommandBarAction.PLAN_DAY, p.first())
        assertFalse(CommandBarAction.SEARCH_FOR in p)
        assertEquals(CommandBarAction.ADD_TASK, p.last())
    }

    @Test
    fun titleMatchesOutrankKeywordMatches() {
        // "Calendar" is a title; "Calendars" too, and "calendar" is also nowhere in the keywords of others.
        val cal = actions("calendar")
        assertEquals(CommandBarAction.GO_CALENDAR, cal[0])
        assertEquals(CommandBarAction.CALENDARS, cal[1])
        // "habit" only in Goals and habits' title; "fasting" is a keyword of Goals and Start a fast.
        assertEquals(CommandBarAction.GO_GOALS, actions("habit").first())
        // "theme" is only a keyword: the offered appearances, in catalogue order.
        assertEquals(listOf(CommandBarAction.APPEARANCE_LIGHT, CommandBarAction.APPEARANCE_AUTO), actions("theme").take(2))
    }

    @Test
    fun accentsAndCaseAreIgnoredAndEveryWordMustMatch() {
        assertEquals(CommandBarAction.GO_REVIEW, actions("RÉV").first())
        assertTrue(actions("plan xyz").none { it == CommandBarAction.PLAN_DAY })
    }

    @Test
    fun typedTextCanAlwaysBeSearchedOrAdded() {
        val r = CommandBarRules.run("Call Mum about Sunday", ctx).rows
        assertEquals(listOf(CommandBarAction.SEARCH_FOR, CommandBarAction.ADD_TASK), r.map { it.action })
        assertEquals("Search for “Call Mum about Sunday”", r[0].title)
        assertEquals("Add “Call Mum about Sunday” as a task", r[1].title)
        assertEquals("Call Mum about Sunday", r[1].text)
        assertEquals("Call Mum about Sunday", r[0].text)
        // Commands come first when something matches; Search and Add still close the list.
        val rev = CommandBarRules.run("review", ctx).rows
        assertEquals(CommandBarAction.GO_REVIEW, rev.first().action)
        assertEquals(listOf(CommandBarAction.SEARCH_FOR, CommandBarAction.ADD_TASK), rev.takeLast(2).map { it.action })
        assertNull(rev.first().text)
    }

    @Test
    fun theTypedTextIsKeptWholeButEchoedShort() {
        val long = "Book the boiler service before the cold weather sets in properly this year"
        val add = CommandBarRules.run(long, ctx).rows.last()
        assertEquals(long, add.text)
        assertEquals("Add “Book the boiler service before the cold weather…” as a task", add.title)
        assertEquals("Dentist Fri 3pm", CommandBarRules.echo("  Dentist   Fri\t3pm "))
        val noSpaces = "x".repeat(60)
        assertEquals("x".repeat(CommandBarRules.ECHO_CHARS) + "…", CommandBarRules.echo(noSpaces))
    }

    @Test
    fun matchesAreCapped() {
        // "o" starts a word in many entries; at most MAX_MATCHES commands, then Add (one letter: no Search).
        val r = actions("o")
        assertTrue(r.size <= CommandBarRules.MAX_MATCHES + 1)
        assertEquals(CommandBarAction.ADD_TASK, r.last())
    }

    @Test
    fun detailsSayWhatHappens() {
        val start = CommandBarRules.run("start a fast", ctx.copy(fastGoalHours = 18)).rows.first()
        assertEquals(CommandBarAction.FAST_START, start.action)
        assertEquals("Goal 18 h · starts now", start.detail)
        val end = CommandBarRules.run("end", ctx.copy(fasting = true)).rows.first { it.action == CommandBarAction.FAST_END }
        assertEquals("You can undo it for 10 minutes", end.detail)
        assertNull(CommandBarRules.run("today", ctx).rows.first().detail)
    }
}
