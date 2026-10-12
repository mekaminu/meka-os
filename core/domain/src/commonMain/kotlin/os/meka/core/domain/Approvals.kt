package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * The approvals inbox (build plan V1, "Approvals inbox", slice 1; ADR-006 §1 and its 2026-10-11 addendum; ADR-008
 * addendum): requests from outside MEKA to do something with money or Meka's documents (a trade Kestrel wants to make,
 * a payment, sharing a document) wait in Needs you as cards, and nothing happens until Meka answers. **Approving always
 * needs his fingerprint** (Touch ID on the Mac): the policy engine's hard rule for financial transactions, trading
 * approvals and sharing documents is Level 3 with biometric confirmation, and no setting lowers it. Declining never
 * does (saying no is always safe). Non-AI, pure rules over plain fields, so the devices and the server read an
 * approval the same way.
 *
 * Fields of an `approval` (one per request). Written once by whoever asks (the server, for Kestrel); afterwards only the
 * answer changes: [APPROVED] and [DECLINED] are TrueWins, and if the two devices answer differently at the same time
 * the request counts as **declined** ([Approval.state]), so a conflict never turns into a yes.
 */
object ApprovalFields {
    /** [ApprovalKind.wire]. */
    const val KIND = "kind"
    /** Who asked, by name ("Kestrel"); display only. */
    const val SOURCE = "source"
    /** What would happen: "Buy 0.05 BTC at market". Display only, never instructions (ADR-006). */
    const val TITLE = "title"
    /** Up to [ApprovalRules.MAX_LINES] detail lines, one per line ("About £2,410", "Stop loss 5%"). Display only. */
    const val LINES = "lines"
    const val ASKED_AT = "askedAtMs"
    /** After this the request can't be approved; it leaves Needs you. */
    const val EXPIRES_AT = "expiresAtMs"
    /** TrueWins. */
    const val APPROVED = "approved"
    /** TrueWins; beats [APPROVED] when both are set. */
    const val DECLINED = "declined"
    const val DECIDED_AT = "decidedAtMs"
    /** [ApprovalConfirmation.wire]: how Meka confirmed it was him. */
    const val CONFIRMED_BY = "confirmedBy"
    /** The idempotency key made when the answer is given (ADR-006 §3): whoever acts on it does so once. */
    const val DECISION_KEY = "decisionKey"
    /** The device that answered. */
    const val DECIDED_ON = "decidedOn"
    /**
     * Slice 2 (Trading approvals, 2026-10-12): a yes is signed on the answering device by its approval key, which only
     * Meka's fingerprint unlocks ([ApprovalKeyFields]); base64 DER ECDSA P-256 / SHA-256 over [ApprovalRules.answerMessage].
     */
    const val ANSWER_SIGNATURE = "answerSignature"
    /** The approval key's public half that made [ANSWER_SIGNATURE] (X.509 SubjectPublicKeyInfo DER, base64). */
    const val ANSWER_PUBLIC_KEY = "answerPublicKey"
}

/**
 * One device's approval key (entity `approval_key`, id = the device's id; ADR-008 addendum 2026-10-12): an ECDSA P-256
 * key made in the device's secure hardware that **only a fingerprint unlocks** (an auth-bound Android Keystore key that
 * a new enrolled fingerprint invalidates; the Mac's Secure Enclave with `.biometryCurrentSet`). Its private half never
 * leaves the chip; only the public half is synced, written by the device itself, so the server (and Kestrel, which pins
 * the keys at setup) can check that a yes came from Meka's finger. A key renewed after a fingerprint change replaces it.
 */
object ApprovalKeyFields {
    /** X.509 SubjectPublicKeyInfo, DER, base64. */
    const val PUBLIC_KEY = "publicKey"
    /** "Fold" · "Mac": which device, for the keys list. */
    const val LABEL = "label"
    const val SINCE = "sinceMs"
}

/** A yes, signed on the device ([ApprovalFields.ANSWER_SIGNATURE], [ApprovalFields.ANSWER_PUBLIC_KEY]). */
data class ApprovalSignature(val publicKey: String, val signature: String)

/**
 * What to sign before approving ([ApprovalRules.prepare]): the exact [message], and the idempotency key and answer time
 * it names, which the answer then writes unchanged. [refusal] instead when the request can't be approved now (gone,
 * answered, expired): the app shows it and never asks for the fingerprint.
 */
data class ApprovalPrep(
    val approvalId: String,
    val message: String?,
    val decisionKey: String,
    val decidedAtMs: Long,
    val refusal: String?,
)

