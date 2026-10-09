package os.meka.android.today

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HereLocationTest {
    @Test
    fun theFusedProviderComesFirstThenTheNetworkThenGps() {
        assertEquals("fused", HereLocation.pickProvider(listOf("gps", "network", "fused", "passive")))
        assertEquals("network", HereLocation.pickProvider(listOf("gps", "network", "passive")))
        assertEquals("gps", HereLocation.pickProvider(listOf("passive", "gps")))
    }

    @Test
    fun withLocationOffOrOnlyPassiveNothingIsAsked() {
        assertNull(HereLocation.pickProvider(emptyList()))
        assertNull(HereLocation.pickProvider(listOf("passive")))
    }

    @Test
    fun aFixThePhoneAlreadyHasIsUsedOnlyWhileFresh() {
        assertTrue(HereLocation.freshEnough(0))
        assertTrue(HereLocation.freshEnough(HereLocation.LAST_KNOWN_FRESH_MS))
        assertFalse(HereLocation.freshEnough(HereLocation.LAST_KNOWN_FRESH_MS + 1))
        assertFalse(HereLocation.freshEnough(-1)) // a clock that went backwards: take a new one
    }
}
