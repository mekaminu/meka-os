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
}
