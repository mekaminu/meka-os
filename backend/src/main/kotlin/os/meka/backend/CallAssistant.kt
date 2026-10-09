package os.meka.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import os.meka.backend.integrations.Integrations
import os.meka.core.domain.BankHolidayFields
import os.meka.core.domain.BankHolidayStore
import os.meka.core.domain.CallAssistantRules
import os.meka.core.domain.CallAssistantScript
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.HeldMessageFields
import os.meka.core.domain.MekaVoiceFields
import os.meka.core.domain.MekaVoiceRules
import os.meka.core.domain.MekaVoiceStore
import os.meka.core.domain.Urgency
import os.meka.core.domain.WorkFields
import os.meka.core.domain.WorkMode
import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.Op
import os.meka.core.sync.ServerOpStore
import os.meka.core.sync.fv
import os.meka.core.wire.SpeechCodec
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/*
 * The call assistant's voice side (build plan M1, call assistant slice 2; Needs Meka #9, approved 2026-10-07).
 *
 * A call the Fold declined at work reaches the assistant's phone number (the carrier's "forward when busy"); the phone
 * service calls these webhooks, and the server answers with Meka's fixed script: greeting (it says it is an automated
 * assistant) → record a message → "is it urgent?" → goodbye. The message lands in Meka's synced after-work summary as
 * a `held_message` written by the server (ADR-008 addendum); an urgent one wakes his devices at high priority so the
 * Fold alerts at once. The push itself carries nothing but "sync" (as every wake-up does).
 *
 * Everything the caller says is untrusted (ADR-006): the transcript is stored for display only. Each webhook must carry
 * the phone service's signature over the exact URL and form, so nobody else can put messages into the summary.
 */

/** What happened on a call, provider-neutral. */
sealed class VoiceEvent {
    abstract val callId: String

    data class Incoming(override val callId: String, val from: String?) : VoiceEvent()
    /** The message was recorded ([seconds] long; 0 when nothing was said). */
    data class Recorded(override val callId: String, val from: String?, val seconds: Int) : VoiceEvent()
    /**
     * The answer to "is it urgent?" (keypad [digits] or recognised [speech]; both empty when the caller said nothing).
     * [again] when it answers the second asking ([VoiceStep.ANSWERED_AGAIN]).
     */
    data class Answered(override val callId: String, val digits: String?, val speech: String?, val again: Boolean = false) : VoiceEvent()
    /**
     * The phone service's transcript of the message, or null when transcription failed. [recording] names the
     * recording, which the server then deletes from the phone service (the words live on only in the summary).
     */
    data class Transcribed(override val callId: String, val text: String?, val recording: String? = null) : VoiceEvent()
}

/** What the assistant does next, provider-neutral; the provider renders it as its own markup. */
sealed class VoiceReply {
    /**
     * Greet, ask for a message and record it (then [VoiceStep.RECORDED]; the transcript later to [VoiceStep.TRANSCRIBED]).
     * [atWork] picks the greeting ([CallAssistantScript.greeting]): outside work hours it doesn't say Meka is at work.
     */
    data class TakeMessage(val atWork: Boolean = true) : VoiceReply()
    /**
     * Ask "is it urgent?" (answer to [VoiceStep.ANSWERED]); [atWork] picks the goodbye if nothing is answered. [again]
     * asks once more ([CallAssistantScript.URGENT_AGAIN], answer to [VoiceStep.ANSWERED_AGAIN]) after an answer the
     * assistant didn't understand.
     */
    data class AskUrgent(val atWork: Boolean = true, val again: Boolean = false) : VoiceReply()
    data class Goodbye(val text: String) : VoiceReply()
    /** Turn the call away as busy: the assistant is switched off or nobody is set up to take it. */
    data object Busy : VoiceReply()
    /** A callback that needs no answer. */
    data object Done : VoiceReply()
}

/** The webhook paths, `/v1/voice/<provider>/<step>`. */
object VoiceStep {
    const val INCOMING = "incoming"
    const val RECORDED = "recorded"
    const val ANSWERED = "answered"
    const val ANSWERED_AGAIN = "answered-again"
    const val TRANSCRIBED = "transcribed"

