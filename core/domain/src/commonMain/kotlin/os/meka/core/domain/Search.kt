package os.meka.core.domain

/**
 * Search everything (build plan M1): tasks (open, Someday and done), calendar events, Waiting for, decisions (replaced
 * ones too), renewals and bills, habits and goals, searched on the device over what is already synced. Documents join
 * when the Vault lands.
 *
 * Non-AI and local: nothing is sent anywhere, nothing is stored, no index is kept (the data is small enough to scan on
 * each keystroke). Every word typed must match the start of a word in the item (so "den" finds "Dentist", "boil serv"
 * finds "Boiler service"), case and common accents ignored. Titles weigh more than notes; a title holding the whole
 * phrase comes first. Results are grouped by kind in a fixed order so they don't jump about while typing.
 */

enum class SearchKind(val label: String) {
    TASK("Tasks"),
    EVENT("Calendar"),
    WAITING("Waiting for"),
    DECISION("Decisions"),
    RENEWAL("Renewals and bills"),
    SOMEDAY("Someday"),
    GOAL("Goals"),
    HABIT("Habits"),
    DONE("Done"),
}

/** Where tapping a result goes. */
enum class SearchTarget {
    /** The task's detail (open or done). */
    TASK,
    LISTS_WAITING,
    LISTS_SOMEDAY,
    LISTS_DECISIONS,
    LISTS_RENEWALS,
    GOALS,
    /** Shown for information only (calendar events are read-only). */
    INFO,
}

data class SearchHit(
    val id: String,
    val kind: SearchKind,
    val title: String,
    /** "Planned Thu 8 Oct 14:00 · ↻ Every weekday", "Thu 8 Oct · 09:30–10:00 · Camp Nou", "Done Mon 5 Oct". */
    val detail: String?,
    /** A few words around the match when it was found in the notes, steps or rationale rather than the title. */
    val snippet: String?,
    val target: SearchTarget,
    /** The task itself for task hits (open, Someday, done), so the detail can open from the results. */
    val task: Task?,
    val score: Int,
)

data class SearchGroup(
    val kind: SearchKind,
    val label: String,
    val hits: List<SearchHit>,
    /** Matches not shown (more than [SearchRules.PER_GROUP]); "and 3 more · keep typing to narrow". */
    val more: Int,
) {
    val moreLine: String? get() = if (more > 0) "and $more more · keep typing to narrow" else null
}

data class SearchView(
    /** The query as typed. */
    val query: String,
    val groups: List<SearchGroup>,
    val total: Int,
) {
    /** True when there is something to search for (at least [SearchRules.MIN_CHARS] letters or digits). */
    val active: Boolean get() = SearchRules.tokens(query).isNotEmpty()

    /** "" before typing; "No matches for “boiler”"; "1 match"; "12 matches". */
    val summary: String get() = when {
        !active -> ""
        total == 0 -> "No matches for “${query.trim()}”"
        total == 1 -> "1 match"
        else -> "$total matches"
    }

    /** Every hit in display order (for keyboard navigation). */
    val hits: List<SearchHit> get() = groups.flatMap { it.hits }

    companion object {
        val EMPTY = SearchView("", emptyList(), 0)
    }
}

/** What to search, as the views already have it. */
data class SearchSources(
    val tasks: List<Task>,
    val events: List<CalendarEvent>,
    val waiting: List<WaitingItem>,
    /** Including replaced (superseded) decisions. */
    val decisions: List<DecisionItem>,
    val renewals: List<RenewalItem>,
    val habits: List<HabitItem>,
    val goals: List<GoalItem>,
)

object SearchRules {
    /** A query needs at least this many letters or digits in total before anything is searched. */
    const val MIN_CHARS = 2
    const val MAX_TOKENS = 8
    const val MAX_QUERY = 200
    /** Hits shown per group; the rest are counted. */
    const val PER_GROUP = 20
    const val SNIPPET_CHARS = 90

    private val FOLD: Map<Char, Char> = buildMap {
        "àáâãäåā".forEach { put(it, 'a') }; "çćč".forEach { put(it, 'c') }; "èéêëēę".forEach { put(it, 'e') }
        "ìíîïī".forEach { put(it, 'i') }; "ñń".forEach { put(it, 'n') }; "òóôõöøō".forEach { put(it, 'o') }
        "ùúûüū".forEach { put(it, 'u') }; "ýÿ".forEach { put(it, 'y') }; "śšş".forEach { put(it, 's') }
        "źżž".forEach { put(it, 'z') }; "ł".forEach { put(it, 'l') }; put('ß', 's')
    }

