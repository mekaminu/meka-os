package os.meka.core.domain

/**
 * Taps feel instant, slice 3 (Meka 2026-10-25 01:45: "Everything takes a while and app feels slow"): how long the
 * core's own work takes on this device, by name, so Health → Speed can say which part is slow ("Money view · 1.8 s").
 * Times only, never what the work was about; kept in memory on this device, never stored or synced. Pure: the caller
 * measures and hands in milliseconds.
 */
data class SpeedEntry(
    val name: String,
    /** The slowest time in the window, in milliseconds. */
    val worstMs: Long,
    /** The most recent time. */
    val lastMs: Long,
    /** How many times it ran in the window. */
    val count: Int,
    /** How many of those were over [SpeedRules.SLOW_MS]. */
    val slow: Int,
)

/** The log itself (one per device, in the facade). Not thread-safe: the facade records on its core thread only. */
class SpeedLog(private val windowMs: Long = SpeedRules.WINDOW_MS) {
    private class Slot(var worstMs: Long, var lastMs: Long, var count: Int, var slow: Int, var sinceMs: Long)

    private val slots = LinkedHashMap<String, Slot>()

    /** One run of [name] took [ms] at [atMs]. A name's window starts again once it is older than [windowMs]. */
    fun record(name: String, ms: Long, atMs: Long) {
        val t = ms.coerceAtLeast(0)
        val s = slots[name]
        if (s == null || atMs - s.sinceMs >= windowMs) {
            if (s == null && slots.size >= SpeedRules.MAX_NAMES) return
            slots[name] = Slot(t, t, 1, if (t > SpeedRules.SLOW_MS) 1 else 0, atMs)
            return
        }
        s.lastMs = t
        s.count++
        if (t > SpeedRules.SLOW_MS) s.slow++
        if (t > s.worstMs) s.worstMs = t
    }

    /** The [limit] slowest names in their windows, slowest first (ties by name, so the list doesn't jump). */
    fun worst(limit: Int = SpeedRules.WORST): List<SpeedEntry> =
        slots.map { (n, s) -> SpeedEntry(n, s.worstMs, s.lastMs, s.count, s.slow) }
            .sortedWith(compareByDescending<SpeedEntry> { it.worstMs }.thenBy { it.name })
            .take(limit)
}

object SpeedRules {
    /** A tap's result shows in the next frame (≤ 100 ms): core work longer than this is "slow". */
    const val SLOW_MS = 100L
    /** Only work over this is worth logging at all (the board: "log any onCore block over 50 ms"). */
    const val LOG_MS = 50L
    const val WINDOW_MS = 60 * 60_000L
    const val WORST = 10
    const val MAX_NAMES = 64
    const val HEALTH_KEY = "speed"

    /** "1.8 s" from 1 s up, "240 ms" below. */
    fun said(ms: Long): String = if (ms < 1000) "$ms ms" else {
        val tenths = (ms + 50) / 100
        "${tenths / 10}.${tenths % 10} s"
    }

    /** One line of the list: "Money view · 1.8 s · 12 times, 3 slow". */
    fun line(e: SpeedEntry): String =
        "${e.name} · ${said(e.worstMs)}" + when {
            e.count <= 1 -> ""
            e.slow == 0 -> " · ${e.count} times"
            else -> " · ${e.count} times, ${e.slow} slow"
        }

    /**
     * Health's Speed row: the slowest part in the last hour on its line, the worst ten under it. Information only
     * (always a tick: a slow part is for Meka and the build to see, not a fault to nag about on Today). Null until
     * anything was timed.
     */
    fun healthRow(entries: List<SpeedEntry>?): HealthRow? {
        if (entries.isNullOrEmpty()) return null
        val top = entries.first()
        val head = if (top.worstMs <= SLOW_MS) "Quick · nothing over ${said(SLOW_MS)} in the last hour"
        else "Slowest in the last hour: ${top.name} · ${said(top.worstMs)}"
        return HealthRow(HEALTH_KEY, "Speed", head, HealthState.OK, detail = entries.take(WORST).joinToString("\n") { line(it) })
    }
}

/**
 * Taps feel instant, slice 5a: the money view is kept when nothing it is built from changed. [same] is true only when
 * every input equals the last build's and the screen still shows exactly that build (anything else that set the view,
 * a lock or a failed open, means build again).
 */
object MoneyViewMemo {
    const val MINUTE_MS = 60_000L

    fun same(lastInputs: List<Any?>?, inputs: List<Any?>, lastBuilt: Any?, showing: Any?): Boolean =
        lastInputs != null && lastBuilt != null && showing === lastBuilt && lastInputs == inputs
}
