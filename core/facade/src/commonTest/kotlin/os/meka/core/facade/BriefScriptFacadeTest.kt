package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.BriefScriptRules
import os.meka.core.domain.CivilDate
import os.meka.core.domain.HabitTiming
import os.meka.core.domain.ReadOut
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A morning brief worth listening to, slice 1: the facade fills the story and Listen and Talk read the same script. */
class BriefScriptFacadeTest {
    private var now = 1_791_786_600_000L // Mon 12 Oct 2026, 07:30 in London

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(InMemoryServerOpStore())),
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun theBriefNamesTheTaskAndHabitAndTalkReadsTheSameScript() = runTest {
        val fold = core()
        fold.addTask("Renew passport")
        fold.addHabit("Gym", 3, HabitTiming.EVENING, 60, null)
        val v = fold.briefView.value
        assertEquals(CivilDate.toEpochDay(2026, 10, 12), v.story.epochDay)
        assertEquals(false, v.story.weekend)
        assertEquals("Renew passport", v.story.focus.single().title)
        val s = BriefScriptRules.script(v)
        assertTrue(s.startsWith("Good morning, Meka. It's Monday 12 October"), s)
        assertTrue("Renew passport, it's up next." in s, s)
        assertTrue(s.endsWith(BriefScriptRules.CLOSE), s)
        assertEquals(s, fold.talkReadOut(ReadOut(ReadOut.Kind.BRIEF)))
    }
}
