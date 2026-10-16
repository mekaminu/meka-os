package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    // ---- Slice 2: watched people and words ----

    @Test
    fun wordsMatchWholeWordsAndTickers() {
        assertTrue(DiscordRules.saysWord("BTC long idea above 68k, Stop 66.5k", "stop"))
        assertTrue(DiscordRules.saysWord("Long \$BTC here", "BTC"))
        assertTrue(DiscordRules.saysWord("watching BTC/USDT", "btc"))
        assertTrue(DiscordRules.saysWord("Long \$BTC here", "\$btc"))
        assertTrue(DiscordRules.saysWord("Take profit at 70k", "take profit"))
        assertFalse(DiscordRules.saysWord("I stopped trading", "stop"))
        assertFalse(DiscordRules.saysWord("nonstop rally", "stop"))
        assertFalse(DiscordRules.saysWord("BTCUSD", "BTC"))
        assertTrue(DiscordRules.saysWord("TP hit", "TP"))
        assertFalse(DiscordRules.saysWord("", "TP"))
    }

    @Test
    fun alertsForAWatchedPersonOrWordAndSaysWhy() {
        val people = listOf("Admin")
        val words = listOf("entry", "stop", "BTC")
        assertEquals("From Admin", DiscordRules.alertWhy("admin", "gm all", people, words))
        assertEquals("From Admin · says “entry”, “stop”", DiscordRules.alertWhy("@Admin", "entry 67.8k stop 66.5k BTC", people, words))
        assertEquals("Says “BTC”", DiscordRules.alertWhy("Sam", "BTC looking weak", people, words))
        assertNull(DiscordRules.alertWhy("Sam", "anyone around?", people, words))
        // An image post never matches by its stand-in words.
        assertNull(DiscordRules.alertWhy("Sam", "sent an image", people, listOf("image", "sent")))
        assertNull(DiscordRules.alertWhy("Admin", "x", emptyList(), emptyList()))
    }

    @Test
    fun addingAndRemovingPeopleAndWords() {
        val (one, line) = DiscordRules.added(emptyList(), "  @Admin ", person = true)
        assertEquals(listOf("Admin"), one)
        assertEquals("Alerts straight away for posts from Admin", line)
        assertEquals("Already alerting for admin", DiscordRules.added(one!!, "admin", true).second)
        assertNull(DiscordRules.added(emptyList(), "x", person = false).first)
        assertEquals("Alerts straight away for posts saying “stop”", DiscordRules.added(emptyList(), "\"stop\"", false).second)
        val full = (1..DiscordRules.MAX_WORDS).map { "w$it" }
        assertEquals("Up to ${DiscordRules.MAX_WORDS} words", DiscordRules.added(full, "more", false).second)
        assertEquals(emptyList(), DiscordRules.removed(one, "ADMIN", true).first)
        assertNull(DiscordRules.removed(one, "Sam", true).first)
        assertEquals(DiscordRules.NO_ALERTS, DiscordRules.alertLine(emptyList(), emptyList()))
        assertEquals("Alerts straight away for posts from Admin, or saying BTC or stop", DiscordRules.alertLine(listOf("Admin"), listOf("BTC", "stop")))
        assertEquals("Alerts straight away for saying a, b, c or 2 more", DiscordRules.alertLine(emptyList(), listOf("a", "b", "c", "d", "e")))
    }

    @Test
    fun theAlertNoticeAndTheCardMarkAlertingPosts() {
        fun p(id: String, who: String, at: Long, alert: String?, done: Boolean = false) =
            DiscordRules.Post(id, ideas, who, "post $id", at, done, alert)
        val posts = listOf(
            p("a", "Admin", now - 120_000, "From Admin"),
            p("b", "Sam", now - 60_000, null),
            p("c", "Sam", now, "Says “stop”"),
            p("d", "Admin", now - 30_000, "From Admin", done = true),
            p("e", "Admin", now - DiscordRules.ALERT_WINDOW_MS - 1, "From Admin"),
            DiscordRules.Post("g", chat, "Jordan", "gm", now + 1, false),
        )
        val n = DiscordRules.alertNotice(posts, ideas.key, now)!!
        assertEquals("#trade-ideas · Example Traders", n.title)
        assertEquals("Sam: post c", n.text)
        assertEquals("Says “stop”", n.why)
        assertEquals(listOf("Admin: post a", "Sam: post c"), n.lines)
        assertEquals(2, n.count)
        assertNull(DiscordRules.alertNotice(posts, chat.key, now))
        val cards = DiscordRules.cards(posts, setOf(ideas.key, chat.key), now + 1, cal)!!
        // The channel with alerts comes first even though the other has the newest post.
        assertEquals(listOf(ideas.key, chat.key), cards.cards.map { it.key })
        assertEquals("3 alerts · latest 09:00", cards.cards[0].alertLine)
        assertEquals("Says “stop”", cards.cards[0].posts.last().alert)
        assertNull(cards.cards[1].alertLine)
        // Who posted lately is offered, busiest first, minus anyone already watched.
        assertEquals(listOf("Sam", "Jordan"), DiscordRules.suggestedPeople(posts, setOf(ideas.key, chat.key), listOf("admin"), now))
    }

    @Test
    fun theStoreMarksAlertsAndOnlyFreshOnesNotify() {
        var n = 0
        val replica = os.meka.core.sync.Replica("hh", "android", os.meka.core.sync.HlcClock("android", { now }), os.meka.core.sync.InMemoryReplicaStore(), MekaSchema) { "op" + n++ }
        val store = DiscordStore(replica)
        store.note(listOf(DiscordCaptured(ideas, "Admin", "hello", now)), now)
        assertEquals("Following #trade-ideas · Example Traders · its posts come to Needs you", store.setWatched(ideas.key, true, now).first)
        assertEquals("Alerts straight away for posts saying “stop”", store.setAlert("stop", person = false, add = true, nowMs = now))
        store.setAlert("Admin", person = true, add = true, nowMs = now)
        val noted = store.note(listOf(
            DiscordCaptured(ideas, "Sam", "stop 66.5k", now),
            DiscordCaptured(ideas, "Sam", "nothing here", now + 1),
            DiscordCaptured(ideas, "Admin", "an old one", now - DiscordRules.ALERT_FRESH_MS - 1),
        ), now)
        assertEquals(3, noted.fresh)
        assertEquals(listOf(ideas.key), noted.alerts.map { it.key })
        assertEquals(2, noted.alerts.single().count)
        assertEquals(setOf("Says “stop”", "From Admin", null), store.posts().filter { it.text != "hello" }.map { it.alert }.toSet())
        // Only the stale alert this time: marked, but nothing to post.
        assertTrue(store.note(listOf(DiscordCaptured(ideas, "Admin", "older still", now - DiscordRules.ALERT_FRESH_MS - 5)), now).alerts.isEmpty())
        assertEquals("No longer alerting for “stop”", store.setAlert("STOP", person = false, add = false, nowMs = now))
        assertEquals(emptyList(), store.words())
    }

    @Test
    fun aKeptIdeaReadsAsOneLineWithWhereItCameFrom() {
        assertEquals("Admin in #trade-ideas: BTC long above 68k, stop 66.5k",
            DiscordRules.ideaTitle("Admin", "#trade-ideas", "BTC long above 68k,\n  stop 66.5k"))
        assertEquals("Someone in #x: hi", DiscordRules.ideaTitle(" ", "x", "hi"))
        val long = DiscordRules.ideaTitle("Admin", "trade-ideas", List(60) { "word$it" }.joinToString(" "))
        assertTrue(long.length <= DiscordRules.IDEA_MAX, long)
        assertTrue(long.endsWith("…"), long)
        assertFalse(long.contains("word5 …"), long)
        val p = DiscordRules.Post("p1", DiscordChannel("example traders#trade-ideas", "Example Traders", "trade-ideas"),
            "Admin", "BTC long above 68k", now, done = false)
        assertEquals("BTC long above 68k\n\nFrom Discord · #trade-ideas · Example Traders · Admin · Mon 12 Oct 09:00\n" +
            "Information only · Navilife never trades", DiscordRules.ideaNotes(p, cal))
        // The card says which posts are already kept.
        val cards = DiscordRules.cards(listOf(p, p.copy(id = "p2", atMs = now + 1, kept = "t1")), setOf(p.channel.key), now + 60_000, cal)!!
        assertEquals(listOf(false, true), cards.cards.single().posts.map { it.kept })
        assertEquals("Trading ideas", ListRules.kindLabel(SomedayKind.TRADING))
    }
}
