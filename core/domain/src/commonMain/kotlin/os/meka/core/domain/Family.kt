package os.meka.core.domain

/**
 * Settings → Family on both apps (build plan "Family sharing with Jeanette", slice 4): Meka makes Jeanette's private
 * link to the shopping list, sees whether she has opened it ("Joined Sat 10 Oct · seen today") and turns it off in one
 * tap. The invites live on MEKA's server (ADR-005 amendment 2026-10-10); the devices only read them, so nothing here is
 * synced except the Activity entries ([ActivityKind.FAMILY]). Pure rules, no AI.
 */
enum class FamilyState { WAITING, JOINED, OFF }

/** One invite as the server lists it (never its token or her browser's key). [name] is the server's key ("jeanette"). */
data class FamilyMember(
    val id: String,
    val name: String,
    val state: FamilyState,
    val createdAtMs: Long,
    val claimedAtMs: Long? = null,
    val lastSeenAtMs: Long? = null,
)

/** A row in Family: "Jeanette" and "Joined Sat 10 Oct · seen today"; [lit] while it waits on her; Turn off while on. */
data class FamilyRow(
    val id: String,
    val title: String,
    val line: String,
    val state: FamilyState,
    val lit: Boolean,
    val canTurnOff: Boolean,
)

/**
 * The Family screen. [summary] says who can use the list; [rows] are the links that are on (newest first), then up to
 * [FamilyRules.MAX_OFF_SHOWN] turned off; [problem] says why the server couldn't be read (the rows are the last ones
 * read). [canInvite]: no link is on for [FamilyRules.DEFAULT_NAME] yet, so the screen offers to make one.
 */
data class FamilyView(
    val summary: String,
    val rows: List<FamilyRow>,
    val shared: String,
    val canInvite: Boolean,
    val problem: String? = null,
)

/** A link just made: what the share sheet sends. The URL carries the token, so it is shown once and never stored. */
data class FamilyLink(val id: String, val name: String, val url: String, val shareText: String, val note: String)

object FamilyRules {
    const val DEFAULT_NAME = "Jeanette"
    const val MAX_NAME = 30
    const val MAX_OFF_SHOWN = 3
    const val SHARED = "Only the shopping list is shared. Nothing else of yours leaves MEKA."
    const val NOTE = "The link works on the first phone that opens it, and only there. Send it to that one person."
    const val NOT_CONNECTED = "Connect this device to your server first."
    const val NO_KEY = "This device's key isn't registered with MEKA's server yet · try again in a minute"
    const val NO_ROUTE = "MEKA's server doesn't have family sharing yet · it arrives with the next deploy"
    const val OFFLINE = "Couldn't reach MEKA's server · try again"

    /**
     * A name MEKA's server accepts ("Jeanette", "Mary-Jane"): letters, spaces, hyphens and apostrophes, up to 30, and
     * not Meka's own. Spaces are tidied; null when it isn't one.
     */
    fun validName(raw: String): String? {
        val t = raw.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty() || t.length > MAX_NAME) return null
        if (!t.all { it.isLetter() || it == ' ' || it == '-' || it == '\'' }) return null
        if (t.equals(ShoppingRules.OWNER, ignoreCase = true)) return null
        return t
    }

    /** "jeanette" → "Jeanette", "mary jane" → "Mary Jane" (the server keeps names lower case). */
    fun display(name: String): String =
        name.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    fun state(server: String): FamilyState = when (server) {
        "joined" -> FamilyState.JOINED
        "revoked" -> FamilyState.OFF
        else -> FamilyState.WAITING
    }

    /** "Waiting · link made today" · "Joined Sat 10 Oct · seen today" · "Turned off". */
    fun line(m: FamilyMember, nowMs: Long, cal: LocalCalendar): String {
        val today = cal.epochDayOf(nowMs)
        fun day(ms: Long) = SearchRules.dayLabel(cal.epochDayOf(ms), today).let {
            if (it == "Today" || it == "Yesterday") it.lowercase() else it
        }
        return when (m.state) {
            FamilyState.WAITING -> "Waiting · link made ${day(m.createdAtMs)}"
            FamilyState.JOINED -> buildString {
                append("Joined ")
                append(day(m.claimedAtMs ?: m.createdAtMs))
                m.lastSeenAtMs?.let { append(" · seen ").append(day(it)) }
            }
            FamilyState.OFF -> "Turned off"
        }
    }

    fun row(m: FamilyMember, nowMs: Long, cal: LocalCalendar) = FamilyRow(
        id = m.id,
        title = display(m.name),
        line = line(m, nowMs, cal),
        state = m.state,
        lit = m.state == FamilyState.WAITING,
        canTurnOff = m.state != FamilyState.OFF,
    )

    fun view(members: List<FamilyMember>, nowMs: Long, cal: LocalCalendar, problem: String? = null): FamilyView {
        val newest = members.sortedWith(compareByDescending<FamilyMember> { it.createdAtMs }.thenBy { it.id })
        val on = newest.filter { it.state != FamilyState.OFF }
        val off = newest.filter { it.state == FamilyState.OFF }.take(MAX_OFF_SHOWN)
        val joined = on.filter { it.state == FamilyState.JOINED }.map { display(it.name) }.distinct()
        val waiting = on.filter { it.state == FamilyState.WAITING }.map { display(it.name) }.distinct()
        val summary = when {
            joined.isNotEmpty() -> "${names(joined)} can see and add to the shopping list"
            waiting.isNotEmpty() -> "Waiting for ${names(waiting)} to open the link"
            else -> "No one yet · make a link so $DEFAULT_NAME can add to the shopping list"
        }
        return FamilyView(
            summary = summary,
            rows = (on + off).map { row(it, nowMs, cal) },
            shared = SHARED,
            canInvite = on.none { it.name.equals(DEFAULT_NAME, ignoreCase = true) },
            problem = problem,
        )
    }

    private fun names(n: List<String>): String = when (n.size) {
        1 -> n[0]
        else -> n.dropLast(1).joinToString(", ") + " and " + n.last()
    }

    /** What the share sheet sends with the link. */
    fun shareText(name: String, url: String): String =
        "${display(name)}, here's our shopping list. Open it on your phone and add to it any time: $url"

    fun link(id: String, name: String, url: String) = FamilyLink(id, display(name), url, shareText(name, url), NOTE)

    // ---- Activity (ActivityKind.FAMILY): ids from the invite, so both devices write the same entry once ----

    fun joinedId(inviteId: String): String = "f" + ActivityRules.fnv64("family:joined:$inviteId")
    fun invitedId(inviteId: String): String = "f" + ActivityRules.fnv64("family:invited:$inviteId")
    fun turnedOffId(inviteId: String): String = "f" + ActivityRules.fnv64("family:off:$inviteId")

    fun joinedSummary(name: String) = "${display(name)} joined the shopping list"
    fun invitedSummary(name: String) = "Made a shopping list link for ${display(name)}"
    fun turnedOffSummary(name: String) = "Turned off ${display(name)}'s link"
    const val WHY_JOINED = "Her phone opened the link you made in Family"
    const val WHY_YOU = "You did this in Family"
}
