package os.meka.core.domain

/**
 * The waveform under a caller's message (call notifications that tell him something, slice 7: "a player with a
 * waveform and scrub bar"). Both apps draw the same bars from the recording's own bytes, in memory, without decoding
 * the audio: every MP3 frame carries, per granule (576 samples), a `global_gain` that tracks how loud that stretch is
 * (1.5 dB a step), and a granule with no coded bits is silence. Walking the frames is cheap enough for the phone and
 * the Mac to do as the message starts, and works the same on both from common code. Non-AI, pure; nothing about the
 * recording is kept or sent.
 */
object VoiceWaveformRules {
    /** Bars across the player (the Fold's 48 dp rows and the Mac's sheet both fit them at 3 dp a bar). */
    const val BARS = 48
    /** The shortest bar (silence still shows a dot, so the line reads as a recording, not a gap). */
    const val MIN_BAR = 0.08f
    /** How many gain steps below the loudest stretch still draw above the minimum (40 × 1.5 dB = 60 dB). */
    const val RANGE_STEPS = 40

    /** The bars for [bytes] (an MP3): [bars] heights in [MIN_BAR]..1, or empty when no frame could be read. */
    fun of(bytes: ByteArray, bars: Int = BARS): List<Float> = bars(levels(bytes), bars)

    /**
     * One loudness value per granule, in order: its `global_gain` (the loudest channel's), or 0 for a silent granule.
     * Skips an ID3v2 tag and anything between frames that isn't a Layer III frame header.
     */
    fun levels(bytes: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        var i = id3Size(bytes)
        while (i + 4 <= bytes.size) {
            val frame = header(bytes, i)
            if (frame == null || i + frame.length > bytes.size) { i++; continue }
            val side = BitReader(bytes, (i + 4 + if (frame.crc) 2 else 0) * 8)
            val nch = if (frame.mono) 1 else 2
            if (frame.mpeg1) {
                side.skip(9 + (if (frame.mono) 5 else 3) + 4 * nch)
                repeat(2) { out += granule(side, nch, 30) }
            } else {
                side.skip(8 + if (frame.mono) 1 else 2)
                out += granule(side, nch, 34)
            }
            i += frame.length
        }
        return out
    }

    /** [levels] into [bars] heights: each bar is its stretch's loudest granule, relative to the whole message's. */
    fun bars(levels: List<Int>, bars: Int = BARS): List<Float> {
        val top = levels.maxOrNull() ?: return emptyList()
        if (top <= 0 || bars <= 0) return emptyList()
        val n = levels.size
        val bottom = top - RANGE_STEPS
        return List(bars) { b ->
            val from = (b.toLong() * n / bars).toInt()
            val to = maxOf(from + 1, ((b + 1).toLong() * n / bars).toInt()).coerceAtMost(n)
            var v = 0
            for (k in from until to) if (levels[k] > v) v = levels[k]
            if (v <= 0) MIN_BAR
            else MIN_BAR + (1f - MIN_BAR) * ((v - bottom).toFloat() / RANGE_STEPS).coerceIn(0f, 1f)
        }
    }

    private class Frame(val mpeg1: Boolean, val crc: Boolean, val mono: Boolean, val length: Int)

    private val BITRATE_V1 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val BITRATE_V2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
    private val RATE_V1 = intArrayOf(44100, 48000, 32000)
    private val RATE_V2 = intArrayOf(22050, 24000, 16000)
    private val RATE_V25 = intArrayOf(11025, 12000, 8000)

    /** The Layer III frame starting at [at], or null when there isn't one. */
    private fun header(b: ByteArray, at: Int): Frame? {
        val b0 = b[at].toInt() and 0xFF
        val b1 = b[at + 1].toInt() and 0xFF
        val b2 = b[at + 2].toInt() and 0xFF
        val b3 = b[at + 3].toInt() and 0xFF
        if (b0 != 0xFF || b1 and 0xE0 != 0xE0) return null
        val version = (b1 shr 3) and 3 // 0 = MPEG 2.5, 1 reserved, 2 = MPEG 2, 3 = MPEG 1
        if (version == 1 || (b1 shr 1) and 3 != 1) return null // Layer III only
        val bitrateIdx = b2 shr 4
        val rateIdx = (b2 shr 2) and 3
        if (bitrateIdx == 0 || bitrateIdx == 15 || rateIdx == 3) return null
        val mpeg1 = version == 3
        val kbps = (if (mpeg1) BITRATE_V1 else BITRATE_V2)[bitrateIdx]
        val rate = when (version) { 3 -> RATE_V1; 2 -> RATE_V2; else -> RATE_V25 }[rateIdx]
        val padding = (b2 shr 1) and 1
        val length = (if (mpeg1) 144_000 else 72_000) * kbps / rate + padding
        val mono = b3 shr 6 == 3
        val crc = b1 and 1 == 0
        val sideBytes = if (mpeg1) (if (mono) 17 else 32) else (if (mono) 9 else 17)
        if (length < 4 + (if (crc) 2 else 0) + sideBytes) return null
        return Frame(mpeg1, crc, mono, length)
    }

    /** One granule across [nch] channels: the loudest channel's gain, 0 when every channel is silent. */
    private fun granule(side: BitReader, nch: Int, rest: Int): Int {
        var level = 0
        repeat(nch) {
            val bits = side.read(12)
            side.skip(9) // big_values
            val gain = side.read(8)
            side.skip(rest)
            if (bits > 0 && gain > level) level = gain
        }
        return level
    }

    /** An ID3v2 tag's whole length at the start of [b] (0 when there isn't one). */
    private fun id3Size(b: ByteArray): Int {
        if (b.size < 10 || b[0] != 'I'.code.toByte() || b[1] != 'D'.code.toByte() || b[2] != '3'.code.toByte()) return 0
        var size = 0
        for (k in 6..9) size = (size shl 7) or (b[k].toInt() and 0x7F)
        val footer = if (b[5].toInt() and 0x10 != 0) 10 else 0
        return (10 + size + footer).coerceAtMost(b.size)
    }

    private class BitReader(private val b: ByteArray, private var bit: Int) {
        fun skip(n: Int) { bit += n }
        fun read(n: Int): Int {
            var v = 0
            repeat(n) {
                val byte = bit ushr 3
                val on = byte < b.size && (b[byte].toInt() shr (7 - (bit and 7))) and 1 == 1
                v = (v shl 1) or if (on) 1 else 0
                bit++
            }
            return v
        }
    }
}
