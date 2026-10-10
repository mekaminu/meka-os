package os.meka.core.domain

import os.meka.core.sync.DELETED_FIELD
import os.meka.core.sync.FieldValue
import os.meka.core.sync.Op
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * "What MEKA did and why" (build plan V1, ADR-006 §3): the activity log, landed before any automatic action so every
 * later one is recorded from day one. Non-AI.
 *
 * One `agent_action` entity per thing MEKA did on its own. Fields (all LWW: an entry is written by MEKA when it acts,
 * then only its undo marks change):
 * - `atMs`, `kind` ([ActivityKind]), `summary` (what it did, in words), `detail` (one more line, optional),
 *   `why` (the rule or setting behind it), `source` (machine name of that rule), `level` (autonomy level, for
 *   actions), `changes` (what it changed, for undo; see [ActivityRules.encodeChanges]),
 *   `undoneAtMs` and `undoNote` once undone.
 *
 * Text is built from Meka's own data (task titles, his settings) and stays in his synced data like the rest; it is
 * never sent anywhere else (ADR-013 telemetry rules unchanged). Nothing from untrusted content is written here as
 * an instruction: summaries are shown as plain text only.
 */
object ActivityFields {
    const val AT = "atMs"
    const val KIND = "kind"
    const val SUMMARY = "summary"
    const val DETAIL = "detail"
    const val WHY = "why"
    const val SOURCE = "source"
    const val LEVEL = "level"
    const val CHANGES = "changes"
    const val UNDONE_AT = "undoneAtMs"
    const val UNDO_NOTE = "undoNote"
}

enum class ActivityKind {
    /** A notification that reached you (Critical, Needs a decision or Heads-up). */
    REMINDED,
    /** A digest that went out (it never interrupts). */
    DIGEST,
    /** MEKA changed something of yours (an automatic action); can be undone. */
    CHANGED,
    /**
     * A new phone build was published without Meka (the GitHub build, after a green CI run on main). Written by the
     * server; nothing to undo (nothing installs without his tap). Older apps skip entries of a kind they don't know.
     */
    PUBLISHED,
    /**
     * An edit Meka made to his Google/Outlook calendar from MEKA (calendar editing, slice 2d): added, changed, moved or
     * deleted, and how it went. Not stored as an `agent_action`: each row is read from the synced `event_edit` itself
     * (see [ActivityRules.calendarEditItem]), so it follows the edit as the server answers. Nothing to undo here (an edit
     * is undone in its five seconds; after that it is changed again from the event).
     */
    CALENDAR,
    /**
     * A call spam protection stopped on the Fold: blocked (on the block list) or sent to the assistant (failed the
     * network's caller check, or withheld in quiet hours). Nothing to undo; unblocking is in Work mode. Added 2026-10-09.
     */
    SCREENED,
    /**
     * What the call assistant heard when it asked a caller "is it urgent?" and what it made of it (call assistant
     * polish 8d). Written by the server; nothing to undo. Added 2026-10-09.
     */
    CALL,
    /**
     * Family sharing (slice 4): a shopping list link made or turned off in Family, and the day the family member's
     * phone opened it ("Jeanette joined the shopping list"). Ids come from the invite, so both devices write one entry.
     * Nothing to undo here (Turn off is in Family). Added 2026-10-10.
     */
    FAMILY,
}

/** One field MEKA changed: what it was, and what MEKA set. */
data class ActivityChange(
    val entityType: String,
    val entityId: String,
    val field: String,
    val before: FieldValue,
    val after: FieldValue,
)

/** One field MEKA is about to change (see [ActivityLog.act]). */
data class PlannedChange(val entityType: String, val entityId: String, val field: String, val value: FieldValue)

data class ActivityItem(
    val id: String,
    val atMs: Long,
    val kind: ActivityKind,
    val summary: String,
    val detail: String?,
    val why: String,
    val source: String?,
    val level: String?,
    val changes: List<ActivityChange>,
    val undoneAtMs: Long?,
    val undoNote: String?,
) {
    val canUndo: Boolean get() = changes.isNotEmpty() && undoneAtMs == null
}

