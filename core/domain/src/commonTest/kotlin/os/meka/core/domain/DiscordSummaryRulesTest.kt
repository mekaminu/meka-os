package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Discord channel watch, slice 3: which channels the AI summarises, what its answer may become, and the card's line. */
class DiscordSummaryRulesTest {
    private val cal = LocalCalendar.UTC
    private val now = 1_791_795_600_000L // Mon 12 Oct 2026 09:00 UTC
    private val ideas = DiscordChannel("example traders#trade-ideas", "Example Traders", "trade-ideas")
    private val general = DiscordChannel("example traders#general", "Example Traders", "general")
    private val watched = setOf(ideas.key, general.key)

    private fun post(id: String, ch: DiscordChannel, who: String, text: String, minsAgo: Int, done: Boolean = false) =
        DiscordRules.Post(id, ch, who, text, now - minsAgo * 60_000L, done)

    private val busy = listOf(
        post("a", ideas, "Admin", "BTC long idea above 68k, stop 66.5k", 30),
        post("b", ideas, "Admin", "sent an image", 29),
        post("c", ideas, "Sam", "In at 67.9k", 10),
    )

    private fun summary(chatKey: String, upToMs: Long, madeMinsAgo: Int, text: String? = "Admin's BTC long above 68k, stop 66.5k") =
        ChatSummary(chatKey, "#trade-ideas", text, upToMs, 3, now - madeMinsAgo * 60_000L)

    @Test
    fun aChannelWithThreeOpenPostsGoesWithItsWordsAndTime() {
        val quiet = listOf(post("g1", general, "Jordan", "gm", 5), post("g2", general, "Alex", "gm", 4))
        val due = DiscordSummaryRules.toSummarise(busy + quiet, watched, emptyList(), TriageSettings(), now, cal)
        val c = due.single()
        assertEquals("discord:example traders#trade-ideas", c.chatKey)
        assertEquals("#trade-ideas · Example Traders", c.name)
        assertTrue(c.isGroup)
        assertEquals(listOf("08:30 Admin: BTC long idea above 68k, stop 66.5k", "08:31 Admin: sent an image", "08:50 Sam: In at 67.9k"),
            c.lines.map { "${it.at} ${it.from}: ${it.text}" })
        assertEquals(now - 10 * 60_000L, c.upToMs)
        // Done posts, unfollowed channels and a channel kept from the AI never go.
        assertTrue(DiscordSummaryRules.toSummarise(busy.map { it.copy(done = true) }, watched, emptyList(), TriageSettings(), now, cal).isEmpty())
        assertTrue(DiscordSummaryRules.toSummarise(busy, setOf(general.key), emptyList(), TriageSettings(), now, cal).isEmpty())
        assertTrue(DiscordSummaryRules.toSummarise(busy, watched, emptyList(), TriageSettings(neverToAi = setOf("Example Traders")), now, cal).isEmpty())
        assertTrue(DiscordSummaryRules.toSummarise(busy, watched, emptyList(), TriageSettings(neverToAi = setOf("#trade-ideas")), now, cal).isEmpty())
    }

    @Test
    fun aSummaryUpToDateIsNotSentAgainAndNewsWaitsTenMinutes() {
        val key = DiscordSummaryRules.chatKey(ideas.key)
        val upTo = now - 10 * 60_000L
        assertTrue(DiscordSummaryRules.toSummarise(busy, watched, listOf(summary(key, upTo, 1)), TriageSettings(), now, cal).isEmpty())
        val more = busy + post("d", ideas, "Admin", "Moving stop to entry", 1)
        assertTrue(DiscordSummaryRules.toSummarise(more, watched, listOf(summary(key, upTo, 5)), TriageSettings(), now, cal).isEmpty())
        assertEquals(1, DiscordSummaryRules.toSummarise(more, watched, listOf(summary(key, upTo, 11)), TriageSettings(), now, cal).size)
        // A summary from before the oldest open post (an earlier batch, since Done) doesn't count.
        assertEquals(1, DiscordSummaryRules.toSummarise(busy, watched, listOf(summary(key, now - 40 * 60_000L, 1)), TriageSettings(), now, cal).size)
    }

    @Test
    fun theAnswerIsCheckedAndTheCardShowsIt() {
        val due = DiscordSummaryRules.toSummarise(busy, watched, emptyList(), TriageSettings(), now, cal)
        val key = due.single().chatKey
        assertEquals("Admin's BTC long above 68k, stop 66.5k; Sam is in",
            DiscordSummaryRules.check(due, listOf(ChatSummaryRules.Raw("#trade-ideas · Example Traders", "  Admin's BTC long above 68k, stop 66.5k; Sam is in ")))[key])
        assertNull(DiscordSummaryRules.check(due, listOf(ChatSummaryRules.Raw("#trade-ideas · Example Traders", "Join at discord.gg/abc")))[key])
        assertNull(DiscordSummaryRules.check(due, listOf(ChatSummaryRules.Raw("#trade-ideas · Example Traders", "See https://example.com")))[key])
        assertNull(DiscordSummaryRules.check(due, listOf(ChatSummaryRules.Raw("#other", "Something")))[key])

        val s = summary(key, now - 10 * 60_000L, 1)
        val card = DiscordRules.cards(busy, watched, now, cal, summaries = listOf(s))!!.cards.single()
        assertEquals("Admin's BTC long above 68k, stop 66.5k", card.summary)
        assertEquals(3, card.posts.size)
        // Fewer than three open posts: no summary line.
        assertNull(DiscordRules.cards(busy.take(2), watched, now, cal, summaries = listOf(s))!!.cards.single().summary)
    }
}
