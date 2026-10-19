package os.meka.backend

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import os.meka.core.domain.DeviceJoinRules
import os.meka.core.domain.EntityTypes
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.wire.DeviceJoinCodec
import os.meka.core.wire.WireCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Clean-ups (2026-10-19): a new phone or Mac waits for Allow on the Fold; the code alone only reconnects or recovers. */
class EnrolApprovalTest {
    private val token = "t".repeat(48)
    private val devices = InMemoryDeviceRegistry()
    private val ops = InMemoryServerOpStore()
    private val foldKey = TestDeviceKey()
    private val fold = DeviceIdentity("home", "android0123456789ab")
    private val foldSecret = devices.enrol(fold.householdId, fold.deviceId, "Fold")
    private val newMac = "mac0123456789abcdef"
    private var asked = 0

    init {
        devices.registerKey(fold, foldKey.publicB64)
    }

    private fun approval(now: () -> Long = System::currentTimeMillis) = EnrolApproval(devices, ops, now, onAsked = { asked++ })

    private fun enrolBody(dev: String, name: String = "MacBook Air") = WireCodec.encodeEnrolRequest(WireCodec.EnrolRequest("home", dev, name))

    /** The ask as the devices would read it after a sync. */
    private fun joins() = ops.after("home", 0, Int.MAX_VALUE).map { it.op }.filter { it.entityType == EntityTypes.DEVICE_JOIN }
        .groupBy { it.entityId }.mapNotNull { (id, list) ->
            val latest = list.groupBy { it.field }.mapValues { (_, l) -> l.maxBy { it.hlc }.value }
            DeviceJoinRules.read(id) { latest[it] }
        }

    @Test
    fun aNewMacWaitsTheFoldAllowsItAndItsSecretComesOnce() = testApplication {
        application { mekaSync(ops, devices, enrolToken = token, enrolApproval = approval()) }
        val r = client.post("/v1/enrol") { header("Authorization", "Enrol $token"); setBody(enrolBody(newMac)) }
        assertEquals(HttpStatusCode.Accepted, r.status)
        val waiting = assertNotNull(DeviceJoinCodec.decodeWaiting(r.bodyAsText()))
        assertTrue(DeviceJoinCodec.isCode(waiting.code))
        assertEquals(1, asked)
        // The Fold's Needs you will read it: who, the code, waiting.
        val join = joins().single()
        assertEquals(listOf("MacBook Air", DeviceJoinRules.MAC, waiting.code, DeviceJoinRules.WAITING), listOf(join.name, join.kind, join.code, join.state))
        // Not a device yet.
        assertTrue(devices.isNewDevice("home", newMac))

        suspend fun status() = client.post(DeviceJoinCodec.STATUS_PATH) {
            header("Authorization", "Enrol $token"); setBody(DeviceJoinCodec.encodeStatusRequest(waiting.requestId))
        }
        assertEquals(DeviceJoinCodec.WAITING, DeviceJoinCodec.decodeStatus(status().bodyAsText()).state)
        // Looking needs the code too.
        assertEquals(HttpStatusCode.Unauthorized, client.post(DeviceJoinCodec.STATUS_PATH) { setBody(DeviceJoinCodec.encodeStatusRequest(waiting.requestId)) }.status)

        val answer = DeviceJoinCodec.encodeAnswer(DeviceJoinCodec.Answer(waiting.requestId, true))
        val a = client.post(DeviceJoinCodec.ANSWER_PATH) { with(foldKey) { signed(foldSecret, DeviceJoinCodec.ANSWER_PATH, answer) } }
        assertEquals(DeviceJoinCodec.ALLOWED, DeviceJoinCodec.decodeAnswered(a.bodyAsText()))
        assertEquals(DeviceJoinRules.ALLOWED, joins().single().state)
        assertEquals("Fold", joins().single().answeredBy)
        // A second answer (the Mac's) finds it gone.
        val again = client.post(DeviceJoinCodec.ANSWER_PATH) { with(foldKey) { signed(foldSecret, DeviceJoinCodec.ANSWER_PATH, answer) } }
        assertEquals(DeviceJoinCodec.GONE, DeviceJoinCodec.decodeAnswered(again.bodyAsText()))

        val enrolled = DeviceJoinCodec.decodeStatus(status().bodyAsText())
        assertEquals(DeviceJoinCodec.ENROLLED, enrolled.state)
        assertEquals(DeviceIdentity("home", newMac), devices.authenticate(enrolled.secret!!))
        assertEquals(DeviceJoinCodec.EXPIRED, DeviceJoinCodec.decodeStatus(status().bodyAsText()).state)
    }

