package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListenTest {
    @Test
    fun onlineWithSignalTheDeviceOnlyWithout() {
        assertEquals(ListenEngine.ONLINE, ListenRules.engine(hasSignal = true, onlineAvailable = true, onDeviceAvailable = true))
        assertEquals(ListenEngine.ONLINE, ListenRules.engine(hasSignal = true, onlineAvailable = true, onDeviceAvailable = false))
        assertEquals(ListenEngine.ON_DEVICE, ListenRules.engine(hasSignal = false, onlineAvailable = true, onDeviceAvailable = true))
        assertEquals(ListenEngine.ON_DEVICE, ListenRules.engine(hasSignal = true, onlineAvailable = false, onDeviceAvailable = true))
        assertNull(ListenRules.engine(hasSignal = false, onlineAvailable = true, onDeviceAvailable = false))
    }

    @Test
    fun aNetworkFailureOnlineFallsBackToTheDeviceOnce() {
        assertEquals(ListenEngine.ON_DEVICE, ListenRules.fallback(ListenEngine.ONLINE, networkFailure = true, onDeviceAvailable = true))
        assertNull(ListenRules.fallback(ListenEngine.ONLINE, networkFailure = false, onDeviceAvailable = true))
        assertNull(ListenRules.fallback(ListenEngine.ONLINE, networkFailure = true, onDeviceAvailable = false))
        assertNull(ListenRules.fallback(ListenEngine.ON_DEVICE, networkFailure = true, onDeviceAvailable = true))
    }

    @Test
    fun hisVoiceKeepsListeningOpenWhileTheWordsLag() {
        val t0 = 5_000_000L
        // Words last changed at 1 s, but the mic still heard him at 1.6 s: not over at 2 s, over at 2.4 s.
        val sound = ListenRules.lastSound(t0 + 1_000, t0 + 1_600)
        assertEquals(t0 + 1_600, sound)
        assertEquals(ListenStep.KEEP, TalkEndpoint.step(t0, true, sound, t0 + 2_000))
        assertEquals(ListenStep.FINISH, TalkEndpoint.step(t0, true, sound, t0 + 2_400))
        assertEquals(t0 + 1_000, ListenRules.lastSound(t0 + 1_000, null))
        // A noisy room: the voice counts only 1.5 s past the last word, so listening still ends.
        assertEquals(t0 + 2_500, ListenRules.lastSound(t0 + 1_000, t0 + 9_000))
        assertEquals(t0 + 1_000, ListenRules.lastSound(t0 + 1_000, t0 + 400))
        assertTrue(ListenRules.isVoice(0.5f))
        assertFalse(ListenRules.isVoice(0.1f))
        assertEquals(600L, ListenRules.END_PAUSE_MS)
    }

    @Test
    fun theSheetSaysWhichSpeechIsListening() {
        assertEquals("Listening · Google's speech", ListenRules.listeningLine(ListenEngine.ONLINE, ListenRules.GOOGLE))
        assertEquals("Listening · on this phone, no signal", ListenRules.listeningLine(ListenEngine.ON_DEVICE, ListenRules.GOOGLE))
        assertEquals("Listening · on this Mac, no signal", ListenRules.listeningLine(ListenEngine.ON_DEVICE, ListenRules.APPLE, "Mac"))
    }

    @Test
    fun activitySaysHowQuicklyItWasAdded() {
        assertNull(HeardTimingRules.timingLine(emptyList()))
        assertEquals("Speech to Added · 0.6 s", HeardTimingRules.timingLine(listOf(HeardTiming(612, ListenEngine.ONLINE))))
        val line = HeardTimingRules.timingLine(
            listOf(
                HeardTiming(700, ListenEngine.ON_DEVICE),
                HeardTiming(1_420, ListenEngine.ONLINE),
                HeardTiming(560, ListenEngine.ONLINE),
            ),
        )
        assertEquals("Speech to Added · 0.6 s · 1.4 s slow · 0.7 s offline · middle 0.7 s", line)
        val many = (1..14).map { HeardTiming(it * 100L, ListenEngine.ONLINE) }
        assertTrue(HeardTimingRules.timingLine(many)!!.startsWith("Speech to Added · 1.4 s slow · 1.3 s slow"))
        assertEquals(11, HeardTimingRules.timingLine(many)!!.split(" · ").size - 1) // 10 kept + the middle
    }
}
