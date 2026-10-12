package os.meka.core.facade

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import os.meka.core.domain.RecoveryKeyRules
import os.meka.core.wire.RecoveryCodec
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

/**
 * A new phone or Mac connecting with the recovery key's 24 words (build plan V1 "Recovery key"; ADR-005 amendment
 * 2026-10-12). Like a watch's link, the device has no secret yet, only its new hardware key, so the one call is signed
 * with that key alone. The words are read on the device ([RecoveryKeyRules.read]); only their [RecoveryKeyRules.secret]
 * is sent. The answer is the household, the device id and the device's own secret, handed over once; then the device
 * connects like any other ([Enrolment.transport] with the same key, which the server already holds) and the household's
 * data comes back from the server.
 */
object RecoveryEnrolment {
    sealed class Result {
        data class Enrolled(val householdId: String, val deviceId: String, val secret: String) : Result()
        /** The words didn't read ([line] says why, e.g. "Word 3 isn't one of the key's words"); nothing was sent. */
        data class Unreadable(val line: String) : Result()
        /** The server refused ([line] in words: wrong or spent key, too many tries, this device id). */
        data class Refused(val reason: String, val line: String) : Result()
        data class Failed(val line: String) : Result()
    }

    /**
     * The answer flattened for Swift (the Mac's Connect sheet, Recovery key slice 3): [secret] once enrolled, else
     * [problem] in words. The household and device id the server enrolled must be the ones this device's core was made
     * with ([householdId], [deviceId]: every op carries them), else it can't join ([device] names it: "Mac").
     */
    class Outcome(val secret: String?, val problem: String?)

    fun outcome(r: Result, householdId: String, deviceId: String, device: String): Outcome = when (r) {
        is Result.Enrolled ->
            if (r.householdId != householdId || r.deviceId != deviceId) Outcome(null, "This $device can't join that household · reinstall MEKA and try again")
            else Outcome(r.secret, null)
        is Result.Unreadable -> Outcome(null, r.line)
        is Result.Refused -> Outcome(null, r.line)
        is Result.Failed -> Outcome(null, r.line)
    }

    suspend fun enrol(
        client: HttpClient,
        baseUrl: String,
        key: DeviceKey,
        words: String,
        deviceId: String,
        name: String,
        nowMs: Long,
    ): Result {
        if (!baseUrl.startsWith("https://")) return Result.Failed("The server address must start with https://")
        val reading = RecoveryKeyRules.read(words)
        if (reading !is RecoveryKeyRules.Reading.Ok) return Result.Unreadable(RecoveryKeyRules.readingLine(reading).orEmpty())
        val body = RecoveryCodec.encodeEnrol(RecoveryCodec.Enrol(RecoveryKeyRules.secret(reading.entropy), deviceId, name, key.publicKeyDerBase64))
        val path = "/v1/recovery/enrol"
        return try {
            val nonce = Random.nextBytes(16).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            val signature = key.signBase64(RequestSigning.canonical("POST", path, nowMs, nonce, body))
            val resp = client.post(baseUrl.trimEnd('/') + path) {
                contentType(ContentType.Application.Json)
                header(RequestSigning.HEADER_TIME, nowMs.toString())
                header(RequestSigning.HEADER_NONCE, nonce)
                header(RequestSigning.HEADER_SIGNATURE, signature)
                setBody(body)
            }
            val text = resp.bodyAsText()
            val refusal = if (!resp.status.isSuccess()) RecoveryCodec.decodeError(text) else null
            when {
                refusal != null -> Result.Refused(refusal, RecoveryKeyRules.refusal(refusal))
                resp.status.value == 404 -> Result.Failed("This server doesn't take recovery keys yet")
                !resp.status.isSuccess() -> Result.Failed("Server returned ${resp.status.value}")
                else -> RecoveryCodec.decodeEnrolled(text).let { Result.Enrolled(it.householdId, it.deviceId, it.secret) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Failed("Couldn't reach the server")
        }
    }
}
