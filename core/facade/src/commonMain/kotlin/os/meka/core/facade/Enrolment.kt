package os.meka.core.facade

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import os.meka.core.sync.TransportException
import os.meka.core.wire.DeviceJoinCodec
import os.meka.core.wire.WireCodec
import kotlin.coroutines.cancellation.CancellationException

sealed class EnrolmentResult {
    data class Enrolled(val deviceSecret: String) : EnrolmentResult()
    /** Wrong or expired enrolment code. */
    object Rejected : EnrolmentResult()
    data class Failed(val reason: String) : EnrolmentResult()
    /**
     * Clean-ups (2026-10-19): the household's phone or Mac has to allow this new device first. Show [code] and call
     * [Enrolment.awaitApproval] with [requestId]; the ask runs out at [expiresAtMs].
     */
    data class Waiting(val requestId: String, val code: String, val expiresAtMs: Long) : EnrolmentResult()
}

/**
 * One-time device enrolment (ADR-005 M0). The enrolment code is typed once on each device; the returned device secret
 * goes straight into Keystore/Keychain storage and the code is not kept.
 */
object Enrolment {
    suspend fun enrol(
        client: HttpClient,
        baseUrl: String,
        enrolCode: String,
        householdId: String,
        deviceId: String,
        deviceName: String,
    ): EnrolmentResult {
        if (!baseUrl.startsWith("https://")) return EnrolmentResult.Failed("The server address must start with https://")
        return try {
            val resp = client.post(baseUrl.trimEnd('/') + "/v1/enrol") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Enrol ${enrolCode.trim()}")
                setBody(WireCodec.encodeEnrolRequest(WireCodec.EnrolRequest(householdId, deviceId, deviceName)))
            }
            when {
                resp.status.value == 401 -> EnrolmentResult.Rejected
                resp.status.value == 403 -> EnrolmentResult.Failed(refusal(resp.bodyAsText()))
                resp.status.value == 429 -> EnrolmentResult.Failed("Too many devices are asking to join right now. Try again in a few minutes.")
                !resp.status.isSuccess() -> EnrolmentResult.Failed("Server returned ${resp.status.value}")
                resp.status.value == 202 -> DeviceJoinCodec.decodeWaiting(resp.bodyAsText())
                    ?.let { EnrolmentResult.Waiting(it.requestId, it.code, it.expiresAtMs) }
                    ?: EnrolmentResult.Failed("The server's answer couldn't be read")
                else -> EnrolmentResult.Enrolled(WireCodec.decodeEnrolResponse(resp.bodyAsText()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            EnrolmentResult.Failed("Couldn't reach the server")
        }
    }

    /**
     * One look at a [EnrolmentResult.Waiting] ask (`POST /v1/enrol/status`, with the same enrolment code): Enrolled once
     * the other device allowed it (the secret, handed over once), Waiting meanwhile, Failed with the words when it was
     * refused or ran out. [Enrolment.awaitApproval] repeats it.
     */
    suspend fun approval(client: HttpClient, baseUrl: String, enrolCode: String, waiting: EnrolmentResult.Waiting): EnrolmentResult = try {
        val resp = client.post(baseUrl.trimEnd('/') + DeviceJoinCodec.STATUS_PATH) {
            contentType(ContentType.Application.Json)
            header("Authorization", "Enrol ${enrolCode.trim()}")
            setBody(DeviceJoinCodec.encodeStatusRequest(waiting.requestId))
        }
        when {
            resp.status.value == 401 -> EnrolmentResult.Rejected
            !resp.status.isSuccess() -> EnrolmentResult.Failed("Server returned ${resp.status.value}")
            else -> {
                val s = DeviceJoinCodec.decodeStatus(resp.bodyAsText())
                when (s.state) {
                    DeviceJoinCodec.WAITING -> waiting
                    DeviceJoinCodec.ENROLLED -> EnrolmentResult.Enrolled(s.secret!!)
                    DeviceJoinCodec.REFUSED -> EnrolmentResult.Failed(os.meka.core.domain.DeviceJoinRules.REFUSED_LINE)
                    else -> EnrolmentResult.Failed(os.meka.core.domain.DeviceJoinRules.EXPIRED_LINE)
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        waiting // a dropped connection while waiting: look again next time
    }

    /**
     * Waits for the other device's answer, looking every [everyMs] until it comes or the ask runs out (then Failed with
     * the words). [nowMs] is the device's clock.
     */
    suspend fun awaitApproval(
        client: HttpClient, baseUrl: String, enrolCode: String, waiting: EnrolmentResult.Waiting, nowMs: () -> Long,
        everyMs: Long = 3_000,
    ): EnrolmentResult {
        while (true) {
            val r = approval(client, baseUrl, enrolCode, waiting)
            if (r !is EnrolmentResult.Waiting) return r
            // A little past the ask's end, so the server's own "expired" is what ends it when the clocks differ.
            if (nowMs() > waiting.expiresAtMs + 30_000) return EnrolmentResult.Failed(os.meka.core.domain.DeviceJoinRules.EXPIRED_LINE)
            kotlinx.coroutines.delay(everyMs)
        }
    }

    /** The server's refusal codes (ADR-005 amendment 2026-10-07) in words. */
    fun refusal(code: String): String = when (code.trim()) {
        "revoked" -> "This device was revoked, so the enrolment code can't reconnect it. Undo the revocation on the server first."
        "household" -> "The enrolment code only adds devices to your existing household."
        else -> "The server refused this device"
    }

    fun transport(client: HttpClient, baseUrl: String, deviceSecret: String, deviceKey: DeviceKey? = null): HttpSyncTransport =
        HttpSyncTransport(client, baseUrl, deviceKey) { deviceSecret }
}
