package os.meka.core.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Notifications never seen, slice 2. Three routes, all signed by a keyed device:
 *  - `POST` [REPORT_PATH]: the phone sends its notification trace (allowed or not, MEKA's own phone setting, each
 *    channel on/off, the last [TRACE_MAX] trace entries) so a build run can read where notifications stop;
 *  - `POST` [LATEST_PATH]: the release publisher (or a keyed device) reads the household's newest report, with the
 *    server's last push to the device that sent it;
 *  - `POST` [TEST_PATH]: Send a test asks the server to push a test to this very device; the answer is what Firebase
 *    said ([RESULTS]).
 * Nothing personal: channel ids and names, counts, reasons and error class names; never a notification's words.
 */
object NotifyDiagnosticsCodec {
    const val REPORT_PATH = "/v1/diagnostics/notifications"
    const val LATEST_PATH = "/v1/diagnostics/notifications/latest"
    const val TEST_PATH = "/v1/push/test"
    const val TRACE_MAX = 40
    const val CALLS_MAX = 20
    const val CHANNELS_MAX = 24
    const val ASSISTANT_MAX = 10
    const val ASSISTANT_DETAIL_MAX = 160
    const val MAIL_MAX = 6
    const val MAIL_DETAIL_MAX = 400
    const val DETAIL_MAX = 120
    const val NAME_MAX = 40
    const val MODEL_MAX = 40

    const val SENT = "sent"
    const val OFF = "off"
    const val FAILED = "failed"
    const val GONE = "gone"
    const val NO_ADDRESS = "no_address"
    val RESULTS = setOf(SENT, OFF, FAILED, GONE, NO_ADDRESS)

    private val json = Json { ignoreUnknownKeys = true }

    /** [lastPostMs]: when MEKA last posted on the channel from this phone (slice 3; older phones send none). */
    data class Channel(val id: String, val name: String, val on: Boolean, val lastPostMs: Long? = null)

    data class Entry(val atMs: Long, val kind: String, val detail: String)

    data class Report(
        val model: String?,
        val sentAtMs: Long,
        val allowed: Boolean,
        /** MEKA's "This phone" setting: "ALL" · "DIGESTS" · "OFF". */
        val phone: String,
        val channels: List<Channel>,
        /** Newest first. */
        val trace: List<Entry>,
        /** Spam calls still ring, slice 2: the phone's call screening; null from phones before it. */
        val calls: Calls? = null,
    )

    /**
     * The call-screening diagnosis (spam calls still ring, slice 2): whether Navilife holds the Caller ID & spam role,
     * the spam switch, whether it may read contacts and the call log, and the last [CALLS_MAX] screened calls (newest
     * first; kind = the verdict, detail = a salted tag, never the number, and the reason).
     */
    data class Calls(val roleHeld: Boolean, val protection: Boolean, val lookups: Boolean, val screened: List<Entry>)

    /** The newest report and what the server knows of its last push to the device that sent it. */
    /**
     * [aboutFilled] / [aboutFoldAtMs] / [aboutMacAtMs] (About you, slice 2, release 2's gate): whether the household
     * profile has anything in it and when the Fold and the Mac each first held it; [aboutFilled] null = no profile (or
     * a server before slice 2). Never a value from the profile.
     */
    data class Latest(
        val report: Report?, val lastPushAtMs: Long? = null, val lastPushResult: String? = null, val hasAddress: Boolean? = null,
        val aboutFilled: Boolean? = null, val aboutFoldAtMs: Long? = null, val aboutMacAtMs: Long? = null,
        /** Release 2 slice 5: whether the profile holds the clubs and the council's holidays (never their values). */
        val aboutClubs: Boolean? = null, val aboutCouncil: Boolean? = null,
        /** Release 2 slice 6: whether the profile holds the train route (never the route). */
        val aboutRoute: Boolean? = null,
        /**
         * Call notifications, slice 1: the call assistant's last messages, newest first (kind "voice" or "automated",
         * detail = length, characters, urgency and how it was answered, the recording; never the number or the words).
         * Null from a server before it.
         */
        val assistant: List<Entry>? = null,
        /**
         * Inbox fixes, slice 6: one line per connected mail account (kind = its label, "Personal"; detail =
         * `MailCheckRules.reportLine`: state and reason, last check, inbox counts, Autopilot, the sort; never an
         * address). Null from a server before it.
         */
        val mail: List<Entry>? = null,
        /** Money's bank sync: one line of counts (state, accounts, transactions, last ok); null from a server before it. */
        val bank: String? = null,
        /** Live trains: the rail key's probe and the household's board, counts only; null from a server before it. */
        val trains: String? = null,
    )