    /** Lower case, common accents folded, apostrophes dropped ("Ada's" → "adas"), anything else that isn't a letter or digit a space. */
    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        var space = true
        for (raw in text) {
            if (raw == '\'' || raw == '’') continue
            val c = raw.lowercaseChar().let { FOLD[it] ?: it }
            if (c.isLetterOrDigit()) { sb.append(c); space = false } else if (!space) { sb.append(' '); space = true }
        }
        return sb.toString().trimEnd()
    }

    /** The words of a query, distinct, at most [MAX_TOKENS]; empty when it has fewer than [MIN_CHARS] letters or digits. */
    fun tokens(query: String): List<String> {
        val n = normalize(query.take(MAX_QUERY))
        if (n.count { it != ' ' } < MIN_CHARS) return emptyList()
        return n.split(' ').filter { it.isNotEmpty() }.distinct().take(MAX_TOKENS)
    }

    private fun words(text: String?): List<String> =
        if (text.isNullOrBlank()) emptyList() else normalize(text).split(' ').filter { it.isNotEmpty() }

    /** A searchable field and how much a match in it counts. */
    internal class Field(val text: String?, val weight: Int, val snippetSource: Boolean = false) {
        val words = words(text)
    }

    /**
     * The score of an item for [tokens], or null when any token matches the start of no word in any field. Each token
     * adds the weight of the best field it matches (one more for a whole-word match); a title (the first field) that
     * contains the whole phrase adds 4, one that starts with it 2 more.
     */
    internal fun score(tokens: List<String>, fields: List<Field>): Int? {
        if (tokens.isEmpty()) return null
        var total = 0
        for (t in tokens) {
            var best = 0
            for (f in fields) {
                for (w in f.words) {
                    if (!w.startsWith(t)) continue
                    val s = f.weight * 2 + if (w.length == t.length) 1 else 0
                    if (s > best) best = s
                }
            }
            if (best == 0) return null
            total += best
        }
        val phrase = tokens.joinToString(" ")
        val title = fields.firstOrNull()?.words?.joinToString(" ").orEmpty()
        if (tokens.size > 1 && title.contains(phrase)) total += 4
        if (title.startsWith(phrase)) total += 2
        return total
    }

    /**
     * A few words around the first match in a snippet field, when no token is found in the title: "…ask about the
     * crown on the lower left…". Null when the title already shows why it matched.
     */
    internal fun snippet(tokens: List<String>, fields: List<Field>): String? {
        val title = fields.firstOrNull() ?: return null
        if (tokens.all { t -> title.words.any { it.startsWith(t) } }) return null
        for (f in fields.drop(1)) {
            if (!f.snippetSource || f.text.isNullOrBlank()) continue
            val text = f.text.replace(Regex("\\s+"), " ").trim()
            val at = firstMatch(text, tokens) ?: continue
            return excerpt(text, at)
        }
        return null
    }

    /** Index in [text] of the first word starting with any token, compared after normalising each word. */
    private fun firstMatch(text: String, tokens: List<String>): Int? {
        var i = 0
        while (i < text.length) {
            while (i < text.length && !text[i].isLetterOrDigit()) i++
            val start = i
            while (i < text.length && !text[i].isWhitespace()) i++
            if (start < i) {
                val w = normalize(text.substring(start, i))
                if (w.split(' ').any { part -> tokens.any { part.startsWith(it) } }) return start
            }
        }
        return null
    }

    private fun excerpt(text: String, at: Int): String {
        if (text.length <= SNIPPET_CHARS) return text
        var from = (at - SNIPPET_CHARS / 3).coerceAtLeast(0)
        if (from > 0) { val sp = text.indexOf(' ', from); from = if (sp in from until at) sp + 1 else from }
        var to = (from + SNIPPET_CHARS).coerceAtMost(text.length)
        if (to < text.length) { val sp = text.lastIndexOf(' ', to); if (sp > at) to = sp }
        return (if (from > 0) "…" else "") + text.substring(from, to).trim() + (if (to < text.length) "…" else "")
    }

    /** "Today", "Tomorrow", "Yesterday", else "Thu 8 Oct". */
    fun dayLabel(day: Long, today: Long): String = when (day) {
        today -> "Today"
        today + 1 -> "Tomorrow"
        today - 1 -> "Yesterday"
        else -> CivilDate.shortLabel(day)
    }
}

