package os.meka.backend

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import os.meka.core.domain.AboutYouFields
import os.meka.core.sync.FieldValue
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.wire.NotifyDiagnosticsCodec
import os.meka.core.wire.WireCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Notifications never seen, slice 2: the phone sends its trace and a build run reads the newest with the server's last
 * push; Send a test pushes `{"t":"test"}` to the device that asked and answers with what Firebase said.
 */
class NotifyReportsTest {
    private val devices = InMemoryDeviceRegistry()
    private val foldSecret = devices.enrol("hh", "fold")
    private val foldKey = TestDeviceKey().also { devices.registerKey(DeviceIdentity("hh", "fold"), it.publicB64) }
    private val keys = ReleasePublisher.generateKeyPair()
    private val privateKey = ReleasePublisher.privateKey(keys.first)
    private val at = java.time.Instant.parse("2026-10-13T20:41:00Z").toEpochMilli()
    private val report = NotifyDiagnosticsCodec.Report(
        "SM-F000X", at + 60_000, allowed = true, phone = "ALL",
        channels = listOf(NotifyDiagnosticsCodec.Channel("meka_heads_up", "Heads-ups", true), NotifyDiagnosticsCodec.Channel("meka_digest", "Digests", false)),
        trace = listOf(NotifyDiagnosticsCodec.Entry(at, "LOOKED", "nothing due · next look 21:10")),
    )
    private val tokens = InMemoryPushTokenStore()
    private val sentTests = mutableListOf<PushAddress>()
    private var testResult = SendResult.SENT
    private val sender = object : PushSender {
        override fun wake(address: PushAddress) = SendResult.SENT
        override fun test(address: PushAddress): SendResult { sentTests += address; return testResult }
    }
    private val push = Push(tokens, sender, now = { at + 120_000 }, schedule = { _, task -> task() })

    private fun ApplicationTestBuilder.mekaServer(withPush: Boolean = true) = application {
        mekaSync(
            InMemoryServerOpStore(), devices, releases = Releases(InMemoryReleaseStore()), publisher = PublisherKeySource { keys.second },
            push = if (withPush) push else null,
            notifyReports = NotifyReports(InMemoryNotifyReportStore(), if (withPush) push else null),
        )
    }

    private fun ApplicationTestBuilder.publisherClient() = ReleasePublisherClient(privateKey, ReleasePublisherClient.Post { path, headers, body ->
        val r = client.post(path) { headers.forEach { (k, v) -> header(k, v) }; setBody(body) }
        r.status.value to r.bodyAsText()
    })

    private suspend fun ApplicationTestBuilder.testPush(): String {
        val path = NotifyDiagnosticsCodec.TEST_PATH
        val body = NotifyDiagnosticsCodec.encodeEmptyRequest()
        val r = client.post(path) { with(foldKey) { signed(foldSecret, path, body) } }
        assertEquals(HttpStatusCode.OK, r.status)
        return NotifyDiagnosticsCodec.decodeTestAnswer(r.bodyAsText())
    }

    @Test
    fun thePhoneSendsAndThePublisherReadsTheNewestWithTheLastPush() = testApplication {
        mekaServer()
        assertNull(publisherClient().notifications().report)
        val path = NotifyDiagnosticsCodec.REPORT_PATH
        val body = NotifyDiagnosticsCodec.encodeReport(report)
        assertEquals(HttpStatusCode.OK, client.post(path) { with(foldKey) { signed(foldSecret, path, body) } }.status)
        tokens.put(DeviceIdentity("hh", "fold"), "fcm", "tok")
        assertEquals("sent", testPush())

        val read = publisherClient().notifications()
        assertEquals(report, read.report)
        assertEquals("sent", read.lastPushResult)
        assertEquals(true, read.hasAddress)
        val lines = ReleasePublisherCli.notificationLines(read)
        assertEquals(
            "::notice title=Notifications (SM-F000X, sent 2026-10-13 20:42 UTC)::allowed · this phone: ALL · off: Digests · last push 2026-10-13 20:43 UTC · sent",
            lines.first(),
        )
        assertEquals("::notice title=Notification trace::2026-10-13 20:41 UTC · LOOKED · nothing due · next look 21:10", lines[1])
        assertEquals(
            listOf("::notice title=Notifications::the phone hasn't sent its notification trace yet"),
            ReleasePublisherCli.notificationLines(NotifyDiagnosticsCodec.Latest(null)),
        )
    }

