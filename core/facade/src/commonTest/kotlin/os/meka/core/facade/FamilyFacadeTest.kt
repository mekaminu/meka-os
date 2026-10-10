package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.ActivityKind
import os.meka.core.domain.FamilyRules
import os.meka.core.domain.FamilyState
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.sync.TransportException
import os.meka.core.wire.FamilyCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Settings → Family through the facade (family sharing, slice 4): make a link, see her join, turn it off. */
class FamilyFacadeTest {
    private var now = 1_791_622_800_000L // Sat 10 Oct 2026, 10:00 in London

    private inner class Server(service: SyncService) : SyncTransport, FamilyApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        val invites = mutableListOf<FamilyCodec.Invite>()
        val names = mutableListOf<String>()
        var down = false
        var noKey = false
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        private fun check() {
            if (down) throw TransportException("offline")
            if (noKey) throw FamilyUnavailableException(FamilyUnavailableException.NO_KEY)
        }
        override suspend fun familyInvites(): List<FamilyCodec.Invite> { check(); return invites.toList() }
        override suspend fun createFamilyInvite(name: String): Pair<FamilyCodec.Made, String> {
            check()
            names += name
            val id = "fam" + invites.size.toString().padStart(20, '0')
            invites.add(0, FamilyCodec.Invite(id, name.lowercase(), "waiting", now))
            val path = "/family#" + "ab".repeat(32)
            return FamilyCodec.Made(id, name.lowercase(), path) to "https://meka.example$path"
        }
        override suspend fun revokeFamilyInvite(id: String): Boolean {
            check()
            val i = invites.indexOfFirst { it.id == id }
            if (i < 0) return false
            invites[i] = invites[i].copy(state = "revoked")
            return true
        }
    }

    private val service = SyncService(InMemoryServerOpStore())
    private val server = Server(service)

    private fun core(device: String = "android", transport: SyncTransport? = server) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun makeALinkSeeHerJoinAndTurnItOff() = runTest {
        val c = core()
        assertNull(c.familyView.value)
        val first = c.refreshFamily()
        assertTrue(first.canInvite)
        assertTrue(first.rows.isEmpty())

        val link = assertNotNull(c.inviteFamily("  Jeanette "))
        assertEquals(listOf("Jeanette"), server.names)
        assertEquals("Jeanette", link.name)
        assertTrue(link.url.startsWith("https://meka.example/family#"))
        assertTrue(link.shareText.endsWith(link.url))
        val waiting = c.familyView.value!!
        assertEquals("Waiting for Jeanette to open the link", waiting.summary)
        assertFalse(waiting.canInvite)
        assertEquals("Waiting · link made today", waiting.rows.single().line)

        // Her phone opens it an hour later.
        now += 3_600_000L
        server.invites[0] = server.invites[0].copy(state = "joined", claimedAtMs = now, lastSeenAtMs = now)
        val joined = c.refreshFamily()
        assertEquals("Jeanette can see and add to the shopping list and dinners", joined.summary)
        assertEquals("Joined today · seen today", joined.rows.single().line)
        c.refreshFamily()
        val family = c.activityView.value.days.flatMap { it.rows }.filter { it.kind == ActivityKind.FAMILY }.map { it.summary }
        assertEquals(listOf("Jeanette joined the shopping list", "Made a shopping list link for Jeanette"), family)

        now += 60_000L
        assertTrue(c.turnOffFamily(link.id))
        val off = c.familyView.value!!
        assertEquals(FamilyState.OFF, off.rows.single().state)
        assertTrue(off.canInvite)
        assertEquals("Turned off Jeanette's link", c.activityView.value.days.first().rows.first().summary)
        // A link the server doesn't know: false, nothing logged.
        assertFalse(c.turnOffFamily("fam" + "9".repeat(20)))
    }

    @Test
    fun theJoinIsLoggedOnceAcrossBothDevices() = runTest {
        server.invites += FamilyCodec.Invite("fam" + "1".repeat(20), "jeanette", "joined", now - 7_200_000L, now - 3_600_000L, now)
        val fold = core("android")
        val mac = core("mac")
        fold.refreshFamily()
        fold.syncNow()
        mac.syncNow()
        mac.refreshFamily()
        mac.syncNow()
        fold.syncNow()
        for (c in listOf(fold, mac)) {
            assertEquals(1, c.activityView.value.days.flatMap { it.rows }.count { it.kind == ActivityKind.FAMILY })
        }
    }

    @Test
    fun whenTheServerCantAnswerTheLastRowsStayWithTheReason() = runTest {
        val c = core()
        c.inviteFamily("Jeanette")
        server.down = true
        val v = c.refreshFamily()
        assertEquals(FamilyRules.OFFLINE, v.problem)
        assertEquals(1, v.rows.size)
        server.down = false
        server.noKey = true
        assertNull(c.inviteFamily("Ada"))
        assertEquals(FamilyRules.NO_KEY, c.familyView.value!!.problem)
        server.noKey = false
        assertNull(c.inviteFamily("Ada2"))
        assertTrue(c.familyView.value!!.problem!!.startsWith("Use letters"))
        assertNull(c.refreshFamily().problem)
        // Not connected at all.
        assertEquals(FamilyRules.NOT_CONNECTED, core("mac", transport = null).refreshFamily().problem)
    }
}
