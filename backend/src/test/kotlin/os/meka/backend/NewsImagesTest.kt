package os.meka.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import os.meka.backend.integrations.BbcNewsRss
import os.meka.backend.integrations.InMemoryIntegrationStore
import os.meka.backend.integrations.InMemoryNewsImageStore
import os.meka.backend.integrations.Integrations
import os.meka.backend.integrations.NewsImageMaker
import os.meka.backend.integrations.NewsImages
import os.meka.backend.integrations.PostgresNewsImageStore
import os.meka.backend.integrations.Rss
import os.meka.backend.integrations.Thumbnail
import os.meka.backend.integrations.TokenCipher
import os.meka.core.domain.MekaSchema
import os.meka.core.domain.News
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Replica
import os.meka.core.wire.WireCodec
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** News pictures (build plan M1, "News ticker", images slice): found in feeds, made small, kept, served by key. */
class NewsImagesTest {
    private var now = 1_791_270_000_000L // 2026-10-06T07:00Z

    private fun picture(w: Int, h: Int, noisy: Boolean = false, format: String = "png"): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val r = java.util.Random(7)
        for (y in 0 until h) for (x in 0 until w) {
            img.setRGB(x, y, if (noisy) r.nextInt(0xffffff) else ((x * 255 / w) shl 16) or ((y * 255 / h) shl 8))
        }
        return ByteArrayOutputStream().also { ImageIO.write(img, format, it) }.toByteArray()
    }

    // ---- Making pictures ----

    @Test
    fun picturesAreShrunkToSmallJpegs() {
        for ((w, h, noisy) in listOf(Triple(1600, 900, false), Triple(1600, 900, true), Triple(1200, 675, true))) {
            val t = assertNotNull(NewsImageMaker.thumbnail(picture(w, h, noisy)))
            assertTrue(t.bytes.size <= NewsImageMaker.MAX_BYTES, "${t.bytes.size} bytes")
            assertTrue(t.width <= NewsImageMaker.MAX_WIDTH && t.height <= NewsImageMaker.MAX_HEIGHT)
            // A real JPEG of the size it says.
            assertEquals(0xFF.toByte(), t.bytes[0]); assertEquals(0xD8.toByte(), t.bytes[1])
            val back = ImageIO.read(t.bytes.inputStream())
            assertEquals(t.width to t.height, back.width to back.height)
        }
        // 16:9 stays 16:9; a small picture isn't blown up; a tall one is cropped around its middle.
        assertEquals(320 to 180, NewsImageMaker.thumbnail(picture(1600, 900))!!.let { it.width to it.height })
        assertEquals(240 to 135, NewsImageMaker.thumbnail(picture(240, 135, format = "jpg"))!!.let { it.width to it.height })
        assertEquals(200 to 320, NewsImageMaker.thumbnail(picture(200, 1200))!!.let { it.width to it.height })
    }

    @Test
    fun logosPixelsAndJunkAreRefused() {
        assertNull(NewsImageMaker.thumbnail(picture(40, 40)))
        assertNull(NewsImageMaker.thumbnail(picture(1, 1)))
        assertNull(NewsImageMaker.thumbnail(ByteArray(2000) { it.toByte() }))
        assertNull(NewsImageMaker.thumbnail("<html>not a picture</html>".toByteArray()))
        assertNull(NewsImageMaker.thumbnail(ByteArray(0)))
    }

    // ---- Finding them in feeds ----

    private fun rss(vararg items: String) = """<?xml version="1.0" encoding="UTF-8"?>
        <rss xmlns:media="http://search.yahoo.com/mrss/" xmlns:content="http://purl.org/rss/1.0/modules/content/" version="2.0"><channel>
        ${items.joinToString("\n")}
        </channel></rss>"""

    private fun item(slug: String, extra: String) =
        """<item><title>Story $slug</title><link>https://news.example/$slug</link><pubDate>Tue, 06 Oct 2026 06:00:00 GMT</pubDate>$extra</item>"""

    @Test
    fun feedsNameTheirPicturesInSeveralWays() {
        val items = Rss.parse(
            rss(
                // The BBC: a 240 px thumbnail.
                item("bbc", """<media:thumbnail width="240" height="135" url="https://ichef.bbci.co.uk/ace/standard/240/a.jpg"/>"""),
                // Several sizes: the one nearest 320 px wide (at least 320), not a video.
                item("sizes", """<media:content medium="image" width="1280" url="https://img.example/1280.jpg"/>
                    <media:content medium="image" width="400" url="https://img.example/400.jpg?w=400&amp;q=80"/>
                    <media:content type="video/mp4" width="640" url="https://img.example/clip.mp4"/>"""),
                item("enclosure", """<enclosure url="https://img.example/e.png" type="image/png" length="1"/>"""),
                // WordPress-style: the picture is only in the description's HTML.
                item("html", """<description><![CDATA[<p><img width="800" src="https://img.example/wp.jpg" class="x"/>A long enough summary for the story.</p>]]></description>"""),
                item("encoded", """<content:encoded><![CDATA[<figure><img src='https://img.example/enc.jpg'></figure>]]></content:encoded>"""),
                // http, data: and odd ports are never fetched.
                item("http", """<media:thumbnail url="http://img.example/a.jpg"/>"""),
                item("data", """<description><![CDATA[<img src="data:image/png;base64,AAAA">]]></description>"""),
                item("port", """<enclosure url="https://img.example:8443/p.jpg" type="image/jpeg"/>"""),
                item("none", ""),
            ),
        ).associate { it.id.substringAfterLast('/') to it.imageUrl }
        assertEquals("https://ichef.bbci.co.uk/ace/standard/240/a.jpg", items["bbc"])
        assertEquals("https://img.example/400.jpg?w=400&q=80", items["sizes"])
        assertEquals("https://img.example/e.png", items["enclosure"])
        assertEquals("https://img.example/wp.jpg", items["html"])
        assertEquals("https://img.example/enc.jpg", items["encoded"])
        for (k in listOf("http", "data", "port", "none")) assertNull(items[k], k)
    }

    @Test
    fun atomFeedsNameThemToo() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?><feed xmlns="http://www.w3.org/2005/Atom">
            <entry><title>Verge</title><link rel="alternate" href="https://www.theverge.com/a"/><published>2026-10-06T06:00:00Z</published>
            <content type="html">&lt;figure&gt;&lt;img alt="" src="https://platform.theverge.com/a.jpg?quality=90&amp;amp;crop=0"/&gt;&lt;/figure&gt;&lt;p&gt;Text&lt;/p&gt;</content></entry>
            <entry><title>Enclosed</title><link href="https://ex.example/b"/><link rel="enclosure" type="image/jpeg" href="https://ex.example/b.jpg"/><published>2026-10-06T06:00:00Z</published></entry>
            </feed>"""
        val items = Rss.parseAny(xml)
        assertEquals("https://platform.theverge.com/a.jpg?quality=90&crop=0", items[0].imageUrl)
        assertEquals("https://ex.example/b.jpg", items[1].imageUrl)
    }

    @Test
    fun onlyPublicHostsAreFetched() {
        for (h in listOf("127.0.0.1", "localhost", "10.0.0.5", "172.16.1.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "::1", "fd00::1", "0.0.0.0")) {
            assertFalse(NewsImages.isPublicHost(h), h)
        }
        assertTrue(NewsImages.isPublicHost("93.184.215.14"))
        assertNull(NewsImages.safeImageUrl("https://user:pw@img.example/a.jpg"))
        assertNull(NewsImages.safeImageUrl("https://img.example/a b.jpg"))
        assertEquals("https://img.example/a.jpg?x=1&y=2", NewsImages.safeImageUrl(" https://img.example/a.jpg?x=1&amp;y=2 "))
    }

    // ---- Keeping them ----

    @Test
    fun eachPictureIsMadeOnceKeptWhileMentionedAndThenTidiedAway() {
        val store = InMemoryNewsImageStore { now }
        val asked = mutableListOf<String>()
        val images = NewsImages(store, fetch = { url -> asked += url; if ("broken" in url) null else picture(800, 450) }, now = { now })
        val a = "https://img.example/a.jpg"
        val got = images.prepare(listOf(a, "https://img.example/broken.jpg", "http://img.example/plain.jpg", a))
        assertEquals(setOf(a), got.keys)
        assertEquals(NewsImages.key(a), got[a])
        assertEquals(listOf(a, "https://img.example/broken.jpg"), asked)
        assertNotNull(images.get(got.getValue(a)))

        // Mentioned again: not fetched again, and its clock restarts.
        now += 2 * 24 * 3_600_000L
        assertEquals(got, images.prepare(listOf(a)))
        assertEquals(2, asked.count { it == a } + asked.count { "broken" in it })
        // No feed mentions it for three days: deleted.
        now += NewsImages.KEEP_MS + 1
        images.prepare(emptyList())
        assertNull(images.get(got.getValue(a)))
    }

    @Test
    fun aRefreshMakesOnlySoManyNewPictures() {
        val images = NewsImages(InMemoryNewsImageStore { now }, fetch = { picture(400, 225) }, now = { now })
        val urls = (1..60).map { "https://img.example/$it.jpg" }
        assertEquals(5, images.prepare(urls, maxNew = 5).size)
        assertEquals(10, images.prepare(urls, maxNew = 5).size) // the next refresh carries on
    }

    @Test
    fun keysAreThirtyTwoHexCharacters() {
        val k = NewsImages.key("https://img.example/a.jpg")
        assertEquals(32, k.length)
        assertTrue(os.meka.core.domain.NewsRules.isImageKey(k))
        assertEquals(k, NewsImages.key("https://img.example/a.jpg"))
    }

    // ---- Reaching the devices ----

    private val noCipher = object : TokenCipher {
        override fun encrypt(plain: ByteArray, context: Map<String, String>) = plain
        override fun decrypt(cipher: ByteArray, context: Map<String, String>) = cipher
    }

    @Test
    fun headlinesCarryTheServersKeyNeverTheAddress() {
        var down = false
        val thumb = "https://ichef.bbci.co.uk/ace/standard/240/a.jpg"
        val feed = BbcNewsRss { url ->
            if (url.endsWith("/news/rss.xml")) rss(
                """<item><title>With a picture</title><link>https://www.bbc.com/news/articles/p1</link><pubDate>Tue, 06 Oct 2026 06:00:00 GMT</pubDate>
                   <media:thumbnail width="240" height="135" url="$thumb"/></item>""",
                """<item><title>Without</title><link>https://www.bbc.com/news/articles/p2</link><pubDate>Tue, 06 Oct 2026 05:00:00 GMT</pubDate></item>""",
            ) else rss()
        }
        val pictures = InMemoryNewsImageStore { now }
        val images = NewsImages(pictures, fetch = { if (down) error("offline") else picture(240, 135, format = "jpg") }, now = { now })
        val store = InMemoryIntegrationStore(knownHouseholds = listOf("home"))
        val ops = InMemoryServerOpStore()
        val integrations = Integrations(store, ops, emptyMap(), { null }, noCipher, "https://meka.example", { now }, news = mapOf(feed.id to feed), images = images)
        integrations.syncAll()

        val r = Replica("home", "fold", HlcClock("fold", { now }), InMemoryReplicaStore(), MekaSchema) { "d" + System.nanoTime() }
        var cursor = 0L
        fun pull() { val page = ops.after("home", cursor, 10_000); r.applyRemoteBatch(page.map { it.op }); page.lastOrNull()?.let { cursor = it.seq } }
        pull()
        val byTitle = News(r).all().associateBy { it.title }
        assertEquals(NewsImages.key(thumb), byTitle.getValue("With a picture").imageKey)
        assertNull(byTitle.getValue("Without").imageKey)
        // The publisher's address is never written into anyone's data.
        assertTrue(ops.after("home", 0, 10_000).none { thumb in it.op.value.toString() })

        // An hour on the picture can't be fetched, but the server still has it: nothing changes.
        down = true
        now += 61 * 60_000L
        val before = ops.after("home", 0, 10_000).size
        integrations.syncAll()
        assertEquals(before, ops.after("home", 0, 10_000).size)
    }

    @Test
    fun keyedDevicesFetchPicturesByKeyOnly() = testApplication {
        val devices = InMemoryDeviceRegistry()
        val foldSecret = devices.enrol("hh", "fold")
        val foldKey = TestDeviceKey().also { devices.registerKey(DeviceIdentity("hh", "fold"), it.publicB64) }
        val bare = devices.enrol("hh", "old")
        val store = InMemoryNewsImageStore { now }
        val jpeg = NewsImageMaker.thumbnail(picture(800, 450))!!
        val key = NewsImages.key("https://img.example/a.jpg")
        store.put(key, Thumbnail(jpeg.bytes, jpeg.width, jpeg.height))
        application { mekaSync(InMemoryServerOpStore(), devices, newsImages = NewsImages(store, fetch = { null }, now = { now })) }

        val body = WireCodec.encodeNewsImageRef(key)
        val ok = client.post("/v1/news/image") { with(foldKey) { signed(foldSecret, "/v1/news/image", body) } }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertContentEquals(jpeg.bytes, Base64.getDecoder().decode(WireCodec.decodeChunkData(ok.bodyAsText())))

        val missing = WireCodec.encodeNewsImageRef("f".repeat(32))
        assertEquals(HttpStatusCode.NotFound, client.post("/v1/news/image") { with(foldKey) { signed(foldSecret, "/v1/news/image", missing) } }.status)
        // An address or a path is not a key.
        val address = """{"key":"https://img.example/a.jpg"}"""
        assertEquals(HttpStatusCode.BadRequest, client.post("/v1/news/image") { with(foldKey) { signed(foldSecret, "/v1/news/image", address) } }.status)
        // Unsigned, unkeyed and the release-only publisher are refused.
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/news/image") { header("Authorization", "Bearer $foldSecret"); setBody(body) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/v1/news/image") { header("Authorization", "Bearer $bare"); setBody(body) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/v1/news/image") { header("Authorization", "Publisher github-build"); setBody(body) }.status)
    }

    /** The store on Postgres (CI's service container); skipped when MEKA_TEST_DB_URL is unset. */
    @Test
    fun postgresStoreKeepsPicturesWhileMentioned() {
        val url = System.getenv("MEKA_TEST_DB_URL")?.takeIf { it.isNotBlank() } ?: return
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = url; username = System.getenv("MEKA_TEST_DB_USER") ?: "postgres"
            password = System.getenv("MEKA_TEST_DB_PASSWORD") ?: "postgres"; maximumPoolSize = 2
        }).use { ds ->
            Migrations.apply(ds)
            val store = PostgresNewsImageStore(ds)
            val k1 = NewsImages.key("https://pg.example/1.jpg")
            val k2 = NewsImages.key("https://pg.example/2.jpg")
            ds.connection.use { c -> c.createStatement().execute("DELETE FROM news_image WHERE key IN ('$k1', '$k2')") }
            store.put(k1, Thumbnail(byteArrayOf(1, 2, 3), 320, 180))
            store.put(k1, Thumbnail(byteArrayOf(4, 5), 320, 180)) // made again: replaced
            assertContentEquals(byteArrayOf(4, 5), store.get(k1)!!.bytes)
            assertEquals(setOf(k1), store.existing(setOf(k1, k2)))
            assertEquals(emptySet(), store.existing(emptySet()))
            ds.connection.use { c -> c.createStatement().execute("UPDATE news_image SET used_at = now() - interval '10 days' WHERE key = '$k1'") }
            store.touch(setOf(k1))
            store.prune(System.currentTimeMillis() - NewsImages.KEEP_MS)
            assertNotNull(store.get(k1))
            ds.connection.use { c -> c.createStatement().execute("UPDATE news_image SET used_at = now() - interval '10 days' WHERE key = '$k1'") }
            store.prune(System.currentTimeMillis() - NewsImages.KEEP_MS)
            assertNull(store.get(k1))
        }
    }
}
