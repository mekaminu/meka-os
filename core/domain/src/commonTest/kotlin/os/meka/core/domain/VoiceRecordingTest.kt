package os.meka.core.domain

import os.meka.core.sync.HlcClock
import os.meka.core.sync.Hlc
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.Op
import os.meka.core.sync.Replica
import os.meka.core.sync.fv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Callers' recordings (call assistant polish 8c): when ▶ Play is offered, where a recording lives, the player's line. */
class VoiceRecordingTest {
    private var now = 1_791_450_000_000L // 2026-10-08 09:00 UTC
    private var n = 0
    private val replica = Replica("hh", "fold", HlcClock("fold", { now }), InMemoryReplicaStore(), MekaSchema) { "f${n++}" }
    private val held = HeldMessages(replica) { now }
    private var seq = 0

    /** What MEKA's server writes for a message left on [call] (device `server`). */
    private fun serverWrites(call: String, audio: Boolean, atMs: Long = now) {
        val id = CallAssistantRules.heldId("twilio", call)
        val fields = CallAssistantRules.messageFields("+447700900123", atMs) +
            (if (audio) mapOf(HeldMessageFields.AUDIO to true.fv()) else emptyMap())
        replica.applyRemoteBatch(
            fields.map { (f, v) -> Op("s${seq++}", "hh", EntityTypes.HELD_MESSAGE, id, f, v, Hlc(now, seq, "server"), emptyList(), "server") },
        )
    }

    @Test
    fun aKeptRecordingIsOfferedUntilDoneAndNeverForOtherKinds() {
        serverWrites("CA1", audio = true)
        serverWrites("CA2", audio = false)
        val items = held.items().associateBy { it.id }
        assertTrue(items.getValue(CallAssistantRules.heldId("twilio", "CA1")).hasAudio)
        assertFalse(items.getValue(CallAssistantRules.heldId("twilio", "CA2")).hasAudio)
        // A message the Fold held itself never has a recording.
        held.hold(listOf(CapturedItem("w1", CaptureApp.WHATSAPP, CaptureKind.MESSAGE, "Tunde", "hi", null, now)))
        assertFalse(held.items().first { it.app == CaptureApp.WHATSAPP }.hasAudio)
        // The summary keeps it through the people lists and the Fold's names.
        val p = held.summary().withLists(PeopleLists(), CallerNames(contactName = { "Garage" })).people
        assertTrue(p.flatMap { it.items }.single { it.id == CallAssistantRules.heldId("twilio", "CA1") }.hasAudio)
        // Done: gone from the summary, and nothing left to play.
        held.clear()
        assertTrue(held.items().isEmpty())
    }

    @Test
    fun playableOnlyForAVoiceMessageNotClearedAndUnderThirtyDaysOld() {
        val r = VoiceRecordingRules
        assertTrue(r.playable(CaptureKind.VOICE_MESSAGE, audio = true, cleared = false, atMs = now, nowMs = now))
        assertTrue(r.playable(CaptureKind.VOICE_MESSAGE, true, false, now - r.KEEP_MS + 1, now))
        assertFalse(r.playable(CaptureKind.VOICE_MESSAGE, true, false, now - r.KEEP_MS, now))
        assertFalse(r.playable(CaptureKind.VOICE_MESSAGE, true, cleared = true, atMs = now, nowMs = now))
        assertFalse(r.playable(CaptureKind.VOICE_MESSAGE, audio = false, cleared = false, atMs = now, nowMs = now))
        assertFalse(r.playable(CaptureKind.MISSED_CALL, true, false, now, now))
        assertFalse(r.playable(null, true, false, now, now))
        assertFalse(r.playable(CaptureKind.VOICE_MESSAGE, true, false, null, now))
        assertFalse(r.playable(CaptureKind.VOICE_MESSAGE, true, false, now + 60_000, now)) // from the future
    }

    @Test
    fun aRecordingsKeyIsMadeOnlyFromAHouseholdAndAHeldId() {
        val id = CallAssistantRules.heldId("twilio", "CA1")
        assertTrue(VoiceRecordingRules.isHeldId(id))
        assertEquals("voice/hh/$id.mp3", VoiceRecordingRules.key("hh", id))
        assertTrue(VoiceRecordingRules.key("hh", id)!!.startsWith(VoiceRecordingRules.PREFIX))
        assertNull(VoiceRecordingRules.key("hh", "../h0000000000000000"))
        assertNull(VoiceRecordingRules.key("hh/other", id))
        assertNull(VoiceRecordingRules.key("", id))
        assertNull(VoiceRecordingRules.key("hh", "hXYZ"))
        assertFalse(VoiceRecordingRules.isHeldId("h" + "0".repeat(15)))
        assertEquals("voice/hh/", VoiceRecordingRules.householdPrefix("hh"))
        assertNull(VoiceRecordingRules.householdPrefix("hh/other"))
        assertNull(VoiceRecordingRules.householdPrefix(""))
    }

