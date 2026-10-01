package os.meka.backend

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import os.meka.core.sync.FieldValue
import os.meka.core.sync.Hlc
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Op
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.wire.WireCodec
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncApiTest {
    private val devices = InMemoryDeviceRegistry()
    private val androidSecret = devices.enrol("hh", "android")
    private val macSecret = devices.enrol("hh", "mac")
    private val strangerSecret = devices.enrol("other-hh", "intruder")

    private fun op(id: String, dev: String = "android", hh: String = "hh") =
        Op(id, hh, "task", "t1", "title", FieldValue.Text("x"), Hlc(1, 0, dev), emptyList(), dev)

    @Test
    fun pushIsIdempotentAndPullReturnsOps() = testApplication {
        application { mekaSync(InMemoryServerOpStore(), devices) }
        val body = WireCodec.encodePushRequest(PushRequest("hh", "android", listOf(op("o1"), op("o2"))))
        repeat(3) {
            val r = client.post("/v1/sync/push") { header("Authorization", "Bearer $androidSecret"); setBody(body) }
            assertEquals(HttpStatusCode.OK, r.status)
            assertEquals(listOf("o1", "o2"), WireCodec.decodePushResponse(r.bodyAsText()).acknowledged)
        }
        val pull = client.post("/v1/sync/pull") {
            header("Authorization", "Bearer $macSecret")
            setBody(WireCodec.encodePullRequest(PullRequest("hh", "mac", 0)))
        }
        assertEquals(listOf(1L, 2L), WireCodec.decodePullResponse(pull.bodyAsText()).ops.map { it.seq })
    }

    @Test
    fun authIsRequiredAndBoundToHouseholdAndDevice() = testApplication {
        application { mekaSync(InMemoryServerOpStore(), devices) }
        val body = WireCodec.encodePushRequest(PushRequest("hh", "android", listOf(op("o1"))))
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/sync/push") { setBody(body) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/sync/push") { header("Authorization", "Bearer nope"); setBody(body) }.status)
        // A valid device from another household cannot write into this one, nor impersonate a device.
        assertEquals(HttpStatusCode.Forbidden, client.post("/v1/sync/push") { header("Authorization", "Bearer $strangerSecret"); setBody(body) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/v1/sync/push") { header("Authorization", "Bearer $macSecret"); setBody(body) }.status)
        devices.revoke("android")
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/sync/push") { header("Authorization", "Bearer $androidSecret"); setBody(body) }.status)
    }

    @Test
    fun malformedBodiesAreRejectedWithoutEcho() = testApplication {
        application { mekaSync(InMemoryServerOpStore(), devices) }
        val r = client.post("/v1/sync/push") { header("Authorization", "Bearer $androidSecret"); setBody("{\"w\":9}") }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }
}
