package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/** HMAC-SHA256 and HKDF-SHA256 against the RFCs' own vectors (Vault slice 2a). */
class HkdfTest {
    private fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    private fun bytes(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun hmacMatchesRfc4231() {
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            hex(HmacSha256.mac("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray())),
        )
        // A key longer than the block is hashed first (RFC 4231 case 6).
        assertEquals(
            "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54",
            hex(HmacSha256.mac(ByteArray(131) { 0xaa.toByte() }, "Test Using Larger Than Block-Size Key - Hash Key First".encodeToByteArray())),
        )
    }

    @Test
    fun hkdfMatchesRfc5869() {
        val ikm = ByteArray(22) { 0x0b }
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            hex(Hkdf.derive(ikm, bytes("000102030405060708090a0b0c"), bytes("f0f1f2f3f4f5f6f7f8f9"), 42)),
        )
        // Case 3: no salt, no info.
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            hex(Hkdf.derive(ikm, ByteArray(0), ByteArray(0), 42)),
        )
    }
}