    @Test
    fun thePlayersLineAndBar() {
        assertEquals("0:12 / 0:40", VoiceRecordingRules.progressLine(12_400, 40_000))
        assertEquals("1:05 / 2:00", VoiceRecordingRules.progressLine(65_000, 120_000))
        assertEquals("0:03", VoiceRecordingRules.progressLine(3_000, 0))
        assertEquals("0:00 / 0:40", VoiceRecordingRules.progressLine(-5, 40_000))
        assertEquals(0.5f, VoiceRecordingRules.fraction(20_000, 40_000))
        assertEquals(1f, VoiceRecordingRules.fraction(50_000, 40_000))
        assertEquals(0f, VoiceRecordingRules.fraction(5_000, 0))
    }

    @Test
    fun keepCallersRecordingsChoosesHowLongPlayIsOffered() {
        val r = VoiceRecordingRules
        assertEquals(listOf(7, 30, 0), r.KEEP_CHOICES)
        assertEquals(listOf("7 days", "30 days", "Don't keep"), r.KEEP_CHOICES.map(r::choiceLabel))
        // Absent or anything unexpected reads as the default, 30 days.
        assertEquals(30, r.keepDays(null))
        assertEquals(30, r.keepDays(30))
        assertEquals(30, r.keepDays(12))
        assertEquals(7, r.keepDays(7))
        assertEquals(0, r.keepDays(0))
        assertEquals(r.KEEP_MS, r.keepMs(30))
        assertEquals(r.PRIVACY, r.privacy(30))
        assertTrue(r.privacy(7).contains("for 7 days"))
        assertTrue(r.privacy(0).contains("aren't kept"))
        assertTrue(r.settingLine(7).startsWith("Kept 7 days"))
        assertTrue(r.settingLine(0).startsWith("Not kept"))
        val day = 24 * 60 * 60_000L
        assertTrue(r.playable(CaptureKind.VOICE_MESSAGE, true, false, now - 7 * day + 1, now, keepDays = 7))
        assertFalse(r.playable(CaptureKind.VOICE_MESSAGE, true, false, now - 7 * day, now, keepDays = 7))
        assertFalse(r.playable(CaptureKind.VOICE_MESSAGE, true, false, now, now, keepDays = 0))
        // The server's sweep: older than the choice, or (don't keep) anything not still being transcribed.
        assertEquals(now - 7 * day, r.sweepBeforeMs(7, now))
        assertEquals(now - r.KEEP_MS, r.sweepBeforeMs(30, now))
        assertEquals(now - r.UNKEPT_GRACE_MS, r.sweepBeforeMs(0, now))
    }

    @Test
    fun theChoiceSyncsWithWorkModeAndTheSummaryFollowsIt() {
        val work = WorkMode(replica, nowMs = { now })
        assertEquals(30, work.recordingDays())
        // The summary keeps a message 7 days ([HeldMessages.RETENTION_MS]), so 7 or 30 days look alike here; the
        // server's sweep is where 7 days differs (backend VoiceRecordingsTest).
        serverWrites("CA1", audio = true, atMs = now - 6 * 24 * 60 * 60_000L)
        serverWrites("CA2", audio = true)
        fun playable() = held.items().filter { it.hasAudio }.map { it.id }.toSet()
        assertEquals(setOf(CallAssistantRules.heldId("twilio", "CA1"), CallAssistantRules.heldId("twilio", "CA2")), playable())
        assertTrue(work.setRecordingDays(7))
        assertFalse(work.setRecordingDays(7)) // nothing changes the second time
        assertEquals(7, work.recordingDays())
        assertEquals(2, playable().size)
        assertTrue(work.setRecordingDays(0))
        assertTrue(playable().isEmpty())
        assertEquals(0, work.state(LocalClock(1, 600)).recordingDays)
        assertTrue(work.setRecordingDays(30))
        assertEquals(2, playable().size)
        kotlin.test.assertFailsWith<IllegalArgumentException> { work.setRecordingDays(14) }
    }
}