    fun path(provider: String, step: String) = "/v1/voice/$provider/$step"
}

/** A phone service the assistant answers through (ADR-009). Twilio first; a fake in tests. */
interface VoiceProvider {
    val id: String

    /** Whether this request came from the phone service: its signature over [url] (exactly as called) and the form. */
    fun verify(url: String, form: Map<String, String>, header: (String) -> String?): Boolean

    /** The event a webhook [step] describes, or null for a step or form it doesn't know. */
    fun parse(step: String, form: Map<String, String>): VoiceEvent?

    /**
     * The reply as the service's markup: content type and body. [baseUrl] is the server's public URL; [voice] is MEKA's
     * voice, so callers hear the same voice Meka hears in Talk ([CallAssistant.voice]).
     */
    fun render(reply: VoiceReply, baseUrl: String, voice: CallVoice = CallVoice.DEFAULT): Pair<String, String>

    /** Deletes a recording from the phone service once it has been transcribed. Returns whether it is gone. */
    fun deleteRecording(recording: String): Boolean
}

/**
 * The Polly voice the call assistant speaks with and its engine ("generative" or "neural"): MEKA's voice (build plan V1,
 * "Weather and a voice", item 3: one voice everywhere, so callers hear the voice Meka chose).
 */
data class CallVoice(val name: String, val engine: String) {
    companion object {
        /** Before MEKA's voice is known (Polly off or unreachable, nothing chosen): Amy, the server's first choice. */
        val DEFAULT = CallVoice("Amy", SpeechService.NEURAL)

        /**
         * The voice for calls: the synced choice ([chosen], a `context_mode/voice` name) when the server offers it,
         * else the server's default (the first [offered]; also when the device's own voice is chosen, since a caller
         * can't hear Meka's phone), else the chosen name on the neural engine, else [DEFAULT].
         */
        fun choose(chosen: String?, offered: List<SpeechCodec.Voice>): CallVoice {
            val polly = MekaVoiceRules.normalize(chosen)?.takeIf { it != MekaVoiceRules.DEVICE }
            offered.firstOrNull { it.id.equals(polly, ignoreCase = true) }?.let { return CallVoice(it.id, it.engine) }
            offered.firstOrNull()?.let { return CallVoice(it.id, it.engine) }
            return polly?.let { CallVoice(it, SpeechService.NEURAL) } ?: DEFAULT
        }
    }
}

/** A field's current value (last writer wins by HLC) in a household's synced data; null when never written. */
fun interface FieldReader {
    fun latest(householdId: String, entityType: String, entityId: String, field: String): FieldValue?

    companion object {
        /** Scans the op log (tests and small stores); Postgres uses an indexed query ([PostgresOpStore.latestValue]). */
        fun scanning(ops: ServerOpStore) = FieldReader { hh, type, id, field ->
            var after = 0L
            var best: Op? = null
            while (true) {
                val page = ops.after(hh, after, 1_000)
                if (page.isEmpty()) break
                for (s in page) {
                    val op = s.op
                    if (op.entityType == type && op.entityId == id && op.field == field && (best == null || op.hlc > best.hlc)) best = op
                }
                after = page.last().seq
            }
            best?.value
        }
    }
}

/**
 * The voice flow. Webhooks may be retried by the phone service, so every write has a fixed op id per call and field
 * (`srvvoice<held id><field>`): a retry writes nothing more.
 */
