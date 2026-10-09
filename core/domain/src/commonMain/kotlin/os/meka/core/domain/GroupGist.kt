package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * The group digest's AI gist (build plan V1, "Messages assistant", slice 4b). At each digest time (12:30, 18:30) the Fold
 * makes **one** batched call for the busy groups in the digest — only each group's name, its messages' text and the
 * senders' names as the notifications showed them — and gets back a one-line gist per group and anything in the
 * chatter that asks something of Meka. Non-AI and pure: which groups and lines are sent, and what the answer may become.
 *
 * Messages are untrusted (ADR-006): a gist is display text; an ask is promoted to a Needs you card (Needs a reply with no
 * draft, so Meka opens the chat himself, or request cards for an action), never acted on.
 */
object GroupGistRules {
    /** A group needs this many messages since Meka caught up before its card is worth a gist (fewer: the three lines say it all). */
    const val MIN_MESSAGES = 5
    /** Groups in one call, busiest first. */
    const val MAX_GROUPS = 6
    /** The latest lines of each group sent, oldest first. */
    const val MAX_LINES = 40
    const val MAX_LINE_CHARS = 300
    const val MAX_GIST = 160
    /** Asks kept from one answer (each group's first ones, in the answer's order). */
    const val MAX_ASKS = 3

    /** A group as it is sent: its name and its latest lines. */
    data class Group(val groupKey: String, val name: String, val lines: List<Line>)

    /** One message as it is sent: the sender's label, the time ("13:02") and the text. */
    data class Line(val from: String, val at: String, val text: String)

    /**
     * The groups the call carries: the digest's [cards] (already busiest first) with at least [MIN_MESSAGES] since Meka
     * caught up, not [gisted] for this slot already, at most [MAX_GROUPS]; each with its latest [MAX_LINES] lines
     * since [seen] (a line cut at a word to [MAX_LINE_CHARS]). Only text a person wrote, never a number or the app.
     */
    fun groups(
        cards: List<GroupDigestCard>, items: List<CapturedItem>, seen: Map<String, Long>, gisted: Set<String>,
        timeOf: (Long) -> String,
        settings: TriageSettings = TriageSettings(),
    ): List<Group> = cards.asSequence()
        // Work mode → Messages: a group kept from MEKA's AI is never gisted, and a person kept from it is left out.
        .filter { it.count >= MIN_MESSAGES && it.groupKey !in gisted && !settings.isPrivate(it.title) }
        .take(MAX_GROUPS)
        .map { card ->
            val since = seen[card.groupKey] ?: 0L
            val lines = items.asSequence()
                .filter { it.atMs > since && it.conversation != null && GroupDigestRules.groupKey(it) == card.groupKey && !it.text.isNullOrBlank() &&
                        !settings.isPrivate(it.personName) }
                .distinctBy { it.id }.sortedBy { it.atMs }.toList().takeLast(MAX_LINES)
                .map { Line(it.personName.trim().take(60), timeOf(it.atMs), cut(it.text!!, MAX_LINE_CHARS)) }
            Group(card.groupKey, card.title, lines)
        }
        .filter { it.lines.isNotEmpty() }
        .toList()

    /** One group's gist as the model gave it (unchecked). */
    data class RawGroup(val name: String?, val gist: String?, val asks: List<RawAsk> = emptyList())

    /** Something in a group's chatter that asks Meka for something (unchecked): needs_reply or action. */
    data class RawAsk(val lane: String?, val from: String?, val summary: String?, val proposals: List<RawRequestProposal> = emptyList())

    /** A checked gist: the group it belongs to and the line under its title. */
    data class Gist(val groupKey: String, val gist: String?)

    /** A checked ask: who asked (one of the group's senders), the gist of the ask, and an action's checked proposals. */
    data class Ask(val groupKey: String, val group: String, val from: String, val lane: TriageLane, val summary: String, val proposals: List<RequestProposal>)

    /** The model's answer checked against what was [sent]: gists and asks only for groups that were sent. */
    data class Checked(val gists: List<Gist>, val asks: List<Ask>)

