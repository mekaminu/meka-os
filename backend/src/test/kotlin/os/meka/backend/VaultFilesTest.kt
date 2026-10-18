package os.meka.backend

import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.wire.VaultFileCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The Vault's encrypted copies (slice 2b): sealed bytes kept per household by id, never for a watch or another household. */
class VaultFilesTest {
    private val devices = InMemoryDeviceRegistry()
    private val foldSecret = devices.enrol("hh", "fold")
    private val foldKey = TestDeviceKey().also { devices.registerKey(DeviceIdentity("hh", "fold"), it.publicB64) }
    private val macSecret = devices.enrol("hh", "mac")
    private val macKey = TestDeviceKey().also { devices.registerKey(DeviceIdentity("hh", "mac"), it.publicB64) }
    private val otherSecret = devices.enrol("hh2", "fold2")
    private val otherKey = TestDeviceKey().also { devices.registerKey(DeviceIdentity("hh2", "fold2"), it.publicB64) }
    private val watchSecret = devices.enrol("hh", "watch0123abcd")
    private val watchKey = TestDeviceKey().also { devices.registerKey(DeviceIdentity("hh", "watch0123abcd"), it.publicB64) }
    private val store = InMemoryVaultFileStore()
    private val id = "0123456789abcdef0123456789abcdef"
    private val sealed = java.util.Base64.getEncoder().encodeToString(ByteArray(5_000) { it.toByte() })

    private fun ApplicationTestBuilder.mekaServer() = application {
        mekaSync(InMemoryServerOpStore(), devices, vaultFiles = VaultFiles(store))
    }

    @Test
    fun theFoldPutsTheMacGetsAndEitherDeletes() = testApplication {
        mekaServer()
        val put = VaultFileCodec.encodePut(VaultFileCodec.Put(id, sealed))
        val p = VaultFileCodec.PUT_PATH
        assertEquals(HttpStatusCode.OK, client.post(p) { with(foldKey) { signed(foldSecret, p, put) } }.status)
        assertEquals(setOf("vault/hh/$id.bin"), store.objects.keys)

        val ref = VaultFileCodec.encodeRef(id)
        val g = VaultFileCodec.GET_PATH
        val got = client.post(g) { with(macKey) { signed(macSecret, g, ref) } }
        assertEquals(HttpStatusCode.OK, got.status)
        assertEquals(sealed, VaultFileCodec.decodeSealed(got.bodyAsText()))
        // Another household's device finds nothing under the same id.
        assertEquals(HttpStatusCode.NotFound, client.post(g) { with(otherKey) { signed(otherSecret, g, ref) } }.status)

        val d = VaultFileCodec.DELETE_PATH
        assertEquals(HttpStatusCode.OK, client.post(d) { with(macKey) { signed(macSecret, d, ref) } }.status)
        assertTrue(store.objects.isEmpty())
        assertEquals(HttpStatusCode.NotFound, client.post(g) { with(foldKey) { signed(foldSecret, g, ref) } }.status)
        // Deleting one that isn't there is fine.
        assertEquals(HttpStatusCode.OK, client.post(d) { with(foldKey) { signed(foldSecret, d, ref) } }.status)
    }

    @Test
    fun aWatchUnsignedCallersAndBadIdsAreRefused() = testApplication {
        mekaServer()
        val put = VaultFileCodec.encodePut(VaultFileCodec.Put(id, sealed))
        val p = VaultFileCodec.PUT_PATH
        assertEquals(HttpStatusCode.Forbidden, client.post(p) { with(watchKey) { signed(watchSecret, p, put) } }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post(p) { with(foldKey) { signed("wrong", p, put) } }.status)
        val bad = """{"w":${os.meka.core.wire.WireCodec.VERSION},"id":"../../etc","sealed":"$sealed"}"""
        assertEquals(HttpStatusCode.BadRequest, client.post(p) { with(foldKey) { signed(foldSecret, p, bad) } }.status)
        assertTrue(store.objects.isEmpty())
        assertFailsWith<IllegalArgumentException> { VaultFiles.key("hh/../x", id) }
    }
}
