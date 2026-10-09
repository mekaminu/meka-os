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
}