    fun encodeReport(r: Report): String = obj(r).toString()

    fun decodeReport(body: String): Report = wrap("notification report") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        report(o)
    }

    fun encodeEmptyRequest(): String = buildJsonObject { put("w", WireCodec.VERSION) }.toString()

    fun decodeEmptyRequest(body: String) = wrap("notification request") {
        if (body.isBlank()) return@wrap
        checkVersion(json.parseToJsonElement(body).jsonObject)
    }

    fun encodeLatest(l: Latest): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("report", l.report?.let { obj(it, version = false) } ?: JsonNull)
        if (l.lastPushAtMs != null && l.lastPushResult != null) {
            put("lastPushAt", l.lastPushAtMs); put("lastPush", l.lastPushResult)
        }
        l.hasAddress?.let { put("address", it) }
        l.aboutFilled?.let { f ->
            put("aboutFilled", f)
            l.aboutFoldAtMs?.let { put("aboutFold", it) }
            l.aboutMacAtMs?.let { put("aboutMac", it) }
            l.aboutClubs?.let { put("aboutClubs", it) }
            l.aboutCouncil?.let { put("aboutCouncil", it) }
            l.aboutRoute?.let { put("aboutRoute", it) }
        }
        l.assistant?.let { list ->
            put("assistant", buildJsonArray {
                list.take(ASSISTANT_MAX).forEach { e ->
                    add(buildJsonObject { put("at", e.atMs); put("kind", clean(e.kind, 12)); put("detail", clean(e.detail, ASSISTANT_DETAIL_MAX)) })
                }
            })
        }
        l.mail?.let { list ->
            put("mail", buildJsonArray {
                list.take(MAIL_MAX).forEach { e ->
                    add(buildJsonObject { put("at", e.atMs); put("kind", clean(e.kind, 12)); put("detail", clean(e.detail, MAIL_DETAIL_MAX)) })
                }
            })
        }
        l.bank?.let { put("bank", clean(it, MAIL_DETAIL_MAX)) }
        l.trains?.let { put("trains", clean(it, MAIL_DETAIL_MAX)) }
    }.toString()

    fun decodeLatest(body: String): Latest = wrap("notification answer") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        val at = o.long("lastPushAt")?.takeIf { it > 0 }
        val result = o.str("lastPush")?.let { if (it in RESULTS) it else FAILED }
        Latest(
            (o["report"] as? JsonObject)?.let { runCatching { report(it) }.getOrNull() },
            lastPushAtMs = result?.let { at },
            lastPushResult = at?.let { result },
            hasAddress = (o["address"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull,
            aboutFilled = (o["aboutFilled"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull,
            aboutFoldAtMs = o.long("aboutFold")?.takeIf { it > 0 },
            aboutMacAtMs = o.long("aboutMac")?.takeIf { it > 0 },
            aboutClubs = (o["aboutClubs"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull,
            aboutCouncil = (o["aboutCouncil"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull,
            aboutRoute = (o["aboutRoute"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull,
            assistant = (o["assistant"] as? JsonArray)?.let { a -> entries(a, ASSISTANT_MAX, ASSISTANT_DETAIL_MAX) },
            mail = (o["mail"] as? JsonArray)?.let { a -> entries(a, MAIL_MAX, MAIL_DETAIL_MAX) },
            bank = o.str("bank")?.let { clean(it, MAIL_DETAIL_MAX) },
            trains = o.str("trains")?.let { clean(it, MAIL_DETAIL_MAX) },
        )
    }

    fun encodeTestAnswer(result: String): String = buildJsonObject {
        put("w", WireCodec.VERSION); put("result", result)
    }.toString()

    /** What the server said; an unknown word reads as failed. */
    fun decodeTestAnswer(body: String): String = wrap("push test answer") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        o.str("result")?.let { if (it in RESULTS) it else FAILED } ?: throw IllegalArgumentException("result")
    }

    private fun clean(s: String, max: Int) = s.replace('\n', ' ').take(max)

    private fun obj(r: Report, version: Boolean = true): JsonObject = buildJsonObject {
        if (version) put("w", WireCodec.VERSION)
        r.model?.let { put("model", clean(it, MODEL_MAX)) }
        put("sent", r.sentAtMs)
        put("allowed", r.allowed)
        put("phone", clean(r.phone, 12))
        put("channels", buildJsonArray {
            r.channels.take(CHANNELS_MAX).forEach { c ->
                add(buildJsonObject { put("id", clean(c.id, NAME_MAX)); put("name", clean(c.name, NAME_MAX)); put("on", c.on)
                    c.lastPostMs?.takeIf { it > 0 }?.let { put("last", it) }
                })
            }
        })
        put("trace", buildJsonArray {
            r.trace.take(TRACE_MAX).forEach { e ->
                add(buildJsonObject { put("at", e.atMs); put("kind", clean(e.kind, 12)); put("detail", clean(e.detail, DETAIL_MAX)) })
            }
        })
        r.calls?.let { c ->
            put("calls", buildJsonObject {
                put("role", c.roleHeld); put("protection", c.protection); put("lookups", c.lookups)
                put("screened", buildJsonArray {
                    c.screened.take(CALLS_MAX).forEach { e ->
                        add(buildJsonObject { put("at", e.atMs); put("kind", clean(e.kind, 12)); put("detail", clean(e.detail, DETAIL_MAX)) })
                    }
                })
            })
        }
    }

    private fun entries(a: JsonArray?, max: Int, detailMax: Int = DETAIL_MAX): List<Entry> = (a ?: JsonArray(emptyList())).mapNotNull { e ->
        val t = e as? JsonObject ?: return@mapNotNull null
        val at = t.long("at")?.takeIf { it > 0 } ?: return@mapNotNull null
        val kind = t.str("kind")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        Entry(at, clean(kind, 12), clean(t.str("detail").orEmpty(), detailMax))
    }.take(max)

    private fun calls(o: JsonObject?): Calls? {
        o ?: return null
        fun flag(k: String) = (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        val role = flag("role") ?: return null
        return Calls(role, flag("protection") ?: true, flag("lookups") ?: false, entries(o["screened"] as? JsonArray, CALLS_MAX))
    }

    private fun report(o: JsonObject): Report {
        val sent = o.long("sent")?.takeIf { it > 0 } ?: throw IllegalArgumentException("sent")
        val allowed = (o["allowed"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: throw IllegalArgumentException("allowed")
        val channels = (o["channels"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { e ->
            val c = e as? JsonObject ?: return@mapNotNull null
            val id = c.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val on = (c["on"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: return@mapNotNull null
            Channel(clean(id, NAME_MAX), clean(c.str("name") ?: id, NAME_MAX), on, c.long("last")?.takeIf { it > 0 })
        }.take(CHANNELS_MAX)
        val trace = entries(o["trace"] as? JsonArray ?: throw IllegalArgumentException("trace"), TRACE_MAX)
        return Report(
            o.str("model")?.let { clean(it, MODEL_MAX) }?.takeIf { it.isNotBlank() }, sent, allowed, clean(o.str("phone") ?: "ALL", 12), channels, trace,
            calls(o["calls"] as? JsonObject),
        )
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    private fun checkVersion(o: JsonObject) {
        val w = (o["w"] as? JsonPrimitive)?.intOrNull ?: throw IllegalArgumentException("w")
        if (w != WireCodec.VERSION) throw IllegalArgumentException("unsupported wire version $w")
    }

    private inline fun <T> wrap(what: String, block: () -> T): T = try {
        block()
    } catch (e: WireFormatException) {
        throw e
    } catch (e: Exception) {
        throw WireFormatException("bad $what: ${e.message}", e)
    }
}
