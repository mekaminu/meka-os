package os.meka.backend

import os.meka.core.wire.SpeechCodec
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.services.polly.PollyClient
import software.amazon.awssdk.services.polly.model.DescribeVoicesRequest
import software.amazon.awssdk.services.polly.model.SynthesizeSpeechRequest
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import javax.sql.DataSource

/**
 * MEKA's voice (build plan V1, "Weather and a voice Meka likes", item 3, approved by Meka 2026-10-08): a keyed device
 * sends a piece of MEKA's own reply and gets it back as speech from Amazon Polly in MEKA's own AWS account (no new
 * provider, no new key; the task role may only call SynthesizeSpeech and DescribeVoices). The text is never stored or
 * logged; only the month's character count is kept (`speech_usage`), and at [SpeechService.capChars] the server says
 * "over" so the device's own voice speaks until the 1st.
 */
data class SpeechVoice(val id: String, val gender: String, val engines: Set<String>)

/** Text-to-speech, behind an interface so the service is testable without AWS (ADR-009). */
interface SpeechEngine {
    /** The voices for [language] ("en-GB"). Throws when the service can't be reached. */
    fun voices(language: String): List<SpeechVoice>

    /** MP3 of [text] said by [voice] on [engine] ("generative" or "neural"). Throws on failure. */
    fun synthesize(text: String, voice: String, engine: String): ByteArray
}

/** Amazon Polly in the service's own region (credentials from the task role). */
class PollySpeech : SpeechEngine {
    private val polly = PollyClient.builder().httpClient(UrlConnectionHttpClient.create()).build()

    override fun voices(language: String): List<SpeechVoice> {
        val out = mutableListOf<SpeechVoice>()
        var token: String? = null
        do {
            val req = DescribeVoicesRequest.builder().languageCode(language).apply { token?.let { nextToken(it) } }.build()
            val page = polly.describeVoices(req)
            page.voices().forEach { v -> out += SpeechVoice(v.idAsString(), v.genderAsString().orEmpty(), v.supportedEnginesAsStrings().toSet()) }
            token = page.nextToken()?.takeIf { it.isNotEmpty() }
        } while (token != null && out.size < 200)
        return out
    }

    override fun synthesize(text: String, voice: String, engine: String): ByteArray =
        polly.synthesizeSpeechAsBytes(
            SynthesizeSpeechRequest.builder().text(text).textType("text").voiceId(voice).engine(engine).outputFormat("mp3").build(),
        ).asByteArray()
}

/** The month's meter: clips and characters per UTC month. */
interface SpeechUsageStore {
    fun add(month: String, chars: Long)
    fun used(month: String): Long
}

class InMemorySpeechUsageStore : SpeechUsageStore {
    private val chars = mutableMapOf<String, Long>()

    @Synchronized
    override fun add(month: String, chars: Long) { this.chars[month] = (this.chars[month] ?: 0) + chars }

    @Synchronized
    override fun used(month: String): Long = chars[month] ?: 0
}

class PostgresSpeechUsageStore(private val ds: DataSource) : SpeechUsageStore {
    override fun add(month: String, chars: Long) = ds.connection.use { c ->
        c.prepareStatement(
            """INSERT INTO speech_usage(month, clips, chars) VALUES (?,1,?)
               ON CONFLICT (month) DO UPDATE SET clips = speech_usage.clips + 1, chars = speech_usage.chars + EXCLUDED.chars, updated_at = now()""",
        ).use { it.setString(1, month); it.setLong(2, chars); it.executeUpdate() }
        Unit
    }

    override fun used(month: String): Long = ds.connection.use { c ->
        c.prepareStatement("SELECT chars FROM speech_usage WHERE month = ?").use { st ->
            st.setString(1, month)
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }
    }
}

/**
 * Chooses the voice and engine, meters the month and says MEKA's words. The voice list is read from Polly at most
 * every [voicesForMs] (six hours; a failure is tried again on the next request). Only British English voices are
 * offered, each on its best engine here (generative, else neural; standard-only voices aren't natural enough to
 * offer), best first: generative before neural, then [PREFERRED], then by name. The first is the default.
 */
