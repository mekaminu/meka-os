package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ▶ Play on the urgent voice-message alert (call assistant polish 8c). */
class UrgentPlayTest {
    private val now = 1_791_396_000_000L
    private val idA = "h0123456789abcdef"
    private val idB = "h1111111111111111"
    private val idC = "h2222222222222222"
    private val idD = "h3333333333333333"

    private fun voice(id: String, who: String, at: Long, urgent: Boolean = true, audio: Boolean = true, text: String? = null) =
        CapturedItem(id, CaptureApp.PHONE, CaptureKind.VOICE_MESSAGE, who, text, null, at, urgent = urgent, hasAudio = audio)

    @Test
    fun anAlertGainsPlayOnceItsRecordingArrivesAndOnlyOnce() {
        val items = listOf(
            voice(idA, "07700 900111", now - 3 * 60_000),                       // alerted, recording now kept
            voice(idB, "07700 900222", now - 3 * 60_000, audio = false),        // still transcribing: no Play yet
            voice(idC, "07700 900333", now - 2 * 60 * 60_000),                  // too long ago to touch the alert
            voice(idD, "07700 900444", now - 60_000, urgent = false),           // not urgent: never alerted
        )
        val alerted = setOf(idA, idB, idC)
        assertEquals(listOf(idA), CallAssistantRules.toAddPlay(items, alerted, emptySet(), now).map { it.id })
        assertTrue(CallAssistantRules.toAddPlay(items, alerted, setOf(idA), now).isEmpty())
        // Never alerted (the phone was off, say): toAlert posts it, with Play straight away, rather than this.
        assertTrue(CallAssistantRules.toAddPlay(items, emptySet(), emptySet(), now).isEmpty())
        // An urgent message whose words said so counts the same.
        val byWords = voice(idB, "07700 900222", now - 60_000, urgent = false, text = "It's an emergency, call me")
        assertEquals(listOf(idB), CallAssistantRules.toAddPlay(listOf(byWords), setOf(idB), emptySet(), now).map { it.id })
    }

    @Test
    fun thePlayActionOpensTheSummaryOnThatMessage() {
        assertEquals("play:$idA", CallAssistantRules.openPlay(idA))
        assertEquals(idA, CallAssistantRules.playFromOpen(CallAssistantRules.openPlay(idA)))
        assertNull(CallAssistantRules.playFromOpen("play:../../etc"))
        assertNull(CallAssistantRules.playFromOpen("after_work"))
        assertNull(CallAssistantRules.playFromOpen(null))

        val summary = AfterWorkSummaries.build(
            listOf(voice(idA, "07700 900111", now - 60_000), voice(idB, "Mum", now - 30_000, audio = false)),
            PeopleLists(),
        )
        assertEquals("07700 900111", CallAssistantRules.personWith(summary, idA)?.personName)
        // No recording to play (or Done cleared it): nothing is unfolded.
        assertNull(CallAssistantRules.personWith(summary, idB))
        assertNull(CallAssistantRules.personWith(summary, idC))
        assertNull(CallAssistantRules.personWith(summary, null))
    }
}
