package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.ActivityKind
import os.meka.core.domain.WatchLinkRules
import os.meka.core.domain.WatchSyncRules
import os.meka.core.domain.WatchUnlinkRules
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.sync.TransportException
import os.meka.core.wire.DeviceLinkCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Settings → Watch through the facade (Galaxy Watch, slice 1): type the watch's code, see it linked, unlink it. */
class WatchLinkFacadeTest {
    private var now = 1_791_622_800_000L // Sat 10 Oct 2026, 10:00 in London
    private val watchId = "watch0123456789abcdef"

    private inner class Server(service: SyncService) : SyncTransport, DeviceLinkApi {
        val sync = os.meka.core.testing.FaultyTransport(service)
        val watches = mutableListOf<DeviceLinkCodec.Watch>()
        val codes = mutableListOf<String>()
        var down = false
        var refuse: String? = null
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        private fun check() {
            if (down) throw TransportException("offline")
            refuse?.let { throw LinkRefusedException(it) }
        }
        override suspend fun linkWatch(code: String): DeviceLinkCodec.Linked {
            check()
            codes += code
            watches.add(0, DeviceLinkCodec.Watch(watchId, "Galaxy Watch", now))
            return DeviceLinkCodec.Linked(watchId, "Galaxy Watch")
        }
        override suspend fun linkedWatches(): List<DeviceLinkCodec.Watch> { check(); return watches.toList() }
        override suspend fun unlinkWatch(id: String) {
            check()
            if (watches.none { it.id == id }) throw LinkRefusedException(DeviceLinkCodec.ERR_UNKNOWN)
            watches.removeAll { it.id == id }
        }
        val answers = mutableListOf<Pair<String, Boolean>>()
        override suspend fun answerDeviceJoin(requestId: String, allow: Boolean): String {
            check()
            answers += requestId to allow
            return if (allow) os.meka.core.wire.DeviceJoinCodec.ALLOWED else os.meka.core.wire.DeviceJoinCodec.REFUSED
        }
    }

    private val service = SyncService(InMemoryServerOpStore())
    private val server = Server(service)