    @Test
    fun thePublisherPrintsEachChannelsLastPostWhenThePhoneSendsThem() {
        val withPosts = report.copy(channels = listOf(
            NotifyDiagnosticsCodec.Channel("meka_heads_up", "Heads-ups", true, at - 60_000),
            NotifyDiagnosticsCodec.Channel("meka_work", "Urgent while at work", true, at),
            NotifyDiagnosticsCodec.Channel("meka_digest", "Digests", false),
        ))
        val lines = ReleasePublisherCli.notificationLines(NotifyDiagnosticsCodec.Latest(withPosts))
        assertEquals(
            "::notice title=Notification channels::Urgent while at work 2026-10-13 20:41 UTC · Heads-ups 2026-10-13 20:40 UTC · never: Digests",
            lines[1],
        )
        // A phone from before slice 3 sends no times: no channel line.
        assertTrue(ReleasePublisherCli.notificationLines(NotifyDiagnosticsCodec.Latest(report)).none { "Notification channels" in it })
    }

    @Test
    fun thePublisherSaysWhetherBothAppsHoldAboutYouWithoutItsValues() = testApplication {
        var profile: Map<String, FieldValue>? = null
        application {
            mekaSync(
                InMemoryServerOpStore(), devices, releases = Releases(InMemoryReleaseStore()), publisher = PublisherKeySource { keys.second },
                notifyReports = NotifyReports(InMemoryNotifyReportStore(), null, profile = { hh -> if (hh == "hh") profile else null }),
            )
        }
        assertEquals("::notice title=About you::no profile yet (release 1's copy hasn't run)", ReleasePublisherCli.aboutYouLine(publisherClient().notifications()))
        profile = mapOf(AboutYouFields.HOME to FieldValue.Text("Hilltown"), AboutYouFields.HELD_FOLD to FieldValue.Int64(at))
        val one = publisherClient().notifications()
        assertEquals(true, one.aboutFilled)
        assertEquals("::notice title=About you::on the Fold since 2026-10-13 20:41 UTC · NOT on the Mac yet · release 2 waits", ReleasePublisherCli.aboutYouLine(one))
        profile = profile!! + (AboutYouFields.HELD_MAC to FieldValue.Int64(at + 60_000))
        val line = ReleasePublisherCli.aboutYouLine(publisherClient().notifications())
        assertEquals("::notice title=About you::on the Fold since 2026-10-13 20:41 UTC · on the Mac since 2026-10-13 20:42 UTC · release 2 can start", line)
        assertTrue("Hilltown" !in line)
    }

    @Test
    fun aTestPushGoesOnlyToTheAskerAndSaysWhatHappened() = testApplication {
        mekaServer()
        assertEquals("no_address", testPush())
        tokens.put(DeviceIdentity("hh", "fold"), "fcm", "tok")
        tokens.put(DeviceIdentity("hh", "mac"), "fcm", "other")
        assertEquals("sent", testPush())
        assertEquals(listOf(PushAddress("fold", "fcm", "tok")), sentTests)
        testResult = SendResult.GONE
        assertEquals("gone", testPush())
        // A stale address is forgotten, so the next test says the phone has none.
        assertTrue(tokens.household("hh").none { it.deviceId == "fold" })
        assertEquals("no_address", testPush())
    }

    @Test
    fun withoutPushTheTestSaysOffAndThePublisherCantSend() = testApplication {
        mekaServer(withPush = false)
        assertEquals("off", testPush())
        val path = NotifyDiagnosticsCodec.REPORT_PATH
        val body = NotifyDiagnosticsCodec.encodeReport(report)
        val asPublisher = client.post(path) { ReleasePublisher.headers(privateKey, path, body).forEach { (k, v) -> header(k, v) }; setBody(body) }
        assertEquals(HttpStatusCode.Forbidden, asPublisher.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post(path) { header("Authorization", "Bearer $foldSecret"); setBody(body) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post(path) { with(foldKey) { signed(foldSecret, path, "{\"w\":${WireCodec.VERSION}}") } }.status)
    }

    @Test
    fun healthCarriesTheLastPush() {
        val p = Push(tokens, sender, now = { at }, schedule = { _, task -> task() })
        val fold = DeviceIdentity("hh", "fold")
        assertNull(p.lastResult(fold))
        tokens.put(fold, "fcm", "tok")
        p.serverChanged("hh")
        assertEquals(at to SendResult.SENT, p.lastResult(fold))
        assertEquals("sent", NotifyReports.word(SendResult.SENT))
        assertEquals("off", NotifyReports.word(SendResult.OFF))
    }
}
