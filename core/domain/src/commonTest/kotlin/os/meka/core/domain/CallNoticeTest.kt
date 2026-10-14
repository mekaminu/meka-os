package os.meka.core.domain

import os.meka.core.sync.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Call notifications that actually tell him something, slice 1 (Meka 2026-10-14 18:38). Invented numbers only. */
class CallNoticeTest {
    private val t = 1_760_450_520_000L // 14:02 UTC
    private val cal = LocalCalendar.UTC

    private fun voice(
        text: String? = null, seconds: Int? = 24, urgent: Boolean = false, by: String? = null, name: String = "01632 960001",
        number: String? = "+441632960001", transcribing: Boolean = false, noTranscript: Boolean = false,
    ) = CapturedItem(
        id = "h1", app = CaptureApp.PHONE, kind = CaptureKind.VOICE_MESSAGE, personName = name, text = text, conversation = null,
        atMs = t, urgent = urgent, transcribing = transcribing, noTranscript = noTranscript, callerNumber = number,
        seconds = seconds, urgentBy = by,
    )

    @Test
    fun automatedCallsAreRecognisedAndNeverUrgent() {
        // What Meka heard: the whole "message" a keypad press or one number.
        val six = voice(text = "6", seconds = 2, urgent = true, by = CallNoticeRules.BY_KEYPAD)
        assertTrue(six.automated)
        assertFalse(six.isUrgent)
        assertTrue(voice(text = "One.", seconds = 8, urgent = true, by = CallNoticeRules.BY_SPEECH).automated)
        assertTrue(voice(text = "1 1", seconds = 8).automated)
        // Under 3 s, whatever was said.
        assertTrue(voice(text = null, seconds = 1, transcribing = true).automated)
        // Urgent pressed on the keypad with no words in the message.
        assertTrue(voice(text = null, seconds = 10, urgent = true, by = CallNoticeRules.BY_KEYPAD, noTranscript = true).automated)
        // A person: words, long enough; "urgent" by keypad counts once the words show they spoke.
        val dentist = voice(text = "Hi it's the dentist confirming Thursday at 3pm", urgent = true, by = CallNoticeRules.BY_KEYPAD)
        assertFalse(dentist.automated)
        assertTrue(dentist.isUrgent)
        // Keypad "urgent" waits for the words before it breaks through…
        val waiting = voice(text = null, urgent = true, by = CallNoticeRules.BY_KEYPAD, transcribing = true)
        assertFalse(waiting.automated)
        assertFalse(waiting.isUrgent)
        assertTrue(CallAssistantRules.toAlert(listOf(waiting), emptySet(), t + 60_000).isEmpty())
        // …a "yes" said aloud doesn't.
        assertTrue(voice(text = null, urgent = true, by = CallNoticeRules.BY_SPEECH, transcribing = true).isUrgent)
        // Messages from before the server noted length or answer behave as before (unless their words are a robocall's).
        assertTrue(voice(text = null, seconds = null, urgent = true, by = null, transcribing = true).isUrgent)
        assertFalse(voice(text = "6", seconds = null, urgent = true).isUrgent)
        assertEquals("Likely automated call · “6”", voice(text = "6", seconds = 2).displayLine)
        // A WhatsApp voice note's "text" is its length: never a call-assistant robocall.
        val note = voice(text = "0:12", seconds = null).copy(app = CaptureApp.WHATSAPP)
        assertFalse(note.automated)
        assertEquals("Voice message · “0:12”", note.displayLine)
    }