    private fun core(transport: SyncTransport? = server, deviceId: String = "android", seed: Int = 1) = MekaCore(
        householdId = "hh", deviceId = deviceId, store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(seed), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun typeTheCodeSeeItLinkedAndUnlinkIt() = runTest {
        val c = core()
        assertNull(c.watchLinkView.value)
        assertEquals("No watch linked yet", c.refreshWatches().summary)

        // A code that isn't 8 digits never reaches the server.
        assertFalse(c.linkWatch("1234"))
        assertEquals(WatchLinkRules.NOT_A_CODE, c.watchLinkView.value!!.problem)
        assertTrue(server.codes.isEmpty())

        assertTrue(c.linkWatch("1234 5678"))
        assertEquals(listOf("12345678"), server.codes)
        val linked = c.watchLinkView.value!!
        assertNull(linked.problem)
        assertEquals("Galaxy Watch is linked · it shows Up next and takes Done", linked.summary)
        assertEquals("Linked today", linked.rows.single().line)
        val activity = c.activityView.value.days.flatMap { it.rows }.filter { it.kind == ActivityKind.DEVICE }.map { it.summary }
        assertEquals(listOf("Linked Galaxy Watch"), activity)

        assertTrue(c.unlinkWatch(watchId))
        assertEquals("No watch linked yet", c.watchLinkView.value!!.summary)
        assertTrue(server.watches.isEmpty())
        assertEquals(
            setOf("Linked Galaxy Watch", "Unlinked Galaxy Watch"),
            c.activityView.value.days.flatMap { it.rows }.filter { it.kind == ActivityKind.DEVICE }.map { it.summary }.toSet(),
        )
        // Unlinking one the server no longer knows is fine (already gone) and logs nothing more.
        assertTrue(c.unlinkWatch(watchId))
        assertEquals(2, c.activityView.value.days.flatMap { it.rows }.count { it.kind == ActivityKind.DEVICE })
    }

    @Test
    fun refusalsAndProblemsReadInWordsAndTheLastRowsStay() = runTest {
        val c = core()
        c.linkWatch("12345678")
        server.refuse = DeviceLinkCodec.ERR_CODE
        assertFalse(c.linkWatch("87654321"))
        assertEquals(WatchLinkRules.WRONG_CODE, c.watchLinkView.value!!.problem)
        assertEquals(1, c.watchLinkView.value!!.rows.size)
        server.refuse = DeviceLinkCodec.ERR_WAIT
        assertFalse(c.linkWatch("87654321"))
        assertEquals(WatchLinkRules.TOO_MANY, c.watchLinkView.value!!.problem)
        server.refuse = null
        server.down = true
        assertEquals(WatchLinkRules.OFFLINE, c.refreshWatches().problem)
        assertEquals(1, c.watchLinkView.value!!.rows.size)
        server.down = false
        assertNull(c.refreshWatches().problem)

        // A device that isn't connected says so.
        val offline = core(transport = null)
        assertEquals(WatchLinkRules.NOT_CONNECTED, offline.refreshWatches().problem)
        assertFalse(offline.linkWatch("12345678"))
    }

    /** Galaxy Watch completeness slice 1: changes made offline are counted on the watch, then sent before it unlinks itself. */
    @Test
    fun theWatchCountsChangesWaitingAndUnlinksItselfAfterSendingThem() = runTest {
        val fold = core()
        assertTrue(fold.linkWatch("12345678"))
        val watch = core(deviceId = watchId, seed = 2) // its own ids (the same seed would repeat the Fold's op ids)
        assertTrue(watch.syncNow())
        assertNull(watch.watchSyncLine())
        assertNull(watch.watchTile().syncMark)
        assertNull(watch.watchUnlinkAsk().blocked)

        // No signal on the watch: two captures wait, and the screen and the tile say so (no count: ops aren't taps).
        server.sync.online = false
        watch.addTask("Buy milk")
        watch.addTask("Call the garage")
        assertFalse(watch.syncNow())
        assertTrue(watch.watchPending() > 0)
        assertEquals(WatchSyncRules.OFFLINE_WAITING, watch.watchSyncLine())
        assertEquals(WatchSyncRules.TILE_MARK, watch.watchTile().syncMark)
        val ask = watch.watchUnlinkAsk()
        assertEquals(WatchUnlinkRules.NEEDS_CONNECTION, ask.blocked)
        assertEquals(WatchUnlinkRules.LOSS, ask.loss)
        server.down = true
        assertFalse(watch.unlinkThisWatch())
        assertEquals(listOf(watchId), server.watches.map { it.id })

        // Back online: Unlink sends what waits first, then the server lets the watch go.
        server.sync.online = true
        server.down = false
        assertTrue(watch.unlinkThisWatch())
        assertTrue(server.watches.isEmpty())
        assertEquals(0, watch.watchPending())
        assertTrue(fold.syncNow())
        assertEquals(setOf("Buy milk", "Call the garage"), (listOfNotNull(fold.today.value.upNext) + fold.today.value.yourDay).map { it.title }.toSet())
        // Already gone on the server: still fine.
        assertTrue(watch.unlinkThisWatch())
    }

    /** Galaxy Watch completeness slice 2: the watch keeps the quiet hours set on the Fold and rests its sync at night. */
    @Test
    fun theWatchKeepsTheFoldsQuietHoursAndRestsItsSyncAtNight() = runTest {
        val fold = core()
        assertTrue(fold.linkWatch("12345678"))
        fold.setQuietHours(true, 22 * 60, 7 * 60)
        assertTrue(fold.syncNow())
        val watch = core(deviceId = watchId, seed = 2)
        assertTrue(watch.syncNow())
        watch.tick()

        // 10:00: not quiet, the usual quarter-hourly look and every background sync goes.
        assertNull(watch.watchQuietLine())
        assertNull(watch.watchTile().quietLine)
        assertEquals(os.meka.core.domain.WatchTileRules.CALM_REFRESH_MS, watch.watchRefreshAfterMs())
        assertTrue(watch.watchSyncDue(now - 15 * 60_000L))

        // 23:00 in London: quiet until 07:00, an hourly look and an hourly sync.
        now += 13 * 3_600_000L
        watch.tick()
        assertEquals("Quiet hours · until 07:00", watch.watchQuietLine())
        assertEquals("Quiet hours · until 07:00", watch.watchTile().quietLine)
        assertEquals(os.meka.core.domain.WatchQuietRules.QUIET_REFRESH_MS, watch.watchRefreshAfterMs())
        assertFalse(watch.watchSyncDue(now - 15 * 60_000L))
        assertTrue(watch.watchSyncDue(now - 60 * 60_000L))

        // A capture made on the watch with no signal still goes at the next run.
        server.sync.online = false
        watch.addTask("Buy milk")
        assertFalse(watch.syncNow())
        assertTrue(watch.watchSyncDue(now - 15 * 60_000L))
    }
}
