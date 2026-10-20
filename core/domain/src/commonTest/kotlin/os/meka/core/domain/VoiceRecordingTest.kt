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
        // Done: gone from the summary (Activity → Calls keeps it; see doneKeepsACallsRecording…).
        held.clear()
        assertTrue(held.items().isEmpty())
    }

    @Test
    fun playableOnlyForAVoiceMessageNotGoneAndUnderSevenDaysOld() {
        val r = VoiceRecordingRules
        assertEquals(7L * 24 * 60 * 60_000L, r.KEEP_MS)
        assertTrue(r.playable(CaptureKind.VOICE_MESSAGE, audio = true, gone = false, atMs = now, nowMs = now))
        assertTrue(r.playable(CaptureKind.VOICE_MESSAGE, true, false, now - r.KEEP_MS + 1, now))
        assertFalse(r.playable(CaptureKind.VOICE_MESSAGE, true, false, now - r.KEEP_MS, now))
        assertFalse(r.playable(CaptureKind.VOICE_MESSAGE, true, gone = true, atMs = now, nowMs = now))
        assertFalse(r.playable(CaptureKind.VOICE_MESSAGE, audio = false, gone = false, atMs = now, nowMs = now))
        assertFalse(r.playable(CaptureKind.MISSED_CALL, true, false, now, now))
        assertFalse(r.playable(null, true, false, now, now))
        assertFalse(r.playable(CaptureKind.VOICE_MESSAGE, true, false, null, now))
        assertFalse(r.playable(CaptureKind.VOICE_MESSAGE, true, false, now + 60_000, now)) // from the future
        // Gone: Delete recording, or a Done from before 2026-10-20 (no keptOnDone); a newer Done keeps it.
        assertFalse(r.gone(cleared = true, keptOnDone = true, deleted = false))
        assertTrue(r.gone(cleared = true, keptOnDone = false, deleted = false))
        assertTrue(r.gone(cleared = false, keptOnDone = false, deleted = true))
        assertFalse(r.gone(cleared = false, keptOnDone = false, deleted = false))
    }

    @Test
    fun doneKeepsACallsRecordingAndWordsButBlanksChats() {
        serverWrites("CA1", audio = true)
        val id = CallAssistantRules.heldId("twilio", "CA1")
        replica.applyRemoteBatch(listOf(Op("s${seq++}", "hh", EntityTypes.HELD_MESSAGE, id, HeldMessageFields.TEXT, "Your MOT is due".fv(), Hlc(now, seq, "server"), emptyList(), "server")))
        held.hold(listOf(CapturedItem("w1", CaptureApp.WHATSAPP, CaptureKind.MESSAGE, "Tunde", "hi", null, now)))
        assertEquals(2, held.clear())
        // Needs you is empty, but Activity → Calls keeps the words and ▶ Listen.
        assertTrue(held.items().isEmpty())
        val call = held.calls().single()
        assertTrue(call.cleared && call.keptOnDone && !call.wordsGone)
        assertTrue(call.item.hasAudio)
        assertEquals("Your MOT is due", call.item.text)
        val detail = CallLogRules.view(held.calls(), BlockedCallersView.EMPTY, now, LocalCalendar.UTC).row(id)!!.detail
        assertTrue(detail.listen != null)
        assertEquals(VoiceRecordingRules.DELETE, detail.deleteRecording)
        assertEquals("“Your MOT is due”", detail.words)
        // The chat message's text is blanked as before.
        val chat = replica.entity(EntityTypes.HELD_MESSAGE, HeldMessages.entityId("w1"))!!
        assertNull(chat[HeldMessageFields.TEXT].textOrNull)
        // Seven days after the call it's gone from the log and nothing can play.
        now += VoiceRecordingRules.KEEP_MS + 1
        assertTrue(held.calls().isEmpty())
    }

    @Test
    fun deleteRecordingRemovesOnlyTheRecording() {
        serverWrites("CA1", audio = true)
        serverWrites("CA2", audio = false)
        val id = CallAssistantRules.heldId("twilio", "CA1")
        assertFalse(held.deleteRecording(CallAssistantRules.heldId("twilio", "CA2"))) // nothing to delete
        assertTrue(held.deleteRecording(id))
        assertFalse(held.deleteRecording(id)) // once
        val call = held.calls().first { it.item.id == id }
        assertTrue(call.recordingDeleted)
        assertFalse(call.item.hasAudio)
        val detail = CallLogRules.view(held.calls(), BlockedCallersView.EMPTY, now, LocalCalendar.UTC).row(id)!!.detail
        assertNull(detail.listen)
        assertNull(detail.deleteRecording)
        assertEquals(VoiceRecordingRules.DELETED, detail.recordingGone)
        // Still on Needs you until Done; just nothing to play.
        assertFalse(held.items().first { it.id == id }.hasAudio)
    }

    @Test
    fun aCallClearedByAnOlderDoneSaysRecordingDeleted() {
        serverWrites("CA1", audio = true)
        val id = CallAssistantRules.heldId("twilio", "CA1")
        // What an app from before 2026-10-20 wrote on Done: cleared, words blanked, no keptOnDone.
        replica.commitLocal(EntityTypes.HELD_MESSAGE, id, mapOf(HeldMessageFields.CLEARED to true.fv(), HeldMessageFields.TEXT to os.meka.core.sync.FieldValue.Null))
        val call = held.calls().single()
        assertTrue(call.wordsGone)
        assertFalse(call.item.hasAudio)
        val detail = CallLogRules.view(held.calls(), BlockedCallersView.EMPTY, now, LocalCalendar.UTC).row(id)!!.detail
        assertEquals("Recording deleted", detail.words)
        assertNull(detail.listen)
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
        assertEquals(listOf(7, 0), r.KEEP_CHOICES)
        assertEquals(listOf("7 days", "Don't keep"), r.KEEP_CHOICES.map(r::choiceLabel))
        // Absent, the old 30 or anything unexpected reads as the default, 7 days (the call log's window).
        assertEquals(7, r.keepDays(null))
        assertEquals(7, r.keepDays(30))
        assertEquals(7, r.keepDays(12))
        assertEquals(7, r.keepDays(7))
        assertEquals(0, r.keepDays(0))
        assertEquals(r.KEEP_MS, r.keepMs(7))
        assertEquals(r.KEEP_MS, r.keepMs(30))
        assertEquals(r.PRIVACY, r.privacy(7))
        assertTrue(r.privacy(7).contains("for 7 days"))
        assertTrue(r.privacy(0).contains("aren't kept"))
        assertTrue(r.settingLine(7).startsWith("Kept 7 days"))
        assertTrue(r.settingLine(0).startsWith("Not kept"))
        val day = 24 * 60 * 60_000L
        assertTrue(r.playable(CaptureKind.VOICE_MESSAGE, true, false, now - 7 * day + 1, now, keepDays = 7))
        assertFalse(r.playable(CaptureKind.VOICE_MESSAGE, true, false, now - 7 * day, now, keepDays = 7))
        assertFalse(r.playable(CaptureKind.VOICE_MESSAGE, true, false, now, now, keepDays = 0))
        // The server's sweep: older than 7 days, or (don't keep) anything not still being transcribed.
        assertEquals(now - 7 * day, r.sweepBeforeMs(7, now))
        assertEquals(now - r.UNKEPT_GRACE_MS, r.sweepBeforeMs(0, now))
    }

    @Test
    fun theChoiceSyncsWithWorkModeAndTheSummaryFollowsIt() {
        val work = WorkMode(replica, nowMs = { now })
        assertEquals(7, work.recordingDays())
        // The summary and the call log keep a message 7 days ([HeldMessages.RETENTION_MS]), the same as recordings.
        serverWrites("CA1", audio = true, atMs = now - 6 * 24 * 60 * 60_000L)
        serverWrites("CA2", audio = true)
        fun playable() = held.items().filter { it.hasAudio }.map { it.id }.toSet()
        assertEquals(setOf(CallAssistantRules.heldId("twilio", "CA1"), CallAssistantRules.heldId("twilio", "CA2")), playable())
        assertTrue(work.setRecordingDays(0))
        assertFalse(work.setRecordingDays(0)) // nothing changes the second time
        assertTrue(playable().isEmpty())
        assertEquals(0, work.state(LocalClock(1, 600)).recordingDays)
        assertTrue(work.setRecordingDays(7))
        assertEquals(7, work.recordingDays())
        assertEquals(2, playable().size)
        kotlin.test.assertFailsWith<IllegalArgumentException> { work.setRecordingDays(30) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { work.setRecordingDays(14) }
    }
}
