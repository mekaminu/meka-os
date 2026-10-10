package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica

/**
 * The call assistant's credit (build plan M1, call assistant: low-balance guard; Meka, 2026-10-08, Twilio's
 * auto-recharge off by choice). Non-AI, pure, unit-tested.
 *
 * MEKA's server reads the phone service's balance and account status a few times a day ([CallCreditReading]) and
 * writes the outcome into one server-authored `context_mode/call_credit` entity (ADR-008 addendum 2026-10-10; LWW,
 * additive, written only when something changed). Every device reads it with [CallCreditStore]:
 *
 * - under [LOW_PENCE] (£5) Needs you gets a "Call assistant credit low · £4.20 left · Top up" card ([card]);
 * - under [VERY_LOW_PENCE] (£2) the card is lit and a heads-up posts once ([notices]);
 * - under [EMPTY_PENCE] (50p), with the account suspended, or with the balance unreadable for [UNREADABLE_AFTER_MS],
 *   the assistant is **paused**: the Fold stops declining calls at work (they ring as usual, [CallScreeningRules.decide]),
 *   so a caller is never sent to a number that can't answer, and the Work screen says "Paused · Twilio credit".
 *
 * Only the amount, the currency and the state are synced; nothing about the account itself.
 */
object CallCreditFields {
    /** [CallCreditState] by name. */
    const val STATE = "state"
    /** The balance in pence (minor units); Null when it couldn't be read. */
    const val PENCE = "pence"
    /** ISO currency code, "GBP". */
    const val CURRENCY = "currency"
    /** When this state began (keys the heads-up, so each drop posts once). */
    const val SINCE = "since"
    /** PAUSED only: [CallCreditPause] by name; Null otherwise. */
    const val REASON = "reason"
}

enum class CallCreditState { OK, LOW, VERY_LOW, PAUSED }

enum class CallCreditPause { EMPTY, SUSPENDED, UNREADABLE }

data class CallCredit(
    val state: CallCreditState,
    val pence: Long?,
    val currency: String?,
    val sinceMs: Long,
    val reason: CallCreditPause? = null,
) {
    val paused: Boolean get() = state == CallCreditState.PAUSED
}

/**
 * What the server read from the phone service: [accountActive] false when the account is suspended or closed (null:
 * not known), [balance] as the service writes it ("4.20", "-0.12").
 */
data class CallCreditReading(val accountActive: Boolean?, val balance: String?, val currency: String?)

class CallCreditStore(private val replica: Replica) {
    fun current(): CallCredit? {
        val e = replica.entity(EntityTypes.CONTEXT_MODE, CallCreditRules.ENTITY_ID) ?: return null
        return CallCreditRules.read { e[it] }
    }
}

object CallCreditRules {
    const val ENTITY_ID = "call_credit"
    /** Needs you's card from here down. */
    const val LOW_PENCE = 500L
    /** The card is lit and a heads-up posts from here down. */
    const val VERY_LOW_PENCE = 200L
    /** Below this the assistant is paused: a call or two more could fail half-way. */
    const val EMPTY_PENCE = 50L
    /** Reads failing this long (since the last good one) pause the assistant. */
    const val UNREADABLE_AFTER_MS = 24 * 60 * 60_000L
    /** Where Top up goes: the phone service's billing page (Meka tops up himself; MEKA never pays anything). */
    const val TOP_UP_URL = "https://www.twilio.com/console/billing"
    const val CARD_ID = "callcredit"
    private const val NOTICE_STALE_MS = 2 * 24 * 60 * 60_000L

    /** "4.20" → 420, "-0.125" → -12, "5" → 500; null for anything that isn't a plain amount. */
    fun pence(balance: String?): Long? {
        val s = balance?.trim().orEmpty()
        if (!Regex("^-?\\d{1,9}(\\.\\d+)?$").matches(s)) return null
        val negative = s.startsWith("-")
        val body = s.removePrefix("-")
        val whole = body.substringBefore('.').toLong()
        val frac = body.substringAfter('.', "").padEnd(2, '0').take(2).toLong()
        val p = whole * 100 + frac
        return if (negative) -p else p
    }

    /**
     * What to write after a check: [reading] null when the read failed; [failingSinceMs] when reads started failing
     * (the last good read, or the first failure since the server started). Returns null when nothing should be
     * written (a failed read before [UNREADABLE_AFTER_MS] keeps what the devices have). [previous] keeps [CallCredit.sinceMs]
     * while the state is unchanged.
     */
    fun assess(reading: CallCreditReading?, previous: CallCredit?, nowMs: Long, failingSinceMs: Long?): CallCredit? {
        val pence = reading?.let { pence(it.balance) }
        val currency = reading?.currency?.trim()?.uppercase()?.takeIf { Regex("^[A-Z]{3}$").matches(it) }
        val (state, reason) = when {
            reading != null && reading.accountActive == false -> CallCreditState.PAUSED to CallCreditPause.SUSPENDED
            pence == null -> {
                val since = failingSinceMs ?: return null
                if (nowMs - since < UNREADABLE_AFTER_MS) return null
                CallCreditState.PAUSED to CallCreditPause.UNREADABLE
            }
            pence < EMPTY_PENCE -> CallCreditState.PAUSED to CallCreditPause.EMPTY
            pence < VERY_LOW_PENCE -> CallCreditState.VERY_LOW to null
            pence < LOW_PENCE -> CallCreditState.LOW to null
            else -> CallCreditState.OK to null
        }
        val same = previous != null && previous.state == state && previous.reason == reason
        return CallCredit(
            state = state,
            pence = pence,
            currency = currency ?: previous?.currency,
            sinceMs = if (same) previous!!.sinceMs else nowMs,
            reason = reason,
        )
    }

