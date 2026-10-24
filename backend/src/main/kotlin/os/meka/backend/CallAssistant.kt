package os.meka.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import os.meka.backend.integrations.Integrations
import os.meka.core.domain.AboutYouFields
import os.meka.core.domain.AboutYouRules
import os.meka.core.domain.AssistantHangupRules
import os.meka.core.domain.PutThroughRules
import os.meka.core.domain.BankHolidayFields
import os.meka.core.domain.BlockedCallerFields
import os.meka.core.domain.BlockedCallerRules
import os.meka.core.domain.BankHolidayStore
import os.meka.core.domain.CallAssistantRules
import os.meka.core.domain.CallAssistantScript
import os.meka.core.domain.CallCreditReading
import os.meka.core.domain.CallIntroRules
import os.meka.core.domain.IntroJudgement
import os.meka.core.domain.IntroVerdict
import os.meka.core.domain.NumberLookupRules
import os.meka.core.domain.NumberReputation
import os.meka.core.domain.CaptureKind
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.HeldMessageFields
import os.meka.core.domain.MekaVoiceFields
import os.meka.core.domain.MekaVoiceRules
import os.meka.core.domain.MekaVoiceStore
import os.meka.core.domain.SuspectedSpamRules
import os.meka.core.domain.Urgency
import os.meka.core.domain.VoiceRecordingRules
import os.meka.core.domain.CallNoticeRules
import os.meka.core.domain.WorkFields
import os.meka.core.domain.WorkMode
import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.Op
import os.meka.core.sync.ServerOpStore
import os.meka.core.sync.fv
import os.meka.core.wire.SpeechCodec
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

    /** [to] is the number called (the assistant's own, for a forwarded call); added with item (c) slice 2. */
    data class Incoming(override val callId: String, val from: String?, val to: String? = null) : VoiceEvent()
    /**
     * The caller's answer to "who's calling, and what's it about?" ([CallIntroRules]; [speech] as recognised, empty or
     * null when they said nothing). Added 2026-10-17 (the call assistant talks to them).
     */
    data class Introduced(override val callId: String, val from: String?, val speech: String?) : VoiceEvent()
    /** The message was recorded ([seconds] long; 0 when nothing was said). */
    data class Recorded(override val callId: String, val from: String?, val seconds: Int) : VoiceEvent()
    /**
     * The answer to "is it urgent?" (keypad [digits] or recognised [speech]; both empty when the caller said nothing).
     * [again] when it answers the second asking ([VoiceStep.ANSWERED_AGAIN]).
     */
    data class Answered(
        override val callId: String, val digits: String?, val speech: String?, val again: Boolean = false,
        /** The caller's number and the number they called (the assistant's own): for putting them through (item (c) slice 2). */
        val from: String? = null, val to: String? = null,
    ) : VoiceEvent()
    /**
     * Item (c) slice 2: Meka's mobile answered the put-through ([callId] is that leg, [parent] the caller's call): the
     * whisper is played to him before the two are connected.
     */
    data class Whisper(override val callId: String, val parent: String?) : VoiceEvent()
    /** His key press after the whisper ([digits]; 1 takes the call). */
    data class WhisperAnswered(override val callId: String, val parent: String?, val digits: String?) : VoiceEvent()
    /** The put-through ended (the caller's call, [callId]): taken and finished, or not taken. */
    data class Dialed(override val callId: String) : VoiceEvent()
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
    data class TakeMessage(val atWork: Boolean = true, val greet: Boolean = true) : VoiceReply()
    /**
     * Greet, then ask who's calling and what it's about ([CallIntroRules.INTRO_QUESTION]; the answer to
     * [VoiceStep.INTRODUCED], which then takes the message without greeting again, or ends a judged scam).
     */
    data class AskWho(val atWork: Boolean = true) : VoiceReply()
    /**
     * Ask "is it urgent?" (answer to [VoiceStep.ANSWERED]); [atWork] picks the goodbye if nothing is answered. [again]
     * asks once more ([CallAssistantScript.URGENT_AGAIN], answer to [VoiceStep.ANSWERED_AGAIN]) after an answer the
     * assistant didn't understand.
     */
    data class AskUrgent(val atWork: Boolean = true, val again: Boolean = false) : VoiceReply()
    data class Goodbye(val text: String) : VoiceReply()
    /**
     * Item (c) slice 2: tell the caller to hold and ring [mobile] from [callerId] (the assistant's own number, so his
     * Fold lets it ring), playing the whisper when he answers ([VoiceStep.WHISPER]); then [VoiceStep.DIALED].
     */
    data class PutThrough(val mobile: String, val callerId: String) : VoiceReply()
    /** Played to Meka when he answers: who it is and "press 1" ([VoiceStep.WHISPER_ANSWERED]); silence hangs up his leg. */
    data class Whisper(val text: String) : VoiceReply()
    /** He pressed 1: say so, then the phone service connects the two. */
    data object Connect : VoiceReply()
    /** End this leg without a word. */
    data object HangUp : VoiceReply()
    /** Turn the call away as busy: the assistant is switched off or nobody is set up to take it. */
    data object Busy : VoiceReply()
    /** A callback that needs no answer. */
    data object Done : VoiceReply()
}

/** The webhook paths, `/v1/voice/<provider>/<step>`. */
object VoiceStep {
    const val INCOMING = "incoming"
    const val INTRODUCED = "introduced"
    const val RECORDED = "recorded"
    const val ANSWERED = "answered"
    const val ANSWERED_AGAIN = "answered-again"
    const val TRANSCRIBED = "transcribed"
    /** Item (c) slice 2: putting an urgent caller through. */
    const val WHISPER = "whisper"
    const val WHISPER_ANSWERED = "whisper-answered"
    const val DIALED = "dialed"

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
    fun render(
        reply: VoiceReply,
        baseUrl: String,
        voice: CallVoice = CallVoice.DEFAULT,
        /** The path of a ready clip of a line in a Google voice ([CallClips.path]); null: say it in [voice]'s Polly voice. */
        clip: (voice: String, text: String) -> String? = { _, _ -> null },
    ): Pair<String, String>

    /** Deletes a recording from the phone service once it has been transcribed. Returns whether it is gone. */
    fun deleteRecording(recording: String): Boolean

    /**
     * The recording's audio as MP3 (call assistant polish 8c), fetched before the phone service's copy is deleted;
     * null when it can't be had. Never larger than [VoiceRecordingRules.MAX_BYTES].
     */
    fun fetchRecording(recording: String): ByteArray? = null
}

