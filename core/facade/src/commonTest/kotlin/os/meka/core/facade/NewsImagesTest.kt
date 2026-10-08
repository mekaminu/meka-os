package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.sync.TransportException
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** News pictures through the facade (news, images slice): server keys only, cached, misses not re-asked at once. */
class NewsImagesTest {
    private val key = "0123456789abcdef0123456789abcdef"
    private val other = "fedcba9876543210fedcba9876543210"
    private var now = 1_790_000_000_000L

    private class Server(service: SyncService) : SyncTransport, NewsImagesApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        val pictures = HashMap<String, ByteArray>()
        val asked = mutableListOf<String>()
        var down = false
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun newsImage(key: String): ByteArray? {
            asked += key
            if (down) throw TransportException("offline")
            return pictures[key]
        }
    }

    private val server = Server(SyncService(InMemoryServerOpStore()))

    private fun core(transport: SyncTransport? = server) = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun aPictureIsFetchedOnceThenKept() = runTest {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        server.pictures[key] = jpeg
        val c = core()
        assertContentEquals(jpeg, c.newsImage(key))
        assertContentEquals(jpeg, c.newsImage(key))
        assertEquals(listOf(key), server.asked)
        assertEquals(Base64.encode(jpeg), c.newsImageBase64(key))
    }

    @Test
    fun onlyServerKeysAreEverAskedFor() = runTest {
        val c = core()
        assertNull(c.newsImage("https://evil.example/a.jpg"))
        assertNull(c.newsImage(key.uppercase()))
        assertNull(c.newsImage("../$key"))
        assertEquals(emptyList(), server.asked)
    }

    @Test
    fun aMissIsNotAskedAgainForTenMinutes() = runTest {
        val c = core()
        assertNull(c.newsImage(other))
        assertNull(c.newsImage(other))
        assertEquals(1, server.asked.size)
        server.pictures[other] = byteArrayOf(1, 2, 3)
        now += NewsImageCache.MISS_RETRY_MS
        assertContentEquals(byteArrayOf(1, 2, 3), c.newsImage(other))
        assertEquals(2, server.asked.size)
    }

    @Test
    fun offlineOrNotConnectedShowsTheTile() = runTest {
        server.down = true
        assertNull(core().newsImage(key))
        assertNull(core(transport = null).newsImage(key))
    }

    @Test
    fun somethingFarBiggerThanAPictureIsRefused() = runTest {
        server.pictures[key] = ByteArray(NewsImageCache.MAX_BYTES + 1)
        assertNull(core().newsImage(key))
    }

    @Test
    fun theCacheKeepsTheMostRecentlyUsed() = runTest {
        val cache = NewsImageCache()
        for (i in 0 until NewsImageCache.MAX_ENTRIES) cache.put(i.toString().padStart(32, '0'), byteArrayOf(i.toByte()))
        cache.get("0".padStart(32, '0')) // used again: kept
        cache.put("f".repeat(32), byteArrayOf(9))
        assertEquals(NewsImageCache.MAX_ENTRIES, cache.size())
        assertContentEquals(byteArrayOf(0), cache.get("0".padStart(32, '0')))
        assertNull(cache.get("1".padStart(32, '0')))
    }
}
