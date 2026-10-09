package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica

/**
 * Whether each signed-in calendar account is still good (Reliability first, item 2; Meka approved 2026-10-09 22:29).
 * Non-AI, pure, unit-tested.
 *
 * While MEKA's Google app is in Testing (Needs Meka #17), Google lets its sign-in last 7 days: after that the refresh
 * token is refused (`invalid_grant`) and calendars silently stop updating. So the server writes one
 * `context_mode/sign_in.<key>` entity per signed-in account (ADR-008 addendum; server-authored, LWW, additive) saying
 * whether it is fine, about to end (the last day of the 7) or already needs reconnecting; Today on both apps shows the
 * most urgent as one line with a one-tap Reconnect, and the governor gives a heads-up.
 *
 * Once the app is published to Production the token keeps working past day 7: the line shows once, on day 6, and is
 * gone after the 7th day with nothing expiring. A Microsoft sign-in doesn't run out like this (only when revoked).
 */
object SignInFields {
    const val PROVIDER = "provider"
    const val ACCOUNT = "account"
    /** [SignInState] by name. */
    const val STATE = "state"
    /** ENDING: when Google stops honouring the sign-in. EXPIRED: when the server saw it refused. Null when OK. */
    const val UNTIL = "until"
    /** The account could edit (Allow editing), so Reconnect asks for editing again. */
    const val EDIT = "edit"
}

enum class SignInState { OK, ENDING, EXPIRED }

data class SignIn(
    val provider: String,
    val account: String,
    val state: SignInState,
    val untilMs: Long?,
    val canEdit: Boolean = false,
)

/** Today's line for the most urgent sign-in, with what Reconnect needs. */
data class SignInLine(
    /** "Google sign-in ends tomorrow at 14:05 · Reconnect". */
    val text: String,
    /** Unfolded under the line: why, and what stops it happening again. */
    val detail: String,
    /** Expired (calendars stopped updating): the critical colour; ending: the accent. */
    val critical: Boolean,
    val provider: String,
    val account: String,
    /** Reconnect asks for editing again ([CalendarAccessRules.reconnectAsksEditing]). */
    val editing: Boolean,
    val spoken: String,
)

class SignInStore(private val replica: Replica) {
    fun all(): List<SignIn> = replica.entities(EntityTypes.CONTEXT_MODE)
        .filter { it.ref.entityId.startsWith(SignInRules.ENTITY_PREFIX) }
        .mapNotNull { e ->
            val provider = e[SignInFields.PROVIDER].textOrNull ?: return@mapNotNull null
            val account = e[SignInFields.ACCOUNT].textOrNull ?: return@mapNotNull null
            val state = e[SignInFields.STATE].textOrNull?.let { s -> SignInState.entries.firstOrNull { it.name == s } } ?: return@mapNotNull null
            SignIn(provider, account, state, (e[SignInFields.UNTIL] as? FieldValue.Int64)?.value,
                (e[SignInFields.EDIT] as? FieldValue.Bool)?.value ?: false)
        }
        .sortedWith(compareBy({ it.provider }, { it.account }))
}

object SignInRules {
    const val ENTITY_PREFIX = "sign_in."
    const val DAY_MS = 86_400_000L
    /** How long Google honours a sign-in while its app is in Testing. */
    const val TESTING_GRANT_MS = 7 * DAY_MS
    /** Warn this long before it runs out. */
    const val WARN_BEFORE_MS = DAY_MS
    /** An expired sign-in's heads-up is stale after this (the line on Today stays until reconnected). */
    const val EXPIRED_NOTICE_STALE_MS = 2 * DAY_MS

    /** The entity id for an account: [key] is the server's own stable key for it (never the address). */
    fun entityId(key: String): String = ENTITY_PREFIX + key.filter { it.isLetterOrDigit() }.lowercase().take(40)

    /**
     * The state the server writes for an account: [status] is the server's ("ok", "error", "needs_reconnect"),
     * [grantedAtMs] when Meka last signed in (null for accounts signed in before this was recorded: nothing to warn
     * about until the next sign-in). Returns the state and its time ([SignInFields.UNTIL]); EXPIRED's time is
     * [seenExpiredMs] (when the server first saw it, kept across polls) or [nowMs].
     */
    fun state(provider: String, status: String, grantedAtMs: Long?, nowMs: Long, seenExpiredMs: Long? = null): Pair<SignInState, Long?> {
        if (status == "needs_reconnect") return SignInState.EXPIRED to (seenExpiredMs ?: nowMs)
        if (provider == "google" && grantedAtMs != null) {
            val ends = grantedAtMs + TESTING_GRANT_MS
            if (nowMs >= ends - WARN_BEFORE_MS && nowMs < ends) return SignInState.ENDING to ends
        }
        return SignInState.OK to null
    }

