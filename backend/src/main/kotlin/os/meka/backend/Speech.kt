package os.meka.backend

import os.meka.core.domain.EngineLatch
import os.meka.core.domain.EngineLatchRules
import os.meka.core.wire.SpeechCodec
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.services.polly.PollyClient
import software.amazon.awssdk.services.polly.model.DescribeVoicesRequest
import software.amazon.awssdk.services.polly.model.OutputFormat
import software.amazon.awssdk.services.polly.model.SpeechMarkType
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

    /** Whether the engine can be asked at all (Google: a key is in place). */
    fun configured(): Boolean = true

    /**
     * The orb speed pass, slice 5: opens (or keeps open) the connection to the voice with a free request (the British
     * voice list, nothing said, nothing counted), so the clip that follows doesn't pay for a new connection. True when
     * it answered. Throws when the service can't be reached.
     */
    fun touch(): Boolean = false

    /**
     * Obi's face, slice 2: the mouth sounds of [text] said by [voice] on [engine] (Polly's viseme speech marks), times
     * in milliseconds from the clip's start. Empty when the voice gives none (Google's). Throws on failure.
     */
    fun visemes(text: String, voice: String, engine: String): List<SpeechCodec.Mark> = emptyList()
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

    override fun touch(): Boolean {
        polly.describeVoices(DescribeVoicesRequest.builder().languageCode("en-GB").build())
        return true
    }

    /** Engines that refused SSML once (slice 7): they are sent the plain words from then on, until the server restarts. */
    private val plainOnly = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override fun synthesize(text: String, voice: String, engine: String): ByteArray = sayMarkedUp(text, engine, plainOnly) { words, type ->
        polly.synthesizeSpeechAsBytes(
            SynthesizeSpeechRequest.builder().text(words).textType(type).voiceId(voice).engine(engine).outputFormat("mp3").build(),
        ).asByteArray()
    }

    /**
     * Polly's viseme speech marks for the same words, marked up the same way as the clip so the timings match. The
     * generative engine gives no speech marks, so its clips borrow the neural engine's marks for the same voice (the
     * service then stretches them to the clip's length: [SpeechService.fitMarks]).
     */
    override fun visemes(text: String, voice: String, engine: String): List<SpeechCodec.Mark> {
        val marksEngine = if (engine == SpeechService.GENERATIVE) SpeechService.NEURAL else engine
        val bytes = sayMarkedUp(text, marksEngine, plainOnly) { words, type ->
            polly.synthesizeSpeechAsBytes(
                SynthesizeSpeechRequest.builder().text(words).textType(type).voiceId(voice).engine(marksEngine)
                    .outputFormat(OutputFormat.JSON).speechMarkTypes(SpeechMarkType.VISEME).build(),
            ).asByteArray()
        }
        return parseMarks(bytes.decodeToString())
    }

    companion object {
        /**
         * Polly's speech marks (one JSON object a line: `{"time":62,"type":"viseme","start":0,"end":3,"value":"p"}`) →
         * marks: viseme lines only, with a known viseme and a time ≥ 0; anything else is skipped.
         */
        fun parseMarks(lines: String): List<SpeechCodec.Mark> = lines.lineSequence().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(line) as? kotlinx.serialization.json.JsonObject }.getOrNull() ?: return@mapNotNull null
            fun str(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            if (str("type") != "viseme") return@mapNotNull null
            val t = (o["time"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull() ?: return@mapNotNull null
            val v = str("value")?.takeIf { it in SpeechCodec.POLLY_VISEMES } ?: return@mapNotNull null
            if (t < 0) null else SpeechCodec.Mark(t, v)
        }.take(SpeechCodec.MAX_MARKS * 2).toList()

        /**
         * Talking to Ada like a conversation, slice 7 (speech that sounds alive): [text] said as SSML
         * ([os.meka.core.domain.SpokenSsmlRules]: a small pause after an opening reaction, a touch quicker) through
         * [call] (words, "ssml" or "text"). If the engine refuses the SSML the same words are said plainly at once, and
         * that engine is kept in [plainOnly] so it isn't asked with SSML again; a plain failure is thrown as before.
         */
        fun sayMarkedUp(text: String, engine: String, plainOnly: MutableSet<String>, call: (String, String) -> ByteArray): ByteArray {
            if (engine in plainOnly) return call(text, "text")
            val marked = runCatching { call(os.meka.core.domain.SpokenSsmlRules.ssml(text), "ssml") }
            marked.getOrNull()?.let { return it }
            val plain = call(text, "text")
            plainOnly += engine
            return plain
        }
    }
}

/**
 * Google Cloud Text-to-Speech's **Chirp 3: HD** voices (natural voice, slice 1; Meka 2026-10-14, Needs Meka #33): the
 * British voices ("en-GB-Chirp3-HD-Aoede" → id "Aoede") and the American ones ("en-US-Chirp3-HD-Aoede" → "Aoede-US"),
 * MP3, plain text (Chirp 3 HD takes no SSML). The API key goes in a header, never a URL or a log; only MEKA's own words
 * are sent. Free for the first 1M characters a month; [SpeechService] stops using it at 900k.
 */
