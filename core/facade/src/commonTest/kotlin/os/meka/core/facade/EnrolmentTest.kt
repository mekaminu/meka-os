package os.meka.core.facade

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import os.meka.core.wire.WireCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EnrolmentTest {
    private fun client(status: HttpStatusCode, body: String = "") = HttpClient(MockEngine { req ->
        assertEquals("/v1/enrol", req.url.encodedPath)
        assertEquals("Enrol code123", req.headers["Authorization"])
        respond(body, status)
    })

    @Test
    fun successReturnsTheDeviceSecret() = runTest {
        val r = Enrolment.enrol(client(HttpStatusCode.OK, WireCodec.encodeEnrolResponse("abc")), "https://x.cloudfront.net", " code123 ", "home", "fold8", "Fold 8")
        assertEquals(EnrolmentResult.Enrolled("abc"), r)
    }

    @Test
    fun wrongCodeIsRejectedAndPlainHttpIsRefused() = runTest {
        assertIs<EnrolmentResult.Rejected>(Enrolment.enrol(client(HttpStatusCode.Unauthorized), "https://x", "code123", "home", "fold8", "Fold"))
        assertIs<EnrolmentResult.Failed>(Enrolment.enrol(client(HttpStatusCode.OK), "http://x", "code123", "home", "fold8", "Fold"))
    }

    @Test
    fun serverRefusalsBecomeWords() = runTest {
        val revoked = Enrolment.enrol(client(HttpStatusCode.Forbidden, "revoked"), "https://x", "code123", "home", "fold8", "Fold")
        assertEquals(EnrolmentResult.Failed(Enrolment.refusal("revoked")), revoked)
        assertTrue((revoked as EnrolmentResult.Failed).reason.startsWith("This device was revoked"))
        val household = Enrolment.enrol(client(HttpStatusCode.Forbidden, "household"), "https://x", "code123", "elsewhere", "fold8", "Fold")
        assertEquals("The enrolment code only adds devices to your existing household.", (household as EnrolmentResult.Failed).reason)
        assertEquals("The server refused this device", Enrolment.refusal("forbidden"))
    }
}
