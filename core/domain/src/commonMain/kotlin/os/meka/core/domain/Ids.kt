package os.meka.core.domain

import kotlin.random.Random

/**
 * Random 128-bit identifiers, rendered as 32 lowercase hex chars. Entity and op ids only need uniqueness;
 * ordering comes from HLCs. The platform supplies a CSPRNG-backed [Random] (SecureRandom / SecRandomCopyBytes).
 */
class IdGenerator(private val random: Random) {
    fun next(): String {
        val bytes = random.nextBytes(16)
        val sb = StringBuilder(32)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    private companion object {
        const val HEX = "0123456789abcdef"
    }
}
