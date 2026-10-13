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
    /**
     * What a reminder was about, for "Prevented misses" (ADR-013): `task:<id>` or `renewal:<id>:<cancel-by day>`
     * ([PreventedMissRules.aboutOf]); absent on other entries and on reminders logged before 2026-10-10.
     */
    const val ABOUT = "about"
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
    /**
     * A watch linked to MEKA or unlinked from it in Settings → Watch (Galaxy Watch, slice 1): "Linked Galaxy Watch".
     * Ids come from the watch's device id and the time, so both devices write one entry. Nothing to undo here (Unlink is
     * in Watch). Added 2026-10-10.
     */
    DEVICE,
    /**
     * Meka's answer to a request in the approvals inbox (approvals inbox, slice 1): "Approved: Buy 0.05 BTC" with who
     * asked and how he confirmed. One entry per request, whichever device answered. Nothing to undo: the one who asked
     * acts on the answer. Added 2026-10-11.
     */
    APPROVAL,
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
    /** [ActivityFields.ABOUT]: what a reminder was about; null otherwise. */
    val about: String? = null,
    /**
     * Inbox autopilot, slice 3b-2: a tidy MEKA's server did in his mailbox (a `mail_tidy` entity, [ActivityRules.mailTidyItem]).
     * Its Undo is asked here ([undoAskedAtMs]) and done by the server at its next check, which writes [undoneAtMs].
     */
    val mailTidy: Boolean = false,
    val undoAskedAtMs: Long? = null,
) {
    val canUndo: Boolean get() = undoneAtMs == null && (changes.isNotEmpty() || (mailTidy && undoAskedAtMs == null))
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

/**
 * Activity's filter (activity log, slice 3): the screen shows everything, or one kind of thing. Purely a way of looking;
 * nothing is stored or synced (each device remembers its own choice while the screen is open).
 */
enum class ActivityFilter(val label: String, val kinds: Set<ActivityKind>) {
    ALL("All", ActivityKind.entries.toSet()),
    REMINDERS("Reminders", setOf(ActivityKind.REMINDED, ActivityKind.DIGEST)),
    DONE_FOR_YOU("Done for you", setOf(ActivityKind.CHANGED)),
    CALENDAR("Calendar", setOf(ActivityKind.CALENDAR)),
    CALLS("Calls", setOf(ActivityKind.SCREENED, ActivityKind.CALL)),
    OTHER("Other", setOf(ActivityKind.PUBLISHED, ActivityKind.FAMILY, ActivityKind.DEVICE, ActivityKind.APPROVAL)),
}

/** One filter chip: "Calls · 3" (All carries no count). */
data class ActivityFilterChip(val filter: ActivityFilter, val label: String, val count: Int, val spoken: String)

/** What an undo did. */
enum class UndoOutcome(val line: String) {
    UNDONE("Undone"),
    PARTLY("Partly undone: you've changed some of it since"),
    CHANGED_SINCE("Nothing to undo: you've changed it since"),
    NOT_UNDOABLE("This can't be undone"),
    /** Inbox autopilot (slice 3b-2): asked; the server puts the emails back at its next check. */
    ASKED("Putting them back in the inbox at the next check"),
}

/** Pure rules, unit-tested without a replica. */
object ActivityRules {
    const val DAYS_SHOWN = 30
    const val EMPTY_LINE =
        "Nothing yet. When Navilife reminds you or sends a digest it shows here with why, as do the calendar edits you " +
            "make in Navilife; once it does things for you, each one shows here too and can be undone."

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
        "Navilife ${b.versionName} · ${AppUpdateRules.sizeLabel(b.sizeBytes)} · install it from Today on the Fold",
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

    // ---- Inbox autopilot (slice 3b-2) ----

    const val MAIL_TIDY_WHY = "Inbox autopilot · newsletters, receipts, notifications and promos · switch it in Mail"
    const val MAIL_TIDY_SOURCE = "mail_autopilot"

    /** A tidy as an Activity row ("Done for you"): its Undo puts the emails back in the inbox. */
    fun mailTidyItem(b: MailTidyBatch, nowMs: Long): ActivityItem = ActivityItem(
        id = b.id, atMs = b.atMs, kind = ActivityKind.CHANGED,
        summary = b.summary, detail = b.detail, why = MAIL_TIDY_WHY, source = MAIL_TIDY_SOURCE, level = null,
        changes = emptyList(), undoneAtMs = b.undoneAtMs, undoNote = b.undoNote,
        mailTidy = true,
        // Past the 30 days the server no longer puts back: shown as asked so no Undo is offered.
        undoAskedAtMs = b.undoAskedAtMs ?: (if (nowMs - b.atMs > MailAutopilotRules.UNDO_DAYS * 86_400_000L) b.atMs else null),
    )

    // ---- Inbox autopilot: Unsubscribe (slice 5a) ----

    const val UNSUBSCRIBE_WHY = "You tapped Unsubscribe in Mail · Navilife did it from that account"
    const val UNSUBSCRIBE_SOURCE = "mail_unsubscribe"

    /** An Unsubscribe the server tried, as an Activity row (nothing to undo); null before it was tried. */
    fun unsubscribeItem(s: MailSender): ActivityItem? {
        val at = s.triedAtMs ?: return null
        val state = s.state ?: return null
        val summary = when {
            state.finished -> "Unsubscribed from ${s.name} · ${s.account}"
            state == UnsubscribeState.OPEN_LINK -> "${s.name} · ${s.account} · open its page to unsubscribe"
            else -> "Couldn't unsubscribe from ${s.name} · ${s.account}"
        }
        return ActivityItem(
            id = "u" + s.id, atMs = at, kind = ActivityKind.CHANGED,
            summary = summary, detail = s.note ?: state.note, why = UNSUBSCRIBE_WHY, source = UNSUBSCRIBE_SOURCE, level = null,
            changes = emptyList(), undoneAtMs = null, undoNote = null,
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
            else -> "Your edit in Navilife"
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

    // ---- Filtering (slice 3) ----

    /**
     * The chips over Activity's days: All, then each kind of thing present in the last 30 days, in a fixed order, with
     * how many ("Calls · 3"). Empty when only one kind is there (a filter would show the same list).
     */
    fun filters(view: ActivityView): List<ActivityFilterChip> {
        val kinds = view.days.flatMap { d -> d.rows.map { it.kind } }
        val present = ActivityFilter.entries.filter { it != ActivityFilter.ALL }.mapNotNull { f ->
            val n = kinds.count { it in f.kinds }
            if (n == 0) null else ActivityFilterChip(f, "${f.label} · $n", n, "${f.label}, $n")
        }
        if (present.size < 2) return emptyList()
        val all = ActivityFilterChip(ActivityFilter.ALL, ActivityFilter.ALL.label, kinds.size, "All, ${kinds.size}")
        return listOf(all) + present
    }

    /** [chosen] while it still has a chip, else All (the last call of that kind aged out, or a filter no longer applies). */
    fun chosen(view: ActivityView, chosen: ActivityFilter): ActivityFilter =
        if (filters(view).any { it.filter == chosen }) chosen else ActivityFilter.ALL

    /** Looks up a filter by its name (as a device remembers it); All for anything unknown. */
    fun filterNamed(name: String?): ActivityFilter = ActivityFilter.entries.firstOrNull { it.name == name } ?: ActivityFilter.ALL

    /**
     * [view] showing only [filter]'s kinds: days with none of them drop out; the week line stays the whole week's. An
     * unavailable filter shows everything (see [chosen]).
     */
    fun filtered(view: ActivityView, filter: ActivityFilter): ActivityView {
        val f = chosen(view, filter)
        if (f == ActivityFilter.ALL) return view
        val days = view.days.mapNotNull { d ->
            val rows = d.rows.filter { it.kind in f.kinds }
            if (rows.isEmpty()) null else d.copy(rows = rows)
        }
        return view.copy(days = days)
    }

    private fun row(i: ActivityItem, cal: LocalCalendar, today: Long): ActivityRow {
        val undone = i.undoneAtMs?.let { at ->
            val d = cal.epochDayOf(at)
            val whenText = LocalClock.formatMinute(cal.minuteOfDay(at)) + if (d != cal.epochDayOf(i.atMs)) " ${SearchRules.dayLabel(d, today).lowercaseIfRelative()}" else ""
            "Undone at $whenText" + (i.undoNote?.let { " · $it" } ?: "")
        } ?: (if (i.mailTidy && i.undoAskedAtMs != null && i.undoAskedAtMs != i.atMs) MailAutopilotPanelRules.PUTTING_BACK else null)
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
    fun items(): List<ActivityItem> = stored() + calendarEdits() + mailTidies() + unsubscribes()

    /** Inbox autopilot (slice 5a): each Unsubscribe the server tried, read from the synced `mail_sender` entity. */
    private fun unsubscribes(): List<ActivityItem> = mailSenders().mapNotNull { ActivityRules.unsubscribeItem(it) }

    /** The newsletter and promo senders as Mail reads them. */
    fun mailSenders(): List<MailSender> = replica.entities(EntityTypes.MAIL_SENDER)
        .mapNotNull { s -> MailUnsubscribeRules.read(s.ref.entityId, s.fields) }

    /** Inbox autopilot (slice 6a): the emails the server published as needing Meka, read from the synced `mail_item` entity. */
    fun mailItems(): List<MailItem> = replica.entities(EntityTypes.MAIL_ITEM)
        .mapNotNull { i -> MailNeedsRules.read(i.ref.entityId, i.fields) }

    /**
     * Unsubscribe on one sender (Mail): asks the server to do it at its next check. The line Mail shows: asked, already
     * done/asked, or unknown.
     */
    fun askUnsubscribe(id: String): String {
        val s = mailSenders().firstOrNull { it.id == id } ?: return MailSenderPanelRules.UNKNOWN
        val ask = MailSenderPanelRules.ask(s, nowMs()) ?: return if (MailUnsubscribeRules.due(s)) MailSenderPanelRules.ASKED_LINE else MailSenderPanelRules.ALREADY
        replica.commitLocal(EntityTypes.MAIL_SENDER, id, ask)
        return MailSenderPanelRules.ASKED_LINE
    }

    /** Inbox autopilot (slice 3b-2): each tidy the server did, read from the synced `mail_tidy` entity itself. */
    private fun mailTidies(): List<ActivityItem> {
        val now = nowMs()
        return replica.entities(EntityTypes.MAIL_TIDY)
            .mapNotNull { s -> MailAutopilotRules.readBatch(s.ref.entityId, s.fields) }
            .map { ActivityRules.mailTidyItem(it, now) }
    }

    /** The tidies as Mail reads them. */
    fun mailTidyBatches(): List<MailTidyBatch> = replica.entities(EntityTypes.MAIL_TIDY)
        .mapNotNull { s -> MailAutopilotRules.readBatch(s.ref.entityId, s.fields) }

    /** Slice 8a: each account's backlog clean-up plan, as the server published it. */
    fun mailBacklogs(): List<MailBacklog> = replica.entities(EntityTypes.MAIL_BACKLOG)
        .mapNotNull { s -> MailBacklogRules.read(s.ref.entityId, s.fields) }

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
            about = s[ActivityFields.ABOUT].textOrNull,
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
            write(id, ActivityKind.REMINDED, summary, detail, why, "notice:${n.source.name}", about = PreventedMissRules.aboutOf(n.key))
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

    /** A watch linked or unlinked ([WatchLinkRules.linkedId] etc.) at [atMs]; written once, whichever device sees it first. */
    fun recordDevice(id: String, atMs: Long, summary: String, why: String) {
        if (replica.entity(EntityTypes.AGENT_ACTION, id) != null) return
        write(id, ActivityKind.DEVICE, summary, null, why, "devices", atMs = atMs)
    }

    /** Meka's answer to an approval ([ApprovalRules.activityId]); written once, by the device that answered. */
    fun recordApproval(id: String, summary: String, detail: String?, why: String) {
        if (replica.entity(EntityTypes.AGENT_ACTION, id) != null) return
        write(id, ActivityKind.APPROVAL, summary, detail, why, "approvals")
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
     * Earned trust: runs [block] (the same code a tap runs) as an automatic action and records every field it changed,
     * with what each was, so [undo] can put it back exactly as [act] would (an entity it made is deleted on undo).
     * Returns what [block] returned and the entry's id; null when the block changed nothing (no entry is written).
     */
    fun <T> actRecorded(summary: String, why: String, source: String, level: String?, detail: String? = null, block: () -> T): Pair<T, String?> {
        val (result, recorded) = replica.recording(block)
        if (recorded.isEmpty()) return result to null
        val created = recorded.filter { it.created }.map { it.entityType to it.entityId }.distinct()
        // A made entity is undone by deleting it: it carries an explicit "not deleted" so undo can tell it is as MEKA left it.
        for (ref in created) {
            if (current(ref.first, ref.second, DELETED_FIELD) != FieldValue.Bool(false)) {
                replica.commitLocal(ref.first, ref.second, mapOf(DELETED_FIELD to FieldValue.Bool(false)))
            }
        }
        val changes = recorded.groupBy { Triple(it.entityType, it.entityId, it.field) }.mapNotNull { (k, cs) ->
            if ((k.first to k.second) in created) null
            else ActivityChange(k.first, k.second, k.third, cs.first().before, cs.last().after).takeIf { it.before != it.after }
        } + created.map { ActivityChange(it.first, it.second, DELETED_FIELD, FieldValue.Bool(true), FieldValue.Bool(false)) }
        if (changes.isEmpty()) return result to null
        val encoded = ActivityRules.encodeChanges(changes)
        require(encoded.length <= Op.MAX_TEXT) { "too many changes for one entry" }
        val id = newId()
        write(id, ActivityKind.CHANGED, summary, detail, why, source, level, encoded)
        return result to id
    }

    /**
     * Puts back what entry [id] changed. A field you (or anything else) changed since, or that two devices disagree on,
     * is left alone. Undoing twice, or on both devices, changes nothing more.
     */
    fun undo(id: String): UndoOutcome {
        val item = items().firstOrNull { it.id == id } ?: return UndoOutcome.NOT_UNDOABLE
        if (item.mailTidy) {
            if (item.undoneAtMs != null) return UndoOutcome.UNDONE
            if (item.undoAskedAtMs != null) return if (item.undoAskedAtMs == item.atMs) UndoOutcome.NOT_UNDOABLE else UndoOutcome.ASKED
            replica.commitLocal(EntityTypes.MAIL_TIDY, id, mapOf(MailTidyBatchFields.UNDO_ASKED_AT to nowMs().fv()))
            return UndoOutcome.ASKED
        }
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
        level: String? = null, changes: String? = null, atMs: Long = nowMs(), about: String? = null,
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
                about?.let { put(ActivityFields.ABOUT, it.fv()) }
            },
        )
    }
}
