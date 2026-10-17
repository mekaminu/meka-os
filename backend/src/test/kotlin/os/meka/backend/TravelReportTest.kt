package os.meka.backend

import os.meka.backend.integrations.InMemoryIntegrationStore
import os.meka.backend.integrations.Integrations
import os.meka.backend.integrations.TokenCipher
import os.meka.backend.integrations.TravelProvider
import os.meka.core.domain.TravelRules
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.wire.NotifyDiagnosticsCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Google key (2026-10-17): the deploy report's travel and voice lines, counts only. */
class TravelReportTest {
    private var now = 1_792_224_000_000L // Sat 17 Oct 2026 08:00 UTC

    private val noCipher = object : TokenCipher {
        override fun encrypt(plain: ByteArray, context: Map<String, String>) = plain
        override fun decrypt(cipher: ByteArray, context: Map<String, String>) = cipher
    }

    private class FakeRoutes(var key: Boolean, var fail: Boolean = false) : TravelProvider {
        override val id = TravelRules.PROVIDER
        override val label = TravelRules.LABEL
        val asked = mutableListOf<String>()
        override fun configured() = key
        override fun driveMinutes(fromLat: Double, fromLon: Double, to: String, departAtMs: Long): Int {
            asked += to
            if (fail) error("routes 403")
            return 31
        }
    }

    private fun integrations(routes: FakeRoutes) = Integrations(
        InMemoryIntegrationStore(knownHouseholds = listOf("home")), InMemoryServerOpStore(), emptyMap(), { null }, noCipher,
        "https://meka.example", { now }, travel = mapOf(routes.id to routes),
    )

    @Test
    fun theProbeDrivesBetweenPublicLandmarksAndReportsTheFeed() {
        val routes = FakeRoutes(key = true)
        val i = integrations(routes)
        i.syncAll()
        val line = i.travelReport("home")
        assertTrue(line.startsWith("probe OK · a public drive of 31 min · feed ok · last ok 2026-10-17 08:00 UTC · 0 drives kept"), line)
        assertEquals(listOf(Integrations.PROBE_TO), routes.asked)
        routes.fail = true
        assertTrue(i.travelReport("home").startsWith("probe failed (IllegalStateException: routes 403)"))
    }

    @Test
    fun withoutAKeyNothingIsSentAndTheLineSaysSo() {
        val routes = FakeRoutes(key = false)
        val i = integrations(routes)
        assertEquals("off · the Google secret holds no key", i.travelReport("home"))
        assertTrue(routes.asked.isEmpty())
        val none = Integrations(InMemoryIntegrationStore(knownHouseholds = listOf("home")), InMemoryServerOpStore(), emptyMap(), { null }, noCipher, "https://meka.example", { now })
        assertEquals("off · server has no Google key secret set", none.travelReport("home"))
    }

    @Test
    fun thePublishJobPrintsANoticeWhenWorkingAndAWarningOtherwise() {
        val l = NotifyDiagnosticsCodec.decodeLatest(NotifyDiagnosticsCodec.encodeLatest(NotifyDiagnosticsCodec.Latest(
            null, travel = "probe OK · a public drive of 31 min · feed ok", voice = "Google voice on · 3 Chirp 3 HD voices (2 British) · probe clip 1 KB",
        )))
        assertEquals(
            listOf(
                "::notice title=Travel times::probe OK · a public drive of 31 min · feed ok",
                "::notice title=Navilife's voice::Google voice on · 3 Chirp 3 HD voices (2 British) · probe clip 1 KB",
            ),
            ReleasePublisherCli.googleLines(l),
        )
        val bad = ReleasePublisherCli.googleLines(NotifyDiagnosticsCodec.Latest(null, travel = "off · the Google secret holds no key"))
        assertEquals("::warning title=Travel times::off · the Google secret holds no key", bad[0])
        assertEquals("::notice title=Navilife's voice::the server doesn't report it yet", bad[1])
    }
}
