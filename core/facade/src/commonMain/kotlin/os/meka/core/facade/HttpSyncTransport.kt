package os.meka.core.facade

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import os.meka.core.sync.AuthRejectedException
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PullResponse
import os.meka.core.sync.PushRequest
import os.meka.core.sync.PushResponse
import os.meka.core.sync.SyncTransport
import os.meka.core.sync.TransportException
import os.meka.core.wire.AskCodec
import os.meka.core.wire.SpeechCodec
import os.meka.core.wire.WireCodec
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Connected calendar/email accounts as the apps show them. Tokens never leave the server. */
data class ConnectedAccount(
    val provider: String, val email: String, val status: String, val lastSyncAtMs: Long?,
    /** MEKA may add and change events here (calendar editing; the owner allowed it). */
    val canEdit: Boolean = false,
    /**
     * The row's title (Meka's screenshot 2026-10-09 09:01): a feed's name, or a signed-in account's main calendar name
     * ("Personal", "Hotmail", or Meka's own); [MekaCore.connectedAccounts] fills Meka's names in.
     */
    val title: String = os.meka.core.domain.CalendarAccountRules.title(provider, email),
    /**
     * "No events in the next 30 days" / "12 events in the next 30 days" for a signed-in account (Meka's 10:48
     * screenshots: is Hotmail coming through?); [MekaCore.connectedAccounts] fills it in from the mirrored events.
     */
    val eventsLine: String? = null,
    /** Full news hub, slice 5: a news account's feeds the server couldn't read at its last tries. */
    val newsDown: List<os.meka.core.domain.NewsFeedDown> = emptyList(),
    /**
     * When it last synced as both apps show it ("17:11", "Tue 08:29"), on the core's clock and zone; [MekaCore.connectedAccounts]
     * fills it in (quality gate slice 33).
     */
    val syncedAt: String? = null,
) {
    val needsReconnect: Boolean get() = status == "needs_reconnect"
    /** The status line as Calendars draws it: [syncedAt]'s time, dots and last words held together ([os.meka.core.domain.LoneWordGlue]). */
    val shownStatus: String get() = os.meka.core.domain.LoneWordGlue.kept(statusLine(syncedAt))
    /** [editingLine] as drawn, its dots and last words held together. */
    val editingShown: String? get() = editingLine?.let(os.meka.core.domain.LoneWordGlue::kept)
    /** "Google · meka@gmail.com · synced 08:29" · "Headlines · synced 08:29"; [syncedAt] as the device writes the time. */
    fun statusLine(syncedAt: String?): String = os.meka.core.domain.CalendarAccountRules.statusLine(provider, email, status, syncedAt, title)
    /** The line about editing under the account, or null (feeds, or the reconnect line says enough). */
    val editingLine: String? get() = os.meka.core.domain.CalendarAccessRules.line(provider, canEdit, needsReconnect)
    /** Allow editing · Stop editing · none. */
    val editingAction: os.meka.core.domain.CalendarAccessAction? get() = os.meka.core.domain.CalendarAccessRules.action(provider, canEdit, needsReconnect)
}

sealed class ConnectStart {
    /** Open this in the system browser; the provider sends the owner back to the server, not the app. */
    data class OpenBrowser(val url: String) : ConnectStart()
    /** The provider's credentials have not been set up on the server yet. */
    object NotSetUp : ConnectStart()
    data class Failed(val reason: String) : ConnectStart()
}

/** The server can't take a push address right now (no push route, or this device's key isn't registered yet). */
class PushUnavailableException : Exception("push isn't available on this server yet")

/** Push addresses (build plan M1: push via Firebase), available once the device is connected. */
interface PushApi {
    /** Registers this device's address for [service] (`fcm`), or removes it with an empty [token]. */
    suspend fun registerPushToken(service: String, token: String)
}

/**
 * News pictures (news, images slice): the server fetched each story's picture from its feed, made a small JPEG and
 * keeps it under a key; devices fetch it from their own server only, never from the publisher.
 */
interface NewsImagesApi {
    /** The picture's bytes, or null when the server has none under [key] (made later, or tidied away). */
    suspend fun newsImage(key: String): ByteArray?
}

/** What MEKA's server said to a question (Ask MEKA, V1 AI layer): words and proposed actions, or why there are none. */
sealed class AskReply {
    /**
     * [firstText] / [firstAudio] (base64 MP3): the answer's first spoken piece, said by the server with the answer when
     * [AiApi.askSpoken] asked for it (voice completeness, slice 3); both or neither.
     */
    data class Answered(
        val text: String, val actions: List<os.meka.core.domain.AskRawAction>,
        val firstText: String? = null, val firstAudio: String? = null,
        /** The companion, slice 2: the web results the answer drew on, as (title, https URL); checked again on the core. */
        val sources: List<Pair<String, String>> = emptyList(),
        /** The companion, slice 5: the server's note of what this conversation was about; checked again on the core. */
        val note: String? = null,
    ) : AskReply()
    /** [state]: off · over (the month's budget) · failed, with the server's [reason]. */
    data class Unavailable(val state: String, val reason: String?) : AskReply()
}

/** What the server says about MEKA's AI (`POST /v1/ai/status`): never the key. Budget fields are null on an older server. */
/** [today]: the household day's AI calls per feature (Activity's daily count); empty from an older server. */
data class AiStatusReply(
    val state: String, val reason: String?, val spentCents: Long?, val budgetCents: Long?, val level: String?,
    val today: List<os.meka.core.domain.AiDayUse> = emptyList(),
    /** The month's calls and cost per feature (Settings → Navilife's AI); empty from an older server. */
    val month: List<os.meka.core.domain.AiMonthUse> = emptyList(),
    /** Anthropic refused the key itself (Health red). */
    val keyRefused: Boolean = false,
)

/** Ask MEKA (V1 AI layer, slice 3), available once the device is connected. */
interface AiApi {
    /** [history]: the conversation so far (Talk to MEKA), oldest first; [voice]: the answer will be spoken. */
    suspend fun ask(
        question: String,
        context: os.meka.core.domain.AskContext,
        history: List<os.meka.core.domain.TalkTurn> = emptyList(),
        voice: Boolean = false,
    ): AskReply

    /**
     * Voice completeness, slice 3 (the first words sooner): a spoken question ([ask] with voice) that also asks the
     * server to say the answer's first piece in [speakVoice] (null: the default) and send it with the answer. A
     * transport without it asks as before.
     */
    suspend fun askSpoken(
        question: String,
        context: os.meka.core.domain.AskContext,
        history: List<os.meka.core.domain.TalkTurn>,
        speakVoice: String?,
    ): AskReply = ask(question, context, history, voice = true)

    /**
     * The companion, slice 9 (speech that starts within about a second): [askSpoken], with the answer's first sentence
     * handed to [onFirst] (its words, its clip as base64 MP3, the voice that said it) as soon as the server has said it,
     * while the model is still writing the rest. A transport or server without it asks as [askSpoken] does.
     */
    suspend fun askStreamed(
        question: String,
        context: os.meka.core.domain.AskContext,
        history: List<os.meka.core.domain.TalkTurn>,
        speakVoice: String?,
        onFirst: suspend (text: String, audio: String, voice: String?) -> Unit,
        /**
         * Talking to Ada like a conversation, slice 7: each later piece the server said ahead (its words, its clip as
         * base64 MP3, the voice), before the answer returns. Asking for them is what tells the server this device reads them.
         */
        onNext: (suspend (text: String, audio: String, voice: String?) -> Unit)? = null,
    ): AskReply = askSpoken(question, context, history, speakVoice)
    /** Whether MEKA's AI is on and the month's spend; null when the server has no AI layer. */
    suspend fun aiStatus(): AiStatusReply? = null

    /**
     * Requests from people Meka watches (`POST /v1/ai/message-request`): one message's text, its sender's label and
     * time; the proposals come back unchecked ([os.meka.core.domain.MessageRequestRules.check] reads them). A server
     * without the route answers "off".
     */
    suspend fun messageRequest(request: os.meka.core.wire.MessageRequestCodec.Request): os.meka.core.wire.MessageRequestCodec.Response =
        os.meka.core.wire.MessageRequestCodec.Response(AskCodec.Response.OFF)

    /**
     * "Handle this" (`POST /v1/ai/handle-this`): one piece of text Meka shared with MEKA and its subject; the proposals
     * come back unchecked ([os.meka.core.domain.MessageRequestRules.check] reads them). A server without the route
     * answers "off".
     */
    suspend fun handleThis(request: os.meka.core.wire.HandleThisCodec.Request): os.meka.core.wire.MessageRequestCodec.Response =
        os.meka.core.wire.MessageRequestCodec.Response(AskCodec.Response.OFF)

    /**
     * A voice note's tidy-up (`POST /v1/ai/voice-note`, Voice notes into actions, slice 2): the note's words only; a
     * tidy note, the people and proposals come back unchecked. A server without the route answers "off".
     */
    suspend fun voiceNote(request: os.meka.core.wire.VoiceNoteCodec.Request): os.meka.core.wire.VoiceNoteCodec.Response =
        os.meka.core.wire.VoiceNoteCodec.Response(AskCodec.Response.OFF)

    /**
     * "Break it down" (`POST /v1/ai/goal-breakdown`, Goals, properly, slice 4): one goal in Meka's words; proposed
     * milestones and first steps come back unchecked ([os.meka.core.domain.GoalBreakdownRules.check] reads them). A
     * server without the route answers "off".
     */
    suspend fun goalBreakdown(request: os.meka.core.wire.GoalBreakdownCodec.Request): os.meka.core.wire.GoalBreakdownCodec.Response =
        os.meka.core.wire.GoalBreakdownCodec.Response(AskCodec.Response.OFF)