    /** The credit from its fields ([field] gives each one's value, null when unset); null without a known state. */
    fun read(field: (String) -> FieldValue?): CallCredit? {
        fun text(f: String) = (field(f) as? FieldValue.Text)?.value
        fun long(f: String) = (field(f) as? FieldValue.Int64)?.value
        val state = text(CallCreditFields.STATE)?.let { s -> CallCreditState.entries.firstOrNull { it.name == s } } ?: return null
        return CallCredit(
            state = state,
            pence = long(CallCreditFields.PENCE),
            currency = text(CallCreditFields.CURRENCY),
            sinceMs = long(CallCreditFields.SINCE) ?: 0L,
            reason = text(CallCreditFields.REASON)?.let { s -> CallCreditPause.entries.firstOrNull { it.name == s } },
        )
    }

    /** The entity's fields for [credit]. */
    fun fields(credit: CallCredit): Map<String, FieldValue> = linkedMapOf(
        CallCreditFields.STATE to FieldValue.Text(credit.state.name),
        CallCreditFields.PENCE to (credit.pence?.let { FieldValue.Int64(it) } ?: FieldValue.Null),
        CallCreditFields.CURRENCY to (credit.currency?.let { FieldValue.Text(it) } ?: FieldValue.Null),
        CallCreditFields.SINCE to FieldValue.Int64(credit.sinceMs),
        CallCreditFields.REASON to (credit.reason?.let { FieldValue.Text(it.name) } ?: FieldValue.Null),
    )

    /** "£4.20", "−£0.12", "$3.00", "4.20 CHF". */
    fun money(pence: Long, currency: String?): String {
        val symbol = when (currency) { null, "GBP" -> "£"; "USD" -> "$"; "EUR" -> "€"; else -> null }
        val abs = if (pence < 0) -pence else pence
        val amount = "${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
        val sign = if (pence < 0) "−" else ""
        return if (symbol != null) "$sign$symbol$amount" else "$sign$amount $currency"
    }

    private fun left(c: CallCredit): String? = c.pence?.let { money(it, c.currency) + " left" }

    /** Why a paused assistant is paused, in a few words. */
    private fun pausedWhy(c: CallCredit): String = when (c.reason) {
        CallCreditPause.SUSPENDED -> "Twilio has suspended the account"
        CallCreditPause.UNREADABLE -> "MEKA couldn't read the Twilio balance for a day"
        else -> left(c)?.let { "Out of credit · $it" } ?: "Out of credit"
    }

    /**
     * Needs you's card while the assistant is switched on and the credit is under £5 or paused; null otherwise. Top up
     * opens the phone service's billing page; Later sets it aside on this screen.
     */
    fun card(credit: CallCredit?, assistantOn: Boolean): DecisionCard? {
        if (credit == null || !assistantOn || credit.state == CallCreditState.OK) return null
        val (title, why) = when (credit.state) {
            CallCreditState.PAUSED -> "Call assistant paused · Twilio credit" to "${pausedWhy(credit)} · calls ring as usual at work until it's sorted"
            else -> "Call assistant credit low" to listOfNotNull(left(credit), "top up so callers can leave a message").joinToString(" · ")
        }
        return DecisionCard(
            id = CARD_ID, kind = DecisionKind.CREDIT, title = title, why = why, taskId = null,
            yesLabel = "Top up", laterLabel = "Later", openLabel = "Open Twilio",
            yes = DecisionEffect.OPEN_LINK, later = DecisionEffect.SET_ASIDE, open = DecisionEffect.OPEN_LINK,
            urgent = credit.state != CallCreditState.LOW, link = TOP_UP_URL,
        )
    }

    /** One heads-up when the credit drops under £2 and one when the assistant pauses ([NoticeSource.CALL_CREDIT]). */
    fun notices(credit: CallCredit?, assistantOn: Boolean): List<Notice> {
        if (credit == null || !assistantOn) return emptyList()
        val (title, text) = when (credit.state) {
            CallCreditState.VERY_LOW -> "Call assistant credit low" to (listOfNotNull(left(credit), "top up in Twilio").joinToString(" · "))
            CallCreditState.PAUSED -> "Call assistant paused" to "${pausedWhy(credit)} · calls ring as usual at work"
            else -> return emptyList()
        }
        return listOf(
            Notice(
                key = "callcredit:${credit.state.name}:${credit.sinceMs}", source = NoticeSource.CALL_CREDIT, tier = NoticeTier.HEADS_UP,
                title = title, text = text, atMs = credit.sinceMs, target = NoticeTarget.NEEDS_YOU,
                expiresAtMs = credit.sinceMs + NOTICE_STALE_MS,
            ),
        )
    }

    /** Health's call assistant line while switched on: null when the credit is fine or unknown. */
    fun healthLine(credit: CallCredit?): Pair<String, HealthState>? = when (credit?.state) {
        null, CallCreditState.OK -> null
        CallCreditState.LOW -> "On · ${left(credit) ?: "credit low"} · top up soon" to HealthState.WARN
        CallCreditState.VERY_LOW -> "On · only ${left(credit) ?: "a little credit"} · top up now" to HealthState.WARN
        CallCreditState.PAUSED -> "Paused · ${pausedWhy(credit)} · calls ring as usual" to HealthState.BAD
    }
}
