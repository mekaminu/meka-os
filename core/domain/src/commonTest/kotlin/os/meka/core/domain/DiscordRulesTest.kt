package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Discord channel watch, slice 1: reading Discord's notifications, the watched-channel filter and the cards. */
class DiscordRulesTest {
    private val cal = LocalCalendar.UTC
    private val now = 1_791_795_600_000L // Mon 12 Oct 2026 09:00 UTC

    @Test
    fun readsTheChannelFromEachFormDiscordUses() {
        fun c(conv: String?, title: String? = null, sub: String? = null, group: Boolean = true) = DiscordRules.channelOf(conv, title, sub, group)
        assertEquals(DiscordChannel("example traders#trade-ideas", "Example Traders", "trade-ideas"), c("Example Traders > #trade-ideas"))
        assertEquals(DiscordChannel("example traders#trade-ideas", "Example Traders", "trade-ideas"), c("#trade-ideas, Example Traders"))
        assertEquals(DiscordChannel("example traders#trade-ideas", "Example Traders", "trade-ideas"), c("#trade-ideas (Example Traders)"))
        assertEquals(DiscordChannel("example traders#trade-ideas", "Example Traders", "trade-ideas"), c("Example Traders • #trade-ideas"))
        assertEquals(DiscordChannel("example traders#trade-ideas", "Example Traders", "trade-ideas"), c(null, "Alex (#trade-ideas, Example Traders)"))
        // "#channel" alone takes the server from the sub-text; with none it's the channel by itself.
        assertEquals(DiscordChannel("example traders#trade-ideas", "Example Traders", "trade-ideas"), c("#trade-ideas", sub = "Example Traders"))
        assertEquals(DiscordChannel("#trade-ideas", null, "trade-ideas"), c("#trade-ideas", sub = "3 new messages"))
        // WhatsApp-style counts are cut first.
        assertEquals("example traders#trade-ideas", c("Example Traders > #trade-ideas (4 messages)")?.key)
        // A group DM keeps its name; a one-to-one DM is left alone.
        assertEquals(DiscordChannel("dm:the lads", null, "The lads"), c("The lads"))
        assertNull(c(null, "Alex", group = false))
        assertNull(c("", "Alex", group = false))
        assertEquals("#trade-ideas · Example Traders", c("Example Traders > #trade-ideas")?.label)
    }

    @Test
    fun cleansTheWordsImagesAndLongPosts() {
        assertEquals("sent an image", DiscordRules.cleanText("Sent an image"))
        assertEquals("sent an image", DiscordRules.cleanText("uploaded a file."))
        assertEquals("BTC long idea above 68k, stop 66.5k", DiscordRules.cleanText("  BTC long idea\\nabove 68k,   stop 66.5k ".replace("\\n", "\n")))
        assertNull(DiscordRules.cleanText("   "))
        val long = DiscordRules.cleanText("word ".repeat(300))!!
        assertTrue(long.endsWith("…") && long.length <= DiscordRules.MAX_TEXT + 1)
    }

    private val ideas = DiscordChannel("example traders#trade-ideas", "Example Traders", "trade-ideas")
    private val chat = DiscordChannel("example traders#general", "Example Traders", "general")

    @Test
    fun keepsOnlyWatchedChannelsAndNotesEverySeenOne() {
        val incoming = listOf(
            DiscordCaptured(ideas, "Admin", "BTC long idea above 68k", now),
            DiscordCaptured(chat, "Sam", "gm", now + 1),
        )
        assertEquals(listOf("BTC long idea above 68k"), DiscordRules.toKeep(incoming, setOf(ideas.key)).map { it.text })
        assertTrue(DiscordRules.toKeep(incoming, emptySet()).isEmpty())
        val seen = DiscordRules.noteSeen(emptyList(), incoming, emptySet())!!
        assertEquals(listOf(chat.key, ideas.key), seen.map { it.channel.key })
        // Seen again within the hour: nothing to write; an hour later the time moves.
        assertNull(DiscordRules.noteSeen(seen, incoming.map { it.copy(atMs = it.atMs + 60_000) }, emptySet()))
        assertNotNull(DiscordRules.noteSeen(seen, incoming.map { it.copy(atMs = it.atMs + DiscordRules.SEEN_REFRESH_MS) }, emptySet()))
        // The seen list round-trips through the synced text.
        val text = DiscordRules.seenText(seen)
        assertEquals(seen, DiscordRules.seen(mapOf(DiscordWatchFields.CHANNELS to os.meka.core.sync.FieldValue.Text(text))))
        val view = DiscordRules.view(seen, setOf(ideas.key), now + 2, cal)
        assertEquals(listOf(true, false), view.rows.map { it.watched })
        assertEquals("Following · last post 09:00", view.rows.first().line)
        assertTrue(view.statusLine.startsWith("Following 1 channel"))
    }

    @Test
    fun oneCardPerChannelNewestFirstWithItsPosts() {
        fun p(id: String, ch: DiscordChannel, who: String, at: Long, done: Boolean = false) =
            DiscordRules.Post(id, ch, who, "post $id", at, done)
        val posts = (1..10).map { p("i$it", ideas, if (it % 2 == 0) "Admin" else "Sam", now - 3_600_000L + it * 60_000L) } +
            listOf(p("g1", chat, "Jordan", now - 60_000L), p("g0", chat, "Jordan", now - 120_000L, done = true)) +
            listOf(p("old", ideas, "Admin", now - DiscordRules.KEEP_MS - 1))
        val cards = DiscordRules.cards(posts, setOf(ideas.key, chat.key), now, cal)!!
        assertEquals(listOf(chat.key, ideas.key), cards.cards.map { it.key })
        val c = cards.cards[1]
        assertEquals("#trade-ideas", c.title)
        assertEquals(10, c.ids.size)
        assertEquals(DiscordRules.SHOWN, c.posts.size)
        assertEquals("+ 2 earlier posts", c.moreLine)
        assertEquals("10 posts since you last looked · latest 08:10 · Sam and Admin", c.line)
        assertEquals("1 post since you last looked · latest 08:59 · Jordan", cards.cards[0].line)
        assertTrue(cards.headline.startsWith("2 channels · 11 new posts"))
        // A channel he stopped following leaves Needs you.
        assertEquals(listOf(chat.key), DiscordRules.cards(posts, setOf(chat.key), now, cal)!!.cards.map { it.key })
        assertNull(DiscordRules.cards(posts, emptySet(), now, cal))
    }
}
