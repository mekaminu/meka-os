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

    /** Whether the engine can be asked at all (Google: a key is in place). */
    fun configured(): Boolean = true
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

    override fun synthesize(text: String, voice: String, engine: String): ByteArray {
        val k = key() ?: error("no key")
        val name = fullName(voice) ?: error("not a Google voice")
        val (status, body) = post("$BASE/text:synthesize", mapOf(KEY_HEADER to k), request(text, name))
        check(status == 200) { "synthesize $status" }
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
         * ([mapsSecret]; the same key can be allowed for both APIs). Read at use time, cached a minute (pasting the key
         * needs no restart); `{}` or absent = no Google voices.
         */
        fun fromSecrets(speechSecret: String?, mapsSecret: String?): GoogleSpeech {
            val sm = software.amazon.awssdk.services.secretsmanager.SecretsManagerClient.builder()
                .httpClient(UrlConnectionHttpClient.create()).build()
            var cached: Pair<Long, String?>? = null
            val lock = Any()
            fun read(id: String?): String? = id?.takeIf { it.isNotBlank() }?.let { s ->
                runCatching { os.meka.backend.integrations.GoogleMapsKey.parse(sm.getSecretValue { it.secretId(s) }.secretString()) }.getOrNull()
            }
            val key = {
                synchronized(lock) {
                    val hit = cached?.takeIf { System.currentTimeMillis() - it.first < 60_000 }
                    if (hit != null) hit.second else {
                        val v = read(speechSecret) ?: read(mapsSecret)
                        cached = System.currentTimeMillis() to v
                        v
                    }
                }
            }
            return GoogleSpeech(key, ::httpGet, ::httpPost)
        }
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
) {
    private var cached: Pair<Long, List<SpeechCodec.Voice>>? = null
    private var googleCached: Pair<Long, List<SpeechCodec.Voice>>? = null
    @Volatile private var googleReachable = true

    /** When a Google clip last failed and Polly said it instead (null: not since the server started). */
    @Volatile var googleFailedAtMs: Long? = null
        private set

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
    class Said(val response: SpeechCodec.Response, val mp3: ByteArray? = null)

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
                val audio = runCatching { g.synthesize(r.text, wanted.id, wanted.engine) }.getOrNull()
                    ?.takeIf { it.isNotEmpty() && it.size <= SpeechCodec.MAX_AUDIO_BYTES }
                if (audio != null) {
                    runCatching { googleUsage.add(m, chars) }
                    return Said(SpeechCodec.Response(SpeechCodec.Response.SPOKEN, null, "mp3", wanted.id, wanted.engine), audio)
                }
                googleFailedAtMs = nowMs()
            }
        }
        val voice = if (wanted.provider == SpeechCodec.POLLY) wanted else default
        if (usage.used(m) + chars > capChars) return Said(SpeechCodec.Response(SpeechCodec.Response.OVER, reason = OVER))
        val audio = runCatching { e.synthesize(r.text, voice.id, voice.engine) }.getOrElse {
            return Said(SpeechCodec.Response(SpeechCodec.Response.FAILED, reason = UNREACHABLE))
        }
        if (audio.isEmpty()) return Said(SpeechCodec.Response(SpeechCodec.Response.FAILED, reason = "Amazon Polly said nothing"))
        if (audio.size > SpeechCodec.MAX_AUDIO_BYTES) return Said(SpeechCodec.Response(SpeechCodec.Response.FAILED, reason = "Amazon Polly's clip was too long"))
        usage.add(m, chars)
        return Said(SpeechCodec.Response(SpeechCodec.Response.SPOKEN, null, "mp3", voice.id, voice.engine), audio)
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
        if (runCatching { e.synthesize(WARM_LINE, voice.id, voice.engine) }.isSuccess) runCatching { usage.add(m, WARM_LINE.length.toLong()) }
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
        /** Google's free tier for Chirp 3 HD (1M characters a month); Navilife stops at [GOOGLE_FALLBACK_AT]. */
        const val GOOGLE_FREE = 1_000_000L
        const val GOOGLE_FALLBACK_AT = 900_000L
        const val GENERATIVE = "generative"
        const val NEURAL = "neural"
        /** Meka's shortlist (build plan): these lead the list on the same engine. */
        val PREFERRED = listOf("Amy", "Emma", "Brian", "Arthur")
        /** Google voices that lead their group (the build plan's example first). */
        val PREFERRED_GOOGLE = listOf("Aoede", "Kore", "Leda", "Charon", "Puck", "Orus")
        const val UNREACHABLE = "Couldn't reach Amazon Polly"
        const val OVER = "This month's voice is used up; Navilife's own voice speaks until the 1st"
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
fun speechFromEnv(ds: DataSource): SpeechService? {
    if (System.getenv("MEKA_SPEECH_ENGINE")?.trim() != "polly") return null
    val speechSecret = System.getenv("MEKA_SPEECH_GOOGLE_SECRET")?.takeIf { it.isNotBlank() }
    val mapsSecret = System.getenv("MEKA_MAPS_GOOGLE_SECRET")?.takeIf { it.isNotBlank() }
    val google = if (speechSecret != null || mapsSecret != null) GoogleSpeech.fromSecrets(speechSecret, mapsSecret) else null
    return SpeechService(
        PollySpeech(), PostgresSpeechUsageStore(ds),
        google = google, googleUsage = PostgresSpeechUsageStore(ds, "speech_usage_google"),
    )
}