/** What a request asks to do, and the policy engine's action behind it ([action] is an `ActionType` name). */
enum class ApprovalKind(val wire: String, val label: String, val action: String) {
    TRADE("trade", "Trade", "TRADING_APPROVAL"),
    PAYMENT("payment", "Payment", "FINANCIAL_TRANSACTION"),
    SHARE("share", "Share", "SHARE_DOCUMENT");

    /** Every kind in this inbox needs the fingerprint to approve (ADR-006's hard rule; checked against the engine). */
    val biometric: Boolean get() = true

    companion object {
        fun of(wire: String?): ApprovalKind? = entries.firstOrNull { it.wire == wire }
    }
}

enum class ApprovalState { PENDING, APPROVED, DECLINED, EXPIRED }

/** How Meka confirmed an answer. Only [BIOMETRIC] can approve. */
enum class ApprovalConfirmation(val wire: String) { BIOMETRIC("biometric"), TAP("tap") }

data class Approval(
    val id: String,
    val kind: ApprovalKind,
    val source: String,
    val title: String,
    val lines: List<String>,
    val askedAtMs: Long,
    val expiresAtMs: Long,
    val approved: Boolean,
    val declined: Boolean,
    val decidedAtMs: Long?,
    val confirmedBy: String?,
    val decisionKey: String?,
    val decidedOn: String? = null,
    val answerSignature: String? = null,
    val answerPublicKey: String? = null,
) {
    /** Declined wins over approved (two devices answering differently is a no); an unanswered one expires. */
    fun state(nowMs: Long): ApprovalState = when {
        declined -> ApprovalState.DECLINED
        approved -> ApprovalState.APPROVED
        nowMs >= expiresAtMs -> ApprovalState.EXPIRED
        else -> ApprovalState.PENDING
    }
}

/** A waiting request as Needs you shows it. */
data class ApprovalCard(
    val id: String,
    val kind: ApprovalKind,
    /** "TRADE · KESTREL". */
    val label: String,
    val title: String,
    val lines: List<String>,
    /** "Asked 21:40 · expires in 14 min". */
    val why: String,
    /** Approving needs the fingerprint (always, here). */
    val biometric: Boolean,
    /** Expires within [ApprovalRules.URGENT_MS]: the why line is lit. */
    val urgent: Boolean,
    val approveLabel: String,
    val declineLabel: String,
    val spoken: String,
)

/** Why an answer wasn't taken. */
enum class ApprovalRefusal(val line: String) {
    GONE("This request is no longer here"),
    ANSWERED("Already answered on your other device"),
    EXPIRED("This request has expired · nothing was approved"),
    NEEDS_BIOMETRIC("Confirm it's you to approve"),
    /** The fingerprint prompt stayed open past [ApprovalRules.PREP_TTL_MS]: sign again. */
    STALE("That took too long · approve again"),
}

sealed class ApprovalDecision {
    /** The fields to write and the line to show ("Approved · Buy 0.05 BTC"). */
    data class Write(val fields: Map<String, FieldValue>, val approved: Boolean, val line: String) : ApprovalDecision()
    data class Refused(val reason: ApprovalRefusal) : ApprovalDecision()
}

object ApprovalRules {
    const val MAX_TITLE = 120
    const val MAX_LINES = 4
    const val MAX_LINE = 120
    const val MAX_SOURCE = 40
    const val DEFAULT_TTL_MS = 24 * 60 * 60_000L
    const val MAX_TTL_MS = 7 * 24 * 60 * 60_000L
    const val MIN_TTL_MS = 60_000L
    /** An approval expiring within this lights its why line. */
    const val URGENT_MS = 15 * 60_000L

    const val APPROVE = "Approve"
    const val DECLINE = "Decline"
    /** Under the card's title on each app: what Approve will ask for. */
    const val LOCK_FOLD = "Approving needs your fingerprint"
    const val LOCK_MAC = "Approving needs Touch ID"
    /** The system prompt's reason line (BiometricPrompt's subtitle; LocalAuthentication's reason). */
    fun promptReason(card: ApprovalCard): String = "Approve ${card.title.take(60)}"
    /** When this device can't take a fingerprint at all. */
    const val NO_BIOMETRIC_FOLD = "Set up a fingerprint on this phone to approve"
    const val NO_BIOMETRIC_MAC = "This Mac has no Touch ID · approve on the Fold"

