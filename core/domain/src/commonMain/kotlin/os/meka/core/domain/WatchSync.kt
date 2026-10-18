package os.meka.core.domain

/**
 * Galaxy Watch, completeness slice 1: what the watch says about its own changes and how it leaves Navilife.
 *
 * - **Changes waiting** ([WatchSyncRules]): a Done, Tomorrow, capture or fast tapped on the watch with no signal is
 *   kept on the watch and sent when it's back online; the day screen's foot says so ("Offline · your changes here wait
 *   to sync"), and the tile carries a short mark ("Waiting to sync") so a tap that didn't go anywhere yet isn't a
 *   mystery. Nothing waiting and online: nothing is said.
 * - **Unlink from the watch itself** ([WatchUnlinkRules]): at the foot of the day screen, "Unlink this watch" asks
 *   once (what it forgets, and that changes not yet synced would be lost); Unlink revokes the watch's key on
 *   Navilife's server at once, then the watch forgets the day and its key and shows a new code. It needs a connection
 *   (the server must revoke the key), so offline it says so and does nothing.
 *
 * Pure, no AI; nothing is stored.
 */
object WatchSyncRules {
    const val OFFLINE_WAITING = "Offline · your changes here wait to sync"
    const val FAILING_WAITING = "Not synced yet · your changes here wait"
    const val TILE_MARK = "Waiting to sync"
    const val SPOKEN_MARK = "Changes made on the watch wait to sync."

    /**
     * The day screen's foot line; null when there's nothing to say (online and nothing waiting). No count: the
     * replica counts field changes, not taps, so "14 changes" for two captures would mislead.
     */
    fun line(offline: Boolean, failing: Boolean, waiting: Boolean): String? = when {
        offline && waiting -> OFFLINE_WAITING
        offline -> WatchHomeRules.OFFLINE
        failing && waiting -> FAILING_WAITING
        else -> null
    }

    /** The tile's short mark; null when nothing waits. */
    fun tileMark(waiting: Boolean): String? = if (waiting) TILE_MARK else null

    /** For a screen reader, after the tile's own words. */
    fun spokenMark(waiting: Boolean): String? = if (waiting) SPOKEN_MARK else null
}

/** The confirm row and its outcomes for "Unlink this watch". */
data class WatchUnlinkAsk(
    val title: String,
    val line: String,
    /** "Changes made here haven't synced and will be lost"; null when nothing waits. */
    val loss: String?,
    /** Unlink can't work right now (offline): the reason, and the row offers only Keep. */
    val blocked: String?,
)

object WatchUnlinkRules {
    const val OFFER = "Unlink this watch"
    const val TITLE = "Unlink this watch?"
    const val LINE = "It forgets Navilife's copy of your day here. To use it again, link it with a new code."
    const val UNLINK = "Unlink"
    const val KEEP = "Keep"
    const val NEEDS_CONNECTION = "Needs a connection · try again when the watch is online"
    const val WORKING = "Unlinking…"
    const val FAILED = "Couldn't unlink · try again in a moment"

    const val LOSS = "Changes made here haven't synced and will be lost"

    fun ask(offline: Boolean, waiting: Boolean): WatchUnlinkAsk = WatchUnlinkAsk(
        title = TITLE,
        line = LINE,
        loss = if (waiting) LOSS else null,
        blocked = if (offline) NEEDS_CONNECTION else null,
    )

    /** What the row says when Unlink didn't go through: offline, else a failure. */
    fun failed(offline: Boolean): String = if (offline) NEEDS_CONNECTION else FAILED
}
