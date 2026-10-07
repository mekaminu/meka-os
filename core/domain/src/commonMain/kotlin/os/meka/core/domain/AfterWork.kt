package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * "While you were at work" (build plan M1). The Fold's notification listener captures WhatsApp messages, SMS and
 * missed calls during work mode; these rules decide what breaks through at once and how the after-work summary
 * reads. No AI: grouping, ordering and counts only (one AI line per person arrives with the AI layer).
 *
 * Captured content is untrusted (ADR-006): it is only ever displayed, never interpreted, and nothing here replies,
 * marks anything read or dismisses the original notification.
 */
enum class CaptureApp(val label: String) { WHATSAPP("WhatsApp"), SMS("SMS"), PHONE("Phone") }

enum class CaptureKind {
    MESSAGE,
    MISSED_CALL,
    /** A message left with the call assistant (the server writes it; the text is its transcript, when there is one). */
    VOICE_MESSAGE,
}

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
    /** Held from someone on the family list (set when synced, so the Mac can rank family without the lists). */
    val family: Boolean = false,
    /** The caller told the call assistant it is urgent (a message's own words are checked by [Urgency]). */
    val urgent: Boolean = false,
) {
    val personKey: String get() = People.key(personName)

    /** Urgent by the caller's answer or by its own words ("urgent", "emergency"). */
    val isUrgent: Boolean get() = urgent || Urgency.isUrgent(text)

    /** One line for this item in the after-work summary, the same on both apps. */
    val displayLine: String get() = when (kind) {
        CaptureKind.MISSED_CALL -> "Missed call"
        CaptureKind.MESSAGE -> text.orEmpty()
        CaptureKind.VOICE_MESSAGE -> text?.let { "Voice message \u00b7 \u201c$it\u201d" } ?: "Voice message"
    }
}

/**
 * People the owner picked from contacts. Matched by name, the way notifications identify senders, and by phone
 * number for calls ([numbers]: a name's numbers, picked with it; the call assistant only sees the caller's number).
 */
data class PeopleLists(
    val family: Set<String> = emptySet(),
    val alwaysNotify: Set<String> = emptySet(),
    val numbers: Map<String, Set<String>> = emptyMap(),
) {
    private val familyKeys = family.map(People::key).toSet()
    private val alwaysKeys = alwaysNotify.map(People::key).toSet()
    fun isFamily(name: String) = People.key(name) in familyKeys
    fun isAlwaysNotify(name: String) = People.key(name) in alwaysKeys

    /** The listed name a phone number belongs to (family first), or null. */
    fun nameForNumber(number: String): String? {
        val key = People.key(number)
        if (!key.startsWith("tel:")) return null
        fun find(names: Set<String>) = names.firstOrNull { n -> numbers[n].orEmpty().any { People.key(it) == key } }
        return find(family) ?: find(alwaysNotify)
    }

    /** A listed name with no number yet: its calls can't be recognised until it is picked again with one. */
    fun hasNumber(name: String) = numbers[name].orEmpty().isNotEmpty()

    /** Adds [number] to [name]'s numbers (blank numbers are ignored). */
    fun withNumber(name: String, number: String?): PeopleLists {
        val n = number?.trim()?.takeIf { it.isNotEmpty() } ?: return this
        return copy(numbers = numbers + (name to (numbers[name].orEmpty() + n)))
    }

    /** Drops numbers of names on neither list. */
    fun pruned(): PeopleLists = copy(numbers = numbers.filterKeys { it in family || it in alwaysNotify })
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
        item.isUrgent -> BreakThrough.URGENT
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
    /** Messages left with the call assistant. */
    val voiceMessages: Int = 0,
) {
    /** "3 messages · 1 voice message · 1 missed call", plain counts until the AI layer writes one line per person. */
    val line: String get() = listOfNotNull(
        plural(messages, "message").takeIf { messages > 0 },
        plural(voiceMessages, "voice message").takeIf { voiceMessages > 0 },
        plural(missedCalls, "missed call").takeIf { missedCalls > 0 },
    ).joinToString(" · ")
}

