package os.meka.core.domain

/**
 * Taps feel instant, slice 4 (Meka 2026-10-25 01:45: "Everything takes a while and app feels slow"): when the app
 * rebuilds its views around a sync round. Before, every page of another device's changes rebuilt every view in the
 * app, and every round ended with one more rebuild even when it pulled nothing, so each tap (which pushes at once)
 * cost two whole-app rebuilds on the core's one thread. Now a round's pulled pages are shown with one rebuild after
 * the pull, and an empty round adds none. Pure: the facade asks, and does the rebuilding.
 */
object SyncRefreshRules {
    /**
     * True when a change should wait for the round's one rebuild: only another device's changes ([remote]) while a
     * sync round is running. This device's own commits (a tap made while the round waits on the network) never wait.
     */
    fun putOff(roundRunning: Boolean, remote: Boolean): Boolean = roundRunning && remote

    /**
     * Whether the end of a round needs a rebuild of its own: when the round's own steps changed something the views
     * read that isn't a synced change (the Vault values kept on this device), or when the minute has moved on since
     * the views were last built (a round is also how a long-open app's views catch up with the time: a snooze ending,
     * a parcel's day passing). A tap's own push round, a moment after the tap's rebuild, needs none. Pulled changes
     * were already shown, and the round's synced commits refreshed as they were made.
     */
    fun endOfRound(deviceValuesChanged: Boolean, lastBuiltMs: Long, nowMs: Long): Boolean =
        deviceValuesChanged || nowMs / MINUTE_MS != lastBuiltMs / MINUTE_MS

    /**
     * Taps feel instant, slice 19: how long the live long-poll waits, after it says another device changed something,
     * before pulling it: what's left of [SETTLE_MS] since the views were last built (none when that was longer ago).
     * A burst of the other device's edits (the Mac ticking five tasks) is then pulled and shown with one rebuild
     * instead of one per edit, and still shows within about half a second.
     */
    fun settleMs(lastBuiltMs: Long, nowMs: Long): Long = (SETTLE_MS - (nowMs - lastBuiltMs)).coerceIn(0L, SETTLE_MS)

    /**
     * Slice 19: whether the round should step aside before showing what it pulled, so taps queued while it waited
     * on the network run first (and their own rebuild shows the pulled changes too, leaving the round none to make).
     */
    fun stepAside(pulledOwed: Boolean): Boolean = pulledOwed

    const val MINUTE_MS = 60_000L
    const val SETTLE_MS = 500L
}
