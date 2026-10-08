package os.meka.backend.integrations

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream
import javax.sql.DataSource

/**
 * News pictures (build plan M1, "News ticker", images slice). The feeds name a picture for most stories
 * (`media:thumbnail`, `media:content`, an image `enclosure`, or the first `<img>` in the description); the server
 * fetches it once (a plain GET of public content: nothing about Meka is sent), shrinks it to a JPEG at most
 * [NewsImageMaker.MAX_WIDTH] wide and [NewsImageMaker.MAX_BYTES] in size, and keeps it in its own database under
 * [key] (no S3 bucket, no paid image service). The apps fetch pictures from their own server only, by key, so the
 * publishers never see the devices. Pictures are untrusted: decoded with the JDK's own readers, size-checked before
 * decoding, and re-encoded, so nothing of the original file reaches a device.
 */
class NewsImages(
    private val store: NewsImageStore,
    private val fetch: (String) -> ByteArray? = ::httpGetImage,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * Makes sure the server holds a picture for each address (newest stories first): pictures it already has are
     * marked as still in use, new ones are fetched and made, up to [maxNew] and within [budgetMs]. Returns the key of
     * every address that has a picture now; an address that fails is simply left without one (tried again next time).
     * Pictures no feed has mentioned for [KEEP_MS] are then deleted.
     */
    fun prepare(urls: List<String>, maxNew: Int = MAX_NEW_PER_REFRESH, budgetMs: Long = BUDGET_MS): Map<String, String> {
        val wanted = urls.distinct().filter { safeImageUrl(it) != null }.associateWith(::key)
        if (wanted.isEmpty()) { runCatching { store.prune(now() - KEEP_MS) }; return emptyMap() }
        val have = store.existing(wanted.values.toSet())
        store.touch(have)
        val out = wanted.filterValues { it in have }.toMutableMap()
        val started = now()
        var made = 0
        for ((url, k) in wanted) {
            if (k in have) continue
            if (made >= maxNew || now() - started > budgetMs) break
            made++
            val thumb = runCatching { fetch(url)?.let(NewsImageMaker::thumbnail) }.getOrNull() ?: continue
            runCatching { store.put(k, thumb) }.onSuccess { out[url] = k }
        }
        runCatching { store.prune(now() - KEEP_MS) }
        return out
    }

    /** The picture under [key], for a device (`/v1/news/image`). */
    fun get(key: String): ByteArray? = store.get(key)?.bytes

    companion object {
        const val MAX_NEW_PER_REFRESH = 40
        const val BUDGET_MS = 90_000L
        const val KEEP_MS = 3 * 24 * 3_600_000L
        private const val MAX_DOWNLOAD = 5 * 1024 * 1024

        /** 32 hex characters of the SHA-256 of the address (the apps accept nothing else as a key). */
        fun key(url: String): String =
            MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

        /**
         * A picture's address as the feed gave it, only when it is plain https with a host (entities decoded; query
         * kept, as image services need it). Anything else is never fetched.
         */
        fun safeImageUrl(raw: String?): String? = runCatching {
            val s = Rss.unescape(raw?.trim() ?: return null)
            if (s.length > 2_000 || s.any { it.isWhitespace() || it < ' ' }) return null
            val u = URI(s)
            if (!u.scheme.equals("https", ignoreCase = true) || u.host.isNullOrEmpty() || u.rawUserInfo != null) return null
            if (u.port != -1 && u.port != 443) return null
            u.toASCIIString()
        }.getOrNull()

        /**
         * Only public hosts: the server must never be steered by a feed into fetching from itself or its network
         * (loopback, private, link-local, unique-local or multicast addresses are refused).
         */
        fun isPublicHost(host: String): Boolean {
            val addrs = runCatching { InetAddress.getAllByName(host) }.getOrNull() ?: return false
            return addrs.isNotEmpty() && addrs.none { a ->
                a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress || a.isMulticastAddress ||
                    (a is Inet6Address && (a.address[0].toInt() and 0xfe) == 0xfc) ||
                    // 100.64.0.0/10 (carrier-grade NAT, also used inside cloud networks)
                    (a.address.size == 4 && a.address[0].toInt() == 100 && (a.address[1].toInt() and 0xc0) == 64)
            }
        }

        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            // NORMAL never follows an https link to http; each hop's host is checked again below.
            .followRedirects(HttpClient.Redirect.NEVER).build()

        /** GET with at most three redirects, https and public hosts only, image types only, at most 5 MB. */
        fun httpGetImage(url: String): ByteArray? {
            var next = safeImageUrl(url) ?: return null
            repeat(4) {
                val u = URI(next)
                if (!isPublicHost(u.host)) return null
                val resp = client.send(
                    HttpRequest.newBuilder(u).timeout(Duration.ofSeconds(12))
                        // No WebP: the JDK can't read it, and image services send JPEG or PNG when it isn't asked for.
                        .header("Accept", "image/jpeg,image/png,image/gif;q=0.8")
                        .header("User-Agent", "MEKA-OS/1 (personal feed reader)")
                        .GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream(),
                )
                resp.body().use { body ->
                    when (resp.statusCode()) {
                        200 -> {
                            val type = resp.headers().firstValue("Content-Type").orElse("").lowercase()
                            if (!type.startsWith("image/") && !type.startsWith("application/octet-stream")) return null
                            val len = resp.headers().firstValueAsLong("Content-Length").orElse(-1)
                            if (len > MAX_DOWNLOAD) return null
                            val bytes = body.readNBytes(MAX_DOWNLOAD + 1)
                            return if (bytes.size > MAX_DOWNLOAD) null else bytes
                        }
                        301, 302, 303, 307, 308 -> {
                            val loc = resp.headers().firstValue("Location").orElse(null) ?: return null
                            next = safeImageUrl(u.resolve(loc).toString()) ?: return null
                        }
                        else -> return null
                    }
                }
            }
            return null
        }
    }
}

