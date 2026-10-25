package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.BargeInOutput
import os.meka.core.domain.LiveAudioLines
import os.meka.core.domain.LiveAudioStats
import os.meka.core.domain.LiveDiagnosisRules
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Live item's (3), 2026-10-25: how a talk in Live sounded (speaker or headphones, cut-offs, waits for the network)
 * is kept on this device with the last talk, and a wait shows in Health → Speed with its length.
 */
class LiveSoundFacadeTest {
    private val now = 1_792_144_800_000L

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(InMemoryServerOpStore())),
        secureRandom = Random(7), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun aTalksSoundIsKeptAndAWaitShowsInSpeed() = runTest {
        val store = InMemoryReplicaStore()
        val c = MekaCore(
            householdId = "hh", deviceId = "android", store = store,
            transport = os.meka.core.testing.FaultyTransport(SyncService(InMemoryServerOpStore())),
            secureRandom = Random(7), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
        )
        val sound = LiveAudioStats(BargeInOutput.SPEAKER, cutOffs = 1, waits = 2, longestWaitMs = 640, lastMicDbfs = -20f, lastPlayedDbfs = -26f, lastLetThrough = true)
        c.noteLiveOutcome(true, null, null, sound)
        val wait = c.speedWorst().single { it.name == LiveAudioLines.SPEED_WAIT }
        assertEquals(640L, wait.worstMs)
        val kept = LiveDiagnosisRules.decode(store.localValue(LiveDiagnosisRules.KEY))
        assertEquals(sound, kept?.audio)

        // A talk with no waits adds nothing to Speed; a fallback keeps no sound.
        val quiet = core()
        quiet.noteLiveOutcome(true, null, null, LiveAudioStats(BargeInOutput.HEADPHONES))
        assertTrue(quiet.speedWorst().none { it.name == LiveAudioLines.SPEED_WAIT })
        val fellStore = InMemoryReplicaStore()
        val fell = MekaCore(
            householdId = "hh", deviceId = "android", store = fellStore,
            transport = os.meka.core.testing.FaultyTransport(SyncService(InMemoryServerOpStore())),
            secureRandom = Random(7), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
        )
        fell.noteLiveOutcome(false, LiveDiagnosisRules.LiveFallback.SLOW_GOOGLE, null, sound)
        assertEquals(null, LiveDiagnosisRules.decode(fellStore.localValue(LiveDiagnosisRules.KEY))?.audio)
    }
}
