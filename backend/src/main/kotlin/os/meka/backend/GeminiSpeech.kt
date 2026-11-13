package os.meka.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import os.meka.core.domain.LiveTalkRules
import os.meka.core.domain.ObiVoiceRules
import os.meka.core.wire.SpeechCodec
import java.util.Base64

/**
 * One voice for Obi everywhere (Meka 2026-11-11 21:02: "orb sounds fab"): Google's Gemini speech generation, in the
 * same prebuilt voices Live talks in ("Charon", Obi's), through the Gemini key the server already holds for Live
 * (Secrets Manager `meka-os-dev/ai/gemini`; read at use time, never logged or returned). No action from Meka: the
 * read-outs (the brief, reminders, answers, the hello) sound like the orb. British English is asked for in the words
 * themselves (Gemini's speech models take their style from the prompt, as Live takes it from Obi's instruction).
 *
 * Google answers 24 kHz 16-bit mono PCM; [WavClip] wraps it so the devices' players (Android's MediaPlayer, the Mac's
 * AVAudioPlayer) play it as they play Polly's MP3, inside [SpeechCodec.MAX_AUDIO_BYTES].
 */
class GeminiSpeech internal constructor(
    private val key: () -> String?,
    private val http: HttpExchange,
    val model: String = DEFAULT_MODEL,
) : SpeechEngine {
    /** What the last clip cost Google (tokens in and out), for the AI budget; null until one is made. */
    @Volatile var lastTokens: Pair<Long, Long>? = null
        private set

    override fun configured(): Boolean = key() != null

    /** Live's voices, the same names and order as the Live picker. */
    override fun voices(language: String): List<SpeechVoice> = LiveTalkRules.VOICES.map {
        SpeechVoice(it.name, if (ObiVoiceRules.female(it.name)) "Female" else "Male", setOf(ENGINE))
    }

    override fun synthesize(text: String, voice: String, engine: String): ByteArray {
        val k = key() ?: error("no key")
        val name = liveVoice(voice) ?: error("not a Live voice")
        val (status, body) = http.send(
            "POST", "$BASE/models/$model:generateContent",
            mapOf("x-goog-api-key" to k, "content-type" to "application/json"), request(text, name),
        )
        check(status == 200) { "generate $status${GoogleSpeech.errorWords(body)?.let { " $it" } ?: ""}" }
        val (pcm, rate) = parseAudio(body) ?: error("no audio")
        lastTokens = parseTokens(body) ?: estimateTokens(text, pcm.size, rate)
        return WavClip.fit(pcm, rate) ?: error("clip too long")
    }

    companion object {
        const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        const val ENGINE = "gemini"
        /** Google's speech-generation model; `MEKA_SPEECH_GEMINI_MODEL` overrides it when Google renames it. */
        const val DEFAULT_MODEL = "gemini-2.5-flash-preview-tts"
        /** How the words are asked for: Obi's British English, as in Live. Never said aloud (Gemini reads the style). */
        const val STYLE = "Read this aloud in a warm, calm, natural British English accent, exactly as written:"
        const val RATE = 24_000
        private const val MAX_BYTES = 8_000_000

        /** List prices (2026): text in at $0.50 and audio out at $10 a million tokens; audio is 25 tokens a second. */
        const val IN_MICRO_USD_PER_TOKEN_X100 = 50L
        const val OUT_MICRO_USD_PER_TOKEN = 10L
        const val AUDIO_TOKENS_A_SECOND = 25L

        /** [voice] as one of Live's voices ("charon" → "Charon"), else null. */
        fun liveVoice(voice: String?): String? = LiveTalkRules.VOICES.firstOrNull { it.name.equals(voice?.trim(), ignoreCase = true) }?.name

        fun request(text: String, voice: String): String = buildJsonObject {
            putJsonArray("contents") {
                add(buildJsonObject { putJsonArray("parts") { add(buildJsonObject { put("text", "$STYLE\n\n$text") }) } })
            }
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add("AUDIO") }
                putJsonObject("speechConfig") {
                    putJsonObject("voiceConfig") { putJsonObject("prebuiltVoiceConfig") { put("voiceName", voice) } }
                }
            }
        }.toString()

        /** The PCM and its sample rate in a `generateContent` answer (`inlineData`, "audio/L16;codec=pcm;rate=24000"). */
        fun parseAudio(body: String): Pair<ByteArray, Int>? {
            if (body.length > MAX_BYTES) return null
            val o = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
            val parts = ((((o["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("content") as? JsonObject)?.get("parts") as? JsonArray)
                ?: return null
            val inline = parts.firstNotNullOfOrNull { (it as? JsonObject)?.get("inlineData") as? JsonObject } ?: return null
            val mime = (inline["mimeType"] as? JsonPrimitive)?.content.orEmpty()
            if (mime.isNotEmpty() && !mime.startsWith("audio/L16") && !mime.startsWith("audio/pcm")) return null
            val rate = Regex("rate=(\\d{4,6})").find(mime)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 8_000..48_000 } ?: RATE
            val b64 = (inline["data"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val pcm = runCatching { Base64.getDecoder().decode(b64) }.getOrNull()?.takeIf { it.size >= 2 } ?: return null
            return pcm to rate
        }

        /** (prompt tokens, answer tokens) from `usageMetadata`, else null. */
        fun parseTokens(body: String): Pair<Long, Long>? {
            if (body.length > MAX_BYTES) return null
            val o = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
            val u = o["usageMetadata"] as? JsonObject ?: return null
            val inT = (u["promptTokenCount"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return null
            val outT = (u["candidatesTokenCount"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return null
            return inT to outT
        }

        /** About four characters a token in, [AUDIO_TOKENS_A_SECOND] out; rounded up so the budget never undercounts. */
        fun estimateTokens(text: String, pcmBytes: Int, rate: Int): Pair<Long, Long> {
            val seconds = (pcmBytes.toLong() + 2L * rate - 1) / (2L * rate)
            return ((text.length + STYLE.length + 3L) / 4) to seconds * AUDIO_TOKENS_A_SECOND
        }

        /** Micro-dollars at list price for [tokens], at least 1 for a clip. */
        fun microUsd(tokens: Pair<Long, Long>): Long =
            ((tokens.first.coerceAtLeast(0) * IN_MICRO_USD_PER_TOKEN_X100 + 99) / 100 + tokens.second.coerceAtLeast(0) * OUT_MICRO_USD_PER_TOKEN).coerceAtLeast(1)

        /** The key from the Live secret (`MEKA_GEMINI_SECRET`), read at use time; a slow clip is given up on in 3.5 s. */
        fun fromSecret(secretId: String, model: String? = System.getenv("MEKA_SPEECH_GEMINI_MODEL")): GeminiSpeech {
            val reader = os.meka.backend.integrations.SecretCache.shared.reader(secretId, GeminiLiveTokens::secret) { it.key != null }
            return GeminiSpeech(
                { reader()?.key }, HttpExchange.jdk(java.time.Duration.ofMillis(3_500)),
                model?.trim()?.removePrefix("models/")?.takeIf { Regex("[a-z0-9.\\-]{3,60}").matches(it) } ?: DEFAULT_MODEL,
            )
        }
    }
}

/**
 * Raw PCM from Google as a WAV clip the devices can play. 16-bit when it fits in [SpeechCodec.MAX_AUDIO_BYTES]
 * (about 22 s at 24 kHz); a longer piece is G.711 μ-law, half the size (about 45 s, longer than any piece the devices
 * ask for); longer still, null (Polly says it instead).
 */
object WavClip {
    private const val HEADER = 44
    private const val MULAW_HEADER = 58

    fun fit(pcm16: ByteArray, rate: Int, max: Int = SpeechCodec.MAX_AUDIO_BYTES): ByteArray? {
        val samples = pcm16.size / 2
        if (samples == 0) return null
        if (HEADER + samples * 2 <= max) return pcm(pcm16, rate)
        if (MULAW_HEADER + samples <= max) return mulaw(pcm16, rate)
        return null
    }

    /** How long a clip from [fit] plays, from its own header; null for anything else. */
    fun ms(wav: ByteArray): Long? {
        if (wav.size < HEADER || String(wav, 0, 4, Charsets.US_ASCII) != "RIFF" || String(wav, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        val byteRate = le32(wav, 28).takeIf { it > 0 } ?: return null
        var at = 12
        while (at + 8 <= wav.size) {
            val id = String(wav, at, 4, Charsets.US_ASCII)
            val size = le32(wav, at + 4)
            if (id == "data") return minOf(size.toLong(), (wav.size - at - 8).toLong()) * 1000 / byteRate
            at += 8 + size + (size and 1)
        }
        return null
    }

    private fun pcm(pcm16: ByteArray, rate: Int): ByteArray {
        val data = pcm16.size / 2 * 2
        val out = java.io.ByteArrayOutputStream(HEADER + data)
        out.ascii("RIFF"); out.le32(36 + data); out.ascii("WAVE")
        out.ascii("fmt "); out.le32(16); out.le16(1); out.le16(1); out.le32(rate); out.le32(rate * 2); out.le16(2); out.le16(16)
        out.ascii("data"); out.le32(data); out.write(pcm16, 0, data)
        return out.toByteArray()
    }

    private fun mulaw(pcm16: ByteArray, rate: Int): ByteArray {
        val n = pcm16.size / 2
        val out = java.io.ByteArrayOutputStream(MULAW_HEADER + n + 1)
        val padded = n + (n and 1)
        out.ascii("RIFF"); out.le32(MULAW_HEADER - 8 + padded); out.ascii("WAVE")
        out.ascii("fmt "); out.le32(18); out.le16(7); out.le16(1); out.le32(rate); out.le32(rate); out.le16(1); out.le16(8); out.le16(0)
        out.ascii("fact"); out.le32(4); out.le32(n)
        out.ascii("data"); out.le32(n)
        for (i in 0 until n) {
            val s = (pcm16[2 * i].toInt() and 0xff) or (pcm16[2 * i + 1].toInt() shl 8)
            out.write(mulawByte(s.toShort().toInt()))
        }
        if (n and 1 == 1) out.write(0)
        return out.toByteArray()
    }

    /** G.711 μ-law of one 16-bit sample. */
    fun mulawByte(sample: Int): Int {
        val bias = 0x84
        var s = sample.coerceIn(-32_768, 32_767)
        val sign = if (s < 0) 0x80 else 0
        if (s < 0) s = -s
        s = minOf(s, 32_635) + bias
        var exponent = 7
        var mask = 0x4000
        while (exponent > 0 && s and mask == 0) { exponent--; mask = mask shr 1 }
        val mantissa = (s shr (exponent + 3)) and 0x0f
        return (sign or (exponent shl 4) or mantissa).inv() and 0xff
    }

    private fun le32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)

    private fun java.io.ByteArrayOutputStream.ascii(s: String) = write(s.toByteArray(Charsets.US_ASCII))
    private fun java.io.ByteArrayOutputStream.le16(v: Int) { write(v and 0xff); write((v shr 8) and 0xff) }
    private fun java.io.ByteArrayOutputStream.le32(v: Int) { le16(v and 0xffff); le16((v ushr 16) and 0xffff) }
}