    @Test
    fun theNotificationSaysWhoWhatHappenedAndWhatItWasAbout() {
        val n = CallNoticeRules.voiceNotice(
            voice(text = "Hi it's the dentist confirming Thursday at 3pm, it's urgent", urgent = true, by = CallNoticeRules.BY_SPEECH),
            cal, canPlay = true,
        )
        assertEquals("01632 960001 · not in your contacts", n.title)
        assertEquals("Sent to your call assistant · 14:02 · left a 0:24 message", n.text)
        assertEquals("Said it's urgent", n.sub)
        assertEquals(
            "Sent to your call assistant · 14:02 · left a 0:24 message\nSaid it's urgent · “Hi it's the dentist confirming Thursday at 3pm, it's urgent”\n" +
                "Not blocked · it will ring again",
            n.big,
        )
        assertEquals("▶ Listen 0:24", n.listen)
        assertEquals("Call back", n.callBack)
        assertEquals("Block", n.block)

        // A contact: by name, no Block; no recording yet: no Listen.
        val sam = CallNoticeRules.voiceNotice(voice(text = "Call me when you can please", name = "Sam", urgent = true, by = CallNoticeRules.BY_SPEECH), cal, canPlay = false)
        assertEquals("Sam", sam.title)
        assertNull(sam.listen)
        assertNull(sam.block)
        assertEquals("Call back", sam.callBack)

        // Withheld: nothing to call back or block.
        val hidden = CallNoticeRules.voiceNotice(voice(text = "Hello this is a test", name = CallAssistantRules.WITHHELD_NAME, number = null), cal, canPlay = false)
        assertEquals("Withheld number", hidden.title)
        assertNull(hidden.callBack)
        assertNull(hidden.block)
        // Already blocked: no Block, and the line says so with when and why.
        val list = BlockedCallersView(listOf(BlockedCallerRow(BlockedCallerRules.keyOf("+441632960001")!!, "+441632960001", "Blocked today · Hung up on the assistant")), "1 number")
        val blocked = CallNoticeRules.voiceNotice(voice(text = "Hello there"), cal, canPlay = false, blocked = list)
        assertNull(blocked.block)
        assertTrue(blocked.big.endsWith("\nBlocked · it won't ring again · added today · Hung up on the assistant"), blocked.big)
        // Every notice says whether it's blocked now and in future.
        assertEquals(CallNoticeRules.NEVER_BLOCKED, CallNoticeRules.blockStatus(voice(name = "Sam"), BlockedCallersView.EMPTY))
        assertEquals(CallNoticeRules.WITHHELD_STATUS, CallNoticeRules.blockStatus(voice(name = CallAssistantRules.WITHHELD_NAME, number = null), BlockedCallersView.EMPTY))
        assertEquals(CallNoticeRules.NOT_BLOCKED, CallNoticeRules.blockStatus(voice(), BlockedCallersView.EMPTY))
        // A reputation line joins the title.
        assertEquals("01632 960001 · not in your contacts · flagged as likely spam",
            CallNoticeRules.voiceNotice(voice(text = "Hello there"), cal, canPlay = false, reputation = "flagged as likely spam").title)
    }

    @Test
    fun aboutLinesForEachOutcome() {
        assertEquals(CallNoticeRules.KEYPAD_ONLY, CallNoticeRules.about(voice(text = "6", seconds = 8, by = CallNoticeRules.BY_KEYPAD)))
        assertEquals(CallNoticeRules.KEYPAD_ONLY, CallNoticeRules.about(voice(text = "6", seconds = 8)))
        assertEquals(CallNoticeRules.TOO_SHORT, CallNoticeRules.about(voice(text = null, seconds = 1, noTranscript = true)))
        assertEquals(CallNoticeRules.ONE_WORD, CallNoticeRules.about(voice(text = "Hello", seconds = 8)))
        assertEquals(CallNoticeRules.TRANSCRIBING, CallNoticeRules.about(voice(text = null, transcribing = true)))
        assertEquals(CallNoticeRules.NO_WORDS, CallNoticeRules.about(voice(text = null, noTranscript = true)))
        assertEquals("Sent to your call assistant · 14:02 · hung up after 1 s", CallNoticeRules.happened(voice(seconds = 1), cal))
        assertEquals("Sent to your call assistant · 14:02 · left a message", CallNoticeRules.happened(voice(seconds = null), cal))
        // No urgency line without speech.
        val robo = CallNoticeRules.voiceNotice(voice(text = "1", seconds = 2, urgent = true, by = CallNoticeRules.BY_KEYPAD), cal, canPlay = true)
        assertNull(robo.sub)
        assertEquals("▶ Listen 0:02", robo.listen)
        val long = "word ".repeat(60).trim()
        assertTrue(CallNoticeRules.about(voice(text = long)).endsWith("…”"))
        assertEquals("keypad", CallNoticeRules.answeredBy("1", null))
        assertEquals("speech", CallNoticeRules.answeredBy(" ", "yes"))
        assertNull(CallNoticeRules.answeredBy(null, " "))
    }

    @Test
    fun theServerNotesTheLength() {
        val f = CallAssistantRules.messageFields("+441632960001", t, seconds = 24)
        assertEquals(FieldValue.Int64(24), f[HeldMessageFields.SECONDS])
        assertNull(CallAssistantRules.messageFields("+441632960001", t)[HeldMessageFields.SECONDS])
    }

