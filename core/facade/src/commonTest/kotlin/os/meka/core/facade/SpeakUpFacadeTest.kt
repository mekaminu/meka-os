package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AboutField
import os.meka.core.domain.GovernorResult
import os.meka.core.domain.GovernorState
import os.meka.core.domain.Notice
import os.meka.core.domain.NoticePrecision
import os.meka.core.domain.NoticeSource
import os.meka.core.domain.NoticeTarget
import os.meka.core.domain.NoticeTier
import os.meka.core.domain.SpeakMoment
import os.meka.core.domain.SpeakPlace
import os.meka.core.domain.SpeakUpRules
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Speaking up, slice 1, through the facade: one pick, logged once, the switch and Mute. */
class SpeakUpFacadeTest {
    private var now = 1_792_570_800_000L // Wed 21 Oct 2026, 09:20 in London
    private val ops = InMemoryServerOpStore()

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val train = Notice(
        key = "trainlive:1", source = NoticeSource.TRAINS, tier = NoticeTier.HEADS_UP, title = "South Rail 08:42 cancelled",
        text = "", atMs = now, target = NoticeTarget.TODAY, speak = SpeakMoment.TRAIN, spoken = "your 08:42 is cancelled.",
    )
    private val result = GovernorResult(listOf(train), null, GovernorState(), null, NoticePrecision.SOFT)

    @Test
    fun saidOnceAndInActivity() = runTest {
        val c = core()
        try {
            c.setAboutYou(AboutField.SHOWN_NAME, "Alex Smith")
            // The phone's speaker never says it; earbuds do, with his name, and Activity keeps it as Said aloud.
            assertNull(c.speakUp(result, listOf(train.key), SpeakPlace.SPEAKER, onCall = false, talking = false))
            val pick = c.speakUp(result, listOf(train.key), SpeakPlace.PRIVATE, onCall = false, talking = false)
            assertEquals("Alex, your 08:42 is cancelled.", pick?.line)
            // Counted from Activity's Said aloud entries (every device's).
            assertTrue(c.speakUpView().sections.any { "Said today: 1 of 6" in it.steps })
            // Once per notice, on any device.
            assertNull(c.speakUp(result, listOf(train.key), SpeakPlace.PRIVATE, onCall = false, talking = false))
        } finally { c.close() }
    }

    @Test
    fun switchOffAndMute() = runTest {
        val c = core()
        try {
            val off = c.speakUpAction(SpeakMoment.TRAIN.label)
            assertEquals("Off", off.sections.first { it.label == SpeakMoment.TRAIN.label }.status)
            assertNull(c.speakUp(result, listOf(train.key), SpeakPlace.PRIVATE, onCall = false, talking = false))
            c.speakUpAction(SpeakMoment.TRAIN.label)
            val muted = c.speakUpAction(SpeakUpRules.MUTE_LABEL)
            assertEquals("Muted till 11:20", muted.sections.last().status)
            assertNull(c.speakUp(result, listOf(train.key), SpeakPlace.PRIVATE, onCall = false, talking = false))
            now += SpeakUpRules.MUTE_MS
            assertEquals("Your 08:42 is cancelled.", c.speakUp(result, listOf(train.key), SpeakPlace.PRIVATE, onCall = false, talking = false)?.line)
        } finally { c.close() }
    }

    @Test
    fun urgentFromFamilySaidOnce() = runTest {
        val c = core()
        try {
            c.setAboutYou(AboutField.SHOWN_NAME, "Alex Smith")
            val item = os.meka.core.domain.CapturedItem(
                id = "w1", app = os.meka.core.domain.CaptureApp.WHATSAPP, kind = os.meka.core.domain.CaptureKind.MESSAGE,
                personName = "Sam", text = "Emergency, ring me", conversation = null, atMs = now - 30_000,
            )
            val lists = os.meka.core.domain.PeopleLists(family = setOf("Sam"))
            assertNull(c.speakUpUrgent(item, os.meka.core.domain.PeopleLists(), SpeakPlace.PRIVATE, onCall = false, talking = false))
            assertNull(c.speakUpUrgent(item, lists, SpeakPlace.PRIVATE, onCall = true, talking = false))
            assertEquals("Alex, Sam says it's urgent on WhatsApp: “Emergency, ring me.”",
                c.speakUpUrgent(item, lists, SpeakPlace.PRIVATE, onCall = false, talking = false)?.line)
            assertTrue(c.speakUpView().sections.any { "Said today: 1 of 6" in it.steps })
            // The alert re-posted (▶ Listen arriving) isn't said again; "shh" in Talk mutes the next one.
            assertNull(c.speakUpUrgent(item, lists, SpeakPlace.PRIVATE, onCall = false, talking = false))
            c.muteSpeakUp()
            assertNull(c.speakUpUrgent(item.copy(id = "w2"), lists, SpeakPlace.PRIVATE, onCall = false, talking = false))
        } finally { c.close() }
    }
}
