package os.meka.core.domain

/**
 * How Calendars names a connected account (Meka's screenshot 2026-10-09 09:01). Non-AI, pure, unit-tested.
 *
 * - Every feed reads as what it is: the second news feed's raw id "news_more" is "Headlines", like BBC News's.
 * - A Google or Outlook account is titled with its main calendar's name, the one the Calendar key shows: Meka's own
 *   ([CalendarMarkFields.NAME]) when he renamed it, else "Personal" (Google) / "Hotmail" or "Outlook" (Microsoft);
 *   the address moves to the line under it ("Google · meka@gmail.com · synced 08:29"), so it's still there to tell two
 *   accounts apart.
 */
object CalendarAccountRules {
    /** "Google" · "Outlook" · "Headlines" (both news feeds) · "Fixtures" · "Bank holidays" · "Weather". */
    fun providerLabel(provider: String): String = when (provider) {
        "google" -> "Google"
        "microsoft" -> "Outlook"
        "fixtures" -> "Fixtures"
        "news", "news_more" -> "Headlines"
        "bank_holidays" -> "Bank holidays"
        "weather" -> "Weather"
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
        if (!isMailAccount(provider, email)) return email.trim().ifEmpty { providerLabel(provider) }
        val account = email.trim().lowercase()
        val own = names.entries.firstOrNull { (key, _) ->
            val parts = key.split('|', limit = 3)
            parts.size == 3 && parts[0] == provider && parts[1] == account && isMainCalendar(provider, email, parts[2])
        }?.value?.let { CalendarRules.cleanName(it) }
        return own ?: if (provider == "google") CalendarRules.PERSONAL else outlookName(email)
    }

    /**
     * The line under the title. [syncedAt] is the last sync's time as the device writes it ("08:29"), or null before
     * the first one. A signed-in account carries its address: "Google · meka@gmail.com · synced 08:29".
     */
    fun statusLine(provider: String, email: String, status: String, syncedAt: String?): String {
        val who = if (isMailAccount(provider, email)) "${providerLabel(provider)} · ${email.trim()}" else providerLabel(provider)
        return when {
            status == "needs_reconnect" -> "$who · access expired · Reconnect"
            status == "error" -> "$who · couldn't sync last time · retrying"
            syncedAt != null -> "$who · synced $syncedAt"
            else -> "$who · first sync in progress"
        }
    }
}