/**
 * The Polly voice the call assistant speaks with and its engine ("generative" or "neural"): MEKA's voice (build plan V1,
 * "Weather and a voice", item 3: one voice everywhere, so callers hear the voice Meka chose). [google] is a chosen
 * Google Chirp 3 HD voice (natural voice, slice 3): its lines are played as clips ([CallClips]) when they're ready, and
 * the Polly voice says any line that isn't.
 */
data class CallVoice(val name: String, val engine: String, val google: String? = null) {
    companion object {
        /**
         * Before MEKA's voice is known (Polly off or unreachable, nothing chosen): Obi's, Brian, the server's first
         * choice (the Obi plan, slice 2).
         */
        val DEFAULT = CallVoice(SpeechService.DEFAULT_VOICE, SpeechService.NEURAL)

        /**
         * The voice for calls: the synced choice ([chosen], a `context_mode/voice` name) when the server offers it,
         * else the server's default (the first [offered]; also when the device's own voice is chosen, since a caller
         * can't hear Meka's phone), else the chosen name on the neural engine, else [DEFAULT]. A chosen Google voice
         * the server offers rides along as [google], over the server's default Polly voice.
         */
        fun choose(chosen: String?, offered: List<SpeechCodec.Voice>): CallVoice {
            val polly = pollyFor(chosen, offered)
            val name = MekaVoiceRules.normalize(chosen)?.takeIf { it != MekaVoiceRules.DEVICE } ?: return polly
            val google = offered.firstOrNull { it.provider == SpeechCodec.GOOGLE && it.id.equals(name, ignoreCase = true) }
            return if (google != null) polly.copy(google = google.id) else polly
        }

        private fun pollyFor(chosen: String?, offered: List<SpeechCodec.Voice>): CallVoice {
            val polly = MekaVoiceRules.normalize(chosen)?.takeIf { it != MekaVoiceRules.DEVICE }
            // The phone service's own voices are Polly's: a chosen Google voice isn't one, so the server's default Polly
            // voice stands behind it (said whenever a Google clip isn't ready).
            val pollyOffered = offered.filter { it.provider == SpeechCodec.POLLY }
            pollyOffered.firstOrNull { it.id.equals(polly, ignoreCase = true) }?.let { return CallVoice(it.id, it.engine) }
            pollyOffered.firstOrNull()?.let { return CallVoice(it.id, it.engine) }
            if (offered.any { it.id.equals(polly, ignoreCase = true) } || polly?.endsWith("-US") == true) return DEFAULT
            return polly?.let { CallVoice(it, SpeechService.NEURAL) } ?: DEFAULT
        }
    }
}

/**
 * The call assistant's lines in a chosen Google voice (natural voice, slice 3). The phone service can only speak in its
 * own Polly voices, so Google's are played as MP3 clips the server made once ([CallAssistantScript]'s nine fixed lines,
 * about 700 characters a voice, on Google's meter and under its 900k fallback) and serves from memory at
 * [PATH]`<key>.mp3`. A line whose clip isn't ready is said by the Polly voice and the voice's clips are made in the
 * background, so a call never waits on Google. Keys are a salted hash (new each start), so nobody can guess a clip's
 * address; clips hold only those fixed lines.
 */