    /**
     * Checks the model's answer: a gist only for a group that was sent (by name), one clean line of at most [MAX_GIST];
     * an ask only from someone who wrote in that group, in a known lane (needs_reply, or action with proposals
     * [MessageRequestRules.check] allows; an action left with none is dropped), with a gist of what is asked; at most
     * [MAX_ASKS] in all. [today] and [nowMinute]: when the digest was made.
     */
    fun check(sent: List<Group>, raw: List<RawGroup>, today: Long, nowMinute: Int): Checked {
        val byKey = sent.associateBy { People.key(it.name) }
        val gists = mutableMapOf<String, String?>()
        val asks = mutableListOf<Ask>()
        raw.forEach { r ->
            val group = byKey[People.key(r.name.orEmpty())] ?: return@forEach
            if (group.groupKey !in gists) gists[group.groupKey] = clean(r.gist, MAX_GIST)
            val writers = group.lines.map { it.from }.distinctBy(People::key).associateBy(People::key)
            r.asks.forEach ask@{ a ->
                if (asks.size >= MAX_ASKS) return@ask
                val from = writers[People.key(a.from.orEmpty())] ?: return@ask
                val summary = clean(a.summary, MessageTriageRules.MAX_SUMMARY) ?: return@ask
                when (TriageLane.of(a.lane)) {
                    TriageLane.NEEDS_REPLY -> asks += Ask(group.groupKey, group.name, from, TriageLane.NEEDS_REPLY, summary, emptyList())
                    TriageLane.ACTION -> {
                        val ps = MessageRequestRules.check(a.proposals, today, nowMinute)
                        if (ps.isNotEmpty()) asks += Ask(group.groupKey, group.name, from, TriageLane.ACTION, summary, ps)
                    }
                    else -> Unit
                }
            }
        }
        // Every group sent has a row, gist or not, so it isn't sent again this slot.
        return Checked(sent.map { Gist(it.groupKey, gists[it.groupKey]) }, asks)
    }

    /** "Lunchtime digest" for the 12:30 slot, "Evening digest" for 18:30. */
    fun slotLabel(slotMinute: Int): String =
        if (slotMinute >= GroupDigestRules.EVENING_MINUTE) "Evening digest" else "Lunchtime digest"

    /** The Mac's line over its digest cards: "Evening digest · 2 groups · 64 messages". */
    fun headLine(gists: List<GroupGist>): String? {
        if (gists.isEmpty()) return null
        val groups = if (gists.size == 1) "1 group" else "${gists.size} groups"
        val n = gists.sumOf { it.count }
        val messages = if (n == 1) "1 message" else "$n messages"
        return "${gists.maxBy { it.slotMs }.slotLabel} · $groups · $messages"
    }

    internal fun clean(s: String?, max: Int): String? {
        val t = s?.replace(Regex("""[\u0000-\u001F\u007F]"""), " ")?.replace(Regex("""\s+"""), " ")?.trim()?.trim('"', '“', '”')?.trim()
        if (t.isNullOrEmpty()) return null
        return cut(t, max)
    }

    private fun cut(s: String, max: Int): String {
        val t = s.replace(Regex("""[\u0000-\u001F\u007F]"""), " ").replace(Regex("""\s+"""), " ").trim()
        if (t.length <= max) return t
        val c = t.take(max - 1)
        val at = c.lastIndexOf(' ').takeIf { it >= max / 2 } ?: (max - 1)
        return c.take(at).trimEnd() + "…"
    }
}

/**
 * Fields of a `group_gist` (ADR-008 addendum, 2026-10-09): one per group per digest slot, written by the Fold, synced so
 * the Mac shows the digest too. **Never the messages**: the group's name, how many messages and who wrote (the senders'
 * labels), the model's one-line gist (absent when the group was too quiet for one, or the AI was off), and when Meka
 * caught up with it on either device (LWW, so Undo can take it back).
 */
object GroupGistFields {
    const val GROUP_KEY = "groupKey"
    const val TITLE = "title"
    /** When the slot began (12:30 or 18:30 that day). */
    const val SLOT = "slotMs"
    const val SLOT_MINUTE = "slotMinute"
    const val COUNT = "count"
    /** "Tunde, Femi and 3 others". */
    const val PEOPLE = "people"
    /** The model's checked gist; display only, untrusted (ADR-006). */
    const val GIST = "gist"
    /** The latest message's time when the slot's digest was made. */
    const val LATEST = "latestMs"
    /** Caught up on either device (Null again after Undo). */
    const val CAUGHT_UP = "caughtUpAtMs"
}

/** One group's digest card as both apps show it (the Mac only has these; the Fold adds the lines it keeps). */
data class GroupGist(
    val groupKey: String,
    val title: String,
    /** "47 messages". */
    val countLine: String,
    val count: Int,
    val people: String,
    /** "Lineup debate for Getafe; Tunde shared a ticket link"; null when there is none. */
    val gist: String?,
    val slotMs: Long,
    /** "Lunchtime digest" / "Evening digest". */
    val slotLabel: String,
    val latestMs: Long,
    val caughtUpAtMs: Long?,
    val spoken: String,
)

/**
 * The digest's synced cards (non-AI). The Fold [save]s one per group per slot; both apps read [open] (the latest slot's
 * card per group not caught up, from the last [RETENTION_MS]); Caught up on either device ([caughtUp]) clears it on
 * both, and [undo] takes it back.
 */
class GroupGists(private val replica: Replica, private val nowMs: () -> Long) {
    /** The groups that already have a card for the slot beginning at [slotMs] (sent once a slot). */
    fun gisted(slotMs: Long): Set<String> = stored().filter { it.slotMs == slotMs }.mapTo(HashSet()) { it.groupKey }

