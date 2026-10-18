package os.meka.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VaultFileCodecTest {
    private val id = "0123456789abcdef0123456789abcdef"

    @Test
    fun putGetAndDeleteRoundTrip() {
        val put = VaultFileCodec.Put(id, "AAECAwQ=")
        assertEquals(put, VaultFileCodec.decodePut(VaultFileCodec.encodePut(put)))
        assertEquals(id, VaultFileCodec.decodeRef(VaultFileCodec.encodeRef(id)))
        assertEquals("AAECAwQ=", VaultFileCodec.decodeSealed(VaultFileCodec.encodeSealed("AAECAwQ=")))
    }

    @Test
    fun aBadIdAnEmptyOrOversizedBoxIsRefused() {
        assertFailsWith<WireFormatException> { VaultFileCodec.decodeRef(VaultFileCodec.encodeRef("../etc/passwd/aaaaaaaa")) }
        assertFailsWith<WireFormatException> { VaultFileCodec.decodeRef(VaultFileCodec.encodeRef("short")) }
        assertFailsWith<WireFormatException> { VaultFileCodec.decodePut(VaultFileCodec.encodePut(VaultFileCodec.Put(id, ""))) }
        val big = "A".repeat((VaultFileCodec.MAX_SEALED_BYTES / 3 + 10) * 4)
        assertFailsWith<WireFormatException> { VaultFileCodec.decodePut(VaultFileCodec.encodePut(VaultFileCodec.Put(id, big))) }
        assertFailsWith<WireFormatException> { VaultFileCodec.decodeRef("""{"w":999,"id":"$id"}""") }
        assertFailsWith<WireFormatException> { VaultFileCodec.decodeSealed("""{"w":${WireCodec.VERSION}}""") }
    }
}