/** One row of the Activity screen. */
data class ActivityRow(
    val id: String,
    /** "09:00" */
    val time: String,
    val kind: ActivityKind,
    /** "Reminded you: Cancel or keep Netflix?" */
    val summary: String,
    /** "Cancel by tomorrow" */
    val detail: String?,
    /** "Why: Cancel-by dates · Heads-up" */
    val why: String,
    val canUndo: Boolean,
    /** "Undone at 09:12" (plus a note when only part of it could be undone). */
    val undoneLine: String?,
)

data class ActivityDay(val day: Long, val label: String, val rows: List<ActivityRow>)

data class ActivityView(
    /** "This week: 5 reminders · 2 digests" */
    val weekLine: String,
    val days: List<ActivityDay>,
    /** What the screen says while there is nothing yet. */
    val emptyLine: String,
) {
    val isEmpty: Boolean get() = days.isEmpty()

    companion object {
        val EMPTY = ActivityView("", emptyList(), ActivityRules.EMPTY_LINE)
    }
}

/** What an undo did. */
enum class UndoOutcome(val line: String) {
    UNDONE("Undone"),
    PARTLY("Partly undone: you've changed some of it since"),
    CHANGED_SINCE("Nothing to undo: you've changed it since"),
    NOT_UNDOABLE("This can't be undone"),
}

/** Pure rules, unit-tested without a replica. */
object ActivityRules {
    const val DAYS_SHOWN = 30
    const val EMPTY_LINE =
        "Nothing yet. When MEKA reminds you or sends a digest it shows here with why, as do the calendar edits you " +
            "make in MEKA; once it does things for you, each one shows here too and can be undone."

    /** Entry id for a notice: one entry however many devices posted it (the key is stable per notice). */
    fun noticeId(key: String): String = "n" + fnv64("notice:$key")

    /** Entry id for a digest: one per local day and digest, across devices. */
    fun digestId(day: Long, title: String): String = "g" + fnv64("digest:$day:${title.substringBefore(" ·")}")

    fun noticeSummary(n: Notice): Triple<String, String?, String> = Triple(
        "Reminded you: ${n.title}",
        n.text.takeIf { it.isNotBlank() },
        "${n.source.label} · ${n.tier.label}",
    )

    /** Entry id for a published app build: one per platform and build number, however often it is reported. */
    fun releaseId(platform: String, versionCode: Long): String = "r" + fnv64("release:$platform:$versionCode")

    /**
     * What a build published by [publisher] (e.g. "GitHub build") says in Activity: "GitHub build published build 412"
     * · "MEKA 0.1.412 · 24.3 MB · install it from Today on the Fold" · "Why: Hands-free phone updates · after a green
     * CI run on main".
     */
    fun releaseSummary(publisher: String, b: AppUpdateRules.Build): Triple<String, String?, String> = Triple(
        "$publisher published build ${b.versionCode}",
        "MEKA ${b.versionName} · ${AppUpdateRules.sizeLabel(b.sizeBytes)} · install it from Today on the Fold",
        "Hands-free phone updates · after a green CI run on main",
    )

    /**
     * The fields of that entry, as the server writes them into Meka's synced data when the build is complete (ADR-008
     * addendum): the same fields [ActivityLog] writes, so every device shows it like any other entry.
     */
    fun releaseFields(publisher: String, source: String, b: AppUpdateRules.Build, atMs: Long): Map<String, FieldValue> {
        val (summary, detail, why) = releaseSummary(publisher, b)
        return linkedMapOf(
            ActivityFields.AT to FieldValue.Int64(atMs),
            ActivityFields.KIND to FieldValue.Text(ActivityKind.PUBLISHED.name),
            ActivityFields.SUMMARY to FieldValue.Text(summary.take(MAX_LINE)),
            ActivityFields.DETAIL to FieldValue.Text(detail!!.take(MAX_LINE)),
            ActivityFields.WHY to FieldValue.Text(why.take(MAX_LINE)),
            ActivityFields.SOURCE to FieldValue.Text(source),
        )
    }