/** A finished picture: a JPEG and its size in pixels. */
class Thumbnail(val bytes: ByteArray, val width: Int, val height: Int)

/** Shrinks a fetched picture into a small JPEG (the JDK's ImageIO; no native code, no paid service). */
object NewsImageMaker {
    const val MAX_WIDTH = 320
    /** Taller pictures are cropped to this height around their middle (cards are wide). */
    const val MAX_HEIGHT = 320
    const val MAX_BYTES = 30 * 1024
    /** Smaller than this is a logo or a tracking pixel, not a story's picture. */
    const val MIN_SIDE = 64
    /** Refused before decoding: a small file can claim a huge picture. */
    const val MAX_PIXELS = 40_000_000L

    init {
        System.setProperty("java.awt.headless", "true")
        ImageIO.setUseCache(false) // read-only root: never spill to disk
    }

    fun thumbnail(source: ByteArray): Thumbnail? {
        val img = decode(source) ?: return null
        var width = minOf(MAX_WIDTH, img.width)
        while (width >= MIN_SIDE * 2) {
            val scaled = scale(img, width)
            for (q in floatArrayOf(0.8f, 0.7f, 0.6f, 0.5f, 0.4f)) {
                val jpeg = jpeg(scaled, q)
                if (jpeg.size <= MAX_BYTES) return Thumbnail(jpeg, scaled.width, scaled.height)
            }
            width = width * 3 / 4
        }
        return null
    }

    private fun decode(bytes: ByteArray): BufferedImage? {
        val input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) ?: return null
        input.use { stream ->
            val readers = ImageIO.getImageReaders(stream)
            if (!readers.hasNext()) return null
            val reader = readers.next()
            try {
                reader.setInput(stream, true, true)
                val w = reader.getWidth(0).toLong()
                val h = reader.getHeight(0).toLong()
                if (w < MIN_SIDE || h < MIN_SIDE || w * h > MAX_PIXELS) return null
                return reader.read(0)
            } catch (e: Exception) {
                return null
            } finally {
                reader.dispose()
            }
        }
    }

    /** [width] wide (never wider than the original), at most [MAX_HEIGHT] tall (cropped around the middle), on white. */
    private fun scale(src: BufferedImage, width: Int): BufferedImage {
        var cur = src
        // Halve first for large shrinks: one bilinear step from 2000 px to 320 px looks grainy.
        while (cur.width / 2 >= width) cur = draw(cur, cur.width / 2, maxOf(1, cur.height / 2))
        val h = maxOf(1, (cur.height.toLong() * width / cur.width).toInt())
        val full = if (cur.width == width && cur.height == h && cur.type == BufferedImage.TYPE_INT_RGB) cur else draw(cur, width, h)
        if (full.height <= MAX_HEIGHT) return full
        return full.getSubimage(0, (full.height - MAX_HEIGHT) / 2, full.width, MAX_HEIGHT).let { draw(it, it.width, it.height) }
    }

    private fun draw(src: BufferedImage, w: Int, h: Int): BufferedImage {
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.color = Color.WHITE // transparent PNGs get a white ground (JPEG has no transparency)
            g.fillRect(0, 0, w, h)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(src, 0, 0, w, h, null)
        } finally {
            g.dispose()
        }
        return out
    }

    private fun jpeg(img: BufferedImage, quality: Float): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        try {
            MemoryCacheImageOutputStream(out).use { stream ->
                writer.output = stream
                val param = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = quality
                }
                writer.write(null, IIOImage(img, null, null), param) // no metadata carried over
            }
        } finally {
            writer.dispose()
        }
        return out.toByteArray()
    }
}