data class AfterWorkSummary(val people: List<PersonSummary>) {
    val isEmpty: Boolean get() = people.isEmpty()
    val messages: Int get() = people.sumOf { it.messages }
    val missedCalls: Int get() = people.sumOf { it.missedCalls }
    val voiceMessages: Int get() = people.sumOf { it.voiceMessages }
    val urgentPeople: Int get() = people.count { it.urgent }
    /** Everything held, for "12 held for later" during work. */
    val itemCount: Int get() = people.sumOf { it.items.size }

    /** The same summary with this device's family list applied too (the Fold has the lists; the Mac uses the flags). */
    fun withLists(lists: PeopleLists): AfterWorkSummary =
        if (lists.family.isEmpty()) this else AfterWorkSummaries.build(people.flatMap { it.items }, lists)

    /** "4 people · 9 messages · 2 missed calls" */
    val headline: String get() = if (isEmpty) "Nothing came in." else listOfNotNull(
        plural(people.size, "person", "people"),
        plural(messages, "message").takeIf { messages > 0 },
        plural(voiceMessages, "voice message").takeIf { voiceMessages > 0 },
        plural(missedCalls, "missed call").takeIf { missedCalls > 0 },
    ).joinToString(" · ")
}

object AfterWorkSummaries {
    /**
     * Grouped by person. Urgent people first (latest first), then family, then everyone else by latest. A caller known
     * only by number (the call assistant hears numbers) is shown by their listed name when the lists know the number.
     */
    fun build(items: List<CapturedItem>, lists: PeopleLists): AfterWorkSummary {
        val named = items.map { i -> lists.nameForNumber(i.personName)?.let { i.copy(personName = it) } ?: i }
        val people = named.groupBy { it.personKey }.values.map { group ->
            val sorted = group.sortedBy { it.atMs }
            PersonSummary(
                personName = sorted.last().personName,
                isFamily = lists.isFamily(sorted.last().personName) || sorted.any { it.family },
                urgent = sorted.any { it.isUrgent },
                messages = sorted.count { it.kind == CaptureKind.MESSAGE },
                voiceMessages = sorted.count { it.kind == CaptureKind.VOICE_MESSAGE },
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
            plural(summary.voiceMessages, "voice message").takeIf { summary.voiceMessages > 0 },
            plural(summary.missedCalls, "missed call").takeIf { summary.missedCalls > 0 },
        ).joinToString(" · ")
        return AfterWorkNudgeText(
            title = "While you were at work",
            text = "$who · $counts",
            publicText = "Your after-work summary is ready",
        )
    }
}

/**
 * Fields of a `held_message` (after-work summary on the Mac, Needs Meka #10, approved 2026-10-07): one per item the
 * Fold's listener held at work, synced through Meka's own server like his other data so the Mac shows the same
 * summary. Written once by the Fold; only [CLEARED] (TrueWins) and the blanked [TEXT] change afterwards.
 */
object HeldMessageFields {
    const val APP = "app"
    const val KIND = "kind"
    const val PERSON = "person"
    /** Message text, display only (untrusted, ADR-006); blanked when the summary is cleared. */
    const val TEXT = "text"
    const val CONVERSATION = "conversation"
    const val AT = "atMs"
    /** On the family list when it was held (the lists are picked from contacts on the Fold and stay there). */
    const val FAMILY = "family"
    /** The caller said it is urgent (call assistant voice messages; TrueWins). Absent = no. */
    const val URGENT = "urgent"
    /** "Done" on either device: gone from the summary on both. */
    const val CLEARED = "cleared"
    const val CLEARED_AT = "clearedAtMs"
}

/**
 * The held messages as synced entities, non-AI. The Fold [hold]s what its listener captured (a re-post of the same
 * message is one entity, also across a reinstall: the id comes from [Capture.itemId]); both apps read [items] for the
 * summary; Done on either [clear]s it everywhere and blanks the text. Items older than [RETENTION_MS] are not shown,
 * like the Fold's own sealed copy.
 */
class HeldMessages(private val replica: Replica, private val nowMs: () -> Long) {
    /** Writes the items not already held; returns how many were new. Never revives a cleared one. */
    fun hold(items: List<CapturedItem>, lists: PeopleLists = PeopleLists()): Int {
        var added = 0
        for (item in items) {
            val id = entityId(item.id)
            if (replica.entity(EntityTypes.HELD_MESSAGE, id) != null) continue
            replica.commitLocal(
                EntityTypes.HELD_MESSAGE, id,
                buildMap {
                    put(HeldMessageFields.APP, item.app.name.fv())
                    put(HeldMessageFields.KIND, item.kind.name.fv())
                    put(HeldMessageFields.PERSON, item.personName.take(200).fv())
                    put(HeldMessageFields.TEXT, item.text?.take(2_000).fv())
                    put(HeldMessageFields.CONVERSATION, item.conversation?.take(200).fv())
                    put(HeldMessageFields.AT, item.atMs.fv())
                    put(HeldMessageFields.FAMILY, (item.family || lists.isFamily(item.personName)).fv())
                    if (item.urgent) put(HeldMessageFields.URGENT, true.fv())
                },
            )
            added++
        }
        return added
    }

