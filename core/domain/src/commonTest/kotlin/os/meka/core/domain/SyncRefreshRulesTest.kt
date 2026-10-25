package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncRefreshRulesTest {
    @Test
    fun onlyAnotherDevicesChangesDuringARoundWait() {
        assertTrue(SyncRefreshRules.putOff(roundRunning = true, remote = true))
        assertFalse(SyncRefreshRules.putOff(roundRunning = true, remote = false), "a tap during a round shows at once")
        assertFalse(SyncRefreshRules.putOff(roundRunning = false, remote = true))
        assertFalse(SyncRefreshRules.putOff(roundRunning = false, remote = false))
    }

    @Test
    fun anEmptyRoundAddsNoRebuild() {
        assertFalse(SyncRefreshRules.endOfRound(deviceValuesChanged = false))
        assertTrue(SyncRefreshRules.endOfRound(deviceValuesChanged = true))
    }
}