/** Where pictures are kept: shared by every household (public content), deleted once no feed mentions them. */
interface NewsImageStore {
    /** Which of [keys] are held. */
    fun existing(keys: Set<String>): Set<String>
    /** Marks [keys] as still mentioned by a feed. */
    fun touch(keys: Set<String>)
    fun put(key: String, thumb: Thumbnail)
    fun get(key: String): Thumbnail?
    /** Deletes pictures last mentioned before [beforeMs]. */
    fun prune(beforeMs: Long)
}

class InMemoryNewsImageStore(private val now: () -> Long = System::currentTimeMillis) : NewsImageStore {
    private val pictures = java.util.concurrent.ConcurrentHashMap<String, Pair<Thumbnail, Long>>()
    override fun existing(keys: Set<String>) = keys.filter { pictures.containsKey(it) }.toSet()
    override fun touch(keys: Set<String>) { for (k in keys) pictures.computeIfPresent(k) { _, v -> v.first to now() } }
    override fun put(key: String, thumb: Thumbnail) { pictures[key] = thumb to now() }
    override fun get(key: String) = pictures[key]?.first
    override fun prune(beforeMs: Long) { pictures.entries.removeIf { it.value.second < beforeMs } }
    val size: Int get() = pictures.size
}

class PostgresNewsImageStore(private val ds: DataSource) : NewsImageStore {
    override fun existing(keys: Set<String>): Set<String> = if (keys.isEmpty()) emptySet() else ds.connection.use { c ->
        c.prepareStatement("SELECT key FROM news_image WHERE key = ANY(?)").use { st ->
            st.setArray(1, c.createArrayOf("text", keys.toTypedArray()))
            st.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    override fun touch(keys: Set<String>) {
        if (keys.isEmpty()) return
        ds.connection.use { c ->
            c.prepareStatement("UPDATE news_image SET used_at = now() WHERE key = ANY(?)").use { st ->
                st.setArray(1, c.createArrayOf("text", keys.toTypedArray()))
                st.executeUpdate()
            }
        }
    }

    override fun put(key: String, thumb: Thumbnail) = ds.connection.use { c ->
        c.prepareStatement(
            """INSERT INTO news_image(key, data, width, height) VALUES (?,?,?,?)
               ON CONFLICT (key) DO UPDATE SET data = EXCLUDED.data, width = EXCLUDED.width, height = EXCLUDED.height, used_at = now()""",
        ).use {
            it.setString(1, key); it.setBytes(2, thumb.bytes); it.setInt(3, thumb.width); it.setInt(4, thumb.height)
            it.executeUpdate()
        }
        Unit
    }

    override fun get(key: String): Thumbnail? = ds.connection.use { c ->
        c.prepareStatement("SELECT data, width, height FROM news_image WHERE key = ?").use { st ->
            st.setString(1, key)
            st.executeQuery().use { rs -> if (rs.next()) Thumbnail(rs.getBytes(1), rs.getInt(2), rs.getInt(3)) else null }
        }
    }

    override fun prune(beforeMs: Long) = ds.connection.use { c ->
        c.prepareStatement("DELETE FROM news_image WHERE used_at < ?").use {
            it.setTimestamp(1, java.sql.Timestamp(beforeMs)); it.executeUpdate()
        }
        Unit
    }
}