class SpeechService(
    private val engine: SpeechEngine?,
    private val usage: SpeechUsageStore,
    val capChars: Long = CAP_CHARS,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val voicesForMs: Long = 6 * 3_600_000L,
) {
    private var cached: Pair<Long, List<SpeechCodec.Voice>>? = null

    fun month(): String = Instant.ofEpochMilli(nowMs()).atOffset(ZoneOffset.UTC).let { "%04d-%02d".format(it.year, it.monthValue) }

    /** The offered voices (best first); throws when Polly can't be reached and nothing is cached. */
    @Synchronized
    fun offered(): List<SpeechCodec.Voice> {
        val e = engine ?: return emptyList()
        cached?.let { (at, v) -> if (nowMs() - at < voicesForMs) return v }
        val v = rank(e.voices(LANGUAGE))
        cached = nowMs() to v
        return v
    }

    fun voices(): SpeechCodec.Voices {
        if (engine == null) return SpeechCodec.Voices(SpeechCodec.Voices.OFF, reason = "MEKA's voice isn't set up on this server")
        val m = month()
        val used = runCatching { usage.used(m) }.getOrDefault(0)
        val list = runCatching { offered() }.getOrElse {
            return SpeechCodec.Voices(SpeechCodec.Voices.FAILED, month = m, usedChars = used, capChars = capChars, reason = UNREACHABLE)
        }
        if (list.isEmpty()) return SpeechCodec.Voices(SpeechCodec.Voices.FAILED, month = m, usedChars = used, capChars = capChars, reason = "Amazon Polly has no British voice here")
        return SpeechCodec.Voices(SpeechCodec.Voices.ON, list, list.first().id, m, used, capChars)
    }

    fun speak(r: SpeechCodec.Request): SpeechCodec.Response {
        val e = engine ?: return SpeechCodec.Response(SpeechCodec.Response.OFF, reason = "MEKA's voice isn't set up on this server")
        val m = month()
        val chars = r.text.length.toLong()
        if (usage.used(m) + chars > capChars) return SpeechCodec.Response(SpeechCodec.Response.OVER, reason = OVER)
        val list = runCatching { offered() }.getOrElse { return SpeechCodec.Response(SpeechCodec.Response.FAILED, reason = UNREACHABLE) }
        val voice = list.firstOrNull { it.id.equals(r.voice, ignoreCase = true) } ?: list.firstOrNull()
            ?: return SpeechCodec.Response(SpeechCodec.Response.FAILED, reason = "Amazon Polly has no British voice here")
        val audio = runCatching { e.synthesize(r.text, voice.id, voice.engine) }.getOrElse {
            return SpeechCodec.Response(SpeechCodec.Response.FAILED, reason = UNREACHABLE)
        }
        if (audio.isEmpty()) return SpeechCodec.Response(SpeechCodec.Response.FAILED, reason = "Amazon Polly said nothing")
        usage.add(m, chars)
        return SpeechCodec.Response(SpeechCodec.Response.SPOKEN, Base64.getEncoder().encodeToString(audio), "mp3", voice.id, voice.engine)
    }

    companion object {
        const val LANGUAGE = "en-GB"
        const val CAP_CHARS = 1_000_000L
        const val GENERATIVE = "generative"
        const val NEURAL = "neural"
        /** Meka's shortlist (build plan): these lead the list on the same engine. */
        val PREFERRED = listOf("Amy", "Emma", "Brian", "Arthur")
        const val UNREACHABLE = "Couldn't reach Amazon Polly"
        const val OVER = "This month's voice is used up; MEKA's own voice speaks until the 1st"

        fun rank(voices: List<SpeechVoice>): List<SpeechCodec.Voice> = voices
            .mapNotNull { v ->
                val engine = when {
                    GENERATIVE in v.engines -> GENERATIVE
                    NEURAL in v.engines -> NEURAL
                    else -> return@mapNotNull null
                }
                if (!Regex("[A-Za-z]{2,20}").matches(v.id)) return@mapNotNull null
                SpeechCodec.Voice(v.id, v.gender, engine)
            }
            .distinctBy { it.id }
            .sortedWith(
                compareBy<SpeechCodec.Voice>(
                    { if (it.engine == GENERATIVE) 0 else 1 },
                    { PREFERRED.indexOf(it.id).let { i -> if (i < 0) PREFERRED.size else i } },
                    { it.id },
                ),
            )
    }
}

/** Null unless the deployment turns Polly on (`MEKA_SPEECH_ENGINE=polly`) and has a database for the meter. */
fun speechFromEnv(ds: DataSource): SpeechService? {
    if (System.getenv("MEKA_SPEECH_ENGINE")?.trim() != "polly") return null
    return SpeechService(PollySpeech(), PostgresSpeechUsageStore(ds))
}
