package os.meka.core.domain

/**
 * "While you were at work" (build plan M1). The Fold's notification listener captures WhatsApp messages, SMS and
 * missed calls during work mode; these rules decide what breaks through at once and how the after-work summary
 * reads. No AI: grouping, ordering and counts only (one AI line per person arrives with the AI layer).
 *
 * Captured content is untrusted (ADR-006): it is only ever displayed, never interpreted, and nothing here replies,
 * marks anything read or dismisses the original notification.
 */
enum class CaptureApp(val label: String) { WHATSAPP("WhatsApp"), SMS("SMS"), PHONE("Phone") }

enum class CaptureKind { MESSAGE, MISSED_CALL }

data class CapturedItem(
    /** Stable across re-posts of the same notification (WhatsApp re-posts every unread message each time). */
    val id: String,
    val app: CaptureApp,
    val kind: CaptureKind,
    /** The sender as the notification names them (a contact name or a number). */
    val personName: String,
    /** Message text; null for missed calls. Display only. */
    val text: String?,
    /** Group chat name, when the message came from a group. */
    val conversation: String?,
    val atMs: Long,
) {
    val personKey: String get() = People.key(personName)
}

/** People the owner picked from contacts. Matched by name, the way notifications identify senders. */
data class PeopleLists(val family: Set<String> = emptySet(), val alwaysNotify: Set<String> = emptySet()) {
    private val familyKeys = family.map(People::key).toSet()
    private val alwaysKeys = alwaysNotify.map(People::key).toSet()
    fun isFamily(name: String) = People.key(name) in familyKeys
    fun isAlwaysNotify(name: String) = People.key(name) in alwaysKeys
}

object People {
    /** Case, spacing and phone-number punctuation don't make a different person. */
    fun key(name: String): String {
        val t = name.trim()
        val digits = t.filter { it.isDigit() }
        val looksLikeNumber = digits.length >= 6 && t.all { it.isDigit() || it in "+-() ." }
        // "+44 7700 900123" and "07700 900123" are the same phone: compare the last ten digits.
        if (looksLikeNumber) return "tel:" + digits.takeLast(10)
        return t.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }
}

enum class BreakThrough(val label: String) { URGENT("Urgent"), ALWAYS_NOTIFY("Always notify") }

object Urgency {
    private val words = Regex("\\b(urgent|urgently|emergency|emergencies)\\b", RegexOption.IGNORE_CASE)
    fun isUrgent(text: String?): Boolean = text != null && words.containsMatchIn(text)
}

object Capture {
    /** Keep the store bounded; the oldest items go first. */
    const val MAX_ITEMS = 500

    /** Whether an item captured at work should alert the owner straight away. */
    fun breakThrough(item: CapturedItem, lists: PeopleLists): BreakThrough? = when {
        Urgency.isUrgent(item.text) -> BreakThrough.URGENT
        lists.isAlwaysNotify(item.personName) -> BreakThrough.ALWAYS_NOTIFY
        else -> null
    }

    /** Deterministic id: the same message re-posted by its app maps to the same item. */
    fun itemId(app: CaptureApp, kind: CaptureKind, personName: String, conversation: String?, atMs: Long, text: String?): String =
        listOf(app.name, kind.name, People.key(personName), conversation.orEmpty(), atMs.toString(), stableHash(text.orEmpty())).joinToString("|")

    /** Adds [incoming] to [existing], dropping repeats; returns the merged list (oldest first) and what was new. */
    fun merge(existing: List<CapturedItem>, incoming: List<CapturedItem>): Pair<List<CapturedItem>, List<CapturedItem>> {
        val seen = existing.map { it.id }.toMutableSet()
        val fresh = incoming.filter { seen.add(it.id) }
        val all = (existing + fresh).sortedBy { it.atMs }.takeLast(MAX_ITEMS)
        return all to fresh
    }

    /** FNV-1a, so ids are identical on every platform and app version. */
    fun stableHash(s: String): String {
        var h = 0xcbf29ce484222325uL
        for (ch in s) { h = h xor ch.code.toULong(); h *= 0x100000001b3uL }
        return h.toString(16)
    }
}

