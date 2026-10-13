package os.meka.core.domain

/**
 * Inbox autopilot, slice 1 (Meka, 2026-10-12 21:01): connecting mail accounts. Non-AI, pure, unit-tested.
 *
 * Meka decided MEKA may **read, organise (archive, label, link) and send only on his tap**, for three accounts: his
 * personal Gmail, his personal Outlook/Hotmail and a business Gmail. Each account is connected on MEKA's server with
 * the provider's own sign-in page (Google `gmail.modify` + `gmail.send`, Microsoft `Mail.ReadWrite` + `Mail.Send`);
 * the server keeps the sign-in encrypted and the apps only ever see an account's address, label and state. Each
 * account carries a label (Personal · Business · Outlook) so the business mail can get its own section and replies
 * always go from the account the mail came to. Reading, sorting and tidying come in the next slices.
 */
object MailProviders {
    /** A Gmail account (Google's sign-in, mail permissions only; separate from a Google Calendar account). */
    const val GMAIL = "gmail"
    /** An Outlook/Hotmail account (Microsoft's sign-in, mail permissions only). */
    const val OUTLOOK = "outlook_mail"
    val ALL = listOf(GMAIL, OUTLOOK)

    fun isMail(provider: String): Boolean = provider in ALL

    fun name(provider: String): String = when (provider) {
        GMAIL -> "Gmail"
        OUTLOOK -> "Outlook"
        else -> provider
    }
}

/** What an account is for, as Meka names it. The server stores [name]. */
enum class MailLabel(val label: String) {
    PERSONAL("Personal"),
    BUSINESS("Business"),
    OUTLOOK("Outlook");

    companion object {
        fun of(stored: String?): MailLabel? = entries.firstOrNull { it.name == stored }
    }
}

/** One connected account as the server reports it (never a token). */
data class MailAccountFacts(
    val provider: String,
    val email: String,
    /** The server's state: ok, error or needs_reconnect. */
    val status: String,
    val lastSyncAtMs: Long?,
    /** The stored [MailLabel.name], or null before one was set. */
    val label: String?,
)

/** One row in Mail. */
data class MailAccountRow(
    val provider: String,
    val email: String,
    val label: MailLabel,
    /** "Personal · Gmail". */
    val title: String,
    /** "Connected · checked 4 min ago · sorting comes next" / "Sign-in ended · Reconnect to keep MEKA reading it". */
    val line: String,
    val needsReconnect: Boolean,
    /** The labels Meka can switch it to, its own first. */
    val labels: List<MailLabel>,
    /** Slice 1b: the Google sign-in ends within the day (Reconnect shows, the line says when). */
    val ending: Boolean = false,
)

/** One "Connect …" button. */
data class MailConnect(val provider: String, val label: String, val note: String)

/** The Mail pane (Fold) and sheet (Mac). */
data class MailAccountsView(
    val header: String,
    val rows: List<MailAccountRow>,
    /** Shown while nothing is connected. */
    val emptyLine: String?,
    val connects: List<MailConnect>,
    /** What's still to connect of the three he named ("Still to connect: Outlook · the business Gmail"), or null. */
    val toConnect: String?,
)

object MailAccountRules {
    const val TITLE = "Mail"
    const val ROW_LINE = "Gmail and Outlook · read, tidied, sent only on your tap"
    const val HEADER = "MEKA reads and tidies your mail and sends only when you tap Send. Nothing is ever deleted for good."
    const val EMPTY = "No mail connected yet. Connect each account: your personal Gmail, Outlook and the business Gmail."
    const val OFFLINE = "Couldn't reach your server. Your accounts are still connected; try again."
    /** The label chips, always in this order (both apps). */
    val LABELS: List<MailLabel> = MailLabel.entries.toList()

    fun connectLabel(provider: String): String = "Connect ${MailProviders.name(provider)}"

