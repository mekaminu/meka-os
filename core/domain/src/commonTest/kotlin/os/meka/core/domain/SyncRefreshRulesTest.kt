package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun aBurstOfTheOtherDevicesEditsWaitsAtMostHalfASecondAfterTheLastRebuild() {
        val t = 1_792_144_800_000L
        assertEquals(500L, SyncRefreshRules.settleMs(lastBuiltMs = t, nowMs = t), "just rebuilt: wait the whole half second")
        assertEquals(300L, SyncRefreshRules.settleMs(lastBuiltMs = t, nowMs = t + 200))
        assertEquals(0L, SyncRefreshRules.settleMs(lastBuiltMs = t, nowMs = t + 2_000), "long since: pull at once")
        assertEquals(500L, SyncRefreshRules.settleMs(lastBuiltMs = t + 1_000, nowMs = t), "a clock that went back never waits longer")
    }

    @Test
    fun theRoundStepsAsideOnlyWhenItHasPulledChangesToShow() {
        assertTrue(SyncRefreshRules.stepAside(pulledOwed = true))
        assertFalse(SyncRefreshRules.stepAside(pulledOwed = false))
    }
}