data class PersonSummary(
    val personName: String,
    val isFamily: Boolean,
    val urgent: Boolean,
    val messages: Int,
    val missedCalls: Int,
    val apps: List<CaptureApp>,
    val firstAtMs: Long,
    val latestAtMs: Long,
    /** The most recent message text, for a one-line preview. */
    val latestText: String?,
    /** Everything from this person, oldest first. */
    val items: List<CapturedItem>,
) {
    /** "3 messages · 1 missed call", plain counts until the AI layer writes one line per person. */
    val line: String get() = listOfNotNull(
        plural(messages, "message").takeIf { messages > 0 },
        plural(missedCalls, "missed call").takeIf { missedCalls > 0 },
    ).joinToString(" · ")
}

data class AfterWorkSummary(val people: List<PersonSummary>) {
    val isEmpty: Boolean get() = people.isEmpty()
    val messages: Int get() = people.sumOf { it.messages }
    val missedCalls: Int get() = people.sumOf { it.missedCalls }
    val urgentPeople: Int get() = people.count { it.urgent }

    /** "4 people · 9 messages · 2 missed calls" */
    val headline: String get() = if (isEmpty) "Nothing came in." else listOfNotNull(
        plural(people.size, "person", "people"),
        plural(messages, "message").takeIf { messages > 0 },
        plural(missedCalls, "missed call").takeIf { missedCalls > 0 },
    ).joinToString(" · ")
}

object AfterWorkSummaries {
    /** Grouped by person. Urgent people first (latest first), then family, then everyone else by latest. */
    fun build(items: List<CapturedItem>, lists: PeopleLists): AfterWorkSummary {
        val people = items.groupBy { it.personKey }.values.map { group ->
            val sorted = group.sortedBy { it.atMs }
            PersonSummary(
                personName = sorted.last().personName,
                isFamily = lists.isFamily(sorted.last().personName),
                urgent = sorted.any { Urgency.isUrgent(it.text) },
                messages = sorted.count { it.kind == CaptureKind.MESSAGE },
                missedCalls = sorted.count { it.kind == CaptureKind.MISSED_CALL },
                apps = sorted.map { it.app }.distinct(),
                firstAtMs = sorted.first().atMs,
                latestAtMs = sorted.last().atMs,
                latestText = sorted.lastOrNull { it.text != null }?.text,
                items = sorted,
            )
        }
        val rank = compareBy<PersonSummary> { if (it.urgent) 0 else if (it.isFamily) 1 else 2 }
            .thenByDescending { it.latestAtMs }
            .thenBy { it.personName.lowercase() }
        return AfterWorkSummary(people.sortedWith(rank))
    }
}

internal fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

/** The words of the one quiet "summary ready" notification. Names stay off the lock screen ([publicText]). */
data class AfterWorkNudgeText(val title: String, val text: String, val publicText: String)

/**
 * "Your after-work summary is ready" (build plan M1): one quiet notification when work mode ends and something is
 * waiting. Not when Meka switched work off himself on this phone (he is already looking at it), never twice for the
 * same change, and never when the summary is empty. Urgent items already alerted at the time; this only counts them.
 */
object AfterWorkNudge {
    /**
     * [wasAtWork] is the work-mode state last recorded on this device (null on the very first check, which only
     * records). Every check records [atWork], so the change from at work to off work nudges exactly once.
     */
    fun shouldNudge(wasAtWork: Boolean?, atWork: Boolean, summary: AfterWorkSummary, appOnScreen: Boolean): Boolean =
        wasAtWork == true && !atWork && !summary.isEmpty && !appOnScreen

    fun text(summary: AfterWorkSummary): AfterWorkNudgeText? {
        if (summary.isEmpty) return null
        val names = summary.people.map { it.personName.trim() }
        val who = when (names.size) {
            1 -> names[0]
            2 -> "${names[0]} and ${names[1]}"
            3 -> "${names[0]}, ${names[1]} and ${names[2]}"
            else -> "${names[0]}, ${names[1]} and ${names.size - 2} others"
        }
        val counts = listOfNotNull(
            plural(summary.urgentPeople, "urgent", "urgent").takeIf { summary.urgentPeople > 0 },
            plural(summary.messages, "message").takeIf { summary.messages > 0 },
            plural(summary.missedCalls, "missed call").takeIf { summary.missedCalls > 0 },
        ).joinToString(" · ")
        return AfterWorkNudgeText(
            title = "While you were at work",
            text = "$who · $counts",
            publicText = "Your after-work summary is ready",
        )
    }
}