    // ---- Calendar edits (slice 2d) ----

    /** Row id for calendar edit [editId]: one per edit, on every device. */
    fun calendarEditId(editId: String): String = "c$editId"

    /**
     * The Activity row for calendar edit [e], or null for an edit undone in its five seconds (nothing was sent).
     * Summary: what happened ("Moved “Dentist” in Google", "Adding “Dentist” to Google", "Didn't delete “Standup” from
     * Google · This cancels it for 4 people", "Couldn't move “Dentist” in Google · Reconnect Google in Calendars",
     * "“Dentist” changed in Google meanwhile · open it to choose a version", "Kept Google's version of “Dentist”").
     * Detail: what it changed ("Fri 9 Oct · 14:00–15:00 → 16:00–17:00 · Place: High St → Elm Rd", "Was “Dentist”").
     * Why: Meka's own edit and the account that allows it.
     */
    fun calendarEditItem(e: EventEdit, nowMs: Long, cal: LocalCalendar): ActivityItem? {
        val state = e.state(nowMs)
        if (state == EventEditState.UNDONE) return null
        val p = CalendarEditRules.providerName(e.provider)
        val title = if (e.kind == EventEditKind.ADD || EventEditChange.TITLE in e.changes) e.draft?.title else e.base?.title
        val t = "“${title.orEmpty().trim().ifEmpty { "Event" }.take(60)}”"
        val verb = when {
            e.kind == EventEditKind.ADD -> "add"
            e.kind == EventEditKind.DELETE -> "delete"
            e.isMove -> "move"
            else -> "change"
        }
        val prep = when (e.kind) { EventEditKind.ADD -> "to"; EventEditKind.DELETE -> "from"; else -> "in" }
        val summary = when (state) {
            EventEditState.REFUSED -> "Didn't $verb $t $prep $p · ${e.detail ?: "$p didn't take the change"}"
            EventEditState.FAILED -> "Couldn't $verb $t $prep $p" + (e.detail?.let { " · $it" } ?: "")
            EventEditState.CLASH -> when (e.resolved) {
                null -> "$t changed in $p meanwhile · open it to choose a version"
                ClashChoice.THEIRS -> "Kept $p's version of $t"
                ClashChoice.MINE -> "Kept your version of $t · sent again"
            }
            else -> CalendarEditRules.line(e, nowMs)
        }
        val why = when {
            e.resends != null -> "Your choice after a clash"
            e.forTask != null && e.kind != EventEditKind.ADD -> "Keeps a task's block in step with the task"
            e.forTask != null -> "Plan my day · Apply, with “Also add the blocks” on"
            else -> "Your edit in MEKA"
        } + " · editing allowed for ${e.account}"
        return ActivityItem(
            id = calendarEditId(e.id),
            atMs = e.createdAtMs,
            kind = ActivityKind.CALENDAR,
            summary = summary.take(MAX_LINE),
            detail = calendarEditDetail(e, cal)?.take(MAX_LINE),
            why = why.take(MAX_LINE),
            source = "calendar_edit",
            level = null,
            changes = emptyList(),
            undoneAtMs = null,
            undoNote = null,
        )
    }

