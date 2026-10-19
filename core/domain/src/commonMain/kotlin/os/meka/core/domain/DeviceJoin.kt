package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica

/**
 * A new phone or Mac asking to join the household (build plan M1 "Clean-ups": approve a new device from your Fold;
 * ADR-005 amendment 2026-10-19, ADR-008 addendum). The server writes one `device_join` entity per ask (id the ask's
 * request id) when the enrolment code is used for a device id the household doesn't know yet while one of its phones or
 * Macs is in use; Meka's Fold or Mac shows it in Needs you: "A new Mac wants to join" · "MacBook Air · code 482 913 ·
 * check the new Mac shows the same" with **Allow** and **Not this one**. Only the server writes these (its answer route
 * sets the state), so they merge last-writer-wins.
 */
object DeviceJoinFields {
    /** The name the new device gave ("MacBook Air", "SM-F966B"), at most 80 characters. */
    const val NAME = "name"
    /** phone · mac. */
    const val KIND = "kind"
    /** The six digits both screens show. */
    const val CODE = "code"
    const val ASKED_AT = "askedAt"
    const val EXPIRES_AT = "expiresAt"
    /** waiting · allowed · refused. */
    const val STATE = "state"
    /** The device that answered ("Fold"); Null while waiting. */
    const val ANSWERED_BY = "answeredBy"
}

data class DeviceJoin(
    val id: String,
    val name: String,
    val kind: String,
    val code: String,
    val askedAtMs: Long,
    val expiresAtMs: Long,
    val state: String,
    val answeredBy: String? = null,
)

class DeviceJoinStore(private val replica: Replica) {
    fun all(): List<DeviceJoin> = replica.entities(EntityTypes.DEVICE_JOIN).mapNotNull { e -> DeviceJoinRules.read(e.ref.entityId) { e[it] } }
}

object DeviceJoinRules {
    const val WAITING = "waiting"
    const val ALLOWED = "allowed"
    const val REFUSED = "refused"
    const val PHONE = "phone"
    const val MAC = "mac"

    const val ALLOW = "Allow"
    const val REFUSE = "Not this one"
    const val LATER = "Later"

    /** The undo bar's lines after an answer (nothing to undo: an allowed device is revoked from Settings instead). */
    const val ALLOWED_BAR = "Allowed · the new device connects now"
    const val REFUSED_BAR = "Not allowed · it can't join with that ask"
    const val GONE_BAR = "That ask ran out or was already answered"
    const val OFFLINE_BAR = "Couldn't reach Navilife's server · try again"

    fun read(id: String, field: (String) -> FieldValue?): DeviceJoin? {
        fun text(f: String) = (field(f) as? FieldValue.Text)?.value
        fun long(f: String) = (field(f) as? FieldValue.Int64)?.value
        return DeviceJoin(
            id = id,
            name = text(DeviceJoinFields.NAME)?.take(80) ?: return null,
            kind = text(DeviceJoinFields.KIND)?.takeIf { it == PHONE || it == MAC } ?: return null,
            code = text(DeviceJoinFields.CODE)?.takeIf { c -> c.length == 6 && c.all { it in '0'..'9' } } ?: return null,
            askedAtMs = long(DeviceJoinFields.ASKED_AT) ?: return null,
            expiresAtMs = long(DeviceJoinFields.EXPIRES_AT) ?: return null,
            state = text(DeviceJoinFields.STATE) ?: WAITING,
            answeredBy = text(DeviceJoinFields.ANSWERED_BY),
        )
    }

    fun fields(j: DeviceJoin): Map<String, FieldValue> = linkedMapOf(
        DeviceJoinFields.NAME to FieldValue.Text(j.name),
        DeviceJoinFields.KIND to FieldValue.Text(j.kind),
        DeviceJoinFields.CODE to FieldValue.Text(j.code),
        DeviceJoinFields.ASKED_AT to FieldValue.Int64(j.askedAtMs),
        DeviceJoinFields.EXPIRES_AT to FieldValue.Int64(j.expiresAtMs),
        DeviceJoinFields.STATE to FieldValue.Text(j.state),
        DeviceJoinFields.ANSWERED_BY to (j.answeredBy?.let { FieldValue.Text(it) } ?: FieldValue.Null),
    )

    /** "482913" → "482 913". */
    fun spaced(code: String): String = if (code.length == 6) "${code.take(3)} ${code.drop(3)}" else code

    private fun noun(kind: String) = if (kind == MAC) "Mac" else "phone"

    /** Asks still waiting for an answer and not run out, newest first. */
    fun waiting(joins: List<DeviceJoin>, nowMs: Long): List<DeviceJoin> =
        joins.filter { it.state == WAITING && it.expiresAtMs > nowMs }.sortedByDescending { it.askedAtMs }

    /**
     * Needs you's cards, one per waiting ask, lit (someone holding the enrolment code is at the door): "A new Mac wants
     * to join" · "MacBook Air · code 482 913 · check the new Mac shows the same · 9 min left"; → Allow, ← Not this one,
     * ↑ Later. At most three (a burst of asks is itself suspicious; the rest wait behind them).
     */
    fun cards(joins: List<DeviceJoin>, nowMs: Long): List<DecisionCard> = waiting(joins, nowMs).take(3).map { j ->
        val minutes = ((j.expiresAtMs - nowMs + 59_999) / 60_000).coerceAtLeast(1)
        DecisionCard(
            id = j.id, kind = DecisionKind.DEVICE_JOIN, title = "A new ${noun(j.kind)} wants to join",
            why = "${j.name} · code ${spaced(j.code)} · check the new ${noun(j.kind)} shows the same · $minutes min left",
            taskId = null, yesLabel = ALLOW, laterLabel = REFUSE, openLabel = LATER,
            yes = DecisionEffect.ALLOW_DEVICE, later = DecisionEffect.REFUSE_DEVICE, open = DecisionEffect.SET_ASIDE,
            urgent = true,
        )
    }

    /** The answer route's state in words for the undo bar; null when [state] isn't one it sends. */
    fun answeredLine(state: String?): String = when (state) {
        ALLOWED -> ALLOWED_BAR
        REFUSED -> REFUSED_BAR
        null -> OFFLINE_BAR
        else -> GONE_BAR
    }

    // ---- the asking device ----

    /** While the new device waits: "Allow this Mac on your Fold or another Mac · Navilife → Needs you". */
    fun waitingTitle(kind: String): String = if (kind == MAC) "Allow this Mac from your phone or other Mac" else "Allow this phone from your Mac or other phone"

    /** "Code 482 913 · it shows in Needs you there · 9 min left". */
    fun waitingLine(code: String, expiresAtMs: Long, nowMs: Long): String {
        val minutes = ((expiresAtMs - nowMs + 59_999) / 60_000).coerceAtLeast(1)
        return "Code ${spaced(code)} · it shows in Needs you there · $minutes min left"
    }

    /** Activity's row for an answer, once per ask. */
    fun activityId(requestId: String): String = "d" + ActivityRules.fnv64("device:join:$requestId")
    const val WHY_YOU = "You did this in Needs you"

    /** Activity's line: "Allowed MacBook Air to join" · "Didn't allow MacBook Air to join". */
    fun activitySummary(name: String, allowed: Boolean): String = (if (allowed) "Allowed " else "Didn't allow ") + name + " to join"

    const val REFUSED_LINE = "Not allowed: your other device said Not this one."
    const val EXPIRED_LINE = "Nobody allowed it in 10 minutes. Connect again to ask again."
}