    /** A prepared answer ([prepare]) must be signed and given within this. */
    const val PREP_TTL_MS = 5 * 60_000L
    const val ANSWER_VERSION = "MEKA-APPROVAL1"
    /** When this device's fingerprints changed since its approval key was made (the key is renewed; sign again). */
    const val KEY_RENEWED_FOLD = "Fingerprints changed on this phone · approval key renewed · approve again"
    const val KEY_RENEWED_MAC = "Touch ID changed on this Mac · approval key renewed · approve again"

    /**
     * The exact text a device signs to say yes to [a] (slice 2; docs/kestrel-integration.md), one value per line, no
     * trailing newline: the version, the approval's id, "approved", its kind, who asked, the decision key, the answer
     * time, when it expires, the answering device, and the lowercase hex SHA-256 of the title and its lines as shown
     * (joined with newlines). Kestrel rebuilds it from the status it reads and checks the signature with a key it pinned.
     */
    fun answerMessage(a: Approval, decisionKey: String, decidedAtMs: Long, deviceId: String): String = listOf(
        ANSWER_VERSION, a.id, "approved", a.kind.wire, a.source, decisionKey, decidedAtMs.toString(), a.expiresAtMs.toString(),
        deviceId, shownHash(a.title, a.lines),
    ).joinToString("\n")

    /** SHA-256 hex of the title and lines as Meka saw them, joined with "\n". */
    fun shownHash(title: String, lines: List<String>): String =
        os.meka.core.sync.Sha256.hexOf((listOf(title) + lines).joinToString("\n"))

    /** Step one of a yes: what to sign now, or why it can't be approved. */
    fun prepare(a: Approval?, id: String, nowMs: Long, key: String, deviceId: String): ApprovalPrep {
        val refusal = when {
            a == null -> ApprovalRefusal.GONE
            else -> when (a.state(nowMs)) {
                ApprovalState.APPROVED, ApprovalState.DECLINED -> ApprovalRefusal.ANSWERED
                ApprovalState.EXPIRED -> ApprovalRefusal.EXPIRED
                ApprovalState.PENDING -> null
            }
        }
        return ApprovalPrep(id, if (refusal == null && a != null) answerMessage(a, key, nowMs, deviceId) else null, key, nowMs, refusal?.line)
    }

    /** The `approval` id for a request: the same however often the asker sends it ([source] and its own [ref]). */
    fun entityId(source: String, ref: String): String = "ap" + ActivityRules.fnv64("approval:${source.trim().lowercase()}:$ref")

    /**
     * A new request's fields, cleaned: one-line title and detail lines cut to size, the expiry between a minute and a
     * week from asking (a day when none is given). Null when there's no title or no source, or it expires within a
     * minute.
     */
    fun requestFields(
        kind: ApprovalKind, source: String, title: String, lines: List<String>, askedAtMs: Long, expiresAtMs: Long? = null,
    ): Map<String, FieldValue>? {
        val t = oneLine(title, MAX_TITLE) ?: return null
        val s = oneLine(source, MAX_SOURCE) ?: return null
        val ls = lines.mapNotNull { oneLine(it, MAX_LINE) }.take(MAX_LINES)
        // A request that has already run out (or nearly) is refused rather than given time it didn't ask for.
        if (expiresAtMs != null && expiresAtMs < askedAtMs + MIN_TTL_MS) return null
        val expires = (expiresAtMs ?: (askedAtMs + DEFAULT_TTL_MS)).coerceAtMost(askedAtMs + MAX_TTL_MS)
        return buildMap {
            put(ApprovalFields.KIND, kind.wire.fv())
            put(ApprovalFields.SOURCE, s.fv())
            put(ApprovalFields.TITLE, t.fv())
            if (ls.isNotEmpty()) put(ApprovalFields.LINES, ls.joinToString("\n").fv())
            put(ApprovalFields.ASKED_AT, askedAtMs.fv())
            put(ApprovalFields.EXPIRES_AT, expires.fv())
        }
    }