class CallAssistant(
    private val ops: ServerOpStore,
    private val fields: FieldReader,
    /** The household calls belong to: the server's one household (null when there are none or several). */
    private val household: () -> String?,
    private val now: () -> Long = System::currentTimeMillis,
    /** The voices MEKA's server can speak with, best first ([SpeechService.offered]); empty while Polly is off. */
    private val speechVoices: () -> List<SpeechCodec.Voice> = { emptyList() },
    /** Something was written: wake the household's devices. */
    private val onWritten: (householdId: String) -> Unit = {},
    /** An urgent message: wake the devices now, at high priority. */
    private val onUrgent: (householdId: String) -> Unit = {},
) {
    private val clock = HlcClock(Integrations.SERVER_DEVICE, now)

    fun handle(provider: String, event: VoiceEvent): VoiceReply {
        val hh = household() ?: return if (event is VoiceEvent.Incoming) VoiceReply.Busy else VoiceReply.Done
        val id = CallAssistantRules.heldId(provider, event.callId)
        return when (event) {
            is VoiceEvent.Incoming -> if (switchedOn(hh)) VoiceReply.TakeMessage(atWork(hh)) else VoiceReply.Busy
            is VoiceEvent.Recorded -> {
                if (event.seconds < 1) return VoiceReply.Goodbye(CallAssistantScript.NO_MESSAGE)
                // A retry finds the message written: it keeps the at-work reading it was taken with.
                val atWork = if (exists(hh, id)) !away(hh, id) else atWork(hh)
                if (write(hh, id, CallAssistantRules.messageFields(event.from, now(), atWork))) runCatching { onWritten(hh) }
                VoiceReply.AskUrgent(atWork)
            }
            is VoiceEvent.Answered -> {
                val answer = CallAssistantRules.isUrgentAnswer(event.digits, event.speech)
                val known = exists(hh, id)
                // Not understood (or nothing said) the first time: ask once more rather than drop the urgency (8d).
                val askAgain = answer == null && !event.again && known
                // What it heard, in Activity, so Meka can see why a message was or wasn't marked urgent.
                if (known) logAnswer(hh, id, event, answer, askAgain)
                if (askAgain) return VoiceReply.AskUrgent(!away(hh, id), again = true)
                val urgent = answer == true
                if (urgent && known) {
                    if (write(hh, id, mapOf(HeldMessageFields.URGENT to true.fv()))) runCatching { onUrgent(hh) }
                }
                VoiceReply.Goodbye(if (urgent) CallAssistantScript.THANKS_URGENT else CallAssistantScript.thanks(!away(hh, id)))
            }
            is VoiceEvent.Transcribed -> {
                // Done on a device blanked the summary: a late transcript doesn't bring the words back.
                if (!exists(hh, id) || fields.latest(hh, EntityTypes.HELD_MESSAGE, id, HeldMessageFields.CLEARED) == FieldValue.Bool(true)) {
                    return VoiceReply.Done
                }
                val text = CallAssistantRules.transcript(event.text) ?: run {
                    // No words (transcription failed or heard nothing): the apps stop showing "Transcribing…".
                    if (write(hh, id, mapOf(HeldMessageFields.NO_TRANSCRIPT to true.fv()))) runCatching { onWritten(hh) }
                    return VoiceReply.Done
                }
                if (write(hh, id, mapOf(HeldMessageFields.TEXT to text.fv()))) {
                    val wasUrgent = fields.latest(hh, EntityTypes.HELD_MESSAGE, id, HeldMessageFields.URGENT) == FieldValue.Bool(true)
                    runCatching { if (Urgency.isUrgent(text) && !wasUrgent) onUrgent(hh) else onWritten(hh) }
                }
                VoiceReply.Done
            }
        }
    }

    /** MEKA's voice for this call's household ([CallVoice.choose]). Never throws: Polly unreachable reads as off. */
    fun voice(): CallVoice {
        val hh = household() ?: return CallVoice.DEFAULT
        val chosen = runCatching { fields.latest(hh, EntityTypes.CONTEXT_MODE, MekaVoiceStore.ENTITY_ID, MekaVoiceFields.NAME) }.getOrNull()
        val offered = runCatching { speechVoices() }.getOrDefault(emptyList())
        return CallVoice.choose((chosen as? FieldValue.Text)?.value, offered)
    }

    /**
     * Whether Meka is at work now by the synced schedule, switch and bank holidays, in UK time ([CallAssistantRules.atWork]).
     * Never throws: anything unreadable counts as at work, the greeting the assistant had before.
     */
    private fun atWork(hh: String): Boolean = runCatching {
        fun text(entity: String, field: String) = (fields.latest(hh, EntityTypes.CONTEXT_MODE, entity, field) as? FieldValue.Text)?.value
        val nowMs = now()
        val local = java.time.Instant.ofEpochMilli(nowMs).atZone(UK)
        CallAssistantRules.atWork(
            schedule = text(WorkMode.ENTITY_ID, WorkFields.SCHEDULE), switch = text(WorkMode.ENTITY_ID, WorkFields.SWITCH),
            holidays = text(BankHolidayStore.ENTITY_ID, BankHolidayFields.DATES),
            epochDay = local.toLocalDate().toEpochDay(), minuteOfDay = local.hour * 60 + local.minute, nowMs = nowMs,
        )
    }.getOrDefault(true)

    /** One Activity entry per call and asking ([CallAssistantRules.answerActivity]); a retried webhook writes nothing more. */
    private fun logAnswer(hh: String, id: String, event: VoiceEvent.Answered, answer: Boolean?, askingAgain: Boolean) {
        val caller = (fields.latest(hh, EntityTypes.HELD_MESSAGE, id, HeldMessageFields.PERSON) as? FieldValue.Text)?.value
            ?: CallAssistantRules.WITHHELD_NAME
        val entry = CallAssistantRules.answerActivityId(id, event.again)
        val values = CallAssistantRules.answerActivity(caller, event.digits, event.speech, answer, askingAgain, now())
        // No wake-up of its own: Activity can wait for the next sync (an urgent answer wakes the devices anyway).
        write(hh, entry, values, EntityTypes.AGENT_ACTION)
    }

    /** The message was taken outside work hours ([HeldMessageFields.AWAY]). */
    private fun away(hh: String, id: String) = fields.latest(hh, EntityTypes.HELD_MESSAGE, id, HeldMessageFields.AWAY) == FieldValue.Bool(true)

    /** The one switch (Work screen on either app): off means calls are turned away as busy. Absent = off. */
    private fun switchedOn(hh: String): Boolean =
        fields.latest(hh, EntityTypes.CONTEXT_MODE, WorkMode.ENTITY_ID, WorkFields.CALL_ASSISTANT) == FieldValue.Bool(true)

    private fun exists(hh: String, id: String) = ops.find(hh, opId(id, HeldMessageFields.KIND)) != null

    /** Appends the fields not written before; returns whether anything was written. */
    private fun write(hh: String, id: String, values: Map<String, FieldValue>, type: String = EntityTypes.HELD_MESSAGE): Boolean = ops.transaction {
        var appended = false
        for ((field, value) in values) {
            val opId = opId(id, field)
            if (ops.find(hh, opId) != null) continue
            ops.append(
                Op(
                    opId = opId, householdId = hh, entityType = type, entityId = id, field = field,
                    value = value, hlc = synchronized(clock) { clock.now() }, baseOpIds = emptyList(), deviceId = Integrations.SERVER_DEVICE,
                ),
            )
            appended = true
        }
        appended
    }

    companion object {
        private val UK: java.time.ZoneId = java.time.ZoneId.of("Europe/London")

        fun opId(heldId: String, field: String) = "srvvoice$heldId${field.lowercase().filter(Char::isLetterOrDigit)}"
    }
}

