package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Train-time queue, slice 2: a saved article's readable words, kept on the phone for the train. */
class QueueReaderTest {
    private val para1 = "Trains on the line into Oakfield Junction ran late again this week, and the operator says the signals between Northgate and Westhill are to blame for most of the hold-ups."
    private val para2 = "Engineers will work through the weekend &amp; replace the cables between Bedford and St Pancras, it said, with buses running between Luton and Bedford on Saturday."
    private val para3 = "Passengers were told to expect &ldquo;minor delays&rdquo; until the work is done &#8212; probably by Monday."
    private val page = """
        <html><head><title>Fallback title | Site</title>
        <meta property="og:title" content="Why the trains run late">
        <script>var p = "<p>not this one, a script string that is long enough to be a paragraph</p>";</script>
        <style>p { color: red }</style></head>
        <body>
          <nav><ul><li>Home</li><li>News — a long menu entry that should never be read as a paragraph</li></ul></nav>
          <header><p>Site banner that is long enough to look like a paragraph to a careless reader</p></header>
          <article>
            <h1>Why the trains run late</h1>
            <p>$para1</p>
            <!-- <p>A commented-out paragraph that is long enough to count if it were read</p> -->
            <figure><img src="x.jpg"><figcaption>Image caption: a train that is waiting at a red signal today</figcaption></figure>
            <h2>What happens next</h2>
            <p>$para2</p>
            <p>Advertisement</p>
            <p class="x">$para3</p>
            <p>$para3</p>
            <h2>Related</h2>
          </article>
          <aside><p>Most read: a sidebar story that is long enough to look like a paragraph here</p></aside>
          <footer><p>Copyright 2026 and a long footer line that should not be in the article</p></footer>
        </body></html>
    """.trimIndent()

    @Test
    fun anArticleKeepsItsTitleHeadingsAndParagraphsOnly() {
        val p = ReaderRules.extract(page, "Queue title")!!
        assertEquals("Why the trains run late", p.title)
        assertEquals(
            listOf(
                ReaderBlock(para1, false),
                ReaderBlock("What happens next", true),
                ReaderBlock(para2.replace("&amp;", "&"), false),
                ReaderBlock("Passengers were told to expect “minor delays” until the work is done — probably by Monday.", false),
            ),
            p.blocks,
        )
        assertFalse(p.cut)
        assertEquals("1 min read", p.minutes)
        assertEquals("1 min read · saved to read offline", ReaderRules.rowLine(p))
        assertEquals("6 min read", ReaderRules.minutes(1_200))
    }

    @Test
    fun tooLittleToReadIsNotKeptAndAPageWithNoTitleUsesTheQueuesOwn() {
        assertNull(ReaderRules.extract("<html><body><div id=app></div><p>Enable JavaScript to read this.</p></body></html>", "X"))
        val untitled = "<body><main><p>${para1}</p><p>${para2}</p><p>${para3}</p><p>${para1} again</p></main></body>"
        assertEquals("Queue title", ReaderRules.extract(untitled, "Queue title")!!.title)
        assertEquals("Fallback title | Site", ReaderRules.title("<title>Fallback title | Site</title>"))
        assertEquals("a & b ’ 😀 &bogus;", ReaderRules.decode("a &amp; b &#x2019; &#128512; &bogus;"))
    }

    @Test
    fun aLongArticleIsCutAndSaysTheRestIsOnline() {
        val long = "<article>" + (1..2_000).joinToString("") { "<p>Paragraph $it of a very long piece about the railways of England.</p>" } + "</article>"
        val p = ReaderRules.extract(long, "Long")!!
        assertTrue(p.cut)
        assertTrue(p.blocks.sumOf { it.text.length } <= ReaderRules.MAX_TEXT)
    }

    @Test
    fun aPageRoundTripsAndBadStoredValuesReadAsNothing() {
        val p = ReaderRules.extract(page, "Queue title")!!.copy(savedAtMs = 42L)
        assertEquals(p, ReaderCodec.decode(ReaderCodec.encode(p)))
        assertNull(ReaderCodec.decode(null))
        assertNull(ReaderCodec.decode("something else\nx\n1\nwhole\np hi"))
        assertNull(ReaderCodec.decode("meka-page/1\nT\nnot a time\nwhole\np hi"))
        val index = mapOf("q1" to "6 min read", "q2" to "1 min read")
        assertEquals(index, ReaderCodec.decodeIndex(ReaderCodec.encodeIndex(index)))
        assertEquals(emptyMap(), ReaderCodec.decodeIndex("garbage"))
    }

    @Test
    fun onlyWaitingArticlesAreFetchedAndAFailureWaitsSixHours() {
        val now = 1_791_622_800_000L
        fun item(id: String, url: String, kind: QueueKind = QueueKind.ARTICLE, done: Long = 0L, added: Long = now) =
            QueueItem(id, url, id, kind, QueueRules.host(url), added, done)
        val items = listOf(
            item("a", "https://bbc.co.uk/news/1", added = now - 1),
            item("b", "https://overcast.fm/+x", QueueKind.PODCAST),
            item("c", "https://bbc.co.uk/news/2", done = now - 1),
            item("d", "https://theguardian.com/x", added = now - 2),
            item("e", "https://ft.com/x", added = now - 3),
        )
        assertEquals(listOf("a", "e"), ReaderRules.toFetch(items, setOf("d"), emptyMap(), now).map { it.id })
        assertEquals(listOf("e"), ReaderRules.toFetch(items, setOf("d"), mapOf("a" to now - 60_000), now).map { it.id })
        assertEquals(listOf("a", "e"), ReaderRules.toFetch(items, setOf("d"), mapOf("a" to now - ReaderRules.RETRY_MS), now).map { it.id })
        // Removed links lose their words; done ones keep them while they're in "Done lately".
        assertEquals(setOf("gone"), ReaderRules.stale(setOf("a", "c", "gone"), items, now))
    }
}