    /**
     * The morning brief written by MEKA's AI (`POST /v1/ai/brief-script`, slice 2): the brief's facts; the spoken text
     * comes back unchecked ([os.meka.core.domain.BriefAiRules.check] reads it). A server without the route answers "off".
     */
    suspend fun briefScript(request: os.meka.core.wire.BriefScriptCodec.Request): os.meka.core.wire.BriefScriptCodec.Response =
        os.meka.core.wire.BriefScriptCodec.Response(AskCodec.Response.OFF)

    /**
     * The messages assistant (`POST /v1/ai/message-triage`): one message's text, its sender's label, the group's name when
     * it named Meka, and its time; the lane, gist, draft and proposals come back unchecked
     * ([os.meka.core.domain.MessageTriageRules.check] reads them). A server without the route answers "off".
     */
    suspend fun messageTriage(request: os.meka.core.wire.MessageTriageCodec.Request): os.meka.core.wire.MessageTriageCodec.Response =
        os.meka.core.wire.MessageTriageCodec.Response(AskCodec.Response.OFF)

    /**
     * The group digest's gist (`POST /v1/ai/group-digest`): the busy groups' names and latest lines at a digest time; a
     * gist per group and any asks come back unchecked ([os.meka.core.domain.GroupGistRules.check] reads them). A server
     * without the route answers "off".
     */
    suspend fun groupDigest(request: os.meka.core.wire.GroupDigestCodec.Request): os.meka.core.wire.GroupDigestCodec.Response =
        os.meka.core.wire.GroupDigestCodec.Response(AskCodec.Response.OFF)

    /**
     * A busy chat's summary (`POST /v1/ai/chat-summary`, messages grouped per chat slice 3c): the busy chats' names and
     * MEKA's gists of their messages; one line per chat comes back unchecked ([os.meka.core.domain.ChatSummaryRules.check]
     * reads them). A server without the route answers "off".
     */
    suspend fun chatSummary(request: os.meka.core.wire.ChatSummaryCodec.Request): os.meka.core.wire.ChatSummaryCodec.Response =
        os.meka.core.wire.ChatSummaryCodec.Response(AskCodec.Response.OFF)

    /**
     * Fresh hello openers (`POST /v1/ai/hello-openers`, the orb says hello like a person slice 4): how many per part of
     * the day, nothing about him; openers come back unchecked ([os.meka.core.domain.HelloFreshRules.check] reads them).
     * A server without the route answers "off".
     */
    suspend fun helloOpeners(request: os.meka.core.wire.HelloOpenersCodec.Request): os.meka.core.wire.HelloOpenersCodec.Response =
        os.meka.core.wire.HelloOpenersCodec.Response(AskCodec.Response.OFF)

    /**
     * A reply drafted by MEKA's AI (`POST /v1/ai/mail-draft`, Inbox autopilot slice 6b-2b): one email as its card shows
     * it and a few of Meka's own earlier replies; the draft comes back unchecked ([os.meka.core.domain.MailDraftRules.check]
     * reads it) and only ever goes into his editor. A server without the route answers "off".
     */
    suspend fun mailDraft(request: os.meka.core.wire.MailDraftCodec.Request): os.meka.core.wire.MailDraftCodec.Response =
        os.meka.core.wire.MailDraftCodec.Response(AskCodec.Response.OFF)
}

/** What [MekaCore.gistGroupDigest] did. */
sealed class GistRead {
    /** No digest is due (before 12:30), or every group in it already has its card for this slot. */
    data object NotDue : GistRead()
    /** The digest's cards written ([groups]: their keys) and the Needs you cards its asks made. */
    data class Read(
        val groups: List<String>,
        val replies: List<os.meka.core.domain.TriageCard>,
        val requests: List<os.meka.core.domain.RequestCard> = emptyList(),
    ) : GistRead()
    /** Couldn't ask: offline, or the call failed; [line] says which. Nothing written, so it is tried again. */
    data class Unavailable(val line: String) : GistRead()
}

/** What [MekaCore.summariseChats] did. */
sealed class ChatSummaryRead {
    /** No busy chat needs a summary (none has four or more messages, or each already has one for its messages). */
    data object NotDue : ChatSummaryRead()
    /** The summaries written; [chats]: the keys of the chats that got a line. */
    data class Read(val chats: List<String>) : ChatSummaryRead()
    /** Couldn't ask: offline, AI off, or the call failed; [line] says which. Nothing written, so it is tried again. */
    data class Unavailable(val line: String) : ChatSummaryRead()
}

/** What a Caught up on the group digest changed, for its Undo. */
class GroupDigestUndo internal constructor(internal val before: Map<String, Long?>)

/** What [MekaCore.triageMessage] did with one captured message. */
sealed class TriageRead {
    /** Nothing to triage: a missed call, an empty message, an ignored group, a Normal group's chatter, already triaged. */
    data object Skipped : TriageRead()
    /** A Digest group's chatter: the Fold keeps it (sealed, on the phone) for the digest; no AI, nothing synced. */
    data class Digest(val groupKey: String) : TriageRead()
    /**
     * Triaged: the card Needs you shows (Needs a reply or FYI; null for an Action, or a message with nothing worth a card)
     * and the request cards an Action or a voice note made.
     */
    data class Read(
        val card: os.meka.core.domain.TriageCard?,
        val requests: List<os.meka.core.domain.RequestCard> = emptyList(),
    ) : TriageRead()
    /** Couldn't ask: offline, AI off, the month's budget spent; [line] says which. Not stored, so it is tried again. */
    data class Unavailable(val line: String) : TriageRead()
}

/** What [MekaCore.readRequest] did with one captured message. */
sealed class RequestRead {
    /** Not a message MEKA may read (not watched, a group, a missed call, a bare photo). */
    data object Skipped : RequestRead()
    /** Read: the new Needs you cards (none when the message asked for nothing, or only repeated an open card). */
    data class Read(val cards: List<os.meka.core.domain.RequestCard>) : RequestRead()
    /** Couldn't ask: offline, AI off, the month's budget spent; [line] says which. */
    data class Unavailable(val line: String) : RequestRead()
}

/** What "Break it down" proposed for a goal ([MekaCore.breakDownGoal], Goals, properly, slice 4). */
sealed class BreakdownOutcome {
    /** Milestones and first steps for Meka to tick; [line] "4 milestones and 2 first steps to choose from". */
    data class Proposals(val breakdown: os.meka.core.domain.GoalBreakdown, val line: String) : BreakdownOutcome()
    /** MEKA answered, with nothing the goal doesn't already have; [line] says so. */
    data class NothingNew(val line: String) : BreakdownOutcome()
    /** Couldn't ask: not connected, offline, AI off, the month's budget spent, or the goal is full; [line] says which. */
    data class Unavailable(val line: String) : BreakdownOutcome()
}

/** What Keep added from a breakdown ([MekaCore.keepBreakdown]): [line] for the bar, and what Undo takes back. */
class BreakdownKept internal constructor(
    val line: String,
    internal val milestoneIds: List<String>,
    internal val taskIds: List<String>,
) {
    /** Nothing was added (everything ticked was already there). */
    val isEmpty: Boolean get() = milestoneIds.isEmpty() && taskIds.isEmpty()
}

/**
 * What a voice note's tidy-up made of its words ([MekaCore.tidyVoiceNote], Voice notes into actions, slice 2): [tidy]
 * the AI's tidy note ("" when it gave none, or it couldn't answer), [people] the names it mentions, and [cards] the
 * cards, as "Handle this" gives them (the same lines). Nothing is kept here: the phone keeps [tidy] and [people] with
 * the note.
 */
data class VoiceNoteRead(val tidy: String, val people: List<String>, val cards: HandleOutcome) {
    /** How many cards it offered: 0 when there was nothing to add, -1 when it couldn't answer (offline, AI off). */
    val cardCount: Int
        get() = when (val c = cards) {
            is HandleOutcome.Cards -> c.cards.size
            is HandleOutcome.NothingToDo -> 0
            is HandleOutcome.Unavailable -> -1
        }

    /** "Mentions Sam and Alex" · null. */
    val peopleLine: String? get() = os.meka.core.domain.VoiceNoteRules.peopleLine(people)

    /** The capture sheet's line: what came of it, or (couldn't answer) that Make cards waits in Voice notes. */
    val captureLine: String
        get() = when (val c = cards) {
            is HandleOutcome.Cards -> c.line
            is HandleOutcome.NothingToDo -> c.line
            is HandleOutcome.Unavailable ->
                c.line + " · " + os.meka.core.domain.VoiceNoteRules.MAKE_CARDS.lowercase() + " in Voice notes later"
        }

    /** Voice notes' line after Make cards: the cards wait in Needs you. */
    val paneLine: String
        get() = when (val c = cards) {
            is HandleOutcome.Cards -> c.line + " · in Needs you"
            is HandleOutcome.NothingToDo -> c.line
            is HandleOutcome.Unavailable -> c.line
        }
}

/** What "Handle this" made of a share ([MekaCore.handleThis]). */
sealed class HandleOutcome {
    /** The share's cards, also waiting in Needs you on both apps; [line] "2 things to add". */
    data class Cards(val cards: List<os.meka.core.domain.RequestCard>, val line: String) : HandleOutcome()
    /** Read, and nothing in it for Meka to do (or already handled today); [line] says which. */
    data class NothingToDo(val line: String) : HandleOutcome()
    /** Couldn't read it: too little to read, offline, AI off, the month's budget spent; [line] says which. */
    data class Unavailable(val line: String) : HandleOutcome()
}