class CallClips(
    /** The line in the Google voice, or null (not offered, past the fallback, failed) — [SpeechService.googleClip]. */
    private val synth: (text: String, voice: String) -> ByteArray?,
    /**
     * Where made clips are kept between starts (MEKA's own bucket, under [STORE_PREFIX]; the bucket's 30-day rule
     * renews them monthly), so a deploy doesn't pay Google again; null keeps them in memory only.
     */
    private val store: RecordingStore? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val background: (Runnable) -> Unit = { r -> Thread.ofVirtual().name("call-clips").start(r) },
    private val salt: ByteArray = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) },
) {
    private val clips = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private val tried = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val warming = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun key(voice: String, text: String): String {
        val d = MessageDigest.getInstance("SHA-256")
        d.update(salt)
        d.update("$voice\n$text".toByteArray(Charsets.UTF_8))
        return d.digest().joinToString("") { "%02x".format(it) }.take(32)
    }

    /**
     * The clip's path for [text] in [voice], or null when it isn't ready (Polly says the line; [voice]'s lines are made
     * in the background, at most once every [RETRY_MS] after a failure).
     */
    fun path(voice: String, text: String): String? {
        val k = key(voice, text)
        if (clips.containsKey(k)) return "$PATH$k.mp3"
        warmLater(voice)
        return null
    }

    /** A clip by its file name (`<key>.mp3`); null for anything else. */
    fun audio(file: String): ByteArray? {
        val k = file.removeSuffix(".mp3").takeIf { file.endsWith(".mp3") && KEY.matches(it) } ?: return null
        return clips[k]
    }

    /** Makes [voice]'s missing lines now, stopping at the first failure; how many were made. Never throws. */
    fun warm(voice: String): Int {
        if (!warming.add(voice)) return 0
        try {
            tried[voice] = nowMs()
            var made = 0
            for (line in LINES) {
                val k = key(voice, line)
                if (clips.containsKey(k)) continue
                val kept = storeKey(voice, line)
                val mp3 = runCatching { store?.get(kept) }.getOrNull()?.takeIf { it.isNotEmpty() }
                    ?: runCatching { synth(line, voice) }.getOrNull()?.takeIf { it.isNotEmpty() }
                        ?.also { fresh -> runCatching { store?.put(kept, fresh) } }
                    ?: break
                if (clips.size >= MAX_CLIPS) clips.clear()
                clips[k] = mp3
                made++
            }
            return made
        } finally {
            warming.remove(voice)
        }
    }

    /** Where [text] in [voice] is kept in the bucket: `voice/clips/<voice>/<hash of the line>.mp3`. */
    fun storeKey(voice: String, text: String): String {
        val h = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(24)
        return "$STORE_PREFIX${voice.filter { it.isLetterOrDigit() || it == '-' }}/$h.mp3"
    }

    /** Every line in [voice] is ready. */
    fun ready(voice: String): Boolean = LINES.all { clips.containsKey(key(voice, it)) }

    private fun warmLater(voice: String) {
        val last = tried[voice]
        if (last != null && nowMs() - last < RETRY_MS) return
        if (voice in warming) return
        tried[voice] = nowMs()
        runCatching { background(Runnable { warm(voice) }) }
    }

    companion object {
        const val PATH = "/v1/voice/clip/"
        /** Under the recordings' `voice/` prefix (so the bucket's lifecycle covers them), apart from any household's. */
        const val STORE_PREFIX = "voice/clips/"
        /** Everything the call assistant says (handle's replies only ever use these). */
        val LINES = listOf(
            CallAssistantScript.GREETING, CallAssistantScript.GREETING_AWAY, CallAssistantScript.RECORD_PROMPT,
            CallAssistantScript.URGENT_QUESTION, CallAssistantScript.URGENT_AGAIN, CallAssistantScript.THANKS_URGENT,
            CallAssistantScript.THANKS, CallAssistantScript.THANKS_AWAY, CallAssistantScript.NO_MESSAGE,
            CallIntroRules.INTRO_QUESTION, CallIntroRules.RECORD_AFTER_INTRO, CallIntroRules.SCAM_CLOSE,
            PutThroughRules.HOLD, PutThroughRules.MISSED, PutThroughRules.CONNECTING,
        )
        /** A failed or partial warm-up is tried again no sooner than this (each call would otherwise ask Google). */
        const val RETRY_MS = 5 * 60_000L
        /** A few voices' worth; past it the cache starts again (a voice changed many times). */
        const val MAX_CLIPS = 12 * 6
        private val KEY = Regex("[0-9a-f]{32}")
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
    /** Where callers' recordings are kept (polish 8c); null keeps none (the phone service's copy is still deleted). */
    private val recordings: RecordingStore? = null,
    /**
     * Transcribes kept recordings (polish 8d i, Amazon Transcribe in MEKA's own account); null leaves Twilio's words.
     * With one (and a store), a message's words are written once Transcribe has had its go ([transcribeKept]), with
     * Twilio's as the fallback, so the apps show "Transcribing…" until then rather than Twilio's guess first.
     */
    private val transcriber: Transcriber? = null,
    /**
     * Suspected spam (polish 8b c): reads a settled message's words with MEKA's AI ([CallScamCheck]); a likely scam puts
     * the caller's number on Suspected spam. Null (no AI) flags nothing.
     */
    private val scamCheck: ScamChecker? = null,
    /**
     * The caller's number's web reputation (spam-call lookup, [NumberLookup.lookUp]): waits at most `waitMs` and
     * answers null for no lookup (off, pending, failed). Null: the assistant judges from the caller's words alone.
     */
    private val reputation: ((householdId: String, number: String, waitMs: Long) -> NumberReputation?)? = null,
) {
    private val clock = HlcClock(Integrations.SERVER_DEVICE, now)

    /**
     * What each caller said when asked who's calling, by call id, until the message is recorded (a few minutes).
     * Memory only: a restart mid-call just loses the line, and the words are never logged.
     */
    private val intros = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()

    private fun keepIntro(callId: String, intro: String) {
        val at = now()
        intros.entries.removeIf { at - it.value.first > INTRO_KEEP_MS }
        if (intros.size < 500) intros[callId] = at to intro
    }

    private fun introOf(callId: String): String? = intros[callId]?.takeIf { now() - it.first <= INTRO_KEEP_MS }?.second

    fun handle(provider: String, event: VoiceEvent): VoiceReply {
        val hh = household() ?: return when (event) {
            is VoiceEvent.Incoming -> VoiceReply.Busy
            // Never connect a put-through nobody can vouch for.
            is VoiceEvent.Whisper, is VoiceEvent.WhisperAnswered -> VoiceReply.HangUp
            else -> VoiceReply.Done
        }
        val id = CallAssistantRules.heldId(provider, event.callId)
        return when (event) {
            is VoiceEvent.Incoming -> {
                if (!switchedOn(hh)) return VoiceReply.Busy
                // Item (c) slice 2: a put-through his mobile turned away, sent back by "forward when busy": never a loop.
                if (PutThroughRules.fromAssistant(event.from, event.to) || PutThroughRules.fromAssistant(event.from, profileText(hh, AboutYouFields.ASSISTANT_NUMBER))) {
                    return VoiceReply.Busy
                }
                // The number's reputation starts now (not waited for), so it's ready when they've said who they are.
                event.from?.takeIf { NumberLookupRules.lookUpable(it) }?.let { n -> runCatching { reputation?.invoke(hh, n, 0) } }
                VoiceReply.AskWho(atWork(hh))
            }
            is VoiceEvent.Introduced -> introduced(hh, id, event)
            is VoiceEvent.Recorded -> {
                val intro = introOf(event.callId)
                if (event.seconds < 1) {
                    // They said who they were and hung up at the tone: that's a call to see, not a hang-up to block.
                    if (CallIntroRules.real(intro)) {
                        noMessage(hh, id, event.from, intro!!)
                    } else {
                        noteHangup(hh, id, event.from)
                    }
                    return VoiceReply.Goodbye(CallAssistantScript.NO_MESSAGE)
                }
                // A retry finds the message written: it keeps the at-work reading it was taken with.
                val atWork = if (exists(hh, id)) !away(hh, id) else atWork(hh)
                val values = CallAssistantRules.messageFields(event.from, now(), atWork, seconds = event.seconds) +
                    (intro?.let { mapOf(HeldMessageFields.INTRO to it.fv()) } ?: emptyMap())
                if (write(hh, id, values)) runCatching { onWritten(hh) }
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
                // Call notifications, slice 1: how it was answered, so "urgent" pressed by a robocall never breaks through.
                if (known && answer != null) {
                    CallNoticeRules.answeredBy(event.digits, event.speech)?.let { by -> write(hh, id, mapOf(HeldMessageFields.URGENT_BY to by.fv())) }
                }
                val urgent = answer == true
                if (urgent && known) {
                    if (write(hh, id, mapOf(HeldMessageFields.URGENT to true.fv()))) runCatching { onUrgent(hh) }
                    // Item (c) slice 2: a genuine urgent caller is offered to Meka on his mobile.
                    runCatching { putThrough(hh, id, event) }.getOrNull()?.let { return it }
                }
                VoiceReply.Goodbye(if (urgent) CallAssistantScript.THANKS_URGENT else CallAssistantScript.thanks(!away(hh, id)))
            }
            is VoiceEvent.Whisper -> {
                val parent = event.parent?.takeIf { it.isNotBlank() } ?: return VoiceReply.HangUp
                val heldId = CallAssistantRules.heldId(provider, parent)
                if (!exists(hh, heldId)) return VoiceReply.HangUp
                val intro = introOf(parent) ?: (fields.latest(hh, EntityTypes.HELD_MESSAGE, heldId, HeldMessageFields.INTRO) as? FieldValue.Text)?.value
                VoiceReply.Whisper(PutThroughRules.whisper(intro))
            }
            is VoiceEvent.WhisperAnswered -> {
                val parent = event.parent?.takeIf { it.isNotBlank() } ?: return VoiceReply.HangUp
                val heldId = CallAssistantRules.heldId(provider, parent)
                if (!PutThroughRules.takes(event.digits) || !exists(hh, heldId)) return VoiceReply.HangUp
                putThroughDone(hh, heldId, taken = true)
                VoiceReply.Connect
            }
            is VoiceEvent.Dialed -> {
                // Taken (written when he pressed 1): the call has happened, so nothing more to say.
                val taken = fields.latest(hh, EntityTypes.HELD_MESSAGE, id, HeldMessageFields.PUT_THROUGH) == FieldValue.Text(PutThroughRules.TAKEN)
                if (taken) return VoiceReply.HangUp
                if (exists(hh, id)) putThroughDone(hh, id, taken = false)
                VoiceReply.Goodbye(PutThroughRules.MISSED)
            }
            is VoiceEvent.Transcribed -> {
                // With Transcribe on, the words wait for it ([transcribeKept], off the request); Twilio's are its fallback.
                if (!transcribesLater(event)) settle(hh, id, event.text)
                VoiceReply.Done
            }
        }
    }

    /**
     * Item (c) slice 2: whether to ring Meka's mobile for this urgent caller ([PutThroughRules.offer]: a real
     * introduction judged plainly OK, not reported online, a real message, About you's mobile set, and the number they
     * called usable as the caller ID). The reply that does it, or null to say goodbye as before.
     */
    private fun putThrough(hh: String, id: String, event: VoiceEvent.Answered): VoiceReply? {
        val mobile = profileText(hh, AboutYouFields.MOBILE) ?: return null
        val intro = introOf(event.callId) ?: (fields.latest(hh, EntityTypes.HELD_MESSAGE, id, HeldMessageFields.INTRO) as? FieldValue.Text)?.value
        val from = event.from ?: (fields.latest(hh, EntityTypes.HELD_MESSAGE, id, HeldMessageFields.PERSON) as? FieldValue.Text)?.value
        val rep = from?.takeIf { NumberLookupRules.lookUpable(it) }?.let { n -> runCatching { reputation?.invoke(hh, n, 0) }.getOrNull() }
        val seconds = (fields.latest(hh, EntityTypes.HELD_MESSAGE, id, HeldMessageFields.SECONDS) as? FieldValue.Int64)?.value?.toInt()
        val assistant = event.to ?: profileText(hh, AboutYouFields.ASSISTANT_NUMBER)
        val judged = CallIntroRules.judge(CallIntroRules.clean(intro), rep)
        if (!PutThroughRules.offer(true, intro, judged, rep, seconds, from, mobile, assistant)) return null
        return VoiceReply.PutThrough(PutThroughRules.dialable(mobile)!!, PutThroughRules.dialable(assistant)!!)
    }

    /** The put-through's outcome on the message, and an Activity entry; once per call (the first outcome stands). */
    private fun putThroughDone(hh: String, heldId: String, taken: Boolean) {
        val value = if (taken) PutThroughRules.TAKEN else PutThroughRules.MISSED_VALUE
        if (write(hh, heldId, mapOf(HeldMessageFields.PUT_THROUGH to value.fv()))) {
            val number = (fields.latest(hh, EntityTypes.HELD_MESSAGE, heldId, HeldMessageFields.PERSON) as? FieldValue.Text)?.value
                ?.takeIf { BlockedCallerRules.keyOf(it) != null }
            write(hh, PutThroughRules.activityId(heldId), PutThroughRules.activity(number, taken, now()), EntityTypes.AGENT_ACTION)
            runCatching { onWritten(hh) }
        }
    }

    /** A line of About you ([AboutYouFields]), or null. Never throws. */
    private fun profileText(hh: String, field: String): String? = runCatching {
        (fields.latest(hh, EntityTypes.HOUSEHOLD_PROFILE, AboutYouRules.ENTITY_ID, field) as? FieldValue.Text)?.value?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /**
     * The caller's answer to "who's calling?" ([CallIntroRules.judge], with the number's reputation when the lookup
     * answers within [REPUTATION_WAIT_MS]): a judged scam is ended politely ([CallIntroRules.SCAM_CLOSE]) and kept as a
     * call to see, its number on Suspected spam; anyone else goes on to leave a message.
     */
    private fun introduced(hh: String, id: String, event: VoiceEvent.Introduced): VoiceReply {
        val intro = CallIntroRules.clean(event.speech)
        intro?.let { keepIntro(event.callId, it) }
        val rep = event.from?.takeIf { NumberLookupRules.lookUpable(it) }?.let { n ->
            runCatching { reputation?.invoke(hh, n, REPUTATION_WAIT_MS) }.getOrNull()
        }
        val judged = CallIntroRules.judge(intro, rep)
        if (judged.verdict == IntroVerdict.SCAM) {
            runCatching { endAsScam(hh, id, event.from, intro, judged) }
            return VoiceReply.Goodbye(CallIntroRules.SCAM_CLOSE)
        }
        return VoiceReply.TakeMessage(atWork(hh), greet = false)
    }

    /**
     * A call ended as a likely scam: kept as a voice message with no recording ([HeldMessageFields.ENDED] scam, what
     * they said and the one-line why as its summary; never urgent), and a real number not already blocked, flagged or
     * cleared with Not spam goes on Suspected spam with an Activity entry. Once per call (op ids from the call).
     */
    private fun endAsScam(hh: String, id: String, from: String?, intro: String?, judged: IntroJudgement) {
        val at = now()
        val values = CallAssistantRules.messageFields(from, at, atWork(hh)) + buildMap<String, FieldValue> {
            put(HeldMessageFields.ENDED, CallIntroRules.ENDED_SCAM.fv())
            put(HeldMessageFields.SUMMARY, CallIntroRules.summary(judged).fv())
            if (intro != null) {
                put(HeldMessageFields.INTRO, intro.fv())
                put(HeldMessageFields.TEXT, intro.fv())
            } else {
                put(HeldMessageFields.NO_TRANSCRIPT, true.fv())
            }
        }
        var wrote = write(hh, id, values)
        val key = BlockedCallerRules.keyOf(from)
        if (key != null && from != null) {
            fun flag(field: String) = (fields.latest(hh, EntityTypes.BLOCKED_CALLER, key, field) as? FieldValue.Bool)?.value
            if (flag(BlockedCallerFields.BLOCKED) != true && flag(BlockedCallerFields.SUSPECTED) == null) {
                if (write(hh, key, SuspectedSpamRules.flagFields(from, CallIntroRules.why(judged), at), EntityTypes.BLOCKED_CALLER, opBase = id + "introscam")) {
                    write(hh, CallIntroRules.activityId(id), CallIntroRules.activity(from, judged, at), EntityTypes.AGENT_ACTION)
                    wrote = true
                }
            }
        }
        if (wrote) runCatching { onWritten(hh) }
    }

    /**
     * They said who they were, then left no message: kept as a call to see ([HeldMessageFields.ENDED] noMessage, their
     * words as its text), and their words read like a message's (the AI's summary and scam check, when on).
     */
    private fun noMessage(hh: String, id: String, from: String?, intro: String) {
        val values = CallAssistantRules.messageFields(from, now(), atWork(hh)) + mapOf(
            HeldMessageFields.ENDED to CallIntroRules.ENDED_NO_MESSAGE.fv(),
            HeldMessageFields.INTRO to intro.fv(),
            HeldMessageFields.TEXT to intro.fv(),
        )
        if (write(hh, id, values)) {
            runCatching { onWritten(hh) }
            runCatching { readWords(hh, id, intro) }
        }
    }

    /** Whether this message's words are written by [transcribeKept] rather than straight from the phone service's. */
    fun transcribesLater(event: VoiceEvent.Transcribed): Boolean =
        transcriber != null && recordings != null && event.recording != null

    /**
     * After [keepRecording] (polish 8d i): Amazon Transcribe hears the kept recording ([kept]) and its words are written;
     * when it fails, hears nothing or nothing was kept, the phone service's words are written instead (or "no words came
     * through"). Does nothing unless [transcribesLater]. Never throws past the fallback: the message always settles.
     */
    fun transcribeKept(provider: VoiceProvider, event: VoiceEvent.Transcribed, kept: Boolean) {
        if (!transcribesLater(event)) return
        val hh = household() ?: return
        val id = CallAssistantRules.heldId(provider.id, event.callId)
        // A retried callback finds the words already settled: no second job.
        if (settled(hh, id)) return
        val better = if (kept && !gone(hh, id)) {
            VoiceRecordingRules.key(hh, id)?.let { key -> runCatching { transcriber?.transcribe(key) }.getOrNull() }
        } else null
        settle(hh, id, CallAssistantRules.transcript(better) ?: event.text)
    }

    /** Writes a message's words (or that none came through) once; nothing for one Meka already dismissed. */
    private fun settle(hh: String, id: String, raw: String?) {
        // An older app's Done blanked the summary: a late transcript doesn't bring the words back. (A Done from
        // 2026-10-20 keeps them for Activity → Calls, so its words still arrive.)
        if (!exists(hh, id) || wordsGone(hh, id)) return
        val text = CallAssistantRules.transcript(raw) ?: run {
            // No words (transcription failed or heard nothing): the apps stop showing "Transcribing…".
            if (write(hh, id, mapOf(HeldMessageFields.NO_TRANSCRIPT to true.fv()))) runCatching { onWritten(hh) }
            return
        }
        if (write(hh, id, mapOf(HeldMessageFields.TEXT to text.fv()))) {
            val wasUrgent = fields.latest(hh, EntityTypes.HELD_MESSAGE, id, HeldMessageFields.URGENT) == FieldValue.Bool(true)
            runCatching { if (Urgency.isUrgent(text) && !wasUrgent) onUrgent(hh) else onWritten(hh) }
            // What they said when asked who's calling goes with the message, so the summary knows who it's from.
            val intro = (fields.latest(hh, EntityTypes.HELD_MESSAGE, id, HeldMessageFields.INTRO) as? FieldValue.Text)?.value
            runCatching { readWords(hh, id, intro?.let { "$it. $text" } ?: text) }
        }
    }

    /**
     * Suspected spam (polish 8b c): asks MEKA's AI about a new message's words (never the number) when its caller is a
     * real number that isn't blocked, flagged or cleared with Not spam ([SuspectedSpamRules.shouldCheck]); a likely
     * scam puts the number on Suspected spam ([SuspectedSpamRules.flagFields]) with an Activity entry, and wakes the
     * devices. Nothing is blocked: Meka chooses. Returns whether it flagged.
     */
    fun flagIfScam(hh: String, id: String, words: String): Boolean = readWords(hh, id, words)

    /**
     * Call notifications, slice 2: one AI call per message with words ([SuspectedSpamRules.MIN_WORDS] or more, not
     * a robocall's): its one-line summary is written on the message ([HeldMessageFields.SUMMARY], once, not for one
     * Meka already cleared) and the devices woken so the alert updates in place; the scam flag follows
     * [SuspectedSpamRules.shouldCheck] as before. Returns whether it flagged the number.
     */
    fun readWords(hh: String, id: String, words: String): Boolean {
        val checker = scamCheck ?: return false
        val number = (fields.latest(hh, EntityTypes.HELD_MESSAGE, id, HeldMessageFields.PERSON) as? FieldValue.Text)?.value
        val key = BlockedCallerRules.keyOf(number)
        fun flag(field: String) = key?.let { (fields.latest(hh, EntityTypes.BLOCKED_CALLER, it, field) as? FieldValue.Bool)?.value }
        val checkScam = SuspectedSpamRules.shouldCheck(key, flag(BlockedCallerFields.BLOCKED), flag(BlockedCallerFields.SUSPECTED), words)
        val summarise = SuspectedSpamRules.shouldCheck("any", null, null, words) && !CallNoticeRules.digitsOrOneWord(words)
        if (!checkScam && !summarise) return false
        val verdict = checker.check(words) ?: return false
        verdict.summary?.let { summary ->
            if (!wordsGone(hh, id) && write(hh, id, mapOf(HeldMessageFields.SUMMARY to summary.fv()))) runCatching { onWritten(hh) }
        }
        if (!checkScam || !verdict.scam || key == null || number == null) return false
        val at = now()
        // Op ids from the message, so a retried settle writes nothing more (and a later message can flag again after an unblock).
        val flagged = write(hh, key, SuspectedSpamRules.flagFields(number, verdict.why, at), EntityTypes.BLOCKED_CALLER, opBase = id + "scam")
        if (flagged) {
            write(hh, SuspectedSpamRules.activityId(id), SuspectedSpamRules.activity(number, verdict.why, at), EntityTypes.AGENT_ACTION)
            runCatching { onWritten(hh) }
        }
        return flagged
    }

    /**
     * Spam calls still ring, slice 3: the caller hung up (or said nothing) without leaving a message. For a real number
     * that isn't an emergency service ([AssistantHangupRules.shouldRecord]), the time goes on the number's
     * `blocked_caller` entity ([BlockedCallerFields.HUNG_UP_AT]) and the devices are woken; the Fold decides whether to
     * block (only it knows contacts and recent calls). Once per call (op ids from the call); a blocked number isn't noted.
     */
    private fun noteHangup(hh: String, id: String, from: String?) {
        if (!AssistantHangupRules.shouldRecord(from)) return
        val key = BlockedCallerRules.keyOf(from) ?: return
        if (fields.latest(hh, EntityTypes.BLOCKED_CALLER, key, BlockedCallerFields.BLOCKED) == FieldValue.Bool(true)) return
        if (write(hh, key, AssistantHangupRules.fields(from!!, now()), EntityTypes.BLOCKED_CALLER, opBase = id + "hangup")) {
            runCatching { onWritten(hh) }
        }
    }

    /**
     * Keeps the caller's recording (polish 8c): once the phone service has transcribed (or failed to transcribe) the
     * message, fetch the audio from it, keep it in MEKA's bucket and write [HeldMessageFields.AUDIO] so both apps offer
     * Play. Runs before the phone service's copy is deleted. Nothing is kept for a message Meka already dismissed, or
     * when no store is configured. Returns whether a recording is now kept.
     */
    fun keepRecording(provider: VoiceProvider, event: VoiceEvent.Transcribed): Boolean {
        val store = recordings ?: return false
        val recording = event.recording ?: return false
        val hh = household() ?: return false
        val id = CallAssistantRules.heldId(provider.id, event.callId)
        if (!exists(hh, id) || gone(hh, id)) return false
        val key = VoiceRecordingRules.key(hh, id) ?: return false
        if (fields.latest(hh, EntityTypes.HELD_MESSAGE, id, HeldMessageFields.AUDIO) == FieldValue.Bool(true)) return true
        // "Don't keep": held only while Amazon Transcribe hears it ([releaseUnkept] deletes it after); without
        // Transcribe there's nothing to hold it for.
        val keep = keepDays(hh) > 0
        if (!keep && transcriber == null) return false
        val audio = provider.fetchRecording(recording)?.takeIf { it.isNotEmpty() && it.size <= VoiceRecordingRules.MAX_BYTES } ?: return false
        store.put(key, audio)
        // Delete recording (or an older app's Done) may have come while it was being fetched: then it isn't kept after all.
        if (gone(hh, id)) {
            store.delete(key)
            return false
        }
        // Play is offered only for a recording that is kept.
        if (keep && write(hh, id, mapOf(HeldMessageFields.AUDIO to true.fv()))) runCatching { onWritten(hh) }
        return true
    }

    /**
     * After [transcribeKept]: with "Don't keep" ([keepDays] 0), the recording held for Transcribe is deleted now (and
     * any transcript left beside it). Nothing happens for a kept one.
     */
    fun releaseUnkept(provider: VoiceProvider, event: VoiceEvent.Transcribed) {
        val store = recordings ?: return
        val hh = household() ?: return
        if (keepDays(hh) > 0) return
        val id = CallAssistantRules.heldId(provider.id, event.callId)
        if (fields.latest(hh, EntityTypes.HELD_MESSAGE, id, HeldMessageFields.AUDIO) == FieldValue.Bool(true)) return
        VoiceRecordingRules.key(hh, id)?.let { key ->
            runCatching { store.delete(key) }
            runCatching { store.delete(Transcripts.outputKey(key)) }
        }
    }

    /**
     * "Keep callers' recordings" (polish 8c): deletes whatever under the household's `voice/` is older than the
     * synced choice ([VoiceRecordingRules.sweepBeforeMs], 7 days); run hourly and whenever the choice changes. The
     * bucket's 8-day lifecycle rule stays the backstop. Returns how many objects were deleted.
     */
    fun sweepRecordings(householdId: String? = household()): Int {
        val store = recordings ?: return 0
        val hh = householdId ?: return 0
        val prefix = VoiceRecordingRules.householdPrefix(hh) ?: return 0
        val before = VoiceRecordingRules.sweepBeforeMs(keepDays(hh), now())
        var n = 0
        for (o in store.list(prefix)) if (o.storedAtMs < before) {
            if (runCatching { store.delete(o.key) }.isSuccess) n++
        }
        return n
    }

    /** The synced "Keep callers' recordings" choice, in days (0 = don't keep). Unreadable reads as the default. */
    private fun keepDays(hh: String): Int = runCatching {
        VoiceRecordingRules.keepDays((fields.latest(hh, EntityTypes.CONTEXT_MODE, WorkMode.ENTITY_ID, WorkFields.RECORDING_DAYS) as? FieldValue.Int64)?.value)
    }.getOrDefault(VoiceRecordingRules.DEFAULT_KEEP_DAYS)

    /**
     * A household's recording for a device to play: only a voice message that isn't cleared, was kept and is younger
     * than the "Keep callers' recordings" choice, 7 days ([VoiceRecordingRules.playable]). Null otherwise,
     * including for another household's message.
     */
    fun recording(householdId: String, heldId: String): ByteArray? {
        val store = recordings ?: return null
        val key = VoiceRecordingRules.key(householdId, heldId) ?: return null
        fun field(f: String) = fields.latest(householdId, EntityTypes.HELD_MESSAGE, heldId, f)
        val kind = (field(HeldMessageFields.KIND) as? FieldValue.Text)?.value?.let { k -> CaptureKind.entries.firstOrNull { it.name == k } }
        val at = (field(HeldMessageFields.AT) as? FieldValue.Int64)?.value
        val playable = VoiceRecordingRules.playable(
            kind, audio = field(HeldMessageFields.AUDIO) == FieldValue.Bool(true),
            gone = gone(householdId, heldId), atMs = at, nowMs = now(),
            keepDays = keepDays(householdId),
        )
        return if (playable) store.get(key) else null
    }

    /**
     * A device's push cleared or deleted [heldIds] (Meka 2026-10-20 17:34): only the recordings now gone for good
     * ([VoiceRecordingRules.gone]: Delete recording, or a Done from an app older than that, which blanked the words)
     * are deleted at once; a Done that kept them leaves them for Activity → Calls until the 7-day sweep.
     */
    fun forgetGoneRecordings(householdId: String, heldIds: Collection<String>) =
        forgetRecordings(householdId, heldIds.filter { runCatching { gone(householdId, it) }.getOrDefault(false) })

    /** Deletes [heldIds]' recordings (and any transcript being made) at once. */
    fun forgetRecordings(householdId: String, heldIds: Collection<String>) {
        val store = recordings ?: return
        for (id in heldIds) VoiceRecordingRules.key(householdId, id)?.let { key ->
            runCatching { store.delete(key) }
            // A transcript still being made is deleted too (Transcribe's own clean-up also removes it).
            if (transcriber != null) runCatching { store.delete(Transcripts.outputKey(key)) }
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

    private fun settled(hh: String, id: String) =
        ops.find(hh, opId(id, HeldMessageFields.TEXT)) != null || ops.find(hh, opId(id, HeldMessageFields.NO_TRANSCRIPT)) != null

    /** An older app's Done blanked the words (a Done from 2026-10-20 keeps them, [HeldMessageFields.KEPT_ON_DONE]). */
    private fun wordsGone(hh: String, id: String): Boolean =
        fields.latest(hh, EntityTypes.HELD_MESSAGE, id, HeldMessageFields.CLEARED) == FieldValue.Bool(true) &&
            fields.latest(hh, EntityTypes.HELD_MESSAGE, id, HeldMessageFields.KEPT_ON_DONE) != FieldValue.Bool(true)

    /** The message's recording is gone for good ([VoiceRecordingRules.gone]): nothing is kept or played for it. */
    private fun gone(hh: String, id: String): Boolean {
        fun on(f: String) = fields.latest(hh, EntityTypes.HELD_MESSAGE, id, f) == FieldValue.Bool(true)
        return VoiceRecordingRules.gone(on(HeldMessageFields.CLEARED), on(HeldMessageFields.KEPT_ON_DONE), on(HeldMessageFields.RECORDING_DELETED))
    }

    /** Appends the fields not written before; returns whether anything was written. */
    private fun write(
        hh: String, id: String, values: Map<String, FieldValue>, type: String = EntityTypes.HELD_MESSAGE, opBase: String = id,
    ): Boolean = ops.transaction {
        var appended = false
        for ((field, value) in values) {
            val opId = opId(opBase, field)
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
        /** How long the answer to "who's calling?" waits for the number's lookup (the phone service waits up to 15 s). */
        const val REPUTATION_WAIT_MS = 1_500L
        /** How long a caller's answer is held for their message (a message is at most two minutes). */
        const val INTRO_KEEP_MS = 10 * 60_000L

        fun opId(heldId: String, field: String) = "srvvoice$heldId${field.lowercase().filter(Char::isLetterOrDigit)}"
    }
}

/** The voice routes' dependencies; null in [mekaSync] leaves them out. */
class VoiceRoutes(
    val assistant: CallAssistant,
    providers: List<VoiceProvider>,
    val publicUrl: String,
    /** The lines in a chosen Google voice (natural voice, slice 3); null when the server has no Google voices. */
    val clips: CallClips? = null,
) {
    val providers: Map<String, VoiceProvider> = providers.associateBy { it.id }
}

/**
 * Keeps the chosen Google voice's call lines ready (natural voice, slice 3): shortly after start and then every
 * [SpeechService.REFRESH_MS], so the first call after a deploy or a new choice already hears that voice. Lines already
 * made cost nothing; nothing happens while a Polly voice is chosen.
 */
fun startCallClipsWarm(voice: VoiceRoutes, periodMs: Long = SpeechService.REFRESH_MS) {
    val clips = voice.clips ?: return
    Thread.ofVirtual().name("call-clips-warm").start {
        Thread.sleep(20_000)
        while (true) {
            runCatching { voice.assistant.voice().google?.let { clips.warm(it) } }
            Thread.sleep(periodMs)
        }
    }
}

/** A GET's answer, no redirects followed ([TwilioVoice.fetchRecording]). */
class HttpFetched(val status: Int, val body: ByteArray, val location: String? = null)

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
    /** GET [url] with [headers], no redirects followed: the status, the body (at most [VoiceRecordingRules.MAX_BYTES] + 1 bytes) and `Location`. */
    private val get: (url: String, headers: Map<String, String>) -> HttpFetched = { url, headers -> jdkGet(url, headers) },
) : VoiceProvider, CreditSource {
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
            VoiceStep.INCOMING -> VoiceEvent.Incoming(call, from, form["To"])
            VoiceStep.INTRODUCED -> VoiceEvent.Introduced(call, from, form["SpeechResult"])
            VoiceStep.RECORDED -> VoiceEvent.Recorded(call, from, form["RecordingDuration"]?.trim()?.toIntOrNull() ?: 0)
            VoiceStep.ANSWERED -> VoiceEvent.Answered(call, form["Digits"], form["SpeechResult"], from = from, to = form["To"])
            VoiceStep.ANSWERED_AGAIN -> VoiceEvent.Answered(call, form["Digits"], form["SpeechResult"], again = true, from = from, to = form["To"])
            VoiceStep.WHISPER -> VoiceEvent.Whisper(call, parentOf(form))
            VoiceStep.WHISPER_ANSWERED -> VoiceEvent.WhisperAnswered(call, parentOf(form), form["Digits"])
            VoiceStep.DIALED -> VoiceEvent.Dialed(call)
            VoiceStep.TRANSCRIBED -> VoiceEvent.Transcribed(
                call, form["TranscriptionText"].takeIf { form["TranscriptionStatus"] == "completed" }, form["RecordingSid"]?.trim(),
            )
            else -> null
        }
    }

    /** The caller's call a put-through leg belongs to (`ParentCallSid`), or null. */
    private fun parentOf(form: Map<String, String>): String? = form["ParentCallSid"]?.trim()?.takeIf { it.isNotEmpty() && it.length <= 64 }

    override fun render(reply: VoiceReply, baseUrl: String, voice: CallVoice, clip: (voice: String, text: String) -> String?): Pair<String, String> {
        val base = baseUrl.trimEnd('/')
        val name = voiceName(voice)
        // A chosen Google voice's ready clip is played; any other line is said in the Polly voice (natural voice, slice 3).
        fun say(text: String) = voice.google?.let { g -> runCatching { clip(g, text) }.getOrNull() }
            ?.let { "<Play>${xml(base + it)}</Play>" } ?: sayIn(name, text)
        fun url(step: String) = xml(base + VoiceStep.path(ID, step))
        val s = CallAssistantScript
        val body = when (reply) {
            // The call assistant talks to them (2026-10-17): greet, then ask who's calling; silence comes back too.
            is VoiceReply.AskWho -> say(s.greeting(reply.atWork)) +
                """<Gather input="speech" timeout="${CallIntroRules.INTRO_SECONDS}" speechTimeout="auto" language="en-GB" """ +
                """hints="${CallIntroRules.HINTS}" actionOnEmptyResult="true" action="${url(VoiceStep.INTRODUCED)}" method="POST">""" +
                say(CallIntroRules.INTRO_QUESTION) + "</Gather>" +
                """<Redirect method="POST">${url(VoiceStep.INTRODUCED)}</Redirect>"""
            is VoiceReply.TakeMessage -> (if (reply.greet) say(s.greeting(reply.atWork)) + say(s.RECORD_PROMPT) else say(CallIntroRules.RECORD_AFTER_INTRO)) +
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
            // Item (c) slice 2: ring his mobile from the assistant's own number; the whisper plays when he answers.
            is VoiceReply.PutThrough -> say(PutThroughRules.HOLD) +
                """<Dial timeout="${PutThroughRules.RING_SECONDS}" callerId="${xml(reply.callerId)}" action="${url(VoiceStep.DIALED)}" method="POST">""" +
                """<Number url="${url(VoiceStep.WHISPER)}" method="POST">${xml(reply.mobile)}</Number></Dial>"""
            is VoiceReply.Whisper ->
                """<Gather input="dtmf" numDigits="1" timeout="${PutThroughRules.WHISPER_SECONDS}" action="${url(VoiceStep.WHISPER_ANSWERED)}" method="POST">""" +
                    say(reply.text) + "</Gather><Hangup/>"
            VoiceReply.Connect -> say(PutThroughRules.CONNECTING)
            VoiceReply.HangUp -> "<Hangup/>"
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

    override fun creditConfigured(): Boolean =
        accountSid()?.trim()?.let { accountSidPattern.matches(it) } == true && !authToken().isNullOrBlank()

    /**
     * The account's balance (`Balance.json`: `{"currency": "GBP", "balance": "4.20"}`) and status (the account's own
     * resource: active, suspended or closed). Null when the balance couldn't be read and the account isn't known to be
     * suspended (low-balance guard).
     */
    override fun readCredit(): CallCreditReading? {
        val sid = accountSid()?.trim()?.takeIf { accountSidPattern.matches(it) } ?: return null
        val token = authToken()?.takeIf { it.isNotBlank() } ?: return null
        val headers = mapOf("Authorization" to "Basic " + Base64.getEncoder().encodeToString("$sid:$token".toByteArray(Charsets.UTF_8)))
        fun json(url: String) = runCatching {
            val r = get(url, headers)
            if (r.status != 200) null else Json.parseToJsonElement(r.body.decodeToString()).jsonObject
        }.getOrNull()
        val status = json("https://api.twilio.com/2010-04-01/Accounts/$sid.json")?.get("status")?.jsonPrimitive?.content
        val active = when (status) { "active" -> true; "suspended", "closed" -> false; else -> null }
        val balance = json("https://api.twilio.com/2010-04-01/Accounts/$sid/Balance.json")
        val amount = balance?.get("balance")?.jsonPrimitive?.content
        if (amount == null && active != false) return null
        return CallCreditReading(active, amount, balance?.get("currency")?.jsonPrimitive?.content)
    }

    override fun fetchRecording(recording: String): ByteArray? {
        if (!recordingSid.matches(recording)) return null
        val sid = accountSid()?.trim()?.takeIf { accountSidPattern.matches(it) } ?: return null
        val token = authToken()?.takeIf { it.isNotBlank() } ?: return null
        val auth = Base64.getEncoder().encodeToString("$sid:$token".toByteArray(Charsets.UTF_8))
        val first = runCatching {
            get("https://api.twilio.com/2010-04-01/Accounts/$sid/Recordings/$recording.mp3", mapOf("Authorization" to "Basic $auth"))
        }.getOrNull() ?: return null
        // Twilio may answer with a short-lived signed address for the media: follow it once, without the credentials.
        val answer = if (first.status in 300..399) {
            val to = first.location?.takeIf { it.startsWith("https://") } ?: return null
            runCatching { get(to, emptyMap()) }.getOrNull() ?: return null
        } else first
        if (answer.status != 200) return null
        return answer.body.takeIf { it.isNotEmpty() && it.size <= VoiceRecordingRules.MAX_BYTES }
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

        fun jdkGet(url: String, headers: Map<String, String>): HttpFetched {
            val req = java.net.http.HttpRequest.newBuilder(java.net.URI(url)).timeout(java.time.Duration.ofSeconds(30)).GET()
            headers.forEach { (k, v) -> req.header(k, v) }
            val client = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NEVER).build()
            val resp = client.send(req.build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream())
            val body = resp.body().use { it.readNBytes(VoiceRecordingRules.MAX_BYTES + 1) }
            return HttpFetched(resp.statusCode(), body, resp.headers().firstValue("Location").orElse(null))
        }

        fun jdkDelete(url: String, headers: Map<String, String>): Int {
            val req = java.net.http.HttpRequest.newBuilder(java.net.URI(url)).timeout(java.time.Duration.ofSeconds(15)).DELETE()
            headers.forEach { (k, v) -> req.header(k, v) }
            return java.net.http.HttpClient.newHttpClient().send(req.build(), java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()
        }

        /**
         * MEKA's voice as Twilio names it: generative where Twilio has it, else neural; a voice Twilio doesn't offer
         * says the call in Obi's (Brian's) neural voice rather than failing the call.
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
         * Reads `{"account_sid": …, "auth_token": …}` from Secrets Manager by ARN through SecretCache (six hours; 15 minutes while unset); each is null
         * while unset (`{}`).
         */
        fun fromSecret(secretId: String): TwilioVoice {
            val secret = os.meka.backend.integrations.SecretCache.shared.reader(secretId) { raw ->
                Json.parseToJsonElement(raw ?: return@reader null).jsonObject
                    .mapValues { (_, e) -> e.jsonPrimitive.content.trim() }.takeIf { m -> m["auth_token"].orEmpty().isNotEmpty() }
            }
            fun read(key: String): String? = secret()?.get(key)?.takeIf { it.isNotEmpty() }
            return TwilioVoice(authToken = { read("auth_token") }, accountSid = { read("account_sid") })
        }
    }
}
