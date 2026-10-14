package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceNotesTest {
    private val day = 20_740L // Wed 14 Oct 2026
    private val dayMs = 86_400_000L
    private val at = day * dayMs + (14 * 60 + 2) * 60_000L

    private fun note(id: String = "vn1", atMs: Long = at, words: VoiceNoteWords = VoiceNoteWords.HEARD,
                     transcript: String = "Ring the garage about the service. Book the dentist for Sam next week.",
                     kept: Boolean = false, cards: Int? = null, durationMs: Long = 83_000L) =
        VoiceNote(id, atMs, durationMs, words, transcript, kept, cards)

    @Test
    fun recordingLineCountsAndWarnsBeforeTheLimit() {
        assertEquals("0:00", VoiceNoteRules.elapsed(0))
        assertEquals("0:42", VoiceNoteRules.elapsed(42_400))
        assertEquals("12:05", VoiceNoteRules.elapsed(725_000))
        assertEquals("Recording · 1:23", VoiceNoteRules.recordingLine(83_000, paused = false))
        assertEquals("Paused · 1:23", VoiceNoteRules.recordingLine(83_000, paused = true))
        assertEquals("Recording · 4:31 · stops at 5:00", VoiceNoteRules.recordingLine(271_000, paused = false))
        assertFalse(VoiceNoteRules.atLimit(299_999))
        assertTrue(VoiceNoteRules.atLimit(300_000))
        assertFalse(VoiceNoteRules.longEnough(600))
        assertTrue(VoiceNoteRules.longEnough(1_000))
    }

    @Test
    fun transcriptJoinsSegmentsIntoSentences() {
        assertEquals(
            "Ring the garage about the service. Book the dentist next week? Milk.",
            VoiceNoteRules.transcript(listOf("  ring the   garage about the service ", "", "book the dentist next week?", "milk")),
        )
        assertEquals("", VoiceNoteRules.transcript(listOf(" ", "")))
        assertEquals(VoiceNoteWords.HEARD, VoiceNoteRules.words(listOf("milk"), failed = true))
        assertEquals(VoiceNoteWords.NOTHING, VoiceNoteRules.words(listOf(""), failed = false))
        assertEquals(VoiceNoteWords.FAILED, VoiceNoteRules.words(emptyList(), failed = true))
    }

    @Test
    fun onlyHeardWordsGoToHandleThis() {
        assertTrue(VoiceNoteRules.worthAsking(note()))
        assertFalse(VoiceNoteRules.worthAsking(note(transcript = "Milk.")))
        assertFalse(VoiceNoteRules.worthAsking(note(words = VoiceNoteWords.FAILED, transcript = "")))
        assertEquals("Voice note · 14:02", VoiceNoteRules.subject(14 * 60 + 2))
        assertNull(VoiceNoteRules.noWordsLine(VoiceNoteWords.HEARD, oldAndroid = false))
        assertEquals(VoiceNoteRules.NOTHING_HEARD, VoiceNoteRules.noWordsLine(VoiceNoteWords.NOTHING, oldAndroid = false))
        assertEquals(VoiceNoteRules.FAILED, VoiceNoteRules.noWordsLine(VoiceNoteWords.FAILED, oldAndroid = false))
        assertEquals(VoiceNoteRules.OLD_ANDROID, VoiceNoteRules.noWordsLine(VoiceNoteWords.FAILED, oldAndroid = true))
    }

    @Test
    fun notesGoAfterThirtyDaysUnlessKept() {
        val old = note("vnOld", atMs = at - 30 * dayMs)
        val kept = note("vnKept", atMs = at - 40 * dayMs, kept = true)
        val young = note("vnYoung", atMs = at - 29 * dayMs)
        assertEquals(listOf(old), VoiceNoteRules.expired(listOf(old, kept, young), at))
        assertNull(VoiceNoteRules.expiresAtMs(kept))
        assertEquals(at + 30 * dayMs, VoiceNoteRules.expiresAtMs(note()))
    }

    @Test
    fun filesNoNoteOwnsAreTidiedAway() {
        val notes = listOf(note("vnA"), note("vnB"))
        assertEquals(
            listOf("vnC.m4a.enc", "vnA.part"),
            VoiceNoteRules.orphans(listOf("index.enc", "vnA.m4a.enc", "vnB.m4a.enc", "vnC.m4a.enc", "vnA.part"), notes),
        )
    }

    @Test
    fun indexRoundTripsWordsWithTabsAndLineBreaks() {
        val tricky = note("vnT", transcript = "Line one\twith a tab\nline two \\ done", kept = true, cards = 2)
        val plain = note("vnP", atMs = at + 1, words = VoiceNoteWords.FAILED, transcript = "")
        val back = VoiceNoteRules.decode(VoiceNoteRules.encode(listOf(tricky, plain)))
        assertEquals(listOf(plain, tricky), back) // newest first
        assertEquals(emptyList(), VoiceNoteRules.decode(null))
        assertEquals(emptyList(), VoiceNoteRules.decode("garbage\tline"))
        // The same id twice keeps the first (newest).
        assertEquals(1, VoiceNoteRules.decode(VoiceNoteRules.encode(listOf(tricky)) + "\n" + VoiceNoteRules.encode(listOf(tricky))).size)
        assertEquals(listOf("vnT"), VoiceNoteRules.upsert(listOf(tricky), tricky.copy(kept = false)).map { it.id })
        assertFalse(VoiceNoteRules.upsert(listOf(tricky), tricky.copy(kept = false)).single().kept)
    }

    @Test
    fun paneRowsSayWhenAndWhatWasHeard() {
        val r = VoiceNoteRules.row(note(), day, 14 * 60 + 2, day + 30, today = day)
        assertEquals("Today 14:02 · 1:23", r.title)
        assertEquals("Kept until Fri 13 Nov", r.keepLine)
        assertTrue(r.heard)
        assertTrue(r.canMakeCards)
        assertNull(r.cardsLine)
        val answered = VoiceNoteRules.row(note(cards = 2, kept = true), day - 1, 8 * 60 + 5, null, today = day)
        assertEquals("Yesterday 08:05 · 1:23", answered.title)
        assertEquals("Kept · Navilife won't delete it", answered.keepLine)
        assertEquals("2 cards offered · in Needs you until you answer them", answered.cardsLine)
        assertFalse(answered.canMakeCards)
        assertEquals("Nothing to add in it", VoiceNoteRules.row(note(cards = 0), day, 0, day + 30, day).cardsLine)
        val failed = VoiceNoteRules.row(note(words = VoiceNoteWords.FAILED, transcript = ""), day - 3, 600, day + 27, day)
        assertEquals("Sun 11 Oct 10:00 · 1:23", failed.title)
        assertEquals("Not turned into words on this phone", failed.text)
        assertFalse(failed.heard)
        assertFalse(failed.canMakeCards)
        assertEquals(VoiceNoteRules.EMPTY, VoiceNoteRules.summary(emptyList()))
        assertEquals("2 notes · 2:46 recorded", VoiceNoteRules.summary(listOf(note("a"), note("b"))))
    }

    @Test
    fun dailyRowSaysHowManyAndLightsTodays() {
        assertNull(DailyRules.voiceNotes(0, null, day))
        assertEquals(DailyLine("2 notes · newest today", true), DailyRules.voiceNotes(2, day, day))
        assertEquals(DailyLine("1 note · newest yesterday", false), DailyRules.voiceNotes(1, day - 1, day))
        assertEquals(DailyLine("3 notes", false), DailyRules.voiceNotes(3, day - 5, day))
    }

    @Test
    fun talkStartsAVoiceNoteOnTheFold() {
        listOf(
            "take a voice note", "ok Navilife, take a voice note please", "record a note", "I want to record a voice memo",
            "start a new voice note", "voice note", "new note", "take a note",
        ).forEach { assertEquals(TalkPlace.VOICE_NOTE, TalkOpenRules.request(it, TalkOpenRules.FOLD), it) }
        // The Mac doesn't record them yet; more words make it a question.
        assertNull(TalkOpenRules.request("take a voice note", TalkOpenRules.DEFAULT))
        assertNull(TalkOpenRules.request("read my last voice note", TalkOpenRules.FOLD))
        assertNull(TalkOpenRules.request("note", TalkOpenRules.FOLD))
        assertEquals(VoiceNoteRules.OPENING, TalkOpenRules.opening(TalkPlace.VOICE_NOTE))
        assertEquals(VoiceNoteRules.OPENED, TalkOpenRules.remembered(TalkPlace.VOICE_NOTE))
    }
}