    /** An approval from its fields; null when a field it needs is missing or its kind is unknown (never shown). */
    fun parse(id: String, f: Map<String, FieldValue>): Approval? {
        val kind = ApprovalKind.of(f[ApprovalFields.KIND]?.textOrNull) ?: return null
        val title = f[ApprovalFields.TITLE]?.textOrNull?.takeIf { it.isNotBlank() } ?: return null
        val source = f[ApprovalFields.SOURCE]?.textOrNull?.takeIf { it.isNotBlank() } ?: return null
        val asked = f[ApprovalFields.ASKED_AT]?.longOrNull ?: return null
        val expires = f[ApprovalFields.EXPIRES_AT]?.longOrNull ?: return null
        return Approval(
            id = id, kind = kind, source = source, title = title,
            lines = f[ApprovalFields.LINES]?.textOrNull?.split('\n')?.filter { it.isNotBlank() }.orEmpty().take(MAX_LINES),
            askedAtMs = asked, expiresAtMs = expires,
            approved = f[ApprovalFields.APPROVED]?.boolOrNull == true,
            declined = f[ApprovalFields.DECLINED]?.boolOrNull == true,
            decidedAtMs = f[ApprovalFields.DECIDED_AT]?.longOrNull,
            confirmedBy = f[ApprovalFields.CONFIRMED_BY]?.textOrNull,
            decisionKey = f[ApprovalFields.DECISION_KEY]?.textOrNull,
            decidedOn = f[ApprovalFields.DECIDED_ON]?.textOrNull,
            answerSignature = f[ApprovalFields.ANSWER_SIGNATURE]?.textOrNull,
            answerPublicKey = f[ApprovalFields.ANSWER_PUBLIC_KEY]?.textOrNull,
        )
    }

    /** The ones waiting for Meka: unanswered and not expired, the soonest to expire first (then the oldest). */
    fun pending(all: List<Approval>, nowMs: Long): List<Approval> =
        all.filter { it.state(nowMs) == ApprovalState.PENDING }.sortedWith(compareBy({ it.expiresAtMs }, { it.askedAtMs }, { it.id }))

    fun card(a: Approval, nowMs: Long, cal: LocalCalendar): ApprovalCard {
        val why = "Asked ${at(a.askedAtMs, nowMs, cal)} · expires ${until(a.expiresAtMs, nowMs, cal)}"
        return ApprovalCard(
            id = a.id, kind = a.kind, label = "${a.kind.label} · ${a.source}".uppercase(), title = a.title, lines = a.lines,
            why = why, biometric = a.kind.biometric, urgent = a.expiresAtMs - nowMs <= URGENT_MS,
            approveLabel = APPROVE, declineLabel = DECLINE,
            spoken = listOf("${a.source} asks: ${a.kind.label}", a.title).plus(a.lines).plus(why).joinToString(". "),
        )
    }

    /**
     * Meka's answer to [a]. Approve needs [ApprovalConfirmation.BIOMETRIC] when the kind does (always, here); declining
     * takes a tap. An answered or expired request can't be answered again. [key] is the idempotency key made now.
     * Slice 2: a yes also needs [signature], made by this device's approval key (fingerprint-only) over
     * [answerMessage] with [key] and [decidedAtMs] ([prepare]'s, within [PREP_TTL_MS]); without one it is refused as
     * [ApprovalRefusal.NEEDS_BIOMETRIC]. The core can't check the signature itself (no crypto in common code): the
     * server and Kestrel do.
     */
    fun decide(
        a: Approval?, approve: Boolean, confirmation: ApprovalConfirmation, nowMs: Long, key: String, deviceId: String,
        signature: ApprovalSignature? = null, decidedAtMs: Long = nowMs,
    ): ApprovalDecision {
        if (a == null) return ApprovalDecision.Refused(ApprovalRefusal.GONE)
        when (a.state(nowMs)) {
            ApprovalState.APPROVED, ApprovalState.DECLINED -> return ApprovalDecision.Refused(ApprovalRefusal.ANSWERED)
            ApprovalState.EXPIRED -> return ApprovalDecision.Refused(ApprovalRefusal.EXPIRED)
            ApprovalState.PENDING -> Unit
        }
        if (approve && a.kind.biometric) {
            if (confirmation != ApprovalConfirmation.BIOMETRIC) return ApprovalDecision.Refused(ApprovalRefusal.NEEDS_BIOMETRIC)
            if (signature == null || signature.signature.isBlank() || signature.publicKey.isBlank()) {
                return ApprovalDecision.Refused(ApprovalRefusal.NEEDS_BIOMETRIC)
            }
            if (decidedAtMs > nowMs + 60_000L || nowMs - decidedAtMs > PREP_TTL_MS) return ApprovalDecision.Refused(ApprovalRefusal.STALE)
        }
        val fields = buildMap {
            put(if (approve) ApprovalFields.APPROVED else ApprovalFields.DECLINED, true.fv())
            put(ApprovalFields.DECIDED_AT, (if (approve) decidedAtMs else nowMs).fv())
            put(ApprovalFields.CONFIRMED_BY, confirmation.wire.fv())
            put(ApprovalFields.DECISION_KEY, key.fv())
            put(ApprovalFields.DECIDED_ON, deviceId.fv())
            if (approve && signature != null) {
                put(ApprovalFields.ANSWER_SIGNATURE, signature.signature.fv())
                put(ApprovalFields.ANSWER_PUBLIC_KEY, signature.publicKey.fv())
            }
        }
        return ApprovalDecision.Write(fields, approve, (if (approve) "Approved · " else "Declined · ") + a.title)
    }

