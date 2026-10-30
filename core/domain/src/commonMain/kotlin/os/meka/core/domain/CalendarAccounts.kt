package os.meka.core.domain

/**
 * How Calendars names a connected account (Meka's screenshot 2026-10-09 09:01). Non-AI, pure, unit-tested.
 *
 * - Every feed reads as what it is: the second news feed's raw id "news_more" is "Headlines", like BBC News's.
 * - A Google or Outlook account is titled with its main calendar's name, the one the Calendar key shows: Meka's own
 *   ([CalendarMarkFields.NAME]) when he renamed it, else "Personal" (Google) / "Hotmail" or "Outlook" (Microsoft);
 *   the address is never shown (walk-through A2, 2026-10-30); two accounts of the same name are told apart by
 *   [CalendarAccountRules.distinctTitles] ("Personal", "Personal 2").
 */
object CalendarAccountRules {
    /** "Google" · "Outlook" · "Headlines" (both news feeds) · "Fixtures" · "Bank holidays" · "Weather" · "Train lines". */
    fun providerLabel(provider: String): String = when (provider) {
        "google" -> "Google"
        "microsoft" -> "Outlook"
        "fixtures" -> "Fixtures"
        "news", "news_more" -> "Headlines"
        "bank_holidays" -> "Bank holidays"
        "weather" -> "Weather"
        "lines" -> "Train lines"
        TravelRules.PROVIDER -> "Travel times"
        CarCheckRules.PROVIDER -> "Car (DVLA)"
        else -> provider
    }

    /** A signed-in calendar account (it has an address), not a feed. */
    fun isMailAccount(provider: String, email: String): Boolean =
        (provider == "google" || provider == "microsoft") && '@' in email

    /** Microsoft's name for an account's main calendar. */
    const val OUTLOOK_MAIN = "Calendar"

    /** The default name of a Microsoft account's main calendar: "Hotmail" for a hotmail address, else "Outlook". */
    fun outlookName(account: String?): String {
        val domain = account.orEmpty().substringAfterLast('@', "").trim().lowercase()
        return if (domain.startsWith("hotmail.")) "Hotmail" else "Outlook"
    }

    /** Whether [calendarName] is [account]'s main calendar on [provider]. */
    fun isMainCalendar(provider: String, account: String?, calendarName: String?): Boolean {
        val n = calendarName?.trim().orEmpty()
        val a = account?.trim().orEmpty()
        if (n.isEmpty()) return false
        return when (provider) {
            "google" -> '@' in n && n.equals(a, ignoreCase = true)
            "microsoft" -> n.equals(OUTLOOK_MAIN, ignoreCase = true)
            else -> false
        }
    }

    /**
     * The account row's title: a feed's own name (`BBC News`), else its main calendar's name: Meka's own from [names]
     * (calendar key → name, [EventActions.calendarNames]), else the default.
     */
    fun title(provider: String, email: String, names: Map<String, String> = emptyMap()): String {
        if (provider in KIND_TITLED) return providerLabel(provider)
        if (!isMailAccount(provider, email)) return email.trim().ifEmpty { providerLabel(provider) }
        val account = email.trim().lowercase()
        val own = names.entries.firstOrNull { (key, _) ->
            val parts = key.split('|', limit = 3)
            parts.size == 3 && parts[0] == provider && parts[1] == account && isMainCalendar(provider, email, parts[2])
        }?.value?.let { CalendarRules.cleanName(it) }
        return own ?: if (provider == "google") CalendarRules.PERSONAL else outlookName(email)
    }

    /**
     * Feeds titled by what they bring, with where it comes from under it (quality gate slice 34: the bank holidays feed
     * read "GOV.UK · England and Wales" over "Bank holidays"; now "Bank holidays" over "GOV.UK · England and Wales ·
     * synced …"). A named feed (BBC News, FC Barcelona) keeps its own name as the title.
     */
    val KIND_TITLED: Set<String> = setOf("bank_holidays", "weather", "lines", TravelRules.PROVIDER, CarCheckRules.PROVIDER)

    /** How far ahead [eventsLine] looks. */
    const val EVENTS_AHEAD_DAYS = 30

