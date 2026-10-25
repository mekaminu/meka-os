package os.meka.core.domain

/**
 * Taps feel instant, part (4) (Meka 2026-10-25 01:45: "Everything takes a while and app feels slow"): how the app feels
 * to him, measured by the apps themselves (cold start to Today, a tab switch, a screen's first content) against the
 * board's aims, so Health → Speed says "Tab switch · 320 ms · aim 150 ms" before he has to. Times only (and which tab),
 * kept in memory on this device, never stored or synced. Pure: the apps measure and hand in milliseconds.
 */
enum class FeelMoment(val label: String, val aimMs: Long) {
    /** The process starting to Today's first frame. */
    COLD_START("Cold start to Today", 1_500),

    /** A tap on a tab (or the Mac's sidebar) to the new screen's first frame. */
    TAB("Tab switch", 150),

    /** A screen behind a lock or a list (Money after the fingerprint's yes) to its first content. */
    SCREEN("First content", 300),
}

data class FeelEntry(
    val moment: FeelMoment,
    /** The slowest time in the window, in milliseconds, and where it was ("Calendar"), when the app said. */
    val worstMs: Long,
    val worstPlace: String?,
    val lastMs: Long,
    val count: Int,
    /** How many of those were over the moment's aim. */
    val over: Int,
)

/** The log (one per device, in the facade). Not thread-safe: the facade records on its core thread only. */
class FeelLog(private val windowMs: Long = FeelRules.WINDOW_MS) {
    private class Slot(var worstMs: Long, var worstPlace: String?, var lastMs: Long, var count: Int, var over: Int, var sinceMs: Long)

    private val slots = LinkedHashMap<FeelMoment, Slot>()

    /** One [moment] took [ms] at [atMs]; times [FeelRules.plausible] rejects are dropped. A window starts again after [windowMs]. */
    fun record(moment: FeelMoment, ms: Long, place: String?, atMs: Long) {
        if (!FeelRules.plausible(moment, ms)) return
        val where = place?.trim()?.take(FeelRules.PLACE_MAX)?.ifEmpty { null }
        val isOver = if (ms > moment.aimMs) 1 else 0
        val s = slots[moment]
        if (s == null || atMs - s.sinceMs >= windowMs) {
            slots[moment] = Slot(ms, where, ms, 1, isOver, atMs)
            return
        }
        s.lastMs = ms
        s.count++
        s.over += isOver
        if (ms > s.worstMs) { s.worstMs = ms; s.worstPlace = where }
    }

    /** Every moment timed in its window, in the enum's order (cold start, tab, screen), so the list doesn't jump. */
    fun all(): List<FeelEntry> = FeelMoment.entries.mapNotNull { m ->
        slots[m]?.let { FeelEntry(m, it.worstMs, it.worstPlace, it.lastMs, it.count, it.over) }
    }
}

object FeelRules {
    /** A day: cold starts are rare, so an hour's window would mostly show nothing. */
    const val WINDOW_MS = 24 * 60 * 60_000L
    /** Anything longer wasn't the app being slow (the phone slept mid-switch, the clock jumped). */
    const val MAX_MS = 60_000L
    /**
     * A "cold start" longer than this means the process was already running for something else (a notification, the
     * sync job) before he opened the app, so it's no cold start at all.
     */
    const val COLD_START_MAX_MS = 20_000L
    const val PLACE_MAX = 24

    fun plausible(moment: FeelMoment, ms: Long): Boolean =
        ms >= 0 && ms <= (if (moment == FeelMoment.COLD_START) COLD_START_MAX_MS else MAX_MS)

    /** One line: "Tab switch · 320 ms (Calendar) · aim 150 ms · 12 times, 2 over". */
    fun line(e: FeelEntry): String =
        "${e.moment.label} · ${SpeedRules.said(e.worstMs)}" + (e.worstPlace?.let { " ($it)" } ?: "") +
            " · aim ${SpeedRules.said(e.moment.aimMs)}" + when {
                e.count <= 1 -> ""
                e.over == 0 -> " · ${e.count} times"
                else -> " · ${e.count} times, ${e.over} over"
            }

    /** The moment furthest over its aim (by how many times the aim), or null when all are within it. */
    fun worstOver(entries: List<FeelEntry>): FeelEntry? =
        entries.filter { it.worstMs > it.moment.aimMs }.maxByOrNull { it.worstMs.toDouble() / it.moment.aimMs }

    /** Health's head line when something is over its aim: "Slower than it should be: Tab switch · 320 ms (aim 150 ms)". */
    fun overLine(e: FeelEntry): String =
        "Slower than it should be: ${e.moment.label} · ${SpeedRules.said(e.worstMs)} (aim ${SpeedRules.said(e.moment.aimMs)})"

    const val FEEL_HEADER = "How it feels · last day"
    const val WORK_HEADER = "Slowest work · last hour"
}
