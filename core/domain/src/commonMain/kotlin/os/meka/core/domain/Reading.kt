package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Reading and learning (V1, Meka approved 2026-10-12), slice 1: the books, podcasts and courses he wants to get to, on
 * Ask → Daily → **Reading** on both apps. One line adds one ("Atomic Habits by James Clear, 320 pages", "podcast The
 * Rest Is History", "course Kotlin coroutines 12 lessons", or a shared link); each sits under UP NEXT until he starts it
 * or types how far he's got ("120", "p 120 of 320", "45%", "episode 4", "lesson 3"), then under ON THE GO with its
 * progress and a bar; **Finished** and **Put down** move it on, **Remove** takes it off, each with Undo. The pane's
 * month line ("October · finished 1 · next up: …") shows only when something moved this month.
 *
 * One synced entity type (ADR-008 addendum 2026-10-18): `reading_item`, a random id, every field LWW (Remove is a field,
 * not a tombstone, so Undo can put it back). Non-AI, pure rules ([ReadingRules]) over a thin store ([Reading]); nothing
 * is fetched. Later slices: the train's commute slots, a reading goal in Goals, Ask and Talk.
 */
object ReadingFields {
    const val TITLE = "title"
    /** The author, host or teacher ("James Clear"); "" when not given. */
    const val BY = "by"
    /** [ReadingKind.key]. */
    const val KIND = "kind"
    /** A link it was shared with; "" when typed. */
    const val URL = "url"
    /** [ReadingUnit.key]: what [AT] and [TOTAL] count. */
    const val UNIT = "unit"
    const val AT = "at"
    /** 0 when unknown. */
    const val TOTAL = "total"
    /** [ReadingState.key]. */
    const val STATE = "state"
    const val ADDED_AT = "addedAtMs"
    const val STARTED_AT = "startedAtMs"
    /** When it was last moved on (progress typed, started, finished or put down). */
    const val MOVED_AT = "movedAtMs"
    /** When it was finished or put down; 0 otherwise. */
    const val ENDED_AT = "endedAtMs"
    const val REMOVED = "removed"
}

enum class ReadingKind(val key: String, val label: String) {
    BOOK("book", "Book"),
    PODCAST("podcast", "Podcast"),
    COURSE("course", "Course"),
    ;

    companion object { fun of(key: String?): ReadingKind = entries.firstOrNull { it.key == key } ?: BOOK }
}

enum class ReadingUnit(val key: String, val one: String) {
    PAGE("page", "page"),
    PERCENT("percent", "%"),
    EPISODE("episode", "episode"),
    LESSON("lesson", "lesson"),
    CHAPTER("chapter", "chapter"),
    ;

    companion object {
        fun of(key: String?): ReadingUnit? = entries.firstOrNull { it.key == key }

        /** The unit a kind counts in when nothing says otherwise. */
        fun default(kind: ReadingKind): ReadingUnit = when (kind) {
            ReadingKind.BOOK -> PAGE
            ReadingKind.PODCAST -> EPISODE
            ReadingKind.COURSE -> LESSON
        }
    }
}

enum class ReadingState(val key: String) {
    /** Up next: wants to get to it. */
    WANT("want"),
    READING("reading"),
    FINISHED("finished"),
    /** Put down (abandoned). */
    PUT_DOWN("put_down"),
    ;

    companion object { fun of(key: String?): ReadingState = entries.firstOrNull { it.key == key } ?: WANT }
}

/** One book, podcast or course as stored. */
data class ReadingItem(
    val id: String,
    val title: String,
    val by: String,
    val kind: ReadingKind,
    val url: String,
    val unit: ReadingUnit,
    val at: Long,
    val total: Long,
    val state: ReadingState,
    val addedAtMs: Long,
    val startedAtMs: Long,
    val movedAtMs: Long,
    val endedAtMs: Long,
    val removed: Boolean,
)

/** One row as the pane shows it. */
data class ReadingRow(
    val id: String,
    val title: String,
    /** "Book · James Clear" */
    val meta: String,
    /** "Page 120 of 320 · 38%", "Episode 4", "Not started", "Finished Sat 17 Oct". */
    val progress: String,
    /** 0..1 for the bar; null when there's nothing to measure against. */
    val fraction: Float?,
    val state: String,
    val url: String,
    val spoken: String,
)

data class ReadingView(
    val onTheGo: List<ReadingRow>,
    val upNext: List<ReadingRow>,
    /** Finished in the last [ReadingRules.FINISHED_DAYS] days, newest first. */
    val finished: List<ReadingRow>,
    /** Put down in the last [ReadingRules.FINISHED_DAYS] days, newest first. */
    val putDown: List<ReadingRow>,
    /** "2 on the go · 3 up next · finished 4 this year", or how to start. */
    val summary: String,
    /** "October · finished 1 · 2 moved on · next up: Atomic Habits"; null when nothing moved this month. */
    val month: String?,
) {
    val isEmpty: Boolean get() = onTheGo.isEmpty() && upNext.isEmpty() && finished.isEmpty() && putDown.isEmpty()

    companion object {
        val EMPTY = ReadingView(emptyList(), emptyList(), emptyList(), emptyList(), ReadingRules.EMPTY_SUMMARY, null)
    }
}

/** What a command did: the line under the field (lit when [refused]), and [undo] when Undo can take it back. */
data class ReadingSaid(val line: String, val refused: Boolean, val undo: String? = null)

/** What typing a line about progress means. */
data class ReadingProgress(val at: Long?, val total: Long?, val unit: ReadingUnit?, val finished: Boolean)

/** What one added line holds. */
data class ReadingParsed(val title: String, val by: String, val kind: ReadingKind, val unit: ReadingUnit, val total: Long, val url: String, val started: Boolean)

object ReadingRules {
    const val TITLE = "Reading"
    const val ROW_LINE = "Books, podcasts and courses you want to get to"
    const val HINT = "Add a book, podcast or course…"
    const val PROGRESS_HINT = "How far? 120 · 45% · episode 4"
    const val EMPTY_SUMMARY = "Nothing yet. Type a book (\"Atomic Habits by James Clear, 320 pages\"), a podcast or a course."
    const val NO_TITLE = "Type its name, e.g. \"Atomic Habits by James Clear\" or \"podcast The Rest Is History\"."
    const val NOT_PROGRESS = "Type how far you've got: a page (120), a percent (45%), \"episode 4\" or \"lesson 3\"."
    const val GONE = "That isn't on your reading list any more"
    const val NOTE = "Kept in your own data on both apps. Nothing is looked up or sent anywhere."
    const val MAX_TITLE = 120
    const val FINISHED_DAYS = 90
    const val FINISHED_SHOWN = 10
    private const val DAY_MS = 86_400_000L

    private val COURSE_HOSTS = listOf(
        "udemy.com", "coursera.org", "futurelearn.com", "edx.org", "skillshare.com", "pluralsight.com", "khanacademy.org",
        "open.edu", "codecademy.com", "masterclass.com", "domestika.org", "frontendmasters.com",
    )

    private val KIND_WORD = Regex("""^(?:(book|audiobook|podcast|course|class|lesson)s?\s*[:\-–]?\s+)""", RegexOption.IGNORE_CASE)
    private val KIND_TAIL = Regex("""\s*[(\[]\s*(book|audiobook|podcast|course)\s*[)\]]\s*$""", RegexOption.IGNORE_CASE)
    private val STARTED = Regex("""^(?:i'?m\s+|i\s+am\s+|currently\s+|now\s+)?(?:reading|listening\s+to|doing|taking|started|starting)\s+""", RegexOption.IGNORE_CASE)
    private val WANT = Regex("""^(?:i\s+)?(?:want\s+to\s+(?:read|listen\s+to|do|take)|to\s+read|read)\s+""", RegexOption.IGNORE_CASE)
    private val COUNT = Regex("""[,;·\-–(]?\s*\b(\d{1,5})\s*(pages?|pp|p|episodes?|eps?|lessons?|modules?|chapters?|ch)\b\.?\)?\s*$""", RegexOption.IGNORE_CASE)
    private val BY = Regex(""",?\s+by\s+(.+)$""", RegexOption.IGNORE_CASE)

    private fun tidy(s: String): String = s.replace(Regex("""\s+"""), " ").trim(' ', ',', ';', '·', '-', '–', '—', ':', '"', '“', '”')

    /** The same name however typed: lower case, letters and digits only. */
    fun fold(title: String): String = title.lowercase().filter { it.isLetterOrDigit() }

    private fun unitOf(word: String): ReadingUnit = when (word.lowercase().trimEnd('s')) {
        "page", "pp", "p" -> ReadingUnit.PAGE
        "episode", "ep" -> ReadingUnit.EPISODE
        "lesson", "module" -> ReadingUnit.LESSON
        else -> ReadingUnit.CHAPTER
    }

    /** What one typed or shared line adds; null when it names nothing. */
    fun parse(text: String?, subject: String? = null): ReadingParsed? {
        val raw = text?.trim().orEmpty()
        val url = QueueRules.link(raw) ?: QueueRules.link(subject)
        var t = tidy((if (url != null) raw.replace(url, " ") else raw).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty())
        var kind: ReadingKind? = null
        var started = false
        STARTED.find(t)?.let { started = true; t = t.substring(it.range.last + 1) }
        WANT.find(t)?.let { t = t.substring(it.range.last + 1) }
        fun takeKind(word: String) = when (word.lowercase()) {
            "podcast" -> ReadingKind.PODCAST
            "course", "class", "lesson" -> ReadingKind.COURSE
            else -> ReadingKind.BOOK
        }
        KIND_WORD.find(t)?.let { kind = takeKind(it.groupValues[1]); t = t.substring(it.range.last + 1) }
        KIND_TAIL.find(t)?.let { kind = kind ?: takeKind(it.groupValues[1]); t = t.substring(0, it.range.first) }
        var unit: ReadingUnit? = null
        var total = 0L
        COUNT.find(t)?.let { m ->
            unit = unitOf(m.groupValues[2]); total = m.groupValues[1].toLong(); t = t.substring(0, m.range.first)
        }
        t = tidy(t)
        var by = ""
        BY.find(t)?.let { m ->
            val who = tidy(m.groupValues[1])
            // "Dune by Frank Herbert" names an author; "Stand by Me" doesn't (an author takes two words, or a comma).
            val named = who.contains(' ') || m.value.startsWith(",")
            if (who.isNotEmpty() && m.range.first > 0 && named) { by = who.take(60); t = tidy(t.substring(0, m.range.first)) }
        }
        if (t.length < 2 && url != null) t = QueueRules.title(null, subject, url)
        if (t.length < 2 || fold(t) in setOf("book", "books", "audiobook", "podcast", "podcasts", "course", "courses", "class")) return null
        if (t.length > MAX_TITLE) t = t.take(MAX_TITLE).substringBeforeLast(' ', t.take(MAX_TITLE)) + "…"
        val k = kind ?: when {
            unit == ReadingUnit.EPISODE -> ReadingKind.PODCAST
            unit == ReadingUnit.LESSON -> ReadingKind.COURSE
            url != null && QueueRules.kind(url, t) == QueueKind.PODCAST -> ReadingKind.PODCAST
            url != null && QueueRules.host(url).let { h -> COURSE_HOSTS.any { h == it || h.endsWith(".$it") } || h == "linkedin.com" && url.contains("/learning") } -> ReadingKind.COURSE
            else -> ReadingKind.BOOK
        }
        return ReadingParsed(t.replaceFirstChar { it.titlecase() }, by, k, unit ?: ReadingUnit.default(k), total, url.orEmpty(), started)
    }

    private val PROGRESS = Regex(
        """^(?:(?:i'?m\s+)?(?:on|at|up\s+to|read\s+to|done)\s+)?(?:(page|p|pg|episode|ep|lesson|module|chapter|ch)\.?\s*)?(\d{1,5})\s*(%|percent|pages?)?\s*(?:(?:of|/|out\s+of)\s*(\d{1,5}))?\s*(pages?|episodes?|lessons?|chapters?)?$""",
        RegexOption.IGNORE_CASE,
    )

    /** What a typed progress line means; null when it isn't one. */
    fun progress(text: String?): ReadingProgress? {
        val t = text?.trim()?.trimEnd('.')?.lowercase().orEmpty()
        if (t.isEmpty()) return null
        if (t in setOf("done", "finished", "finished it", "read it", "all done", "the end")) return ReadingProgress(null, null, null, true)
        val m = PROGRESS.find(t) ?: return null
        val word = m.groupValues[1]
        val at = m.groupValues[2].toLong()
        val pct = m.groupValues[3].let { it == "%" || it == "percent" }
        val total = m.groupValues[4].toLongOrNull()
        val unit = when {
            pct -> ReadingUnit.PERCENT
            word.isNotEmpty() -> when (word) {
                "page", "p", "pg" -> ReadingUnit.PAGE
                "episode", "ep" -> ReadingUnit.EPISODE
                "lesson", "module" -> ReadingUnit.LESSON
                else -> ReadingUnit.CHAPTER
            }
            m.groupValues[3].startsWith("page") || m.groupValues[5].startsWith("page") -> ReadingUnit.PAGE
            m.groupValues[5].isNotEmpty() -> unitOf(m.groupValues[5])
            else -> null
        }
        if (pct && at > 100) return null
        if (total != null && (total <= 0 || at > total)) return null
        return ReadingProgress(at, if (pct) 100 else total, unit, false)
    }

    private fun Map<String, FieldValue>.f(field: String): FieldValue = this[field] ?: FieldValue.Null

    fun items(entities: Map<String, Map<String, FieldValue>>): List<ReadingItem> = entities.mapNotNull { (id, s) ->
        val title = s.f(ReadingFields.TITLE).textOrNull?.trim().orEmpty()
        if (title.isEmpty()) return@mapNotNull null
        val kind = ReadingKind.of(s.f(ReadingFields.KIND).textOrNull)
        ReadingItem(
            id = id, title = title, by = s.f(ReadingFields.BY).textOrNull?.trim().orEmpty(), kind = kind,
            url = s.f(ReadingFields.URL).textOrNull.orEmpty(),
            unit = ReadingUnit.of(s.f(ReadingFields.UNIT).textOrNull) ?: ReadingUnit.default(kind),
            at = s.f(ReadingFields.AT).longOrNull ?: 0L, total = s.f(ReadingFields.TOTAL).longOrNull ?: 0L,
            state = ReadingState.of(s.f(ReadingFields.STATE).textOrNull),
            addedAtMs = s.f(ReadingFields.ADDED_AT).longOrNull ?: 0L, startedAtMs = s.f(ReadingFields.STARTED_AT).longOrNull ?: 0L,
            movedAtMs = s.f(ReadingFields.MOVED_AT).longOrNull ?: 0L, endedAtMs = s.f(ReadingFields.ENDED_AT).longOrNull ?: 0L,
            removed = s.f(ReadingFields.REMOVED).boolOrNull == true,
        )
    }.sortedWith(compareBy<ReadingItem> { it.addedAtMs }.thenBy { it.id })

    /** 0..1 against the total (percent counts out of 100); null without one. */
    fun fraction(i: ReadingItem): Float? = when {
        i.state == ReadingState.FINISHED -> 1f
        i.total > 0 -> (i.at.toFloat() / i.total).coerceIn(0f, 1f)
        else -> null
    }

    /** "Page 120 of 320 · 38%", "45%", "Episode 4 of 12", "Lesson 3". */
    fun where(i: ReadingItem): String? {
        if (i.at <= 0) return null
        if (i.unit == ReadingUnit.PERCENT) return "${i.at}%"
        val name = i.unit.one.replaceFirstChar { it.titlecase() }
        if (i.total <= 0) return "$name ${i.at}"
        val pct = (i.at * 100 / i.total).toInt()
        return "$name ${i.at} of ${i.total} · $pct%"
    }

    fun row(i: ReadingItem, cal: LocalCalendar): ReadingRow {
        fun day(ms: Long) = CivilDate.shortLabel(cal.epochDayOf(ms))
        val size = if (i.total > 0 && i.unit != ReadingUnit.PERCENT && i.state == ReadingState.WANT) " · ${ShutdownRules.count(i.total.toInt(), i.unit.one)}" else ""
        val meta = i.kind.label + (if (i.by.isNotEmpty()) " · ${i.by}" else "") + size
        val progress = when (i.state) {
            ReadingState.WANT -> "Not started · added ${day(i.addedAtMs)}"
            ReadingState.READING -> where(i) ?: "Started ${day(i.startedAtMs.takeIf { it > 0 } ?: i.movedAtMs)}"
            ReadingState.FINISHED -> "Finished ${day(i.endedAtMs)}"
            ReadingState.PUT_DOWN -> "Put down ${day(i.endedAtMs)}" + (where(i)?.let { " · at ${it.replaceFirstChar { c -> c.lowercase() }}" } ?: "")
        }
        val spoken = "${i.kind.label}: ${i.title}" + (if (i.by.isNotEmpty()) " by ${i.by}" else "") + ", " + progress
        return ReadingRow(i.id, i.title, meta, progress, if (i.state == ReadingState.READING) fraction(i) else null, i.state.key, i.url, spoken)
    }

    fun view(items: List<ReadingItem>, nowMs: Long, cal: LocalCalendar): ReadingView {
        val live = items.filter { !it.removed }
        if (live.isEmpty()) return ReadingView.EMPTY
        val recent = { i: ReadingItem -> nowMs - i.endedAtMs < FINISHED_DAYS * DAY_MS }
        val onTheGo = live.filter { it.state == ReadingState.READING }.sortedByDescending { it.movedAtMs }
        val upNext = live.filter { it.state == ReadingState.WANT }
        val finished = live.filter { it.state == ReadingState.FINISHED && recent(it) }.sortedByDescending { it.endedAtMs }.take(FINISHED_SHOWN)
        val putDown = live.filter { it.state == ReadingState.PUT_DOWN && recent(it) }.sortedByDescending { it.endedAtMs }.take(FINISHED_SHOWN)
        val today = cal.epochDayOf(nowMs)
        val year = CivilDate.fromEpochDay(today).year
        val thisYear = live.count { it.state == ReadingState.FINISHED && it.endedAtMs > 0 && CivilDate.fromEpochDay(cal.epochDayOf(it.endedAtMs)).year == year }
        val summary = listOfNotNull(
            onTheGo.size.takeIf { it > 0 }?.let { "$it on the go" },
            upNext.size.takeIf { it > 0 }?.let { "$it up next" },
            thisYear.takeIf { it > 0 }?.let { "finished $it this year" },
        ).joinToString(" · ").replaceFirstChar { it.titlecase() }.ifEmpty { "Nothing on the go. Add the next one." }
        return ReadingView(
            onTheGo.map { row(it, cal) }, upNext.map { row(it, cal) }, finished.map { row(it, cal) }, putDown.map { row(it, cal) },
            summary, month(live, nowMs, cal),
        )
    }

    /**
     * The month line: "October · finished 1 · 2 moved on · next up: Atomic Habits". What finished this month, what
     * else moved on (started or progress typed), then the first up next; null when nothing moved this month.
     */
    fun month(items: List<ReadingItem>, nowMs: Long, cal: LocalCalendar): String? {
        val ymd = CivilDate.fromEpochDay(cal.epochDayOf(nowMs))
        val y = ymd.year
        val mo = ymd.month
        fun inMonth(ms: Long) = ms > 0 && CivilDate.fromEpochDay(cal.epochDayOf(ms)).let { it.year == y && it.month == mo }
        val live = items.filter { !it.removed }
        val finished = live.count { it.state == ReadingState.FINISHED && inMonth(it.endedAtMs) }
        val moved = live.count { it.state == ReadingState.READING && inMonth(it.movedAtMs) }
        if (finished == 0 && moved == 0) return null
        val next = live.firstOrNull { it.state == ReadingState.WANT }?.title
        return listOfNotNull(
            CivilDate.MONTH_LONG[mo - 1],
            finished.takeIf { it > 0 }?.let { "finished $it" },
            moved.takeIf { it > 0 }?.let { "$it moved on" },
            next?.let { "next up: $it" },
        ).joinToString(" · ")
    }

    /** Ask → Daily's row: the newest on the go with how far ("Atomic Habits · 38%"), else "3 up next"; null when empty. */
    fun dailyLine(items: List<ReadingItem>): DailyLine? {
        val live = items.filter { !it.removed }
        val on = live.filter { it.state == ReadingState.READING }.sortedByDescending { it.movedAtMs }
        on.firstOrNull()?.let { i ->
            val how = where(i)?.substringAfterLast(" · ") ?: "on the go"
            return DailyLine("${i.title} · $how" + if (on.size > 1) " · +${on.size - 1}" else "", false)
        }
        val next = live.count { it.state == ReadingState.WANT }
        return if (next > 0) DailyLine("$next up next", false) else null
    }
}

/** The reading list on a replica. Undo keeps the fields a command changed, in memory, until the next command. */
class Reading(private val replica: Replica, private val newId: () -> String, private val nowMs: () -> Long) {
    private val undos = mutableMapOf<String, Pair<String, Map<String, FieldValue>>>()
    private var undoSeq = 0

    fun items(): List<ReadingItem> = ReadingRules.items(replica.entities(EntityTypes.READING_ITEM).associate { it.ref.entityId to it.fields })

    fun view(cal: LocalCalendar): ReadingView = ReadingRules.view(items(), nowMs(), cal)

    private fun live(id: String): ReadingItem? = items().firstOrNull { it.id == id && !it.removed }

    /** Writes [fields] on [id], keeping what they replace for Undo; returns the undo token. */
    private fun write(id: String, fields: Map<String, FieldValue>): String {
        val before = replica.entity(EntityTypes.READING_ITEM, id)
        val old = fields.keys.associateWith { k -> before?.get(k) ?: FieldValue.Null }
        replica.commitLocal(EntityTypes.READING_ITEM, id, fields)
        undos.clear()
        val token = "r${undoSeq++}"
        undos[token] = id to old
        return token
    }

    /** Adds what [text] names (a share's [subject] gives a link's title). The same name still on the list is said, not added. */
    fun add(text: String, subject: String? = null): ReadingSaid {
        val p = ReadingRules.parse(text, subject) ?: return ReadingSaid(ReadingRules.NO_TITLE, true)
        val now = nowMs()
        items().firstOrNull { !it.removed && ReadingRules.fold(it.title) == ReadingRules.fold(p.title) && it.state != ReadingState.FINISHED }?.let {
            return ReadingSaid("Already on your list: ${it.title}", false)
        }
        val id = newId()
        val token = write(id, mapOf(
            ReadingFields.TITLE to p.title.fv(), ReadingFields.BY to p.by.fv(), ReadingFields.KIND to p.kind.key.fv(),
            ReadingFields.URL to p.url.fv(), ReadingFields.UNIT to p.unit.key.fv(), ReadingFields.AT to 0L.fv(),
            ReadingFields.TOTAL to p.total.fv(),
            ReadingFields.STATE to (if (p.started) ReadingState.READING else ReadingState.WANT).key.fv(),
            ReadingFields.ADDED_AT to now.fv(), ReadingFields.STARTED_AT to (if (p.started) now else 0L).fv(),
            ReadingFields.MOVED_AT to (if (p.started) now else 0L).fv(), ReadingFields.ENDED_AT to 0L.fv(),
            ReadingFields.REMOVED to false.fv(),
        ))
        val where = if (p.started) "on the go" else "up next"
        return ReadingSaid("Added ${p.kind.label.lowercase()} ${p.title}" + (if (p.by.isNotEmpty()) " by ${p.by}" else "") + " · $where", false, token)
    }

    /** Starts one from up next, or picks up one put down or finished again. */
    fun start(id: String): ReadingSaid {
        val i = live(id) ?: return ReadingSaid(ReadingRules.GONE, true)
        val now = nowMs()
        val again = i.state == ReadingState.FINISHED
        val token = write(id, buildMap {
            put(ReadingFields.STATE, ReadingState.READING.key.fv())
            put(ReadingFields.MOVED_AT, now.fv())
            put(ReadingFields.ENDED_AT, 0L.fv())
            if (i.startedAtMs == 0L || again) put(ReadingFields.STARTED_AT, now.fv())
            if (again) put(ReadingFields.AT, 0L.fv())
        })
        return ReadingSaid("On the go: ${i.title}", false, token)
    }

    /** How far he's got; the last page (or 100%) finishes it. Typing on one up next starts it. */
    fun progress(id: String, text: String): ReadingSaid {
        val i = live(id) ?: return ReadingSaid(ReadingRules.GONE, true)
        val p = ReadingRules.progress(text) ?: return ReadingSaid(ReadingRules.NOT_PROGRESS, true)
        if (p.finished) return finish(id)
        val unit = p.unit ?: i.unit
        val total = p.total ?: if (unit == i.unit) i.total else 0L
        val at = p.at ?: 0L
        if (total > 0 && at > total) return ReadingSaid(ReadingRules.NOT_PROGRESS, true)
        val now = nowMs()
        val done = total > 0 && at >= total
        val token = write(id, buildMap {
            put(ReadingFields.UNIT, unit.key.fv()); put(ReadingFields.AT, at.fv()); put(ReadingFields.TOTAL, total.fv())
            put(ReadingFields.MOVED_AT, now.fv())
            put(ReadingFields.STATE, (if (done) ReadingState.FINISHED else ReadingState.READING).key.fv())
            if (i.startedAtMs == 0L) put(ReadingFields.STARTED_AT, now.fv())
            put(ReadingFields.ENDED_AT, (if (done) now else 0L).fv())
        })
        val shown = ReadingRules.where(i.copy(unit = unit, at = at, total = total))
        return ReadingSaid(if (done) "Finished ${i.title}" else "${i.title} · ${shown ?: "on the go"}", false, token)
    }

    fun finish(id: String): ReadingSaid = end(id, ReadingState.FINISHED, "Finished")

    fun putDown(id: String): ReadingSaid = end(id, ReadingState.PUT_DOWN, "Put down")

    private fun end(id: String, state: ReadingState, word: String): ReadingSaid {
        val i = live(id) ?: return ReadingSaid(ReadingRules.GONE, true)
        val now = nowMs()
        val token = write(id, mapOf(
            ReadingFields.STATE to state.key.fv(), ReadingFields.ENDED_AT to now.fv(), ReadingFields.MOVED_AT to now.fv(),
        ))
        return ReadingSaid("$word ${i.title}", false, token)
    }

    fun remove(id: String): ReadingSaid {
        val i = live(id) ?: return ReadingSaid(ReadingRules.GONE, true)
        val token = write(id, mapOf(ReadingFields.REMOVED to true.fv()))
        return ReadingSaid("Removed ${i.title}", false, token)
    }

    /** Puts back what the command behind [token] changed (the last one only). */
    fun undo(token: String): Boolean {
        val (id, old) = undos.remove(token) ?: return false
        replica.commitLocal(EntityTypes.READING_ITEM, id, old.mapValues { (k, v) ->
            if (v == FieldValue.Null) when (k) { ReadingFields.REMOVED -> true.fv(); ReadingFields.TITLE -> "".fv(); else -> v } else v
        })
        return true
    }
}
