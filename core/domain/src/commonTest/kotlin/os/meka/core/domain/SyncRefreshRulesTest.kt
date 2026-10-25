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
        val t = 1_792_144_800_000L // 11:00:00
        assertFalse(SyncRefreshRules.endOfRound(false, lastBuiltMs = t, nowMs = t + 900), "a tap's push round, the same minute")
        assertTrue(SyncRefreshRules.endOfRound(true, lastBuiltMs = t, nowMs = t))
        assertTrue(SyncRefreshRules.endOfRound(false, lastBuiltMs = t + 59_000, nowMs = t + 61_000), "the minute moved on")
    }
}
