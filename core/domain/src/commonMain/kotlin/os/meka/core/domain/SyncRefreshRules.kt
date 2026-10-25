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
     * Whether the end of a round needs a rebuild of its own: only when the round's own steps changed something the
     * views read that isn't a synced change (the Vault values kept on this device). Pulled changes were already shown,
     * and the round's synced commits refreshed as they were made.
     */
    fun endOfRound(deviceValuesChanged: Boolean): Boolean = deviceValuesChanged
}