/** The voice routes' dependencies; null in [mekaSync] leaves them out. */
class VoiceRoutes(val assistant: CallAssistant, providers: List<VoiceProvider>, val publicUrl: String) {
    val providers: Map<String, VoiceProvider> = providers.associateBy { it.id }
}

/** `a=1&b=x+y` as the phone service posts it (form-encoded; `+` is a space). Repeated keys keep the last. */
fun parseForm(body: String): Map<String, String> = body.split('&').filter { it.isNotEmpty() }.associate { pair ->
    val i = pair.indexOf('=')
    val k = if (i < 0) pair else pair.substring(0, i)
    val v = if (i < 0) "" else pair.substring(i + 1)
    URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
}

/**
 * Twilio Programmable Voice (the provider Meka approved, 2026-10-07). Webhooks are form posts signed with the
 * account's auth token (`X-Twilio-Signature`: Base64 HMAC-SHA1 over the URL followed by every form key and value,
 * keys sorted); replies are TwiML. The auth token comes from Secrets Manager at use time ([authToken]); while it is
 * unset every request is refused.
 */
class TwilioVoice(
    private val authToken: () -> String?,
    /** The account SID (`AC…`), for deleting recordings; null while unset (recordings then stay in Twilio). */
    private val accountSid: () -> String? = { null },
    /** DELETE [url] with [headers]; returns the HTTP status. */
    private val delete: (url: String, headers: Map<String, String>) -> Int = { url, headers -> jdkDelete(url, headers) },
) : VoiceProvider {
    override val id = ID

    override fun verify(url: String, form: Map<String, String>, header: (String) -> String?): Boolean {
        val token = authToken()?.takeIf { it.isNotBlank() } ?: return false
        val given = header("X-Twilio-Signature")?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val expected = signature(token, url, form)
        return MessageDigest.isEqual(expected.toByteArray(), given.toByteArray())
    }

    override fun parse(step: String, form: Map<String, String>): VoiceEvent? {
        val call = form["CallSid"]?.trim()?.takeIf { it.isNotEmpty() && it.length <= 64 } ?: return null
        val from = form["From"]
        return when (step) {
            VoiceStep.INCOMING -> VoiceEvent.Incoming(call, from)
            VoiceStep.RECORDED -> VoiceEvent.Recorded(call, from, form["RecordingDuration"]?.trim()?.toIntOrNull() ?: 0)
            VoiceStep.ANSWERED -> VoiceEvent.Answered(call, form["Digits"], form["SpeechResult"])
            VoiceStep.ANSWERED_AGAIN -> VoiceEvent.Answered(call, form["Digits"], form["SpeechResult"], again = true)
            VoiceStep.TRANSCRIBED -> VoiceEvent.Transcribed(
                call, form["TranscriptionText"].takeIf { form["TranscriptionStatus"] == "completed" }, form["RecordingSid"]?.trim(),
            )
            else -> null
        }
    }

    override fun render(reply: VoiceReply, baseUrl: String, voice: CallVoice): Pair<String, String> {
        val base = baseUrl.trimEnd('/')
        val name = voiceName(voice)
        fun say(text: String) = sayIn(name, text)
        fun url(step: String) = xml(base + VoiceStep.path(ID, step))
        val s = CallAssistantScript
        val body = when (reply) {
            is VoiceReply.TakeMessage -> say(s.greeting(reply.atWork)) + say(s.RECORD_PROMPT) +
                """<Record action="${url(VoiceStep.RECORDED)}" method="POST" maxLength="${s.MAX_MESSAGE_SECONDS}" """ +
                """timeout="${s.SILENCE_SECONDS}" finishOnKey="#" playBeep="true" transcribe="true" """ +
                """transcribeCallback="${url(VoiceStep.TRANSCRIBED)}"/>""" +
                // Reached only when nothing was recorded (Twilio then skips the action).
                say(s.NO_MESSAGE) + "<Hangup/>"
            // actionOnEmptyResult: silence comes back too, so the assistant can ask once more (8d).
            is VoiceReply.AskUrgent ->
                """<Gather input="dtmf speech" numDigits="1" timeout="${s.ANSWER_SECONDS}" speechTimeout="auto" language="en-GB" """ +
                    """hints="$URGENT_HINTS" actionOnEmptyResult="true" """ +
                    """action="${url(if (reply.again) VoiceStep.ANSWERED_AGAIN else VoiceStep.ANSWERED)}" method="POST">""" +
                    say(if (reply.again) s.URGENT_AGAIN else s.URGENT_QUESTION) + "</Gather>" +
                    say(s.thanks(reply.atWork)) + "<Hangup/>"
            is VoiceReply.Goodbye -> say(reply.text) + "<Hangup/>"
            VoiceReply.Busy -> """<Reject reason="busy"/>"""
            VoiceReply.Done -> ""
        }
        return "text/xml" to """<?xml version="1.0" encoding="UTF-8"?><Response>$body</Response>"""
    }

    override fun deleteRecording(recording: String): Boolean {
        if (!recordingSid.matches(recording)) return false
        val sid = accountSid()?.trim()?.takeIf { accountSidPattern.matches(it) } ?: return false
        val token = authToken()?.takeIf { it.isNotBlank() } ?: return false
        val auth = Base64.getEncoder().encodeToString("$sid:$token".toByteArray(Charsets.UTF_8))
        val status = runCatching {
            delete("https://api.twilio.com/2010-04-01/Accounts/$sid/Recordings/$recording.json", mapOf("Authorization" to "Basic $auth"))
        }.getOrElse { return false }
        return status == 204 || status == 404 // 404: already gone
    }

    companion object {
        const val ID = "twilio"
        /** Words the speech recogniser should expect after "is it urgent?". */
        const val URGENT_HINTS = "yes, no, urgent, it is, it's urgent, not urgent, it can wait, emergency"
        /** Twilio's names for the British Polly voices MEKA offers (`Polly.<Name>-Neural`). */
        val NEURAL_VOICES = setOf("Amy", "Emma", "Brian", "Arthur")
        /** Those Twilio also offers on Polly's generative engine (`Polly.<Name>-Generative`). */
        val GENERATIVE_VOICES = setOf("Amy")
        private val recordingSid = Regex("^RE[0-9a-fA-F]{32}$")
        private val accountSidPattern = Regex("^AC[0-9a-fA-F]{32}$")

        fun jdkDelete(url: String, headers: Map<String, String>): Int {
            val req = java.net.http.HttpRequest.newBuilder(java.net.URI(url)).timeout(java.time.Duration.ofSeconds(15)).DELETE()
            headers.forEach { (k, v) -> req.header(k, v) }
            return java.net.http.HttpClient.newHttpClient().send(req.build(), java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()
        }

        /**
         * MEKA's voice as Twilio names it: generative where Twilio has it, else neural; a voice Twilio doesn't offer
         * says the call in Amy's neural voice rather than failing the call.
         */
        fun voiceName(voice: CallVoice): String = when {
            voice.engine == SpeechService.GENERATIVE && voice.name in GENERATIVE_VOICES -> "Polly.${voice.name}-Generative"
            voice.name in NEURAL_VOICES -> "Polly.${voice.name}-Neural"
            else -> "Polly.${CallVoice.DEFAULT.name}-Neural"
        }

        private fun sayIn(voice: String, text: String) = """<Say voice="$voice" language="en-GB">${xml(text)}</Say>"""

        private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

        /** Twilio's request signature: Base64(HMAC-SHA1(auth token, url + key1 + value1 + key2 + value2 …)), keys sorted. */
        fun signature(authToken: String, url: String, form: Map<String, String>): String {
            val data = StringBuilder(url)
            for (k in form.keys.sorted()) data.append(k).append(form.getValue(k))
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(authToken.toByteArray(Charsets.UTF_8), "HmacSHA1"))
            return Base64.getEncoder().encodeToString(mac.doFinal(data.toString().toByteArray(Charsets.UTF_8)))
        }

        /**
         * Reads `{"account_sid": …, "auth_token": …}` from Secrets Manager by ARN, cached for five minutes; each is null
         * while unset (`{}`).
         */
        fun fromSecret(secretId: String): TwilioVoice {
            val sm = SecretsManagerClient.builder().httpClient(UrlConnectionHttpClient.create()).build()
            var cached: Pair<Long, Map<String, String>>? = null
            val lock = Any()
            fun read(key: String): String? = synchronized(lock) {
                val hit = cached?.takeIf { System.currentTimeMillis() - it.first < 5 * 60_000 }?.second ?: run {
                    val v = runCatching {
                        Json.parseToJsonElement(sm.getSecretValue { it.secretId(secretId) }.secretString()).jsonObject
                            .mapValues { (_, e) -> e.jsonPrimitive.content.trim() }
                    }.getOrElse { emptyMap() }
                    cached = System.currentTimeMillis() to v
                    v
                }
                hit[key]?.takeIf { it.isNotEmpty() }
            }
            return TwilioVoice(authToken = { read("auth_token") }, accountSid = { read("account_sid") })
        }
    }
}