    /** What [e] changes, in words (null when there's nothing to add to the summary). */
    fun calendarEditDetail(e: EventEdit, cal: LocalCalendar): String? {
        fun whenOf(d: EventDraft) = EventDetails.whenLine(d.startAtMs, d.endAtMs, d.allDay, cal)
        fun short(s: String) = s.trim().let { if (it.length > 40) it.take(39).trimEnd() + "…" else it }
        val parts = mutableListOf<String>()
        when (e.kind) {
            EventEditKind.ADD -> e.draft?.let { d ->
                parts += whenOf(d)
                d.location?.takeIf { it.isNotBlank() }?.let { parts += short(it) }
            }
            EventEditKind.DELETE -> e.base?.let { b ->
                parts += whenOf(b)
                val g = e.guests ?: 0
                if (e.guestsOk && g > 0 && e.status == EventEditStatus.DONE) parts += if (g == 1) "its guest was told" else "its $g guests were told"
            }
            EventEditKind.CHANGE -> {
                val base = e.base
                val draft = e.draft
                if (base != null && draft != null) {
                    val after = CalendarEditRules.merged(base, draft, e.changes)
                    if (EventEditChange.TIME in e.changes) {
                        val a = whenOf(base)
                        val b = whenOf(after)
                        parts += if (" · " in a && " · " in b && a.substringBefore(" · ") == b.substringBefore(" · ")) "$a → ${b.substringAfter(" · ")}" else "$a → $b"
                    }
                    if (EventEditChange.TITLE in e.changes) parts += "Was “${short(base.title)}”"
                    if (EventEditChange.LOCATION in e.changes) {
                        val was = base.location?.takeIf { it.isNotBlank() }
                        val now = after.location?.takeIf { it.isNotBlank() }
                        parts += when {
                            now == null -> "Place removed"
                            was == null -> "Place: ${short(now)}"
                            else -> "Place: ${short(was)} → ${short(now)}"
                        }
                    }
                    if (EventEditChange.NOTES in e.changes) parts += "Notes changed"
                }
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** Longest summary, detail or why line kept in an entry. */
    const val MAX_LINE = 300

    fun digestSummary(d: Digest): Triple<String, String?, String> = Triple(
        "Sent a digest: ${d.title}",
        d.summary.takeIf { it.isNotBlank() },
        "Digests · set in Notifications",
    )

    // ---- Changes, encoded for undo ----

    /** One line per change: type, id, field, before, after, tab-separated; text escaped. */
    fun encodeChanges(changes: List<ActivityChange>): String = changes.joinToString("\n") { c ->
        listOf(esc(c.entityType), esc(c.entityId), esc(c.field), value(c.before), value(c.after)).joinToString("\t")
    }

    /** All or nothing: an entry whose changes can't all be read back can't be undone (empty list). */
    fun decodeChanges(text: String?): List<ActivityChange> {
        if (text.isNullOrEmpty()) return emptyList()
        val out = mutableListOf<ActivityChange>()
        for (line in text.split('\n')) {
            val p = line.split('\t')
            if (p.size != 5) return emptyList()
            val before = parseValue(p[3]) ?: return emptyList()
            val after = parseValue(p[4]) ?: return emptyList()
            val type = unesc(p[0]) ?: return emptyList()
            val id = unesc(p[1]) ?: return emptyList()
            val field = unesc(p[2]) ?: return emptyList()
            if (type.isEmpty() || id.isEmpty() || field.isEmpty()) return emptyList()
            out += ActivityChange(type, id, field, before, after)
        }
        return out
    }

    private fun value(v: FieldValue): String = when (v) {
        FieldValue.Null -> "-"
        is FieldValue.Text -> "t:" + esc(v.value)
        is FieldValue.Int64 -> "i:${v.value}"
        is FieldValue.Bool -> if (v.value) "b:1" else "b:0"
    }

    private fun parseValue(s: String): FieldValue? = when {
        s == "-" -> FieldValue.Null
        s.startsWith("t:") -> unesc(s.substring(2))?.let { FieldValue.Text(it) }
        s.startsWith("i:") -> s.substring(2).toLongOrNull()?.let { FieldValue.Int64(it) }
        s == "b:1" -> FieldValue.Bool(true)
        s == "b:0" -> FieldValue.Bool(false)
        else -> null
    }

    private fun esc(s: String): String = buildString {
        for (ch in s) when (ch) {
            '\\' -> append("\\\\")
            '\t' -> append("\\t")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(ch)
        }
    }

    private fun unesc(s: String): String? {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch != '\\') { sb.append(ch); i++; continue }
            if (i + 1 >= s.length) return null
            when (s[i + 1]) {
                '\\' -> sb.append('\\')
                't' -> sb.append('\t')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                else -> return null
            }
            i += 2
        }
        return sb.toString()
    }

    /** FNV-1a 64-bit, as 16 hex chars: a short, stable id from a key. */
    internal fun fnv64(s: String): String {
        var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
        for (b in s.encodeToByteArray()) {
            h = h xor (b.toLong() and 0xff)
            h *= 0x100000001b3L
        }
        return h.toULong().toString(16).padStart(16, '0')
    }

    // ---- The screen ----

    /** The last [DAYS_SHOWN] days, newest first, grouped by local day. */
    fun view(items: List<ActivityItem>, nowMs: Long, cal: LocalCalendar): ActivityView {
        val today = cal.epochDayOf(nowMs)
        val shown = items.filter { cal.epochDayOf(it.atMs) in (today - DAYS_SHOWN + 1)..today }
            .sortedWith(compareByDescending<ActivityItem> { it.atMs }.thenBy { it.id })
        val days = shown.groupBy { cal.epochDayOf(it.atMs) }.map { (day, rows) ->
            ActivityDay(day, SearchRules.dayLabel(day, today), rows.map { row(it, cal, today) })
        }
        val monday = today - (CivilDate.isoDayOfWeek(today) - 1)
        val week = shown.filter { cal.epochDayOf(it.atMs) >= monday }
        val parts = listOfNotNull(
            week.count { it.kind == ActivityKind.REMINDED }.takeIf { it > 0 }?.let { plural(it, "reminder") },
            week.count { it.kind == ActivityKind.DIGEST }.takeIf { it > 0 }?.let { plural(it, "digest") },
            week.count { it.kind == ActivityKind.CHANGED }.takeIf { it > 0 }?.let { plural(it, "change") },
            week.count { it.kind == ActivityKind.PUBLISHED }.takeIf { it > 0 }?.let { plural(it, "phone build") },
            week.count { it.kind == ActivityKind.CALENDAR }.takeIf { it > 0 }?.let { plural(it, "calendar edit") },
            week.count { it.kind == ActivityKind.SCREENED }.takeIf { it > 0 }?.let { plural(it, "call stopped", "calls stopped") },
        )
        val weekLine = if (parts.isEmpty()) "Nothing this week" else "This week: " + parts.joinToString(" · ")
        return ActivityView(if (shown.isEmpty()) "" else weekLine, days, EMPTY_LINE)
    }

    private fun row(i: ActivityItem, cal: LocalCalendar, today: Long): ActivityRow {
        val undone = i.undoneAtMs?.let { at ->
            val d = cal.epochDayOf(at)
            val whenText = LocalClock.formatMinute(cal.minuteOfDay(at)) + if (d != cal.epochDayOf(i.atMs)) " ${SearchRules.dayLabel(d, today).lowercaseIfRelative()}" else ""
            "Undone at $whenText" + (i.undoNote?.let { " · $it" } ?: "")
        }
        return ActivityRow(
            id = i.id,
            time = LocalClock.formatMinute(cal.minuteOfDay(i.atMs)),
            kind = i.kind,
            summary = i.summary,
            detail = i.detail,
            why = "Why: ${i.why}",
            canUndo = i.canUndo,
            undoneLine = undone,
        )
    }

    private fun String.lowercaseIfRelative() = if (this == "Today" || this == "Yesterday" || this == "Tomorrow") lowercase() else "on $this"
}

/**
 * The activity log on a replica. [act] is how any automatic action must change Meka's data: it records what each
 * field was, so [undo] can put it back.
 */
class ActivityLog(
    private val replica: Replica,
    private val newId: () -> String,
    private val nowMs: () -> Long,
    private val calendar: LocalCalendar = LocalCalendar.UTC,
) {
    /** Everything in the log: MEKA's own entries, then Meka's calendar edits (read from the edits themselves). */
    fun items(): List<ActivityItem> = stored() + calendarEdits()

    private fun calendarEdits(): List<ActivityItem> {
        val now = nowMs()
        return replica.entities(EntityTypes.EVENT_EDIT)
            .mapNotNull { s -> CalendarEditRules.from(s.ref.entityId) { s[it] } }
            .mapNotNull { ActivityRules.calendarEditItem(it, now, calendar) }
    }

    private fun stored(): List<ActivityItem> = replica.entities(EntityTypes.AGENT_ACTION).mapNotNull { s ->
        val kind = s[ActivityFields.KIND].textOrNull?.let { k -> ActivityKind.entries.firstOrNull { it.name == k } } ?: return@mapNotNull null
        ActivityItem(
            id = s.ref.entityId,
            atMs = s[ActivityFields.AT].longOrNull ?: return@mapNotNull null,
            kind = kind,
            summary = s[ActivityFields.SUMMARY].textOrNull ?: return@mapNotNull null,
            detail = s[ActivityFields.DETAIL].textOrNull,
            why = s[ActivityFields.WHY].textOrNull.orEmpty(),
            source = s[ActivityFields.SOURCE].textOrNull,
            level = s[ActivityFields.LEVEL].textOrNull,
            changes = if (kind == ActivityKind.CHANGED) ActivityRules.decodeChanges(s[ActivityFields.CHANGES].textOrNull) else emptyList(),
            undoneAtMs = s[ActivityFields.UNDONE_AT].longOrNull,
            undoNote = s[ActivityFields.UNDO_NOTE].textOrNull,
        )
    }

    fun view(): ActivityView = ActivityRules.view(items(), nowMs(), calendar)

    /** Notices this device just posted. A notice already in the log (posted on the other device too) isn't rewritten. */
    fun recordPosted(posted: List<Notice>) {
        for (n in posted) {
            if (!n.tier.interrupts) continue
            val id = ActivityRules.noticeId(n.key)
            if (replica.entity(EntityTypes.AGENT_ACTION, id) != null) continue
            val (summary, detail, why) = ActivityRules.noticeSummary(n)
            write(id, ActivityKind.REMINDED, summary, detail, why, "notice:${n.source.name}")
        }
    }

    /**
     * A call spam protection stopped on this device (the Fold's screening). [callId] is the number's key and the
     * time, so a retried screening doesn't log twice.
     */
    fun recordScreened(callKey: String, atMs: Long, summary: String, why: String) {
        val id = "s" + ActivityRules.fnv64("screened:$callKey:$atMs")
        if (replica.entity(EntityTypes.AGENT_ACTION, id) != null) return
        write(id, ActivityKind.SCREENED, summary, null, why, "calls")
    }

    /** A family sharing entry ([FamilyRules.joinedId] etc.) at [atMs]; written once, whichever device sees it first. */
    fun recordFamily(id: String, atMs: Long, summary: String, why: String) {
        if (replica.entity(EntityTypes.AGENT_ACTION, id) != null) return
        write(id, ActivityKind.FAMILY, summary, null, why, "family", atMs = atMs)
    }

    /** A digest this device just posted. */
    fun recordDigest(d: Digest) {
        val now = nowMs()
        val id = ActivityRules.digestId(calendar.epochDayOf(now), d.title)
        if (replica.entity(EntityTypes.AGENT_ACTION, id) != null) return
        val (summary, detail, why) = ActivityRules.digestSummary(d)
        write(id, ActivityKind.DIGEST, summary, detail, why, "digest")
    }

    /**
     * Makes [changes] to Meka's data and records them, with what each field was, so they can be undone. Every automatic
     * action goes through here (ADR-006 §3). [why] names the rule or setting; [level] the autonomy level it ran at.
     */
    fun act(summary: String, why: String, source: String, level: String?, changes: List<PlannedChange>, detail: String? = null): String {
        require(changes.isNotEmpty()) { "an action changes something" }
        // Something MEKA creates is undone by deleting it, not by blanking its fields.
        val created = changes.map { it.entityType to it.entityId }.distinct().filter { replica.entity(it.first, it.second) == null }
        val planned = changes.filter { it.field != DELETED_FIELD || (it.entityType to it.entityId) !in created } +
            created.map { PlannedChange(it.first, it.second, DELETED_FIELD, FieldValue.Bool(false)) }
        val recorded = planned.map { c ->
            val before = if ((c.entityType to c.entityId) in created && c.field == DELETED_FIELD) FieldValue.Bool(true)
            else current(c.entityType, c.entityId, c.field)
            ActivityChange(c.entityType, c.entityId, c.field, before, c.value)
        }
        val encoded = ActivityRules.encodeChanges(recorded)
        require(encoded.length <= Op.MAX_TEXT) { "too many changes for one entry" }
        for ((ref, cs) in planned.groupBy { it.entityType to it.entityId }) {
            replica.commitLocal(ref.first, ref.second, cs.associate { it.field to it.value })
        }
        val id = newId()
        write(id, ActivityKind.CHANGED, summary, detail, why, source, level, encoded)
        return id
    }

    /**
     * Puts back what entry [id] changed. A field you (or anything else) changed since, or that two devices disagree on,
     * is left alone. Undoing twice, or on both devices, changes nothing more.
     */
    fun undo(id: String): UndoOutcome {
        val item = items().firstOrNull { it.id == id } ?: return UndoOutcome.NOT_UNDOABLE
        if (item.changes.isEmpty()) return UndoOutcome.NOT_UNDOABLE
        if (item.undoneAtMs != null) return UndoOutcome.UNDONE
        var restored = 0
        for ((ref, cs) in item.changes.groupBy { it.entityType to it.entityId }) {
            val conflicted = replica.conflictsFor(ref.first, ref.second).map { it.key.field }.toSet()
            // Made by this action: undoing it deletes it (the other fields stay as they are).
            val made = cs.firstOrNull { it.field == DELETED_FIELD && it.before == FieldValue.Bool(true) }
            val candidates = if (made != null) listOf(made) else cs
            val back = candidates.filter { it.field !in conflicted && current(it.entityType, it.entityId, it.field) == it.after }
            if (back.isNotEmpty()) {
                replica.commitLocal(ref.first, ref.second, back.associate { it.field to it.before })
                restored += back.size
            }
        }
        val undoable = item.changes.groupBy { it.entityType to it.entityId }.values
            .sumOf { cs -> if (cs.any { it.field == DELETED_FIELD && it.before == FieldValue.Bool(true) }) 1 else cs.size }
        val outcome = when (restored) {
            undoable -> UndoOutcome.UNDONE
            0 -> UndoOutcome.CHANGED_SINCE
            else -> UndoOutcome.PARTLY
        }
        replica.commitLocal(
            EntityTypes.AGENT_ACTION, id,
            mapOf(
                ActivityFields.UNDONE_AT to nowMs().fv(),
                ActivityFields.UNDO_NOTE to (if (outcome == UndoOutcome.UNDONE) null else outcome.line).fv(),
            ),
        )
        return outcome
    }

    private fun current(type: String, id: String, field: String): FieldValue =
        replica.entity(type, id)?.get(field) ?: FieldValue.Null

    private fun write(
        id: String, kind: ActivityKind, summary: String, detail: String?, why: String, source: String,
        level: String? = null, changes: String? = null, atMs: Long = nowMs(),
    ) {
        replica.commitLocal(
            EntityTypes.AGENT_ACTION, id,
            buildMap {
                put(ActivityFields.AT, atMs.fv())
                put(ActivityFields.KIND, kind.name.fv())
                put(ActivityFields.SUMMARY, summary.take(ActivityRules.MAX_LINE).fv())
                detail?.let { put(ActivityFields.DETAIL, it.take(ActivityRules.MAX_LINE).fv()) }
                put(ActivityFields.WHY, why.take(ActivityRules.MAX_LINE).fv())
                put(ActivityFields.SOURCE, source.fv())
                level?.let { put(ActivityFields.LEVEL, it.fv()) }
                changes?.let { put(ActivityFields.CHANGES, it.fv()) }
            },
        )
    }
}