/**
 * What Add or Change on a request card did ([MekaCore.acceptRequest], [MekaCore.changeRequest]): [line] for the undo
 * bar, and what [MekaCore.undoRequest] takes back — the task made, the calendar edit (inside its five seconds), or the
 * work-from-home day (-1 when none), the renewal put on the radar, or the booking put into Trips. [copyText] ("Handle this", slice 3): a reply's
 * draft for the app to put on the clipboard; such a done has nothing to undo ([undoable] false), since nothing was sent
 * or added.
 */
data class RequestDone(
    val line: String,
    val taskId: String?,
    val editId: String?,
    val homeDay: Long = -1,
    val renewalId: String? = null,
    val copyText: String? = null,
    /** The booking put into Trips (Travel mode, slice 2b); Undo takes it out. */
    val bookingId: String? = null,
) {
    /** Whether the bar offers Undo: something was made that Undo can take back. */
    val undoable: Boolean get() = copyText == null &&
        (taskId != null || editId != null || homeDay >= 0 || renewalId != null || bookingId != null)
}

/** An answer to an approval card: [done] when it was taken, and the line to show ("Approved · …", or why not). */
data class ApprovalAnswer(val done: Boolean, val line: String)

/**
 * MEKA's voice (Weather and a voice, item 3): the server says a piece of MEKA's own words with Amazon Polly. Only
 * MEKA's words are sent, never Meka's. Available once the device is connected.
 */
interface SpeechApi {
    /** [voice]: a Polly name, or null for the server's default. A server without a voice answers "off". */
    suspend fun speak(text: String, voice: String?): os.meka.core.wire.SpeechCodec.Response
    /**
     * Obi's face, slice 2: [speak] with the clip's mouth sounds too ([os.meka.core.wire.SpeechCodec.Response.visemes]),
     * for a device drawing the face. A transport that can't ask for them just speaks.
     */
    suspend fun speakMarked(text: String, voice: String?): os.meka.core.wire.SpeechCodec.Response = speak(text, voice)
    /** The voices the server can speak with and the month's characters. */
    suspend fun speechVoices(): os.meka.core.wire.SpeechCodec.Voices
}

/**
 * The orb speed pass, slice 5: "he's about to talk" (`POST /v1/talk/warm`): Navilife's server opens its connections to
 * the AI and to Navilife's voice ahead of the answer. Nothing he said or anything about him is sent. False when an
 * older server lacks the route or has nothing to warm. Network failures throw.
 */
interface TalkWarmApi {
    suspend fun talkWarm(): Boolean
}

/**
 * Talking to Ada like a conversation, slice 2: how long the server took over the last day's answers (`POST
 * /v1/talk/speed`), times only. Null from an older server without the route. Network failures throw.
 */
interface ServerSpeedApi {
    suspend fun serverSpeed(): List<os.meka.core.wire.ServerSpeedCodec.Turn>?
}

/**
 * Ada's Live mode, slice 1: the month's Live meter, a one-session Gemini Live token (Google's, never the key) and the
 * seconds a session heard and spoke. Null from an older server without the routes. Network failures throw.
 */
interface LiveApi {
    suspend fun liveStatus(): os.meka.core.wire.LiveCodec.Meter?
    /**
     * Slice 4: [day] is the compact day the session starts with (null: none, as an older device). Slice 2c: [resume] is
     * Google's handle for a conversation carrying on into a fresh window.
     */
    suspend fun liveSession(voice: String?, day: os.meka.core.wire.LiveCodec.Day? = null, resume: String? = null): os.meka.core.wire.LiveCodec.Session?
    suspend fun liveUsage(sessionId: String, inSeconds: Long, outSeconds: Long): os.meka.core.wire.LiveCodec.Usage?
    /** Slice 4: a web search Ada asked for in a Live session. Null from a server without the route. */
    suspend fun liveSearch(request: os.meka.core.wire.LiveCodec.SearchRequest): os.meka.core.wire.LiveCodec.SearchResult? = null
}

/**
 * "Where I am now" (Places item 3): the forecast at an approximate point. The point must already be rounded
 * ([os.meka.core.domain.HereRules.round]); the server refuses anything finer. Available once the device is connected.
 */
interface HereApi {
    suspend fun hereWeather(lat: Double, lon: Double): os.meka.core.wire.HereCodec.Response
}

/**
 * Cheapest fuel nearby (Car and parking, slice 6): the forecourts near an approximate point from the server's copy of
 * the government's Fuel Finder data. The point must already be rounded ([os.meka.core.domain.HereRules.round]).
 */
interface FuelApi {
    suspend fun fuelNearby(lat: Double, lon: Double, fuel: String): os.meka.core.wire.FuelCodec.Response
}

/**
 * Spam-call lookup (2026-10-17): what the web's report sites say about a caller's number, from Navilife's server
 * (`POST /v1/calls/lookup`), waiting at most [waitMs] for a fresh search. The Fold asks only about callers it doesn't know.
 */
interface NumberLookupApi {
    suspend fun lookUpNumber(number: String, waitMs: Long): os.meka.core.wire.NumberLookupCodec.Response
}

/**
 * The Vault's encrypted copies (Vault slice 2b): sealed bytes (base64) in and out of Navilife's server by the copy's
 * random id. [vaultFileGet] is null when the server has none; [vaultFilePut] and [vaultFileDelete] false when an older
 * server lacks the route. Network failures throw.
 */
interface VaultFileApi {
    suspend fun vaultFilePut(id: String, sealed: String): Boolean
    suspend fun vaultFileGet(id: String): String?
    suspend fun vaultFileDelete(id: String): Boolean
}

/**
 * Trading tracker, slice 8: a tax year's Hyperliquid orders and daily funding for the CSV export, from Navilife's
 * server (`POST /v1/trading/export`), waiting at most [waitMs]; "pending" means ask again.
 */
interface TradingExportApi {
    suspend fun tradingExport(fromMs: Long, toMs: Long, waitMs: Long): os.meka.core.wire.TradingExportCodec.Response
}

/**
 * The Health screen's server half (Reliability first, item 3): `POST /v1/health/household`. Null from an older server
 * without the route (the screen then says "Couldn't check" for what only the server knows).
 */
interface HealthApi {
    suspend fun householdHealth(): os.meka.core.wire.HealthCodec.Response?
}

/**
 * Phone update failures (build plan: phone updates sometimes fail to install): `POST /v1/releases/failure` reports
 * one. False from an older server without the route or before this device's key is registered.
 */
interface UpdateFailureApi {
    suspend fun reportUpdateFailure(report: os.meka.core.wire.UpdateFailureCodec.Report): Boolean
}

/**
 * The Fold's posture changes (bedside clock, slice 2): `POST /v1/diagnostics/fold` sends the last few. False from an
 * older server without the route or before this device's key is registered.
 */
interface FoldPostureApi {
    suspend fun reportFoldPostures(report: os.meka.core.wire.FoldPostureCodec.Report): Boolean
}

/**
 * Notifications never seen (slice 2): `POST /v1/diagnostics/notifications` sends the phone's trace (false from an older
 * server or before this device's key is registered); `POST /v1/push/test` asks the server to push a test to this
 * device and returns its word ("sent", "no_address" …), or null from an older server.
 */
interface NotifyDiagnosticsApi {
    suspend fun reportNotifyTrace(report: os.meka.core.wire.NotifyDiagnosticsCodec.Report): Boolean
    suspend fun testPush(): String?
}

/**
 * A caller's recording (call assistant polish 8c): `POST /v1/voice-message/audio` with the held message's id. The MP3's
 * bytes, or null when the server keeps none (or is older and has no route).
 */
interface VoiceMessageApi {
    suspend fun voiceMessageAudio(id: String): ByteArray?
}

/**
 * Family sharing's links (slice 4): `POST /v1/family/invites/list · create · revoke` from a keyed device. Each throws
 * [FamilyUnavailableException] when the server can't answer it (no route, no key yet) and TransportException offline.
 */
interface FamilyApi {
    suspend fun familyInvites(): List<os.meka.core.wire.FamilyCodec.Invite>
    /** The new link's whole address (this server + "/family#<token>"); the token is in it, so it is never kept. */
    suspend fun createFamilyInvite(name: String): Pair<os.meka.core.wire.FamilyCodec.Made, String>
    /** False when the server doesn't know the link. */
    suspend fun revokeFamilyInvite(id: String): Boolean
}

/** The server can't do this for the device: [reason] is NO_ROUTE (older server) or NO_KEY (key not registered yet). */
class FamilyUnavailableException(val reason: String) : Exception(reason) {
    companion object {
        const val NO_ROUTE = "no-route"
        const val NO_KEY = "no-key"
    }
}

/**
 * Linking a watch (Galaxy Watch, slice 1): `POST /v1/devices/link · watches · unlink` from a keyed device. Each throws
 * [FamilyUnavailableException] when the server can't answer it (no route, no key yet), [LinkRefusedException] when it
 * refused the code (wrong · wait · revoked) or didn't know the watch, and TransportException offline.
 */
interface DeviceLinkApi {
    suspend fun linkWatch(code: String): os.meka.core.wire.DeviceLinkCodec.Linked
    suspend fun linkedWatches(): List<os.meka.core.wire.DeviceLinkCodec.Watch>
    suspend fun unlinkWatch(id: String)
    /** Clean-ups (2026-10-19): Allow or Not this one on a new phone or Mac asking to join; the server's allowed · refused · gone. */
    suspend fun answerDeviceJoin(requestId: String, allow: Boolean): String
}

/**
 * The recovery key from a keyed phone or Mac (build plan V1 "Recovery key"): `POST /v1/recovery/status · set`. Throws
 * like [DeviceLinkApi] ([FamilyUnavailableException] without the route or a key yet, TransportException offline).
 */