class GoogleSpeech internal constructor(
    private val key: () -> String?,
    /** GETs with headers; returns (status, body). */
    private val get: (url: String, headers: Map<String, String>) -> Pair<Int, String>,
    /** POSTs JSON with headers; returns (status, body). */
    private val post: (url: String, headers: Map<String, String>, body: String) -> Pair<Int, String>,
) : SpeechEngine {
    override fun configured(): Boolean = key() != null

    /** [language] is ignored: both groups (en-GB, then en-US) are always listed. */
    override fun voices(language: String): List<SpeechVoice> {
        val k = key() ?: error("no key")
        return LANGUAGES.flatMap { lang ->
            val (status, body) = get("$BASE/voices?languageCode=$lang", mapOf(KEY_HEADER to k))
            check(status == 200) { "voices $status" }
            parseVoices(body, lang)
        }
    }

    /** One British voice list over the clips' own client; no key: nothing is sent. */
    override fun touch(): Boolean {
        val k = key() ?: return false
        return get("$BASE/voices?languageCode=en-GB", mapOf(KEY_HEADER to k)).first == 200
    }

    override fun synthesize(text: String, voice: String, engine: String): ByteArray {
        val k = key() ?: error("no key")
        val name = fullName(voice) ?: error("not a Google voice")
        val (status, body) = post("$BASE/text:synthesize", mapOf(KEY_HEADER to k), request(text, name))
        check(status == 200) { "synthesize $status${errorWords(body)?.let { " $it" } ?: ""}" }
        return parseAudio(body) ?: error("no audio")
    }

    companion object {
        const val BASE = "https://texttospeech.googleapis.com/v1"
        const val KEY_HEADER = "X-Goog-Api-Key"
        const val ENGINE = "chirp3hd"
        val LANGUAGES = listOf("en-GB", "en-US")
        private val NAME = Regex("(en-GB|en-US)-Chirp3-HD-([A-Z][a-z]{1,19})")
        private const val MAX_BYTES = 3_000_000

        /** "Aoede" → "en-GB-Chirp3-HD-Aoede", "Aoede-US" → "en-US-Chirp3-HD-Aoede"; null for anything else. */
        fun fullName(id: String): String? {
            val us = id.endsWith("-US")
            val short = id.removeSuffix("-US").takeIf { Regex("[A-Z][a-z]{1,19}").matches(it) } ?: return null
            return "${if (us) "en-US" else "en-GB"}-Chirp3-HD-$short"
        }

        /** The Chirp 3 HD voices for [lang] in a `voices` answer, as ids ("Aoede", "Aoede-US"). */
        fun parseVoices(body: String, lang: String): List<SpeechVoice> {
            if (body.length > MAX_BYTES) return emptyList()
            val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject }.getOrNull()
                ?: return emptyList()
            val list = o["voices"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
            return list.mapNotNull { e ->
                val v = e as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val name = (v["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
                val m = NAME.matchEntire(name)?.takeIf { it.groupValues[1] == lang } ?: return@mapNotNull null
                val gender = when ((v["ssmlGender"] as? kotlinx.serialization.json.JsonPrimitive)?.content) {
                    "FEMALE" -> "Female"
                    "MALE" -> "Male"
                    else -> ""
                }
                val id = m.groupValues[2] + if (lang == "en-US") "-US" else ""
                SpeechVoice(id, gender, setOf(ENGINE))
            }
        }

        fun request(text: String, name: String): String = kotlinx.serialization.json.buildJsonObject {
            put("input", kotlinx.serialization.json.buildJsonObject { put("text", kotlinx.serialization.json.JsonPrimitive(text)) })
            put(
                "voice",
                kotlinx.serialization.json.buildJsonObject {
                    put("languageCode", kotlinx.serialization.json.JsonPrimitive(name.take(5)))
                    put("name", kotlinx.serialization.json.JsonPrimitive(name))
                },
            )
            put("audioConfig", kotlinx.serialization.json.buildJsonObject { put("audioEncoding", kotlinx.serialization.json.JsonPrimitive("MP3")) })
        }.toString()

        /**
         * Google's own words for a refusal, for the deploy report: the error's status and the first detail's reason
         * ("PERMISSION_DENIED API_KEY_SERVICE_BLOCKED"); never its free-text message (it can name the project). Null
         * when the body isn't Google's error shape.
         */
        fun errorWords(body: String): String? {
            if (body.length > MAX_BYTES) return null
            val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject }.getOrNull() ?: return null
            val err = o["error"] as? kotlinx.serialization.json.JsonObject ?: return null
            val word = Regex("[A-Z][A-Z_]{1,60}")
            val status = (err["status"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { word.matches(it) }
            val reason = (err["details"] as? kotlinx.serialization.json.JsonArray)?.firstNotNullOfOrNull { d ->
                ((d as? kotlinx.serialization.json.JsonObject)?.get("reason") as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { word.matches(it) }
            }
            return listOfNotNull(status, reason).joinToString(" ").takeIf { it.isNotEmpty() }
        }

        /** The MP3 in a `text:synthesize` answer, or null. */
        fun parseAudio(body: String): ByteArray? {
            if (body.length > MAX_BYTES) return null
            val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject }.getOrNull() ?: return null
            val b64 = (o["audioContent"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            return runCatching { Base64.getDecoder().decode(b64) }.getOrNull()?.takeIf { it.isNotEmpty() }
        }

        private val client = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(4))
            .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build()

        fun httpGet(url: String, headers: Map<String, String>): Pair<Int, String> {
            val req = java.net.http.HttpRequest.newBuilder(java.net.URI(url)).timeout(java.time.Duration.ofSeconds(8))
                .apply { headers.forEach { (k, v) -> header(k, v) } }.GET().build()
            val res = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
            return res.statusCode() to res.body()
        }

        /** A clip that takes longer than 5 s is given up on, so Polly can still say it inside the device's wait. */
        fun httpPost(url: String, headers: Map<String, String>, body: String): Pair<Int, String> {
            val req = java.net.http.HttpRequest.newBuilder(java.net.URI(url)).timeout(java.time.Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build()
            val res = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
            return res.statusCode() to res.body()
        }

        /**
         * The key from its own secret ([speechSecret], `meka-os-dev/speech/google`), else the travel times' Google key
         * ([mapsSecret]; the same key can be allowed for both APIs). Read at use time through SecretCache (15 minutes while unset, so pasting the key
         * needs no restart); `{}` or absent = no Google voices.
         */
        fun fromSecrets(speechSecret: String?, mapsSecret: String?): GoogleSpeech {
            val cache = os.meka.backend.integrations.SecretCache.shared
            fun read(id: String?): String? = id?.takeIf { it.isNotBlank() }?.let { s -> cache.read(s) { os.meka.backend.integrations.GoogleMapsKey.parse(it) } }
            val key = { read(speechSecret) ?: read(mapsSecret) }
            return GoogleSpeech(key, ::httpGet, ::httpPost)
        }
    }
}

/**
 * Amazon Polly's list prices (eu-west-2, 2026), in micro-dollars a character: generative $30, neural $16 and standard $4
 * per million characters. Any AWS free tier is not taken off, so the budget never undercounts.
 */
object PollyPrices {
    const val GENERATIVE_MICRO_USD = 30L
    const val NEURAL_MICRO_USD = 16L
    const val STANDARD_MICRO_USD = 4L

    fun microUsd(engine: String, chars: Long): Long = chars.coerceAtLeast(0) * when (engine) {
        SpeechService.GENERATIVE -> GENERATIVE_MICRO_USD
        SpeechService.NEURAL -> NEURAL_MICRO_USD
        else -> STANDARD_MICRO_USD
    }
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

/** Polly's meter in `speech_usage`; Google's in `speech_usage_google` (V26, the same shape). */
class PostgresSpeechUsageStore(private val ds: DataSource, private val table: String = "speech_usage") : SpeechUsageStore {
    init {
        require(table in TABLES) { "table" }
    }

    override fun add(month: String, chars: Long) = ds.connection.use { c ->
        c.prepareStatement(
            """INSERT INTO $table(month, clips, chars) VALUES (?,1,?)
               ON CONFLICT (month) DO UPDATE SET clips = $table.clips + 1, chars = $table.chars + EXCLUDED.chars, updated_at = now()""",
        ).use { it.setString(1, month); it.setLong(2, chars); it.executeUpdate() }
        Unit
    }

    override fun used(month: String): Long = ds.connection.use { c ->
        c.prepareStatement("SELECT chars FROM $table WHERE month = ?").use { st ->
            st.setString(1, month)
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }
    }

    companion object {
        val TABLES = setOf("speech_usage", "speech_usage_google")
    }
}

/**
 * Chooses the voice and engine, meters the month and says MEKA's words. The voice list is read from Polly at most
 * every [voicesForMs] (six hours; a failure is tried again on the next request). Only British English voices are
 * offered from Polly, each on its best engine here (generative, else neural; standard-only voices aren't natural enough
 * to offer), best first: generative before neural, then [PREFERRED], then by name. The first Polly voice is the default.
 *
 * With [google] (natural voice, slice 1) Google's Chirp 3 HD voices come first in the list (British, then American),
 * with their own meter ([googleUsage]): a Google voice speaks while the month's Google characters stay under
 * [googleFallbackAt] (900k of the free 1M, so it is never paid without Meka's yes); past it, or when Google fails or is
 * slow, the same words are said once by the default Polly voice (and [googleFailedAtMs] is kept for the picker's line).
 * Polly's own cap ([capChars]) counts only what Polly says.
 */
class SpeechService(
    private val engine: SpeechEngine?,
    private val usage: SpeechUsageStore,
    val capChars: Long = CAP_CHARS,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val voicesForMs: Long = 6 * 3_600_000L,
    private val google: SpeechEngine? = null,
    private val googleUsage: SpeechUsageStore = InMemorySpeechUsageStore(),
    val googleFallbackAt: Long = GOOGLE_FALLBACK_AT,
    /**
     * The AI layer's monthly budget (voice completeness, 2026-10-19: "Polly cost counted in the AI budget"): each Polly
     * clip is counted in it as [VOICE_FEATURE] at [PollyPrices]' list price, and once the month's budget is spent Polly
     * answers [SpeechCodec.Response.OVER] (the device's own voice speaks until the 1st). Google's clips stay inside its
     * free tier, so they cost nothing and aren't counted. Null (no AI layer): Polly's own [capChars] only, as before.
     */
    private val budget: AiBudget? = null,
) {
    private var cached: Pair<Long, List<SpeechCodec.Voice>>? = null
    private var googleCached: Pair<Long, List<SpeechCodec.Voice>>? = null
    @Volatile private var googleReachable = true

    /** When a Google clip last failed and Polly said it instead (null: not since the server started). */
    @Volatile var googleFailedAtMs: Long? = null
        private set

    /** Google's voice latch: after a failure (or two timeouts in a row) Polly speaks for at least 15 minutes. */
    @Volatile private var googleLatch = EngineLatch()
    private val latchLock = Any()

    fun month(): String = Instant.ofEpochMilli(nowMs()).atOffset(ZoneOffset.UTC).let { "%04d-%02d".format(it.year, it.monthValue) }

    /**
     * The offered voices: Google's first (when a key is in place and Google answered), then Polly's (best first).
     * Throws when Polly can't be reached and nothing is cached.
     */
    @Synchronized
    fun offered(): List<SpeechCodec.Voice> {
        val polly = pollyOffered()
        return googleOffered() + polly
    }

    @Synchronized
    private fun pollyOffered(): List<SpeechCodec.Voice> {
        val e = engine ?: return emptyList()
        cached?.let { (at, v) -> if (nowMs() - at < voicesForMs) return v }
        val v = rank(e.voices(LANGUAGE))
        cached = nowMs() to v
        return v
    }

    /** Google's voices, or none (no key, or Google unreachable: tried again on the next request). Never throws. */
    @Synchronized
    private fun googleOffered(): List<SpeechCodec.Voice> {
        val g = google ?: return emptyList()
        if (!runCatching { g.configured() }.getOrDefault(false)) { googleCached = null; return emptyList() }
        googleCached?.let { (at, v) -> if (nowMs() - at < voicesForMs) return v }
        val v = runCatching { rankGoogle(g.voices(LANGUAGE)) }.getOrElse { googleReachable = false; return emptyList() }
        googleReachable = true
        googleCached = nowMs() to v
        return v
    }

    /** The default voice: Polly's first (a Google voice is only ever used when chosen). */
    private fun defaultOf(list: List<SpeechCodec.Voice>): SpeechCodec.Voice? = list.firstOrNull { it.provider == SpeechCodec.POLLY }

    private fun googleInfo(m: String): SpeechCodec.Google {
        val g = google
        if (g == null || !runCatching { g.configured() }.getOrDefault(false)) return SpeechCodec.Google(SpeechCodec.Google.NO_KEY)
        val used = runCatching { googleUsage.used(m) }.getOrDefault(0)
        val state = if (googleReachable && googleCached?.second?.isNotEmpty() == true) SpeechCodec.Google.ON else SpeechCodec.Google.FAILED
        return SpeechCodec.Google(state, used, GOOGLE_FREE, googleFallbackAt, googleFailedAtMs)
    }

    fun voices(): SpeechCodec.Voices {
        if (engine == null) return SpeechCodec.Voices(SpeechCodec.Voices.OFF, reason = "Navilife's voice isn't set up on this server")
        val m = month()
        val used = runCatching { usage.used(m) }.getOrDefault(0)
        val list = runCatching { offered() }.getOrElse {
            return SpeechCodec.Voices(SpeechCodec.Voices.FAILED, month = m, usedChars = used, capChars = capChars, reason = UNREACHABLE)
        }
        val default = defaultOf(list)
            ?: return SpeechCodec.Voices(SpeechCodec.Voices.FAILED, month = m, usedChars = used, capChars = capChars, reason = "Amazon Polly has no British voice here")
        return SpeechCodec.Voices(SpeechCodec.Voices.ON, list, default.id, m, used, capChars, google = googleInfo(m))
    }

    /** What [say] did: the answer, and the clip itself as raw MP3 when something was said. */
    class Said(val response: SpeechCodec.Response, val mp3: ByteArray? = null) {
        /** Obi's face, slice 2: the clip's mouth sounds ([SpeechCodec.encodeMarks]), when asked for and given. */
        val visemes: String? get() = response.visemes
    }

    /**
     * Says [r]'s words, metered. A spoken [Said] carries the MP3 bytes (the route sends them raw to a device that asked
     * for [SpeechCodec.Request.binary]) and a response without audio; every other answer has no bytes. A chosen Google
     * voice that can't speak (past the free tier's 900k, failed, slow) hands the words to the default Polly voice.
     */
    fun say(r: SpeechCodec.Request): Said {
        val e = engine ?: return Said(SpeechCodec.Response(SpeechCodec.Response.OFF, reason = "Navilife's voice isn't set up on this server"))
        val m = month()
        val chars = r.text.length.toLong()
        val list = runCatching { offered() }.getOrElse { return Said(SpeechCodec.Response(SpeechCodec.Response.FAILED, reason = UNREACHABLE)) }
        val default = defaultOf(list) ?: return Said(SpeechCodec.Response(SpeechCodec.Response.FAILED, reason = "Amazon Polly has no British voice here"))
        val wanted = list.firstOrNull { it.id.equals(r.voice, ignoreCase = true) } ?: default
        val g = google
        if (wanted.provider == SpeechCodec.GOOGLE && g != null) {
            val usedGoogle = runCatching { googleUsage.used(m) }.getOrDefault(Long.MAX_VALUE / 2)
            if (usedGoogle + chars <= googleFallbackAt) {
                val audio = latchedGoogle(g, r.text, wanted)
                if (audio != null) {
                    runCatching { googleUsage.add(m, chars) }
                    return Said(SpeechCodec.Response(SpeechCodec.Response.SPOKEN, null, "mp3", wanted.id, wanted.engine), audio)
                }
            }
        }
        val voice = if (wanted.provider == SpeechCodec.POLLY) wanted else default
        if (usage.used(m) + chars > capChars) return Said(SpeechCodec.Response(SpeechCodec.Response.OVER, reason = OVER))
        if (budget?.over() == true) return Said(SpeechCodec.Response(SpeechCodec.Response.OVER, reason = BUDGET_OVER))
        // Obi's face, slice 2: the mouth sounds asked for alongside the clip, at the same time, so they add no wait.
        val marking = if (r.visemes && usage.used(m) + 2 * chars <= capChars) {
            java.util.concurrent.CompletableFuture.supplyAsync { e.visemes(r.text, voice.id, voice.engine) }
        } else null
        val audio = runCatching { e.synthesize(r.text, voice.id, voice.engine) }.getOrElse {
            marking?.cancel(false)
            return Said(SpeechCodec.Response(SpeechCodec.Response.FAILED, reason = UNREACHABLE))
        }
        if (audio.isEmpty()) return Said(SpeechCodec.Response(SpeechCodec.Response.FAILED, reason = "Amazon Polly said nothing"))
        if (audio.size > SpeechCodec.MAX_AUDIO_BYTES) return Said(SpeechCodec.Response(SpeechCodec.Response.FAILED, reason = "Amazon Polly's clip was too long"))
        usage.add(m, chars)
        meterPolly(chars, voice.engine)
        val visemes = marking?.let { f -> markedUp(f, chars, voice.engine, audio) }
        return Said(SpeechCodec.Response(SpeechCodec.Response.SPOKEN, null, "mp3", voice.id, voice.engine, visemes = visemes), audio)
    }

    /**
     * The mouth sounds from [f] (waited for within [MARKS_WAIT_MS]; the clip never waits longer), metered like a clip
     * (Polly bills speech marks by the character, at the neural price when they were borrowed for a generative clip),
     * fitted to [audio]'s length when they came from another engine. Null when Polly gave none in time. Never throws.
     */
    private fun markedUp(f: java.util.concurrent.CompletableFuture<List<SpeechCodec.Mark>>, chars: Long, engine: String, audio: ByteArray): String? {
        val marks = runCatching { f.get(MARKS_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrElse { f.cancel(false); null }
            ?: return null
        if (marks.isEmpty()) return null
        runCatching { usage.add(month(), chars) }
        meterPolly(chars, if (engine == GENERATIVE) NEURAL else engine)
        val fitted = if (engine == GENERATIVE) Mp3Length.ms(audio)?.let { fitMarks(marks, it) } ?: marks else marks
        return SpeechCodec.encodeMarks(fitted)
    }

    /**
     * [text] in the Google voice [voiceId] only (natural voice, slice 3: the call assistant's lines), metered on Google's
     * meter. Null — and the caller says it with Polly — when that voice isn't offered, the month is past
     * [googleFallbackAt], or Google fails or is slow (then [googleFailedAtMs] is kept, as for [say]). Never throws.
     */
    fun googleClip(text: String, voiceId: String): ByteArray? {
        val g = google ?: return null
        val wanted = runCatching { offered() }.getOrNull()
            ?.firstOrNull { it.provider == SpeechCodec.GOOGLE && it.id.equals(voiceId, ignoreCase = true) } ?: return null
        val m = month()
        val chars = text.length.toLong()
        val used = runCatching { googleUsage.used(m) }.getOrDefault(Long.MAX_VALUE / 2)
        if (used + chars > googleFallbackAt) return null
        val audio = latchedGoogle(g, text, wanted) ?: return null
        runCatching { googleUsage.add(m, chars) }
        return audio
    }

    /**
     * Google's clip of [text] in [voice] through the latch (small refinement, Meka 2026-10-19: "engine fallback latches
     * for the session"): while Google is switched off ([EngineLatchRules.useFallback]: a failure, or two timeouts in a
     * row, in the last 15 minutes) Google isn't asked and null comes back at once, so Polly says it with no wait; after
     * the hold the next clip quietly asks Google again. A failure keeps [googleFailedAtMs]. Never throws.
     */
    private fun latchedGoogle(g: SpeechEngine, text: String, voice: SpeechCodec.Voice): ByteArray? {
        if (EngineLatchRules.useFallback(googleLatch, nowMs())) return null
        val tried = runCatching { g.synthesize(text, voice.id, voice.engine) }
        val audio = tried.getOrNull()?.takeIf { it.isNotEmpty() && it.size <= SpeechCodec.MAX_AUDIO_BYTES }
        synchronized(latchLock) {
            val now = nowMs()
            googleLatch = if (audio != null) EngineLatchRules.worked(googleLatch, now)
            else EngineLatchRules.failed(googleLatch, now, timeout = isTimeout(tried.exceptionOrNull()))
        }
        if (audio == null) googleFailedAtMs = nowMs()
        return audio
    }

    /**
     * The orb speed pass, slice 5: warms the voice the next clip will most likely come from ([SpeechEngine.touch]):
     * Google's while it has a key, answers and isn't resting after a failure, else Polly's. Nothing is said or counted.
     * True when it answered. Never throws.
     */
    fun touch(): Boolean {
        val g = google
        val googleFirst = g != null && googleReachable && runCatching { g.configured() }.getOrDefault(false) &&
            !EngineLatchRules.useFallback(googleLatch, nowMs())
        val e = if (googleFirst) g else engine
        return e != null && runCatching { e.touch() }.getOrDefault(false)
    }

    /** Whether Polly has the Google voice's job now (Google is resting after a failure). */
    fun googleSwitched(): Boolean = googleLatch.switched

    /** How long Polly has stood in for Google (now, or the last time); null when it never has since the start. */
    fun googleSwitchedForMs(): Long? = EngineLatchRules.switchedForMs(googleLatch, nowMs())

    /** Google's state for Health: [SpeechCodec.Google.ON] · [SpeechCodec.Google.FAILED] · null (no key). Never throws. */
    fun googleState(): String? = runCatching { runCatching { offered() }; googleInfo(month()).state }.getOrNull()?.takeIf { it != SpeechCodec.Google.NO_KEY }

    /**
     * The deploy report's "Navilife's voice" line (the Google key, 2026-10-17; counts only): whether Google's voices are
     * listed and how many, a probe clip of [PROBE_LINE] in the first British one (a few metered characters on Google's
     * free tier, skipped past [googleFallbackAt]), this month's Google characters, and Polly's state. Never throws.
     */
    fun report(): String {
        val polly = if (engine == null) "Polly off" else runCatching { pollyOffered().size }.fold({ "Polly $it voices" }, { "Polly unreachable" })
        val g = google
        if (g == null || !runCatching { g.configured() }.getOrDefault(false)) return "Google voice off · no key · $polly"
        refreshVoices()
        val list = runCatching { googleOffered() }.getOrDefault(emptyList())
        if (list.isEmpty()) return "Google voice: key read but no voices listed (API off or not allowed on the key?) · $polly"
        val british = list.count { !it.id.endsWith("-US") }
        val m = month()
        val voice = list.firstOrNull { !it.id.endsWith("-US") } ?: list.first()
        val meter = runCatching { googleUsage.used(m) }
        val probe = if (meter.isFailure) {
            // Without the meter Navilife never uses Google (it can't prove the free tier isn't passed).
            "probe clip skipped · Google's meter can't be read (${meter.exceptionOrNull()?.let { it::class.simpleName }})"
        } else if (meter.getOrThrow() + PROBE_LINE.length > googleFallbackAt) {
            "probe clip skipped · past the free tier's limit"
        } else {
            // Straight to Google (not googleClip) so a refusal's reason reaches the report.
            val started = System.nanoTime()
            runCatching { g.synthesize(PROBE_LINE, voice.id, voice.engine) }.fold(
                { clip ->
                    runCatching { googleUsage.add(m, PROBE_LINE.length.toLong()) }
                    "probe clip ${(clip.size + 1023) / 1024} KB in ${(System.nanoTime() - started) / 1_000_000} ms"
                },
                { e -> googleFailedAtMs = nowMs(); "probe clip failed (${e::class.simpleName}: ${e.message?.take(80)})" },
            )
        }
        val used = runCatching { googleUsage.used(m) }.getOrNull() ?: return "Google voice: ${list.size} Chirp 3 HD voices ($british British) · $probe · $polly"
        return "Google voice on · ${list.size} Chirp 3 HD voices ($british British) · $probe · ${(used + 999) / 1000}k of 1M characters in $m · $polly"
    }

    /** [say] as the JSON answer (audio as base64), for devices that didn't ask for raw MP3. */
    fun speak(r: SpeechCodec.Request): SpeechCodec.Response {
        val said = say(r)
        val mp3 = said.mp3 ?: return said.response
        return said.response.copy(audio = Base64.getEncoder().encodeToString(mp3))
    }

    /**
     * Keeps the first words quick (voice slice 2, cutting latency). Called once at start: reads the voice list and says
     * [WARM_LINE] (a few metered characters, never sent anywhere), so the first real request doesn't pay for the SDK's
     * first use, DescribeVoices and a new connection to Polly. Then [refreshVoices] every [REFRESH_MS] keeps the list
     * fresh off the request path. Never throws.
     */
    fun warm() {
        val e = engine ?: return
        val voice = runCatching { offered() }.getOrNull()?.let(::defaultOf) ?: return
        val m = month()
        if (runCatching { usage.used(m) }.getOrDefault(capChars) + WARM_LINE.length > capChars) return
        if (runCatching { e.synthesize(WARM_LINE, voice.id, voice.engine) }.isSuccess) {
            runCatching { usage.add(m, WARM_LINE.length.toLong()) }
            meterPolly(WARM_LINE.length.toLong(), voice.engine)
        }
    }

    /** One Polly clip in the AI budget at list price; a meter failure never stops the voice. */
    private fun meterPolly(chars: Long, engine: String) {
        val b = budget ?: return
        runCatching { b.recordCost(VOICE_FEATURE, PollyPrices.microUsd(engine, chars)) }
    }

    /** Reads the voice lists again now (off the request path), keeping the old ones if a provider can't be reached. */
    @Synchronized
    fun refreshVoices() {
        val e = engine ?: return
        runCatching { rank(e.voices(LANGUAGE)) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { cached = nowMs() to it }
        val g = google ?: return
        if (!runCatching { g.configured() }.getOrDefault(false)) return
        runCatching { rankGoogle(g.voices(LANGUAGE)) }.onSuccess { googleReachable = true }.getOrNull()
            ?.takeIf { it.isNotEmpty() }?.let { googleCached = nowMs() to it }
    }

    companion object {
        const val LANGUAGE = "en-GB"
        const val CAP_CHARS = 1_000_000L
        /** A timeout anywhere in [e]'s causes (Google too slow) rather than a refusal or a broken answer. */
        fun isTimeout(e: Throwable?): Boolean = generateSequence(e) { it.cause }.take(8)
            .any { it is java.net.http.HttpTimeoutException || it is java.net.SocketTimeoutException || it is java.util.concurrent.TimeoutException }

        /** Google's free tier for Chirp 3 HD (1M characters a month); Navilife stops at [GOOGLE_FALLBACK_AT]. */
        const val GOOGLE_FREE = 1_000_000L
        const val GOOGLE_FALLBACK_AT = 900_000L
        /** What the deploy report's probe clip says (sent only to Google, never played). */
        const val PROBE_LINE = "Navilife voice check."
        /** How long a clip's mouth sounds may lag the clip itself before they're left out (Obi's face, slice 2). */
        const val MARKS_WAIT_MS = 1_500L

        /**
         * Marks borrowed from another engine stretched to a clip [clipMs] long: the last mark (Polly ends with the
         * closing "sil") lands [TAIL_MS] before the clip's end, every other in proportion. Unchanged when they can't be
         * fitted (no span, or a clip shorter than the tail).
         */
        fun fitMarks(marks: List<SpeechCodec.Mark>, clipMs: Long): List<SpeechCodec.Mark> {
            val span = marks.maxOfOrNull { it.atMs } ?: return marks
            val target = clipMs - TAIL_MS
            if (span <= 0 || target <= 0) return marks
            return marks.map { it.copy(atMs = it.atMs * target / span) }
        }

        /** The quiet Polly leaves after the last sound of a clip, roughly. */
        const val TAIL_MS = 60L

        const val GENERATIVE = "generative"
        const val NEURAL = "neural"
        /** Meka's shortlist (build plan): these lead the list on the same engine. */
        val PREFERRED = listOf("Amy", "Emma", "Brian", "Arthur")
        /** Google voices that lead their group (the build plan's example first). */
        val PREFERRED_GOOGLE = listOf("Aoede", "Kore", "Leda", "Charon", "Puck", "Orus")
        const val UNREACHABLE = "Couldn't reach Amazon Polly"
        const val OVER = "This month's voice is used up; Navilife's own voice speaks until the 1st"
        const val BUDGET_OVER = "This month's AI budget is used up; the device's own voice speaks until the 1st"
        /** The AI budget's feature for Navilife's voice (the apps show it as Voice, counted in clips). */
        const val VOICE_FEATURE = "voice.polly"
        /** What [warm] says once at start (metered like any clip). */
        const val WARM_LINE = "Hello."
        /** How often the background refresh reads the voice list (well inside the six hours a request would wait). */
        const val REFRESH_MS = 30 * 60_000L

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

        /** Google's voices: British before American, then [PREFERRED_GOOGLE], then by name. */
        fun rankGoogle(voices: List<SpeechVoice>): List<SpeechCodec.Voice> = voices
            .filter { Regex("[A-Z][a-z]{1,19}(-US)?").matches(it.id) && GoogleSpeech.ENGINE in it.engines }
            .map { SpeechCodec.Voice(it.id, it.gender, GoogleSpeech.ENGINE, SpeechCodec.GOOGLE) }
            .distinctBy { it.id }
            .sortedWith(
                compareBy<SpeechCodec.Voice>(
                    { if (it.id.endsWith("-US")) 1 else 0 },
                    { PREFERRED_GOOGLE.indexOf(it.id.removeSuffix("-US")).let { i -> if (i < 0) PREFERRED_GOOGLE.size else i } },
                    { it.id },
                ),
            )
    }
}

/** Warms MEKA's voice shortly after start, then refreshes the voice list every [SpeechService.REFRESH_MS]. */
fun startSpeechWarm(speech: SpeechService, periodMs: Long = SpeechService.REFRESH_MS) {
    Thread.ofVirtual().name("speech-warm").start {
        Thread.sleep(5_000)
        speech.warm()
        while (true) {
            Thread.sleep(periodMs)
            speech.refreshVoices()
        }
    }
}

/**
 * Null unless the deployment turns Polly on (`MEKA_SPEECH_ENGINE=polly`) and has a database for the meter. Google's
 * voices come with it when a Google key is in place (its own secret, else the travel times' key; read at use time).
 */
fun speechFromEnv(ds: DataSource, budget: AiBudget? = null): SpeechService? {
    if (System.getenv("MEKA_SPEECH_ENGINE")?.trim() != "polly") return null
    val speechSecret = System.getenv("MEKA_SPEECH_GOOGLE_SECRET")?.takeIf { it.isNotBlank() }
    val mapsSecret = System.getenv("MEKA_MAPS_GOOGLE_SECRET")?.takeIf { it.isNotBlank() }
    val google = if (speechSecret != null || mapsSecret != null) GoogleSpeech.fromSecrets(speechSecret, mapsSecret) else null
    return SpeechService(
        PollySpeech(), PostgresSpeechUsageStore(ds),
        google = google, googleUsage = PostgresSpeechUsageStore(ds, "speech_usage_google"),
        budget = budget,
    )
}

/**
 * How long an MP3 clip plays (Obi's face, slice 2: fitting borrowed mouth sounds to a generative clip): the MPEG audio
 * frames counted from their headers, after any ID3 tag. Null when the bytes aren't MP3 frames.
 */
object Mp3Length {
    private val BITRATES_V1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
    private val BITRATES_V2_L3 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
    private val RATES_V1 = intArrayOf(44_100, 48_000, 32_000, 0)

    fun ms(bytes: ByteArray): Long? {
        var i = 0
        if (bytes.size >= 10 && bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) {
            val size = (bytes[6].toInt() and 0x7f shl 21) or (bytes[7].toInt() and 0x7f shl 14) or (bytes[8].toInt() and 0x7f shl 7) or (bytes[9].toInt() and 0x7f)
            i = 10 + size
        }
        var samples = 0L
        var rate = 0
        var frames = 0
        while (i + 4 <= bytes.size) {
            val b1 = bytes[i].toInt() and 0xff
            val b2 = bytes[i + 1].toInt() and 0xff
            val b3 = bytes[i + 2].toInt() and 0xff
            if (b1 != 0xff || b2 and 0xe0 != 0xe0) { if (frames == 0) { i++; continue } else break }
            val version = b2 shr 3 and 3 // 3 = MPEG1, 2 = MPEG2, 0 = MPEG2.5
            val layer = b2 shr 1 and 3 // 1 = Layer III
            if (version == 1 || layer != 1) { if (frames == 0) { i++; continue } else break }
            val mpeg1 = version == 3
            val kbps = (if (mpeg1) BITRATES_V1_L3 else BITRATES_V2_L3)[b3 shr 4]
            val base = RATES_V1[b3 shr 2 and 3]
            if (kbps == 0 || base == 0) { if (frames == 0) { i++; continue } else break }
            val sr = when (version) { 3 -> base; 2 -> base / 2; else -> base / 4 }
            val pad = b3 shr 1 and 1
            val len = (if (mpeg1) 144 else 72) * kbps * 1000 / sr + pad
            if (len < 4) break
            samples += if (mpeg1) 1152 else 576
            rate = sr
            frames++
            i += len
        }
        if (frames == 0 || rate == 0) return null
        return samples * 1000 / rate
    }
}