/**
 * Searches [SearchSources] for [query] (see [SearchRules]). [calendar] gives local days and times for the detail
 * lines; [nowMs] decides what is upcoming. Pure: same inputs, same results, on both apps.
 */
object Search {
    fun run(query: String, sources: SearchSources, nowMs: Long, calendar: LocalCalendar): SearchView {
        val tokens = SearchRules.tokens(query)
        if (tokens.isEmpty()) return SearchView(query, emptyList(), 0)
        val today = calendar.epochDayOf(nowMs)
        val hits = mutableListOf<Pair<SearchHit, Long>>() // hit and an order key within its group (smaller first)

        fun add(id: String, kind: SearchKind, title: String, detail: String?, target: SearchTarget, task: Task?, order: Long,
                fields: List<SearchRules.Field>) {
            val score = SearchRules.score(tokens, fields) ?: return
            hits += SearchHit(id, kind, title, detail?.ifBlank { null }, SearchRules.snippet(tokens, fields), target, task, score) to order
        }

        // Tasks: open ones, Someday, and done ones (a repeating task's done occurrences fold into the latest one).
        val doneSeries = mutableSetOf<String>()
        sources.tasks
            .filter { it.lifecycle != Lifecycle.CANCELLED }
            .sortedByDescending { it.completedAtMs ?: it.createdAtMs }
            .forEach { t ->
                val fields = listOf(
                    SearchRules.Field(t.title, 3),
                    SearchRules.Field(t.notes, 1, snippetSource = true),
                    SearchRules.Field(t.checklist.joinToString(" · ") { it.text }.ifEmpty { null }, 1, snippetSource = true),
                )
                when (t.lifecycle) {
                    Lifecycle.DONE -> {
                        if (t.seriesId != null && !doneSeries.add(t.seriesId)) return@forEach
                        val done = t.completedAtMs?.let { "Done ${SearchRules.dayLabel(calendar.epochDayOf(it), today).lowercaseFirst()}" }
                        add(t.id, SearchKind.DONE, t.title, listOfNotNull(done, t.repeatLabel?.let { "↻ $it" }).joinToString(" · "),
                            SearchTarget.TASK, t, -(t.completedAtMs ?: t.createdAtMs), fields)
                    }
                    Lifecycle.SOMEDAY -> add(t.id, SearchKind.SOMEDAY, t.title, ListRules.kindLabel(t.somedayKind ?: SomedayKind.IDEA),
                        SearchTarget.LISTS_SOMEDAY, t, -t.createdAtMs, fields)
                    else -> add(t.id, SearchKind.TASK, t.title, taskDetail(t, today, calendar), SearchTarget.TASK, t,
                        taskOrder(t), fields)
                }
            }

        // Calendar: upcoming first (soonest first), then past (most recent first).
        val startOfToday = calendar.toEpochMs(today, 0)
        sources.events.forEach { e ->
            val upcoming = e.endAtMs > startOfToday || (e.allDay && e.endAtMs > today * CivilDate.DAY_MS)
            add(e.id, SearchKind.EVENT, e.title, eventDetail(e, today, calendar), SearchTarget.INFO, null,
                if (upcoming) e.startAtMs - Long.MAX_VALUE / 2 else -e.startAtMs,
                listOf(SearchRules.Field(e.title, 3), SearchRules.Field(e.location, 2), SearchRules.Field(e.calendarName, 1)))
        }

        sources.waiting.forEach { w ->
            add(w.id, SearchKind.WAITING, w.title, w.meta, SearchTarget.LISTS_WAITING, null, -w.sinceMs,
                listOf(SearchRules.Field(w.title, 3), SearchRules.Field(w.who, 2), SearchRules.Field(w.notes, 1, snippetSource = true)))
        }
        sources.decisions.forEach { d ->
            // A replaced decision is history: it sorts after the ones that stand.
            val order = -d.decidedAtMs + if (d.status == DecisionStatus.SUPERSEDED) Long.MAX_VALUE / 2 else 0L
            add(d.id, SearchKind.DECISION, d.statement, d.meta, SearchTarget.LISTS_DECISIONS, null, order,
                listOf(SearchRules.Field(d.statement, 3), SearchRules.Field(d.rationale, 1, snippetSource = true)))
        }
        sources.renewals.forEach { r ->
            add(r.id, SearchKind.RENEWAL, r.title, r.meta, SearchTarget.LISTS_RENEWALS, null, r.dueDay,
                listOf(SearchRules.Field(r.title, 3), SearchRules.Field(r.subject, 2),
                    SearchRules.Field(RenewalRules.kindLabel(r.kind), 1), SearchRules.Field(r.notes, 1, snippetSource = true)))
        }
        sources.goals.forEach { g ->
            add(g.id, SearchKind.GOAL, g.title, g.meta, SearchTarget.GOALS, null, 0,
                listOf(SearchRules.Field(g.title, 3), SearchRules.Field(g.target, 1, snippetSource = true)))
        }
        sources.habits.forEach { h ->
            add(h.id, SearchKind.HABIT, h.title, h.meta, SearchTarget.GOALS, null, 0, listOf(SearchRules.Field(h.title, 3)))
        }

        val groups = hits.groupBy { it.first.kind }.let { byKind ->
            SearchKind.entries.mapNotNull { kind ->
                val list = byKind[kind] ?: return@mapNotNull null
                val sorted = list.sortedWith(compareByDescending<Pair<SearchHit, Long>> { it.first.score }
                    .thenBy { it.second }.thenBy { it.first.title.lowercase() }.thenBy { it.first.id })
                    .map { it.first }
                SearchGroup(kind, kind.label, sorted.take(SearchRules.PER_GROUP), (sorted.size - SearchRules.PER_GROUP).coerceAtLeast(0))
            }
        }
        return SearchView(query, groups, hits.size)
    }

