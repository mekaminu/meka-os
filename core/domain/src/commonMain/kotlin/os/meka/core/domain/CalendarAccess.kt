package os.meka.core.domain

/**
 * Calendar editing, slice 1 (build plan M1 "Edit your calendars from MEKA"): the per-account permission. Non-AI, pure.
 *
 * An account is read-only until the owner taps **Allow editing** on it in Calendars; that reconnects the account and
 * asks the provider for the write permission (Google `calendar.events`, Microsoft `Calendars.ReadWrite`). The server
 * records whether the permission was actually granted and never writes to a calendar that hasn't got it. **Stop
 * editing** gives the permission up at once (no consent needed), and the server goes back to reading only.
 */
object CalendarAccessRules {
    /** Providers whose events MEKA can change once allowed (the public feeds are never editable). */
    val EDITABLE_PROVIDERS = setOf("google", "microsoft")

    fun offersEditing(provider: String): Boolean = provider in EDITABLE_PROVIDERS

    /** The line under an account's status in Calendars, or null for feeds. */
    fun line(provider: String, canEdit: Boolean, needsReconnect: Boolean = false): String? = when {
        !offersEditing(provider) -> null
        needsReconnect -> null // the reconnect line says enough
        canEdit -> "Editing allowed · events you add or change in MEKA go to ${providerName(provider)}"
        else -> "Read-only · MEKA only reads this calendar"
    }

    /** The account's action: allow editing (asks the provider), stop editing (at once), or none for feeds. */
    fun action(provider: String, canEdit: Boolean, needsReconnect: Boolean = false): CalendarAccessAction? = when {
        !offersEditing(provider) || needsReconnect -> null
        canEdit -> CalendarAccessAction.STOP_EDITING
        else -> CalendarAccessAction.ALLOW_EDITING
    }

    /** Reconnecting an expired account keeps what it had: one that could edit asks for editing again. */
    fun reconnectAsksEditing(canEdit: Boolean): Boolean = canEdit

    /** The screen's line under its title. */
    fun header(anyEditable: Boolean): String =
        if (anyEditable) "Events appear in Today on all your devices. Editing is on only where you allowed it."
        else "Read-only. Events appear in Today on all your devices."

    /** What the browser hand-off says before the provider's page opens (so the permission screen isn't a surprise). */
    fun allowNote(provider: String): String =
        "${providerName(provider)} will ask to let MEKA see, add, change and delete events. Keep it ticked to allow editing."

    /** After Stop editing. */
    fun stoppedLine(provider: String): String = "Editing off · MEKA only reads ${providerName(provider)} now"

    fun providerName(provider: String): String = when (provider) {
        "google" -> "Google"
        "microsoft" -> "Outlook"
        else -> provider
    }
}

enum class CalendarAccessAction(val label: String) {
    ALLOW_EDITING("Allow editing"),
    STOP_EDITING("Stop editing"),
}
