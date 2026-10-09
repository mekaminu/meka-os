package os.meka.core.domain

/**
 * One row for the same event on several calendars (Fold review 2026-10-09, item 5: "Training" 18:00–19:00 twice plus
 * "Training - 3G" at the same time). Non-AI, pure, unit-tested. Nothing in the real calendars changes.
 *
 * - Events are the same when they start and end at the same moment (both all-day or both timed) and their titles
 *   match: equal once case and punctuation are ignored, or one contains the other as whole words ("Training" in
 *   "Training - 3G"). Different times always stay separate rows.
 * - The row is the event with the fullest title ("Training - 3G"; then one with a place, then a call link, then the
 *   lowest id, so every device picks the same one); the others ride along in [CalendarEvent.alsoOn] and its line lists
 *   every calendar ("SG18 · Personal · Kids").
 * - An event Meka is adding or editing in MEKA (provisional, or with an edit on its way) is never merged, so the edit
 *   shows on its own row.
 * - Hiding any one of them hides the row ([EventMarks.visible], [hiddenIn]).
 */
object DuplicateEvents {
    /** [events] with each set of duplicates folded into one; order otherwise kept (the first of each set's place). */
    fun merge(events: List<CalendarEvent>): List<CalendarEvent> {
        if (events.size < 2) return events
        val byTime = LinkedHashMap<Triple<Boolean, Long, Long>, MutableList<CalendarEvent>>()
        events.forEach { e ->
            if (e.isProvisional || e.pendingEditId != null) return@forEach
            byTime.getOrPut(Triple(e.allDay, e.startAtMs, e.endAtMs)) { mutableListOf() }.add(e)
        }
        val replace = HashMap<String, CalendarEvent>()
        val dropped = HashSet<String>()
        byTime.values.filter { it.size > 1 }.forEach { same ->
            clusters(same).filter { it.size > 1 }.forEach { cluster ->
                val sorted = cluster.sortedWith(PICK)
                val row = sorted.first()
                val rest = sorted.drop(1)
                // The row takes the place of whichever of them came first.
                val firstId = cluster.first().id
                replace[firstId] = row.copy(alsoOn = rest)
                cluster.forEach { if (it.id != firstId) dropped += it.id }
            }
        }
        if (replace.isEmpty()) return events
        return events.mapNotNull { e -> if (e.id in dropped) null else replace[e.id] ?: e }
    }

    /** The ids of [eventId]'s row in [events]: itself and the same event on Meka's other calendars. */
    fun idsWith(eventId: String, events: List<CalendarEvent>): List<String> =
        merge(events).firstOrNull { it.id == eventId || it.alsoOn.any { o -> o.id == eventId } }
            ?.let { listOf(it.id) + it.alsoOn.map { o -> o.id } } ?: listOf(eventId)

    /** Whether the row for [e] (with its duplicates) is hidden: any one of them hidden hides it. */
    fun hiddenIn(e: CalendarEvent, hidden: Set<String>): Boolean = e.id in hidden || e.alsoOn.any { it.id in hidden }

    /** "training 3g" for "Training - 3G"; "adas" for "Ada's" (apostrophes go, other punctuation splits words). */
    fun normalise(title: String): String =
        title.lowercase().filterNot { it == '\'' || it == '\u2019' }.map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").split(' ').filter { it.isNotEmpty() }.joinToString(" ")

    /** Equal, or one contains the other as whole words. */
    fun titlesMatch(a: String, b: String): Boolean {
        val x = normalise(a)
        val y = normalise(b)
        if (x.isEmpty() || y.isEmpty()) return x == y && a.trim().equals(b.trim(), ignoreCase = true)
        if (x == y) return true
        val (short, long) = if (x.length <= y.length) x to y else y to x
        return " $long ".contains(" $short ")
    }

    /** Groups [same] (one start and end) by titles that all match each other ("Training - 3G" and "Training - Gym" stay apart). */
    private fun clusters(same: List<CalendarEvent>): List<List<CalendarEvent>> {
        val out = mutableListOf<MutableList<CalendarEvent>>()
        same.forEach { e ->
            val home = out.firstOrNull { c -> c.all { titlesMatch(it.title, e.title) } }
            if (home != null) home += e else out += mutableListOf(e)
        }
        return out
    }

    private val PICK = compareByDescending<CalendarEvent> { normalise(it.title).length }
        .thenBy { if (it.location.isNullOrBlank()) 1 else 0 }
        .thenBy { if (it.joinUrl.isNullOrBlank()) 1 else 0 }
        .thenBy { it.id }
}
