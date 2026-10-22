package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ada's Live mode, slice 2c: Live after the hello, and carrying a conversation on past one window. */
class LiveHandOnTest {
    @Test
    fun aConversationCarriesOnOnlyWhileHeIsTalkingAndAtMostAnHour() {
        val now = 1_000_000L
        assertTrue(LiveHandOnRules.resume("CjQKMgo-h_1", 0, now - 30_000, now))
        assertTrue(LiveHandOnRules.resume("h", LiveHandOnRules.MAX_RESUMES - 1, now - LiveHandOnRules.IDLE_MS, now))
        // Quiet for over two minutes, never a word, no handle, or the hour used: it ends as before.
        assertFalse(LiveHandOnRules.resume("h", 0, now - LiveHandOnRules.IDLE_MS - 1, now))
        assertFalse(LiveHandOnRules.resume("h", 0, null, now))
        assertFalse(LiveHandOnRules.resume(null, 0, now, now))
        assertFalse(LiveHandOnRules.resume("h", LiveHandOnRules.MAX_RESUMES, now, now))
        // About an hour in all: the first window and five fresh ones.
        assertEquals(60, (LiveHandOnRules.MAX_RESUMES + 1) * LiveModeRules.TOKEN_MS / 60_000)
    }

    @Test
    fun onlyAPlainHandleIsPassedOn() {
        assertEquals("abc-_=", LiveHandOnRules.handle("  abc-_=  "))
        assertNull(LiveHandOnRules.handle(""))
        assertNull(LiveHandOnRules.handle("two words"))
        assertNull(LiveHandOnRules.handle("x\ty"))
        assertNull(LiveHandOnRules.handle("x".repeat(LiveHandOnRules.MAX_HANDLE + 1)))
        assertNull(LiveHandOnRules.handle("café"))
    }

    @Test
    fun theHelloIsPassedOnAsOneLineAndASessionAskedForDuringItKeepsUnderAMinute() {
        assertEquals("Morning. Dentist at half nine.", LiveHandOnRules.greeted("Morning.\n  Dentist at half nine. "))
        assertNull(LiveHandOnRules.greeted("   "))
        assertNull(LiveHandOnRules.greeted(null))
        assertEquals(LiveHandOnRules.MAX_GREETED, LiveHandOnRules.greeted("x".repeat(900))!!.length)
        assertTrue(LiveHandOnRules.preparedUsable(0, 20_000))
        assertTrue(LiveHandOnRules.preparedUsable(0, LiveHandOnRules.PREPARED_FOR_MS))
        assertFalse(LiveHandOnRules.preparedUsable(0, LiveModeRules.START_MS))
        assertFalse(LiveHandOnRules.preparedUsable(5_000, 0))
        assertTrue(LiveHandOnRules.PREPARED_FOR_MS < LiveModeRules.START_MS)
    }
}
