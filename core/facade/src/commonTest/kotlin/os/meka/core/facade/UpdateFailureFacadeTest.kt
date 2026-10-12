package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.ActivityKind
import os.meka.core.domain.UpdateFailure
import os.meka.core.domain.UpdateFailureRules
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.wire.UpdateFailureCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Phone update failures through the facade: Activity says what happened, the server hears why. */
class UpdateFailureFacadeTest {
    private val now = 1_791_622_800_000L // Sat 10 Oct 2026, 10:00 in London
    private val ops = InMemoryServerOpStore()
    private val sent = mutableListOf<UpdateFailureCodec.Report>()

    private fun core(reports: Boolean) = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)).let { t ->
            if (!reports) t else object : SyncTransport by t, UpdateFailureApi {
                override suspend fun reportUpdateFailure(report: UpdateFailureCodec.Report): Boolean { sent += report; return true }
            }
        },
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val failure = UpdateFailure(
        UpdateFailureRules.STAGE_INSTALL, UpdateFailureRules.STATUS_FAILURE_BLOCKED, "INSTALL_FAILED_VERIFICATION_FAILURE",
        fromCode = 425, toCode = 430, atMs = now - 60_000, storage = "over 5 GB", network = "wifi", attempts = 1,
    )

    @Test
    fun aFailureIsInActivityOnceAndReachesTheServer() = runTest {
        val c = core(reports = true)
        assertTrue(c.recordUpdateFailure(failure))
        assertTrue(c.recordUpdateFailure(failure))
        val rows = c.activityView.value.days.flatMap { it.rows }.filter { it.kind == ActivityKind.DEVICE }
        assertEquals(listOf("Build 430 didn't install"), rows.map { it.summary })
        assertTrue("INSTALL_FAILED_VERIFICATION_FAILURE · STATUS_FAILURE_BLOCKED" in rows.single().why, rows.single().why)
        assertEquals(
            UpdateFailureCodec.Report("install", 2, "INSTALL_FAILED_VERIFICATION_FAILURE", 425, 430, now - 60_000, "over 5 GB", "wifi", 1),
            sent.first(),
        )
    }

    @Test
    fun withoutTheRouteItIsStillInActivity() = runTest {
        val c = core(reports = false)
        assertFalse(c.recordUpdateFailure(failure))
        assertEquals(1, c.activityView.value.days.flatMap { it.rows }.count { it.kind == ActivityKind.DEVICE })
    }

    @Test
    fun anInstalledBuildIsInActivityOnceWithItsSize() = runTest {
        val c = core(reports = false)
        c.recordInstalledBuild(471, 18_200_000, previousCode = 470, previousSize = 55_100_000)
        c.recordInstalledBuild(471, 18_200_000, previousCode = 470, previousSize = 55_100_000)
        val rows = c.activityView.value.days.flatMap { it.rows }.filter { it.kind == ActivityKind.DEVICE }
        assertEquals(listOf("Updated to build 471 · 18.2 MB"), rows.map { it.summary })
        assertTrue("From build 470 (55.1 MB) · 36.9 MB smaller" in rows.single().why, rows.single().why)
    }
}