    /** Overdue and planned soonest first, then due, then undated (newest first). */
    private fun taskOrder(t: Task): Long = t.scheduledAtMs ?: t.dueAtMs?.let { it + 1 } ?: (Long.MAX_VALUE / 2 - t.createdAtMs)

    /** "Planned today 14:00", "Due Thu 8 Oct", "Overdue · due Mon 5 Oct", "Snoozed until Tomorrow", plus "↻ Every weekday". */
    private fun taskDetail(t: Task, today: Long, calendar: LocalCalendar): String {
        val parts = mutableListOf<String>()
        val scheduled = t.scheduledAtMs
        val due = t.dueAtMs
        when {
            t.deferredToDay != null && t.deferredToDay > today ->
                parts += "Snoozed until ${SearchRules.dayLabel(t.deferredToDay, today).lowercaseFirst()}"
            scheduled != null -> {
                val day = calendar.epochDayOf(scheduled)
                parts += "Planned ${SearchRules.dayLabel(day, today).lowercaseFirst()} ${LocalClock.formatMinute(calendar.minuteOfDay(scheduled))}"
            }
            due != null -> {
                val day = calendar.epochDayOf(due)
                parts += if (day < today) "Overdue · due ${CivilDate.shortLabel(day)}" else "Due ${SearchRules.dayLabel(day, today).lowercaseFirst()}"
            }
        }
        t.repeatLabel?.let { parts += "↻ $it" }
        if (t.checklist.isNotEmpty()) parts += "${t.checklist.count { it.checked }} of ${t.checklist.size} steps"
        return parts.joinToString(" · ")
    }

    /** "Thu 8 Oct · 09:30–10:00 · Camp Nou", "Today · all day". */
    private fun eventDetail(e: CalendarEvent, today: Long, calendar: LocalCalendar): String {
        val parts = mutableListOf<String>()
        if (e.allDay) {
            // All-day ranges are UTC dates.
            val first = e.startAtMs.floorDiv(CivilDate.DAY_MS)
            val last = (e.endAtMs - 1).floorDiv(CivilDate.DAY_MS).coerceAtLeast(first)
            parts += SearchRules.dayLabel(first, today)
            if (last > first) parts[0] = "${parts[0]} – ${SearchRules.dayLabel(last, today).lowercaseFirst()}"
            parts += "all day"
        } else {
            parts += SearchRules.dayLabel(calendar.epochDayOf(e.startAtMs), today)
            val start = LocalClock.formatMinute(calendar.minuteOfDay(e.startAtMs))
            val end = LocalClock.formatMinute(calendar.minuteOfDay(e.endAtMs))
            parts += if (e.endAtMs > e.startAtMs) "$start–$end" else start
        }
        e.location?.let { parts += it }
        return parts.joinToString(" · ")
    }

    /** "Today" → "today" for use mid-sentence; dates ("Thu 8 Oct") keep their capital. */
    private fun String.lowercaseFirst(): String = if (this == "Today" || this == "Tomorrow" || this == "Yesterday") lowercase() else this
}