interface RecoveryApi {
    suspend fun recoveryStatus(): os.meka.core.wire.RecoveryCodec.Status
    /** Saves a new key's verifier (never the words), replacing the old key; returns the new status. */
    suspend fun setRecoveryKey(verifier: String): os.meka.core.wire.RecoveryCodec.Status
}

/** The server refused: [reason] is one of DeviceLinkCodec's ERR_ codes. */
class LinkRefusedException(val reason: String) : Exception(reason)

/** Account management calls, available once the device is connected. */
interface AccountsApi {
    /** [editing]: ask the provider for permission to change events too (calendar editing). */
    suspend fun startConnect(provider: String, editing: Boolean): ConnectStart
    suspend fun accounts(): List<ConnectedAccount>
    /** Gives up editing on one account; returns the accounts as they are now. */
    suspend fun stopEditing(provider: String, email: String): List<ConnectedAccount>
    /** The connected mail accounts with their labels (Inbox autopilot, slice 1). */
    suspend fun mailAccounts(): List<os.meka.core.domain.MailAccountFacts> = emptyList()
    /** Names what a mail account is for; returns the mail accounts as they are now. */
    suspend fun setMailLabel(provider: String, email: String, label: String): List<os.meka.core.domain.MailAccountFacts> =
        throw UnsupportedOperationException("mail labels need Navilife's server")
    /** Disconnects a mail account (slice 1b): the server forgets its sign-in; returns the mail accounts as they are now. */
    suspend fun disconnectMail(provider: String, email: String): List<os.meka.core.domain.MailAccountFacts> =
        throw UnsupportedOperationException("disconnecting mail needs Navilife's server")
    /** Disconnects a mail account and deletes what Navilife made from its mail (completeness: Delete mail data). */
    suspend fun disconnectMailAndDeleteData(provider: String, email: String): List<os.meka.core.domain.MailAccountFacts> =
        throw UnsupportedOperationException("deleting mail data needs Navilife's server")
    /** Read the full email inside Navilife (2026-10-20): one email by Navilife's handle, read from the provider now. */
    suspend fun mailMessage(request: os.meka.core.wire.MailMessageCodec.Request): os.meka.core.wire.MailMessageCodec.Response =
        throw UnsupportedOperationException("reading an email needs Navilife's server")
    /** Read the full email, slice 2: one attachment's bytes, fetched from the provider now. */
    suspend fun mailAttachment(request: os.meka.core.wire.MailMessageCodec.AttachmentRequest): os.meka.core.wire.MailMessageCodec.AttachmentFetch =
        throw UnsupportedOperationException("opening an attachment needs Navilife's server")
}

/**
 * HTTPS transport to the sync service (ADR-005). Every request carries the device's bearer secret and, when the
 * device has a hardware key, an ECDSA signature over method, path, time, nonce and body hash. Once the server has
 * the device's public key it refuses unsigned requests from that device, so a copied secret alone is useless.
 */
