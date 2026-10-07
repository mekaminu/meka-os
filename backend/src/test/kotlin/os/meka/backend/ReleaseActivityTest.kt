package os.meka.backend

import os.meka.core.domain.ActivityKind
import os.meka.core.domain.ActivityLog
import os.meka.core.domain.ActivityRules
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.MekaSchema
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Replica
import os.meka.core.wire.WireCodec
import java.security.MessageDigest
import java.util.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The GitHub build's publishes listed in Activity (build plan: hands-free phone updates). */
class ReleaseActivityTest {
    private val ops = InMemoryServerOpStore()
    private var nowMs = 1_791_450_000_000L // 2026-10-08 09:00 UTC
    private val woken = mutableListOf<String>()
    private val activity = ReleaseActivity(ops, { nowMs }, onWritten = { woken += it })
    private val releases = Releases(InMemoryReleaseStore(), onPublished = { who, r -> activity.record(who, r) })
    private val github = DeviceIdentity("hh", ReleasePublisher.ID)
    private val mac = DeviceIdentity("hh", "mac")

    private fun publish(who: DeviceIdentity, code: Long, size: Int = 24_300, seed: Int = code.toInt()): Releases.Upload {
        val bytes = Random(seed).nextBytes(size)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val r = WireCodec.AppRelease("android", code, "0.1.$code", sha, bytes.size.toLong(), WireCodec.releaseChunkCount(bytes.size.toLong()))
        var last: Releases.Upload? = null
        for (i in 0 until r.chunkCount) {
            val from = i * WireCodec.RELEASE_CHUNK_BYTES
            val part = bytes.copyOfRange(from, from + WireCodec.releaseChunkSize(r.sizeBytes, i))
            last = releases.upload(who, WireCodec.ReleaseChunk(r, i, Base64.getEncoder().encodeToString(part)))
        }
        return last!!
    }

    /** The Fold, after pulling everything the server holds. */
    private fun fold(): ActivityLog {
        var n = 0
        val replica = Replica("hh", "fold", HlcClock("fold") { nowMs }, InMemoryReplicaStore(), MekaSchema) { "f${n++}" }
        replica.applyRemoteBatch(ops.after("hh", 0, 1000).map { it.op })
        return ActivityLog(replica, { "a${n++}" }, { nowMs })
    }

    @Test
    fun aGitHubBuildIsListedOnceInActivityAndWakesTheDevices() {
        assertEquals(Releases.Upload.Ack(1, true), publish(github, 412))
        val row = fold().view().days.single().rows.single()
        assertEquals(ActivityKind.PUBLISHED, row.kind)
        assertEquals("09:00", row.time)
        assertEquals("GitHub build published build 412", row.summary)
        assertEquals("MEKA 0.1.412 · 24 KB · install it from Today on the Fold", row.detail)
        assertEquals("Why: Hands-free phone updates · after a green CI run on main", row.why)
        assertFalse(row.canUndo)
        assertEquals(listOf("hh"), woken)
        val written = ops.after("hh", 0, 1000).map { it.op }
        assertTrue(written.all { it.entityType == EntityTypes.AGENT_ACTION && it.entityId == ActivityRules.releaseId("android", 412) && it.deviceId == "server" })

        // Publishing the same build again, or recording it again, writes nothing more.
        nowMs += 60_000
        publish(github, 412)
        assertFalse(activity.record(github, WireCodec.AppRelease("android", 412, "0.1.412", "x", 1, 1)))
        assertEquals(written.size, ops.after("hh", 0, 1000).size)
        assertEquals(listOf("hh"), woken)

        // The next build is a second entry.
        publish(github, 413)
        assertEquals(2, fold().view().days.single().rows.size)
        assertEquals("This week: 2 phone builds", fold().view().weekLine)
    }

    @Test
    fun aBuildTheMacPublishedOrOneThatIsntCompleteIsNotListed() {
        publish(mac, 500)
        assertTrue(ops.after("hh", 0, 1000).isEmpty())
        // Only the first of two chunks arrived: nothing yet.
        val bytes = Random(3).nextBytes(WireCodec.RELEASE_CHUNK_BYTES + 10)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val r = WireCodec.AppRelease("android", 501, "0.1.501", sha, bytes.size.toLong(), 2)
        val first = Base64.getEncoder().encodeToString(bytes.copyOfRange(0, WireCodec.RELEASE_CHUNK_BYTES))
        assertEquals(Releases.Upload.Ack(1, false), releases.upload(github, WireCodec.ReleaseChunk(r, 0, first)))
        assertTrue(ops.after("hh", 0, 1000).isEmpty())
        assertTrue(woken.isEmpty())
    }
}