    /** What is waiting: not cleared, from the last [RETENTION_MS], oldest first, at most [Capture.MAX_ITEMS]. */
    fun items(): List<CapturedItem> {
        val cutoff = nowMs() - RETENTION_MS
        return replica.entities(EntityTypes.HELD_MESSAGE).mapNotNull { e ->
            if (e[HeldMessageFields.CLEARED].boolOrNull == true) return@mapNotNull null
            val at = e[HeldMessageFields.AT].longOrNull ?: return@mapNotNull null
            if (at < cutoff) return@mapNotNull null
            val app = e[HeldMessageFields.APP].textOrNull?.let { n -> CaptureApp.entries.firstOrNull { it.name == n } } ?: return@mapNotNull null
            val kind = e[HeldMessageFields.KIND].textOrNull?.let { n -> CaptureKind.entries.firstOrNull { it.name == n } } ?: return@mapNotNull null
            val person = e[HeldMessageFields.PERSON].textOrNull ?: return@mapNotNull null
            CapturedItem(
                id = e.ref.entityId, app = app, kind = kind, personName = person,
                text = e[HeldMessageFields.TEXT].textOrNull, conversation = e[HeldMessageFields.CONVERSATION].textOrNull,
                atMs = at, family = e[HeldMessageFields.FAMILY].boolOrNull == true,
                urgent = e[HeldMessageFields.URGENT].boolOrNull == true,
            )
        }.sortedWith(compareBy<CapturedItem>({ it.atMs }, { it.id })).takeLast(Capture.MAX_ITEMS)
    }

    /** The summary both apps show. */
    fun summary(lists: PeopleLists = PeopleLists()): AfterWorkSummary = AfterWorkSummaries.build(items(), lists)

    /**
     * Done: everything held up to now leaves the summary on both devices, and its text is blanked. Something that
     * arrives later (a message held while the Mac's Done was on its way) stays until the next Done.
     */
    fun clear(): Int {
        val now = nowMs()
        var n = 0
        for (e in replica.entities(EntityTypes.HELD_MESSAGE)) {
            if (e[HeldMessageFields.CLEARED].boolOrNull == true) continue
            if ((e[HeldMessageFields.AT].longOrNull ?: 0L) > now) continue
            replica.commitLocal(
                EntityTypes.HELD_MESSAGE, e.ref.entityId,
                mapOf(HeldMessageFields.CLEARED to true.fv(), HeldMessageFields.CLEARED_AT to now.fv(), HeldMessageFields.TEXT to FieldValue.Null),
            )
            n++
        }
        return n
    }

    companion object {
        const val RETENTION_MS = 7 * 24 * 60 * 60_000L

        /** The `held_message` id for a captured item's id: the same on every device and install. */
        fun entityId(captureId: String): String = "h" + ActivityRules.fnv64("held:$captureId")
    }
}