@OptIn(ExperimentalTime::class)
class HttpSyncTransport(
    private val client: HttpClient,
    private val baseUrl: String,
    private val deviceKey: DeviceKey? = null,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val deviceSecret: () -> String,
) : SyncTransport, AccountsApi, ReleasesApi, PushApi, NewsImagesApi, AiApi, SpeechApi, HereApi, FuelApi, NumberLookupApi, TradingExportApi, HealthApi, VoiceMessageApi, FamilyApi, DeviceLinkApi, UpdateFailureApi, FoldPostureApi, NotifyDiagnosticsApi, RecoveryApi, VaultFileApi, TalkWarmApi, ServerSpeedApi, LiveApi {
    private var keyRegistered = false

    override suspend fun prepare() {
        val key = deviceKey ?: return
        if (keyRegistered) return
        // Best effort: if registration fails (offline, older server) sync still runs and this retries next round.
        val resp = runCatching { send("/v1/devices/key", WireCodec.encodeDeviceKey(key.publicKeyDerBase64)) }.getOrNull()
        when {
            resp == null -> Unit
            resp.status.isSuccess() -> keyRegistered = true
            // Another key is registered for this device id (or the device was revoked): only re-enrolling fixes it.
            resp.status.value == 401 || resp.status.value == 403 ->
                throw AuthRejectedException("The server no longer recognises this device's key")
        }
    }

    override suspend fun push(request: PushRequest): PushResponse =
        WireCodec.decodePushResponse(post("/v1/sync/push", WireCodec.encodePushRequest(request)))

    override suspend fun pull(request: PullRequest): PullResponse =
        WireCodec.decodePullResponse(post("/v1/sync/pull", WireCodec.encodePullRequest(request)))

    override suspend fun awaitChanges(request: PullRequest): Boolean =
        WireCodec.decodePullResponse(post("/v1/sync/wait", WireCodec.encodePullRequest(request))).ops.isNotEmpty()

    override suspend fun startConnect(provider: String, editing: Boolean): ConnectStart {
        prepare() // connecting accounts requires the device's signing key on the server
        val resp = try {
            send("/v1/integrations/$provider/connect", WireCodec.encodeConnectRequest(editing))
        } catch (e: TransportException) {
            return ConnectStart.Failed("Couldn't reach the server")
        }
        return when {
            resp.status.value == 409 -> ConnectStart.NotSetUp
            !resp.status.isSuccess() -> ConnectStart.Failed("Server returned ${resp.status.value}")
            else -> ConnectStart.OpenBrowser(WireCodec.decodeConnectUrl(resp.bodyAsText()))
        }
    }

    override suspend fun accounts(): List<ConnectedAccount> =
        WireCodec.decodeAccounts(post("/v1/integrations/list", "")).map(::account)

    override suspend fun stopEditing(provider: String, email: String): List<ConnectedAccount> =
        WireCodec.decodeAccounts(post("/v1/integrations/$provider/editing", WireCodec.encodeEditingChange(WireCodec.EditingChange(email, editing = false))))
            .map(::account)

    private fun account(a: WireCodec.IntegrationAccount) = ConnectedAccount(
        a.provider, a.email, a.status, a.lastSyncAtMs, a.canEdit,
        newsDown = a.feedsDown.orEmpty().map { os.meka.core.domain.NewsFeedDown(it.source, it.topic, it.sinceMs, it.fails) },
    )

    override suspend fun mailAccounts(): List<os.meka.core.domain.MailAccountFacts> =
        mail(WireCodec.decodeAccounts(post("/v1/integrations/list", "")))

    override suspend fun setMailLabel(provider: String, email: String, label: String): List<os.meka.core.domain.MailAccountFacts> =
        mail(WireCodec.decodeAccounts(post("/v1/integrations/$provider/label", WireCodec.encodeMailLabelChange(WireCodec.MailLabelChange(email, label)))))

    override suspend fun disconnectMail(provider: String, email: String): List<os.meka.core.domain.MailAccountFacts> =
        mail(WireCodec.decodeAccounts(post("/v1/integrations/$provider/disconnect", WireCodec.encodeMailDisconnect(WireCodec.MailDisconnect(email)))))

    override suspend fun disconnectMailAndDeleteData(provider: String, email: String): List<os.meka.core.domain.MailAccountFacts> =
        mail(WireCodec.decodeAccounts(post("/v1/integrations/$provider/disconnect", WireCodec.encodeMailDisconnect(WireCodec.MailDisconnect(email, deleteData = true)))))

    override suspend fun mailMessage(request: os.meka.core.wire.MailMessageCodec.Request): os.meka.core.wire.MailMessageCodec.Response {
        prepare() // the route requires the device's signing key on the server
        return os.meka.core.wire.MailMessageCodec.decodeResponse(post(os.meka.core.wire.MailMessageCodec.PATH, os.meka.core.wire.MailMessageCodec.encodeRequest(request)))
    }

    override suspend fun mailAttachment(request: os.meka.core.wire.MailMessageCodec.AttachmentRequest): os.meka.core.wire.MailMessageCodec.AttachmentFetch {
        val codec = os.meka.core.wire.MailMessageCodec
        val states = os.meka.core.domain.MailMessageAnswer.Companion
        prepare() // the route requires the device's signing key on the server
        val resp = send(codec.ATTACHMENT_PATH, codec.encodeAttachmentRequest(request))
        val max = os.meka.core.domain.MailAttachmentRules.MAX_BYTES
        return when (resp.status.value) {
            401 -> throw AuthRejectedException("HTTP 401 from ${codec.ATTACHMENT_PATH}")
            codec.STATUS_GONE -> os.meka.core.wire.MailMessageCodec.AttachmentFetch(states.GONE)
            codec.STATUS_RECONNECT -> os.meka.core.wire.MailMessageCodec.AttachmentFetch(states.RECONNECT)
            codec.STATUS_TOO_BIG -> os.meka.core.wire.MailMessageCodec.AttachmentFetch(os.meka.core.domain.MailAttachmentRules.TOO_BIG)
            codec.STATUS_REFUSED -> os.meka.core.wire.MailMessageCodec.AttachmentFetch(os.meka.core.domain.MailAttachmentRules.REFUSED)
            in 200..299 -> {
                val size = resp.contentLength()
                if (size != null && size > max) return os.meka.core.wire.MailMessageCodec.AttachmentFetch(os.meka.core.domain.MailAttachmentRules.TOO_BIG)
                val bytes = resp.readRawBytes()
                when {
                    bytes.isEmpty() -> os.meka.core.wire.MailMessageCodec.AttachmentFetch(states.FAILED)
                    bytes.size > max -> os.meka.core.wire.MailMessageCodec.AttachmentFetch(os.meka.core.domain.MailAttachmentRules.TOO_BIG)
                    else -> os.meka.core.wire.MailMessageCodec.AttachmentFetch(states.OK, bytes)
                }
            }
            else -> os.meka.core.wire.MailMessageCodec.AttachmentFetch(states.FAILED)
        }
    }

    private fun mail(list: List<WireCodec.IntegrationAccount>) = list.filter { os.meka.core.domain.MailProviders.isMail(it.provider) }
        .map { os.meka.core.domain.MailAccountFacts(it.provider, it.email, it.status, it.lastSyncAtMs, it.label, it.sorted, it.wouldTidy, it.why, it.inboxTotal, it.inboxUnread) }

    override suspend fun latestRelease(platform: String): AppRelease? {
        prepare() // release routes require the device's signing key on the server
        return WireCodec.decodeRelease(post("/v1/releases/latest", WireCodec.encodePlatform(platform)))?.let {
            AppRelease(it.platform, it.versionCode, it.versionName, it.sha256, it.sizeBytes, it.chunkCount)
        }
    }

    override suspend fun releaseChunk(platform: String, versionCode: Long, index: Int): ByteArray {
        val body = post("/v1/releases/chunk", WireCodec.encodeChunkRef(WireCodec.ChunkRef(platform, versionCode, index)))
        return try {
            Base64.decode(WireCodec.decodeChunkData(body))
        } catch (e: IllegalArgumentException) {
            throw TransportException("malformed chunk", e)
        }
    }

    override suspend fun uploadReleaseChunk(release: AppRelease, index: Int, bytes: ByteArray): Boolean {
        prepare()
        val r = WireCodec.AppRelease(release.platform, release.versionCode, release.versionName, release.sha256, release.sizeBytes, release.chunkCount)
        val resp = send("/v1/releases/upload", WireCodec.encodeReleaseChunk(WireCodec.ReleaseChunk(r, index, Base64.encode(bytes))))
        return when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from /v1/releases/upload")
            resp.status.value == 403 -> throw PublishRefusedException("this device's signing key isn't registered with the server yet")
            resp.status.value == 409 || resp.status.value == 400 -> throw PublishRefusedException(resp.bodyAsText().take(200))
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from /v1/releases/upload")
            else -> WireCodec.decodeUploadAck(resp.bodyAsText()).complete
        }
    }

    override suspend fun newsImage(key: String): ByteArray? {
        prepare() // the picture route requires the device's signing key on the server
        val resp = send("/v1/news/image", WireCodec.encodeNewsImageRef(key))
        return when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from /v1/news/image")
            // 404: no picture under this key (or an older server without the route).
            resp.status.value == 404 -> null
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from /v1/news/image")
            else -> try {
                Base64.decode(WireCodec.decodeChunkData(resp.bodyAsText()))
            } catch (e: IllegalArgumentException) {
                throw TransportException("malformed picture", e)
            }
        }
    }

    override suspend fun voiceMessageAudio(id: String): ByteArray? {
        val codec = os.meka.core.wire.VoiceMessageCodec
        val body = codec.encodeRequest(id)
        prepare() // the route requires the device's signing key on the server
        val resp = send(codec.PATH, body)
        return when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from ${codec.PATH}")
            // 404: no recording kept (or an older server); 403: this device's key isn't registered yet.
            resp.status.value == 404 || resp.status.value == 403 -> null
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from ${codec.PATH}")
            resp.contentType()?.match(AUDIO_MPEG) != true -> null
            else -> {
                val size = resp.contentLength()
                if (size != null && size > codec.MAX_AUDIO_BYTES) return null
                resp.readRawBytes().takeIf { it.isNotEmpty() && it.size <= codec.MAX_AUDIO_BYTES }
            }
        }
    }

    override suspend fun registerPushToken(service: String, token: String) {
        prepare() // the push route requires the device's signing key on the server
        val resp = send("/v1/push/token", WireCodec.encodePushToken(WireCodec.PushToken(service, token)))
        when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from /v1/push/token")
            // 404: a server without push (older, or not configured); 403: no signing key registered yet.
            resp.status.value == 404 || resp.status.value == 403 -> throw PushUnavailableException()
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from /v1/push/token")
        }
    }

    override suspend fun ask(
        question: String,
        context: os.meka.core.domain.AskContext,
        history: List<os.meka.core.domain.TalkTurn>,
        voice: Boolean,
    ): AskReply = askWith(question, context, history, voice, speak = false, speakVoice = null)

    override suspend fun askSpoken(
        question: String,
        context: os.meka.core.domain.AskContext,
        history: List<os.meka.core.domain.TalkTurn>,
        speakVoice: String?,
    ): AskReply = askWith(question, context, history, voice = true, speak = true, speakVoice = speakVoice)

    /** The server answered 404 to the stream once (an older server): ask as before from then on. */
    private var streamMissing = false

    override suspend fun askStreamed(
        question: String,
        context: os.meka.core.domain.AskContext,
        history: List<os.meka.core.domain.TalkTurn>,
        speakVoice: String?,
        onFirst: suspend (text: String, audio: String, voice: String?) -> Unit,
        onNext: (suspend (text: String, audio: String, voice: String?) -> Unit)?,
    ): AskReply {
        if (streamMissing) return askSpoken(question, context, history, speakVoice)
        val body = askBody(question, context, history, voice = true, speak = true, speakVoice = speakVoice, speakNext = onNext != null)
        prepare() // the ask routes require the device's signing key on the server
        val path = AskCodec.STREAM_PATH
        val reply: AskReply? = sendStreaming(path, body) { resp ->
            when {
                resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from $path")
                resp.status.value == 404 -> { streamMissing = true; return@sendStreaming null }
                resp.status.value == 403 -> return@sendStreaming AskReply.Unavailable(AskCodec.Response.FAILED, "this device's key isn't registered yet")
                !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from $path")
            }
            val channel = resp.bodyAsChannel()
            var whole: AskCodec.Response? = null
            while (whole == null) {
                val line = channel.readUTF8Line() ?: break
                when (val l = AskCodec.decodeStreamLine(line)) {
                    is AskCodec.StreamLine.First -> onFirst(l.text, l.audio, l.voice)
                    is AskCodec.StreamLine.Next -> onNext?.invoke(l.text, l.audio, l.voice)
                    is AskCodec.StreamLine.Final -> whole = l.response
                    null -> Unit
                }
            }
            replyOf(whole ?: throw TransportException("the answer stopped part way from $path"))
        }
        return reply ?: askSpoken(question, context, history, speakVoice)
    }

    private fun askBody(
        question: String,
        context: os.meka.core.domain.AskContext,
        history: List<os.meka.core.domain.TalkTurn>,
        voice: Boolean,
        speak: Boolean,
        speakVoice: String?,
        speakNext: Boolean = false,
    ): String = AskCodec.encodeRequest(
        AskCodec.Request(
            question, context.dateIso, context.nowLine, context.items.map { AskCodec.Item(it.ref, it.kind.wire, it.line) },
            history = history.map { AskCodec.Turn(it.question, it.answer, it.done) },
            voice = voice,
            speak = speak,
            speakVoice = speakVoice,
            callMe = if (context.callSir) AskCodec.CALL_SIR else null,
            recall = context.recall.filter { it.isNotBlank() }.map { it.take(AskCodec.MAX_LINE) }.takeLast(AskCodec.MAX_RECALL),
            name = AskCodec.plainName(context.callName),
            introduce = context.introduce,
            speakNext = speakNext,
        ),
    )

    private fun replyOf(r: AskCodec.Response): AskReply =
        if (r.state == AskCodec.Response.ANSWERED) {
            AskReply.Answered(
                r.answer.orEmpty(), r.actions.map { os.meka.core.domain.AskRawAction(it.kind, it.ref, it.title, it.date, it.time, it.hours, it.minutes) },
                r.firstText, r.firstAudio, r.sources.map { it.title to it.url }, r.note,
            )
        } else {
            AskReply.Unavailable(r.state, r.reason)
        }

    private suspend fun askWith(
        question: String,
        context: os.meka.core.domain.AskContext,
        history: List<os.meka.core.domain.TalkTurn>,
        voice: Boolean,
        speak: Boolean,
        speakVoice: String?,
    ): AskReply {
        val body = askBody(question, context, history, voice, speak, speakVoice)
        prepare() // the ask route requires the device's signing key on the server
        val resp = send("/v1/ai/ask", body)
        when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from /v1/ai/ask")
            // A server without the AI layer (older, or no key secret configured).
            resp.status.value == 404 -> return AskReply.Unavailable(AskCodec.Response.OFF, null)
            resp.status.value == 403 -> return AskReply.Unavailable(AskCodec.Response.FAILED, "this device's key isn't registered yet")
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from /v1/ai/ask")
        }
        return replyOf(AskCodec.decodeResponse(resp.bodyAsText()))
    }

    override suspend fun aiStatus(): AiStatusReply? {
        prepare() // the status route requires the device's signing key on the server too
        val resp = send("/v1/ai/status", "{}")
        when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from /v1/ai/status")
            // A server without the AI layer (older, or no key secret configured): AI is off.
            resp.status.value == 404 -> return AiStatusReply(AskCodec.Response.OFF, null, null, null, null)
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from /v1/ai/status")
        }
        val st = AskCodec.decodeStatus(resp.bodyAsText())
        return AiStatusReply(
            st.state, st.reason, st.spentCents, st.budgetCents, st.level,
            st.today.map { os.meka.core.domain.AiDayUse(it.feature, it.calls, it.microUsd) },
            st.month.map { os.meka.core.domain.AiMonthUse(it.feature, it.calls, it.cents) },
            st.keyRefused,
        )
    }

    override suspend fun messageRequest(request: os.meka.core.wire.MessageRequestCodec.Request): os.meka.core.wire.MessageRequestCodec.Response {
        val codec = os.meka.core.wire.MessageRequestCodec
        val body = codec.encodeRequest(request)
        prepare() // the AI routes require the device's signing key on the server
        val resp = send("/v1/ai/message-request", body)
        when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from /v1/ai/message-request")
            // A server without the route (older, or no AI key): nothing is read.
            resp.status.value == 404 -> return os.meka.core.wire.MessageRequestCodec.Response(AskCodec.Response.OFF)
            resp.status.value == 403 -> return os.meka.core.wire.MessageRequestCodec.Response(AskCodec.Response.FAILED, reason = "this device's key isn't registered yet")
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from /v1/ai/message-request")
        }
        return codec.decodeResponse(resp.bodyAsText())
    }

    override suspend fun handleThis(request: os.meka.core.wire.HandleThisCodec.Request): os.meka.core.wire.MessageRequestCodec.Response {
        val body = os.meka.core.wire.HandleThisCodec.encodeRequest(request)
        prepare() // the AI routes require the device's signing key on the server
        val resp = send("/v1/ai/handle-this", body)
        when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from /v1/ai/handle-this")
            // A server without the route (older, or no AI key): nothing is read.
            resp.status.value == 404 -> return os.meka.core.wire.MessageRequestCodec.Response(AskCodec.Response.OFF)
            resp.status.value == 403 -> return os.meka.core.wire.MessageRequestCodec.Response(AskCodec.Response.FAILED, reason = "this device's key isn't registered yet")
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from /v1/ai/handle-this")
        }
        return os.meka.core.wire.MessageRequestCodec.decodeResponse(resp.bodyAsText())
    }

    override suspend fun voiceNote(request: os.meka.core.wire.VoiceNoteCodec.Request): os.meka.core.wire.VoiceNoteCodec.Response {
        val body = os.meka.core.wire.VoiceNoteCodec.encodeRequest(request)
        prepare() // the AI routes require the device's signing key on the server
        val resp = send("/v1/ai/voice-note", body)
        when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from /v1/ai/voice-note")
            resp.status.value == 404 -> return os.meka.core.wire.VoiceNoteCodec.Response(AskCodec.Response.OFF)
            resp.status.value == 403 -> return os.meka.core.wire.VoiceNoteCodec.Response(AskCodec.Response.FAILED, reason = "this device's key isn't registered yet")
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from /v1/ai/voice-note")
        }
        return os.meka.core.wire.VoiceNoteCodec.decodeResponse(resp.bodyAsText())
    }

    override suspend fun goalBreakdown(request: os.meka.core.wire.GoalBreakdownCodec.Request): os.meka.core.wire.GoalBreakdownCodec.Response {
        val codec = os.meka.core.wire.GoalBreakdownCodec
        val body = codec.encodeRequest(request)
        prepare() // the AI routes require the device's signing key on the server
        val resp = send("/v1/ai/goal-breakdown", body)
        when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from /v1/ai/goal-breakdown")
            // A server without the route (older, or no AI key): nothing is proposed.
            resp.status.value == 404 -> return os.meka.core.wire.GoalBreakdownCodec.Response(AskCodec.Response.OFF)
            resp.status.value == 403 -> return os.meka.core.wire.GoalBreakdownCodec.Response(AskCodec.Response.FAILED, reason = "this device's key isn't registered yet")
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from /v1/ai/goal-breakdown")
        }
        return codec.decodeResponse(resp.bodyAsText())
    }

    override suspend fun briefScript(request: os.meka.core.wire.BriefScriptCodec.Request): os.meka.core.wire.BriefScriptCodec.Response {
        val codec = os.meka.core.wire.BriefScriptCodec
        val body = codec.encodeRequest(request)
        prepare() // the AI routes require the device's signing key on the server
        val resp = send("/v1/ai/brief-script", body)
        when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from /v1/ai/brief-script")
            // A server without the route (older, or no AI key): the device reads its own brief.
            resp.status.value == 404 -> return os.meka.core.wire.BriefScriptCodec.Response(AskCodec.Response.OFF)
            resp.status.value == 403 -> return os.meka.core.wire.BriefScriptCodec.Response(AskCodec.Response.FAILED, reason = "this device's key isn't registered yet")
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from /v1/ai/brief-script")
        }
        return codec.decodeResponse(resp.bodyAsText())
    }

    override suspend fun messageTriage(request: os.meka.core.wire.MessageTriageCodec.Request): os.meka.core.wire.MessageTriageCodec.Response {
        val codec = os.meka.core.wire.MessageTriageCodec
        val body = codec.encodeRequest(request)
        prepare() // the AI routes require the device's signing key on the server
        val resp = send("/v1/ai/message-triage", body)
        when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from /v1/ai/message-triage")
            // A server without the route (older, or no AI key): nothing is triaged.
            resp.status.value == 404 -> return os.meka.core.wire.MessageTriageCodec.Response(AskCodec.Response.OFF)
            resp.status.value == 403 -> return os.meka.core.wire.MessageTriageCodec.Response(AskCodec.Response.FAILED, reason = "this device's key isn't registered yet")
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from /v1/ai/message-triage")
        }
        return codec.decodeResponse(resp.bodyAsText())
    }

    override suspend fun groupDigest(request: os.meka.core.wire.GroupDigestCodec.Request): os.meka.core.wire.GroupDigestCodec.Response {
        val codec = os.meka.core.wire.GroupDigestCodec
        val body = codec.encodeRequest(request)
        prepare() // the AI routes require the device's signing key on the server
        val resp = send("/v1/ai/group-digest", body)
        when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from /v1/ai/group-digest")
            // A server without the route (older, or no AI key): the cards go without gists.
            resp.status.value == 404 -> return os.meka.core.wire.GroupDigestCodec.Response(AskCodec.Response.OFF)
            resp.status.value == 403 -> return os.meka.core.wire.GroupDigestCodec.Response(AskCodec.Response.FAILED, reason = "this device's key isn't registered yet")
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from /v1/ai/group-digest")
        }
        return codec.decodeResponse(resp.bodyAsText())
    }

    override suspend fun helloOpeners(request: os.meka.core.wire.HelloOpenersCodec.Request): os.meka.core.wire.HelloOpenersCodec.Response {
        val codec = os.meka.core.wire.HelloOpenersCodec
        val body = codec.encodeRequest(request)
        prepare() // the AI routes require the device's signing key on the server
        val resp = send(codec.PATH, body)
        when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from ${codec.PATH}")
            // A server without the route (older, or no AI key): the hand-written openers carry on alone.
            resp.status.value == 404 -> return os.meka.core.wire.HelloOpenersCodec.Response(AskCodec.Response.OFF)
            resp.status.value == 403 -> return os.meka.core.wire.HelloOpenersCodec.Response(AskCodec.Response.FAILED, reason = "this device's key isn't registered yet")
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from ${codec.PATH}")
        }
        return codec.decodeResponse(resp.bodyAsText())
    }

    override suspend fun chatSummary(request: os.meka.core.wire.ChatSummaryCodec.Request): os.meka.core.wire.ChatSummaryCodec.Response {
        val codec = os.meka.core.wire.ChatSummaryCodec
        val body = codec.encodeRequest(request)
        prepare() // the AI routes require the device's signing key on the server
        val resp = send("/v1/ai/chat-summary", body)
        when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from /v1/ai/chat-summary")
            // A server without the route (older, or no AI key): the chats keep their top lines.
            resp.status.value == 404 -> return os.meka.core.wire.ChatSummaryCodec.Response(AskCodec.Response.OFF)
            resp.status.value == 403 -> return os.meka.core.wire.ChatSummaryCodec.Response(AskCodec.Response.FAILED, reason = "this device's key isn't registered yet")
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from /v1/ai/chat-summary")
        }
        return codec.decodeResponse(resp.bodyAsText())
    }

    override suspend fun mailDraft(request: os.meka.core.wire.MailDraftCodec.Request): os.meka.core.wire.MailDraftCodec.Response {
        val codec = os.meka.core.wire.MailDraftCodec
        val body = codec.encodeRequest(request)
        prepare() // the AI routes require the device's signing key on the server
        val resp = send("/v1/ai/mail-draft", body)
        when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from /v1/ai/mail-draft")
            // A server without the route (older, or no AI key): he writes the reply himself.
            resp.status.value == 404 -> return os.meka.core.wire.MailDraftCodec.Response(AskCodec.Response.OFF)
            resp.status.value == 403 -> return os.meka.core.wire.MailDraftCodec.Response(AskCodec.Response.FAILED, reason = "this device's key isn't registered yet")
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from /v1/ai/mail-draft")
        }
        return codec.decodeResponse(resp.bodyAsText())
    }

    override suspend fun speak(text: String, voice: String?): os.meka.core.wire.SpeechCodec.Response = speakClip(text, voice, false)

    override suspend fun speakMarked(text: String, voice: String?): os.meka.core.wire.SpeechCodec.Response = speakClip(text, voice, true)

    private suspend fun speakClip(text: String, voice: String?, visemes: Boolean): os.meka.core.wire.SpeechCodec.Response {
        // Raw MP3 back (a quarter smaller than base64 JSON, nothing to parse); an older server answers JSON as before.
        val body = os.meka.core.wire.SpeechCodec.encodeRequest(os.meka.core.wire.SpeechCodec.Request(text, voice, binary = true, visemes = visemes))
        prepare() // the speech routes require the device's signing key on the server
        val resp = send("/v1/speech/speak", body)
        when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from /v1/speech/speak")
            // A server without MEKA's voice (older, or not configured): the device's own voice speaks.
            resp.status.value == 404 -> return os.meka.core.wire.SpeechCodec.Response(os.meka.core.wire.SpeechCodec.Response.OFF)
            resp.status.value == 403 -> return os.meka.core.wire.SpeechCodec.Response(os.meka.core.wire.SpeechCodec.Response.FAILED, reason = "this device's key isn't registered yet")
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from /v1/speech/speak")
        }
        if (resp.contentType()?.match(AUDIO_MPEG) == true) {
            val unusable = SpeechCodec.Response(SpeechCodec.Response.FAILED, reason = "Navilife's voice sent an unusable clip")
            val size = resp.contentLength()
            if (size != null && size > SpeechCodec.MAX_AUDIO_BYTES) return unusable
            val bytes = resp.readRawBytes()
            if (bytes.size > SpeechCodec.MAX_AUDIO_BYTES) return unusable
            return SpeechCodec.binaryResponse(
                Base64.encode(bytes), resp.headers[SpeechCodec.HEADER_VOICE], resp.headers[SpeechCodec.HEADER_ENGINE], resp.headers[SpeechCodec.HEADER_VISEMES],
            )
        }
        return SpeechCodec.decodeResponse(resp.bodyAsText())
    }

    override suspend fun talkWarm(): Boolean {
        prepare() // the route requires the device's signing key on the server
        val resp = send("/v1/talk/warm", os.meka.core.wire.TalkWarmCodec.encodeRequest())
        when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from /v1/talk/warm")
            // An older server without the route, or a device key not registered yet: nothing to warm, nothing lost.
            resp.status.value == 404 || resp.status.value == 403 -> return false
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from /v1/talk/warm")
        }
        return os.meka.core.wire.TalkWarmCodec.decodeResponse(resp.bodyAsText()).state != os.meka.core.wire.TalkWarmCodec.Response.OFF
    }

    override suspend fun serverSpeed(): List<os.meka.core.wire.ServerSpeedCodec.Turn>? {
        prepare() // the route requires the device's signing key on the server
        val path = os.meka.core.wire.ServerSpeedCodec.PATH
        val resp = send(path, os.meka.core.wire.ServerSpeedCodec.encodeRequest())
        when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from $path")
            // An older server without the route, or a device key not registered yet: nothing to show.
            resp.status.value == 404 || resp.status.value == 403 -> return null
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from $path")
        }
        return os.meka.core.wire.ServerSpeedCodec.decodeResponse(resp.bodyAsText())
    }

    /** Posts [body] to a Live route; null when an older server lacks it or the device key isn't registered yet. */
    private suspend fun livePost(path: String, body: String): String? {
        prepare() // the routes require the device's signing key on the server
        val resp = send(path, body)
        when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from $path")
            resp.status.value == 404 || resp.status.value == 403 -> return null
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from $path")
        }
        return resp.bodyAsText()
    }

    override suspend fun liveStatus(): os.meka.core.wire.LiveCodec.Meter? =
        livePost(os.meka.core.wire.LiveCodec.STATUS_PATH, os.meka.core.wire.LiveCodec.encodeStatusRequest())?.let(os.meka.core.wire.LiveCodec::decodeMeter)

    override suspend fun liveSession(voice: String?, day: os.meka.core.wire.LiveCodec.Day?, resume: String?): os.meka.core.wire.LiveCodec.Session? =
        livePost(os.meka.core.wire.LiveCodec.SESSION_PATH, os.meka.core.wire.LiveCodec.encodeSessionRequest(os.meka.core.wire.LiveCodec.SessionRequest(voice, day, resume)))
            ?.let(os.meka.core.wire.LiveCodec::decodeSession)

    override suspend fun liveSearch(request: os.meka.core.wire.LiveCodec.SearchRequest): os.meka.core.wire.LiveCodec.SearchResult? =
        livePost(os.meka.core.wire.LiveCodec.SEARCH_PATH, os.meka.core.wire.LiveCodec.encodeSearchRequest(request))
            ?.let(os.meka.core.wire.LiveCodec::decodeSearchResult)

    override suspend fun liveUsage(sessionId: String, inSeconds: Long, outSeconds: Long): os.meka.core.wire.LiveCodec.Usage? =
        livePost(os.meka.core.wire.LiveCodec.USAGE_PATH, os.meka.core.wire.LiveCodec.encodeUsageRequest(os.meka.core.wire.LiveCodec.UsageRequest(sessionId, inSeconds, outSeconds)))
            ?.let(os.meka.core.wire.LiveCodec::decodeUsage)

    override suspend fun speechVoices(): os.meka.core.wire.SpeechCodec.Voices {
        prepare()
        val resp = send("/v1/speech/voices", os.meka.core.wire.SpeechCodec.encodeVoicesRequest())
        when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from /v1/speech/voices")
            resp.status.value == 404 -> return os.meka.core.wire.SpeechCodec.Voices(os.meka.core.wire.SpeechCodec.Voices.OFF)
            resp.status.value == 403 -> return os.meka.core.wire.SpeechCodec.Voices(os.meka.core.wire.SpeechCodec.Voices.FAILED, reason = "this device's key isn't registered yet")
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from /v1/speech/voices")
        }
        return os.meka.core.wire.SpeechCodec.decodeVoices(resp.bodyAsText())
    }

    override suspend fun hereWeather(lat: Double, lon: Double): os.meka.core.wire.HereCodec.Response {
        val codec = os.meka.core.wire.HereCodec
        val body = codec.encodeRequest(os.meka.core.wire.HereCodec.Request(lat, lon))
        prepare() // the route requires the device's signing key on the server
        val resp = send("/v1/weather/here", body)
        when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from /v1/weather/here")
            // A server without the route (older, or no weather): Today keeps home's line.
            resp.status.value == 404 -> return os.meka.core.wire.HereCodec.Response(os.meka.core.wire.HereCodec.Response.OFF)
            resp.status.value == 403 -> return os.meka.core.wire.HereCodec.Response(os.meka.core.wire.HereCodec.Response.FAILED, reason = "this device's key isn't registered yet")
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from /v1/weather/here")
        }
        return codec.decodeResponse(resp.bodyAsText())
    }

    override suspend fun fuelNearby(lat: Double, lon: Double, fuel: String): os.meka.core.wire.FuelCodec.Response {
        val codec = os.meka.core.wire.FuelCodec
        val body = codec.encodeRequest(os.meka.core.wire.FuelCodec.Request(lat, lon, fuel))
        prepare() // the route requires the device's signing key on the server
        val resp = send(codec.PATH, body)
        when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from ${codec.PATH}")
            // A server without the route (older, or no Fuel Finder key): the pane says prices are off.
            resp.status.value == 404 -> return os.meka.core.wire.FuelCodec.Response(os.meka.core.wire.FuelCodec.Response.OFF)
            resp.status.value == 403 -> return os.meka.core.wire.FuelCodec.Response(os.meka.core.wire.FuelCodec.Response.FAILED, reason = "this device's key isn't registered yet")
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from ${codec.PATH}")
        }
        return codec.decodeResponse(resp.bodyAsText())
    }

    override suspend fun vaultFilePut(id: String, sealed: String): Boolean {
        val codec = os.meka.core.wire.VaultFileCodec
        prepare() // the route requires the device's signing key on the server
        val resp = send(codec.PUT_PATH, codec.encodePut(os.meka.core.wire.VaultFileCodec.Put(id, sealed)))
        return when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from ${codec.PUT_PATH}")
            resp.status.value == 404 || resp.status.value == 403 -> false
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from ${codec.PUT_PATH}")
            else -> true
        }
    }

    override suspend fun vaultFileGet(id: String): String? {
        val codec = os.meka.core.wire.VaultFileCodec
        prepare()
        val resp = send(codec.GET_PATH, codec.encodeRef(id))
        return when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from ${codec.GET_PATH}")
            resp.status.value == 404 -> null
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from ${codec.GET_PATH}")
            else -> try {
                codec.decodeSealed(resp.bodyAsText())
            } catch (e: IllegalArgumentException) {
                throw TransportException("malformed vault copy", e)
            }
        }
    }

    override suspend fun vaultFileDelete(id: String): Boolean {
        val codec = os.meka.core.wire.VaultFileCodec
        prepare()
        val resp = send(codec.DELETE_PATH, codec.encodeRef(id))
        return when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from ${codec.DELETE_PATH}")
            resp.status.value == 404 || resp.status.value == 403 -> false
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from ${codec.DELETE_PATH}")
            else -> true
        }
    }

    override suspend fun lookUpNumber(number: String, waitMs: Long): os.meka.core.wire.NumberLookupCodec.Response {
        val codec = os.meka.core.wire.NumberLookupCodec
        val body = codec.encodeRequest(os.meka.core.wire.NumberLookupCodec.Request(number, waitMs))
        prepare() // the route requires the device's signing key on the server
        val resp = send(codec.PATH, body)
        when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from ${codec.PATH}")
            // A server without the route (older, or no search key): no lookup.
            resp.status.value == 404 -> return os.meka.core.wire.NumberLookupCodec.Response(os.meka.core.wire.NumberLookupCodec.Response.OFF)
            resp.status.value == 403 -> return os.meka.core.wire.NumberLookupCodec.Response(os.meka.core.wire.NumberLookupCodec.Response.FAILED)
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from ${codec.PATH}")
        }
        return codec.decodeResponse(resp.bodyAsText())
    }

    override suspend fun tradingExport(fromMs: Long, toMs: Long, waitMs: Long): os.meka.core.wire.TradingExportCodec.Response {
        val codec = os.meka.core.wire.TradingExportCodec
        val body = codec.encodeRequest(os.meka.core.wire.TradingExportCodec.Request(fromMs, toMs, waitMs))
        prepare() // the route requires the device's signing key on the server
        val resp = send(codec.PATH, body)
        when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from ${codec.PATH}")
            // A server without the route (older, or the tracker off): the export says so.
            resp.status.value == 404 -> return os.meka.core.wire.TradingExportCodec.Response(os.meka.core.wire.TradingExportCodec.Response.OFF)
            resp.status.value == 403 -> return os.meka.core.wire.TradingExportCodec.Response(os.meka.core.wire.TradingExportCodec.Response.FAILED)
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from ${codec.PATH}")
        }
        return codec.decodeResponse(resp.bodyAsText())
    }

    override suspend fun reportUpdateFailure(report: os.meka.core.wire.UpdateFailureCodec.Report): Boolean {
        val codec = os.meka.core.wire.UpdateFailureCodec
        prepare() // the route requires the device's signing key on the server
        val resp = send(codec.REPORT_PATH, codec.encodeReport(report))
        return when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from ${codec.REPORT_PATH}")
            resp.status.value == 404 || resp.status.value == 403 -> false
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from ${codec.REPORT_PATH}")
            else -> true
        }
    }

    override suspend fun reportFoldPostures(report: os.meka.core.wire.FoldPostureCodec.Report): Boolean {
        val codec = os.meka.core.wire.FoldPostureCodec
        prepare() // the route requires the device's signing key on the server
        val resp = send(codec.REPORT_PATH, codec.encodeReport(report))
        return when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from ${codec.REPORT_PATH}")
            resp.status.value == 404 || resp.status.value == 403 -> false
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from ${codec.REPORT_PATH}")
            else -> true
        }
    }

    override suspend fun reportNotifyTrace(report: os.meka.core.wire.NotifyDiagnosticsCodec.Report): Boolean {
        val codec = os.meka.core.wire.NotifyDiagnosticsCodec
        prepare() // the route requires the device's signing key on the server
        val resp = send(codec.REPORT_PATH, codec.encodeReport(report))
        return when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from ${codec.REPORT_PATH}")
            resp.status.value == 404 || resp.status.value == 403 -> false
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from ${codec.REPORT_PATH}")
            else -> true
        }
    }

    override suspend fun testPush(): String? {
        val codec = os.meka.core.wire.NotifyDiagnosticsCodec
        prepare()
        val resp = send(codec.TEST_PATH, codec.encodeEmptyRequest())
        return when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from ${codec.TEST_PATH}")
            resp.status.value == 404 || resp.status.value == 403 -> null
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from ${codec.TEST_PATH}")
            else -> codec.decodeTestAnswer(resp.bodyAsText())
        }
    }

    override suspend fun householdHealth(): os.meka.core.wire.HealthCodec.Response? {
        prepare() // the route requires the device's signing key on the server
        val resp = send("/v1/health/household", os.meka.core.wire.HealthCodec.encodeRequest())
        return when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from /v1/health/household")
            // An older server without the route, or this device's key isn't registered yet.
            resp.status.value == 404 || resp.status.value == 403 -> null
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from /v1/health/household")
            else -> os.meka.core.wire.HealthCodec.decodeResponse(resp.bodyAsText())
        }
    }

    override suspend fun familyInvites(): List<os.meka.core.wire.FamilyCodec.Invite> =
        os.meka.core.wire.FamilyCodec.decodeList(family("/v1/family/invites/list", os.meka.core.wire.FamilyCodec.encodeList()))

    override suspend fun createFamilyInvite(name: String): Pair<os.meka.core.wire.FamilyCodec.Made, String> {
        val made = os.meka.core.wire.FamilyCodec.decodeMade(family("/v1/family/invites/create", os.meka.core.wire.FamilyCodec.encodeCreate(name)))
        return made to baseUrl.trimEnd('/') + made.path
    }

    override suspend fun revokeFamilyInvite(id: String): Boolean {
        prepare()
        val resp = send("/v1/family/invites/revoke", os.meka.core.wire.FamilyCodec.encodeRevoke(id))
        return when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from /v1/family/invites/revoke")
            resp.status.value == 403 -> throw FamilyUnavailableException(FamilyUnavailableException.NO_KEY)
            resp.status.value == 404 -> {
                // 404 with a JSON refusal is "unknown link"; a bare 404 is a server without the route.
                if (resp.bodyAsText().contains("\"error\"")) false else throw FamilyUnavailableException(FamilyUnavailableException.NO_ROUTE)
            }
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from /v1/family/invites/revoke")
            else -> true
        }
    }

    /** The family routes need the device's signing key on the server. */
    private suspend fun family(path: String, body: String): String {
        prepare()
        val resp = send(path, body)
        return when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from $path")
            resp.status.value == 403 -> throw FamilyUnavailableException(FamilyUnavailableException.NO_KEY)
            resp.status.value == 404 -> throw FamilyUnavailableException(FamilyUnavailableException.NO_ROUTE)
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from $path")
            else -> resp.bodyAsText()
        }
    }

    override suspend fun linkWatch(code: String): os.meka.core.wire.DeviceLinkCodec.Linked =
        os.meka.core.wire.DeviceLinkCodec.decodeLinked(deviceLink("/v1/devices/link", os.meka.core.wire.DeviceLinkCodec.encodeApprove(code)))

    override suspend fun linkedWatches(): List<os.meka.core.wire.DeviceLinkCodec.Watch> =
        os.meka.core.wire.DeviceLinkCodec.decodeWatches(deviceLink("/v1/devices/watches", "{}"))

    override suspend fun unlinkWatch(id: String) {
        deviceLink("/v1/devices/unlink", os.meka.core.wire.DeviceLinkCodec.encodeUnlink(id))
    }

    override suspend fun answerDeviceJoin(requestId: String, allow: Boolean): String = os.meka.core.wire.DeviceJoinCodec.decodeAnswered(
        deviceLink(os.meka.core.wire.DeviceJoinCodec.ANSWER_PATH, os.meka.core.wire.DeviceJoinCodec.encodeAnswer(os.meka.core.wire.DeviceJoinCodec.Answer(requestId, allow))),
    )

    override suspend fun recoveryStatus(): os.meka.core.wire.RecoveryCodec.Status =
        os.meka.core.wire.RecoveryCodec.decodeStatus(deviceLink("/v1/recovery/status", "{}"))

    override suspend fun setRecoveryKey(verifier: String): os.meka.core.wire.RecoveryCodec.Status =
        os.meka.core.wire.RecoveryCodec.decodeStatus(deviceLink("/v1/recovery/set", os.meka.core.wire.RecoveryCodec.encodeSet(verifier)))

    /**
     * The link routes need the device's signing key on the server. A refusal comes back as JSON (`{"error": …}`) with
     * 404 · 409 · 429; a bare 404 is a server without the route, a bare 403 a device whose key isn't registered yet.
     */
    private suspend fun deviceLink(path: String, body: String): String {
        prepare()
        val resp = send(path, body)
        val text = resp.bodyAsText()
        val refusal = if (resp.status.value in setOf(404, 409, 429)) os.meka.core.wire.DeviceLinkCodec.decodeError(text) else null
        return when {
            resp.status.value == 401 -> throw AuthRejectedException("HTTP 401 from $path")
            resp.status.value == 403 -> throw FamilyUnavailableException(FamilyUnavailableException.NO_KEY)
            refusal != null -> throw LinkRefusedException(refusal)
            resp.status.value == 404 -> throw FamilyUnavailableException(FamilyUnavailableException.NO_ROUTE)
            !resp.status.isSuccess() -> throw TransportException("HTTP ${resp.status.value} from $path")
            else -> text
        }
    }

    private companion object {
        val AUDIO_MPEG = ContentType.parse(SpeechCodec.AUDIO_TYPE)
    }

    private suspend fun post(path: String, body: String): String {
        val resp = send(path, body)
        if (resp.status.value == 401) throw AuthRejectedException("HTTP 401 from $path")
        if (!resp.status.isSuccess()) throw TransportException("HTTP ${resp.status.value} from $path")
        return resp.bodyAsText()
    }

    /**
     * The companion, slice 9: [send], with the answer read as it arrives inside [block] (the body is a stream, so it is
     * only readable there). Network failures are [TransportException]s, as [send]'s are.
     */
    private suspend fun <T> sendStreaming(path: String, body: String, block: suspend (HttpResponse) -> T): T = try {
        val time = nowMs()
        val nonce = Random.nextBytes(16).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        val signature = deviceKey?.signBase64(RequestSigning.canonical("POST", path, time, nonce, body))
        client.preparePost(baseUrl.trimEnd('/') + path) {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${deviceSecret()}")
            if (signature != null) {
                header(RequestSigning.HEADER_TIME, time.toString())
                header(RequestSigning.HEADER_NONCE, nonce)
                header(RequestSigning.HEADER_SIGNATURE, signature)
            }
            setBody(body)
        }.execute { block(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: AuthRejectedException) {
        throw e
    } catch (e: TransportException) {
        throw e
    } catch (e: os.meka.core.wire.WireFormatException) {
        throw TransportException("bad answer from $path: ${e.message}", e)
    } catch (e: Exception) {
        throw TransportException("network error: ${e.message}", e)
    }

    private suspend fun send(path: String, body: String): HttpResponse = try {
        val time = nowMs()
        val nonce = Random.nextBytes(16).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        val signature = deviceKey?.signBase64(RequestSigning.canonical("POST", path, time, nonce, body))
        client.post(baseUrl.trimEnd('/') + path) {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${deviceSecret()}")
            if (signature != null) {
                header(RequestSigning.HEADER_TIME, time.toString())
                header(RequestSigning.HEADER_NONCE, nonce)
                header(RequestSigning.HEADER_SIGNATURE, signature)
            }
            setBody(body)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw TransportException("network error: ${e.message}", e)
    }
}
