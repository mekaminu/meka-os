package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchSyncTest {
    @Test
    fun theFootSaysChangesWaitAndNothingWhenAllIsSent() {
        assertEquals("Offline · your changes here wait to sync", WatchSyncRules.line(offline = true, failing = false, waiting = true))
        assertEquals(WatchHomeRules.OFFLINE, WatchSyncRules.line(offline = true, failing = false, waiting = false))
        assertEquals("Not synced yet · your changes here wait", WatchSyncRules.line(offline = false, failing = true, waiting = true))
        assertNull(WatchSyncRules.line(offline = false, failing = true, waiting = false))
        assertNull(WatchSyncRules.line(offline = false, failing = false, waiting = false))
    }

    @Test
    fun theTileCarriesAShortMarkOnlyWhileSomethingWaits() {
        assertEquals("Waiting to sync", WatchSyncRules.tileMark(true))
        assertNull(WatchSyncRules.tileMark(false))
        assertEquals("Changes made on the watch wait to sync.", WatchSyncRules.spokenMark(true))
        assertNull(WatchSyncRules.spokenMark(false))
        val tile = WatchTileRules.tile(null)
        assertNull(tile.syncMark)
        assertEquals("Waiting to sync", tile.copy(syncMark = WatchSyncRules.tileMark(true)).syncMark)
    }

    @Test
    fun unlinkingAsksOnceSaysWhatWouldBeLostAndNeedsAConnection() {
        val calm = WatchUnlinkRules.ask(offline = false, waiting = false)
        assertEquals("Unlink this watch?", calm.title)
        assertNull(calm.loss)
        assertNull(calm.blocked)
        assertEquals("Changes made here haven't synced and will be lost", WatchUnlinkRules.ask(offline = false, waiting = true).loss)
        assertEquals(WatchUnlinkRules.NEEDS_CONNECTION, WatchUnlinkRules.ask(offline = true, waiting = false).blocked)
        assertEquals(WatchUnlinkRules.NEEDS_CONNECTION, WatchUnlinkRules.failed(offline = true))
        assertEquals(WatchUnlinkRules.FAILED, WatchUnlinkRules.failed(offline = false))
    }
}
