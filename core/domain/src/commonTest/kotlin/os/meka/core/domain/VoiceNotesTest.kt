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
        // A device that doesn't record them leaves it a question; the Mac records them since slice 3.
        assertNull(TalkOpenRules.request("take a voice note", TalkOpenRules.DEFAULT))
        assertEquals(TalkPlace.VOICE_NOTE, TalkOpenRules.request("take a voice note", TalkOpenRules.MAC))
        assertNull(TalkOpenRules.request("where did I park", TalkOpenRules.MAC))
        assertNull(TalkOpenRules.request("bedside clock", TalkOpenRules.MAC))
        assertNull(TalkOpenRules.request("read my last voice note", TalkOpenRules.FOLD))
        assertNull(TalkOpenRules.request("note", TalkOpenRules.FOLD))
        assertEquals(VoiceNoteRules.OPENING, TalkOpenRules.opening(TalkPlace.VOICE_NOTE))
        assertEquals(VoiceNoteRules.OPENED, TalkOpenRules.remembered(TalkPlace.VOICE_NOTE))
    }

    @Test
    fun slice2TheTidyNoteAndPeopleAreKeptAndShown() {
        val words = "Um so ring the garage and uh buy milk."
        // The model's tidy note: control characters out, blank runs folded; repeating the words is no tidy note.
        assertEquals("Ring the garage.\n\nBuy milk.", VoiceNoteRules.tidyNote("Ring the garage.\u0007\n\n\n\nBuy  milk.", words))
        assertEquals("", VoiceNoteRules.tidyNote(words, words))
        assertEquals("", VoiceNoteRules.tidyNote(null, words))
        val long = VoiceNoteRules.tidyNote("word ".repeat(400), words)
        assertTrue(long.length <= VoiceNoteRules.MAX_TIDY + 1 && long.endsWith("…"))
        // People: no numbers, addresses or links; no repeats.
        assertEquals(listOf("Sam", "Mum"), VoiceNoteRules.people(listOf(" Sam,", "sam", "07700 900123", "a@b.c", "Mum", "http://x")))
        assertNull(VoiceNoteRules.peopleLine(emptyList()))
        assertEquals("Mentions Sam", VoiceNoteRules.peopleLine(listOf("Sam")))
        assertEquals("Mentions Sam, Alex and Mum", VoiceNoteRules.peopleLine(listOf("Sam", "Alex", "Mum")))
        assertEquals("Tidied · 2 things to add", VoiceNoteRules.tidiedLine(2))
        // The index keeps them (tabs and line breaks too); a slice-1 line of 7 fields still reads.
        val n = note(transcript = words).copy(tidy = "Ring the garage.\n\tBuy milk.", people = listOf("Sam", "Alex"), cards = 2)
        assertEquals(listOf(n), VoiceNoteRules.decode(VoiceNoteRules.encode(listOf(n))))
        val old = "vn1\t$at\t83000\theard\tBuy milk.\t0\t"
        assertEquals(listOf(note(transcript = "Buy milk.")), VoiceNoteRules.decode(old))
        // The row reads the tidy note, keeps his words behind "Your words", and names the people.
        val row = VoiceNoteRules.row(n, day, 14 * 60 + 2, day + 30, day)
        assertEquals("Ring the garage.\n\tBuy milk.", row.text)
        assertEquals(words, row.words)
        assertEquals("Mentions Sam and Alex", row.peopleLine)
        val plain = VoiceNoteRules.row(note(), day, 14 * 60 + 2, day + 30, day)
        assertNull(plain.words)
        assertNull(plain.peopleLine)
        // Only these kinds come from a note to himself.
        val raw = listOf("task", "reply", "booking", "idea", "work_from_home").map { RawRequestProposal(it, "x") }
        assertEquals(listOf("task", "idea"), VoiceNoteRules.allowed(raw).map { it.kind })
    }

    @Test
    fun slice2AnIdeaIsOnlyForVoiceNotesAndGoesToSomeday() {
        val raw = listOf(RawRequestProposal("idea", "family film night on Fridays", date = "2026-10-16", time = "18:00"))
        assertTrue(MessageRequestRules.check(raw, day, 600).isEmpty())
        val p = MessageRequestRules.check(raw, day, 600, allowIdea = true).single()
        // No day or time, whatever the model wrote.
        assertEquals(RequestProposal(RequestKind.IDEA, "Family film night on Fridays", null, null), p)
        assertEquals("Add to Ideas: Family film night on Fridays", MessageRequestRules.actionLine(p, day))
        assertEquals(MessageRequestRules.IDEA_DETAIL, MessageRequestRules.detailLine(p))
        val plan = RequestAcceptRules.plan(p, canAddEvent = true)
        assertEquals(RequestPlan.AddIdea("Family film night on Fridays"), plan)
        assertNull(RequestAcceptRules.plan(p, canAddEvent = true, change = true))
        assertEquals("Kept “Family film night on Fridays” in Someday · Ideas", RequestAcceptRules.doneLine(plan!!, RequestKind.IDEA, day))
    }

    @Test
    fun slice3TheMacSaysThisMacAndKeepsTheSameNotes() {
        // A note just made: its words from the segments, or why there are none.
        val made = VoiceNoteRules.made("vn2", at, 42_000, listOf("ring the garage", "buy milk"), failed = false)
        assertEquals(VoiceNote("vn2", at, 42_000, VoiceNoteWords.HEARD, "Ring the garage. Buy milk."), made)
        assertEquals(VoiceNoteWords.FAILED, VoiceNoteRules.made("vn3", at, 5_000, emptyList(), failed = true).words)
        assertEquals(VoiceNoteWords.NOTHING, VoiceNoteRules.made("vn4", at, 5_000, listOf(" "), failed = false).words)
        // The Mac's lines when there are no words.
        assertNull(VoiceNoteRules.noWordsLineMac(VoiceNoteWords.HEARD, noOnDevice = true))
        assertEquals(VoiceNoteRules.MAC_NO_ON_DEVICE, VoiceNoteRules.noWordsLineMac(VoiceNoteWords.FAILED, noOnDevice = true))
        assertEquals(VoiceNoteRules.NOTHING_HEARD, VoiceNoteRules.noWordsLineMac(VoiceNoteWords.NOTHING, noOnDevice = false))
        assertEquals(VoiceNoteRules.MAC_FAILED, VoiceNoteRules.noWordsLineMac(VoiceNoteWords.FAILED, noOnDevice = false))
        assertTrue("phone" !in VoiceNoteRules.MAC_FAILED + VoiceNoteRules.MAC_NOTE + VoiceNoteRules.MAC_EMPTY + VoiceNoteRules.MAC_NO_ON_DEVICE)
        // Rows and the summary say "this Mac".
        val failed = note(words = VoiceNoteWords.FAILED, transcript = "")
        assertEquals("Not turned into words on this Mac", VoiceNoteRules.row(failed, day, 600, day + 30, day, onMac = true).text)
        assertEquals("Not turned into words on this phone", VoiceNoteRules.row(failed, day, 600, day + 30, day).text)
        assertEquals(VoiceNoteRules.MAC_EMPTY, VoiceNoteRules.summary(emptyList(), onMac = true))
        assertEquals(VoiceNoteRules.EMPTY, VoiceNoteRules.summary(emptyList()))
        // Keep, and what the AI's answer changes.
        assertTrue(VoiceNoteRules.toggledKeep(note()).kept)
        assertFalse(VoiceNoteRules.toggledKeep(note(kept = true)).kept)
        assertNull(VoiceNoteRules.tidied(note(), -1, "", listOf("Sam")))
        assertEquals(note(cards = 2).copy(tidy = "Ring the garage.", people = listOf("Sam")),
            VoiceNoteRules.tidied(note(), 2, "Ring the garage.", listOf("Sam")))
        // Offline but tidied earlier: the count stays unknown, the tidy note and people stay.
        val earlier = note(cards = 1).copy(tidy = "Earlier.", people = listOf("Alex"))
        assertEquals(earlier.copy(cards = 0), VoiceNoteRules.tidied(earlier, 0, "", emptyList()))
        assertEquals(earlier.copy(tidy = "New."), VoiceNoteRules.tidied(earlier, -1, "New.", emptyList()))
        // The Mac's index is the phone's format.
        val two = listOf(note(), made)
        assertEquals(VoiceNoteRules.encode(two), VoiceNoteRules.encodeList(two))
        assertEquals(VoiceNoteRules.ordered(two), VoiceNoteRules.decode(VoiceNoteRules.encodeList(two)))
    }
}