    /**
     * Whether a signed-in account's events are coming through (Meka's 10:48 screenshots, 2026-10-09: his Hotmail
     * calendar is connected but nothing of it showed, so nothing said whether it was empty or broken): "12 events in the
     * next 30 days" · "1 event in the next 30 days" · "No events in the next 30 days". Counts the account's mirrored
     * events (any of its calendars, hidden ones too) that end after [nowMs] and start within [EVENTS_AHEAD_DAYS]. Null
     * for a feed, an account that needs reconnecting or hasn't synced yet (its status line says so).
     */
    fun eventsLine(provider: String, email: String, status: String, synced: Boolean, events: List<CalendarEvent>, nowMs: Long): String? {
        if (!isMailAccount(provider, email) || status == "needs_reconnect" || !synced) return null
        val account = email.trim()
        val until = nowMs + EVENTS_AHEAD_DAYS * 86_400_000L
        val n = events.count { e ->
            e.provider == provider && e.account?.trim().equals(account, ignoreCase = true) && e.endAtMs > nowMs && e.startAtMs < until
        }
        return when (n) {
            0 -> "No events in the next $EVENTS_AHEAD_DAYS days"
            1 -> "1 event in the next $EVENTS_AHEAD_DAYS days"
            else -> "$n events in the next $EVENTS_AHEAD_DAYS days"
        }
    }

    /**
     * When an account last synced, on the core's clock and zone (quality gate slice 33: Calendars wrote it in each
     * device's own zone, as Health did before slice 29): "17:11" today, "Tue 08:29" on an earlier day, "just now".
     */
    /**
     * Below this width an account's Reconnect / Allow editing sit under its words, not beside them (quality gate slice
     * 33: on the cover screen they squeezed the address and the status to a sliver); the same width as Mail's switches.
     */
    fun actionsUnder(widthDp: Float): Boolean = MailAutopilotPanelRules.stacked(widthDp)

    fun syncedAt(lastSyncAtMs: Long?, nowMs: Long, cal: LocalCalendar): String? =
        lastSyncAtMs?.let { HealthRules.at(it, nowMs, cal) }

    /**
     * Each account's title, told apart (walk-through A2, 2026-10-30: the address was the only thing that told two
     * accounts apart, so it sat on every line): [title] for each, and when two signed-in accounts would read the same
     * ("Personal" twice) the later ones by address get " 2", " 3"… Meka can rename either from its calendar's name.
     * Keyed by "provider|address" (address lower-cased).
     */
    fun distinctTitles(accounts: List<Pair<String, String>>, names: Map<String, String> = emptyMap()): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val seen = HashMap<String, Int>()
        accounts.sortedWith(compareBy({ it.first }, { it.second.trim().lowercase() })).forEach { (provider, email) ->
            val key = key(provider, email)
            if (key in out) return@forEach
            val base = title(provider, email, names)
            if (!isMailAccount(provider, email)) { out[key] = base; return@forEach }
            val n = (seen[base.lowercase()] ?: 0) + 1
            seen[base.lowercase()] = n
            out[key] = if (n == 1) base else "$base $n"
        }
        return out
    }

    /** [distinctTitles]' key for an account. */
    fun key(provider: String, email: String): String = provider + "|" + email.trim().lowercase()

    /**
     * The line under the title. [syncedAt] is the last sync's time as the device writes it ("08:29"), or null before
     * the first one. A signed-in account never shows its address (walk-through A2): "Google · synced 08:29", the title
     * names it. When the account's [title] is already its provider's name ("Outlook" over "Outlook · …" said it twice,
     * quality gate slice 36) the line starts at the state ("Synced 08:29", "Access expired").
     */
    fun statusLine(provider: String, email: String, status: String, syncedAt: String?, title: String? = null): String {
        val mail = isMailAccount(provider, email)
        val label = providerLabel(provider)
        val prefix: String? = label.takeUnless { title?.trim().equals(it, ignoreCase = true) }
        val who: String? = when {
            mail -> prefix
            provider in KIND_TITLED -> email.trim().ifEmpty { null }
            else -> label
        }
        // The row's own Reconnect button says what to do (quality gate slice 34: "access expired · Reconnect" sat right
        // above it).
        val what = when {
            status == "needs_reconnect" -> "access expired"
            status == "error" -> "couldn't sync last time · retrying"
            status == CarCheckRules.STATUS_OFF && provider == CarCheckRules.PROVIDER -> "off · waiting for the DVLA keys"
            status == TravelRules.STATUS_OFF -> "off · add the Google key (see Setup)"
            syncedAt != null -> "synced $syncedAt"
            else -> "first sync in progress"
        }
        return if (who != null) "$who · $what" else what.replaceFirstChar { it.uppercase() }
    }
}