    // ---- Notices (slice 2): a heads-up when a request arrives, Critical in its last 15 minutes ----

    /** Every approval notice's key starts with this; both of a request's notices post at [noticeSlot]. */
    const val NOTICE_PREFIX = "approval:"

    /** Where [key]'s notification is posted: "approval:<id>" for both "approval:<id>" and "approval:<id>:ending". */
    fun noticeSlot(key: String): String = NOTICE_PREFIX + key.removePrefix(NOTICE_PREFIX).substringBefore(':')

    /**
     * The notices for the requests waiting ([pending]): a Needs a decision notice when one arrives ("Trade · Kestrel" ·
     * "Buy 0.05 BTC at market · answer by 22:30", the day named when it isn't the day asked), standing until its last [URGENT_MS], then a Critical one in the same
     * place ("Trade · Kestrel · expires 22:30"), which posts even in quiet hours. A request asked within its last 15
     * minutes has only the Critical one. Tapping opens Needs you; no button approves from the notification (the
     * fingerprint is asked in the app). An answered or expired request has none, so the platform takes it down.
     */
    fun notices(pending: List<Approval>, cal: LocalCalendar): List<Notice> = pending.flatMap { a ->
        val slot = NOTICE_PREFIX + a.id
        val heading = "${a.kind.label} · ${a.source}"
        val urgentFrom = a.expiresAtMs - URGENT_MS
        val ending = Notice(
            key = "$slot:ending", source = NoticeSource.APPROVAL, tier = NoticeTier.CRITICAL,
            title = "$heading · expires ${clock(a.expiresAtMs, maxOf(urgentFrom, a.askedAtMs), cal)}", text = a.title,
            atMs = maxOf(urgentFrom, a.askedAtMs), target = NoticeTarget.NEEDS_YOU, expiresAtMs = a.expiresAtMs,
            postAs = slot,
        )
        if (urgentFrom <= a.askedAtMs) listOf(ending) else listOf(
            Notice(
                key = slot, source = NoticeSource.APPROVAL, tier = NoticeTier.ACTION,
                title = heading, text = "${a.title} · answer by ${clock(a.expiresAtMs, a.askedAtMs, cal)}",
                atMs = a.askedAtMs, target = NoticeTarget.NEEDS_YOU, expiresAtMs = urgentFrom, postAs = slot,
            ),
            ending,
        )
    }

    /**
     * "22:30" on the day of [fromMs], else "Mon 12 Oct 09:30". Never "tomorrow" or "in 14 min": a notice held through
     * quiet hours posts later than it was made, so its words mustn't depend on when it is read.
     */
    private fun clock(ms: Long, fromMs: Long, cal: LocalCalendar): String {
        val day = cal.epochDayOf(ms)
        val time = LocalClock.formatMinute(cal.minuteOfDay(ms))
        return if (day == cal.epochDayOf(fromMs)) time else "${CivilDate.shortLabel(day)} $time"
    }

    // ---- Activity (ActivityKind.APPROVAL): one entry per request, whichever device answered ----

    fun activityId(approvalId: String): String = "p" + ActivityRules.fnv64("approval:$approvalId")

    /** (summary, detail, why) for the activity log. */
    fun activityLines(a: Approval, approved: Boolean, confirmation: ApprovalConfirmation): Triple<String, String?, String> = Triple(
        (if (approved) "Approved: " else "Declined: ") + a.title,
        a.lines.takeIf { it.isNotEmpty() }?.joinToString(" · "),
        "${a.source} asked · " + when {
            approved && confirmation == ApprovalConfirmation.BIOMETRIC -> "you confirmed it was you"
            approved -> "you approved"
            else -> "nothing was done"
        },
    )

    private fun oneLine(s: String, max: Int): String? {
        val t = s.replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return null
        if (t.length <= max) return t
        val cut = t.take(max - 1)
        val space = cut.lastIndexOf(' ')
        return (if (space > max / 2) cut.take(space) else cut).trimEnd() + "…"
    }