    private fun who(provider: String) = CalendarAccountRules.providerLabel(provider)

    private fun whenLabel(ms: Long, nowMs: Long, cal: LocalCalendar): String {
        val days = cal.epochDayOf(ms) - cal.epochDayOf(nowMs)
        val time = LocalClock.formatMinute(cal.minuteOfDay(ms))
        return when (days) {
            0L -> "today at $time"
            1L -> "tomorrow at $time"
            else -> CivilDate.shortLabel(cal.epochDayOf(ms)) + " at " + time
        }
    }

    /** The most urgent sign-in as Today's line (expired first, then the one ending soonest); null when all are fine. */
    fun line(signIns: List<SignIn>, nowMs: Long, cal: LocalCalendar): SignInLine? {
        val expired = signIns.filter { it.state == SignInState.EXPIRED }.minByOrNull { it.untilMs ?: 0L }
        if (expired != null) {
            val p = who(expired.provider)
            return SignInLine(
                text = "$p sign-in expired · calendars aren't updating · Reconnect",
                detail = "${expired.account} needs signing in again. Until then MEKA shows the events it had and can't add or change any." +
                    if (expired.provider == "google") " " + PRODUCTION_HINT else "",
                critical = true, provider = expired.provider, account = expired.account, editing = expired.canEdit,
                spoken = "$p sign-in expired for ${expired.account}. Calendars aren't updating. Double tap to reconnect.",
            )
        }
        val ending = signIns.filter { it.state == SignInState.ENDING && (it.untilMs ?: 0L) > nowMs }.minByOrNull { it.untilMs ?: 0L } ?: return null
        val until = ending.untilMs ?: return null
        val p = who(ending.provider)
        val whenText = whenLabel(until, nowMs, cal)
        return SignInLine(
            text = "$p sign-in ends $whenText · Reconnect",
            detail = "Google lets MEKA's sign-in last 7 days while its Google app is in testing. Reconnecting now keeps ${ending.account}'s calendars updating for another 7 days. $PRODUCTION_HINT",
            critical = false, provider = ending.provider, account = ending.account, editing = ending.canEdit,
            spoken = "$p sign-in for ${ending.account} ends $whenText. Double tap to reconnect.",
        )
    }

    const val PRODUCTION_HINT = "Publishing MEKA's Google app (Needs Meka #17) stops the weekly sign-ins."

    /**
     * Heads-ups ([NoticeSource.SIGN_IN]): one when a Google sign-in enters its last day ("Google sign-in ends tomorrow
     * at 14:05"), one when an account needs reconnecting. Keyed per account, state and time, so each posts once.
     */
    fun notices(signIns: List<SignIn>, nowMs: Long, cal: LocalCalendar): List<Notice> = signIns.mapNotNull { s ->
        val until = s.untilMs ?: return@mapNotNull null
        val p = who(s.provider)
        when (s.state) {
            SignInState.OK -> null
            SignInState.ENDING -> if (until <= nowMs) null else Notice(
                key = "signin:${s.provider}:${s.account}:ending:$until", source = NoticeSource.SIGN_IN, tier = NoticeTier.HEADS_UP,
                title = "$p sign-in ends ${whenLabel(until, nowMs, cal)}",
                text = "Reconnect from Today to keep ${s.account}'s calendars updating",
                atMs = until - WARN_BEFORE_MS, target = NoticeTarget.TODAY, expiresAtMs = until,
            )
            SignInState.EXPIRED -> Notice(
                key = "signin:${s.provider}:${s.account}:expired:$until", source = NoticeSource.SIGN_IN, tier = NoticeTier.HEADS_UP,
                title = "Reconnect $p", text = "${s.account}'s calendars stopped updating · Reconnect from Today",
                atMs = until, target = NoticeTarget.TODAY, expiresAtMs = until + EXPIRED_NOTICE_STALE_MS,
            )
        }
    }
}