    @Test
    fun theDiagnosisNeverCarriesNumbersOrWords() {
        fun held(at: Long, vararg extra: Pair<String, FieldValue>) = mapOf(
            HeldMessageFields.KIND to FieldValue.Text(CaptureKind.VOICE_MESSAGE.name),
            HeldMessageFields.APP to FieldValue.Text(CaptureApp.PHONE.name),
            HeldMessageFields.PERSON to FieldValue.Text("+441632960001"),
            HeldMessageFields.AT to FieldValue.Int64(at),
        ) + extra
        val all = mapOf(
            "h1" to held(t, HeldMessageFields.TEXT to FieldValue.Text("6"), HeldMessageFields.URGENT to FieldValue.Bool(true),
                HeldMessageFields.AUDIO to FieldValue.Bool(true)),
            "h2" to held(t + 1000, HeldMessageFields.TEXT to FieldValue.Text("Hi it's Alex about the parcel"), HeldMessageFields.SECONDS to FieldValue.Int64(24),
                HeldMessageFields.URGENT_BY to FieldValue.Text("speech")),
            "h3" to mapOf(HeldMessageFields.KIND to FieldValue.Text(CaptureKind.MESSAGE.name), HeldMessageFields.AT to FieldValue.Int64(t)),
        )
        val e = AssistantCallReportRules.entries(all, answerDetail = { if (it == "h1") "Pressed 1 · marked urgent" else null }, bytes = { 190_000 })
        assertEquals(2, e.size)
        assertEquals("message 0:24 · words 29 chars · not urgent (answer: speech) · no recording · no AI summary", AssistantCallReportRules.line(e[0]))
        val first = AssistantCallReportRules.line(e[1])
        assertEquals("message length not noted · words 1 chars · urgent (answer: keypad) · recording kept 186 KB · likely automated", first)
        assertFalse(first.contains("1632"))
        assertEquals("speech", AssistantCallReportRules.byFromDetail("Heard “yes” · marked urgent"))
        assertEquals("none", AssistantCallReportRules.byFromDetail("No answer · not urgent"))
    }

    @Test
    fun theAiSummarySaysWhatItWasAboutAndUpdatesTheAlertInPlace() {
        // Slice 2: the summary as shown is one tidy line.
        assertEquals("Dentist confirming Thursday 3pm", CallNoticeRules.cleanSummary("  \"dentist confirming\n Thursday 3pm.\" "))
        assertNull(CallNoticeRules.cleanSummary("  "))
        assertNull(CallNoticeRules.cleanSummary("Call back at https://x.example"))
        val long = CallNoticeRules.cleanSummary("Alex from the garage says the car is ready to collect and the bill comes to two hundred pounds today")!!
        assertTrue(long.length <= CallNoticeRules.SUMMARY_MAX && long.endsWith("…"), long)

        val item = voice(text = "Hi it's the dentist confirming Thursday at 3pm, it's urgent", urgent = true, by = CallNoticeRules.BY_SPEECH)
            .copy(summary = "Dentist confirming Thursday 3pm")
        assertEquals("Dentist confirming Thursday 3pm", CallNoticeRules.about(item))
        val n = CallNoticeRules.voiceNotice(item, cal, canPlay = false)
        assertEquals("Dentist confirming Thursday 3pm", n.text)
        assertEquals(
            "Sent to your call assistant · 14:02 · left a 0:24 message\nSaid it's urgent · Dentist confirming Thursday 3pm\n" +
                "“Hi it's the dentist confirming Thursday at 3pm, it's urgent”\n" + CallNoticeRules.NOT_BLOCKED,
            n.big,
        )
        assertEquals("Voice message · Dentist confirming Thursday 3pm · “Hi it's the dentist confirming Thursday at 3pm, it's urgent”", item.displayLine)
        // A robocall's summary (if one ever came) never replaces "likely automated".
        val robo = voice(text = "6", seconds = 2).copy(summary = "Automated payment reminder")
        assertEquals(CallNoticeRules.TOO_SHORT, CallNoticeRules.about(robo))
        assertEquals("Sent to your call assistant · 14:02 · hung up after 2 s", CallNoticeRules.voiceNotice(robo, cal, canPlay = false).text)

        // The alert already posted gains the summary once, while it's within the hour.
        val bare = item.copy(summary = null)
        assertTrue(CallAssistantRules.toAddSummary(listOf(bare), setOf("h1"), emptySet(), t + 60_000).isEmpty())
        assertEquals(listOf("h1"), CallAssistantRules.toAddSummary(listOf(item), setOf("h1"), emptySet(), t + 60_000).map { it.id })
        assertTrue(CallAssistantRules.toAddSummary(listOf(item), setOf("h1"), setOf("h1"), t + 60_000).isEmpty())
        assertTrue(CallAssistantRules.toAddSummary(listOf(item), emptySet(), emptySet(), t + 60_000).isEmpty())
        assertTrue(CallAssistantRules.toAddSummary(listOf(item), setOf("h1"), emptySet(), t + 2 * 60 * 60_000L).isEmpty())

        // The diagnosis counts the summary's characters, never its words.
        val e = AssistantCallReportRules.entries(mapOf("h9" to mapOf(
            HeldMessageFields.KIND to FieldValue.Text(CaptureKind.VOICE_MESSAGE.name),
            HeldMessageFields.APP to FieldValue.Text(CaptureApp.PHONE.name),
            HeldMessageFields.AT to FieldValue.Int64(t),
            HeldMessageFields.SECONDS to FieldValue.Int64(24),
            HeldMessageFields.TEXT to FieldValue.Text("Hi it's Alex about the parcel"),
            HeldMessageFields.SUMMARY to FieldValue.Text("Alex about the parcel"),
        ))).single()
        assertTrue(AssistantCallReportRules.line(e).endsWith("· AI summary 21 chars"), AssistantCallReportRules.line(e))
    }
}
