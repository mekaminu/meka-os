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
import os.meka.core.wire.WireCodec
import kotlin.coroutines.cancellation.CancellationException

sealed class EnrolmentResult {
    data class Enrolled(val deviceSecret: String) : EnrolmentResult()
    /** Wrong or expired enrolment code. */
    object Rejected : EnrolmentResult()
    data class Failed(val reason: String) : EnrolmentResult()
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
                !resp.status.isSuccess() -> EnrolmentResult.Failed("Server returned ${resp.status.value}")
                else -> EnrolmentResult.Enrolled(WireCodec.decodeEnrolResponse(resp.bodyAsText()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            EnrolmentResult.Failed("Couldn't reach the server")
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