    /** Said before the provider's page opens, so its permission screen isn't a surprise. */
    fun connectNote(provider: String): String = when (provider) {
        MailProviders.GMAIL -> "Google will ask to let MEKA read, organise and send your email. Keep every box ticked: MEKA sends only when you tap Send. " +
            "Google may say the app is unverified (it's your own): Advanced → Go to MEKA."
        else -> "Microsoft will ask to let MEKA read, organise and send your email. MEKA sends only when you tap Send."
    }

    /** A new account's label: Outlook for Outlook; the first Gmail is Personal, a second one Business. */
    fun defaultLabel(provider: String, existing: Collection<MailLabel>): MailLabel = when {
        provider == MailProviders.OUTLOOK -> MailLabel.OUTLOOK
        MailLabel.PERSONAL in existing -> MailLabel.BUSINESS
        else -> MailLabel.PERSONAL
    }

    /** "4 min ago" style, never a clock time (the rules have no time zone). */
    fun ago(atMs: Long, nowMs: Long): String {
        val min = ((nowMs - atMs).coerceAtLeast(0)) / 60_000
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            min < 24 * 60 -> "${min / 60} h ago"
            min < 48 * 60 -> "1 day ago"
            else -> "${min / (24 * 60)} days ago"
        }
    }

    fun line(status: String, lastSyncAtMs: Long?, nowMs: Long): String = when {
        status == "needs_reconnect" -> "Sign-in ended · Reconnect to keep MEKA reading it"
        status == "error" -> "Couldn't reach it last time · MEKA tries again every few minutes"
        lastSyncAtMs == null -> "Connected · checking the sign-in…"
        else -> "Connected · checked ${ago(lastSyncAtMs, nowMs)} · sorting comes in the next update"
    }

    fun row(a: MailAccountFacts, others: Collection<MailLabel>, nowMs: Long): MailAccountRow = row(a, others, nowMs, null)

    /** With the account's sign-in (slice 1b): a Google sign-in in its last day says when it ends, with Reconnect. */
    fun row(a: MailAccountFacts, others: Collection<MailLabel>, nowMs: Long, signIn: SignIn?): MailAccountRow {
        val label = MailLabel.of(a.label) ?: defaultLabel(a.provider, others)
        val endsAt = signIn?.takeIf { it.state == SignInState.ENDING && a.status != "needs_reconnect" }?.untilMs?.takeIf { it > nowMs }
        return MailAccountRow(
            provider = a.provider, email = a.email, label = label,
            title = "${label.label} · ${MailProviders.name(a.provider)}",
            line = endsAt?.let { endingLine(it, nowMs) } ?: line(a.status, a.lastSyncAtMs, nowMs),
            needsReconnect = a.status == "needs_reconnect" || endsAt != null,
            labels = listOf(label) + MailLabel.entries.filter { it != label },
            ending = endsAt != null,
        )
    }

    /** "Sign-in ends in 5 h · Reconnect to keep MEKA reading it" (no clock time: the rules have no time zone). */
    fun endingLine(untilMs: Long, nowMs: Long): String {
        val h = (untilMs - nowMs) / 3_600_000
        val whenText = if (h < 1) "within the hour" else "in $h h"
        return "Sign-in ends $whenText · Reconnect to keep MEKA reading it"
    }

    /** The pane: only mail accounts, Personal first, then Business, then Outlook (then by address). */
    fun view(accounts: List<MailAccountFacts>, nowMs: Long): MailAccountsView = view(accounts, nowMs, emptyList())

    /** With the household's sign-ins (slice 1b), so an account whose Google sign-in is ending says so. */
    fun view(accounts: List<MailAccountFacts>, nowMs: Long, signIns: List<SignIn>): MailAccountsView {
        val mail = accounts.filter { MailProviders.isMail(it.provider) && it.status != SignInRules.DISCONNECTED }
        // Stored labels first, so an unlabelled account defaults around them; then each resolved one counts too.
        val taken = mail.mapNotNull { MailLabel.of(it.label) }.toMutableList()
        fun signIn(a: MailAccountFacts) = signIns.firstOrNull { it.provider == a.provider && it.account.equals(a.email, ignoreCase = true) }
        val rows = mail.map { a -> row(a, taken, nowMs, signIn(a)).also { if (a.label == null) taken += it.label } }
            .sortedWith(compareBy<MailAccountRow> { it.label.ordinal }.thenBy { it.email })
        return MailAccountsView(
            header = HEADER,
            rows = rows,
            emptyLine = if (rows.isEmpty()) EMPTY else null,
            connects = MailProviders.ALL.map { MailConnect(it, connectLabel(it), connectNote(it)) },
            toConnect = toConnect(rows),
        )
    }

    /** Of the three accounts he named: which kinds have no account yet. */
    fun toConnect(rows: List<MailAccountRow>): String? {
        val gmails = rows.filter { it.provider == MailProviders.GMAIL }
        val left = buildList {
            if (gmails.none { it.label == MailLabel.PERSONAL }) add("your personal Gmail")
            if (rows.none { it.provider == MailProviders.OUTLOOK }) add("Outlook")
            if (gmails.none { it.label == MailLabel.BUSINESS }) add("the business Gmail")
        }
        return if (left.isEmpty() || rows.isEmpty()) null else "Still to connect: " + left.joinToString(" · ")
    }

    /** What changing a label says. */
    fun labelledLine(label: MailLabel): String = "Labelled ${label.label}"

    // ---- Disconnect (slice 1b) ----

    const val DISCONNECT = "Disconnect"
    const val KEEP = "Keep"

    /** Asked before disconnecting, in place on the row. */
    fun disconnectAsk(row: MailAccountRow): String =
        "Disconnect ${row.title} (${row.email})? MEKA stops reading it and forgets the sign-in. Nothing in the mailbox changes."

    /** After disconnecting: done, and where to take MEKA's permission away at the provider as well. */
    fun disconnectedLine(provider: String, email: String): String = "Disconnected $email · " + when (provider) {
        MailProviders.GMAIL -> "MEKA asked Google to withdraw its permission; Google Account → Security → Your connections to third-party apps shows it's gone"
        else -> "to remove MEKA from Microsoft too: account.microsoft.com → Privacy → Apps and services"
    }

    const val DISCONNECT_FAILED = "Couldn't reach your server, so it's still connected. Try again."

    // ---- Setup's Mail step (slice 1b) ----

    const val SETUP_KEY = "mail"
    const val SETUP_TITLE = "Mail"

    /**
     * Setup's Mail step: done once the three accounts he named are connected; to do while one is missing or its
     * sign-in ended ("Reconnect"); [accounts] null when MEKA's server couldn't be asked.
     */
    fun setupStep(accounts: List<MailAccountFacts>?, nowMs: Long): SetupStep {
        if (accounts == null) return SetupStep(SETUP_KEY, SETUP_TITLE, "Couldn't check", SetupState.UNKNOWN)
        val v = view(accounts, nowMs)
        val ended = v.rows.filter { it.needsReconnect }
        return when {
            ended.isNotEmpty() -> SetupStep(
                SETUP_KEY, SETUP_TITLE, "Sign-in ended · " + ended.joinToString(", ") { it.title } + " · Reconnect in Mail",
                SetupState.TODO, SetupFix.MAIL, "Reconnect",
            )
            v.rows.isEmpty() -> SetupStep(
                SETUP_KEY, SETUP_TITLE, "Connect your personal Gmail, Outlook and the business Gmail so MEKA can keep the inbox tidy",
                SetupState.TODO, SetupFix.MAIL, "Connect",
            )
            v.toConnect != null -> SetupStep(
                SETUP_KEY, SETUP_TITLE, "${v.rows.size} connected · ${v.toConnect}", SetupState.TODO, SetupFix.MAIL, "Connect",
            )
            else -> SetupStep(SETUP_KEY, SETUP_TITLE, "Connected · " + v.rows.joinToString(" · ") { it.label.label }, SetupState.DONE)
        }
    }
}
