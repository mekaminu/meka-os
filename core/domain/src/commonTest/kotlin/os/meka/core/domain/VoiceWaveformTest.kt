package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The player's waveform and scrub bar (call notifications slice 7): bars read from the MP3's frames, and seeking. */
class VoiceWaveformTest {
    /** Bits written most significant first, as MP3 side information is laid out. */
    private class Bits {
        private val bits = ArrayList<Boolean>()
        fun put(value: Int, n: Int) { for (k in n - 1 downTo 0) bits += (value shr k) and 1 == 1 }
        fun bytes(size: Int): ByteArray = ByteArray(size).also { out ->
            bits.forEachIndexed { i, on -> if (on) out[i / 8] = (out[i / 8].toInt() or (1 shl (7 - i % 8))).toByte() }
        }
    }

    /** One 8 kHz mono 32 kbps MPEG 2.5 frame (288 bytes), the phone service's kind, with [gain] (0 = silent). */
    private fun phoneFrame(gain: Int): ByteArray {
        val side = Bits()
        side.put(0, 8) // main_data_begin
        side.put(0, 1) // private
        side.put(if (gain == 0) 0 else 400, 12) // part2_3_length
        side.put(if (gain == 0) 0 else 120, 9) // big_values
        side.put(gain, 8)
        side.put(0x155, 34) // the rest of the granule (made noisy so it isn't read as gain)
        val frame = ByteArray(288)
        byteArrayOf(0xFF.toByte(), 0xE3.toByte(), 0x48, 0xC0.toByte()).copyInto(frame)
        side.bytes(9).copyInto(frame, 4)
        for (k in 13 until 288) frame[k] = 0x5A
        return frame
    }

    /** One 44.1 kHz stereo 128 kbps MPEG 1 frame (417 bytes) whose four granule/channel gains are [gains]. */
    private fun musicFrame(gains: List<Int>): ByteArray {
        val side = Bits()
        side.put(0, 9); side.put(0, 3); side.put(0, 8) // main_data_begin, private, scfsi × 2
        gains.forEach { g -> side.put(300, 12); side.put(100, 9); side.put(g, 8); side.put(0, 30) }
        val frame = ByteArray(417)
        byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00).copyInto(frame)
        side.bytes(32).copyInto(frame, 4)
        return frame
    }

    private fun join(parts: List<ByteArray>): ByteArray = parts.fold(ByteArray(0)) { acc, p -> acc + p }

    @Test
    fun phoneRecordingGainsAreReadPerFrameThroughAnId3TagAndJunk() {
        val tag = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, 0, 20) + ByteArray(20) { 0x7F }
        val bytes = join(listOf(tag, byteArrayOf(1, 2, 3), phoneFrame(0), phoneFrame(150), phoneFrame(170), phoneFrame(0)))
        assertEquals(listOf(0, 150, 170, 0), VoiceWaveformRules.levels(bytes))
    }

    @Test
    fun mpeg1FramesGiveTwoGranulesEachLoudestChannel() {
        val bytes = musicFrame(listOf(140, 160, 130, 120))
        assertEquals(listOf(160, 130), VoiceWaveformRules.levels(bytes))
    }

    @Test
    fun barsAreRelativeToTheLoudestStretchWithSilenceAsADot() {
        val levels = listOf(0, 0, 200, 200, 180, 180, 0, 0)
        val bars = VoiceWaveformRules.bars(levels, bars = 4)
        assertEquals(4, bars.size)
        assertEquals(VoiceWaveformRules.MIN_BAR, bars[0])
        assertEquals(1f, bars[1])
        assertTrue(bars[2] in 0.5f..0.6f, "20 steps down of 40 is about half: ${bars[2]}")
        assertEquals(VoiceWaveformRules.MIN_BAR, bars[3])
        // Fewer granules than bars still fills every bar; nothing readable gives no waveform (the plain bar instead).
        assertEquals(48, VoiceWaveformRules.bars(listOf(100, 120)).size)
        assertTrue(VoiceWaveformRules.of(ByteArray(500) { 0x41 }).isEmpty())
        assertEquals(1f, VoiceWaveformRules.of(join(List(10) { phoneFrame(140 + it) })).last())
    }

    @Test
    fun scrubbingSeeksAndALetGoAtTheEndStartsAgain() {
        assertEquals(10_000L, VoiceRecordingRules.seekMs(0.25f, 40_000))
        assertEquals(40_000L, VoiceRecordingRules.seekMs(1.4f, 40_000))
        assertEquals(0L, VoiceRecordingRules.seekMs(-1f, 40_000))
        assertEquals(0L, VoiceRecordingRules.seekMs(0.5f, 0))
        assertEquals(20_000L, VoiceRecordingRules.startFromMs(0.5f, 40_000))
        assertEquals(0L, VoiceRecordingRules.startFromMs(0.995f, 40_000))
        assertEquals("Voice message, 0:12 of 0:40", VoiceRecordingRules.spoken(12_400, 40_000))
        assertEquals("Voice message", VoiceRecordingRules.spoken(0, 0))
    }
}