    /** "21:40" today, "yesterday 21:40", "Fri 9 Oct 21:40". */
    private fun at(ms: Long, nowMs: Long, cal: LocalCalendar): String {
        val day = cal.epochDayOf(ms)
        val today = cal.epochDayOf(nowMs)
        val time = LocalClock.formatMinute(cal.minuteOfDay(ms))
        return when (day) {
            today -> time
            today - 1 -> "yesterday $time"
            else -> "${CivilDate.shortLabel(day)} $time"
        }
    }

    /** "in 14 min" within the hour, else "at 23:00" today, "tomorrow 09:30", "Mon 12 Oct 09:30". */
    private fun until(ms: Long, nowMs: Long, cal: LocalCalendar): String {
        val left = ms - nowMs
        if (left < 60 * 60_000L) return "in ${((left + 59_999) / 60_000).coerceAtLeast(1)} min"
        val day = cal.epochDayOf(ms)
        val today = cal.epochDayOf(nowMs)
        val time = LocalClock.formatMinute(cal.minuteOfDay(ms))
        return when (day) {
            today -> "at $time"
            today + 1 -> "tomorrow $time"
            else -> "${CivilDate.shortLabel(day)} $time"
        }
    }
}

/**
 * The approvals as synced entities. Whoever asks writes the request ([request]; the server, for Kestrel); both apps
 * read [cards]; [decide] on either answers it everywhere. Nothing here acts on an answer: the one who asked reads it
 * (with its idempotency key) and acts once.
 */
class Approvals(private val replica: Replica, private val nowMs: () -> Long, private val calendar: LocalCalendar) {
    fun all(): List<Approval> = replica.entities(EntityTypes.APPROVAL).mapNotNull { ApprovalRules.parse(it.ref.entityId, it.fields) }

    fun find(id: String): Approval? = replica.entity(EntityTypes.APPROVAL, id)?.let { ApprovalRules.parse(id, it.fields) }

    fun cards(): List<ApprovalCard> = nowMs().let { now -> ApprovalRules.pending(all(), now).map { ApprovalRules.card(it, now, calendar) } }

    /** Writes a request once; the same [source] and [ref] again changes nothing. Returns its id, or null when invalid. */
    fun request(kind: ApprovalKind, source: String, ref: String, title: String, lines: List<String>, expiresAtMs: Long? = null): String? {
        val fields = ApprovalRules.requestFields(kind, source, title, lines, nowMs(), expiresAtMs) ?: return null
        val id = ApprovalRules.entityId(source, ref)
        if (replica.entity(EntityTypes.APPROVAL, id) == null) replica.commitLocal(EntityTypes.APPROVAL, id, fields)
        return id
    }

    /** Step one of a yes ([ApprovalRules.prepare]). */
    fun prepare(id: String, key: String, deviceId: String): ApprovalPrep = ApprovalRules.prepare(find(id), id, nowMs(), key, deviceId)

    /** Answers [id] ([ApprovalRules.decide]) and writes it when taken. */
    fun decide(
        id: String, approve: Boolean, confirmation: ApprovalConfirmation, key: String, deviceId: String,
        signature: ApprovalSignature? = null, decidedAtMs: Long? = null,
    ): ApprovalDecision {
        val now = nowMs()
        val d = ApprovalRules.decide(find(id), approve, confirmation, now, key, deviceId, signature, decidedAtMs ?: now)
        if (d is ApprovalDecision.Write) replica.commitLocal(EntityTypes.APPROVAL, id, d.fields)
        return d
    }
}

/**
 * The household's approval keys ([ApprovalKeyFields]): each device writes only its own ([register]), and only when the
 * public half changed (a new key after a fingerprint change replaces the old one).
 */
class ApprovalKeys(private val replica: Replica, private val nowMs: () -> Long) {
    fun mine(): String? = replica.entity(EntityTypes.APPROVAL_KEY, replica.deviceId)?.fields?.get(ApprovalKeyFields.PUBLIC_KEY)?.textOrNull

    /** Records this device's approval key; true when something was written. */
    fun register(publicKey: String, label: String): Boolean {
        if (publicKey.isBlank() || mine() == publicKey) return false
        replica.commitLocal(
            EntityTypes.APPROVAL_KEY, replica.deviceId,
            mapOf(
                ApprovalKeyFields.PUBLIC_KEY to publicKey.fv(),
                ApprovalKeyFields.LABEL to label.take(40).fv(),
                ApprovalKeyFields.SINCE to nowMs().fv(),
            ),
        )
        return true
    }
}