    /** Writes [card]'s row for the slot (with [gist] when there is one) unless it has one; true when written. */
    fun save(card: GroupDigestCard, slotMs: Long, slotMinute: Int, gist: String?): Boolean {
        val id = entityId(card.groupKey, slotMs)
        if (replica.entity(EntityTypes.GROUP_GIST, id) != null) return false
        replica.commitLocal(
            EntityTypes.GROUP_GIST, id,
            buildMap {
                put(GroupGistFields.GROUP_KEY, card.groupKey.take(200).fv())
                put(GroupGistFields.TITLE, card.title.take(60).fv())
                put(GroupGistFields.SLOT, slotMs.fv())
                put(GroupGistFields.SLOT_MINUTE, slotMinute.toLong().fv())
                put(GroupGistFields.COUNT, card.count.toLong().fv())
                put(GroupGistFields.PEOPLE, card.people.take(120).fv())
                gist?.let { put(GroupGistFields.GIST, it.take(GroupGistRules.MAX_GIST + 1).fv()) }
                put(GroupGistFields.LATEST, card.latestMs.fv())
            },
        )
        return true
    }

    /** The latest slot's card per group, newest slot first then busiest; caught-up ones left out. */
    fun open(): List<GroupGist> = latest().filter { it.caughtUpAtMs == null }
        .sortedWith(compareByDescending<GroupGist> { it.slotMs }.thenByDescending { it.count })

    /** When Meka last caught up with each group on either device (from the latest slot's cards), for the Fold's digest. */
    fun caughtUpTimes(): Map<String, Long> =
        latest().mapNotNull { g -> g.caughtUpAtMs?.let { g.groupKey to it } }.toMap()

    /** Caught up with [groupKeys] now: their open cards leave on both devices. Returns what each was, for [undo]. */
    fun caughtUp(groupKeys: Collection<String>): Map<String, Long?> {
        val keys = groupKeys.toSet()
        val now = nowMs()
        val before = mutableMapOf<String, Long?>()
        latest().filter { it.groupKey in keys && it.caughtUpAtMs == null }.forEach { g ->
            before[g.groupKey] = null
            replica.commitLocal(EntityTypes.GROUP_GIST, entityId(g.groupKey, g.slotMs), mapOf(GroupGistFields.CAUGHT_UP to now.fv()))
        }
        return before
    }

    /** Takes back a Caught up: the cards [caughtUp] cleared come back, unless a newer slot has a card since. */
    fun undo(before: Map<String, Long?>) {
        latest().filter { it.groupKey in before }.forEach { g ->
            val was = before[g.groupKey]
            replica.commitLocal(
                EntityTypes.GROUP_GIST, entityId(g.groupKey, g.slotMs),
                mapOf(GroupGistFields.CAUGHT_UP to (was?.fv() ?: FieldValue.Null)),
            )
        }
    }

    private fun latest(): List<GroupGist> {
        val cutoff = nowMs() - RETENTION_MS
        return stored().filter { it.slotMs >= cutoff }
            .groupBy { it.groupKey }.values.map { all -> all.maxBy { it.slotMs } }
    }

    private fun stored(): List<GroupGist> = replica.entities(EntityTypes.GROUP_GIST).mapNotNull { e ->
        val key = e[GroupGistFields.GROUP_KEY].textOrNull ?: return@mapNotNull null
        val title = e[GroupGistFields.TITLE].textOrNull ?: return@mapNotNull null
        val slot = e[GroupGistFields.SLOT].longOrNull ?: return@mapNotNull null
        val count = (e[GroupGistFields.COUNT].longOrNull ?: 0L).toInt()
        val people = e[GroupGistFields.PEOPLE].textOrNull.orEmpty()
        val gist = e[GroupGistFields.GIST].textOrNull?.takeIf { it.isNotBlank() }
        val slotLabel = GroupGistRules.slotLabel((e[GroupGistFields.SLOT_MINUTE].longOrNull ?: 0L).toInt())
        val countLine = if (count == 1) "1 message" else "$count messages"
        GroupGist(
            groupKey = key, title = title, countLine = countLine, count = count, people = people, gist = gist,
            slotMs = slot, slotLabel = slotLabel, latestMs = e[GroupGistFields.LATEST].longOrNull ?: slot,
            caughtUpAtMs = e[GroupGistFields.CAUGHT_UP].longOrNull,
            spoken = buildString {
                append(title).append(", ").append(countLine)
                if (people.isNotBlank()) append(" from ").append(people)
                append('.')
                gist?.let { append(' ').append(it.trimEnd('.')).append('.') }
            },
        )
    }

    companion object {
        /** A slot's card is shown for a day at most (the next slot's card replaces it before then). */
        const val RETENTION_MS = 24 * 60 * 60_000L

        /** The `group_gist` id for a group's card in one slot: the same on every device and install. */
        fun entityId(groupKey: String, slotMs: Long): String = "g" + ActivityRules.fnv64("gist:$groupKey:$slotMs")
    }
}