    @Test
    fun notThisOneRefusesItAndAnUnsignedAnswerIsRefused() = testApplication {
        val approval = approval()
        application { mekaSync(ops, devices, enrolToken = token, enrolApproval = approval) }
        val waiting = DeviceJoinCodec.decodeWaiting(
            client.post("/v1/enrol") { header("Authorization", "Enrol $token"); setBody(enrolBody("android00aa11bb22cc33dd", "SM-F966B")) }.bodyAsText(),
        )!!
        assertEquals(DeviceJoinRules.PHONE, joins().single().kind)
        val refuse = DeviceJoinCodec.encodeAnswer(DeviceJoinCodec.Answer(waiting.requestId, false))
        // Bearer alone, without the Fold's signature: refused.
        val unsigned = client.post(DeviceJoinCodec.ANSWER_PATH) { header("Authorization", "Bearer $foldSecret"); setBody(refuse) }
        assertTrue(unsigned.status.value in 401..403)
        assertEquals(DeviceJoinRules.WAITING, joins().single().state)

        val a = client.post(DeviceJoinCodec.ANSWER_PATH) { with(foldKey) { signed(foldSecret, DeviceJoinCodec.ANSWER_PATH, refuse) } }
        assertEquals(DeviceJoinCodec.REFUSED, DeviceJoinCodec.decodeAnswered(a.bodyAsText()))
        assertEquals(DeviceJoinRules.REFUSED, joins().single().state)
        val s = client.post(DeviceJoinCodec.STATUS_PATH) { header("Authorization", "Enrol $token"); setBody(DeviceJoinCodec.encodeStatusRequest(waiting.requestId)) }
        assertEquals(DeviceJoinCodec.REFUSED, DeviceJoinCodec.decodeStatus(s.bodyAsText()).state)
        assertTrue(devices.isNewDevice("home", "android00aa11bb22cc33dd"))
    }

    @Test
    fun theCodeAloneStillReconnectsAKnownDeviceAndEnrolsWhenNoApproverIsInUse() = testApplication {
        application { mekaSync(ops, devices, enrolToken = token, enrolApproval = approval()) }
        // Reconnect: the Fold's own id re-enrols with the code as before.
        assertEquals(HttpStatusCode.OK, client.post("/v1/enrol") { header("Authorization", "Enrol $token"); setBody(enrolBody(fold.deviceId, "Fold")) }.status)
        // The Fold re-enrolled has no key yet, so nobody can allow: a new Mac enrols with the code (both lost).
        assertEquals(HttpStatusCode.OK, client.post("/v1/enrol") { header("Authorization", "Enrol $token"); setBody(enrolBody(newMac)) }.status)
        assertEquals(0, asked)
    }

    @Test
    fun anApproverNotSeenInTwoWeeksDoesntHoldTheDoor() {
        var now = 1_800_000_000_000L
        val approval = approval { now }
        val req = WireCodec.EnrolRequest("home", newMac, "MacBook Air")
        assertTrue(approval.needed(req))
        devices.lastSeen[fold] = now - 15L * 24 * 60 * 60_000
        assertTrue(!approval.needed(req))
        devices.lastSeen[fold] = now - 13L * 24 * 60 * 60_000
        assertTrue(approval.needed(req))
        // A watch id never asks this way (it links from a device), and another household isn't this one.
        assertTrue(!approval.needed(WireCodec.EnrolRequest("home", "watch0123456789abcdef", "Watch")))
        assertTrue(!approval.needed(WireCodec.EnrolRequest("elsewhere", newMac, "Mac")))
    }

    @Test
    fun anAskRunsOutAfterTenMinutesAndAskingAgainReplacesIt() {
        var now = 1_800_000_000_000L
        val approval = approval { now }
        val req = WireCodec.EnrolRequest("home", newMac, "MacBook Air")
        val first = approval.ask(req)!!
        val second = approval.ask(req)!!
        assertEquals(1, approval.waiting())
        assertEquals(DeviceJoinCodec.EXPIRED, DeviceJoinCodec.decodeStatus(approval.status(DeviceJoinCodec.encodeStatusRequest(first.requestId)).body).state)
        now += DeviceJoinCodec.ASK_MS
        assertEquals(DeviceJoinCodec.EXPIRED, DeviceJoinCodec.decodeStatus(approval.status(DeviceJoinCodec.encodeStatusRequest(second.requestId)).body).state)
        val late = approval.answer(fold, DeviceJoinCodec.encodeAnswer(DeviceJoinCodec.Answer(second.requestId, true)))
        assertEquals(DeviceJoinCodec.GONE, DeviceJoinCodec.decodeAnswered(late.body))
        assertTrue(devices.isNewDevice("home", newMac))
    }

    @Test
    fun aWatchCantAnswerAndAtMostTenAsksWait() {
        val approval = approval()
        val w = approval.ask(WireCodec.EnrolRequest("home", newMac, "Mac"))!!
        val watch = DeviceIdentity("home", "watch0123456789abcdef")
        assertEquals(403, approval.answer(watch, DeviceJoinCodec.encodeAnswer(DeviceJoinCodec.Answer(w.requestId, true))).status)
        repeat(9) { i -> assertNotNull(approval.ask(WireCodec.EnrolRequest("home", "mac0123456789abc${"%03d".format(i)}", "Mac"))) }
        assertNull(approval.ask(WireCodec.EnrolRequest("home", "mac0123456789abcfff", "Mac")))
        assertEquals(10, joins().size)
    }
}
