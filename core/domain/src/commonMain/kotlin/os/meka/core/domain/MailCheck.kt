package os.meka.core.domain

/**
 * Inbox fixes, slice 6 (Meka, 2026-10-15 21:09: "Both Gmail accounts say Couldn't reach it last time"): the real reason
 * a mail account's check failed, said on its card, and the `mail-autopilot` report a build run reads from the deploy
 * job. Non-AI, pure, unit-tested.
 *
 * The server sorts each failure into one of [WHY] (never an address, a token or a provider's own words) and keeps it on
 * the account (`integration_account.last_error`, an existing column); the account list carries it as `why`, so both
 * apps' Mail cards say "Gmail busy · …" or "Google sign-in didn't refresh · …" instead of one line for everything.
 */
object MailCheckRules {
    /** The provider said slow down (HTTP 429). */
    const val BUSY = "busy"
    /** The provider refused the check (HTTP 403: a rate limit, or the Gmail API not switched on). */
    const val REFUSED = "refused"
    /** The provider's own servers failed (HTTP 5xx). */
    const val DOWN = "down"
    /** Navilife's server couldn't reach the provider (timeout, connection). */
    const val NETWORK = "network"
    /** The sign-in couldn't be refreshed this time (not revoked: that is needs_reconnect). */
    const val SIGN_IN = "signin"
    /** Anything else. */
    const val OTHER = "other"
    val WHY = setOf(BUSY, REFUSED, DOWN, NETWORK, SIGN_IN, OTHER)

    /** A failure lasting this long adds "Reconnect if it carries on" (a day of failed checks). */
    const val LONG_MS = 24 * 3_600_000L

    /**
     * Sorts a failure: [httpStatus] when the provider answered (else null), [network] when it couldn't be reached,
     * [duringSignIn] when the failure came while refreshing the sign-in.
     */
    fun classify(httpStatus: Int?, network: Boolean, duringSignIn: Boolean): String = when {
        network -> NETWORK
        httpStatus == 429 -> BUSY
        httpStatus == 403 -> REFUSED
        httpStatus != null && httpStatus >= 500 -> DOWN
        duringSignIn -> SIGN_IN
        else -> OTHER
    }

    /** A stored value read back: one of [WHY], or null (an older server's exception name, or none). */
    fun read(stored: String?): String? = stored?.trim()?.lowercase()?.takeIf { it in WHY }

    private fun company(provider: String) = if (provider == MailProviders.GMAIL) "Google" else "Microsoft"

    /**
     * The card's line for an account whose last check failed: what happened, that Navilife retries, when it last
     * worked, and Reconnect's hint once it has failed for a day.
     */
    fun errorLine(provider: String, why: String?, lastSyncAtMs: Long?, nowMs: Long): String {
        val name = MailProviders.name(provider)
        val what = when (read(why)) {
            BUSY -> "$name is busy (too many requests) · Navilife tries again in a few minutes"
            REFUSED -> if (provider == MailProviders.GMAIL) "Gmail refused the check · usually its busy limit, or the Gmail API is off in Google Cloud · Navilife tries again"
                else "Outlook refused the check · Navilife tries again in a few minutes"
            DOWN -> "$name's servers had a problem · Navilife tries again in a few minutes"
            NETWORK -> "Couldn't reach $name (no answer in time) · Navilife tries again in a few minutes"
            SIGN_IN -> "${company(provider)} sign-in didn't refresh · Navilife tries again"
            else -> "Couldn't reach it last time · Navilife tries again every few minutes"
        }
        val worked = lastSyncAtMs?.let { " · last worked ${MailAccountRules.ago(it, nowMs)}" } ?: ""
        val long = lastSyncAtMs == null || nowMs - lastSyncAtMs >= LONG_MS
        val hint = if (long && read(why) in setOf(SIGN_IN, REFUSED, OTHER)) " · Reconnect if it carries on" else ""
        return what + worked + hint
    }

    // ---- The report (deploy's publish-fold job, step "Mail autopilot") ----

    /** What the server knows of one account's last check (in memory; never an address, a subject or a sender). */
    data class Report(
        /** "Personal", "Business", "Outlook" (never the address). */
        val label: String,
        val provider: String,
        val status: String,
        val why: String?,
        val lastCheckAtMs: Long?,
        val lastOkAtMs: Long?,
        val inboxTotal: Long? = null,
        val inboxUnread: Long? = null,
        /** The account's dry-run day ends here (null: no sign-in time recorded). */
        val dryRunEndsAtMs: Long? = null,
        /** Autopilot's switch and the kill switch. */
        val autopilotOn: Boolean? = null,
        val paused: Boolean? = null,
        /** What Autopilot did last check: "moved 12", "nothing to tidy", "waiting for the dry run", "off", "failed: busy". */
        val autopilot: String? = null,
        val sorted: Map<String, Int>? = null,
        val wouldTidy: Map<String, Int>? = null,
        /** Each stage that failed on the last check ("junk", "backlog" …) with its [WHY]. */
        val stageFailures: Map<String, String> = emptyMap(),
    )

    /** One line, for a `::notice title=Mail · Personal::…` annotation. */
    fun reportLine(r: Report, nowMs: Long, utc: (Long) -> String): String = buildList {
        add("${MailProviders.name(r.provider)} · ${r.status}" + (read(r.why)?.let { " ($it)" } ?: ""))
        add("checked " + (r.lastCheckAtMs?.let(utc) ?: "never") + " · ok " + (r.lastOkAtMs?.let(utc) ?: "never"))
        if (r.inboxTotal != null) add("inbox ${r.inboxTotal}" + (r.inboxUnread?.let { " · $it unread" } ?: ""))
        add("autopilot " + when {
            r.autopilotOn == null -> "?"
            !r.autopilotOn -> "off"
            r.paused == true -> "paused"
            r.dryRunEndsAtMs == null -> "on, no sign-in time"
            nowMs < r.dryRunEndsAtMs -> "on, dry run until ${utc(r.dryRunEndsAtMs)}"
            else -> "on"
        } + (r.autopilot?.let { ": $it" } ?: ""))
        r.sorted?.let { m -> add("sorted " + MailSort.entries.mapNotNull { s -> m[s.name]?.takeIf { it > 0 }?.let { "${s.name.lowercase()} $it" } }.joinToString(" ").ifEmpty { "none" }) }
        r.wouldTidy?.let { m -> add("would tidy " + m.filterKeys { it != MailTidy.KEEP.name }.values.sum()) }
        if (r.stageFailures.isNotEmpty()) add("failed: " + r.stageFailures.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value}" })
    }.joinToString(" · ")
}
