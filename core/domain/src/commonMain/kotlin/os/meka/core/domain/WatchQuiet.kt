package os.meka.core.domain

/**
 * Galaxy Watch, completeness slice 2: the watch keeps Navilife's quiet hours and spares its battery.
 *
 * - **Quiet hours follow Navilife's** (Ask → Settings → Notifications, synced): while they're on, the day screen's foot
 *   and the tile say "Quiet hours · until 07:00", the tile drops the inbox count (mail can wait for the morning) and
 *   nothing on the watch lights for it. A parking timer, a meeting about to start and a live match still show and
 *   count, as they do by day. The watch makes no sound of its own at any hour; what buzzes is the phone's own
 *   notification, which the phone's quiet hours already hold.
 * - **Battery-friendly refresh**: the tile looks again each minute only while something is counting down, else every
 *   quarter hour by day. In quiet hours a fast's clock moves every quarter hour instead of every minute, and a calm
 *   tile looks again hourly (or when the quiet hours end, if that's sooner).
 * - **The background sync rests at night**: the quarter-hourly sync runs at most hourly in quiet hours, unless changes
 *   made on the watch wait to go or something short is counting (a parking timer, a meeting about to start, a match).
 *
 * Pure, no AI; nothing is stored.
 */
object WatchQuietRules {
    /** How often a calm tile looks again in quiet hours. */
    const val QUIET_REFRESH_MS = 60 * 60_000L

    /** How often a fast's clock (or another slow countdown) moves on the tile in quiet hours. */
    const val QUIET_COUNTING_MS = 15 * 60_000L

    /** The background sync in quiet hours with nothing waiting or counting: at most this often. */
    const val QUIET_SYNC_MS = 60 * 60_000L

    /** WorkManager's quarter-hourly runs drift by a minute or two: a run this close to the hour counts as due. */
    const val SYNC_SLACK_MS = 2 * 60_000L

    const val SPOKEN = "Quiet hours are on."

    /** "Quiet hours · until 07:00" while [quiet] holds at [nowMs]; null otherwise. */
    fun line(quiet: QuietHours, nowMs: Long, cal: LocalCalendar): String? {
        if (!quiet.isQuietAt(nowMs, cal)) return null
        return "Quiet hours · until ${LocalClock.formatMinute(quiet.endMinute)}"
    }

    /**
     * Something short that matters minute by minute and keeps counting in quiet hours too: a parking timer (it costs
     * money), a meeting about to start, a match under way.
     */
    fun urgent(home: WatchHomeView?): Boolean =
        home != null && (home.parking.any { !it.ended } || home.prep != null || home.match?.lit == true)

    /**
     * When the tile and complication should look again. By day [WatchTileRules.refreshAfterMs]. In quiet hours: the
     * next minute while something [urgent] counts, a quarter hour while a fast runs or something else is lit, else an
     * hour, never past the quiet hours' end ([quietEndMs]) so the morning tile is drawn on time.
     */
    fun refreshAfterMs(home: WatchHomeView?, nowMs: Long, quiet: Boolean, quietEndMs: Long? = null): Long {
        if (!quiet) return WatchTileRules.refreshAfterMs(home, nowMs)
        if (urgent(home)) return WatchHomeRules.nextTickMs(nowMs) - nowMs
        val slow = home != null && (home.fast.running || home.lit)
        val after = if (slow) QUIET_COUNTING_MS else QUIET_REFRESH_MS
        val toEnd = quietEndMs?.let { it - nowMs }?.takeIf { it > 0 } ?: return after
        return minOf(after, toEnd)
    }

    /**
     * Whether a background sync run should reach the server now. Always by day, the first time, while changes made
     * here wait ([waiting]) or something [urgent] counts; in quiet hours otherwise only once an hour has passed since
     * [lastSyncMs] (the last run that went through).
     */
    fun syncDue(lastSyncMs: Long?, nowMs: Long, quiet: Boolean, waiting: Boolean, urgent: Boolean): Boolean {
        if (!quiet || waiting || urgent || lastSyncMs == null || lastSyncMs > nowMs) return true
        return nowMs - lastSyncMs >= QUIET_SYNC_MS - SYNC_SLACK_MS
    }

    /**
     * The tile in quiet hours: the inbox count waits for the morning, the label isn't lit unless something [urgent]
     * counts, and the quiet line sits at the foot. Unchanged by day ([line] null) or unlinked ([home] null).
     */
    fun tile(view: WatchTileView, home: WatchHomeView?, line: String?): WatchTileView {
        if (line == null || home == null) return view
        return view.copy(
            lit = view.lit && urgent(home),
            inboxLine = null,
            inboxLit = false,
            quietLine = line,
            spoken = (home.inbox?.spoken?.let { view.spoken.replace(" $it", "") } ?: view.spoken) + " $SPOKEN",
        )
    }
}
