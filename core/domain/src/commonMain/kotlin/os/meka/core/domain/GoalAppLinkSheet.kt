package os.meka.core.domain

/**
 * A booked habit's workout-app link as a row opening a sheet (walk-through week 5, A17: no open input boxes on a page).
 * An open booked habit on Goals unfolded an always-open "Link to your workout app · hevy.com (optional)" box with Remove
 * beside the line under it. It is now one card row:
 *  - "Workout app" over the link kept and what Today's card does with it ("hevy.com · Today's card opens Hevy"), or
 *    what it's for while there's none ("Not set · Today's card can open the app you log workouts in"),
 *  - opening a sheet that starts with the link kept, says what the link does, Close · Save. Save stays dark until the
 *    typed link would change what's kept; a blank box removes it; a line that isn't a web address keeps the sheet open
 *    and says so under the field.
 *
 * Reuses [GoalAddRow] and [GoalAddSheet]. Pure; nothing new stored (the habit's link as before, [SessionRules.cleanAppLink]).
 */
object GoalAppLinkRules {
    const val LABEL = "Workout app"
    const val EMPTY = "Not set · Today's card can open the app you log workouts in"
    const val SAVE = "Save"
    const val NOT_A_LINK = "That isn't a web address · try hevy.com"
    /** The longest address shown on the row before it's cut with "…". */
    const val MAX_SHOWN = 40

    /** The link as it reads on the row: no scheme, no "www.", no trailing "/", cut at [MAX_SHOWN] with "…". */
    fun shown(link: String): String {
        val bare = link.substringAfter("://").removePrefix("www.").trimEnd('/')
        return if (bare.length <= MAX_SHOWN) bare else bare.take(MAX_SHOWN - 1).trimEnd() + "…"
    }

    /** "Workout app" over the link and what Today's card does with it, or what it's for. */
    fun row(h: HabitItem): GoalAddRow {
        val link = h.appLink?.let { SessionRules.cleanAppLink(it) } ?: return GoalAddRow(LABEL, EMPTY)
        return GoalAddRow(LABEL, "${shown(link)} · Today's card opens ${SessionRules.appName(link)}")
    }

    /** What the sheet starts with: the link kept, as it reads on the row (no scheme), else nothing. */
    fun initial(h: HabitItem): String = h.appLink?.let { SessionRules.cleanAppLink(it) }?.let { it.substringAfter("://").trimEnd('/') } ?: ""

    fun sheet(h: HabitItem): GoalAddSheet = GoalAddSheet(
        title = LABEL,
        hint = "hevy.com",
        spokenHint = "The web address of the app you log workouts in",
        note = if (h.appLink != null) "Today's card for ${h.title} opens this app while the session is ahead, on or just done. Left blank, the link is removed."
        else "Today's card for ${h.title} opens this app while the session is ahead, on or just done, so you can log the workout.",
        addLabel = SAVE,
    )

    /**
     * Save lights once the typed link would change what's kept: a different address, or blank while one is kept (which
     * removes it). Blank with nothing kept, or the same address again (typed with or without https://), stays dark. A
     * line that isn't a web address lights it, so the sheet can say so.
     */
    fun canSave(typed: String, h: HabitItem): Boolean {
        val t = typed.trim().take(SessionRules.MAX_LINK)
        val kept = h.appLink?.let { SessionRules.cleanAppLink(it) }
        if (t.isEmpty()) return kept != null
        val clean = SessionRules.cleanAppLink(t) ?: return true
        return clean.trimEnd('/') != kept?.trimEnd('/')
    }

    /** Whether the typed line is a link the habit can keep (blank removes it, so it's fine too). */
    fun linkOk(typed: String): Boolean = typed.isBlank() || SessionRules.cleanAppLink(typed.trim()) != null
}
