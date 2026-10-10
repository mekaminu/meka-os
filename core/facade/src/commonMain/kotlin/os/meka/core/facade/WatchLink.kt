package os.meka.core.facade

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import os.meka.core.wire.DeviceLinkCodec
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

/**
 * The watch's half of linking (Galaxy Watch, slice 1; ADR-005 amendment 2026-10-10). Before it is a device of the
 * household the watch has no secret, only its hardware key, so these two calls are signed with that key alone:
 * [start] asks for the code the watch shows, [status] asks whether Meka has typed it yet and, once he has, hands back
 * the household, the device id and the secret (exactly once). Then the watch connects like any device
 * ([Enrolment.transport] with the same key, which the server already holds).
 */
object WatchLink {
    sealed class Started {
        data class Code(val linkId: String, val code: String, val expiresAtMs: Long) : Started()
        /** Too many watches are waiting to link right now; try again in a minute. */
        object Busy : Started()
        data class Failed(val reason: String) : Started()
    }

    sealed class Status {
        object Waiting : Status()
        /** The code ran out (or the server forgot it): ask for a new one. */
        object Expired : Status()
        data class Linked(val householdId: String, val deviceId: String, val secret: String) : Status()
        data class Failed(val reason: String) : Status()
    }

    suspend fun start(client: HttpClient, baseUrl: String, key: DeviceKey, deviceId: String, name: String, nowMs: Long): Started {
        if (!baseUrl.startsWith("https://")) return Started.Failed("The server address must start with https://")
        val body = DeviceLinkCodec.encodeStart(DeviceLinkCodec.Start(deviceId, name, key.publicKeyDerBase64))
        return try {
            val resp = signedPost(client, baseUrl, "/v1/link/start", body, key, nowMs)
            val text = resp.bodyAsText()
            when {
                resp.status.value == 429 -> Started.Busy
                !resp.status.isSuccess() -> Started.Failed("Server returned ${resp.status.value}")
                else -> DeviceLinkCodec.decodeStarted(text).let { Started.Code(it.linkId, it.code, it.expiresAtMs) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Started.Failed("Couldn't reach the server")
        }
    }

    suspend fun status(client: HttpClient, baseUrl: String, key: DeviceKey, linkId: String, nowMs: Long): Status = try {
        val resp = signedPost(client, baseUrl, "/v1/link/status", DeviceLinkCodec.encodeStatusRequest(linkId), key, nowMs)
        if (!resp.status.isSuccess()) {
            Status.Failed("Server returned ${resp.status.value}")
        } else {
            val s = DeviceLinkCodec.decodeStatus(resp.bodyAsText())
            when (s.state) {
                DeviceLinkCodec.WAITING -> Status.Waiting
                DeviceLinkCodec.LINKED -> Status.Linked(s.householdId!!, s.deviceId!!, s.secret!!)
                else -> Status.Expired
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Status.Failed("Couldn't reach the server")
    }

    private suspend fun signedPost(client: HttpClient, baseUrl: String, path: String, body: String, key: DeviceKey, nowMs: Long) =
        Random.nextBytes(16).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }.let { nonce ->
            val signature = key.signBase64(RequestSigning.canonical("POST", path, nowMs, nonce, body))
            client.post(baseUrl.trimEnd('/') + path) {
                contentType(ContentType.Application.Json)
                header(RequestSigning.HEADER_TIME, nowMs.toString())
                header(RequestSigning.HEADER_NONCE, nonce)
                header(RequestSigning.HEADER_SIGNATURE, signature)
                setBody(body)
            }
        }
}
