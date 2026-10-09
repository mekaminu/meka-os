package os.meka.core.domain

/**
 * The group digest in Needs you (build plan V1, "Messages assistant", slice 4): busy groups' chatter that didn't name Meka,
 * gathered on the phone with no AI ([MessageTriageRules.route] → Digest) and offered at **lunchtime (12:30)** and in the
 * **evening (18:30)**, and on demand in between. Non-AI and pure.
 *
 * The chatter itself never leaves the Fold (sealed there, a week at most), so this is the Fold's: the Mac gets the
 * digest when slice 4b adds the AI's gist per group (model output, like a triage card's gist, never the messages).
 */
object GroupDigestRules {
    const val LUNCH_MINUTE = 12 * 60 + 30
    const val EVENING_MINUTE = 18 * 60 + 30
    /** Lines an expanded group shows (the latest, oldest first). */
    const val EXPANDED_LINES = 12
    const val EXPANDED_LINE_CHARS = 240

    const val CAUGHT_UP = "Caught up"
    const val SHOW = "Show"
    const val HIDE = "Hide"

    /** "Lunchtime digest" from 12:30, "Evening digest" from 18:30; null in the morning. */
    fun slotLabel(nowMinute: Int): String? = when {
        nowMinute >= EVENING_MINUTE -> "Evening digest"
        nowMinute >= LUNCH_MINUTE -> "Lunchtime digest"
        else -> null
    }

    /** When the latest digest of today began ([dayStartMs] is local midnight); null before 12:30. */
    fun slotStartMs(dayStartMs: Long, nowMinute: Int): Long? = when {
        nowMinute >= EVENING_MINUTE -> dayStartMs + EVENING_MINUTE * 60_000L
        nowMinute >= LUNCH_MINUTE -> dayStartMs + LUNCH_MINUTE * 60_000L
        else -> null
    }

    /** "Digest at 12:30" / "Digest at 18:30" / "Next digest tomorrow at 12:30". */
    fun nextLine(nowMinute: Int): String = when {
        nowMinute < LUNCH_MINUTE -> "Digest at 12:30"
        nowMinute < EVENING_MINUTE -> "Digest at 18:30"
        else -> "Next digest tomorrow at 12:30"
    }

    /**
     * The groups with something new since Meka last caught up with each ([seen]: group key → when), as
     * [MessageTriageRules.digest] cards, busiest first. A group switched to Normal or Ignore has no card.
     */
    fun cards(items: List<CapturedItem>, settings: TriageSettings, seen: Map<String, Long>): List<GroupDigestCard> =
        MessageTriageRules.digest(items.filter { it.atMs > (seen[groupKey(it)] ?: 0L) }, settings, 0L)

    /**
     * Needs you's digest: due (shown open, its cards at the top) once a digest time has come and a group with news
     * wasn't caught up since; otherwise, with news, one quiet line Meka can open on demand. Nothing new, nothing shown.
     */
    fun view(
        items: List<CapturedItem>, settings: TriageSettings, seen: Map<String, Long>, dayStartMs: Long, nowMinute: Int,
    ): GroupDigestView? {
        val cards = cards(items, settings, seen)
        if (cards.isEmpty()) return null
        val start = slotStartMs(dayStartMs, nowMinute)
        val due = start != null && cards.any { (seen[it.groupKey] ?: 0L) < start }
        val groups = if (cards.size == 1) "1 group" else "${cards.size} groups"
        val messages = cards.sumOf { it.count }.let { if (it == 1) "1 message" else "$it messages" }
        val title = if (due) slotLabel(nowMinute)!! else "Group chats"
        val line = if (due) "$groups · $messages" else "$groups · $messages · ${nextLine(nowMinute)}"
        return GroupDigestView(title, line, due, cards, "$title: $messages in $groups.")
    }

    /**
     * One group's messages since [sinceMs] for its expanded card: the latest [EXPANDED_LINES], oldest first
     * ("Tunde · 13:02", the text), and how many earlier ones are left out.
     */
    fun expanded(items: List<CapturedItem>, groupKey: String, sinceMs: Long, timeOf: (Long) -> String): GroupDigestThread {
        val msgs = items.filter { it.atMs > sinceMs && it.conversation != null && groupKey(it) == groupKey && !it.text.isNullOrBlank() }
            .distinctBy { it.id }.sortedBy { it.atMs }
        val shown = msgs.takeLast(EXPANDED_LINES).map {
            GroupDigestLine(it.id, "${it.personName.trim()} · ${timeOf(it.atMs)}", clean(it.text!!))
        }
        val earlier = msgs.size - shown.size
        return GroupDigestThread(shown, earlier, if (earlier > 0) "+$earlier earlier" else null)
    }

    /**
     * Whether MEKA clears a notification after reading it (`cancelNotification`), so a Digest group stops sitting in
     * the shade: only when every message in it went to the digest and is [kept] on the phone. Anything that names Meka,
     * a 1:1 chat, a Normal group, or a message not yet kept leaves the notification alone.
     */
    fun clearsNotification(items: List<CapturedItem>, settings: TriageSettings, kept: Set<String>): Boolean =
        items.isNotEmpty() && items.all { MessageTriageRules.route(it, settings) is TriageRoute.Digest && it.id in kept }

    /** Caught up with [groupKeys] at [nowMs] (old groups forgotten after the digest's week). */
    fun caughtUp(seen: Map<String, Long>, groupKeys: Collection<String>, nowMs: Long): Map<String, Long> =
        (seen + groupKeys.associateWith { nowMs }).filterValues { it >= nowMs - MessageTriageRules.DIGEST_RETENTION_MS }

    /** The modes switched on a card: Digest is the default, so it isn't stored. */
    fun setMode(modes: Map<String, GroupMode>, group: String, mode: GroupMode): Map<String, GroupMode> {
        val rest = modes.filterKeys { People.key(it) != People.key(group) }
        return if (mode == GroupMode.DIGEST) rest else rest + (group.trim() to mode)
    }

    /** "Barça lads now goes to WhatsApp as usual" and the like, for the undo bar. */
    fun modeLine(title: String, mode: GroupMode): String = when (mode) {
        GroupMode.DIGEST -> "$title · in the digest"
        GroupMode.NORMAL -> "$title · back to WhatsApp as usual"
        GroupMode.IGNORE -> "$title · ignored"
    }

    /** What each mode means, under the switch. */
    fun modeHint(mode: GroupMode): String = when (mode) {
        GroupMode.DIGEST -> "Gathered here at 12:30 and 18:30; MEKA clears its notifications"
        GroupMode.NORMAL -> "Notifications as usual; MEKA reads only a mention of you"
        GroupMode.IGNORE -> "MEKA never reads it, not even a mention"
    }

    fun groupKey(item: CapturedItem): String = People.key(item.conversation.orEmpty())

    private fun clean(s: String): String {
        val t = s.replace(Regex("""[\u0000-\u001F\u007F]"""), " ").replace(Regex("""\s+"""), " ").trim()
        return if (t.length <= EXPANDED_LINE_CHARS) t else t.take(EXPANDED_LINE_CHARS - 1).trimEnd() + "…"
    }
}

/** Needs you's group digest: "Lunchtime digest" · "3 groups · 64 messages", open when [due]. */
data class GroupDigestView(
    val title: String,
    val line: String,
    val due: Boolean,
    val cards: List<GroupDigestCard>,
    val spoken: String,
)

data class GroupDigestLine(val id: String, val who: String, val text: String)

data class GroupDigestThread(val lines: List<GroupDigestLine>, val earlier: Int, val earlierLine: String?)
