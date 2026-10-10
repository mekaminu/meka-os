package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/**
 * The call assistant's voice side (build plan M1, Needs Meka #9, approved 2026-10-07). A call the Fold declined at work
 * is forwarded by the carrier ("forward when busy") to the assistant's phone number; the server answers it with this
 * fixed script, records a message, asks whether it is urgent and writes the message into Meka's synced after-work
 * summary as a `held_message` (ADR-008 addendum). Non-AI: the words are fixed (Meka's greeting, which always says it
 * is an automated assistant) and the transcript comes from the phone service.
 *
 * What the caller says is untrusted (ADR-006): the transcript is only ever displayed, never interpreted; only the
 * keypad/"yes" answer to "is it urgent?" and the words "urgent"/"emergency" ([Urgency]) raise an alert.
 */
object CallAssistantScript {
    /** Meka's greeting (approved 2026-10-07), saying it is an automated assistant as the build plan requires. */
    const val GREETING = "Hi, you've reached Meka's automated assistant. Meka is at work right now and will call you back. " +
        "Can I take a message, and is it urgent?"
    const val RECORD_PROMPT = "Please leave your message after the tone, then press the hash key or just hang up."
    const val URGENT_QUESTION = "Is it urgent? Press 1 or say yes if it is. Press 2 or say no if it can wait."
    /**
     * Asked once more when the first answer wasn't understood or nothing came (call assistant polish 8d, Meka's test
     * 2026-10-09: he said it was urgent and the card didn't show it).
     */
    const val URGENT_AGAIN = "Sorry, is it urgent? Say yes or no, or press 1 for yes."
    const val THANKS_URGENT = "Thank you. I'll let Meka know straight away. Goodbye."
    const val THANKS = "Thank you. Meka will get your message after work. Goodbye."

    /**
     * Outside work hours (call assistant polish 6, Meka heard the at-work greeting at 20:50): calls only reach the
     * assistant then when Meka declines one, so it doesn't say he is at work.
     */
    const val GREETING_AWAY = "Hi, you've reached Meka's automated assistant. Meka can't take your call right now and will call you back. " +
        "Can I take a message, and is it urgent?"
    const val THANKS_AWAY = "Thank you. Meka will get your message and call you back. Goodbye."

    fun greeting(atWork: Boolean): String = if (atWork) GREETING else GREETING_AWAY
    fun thanks(atWork: Boolean): String = if (atWork) THANKS else THANKS_AWAY
    const val NO_MESSAGE = "I didn't hear a message. Meka will see that you called. Goodbye."

    /** Longest message kept (the phone service's transcription covers up to two minutes). */
    const val MAX_MESSAGE_SECONDS = 120
    /** Silence that ends a message. */
    const val SILENCE_SECONDS = 5
    /** How long "is it urgent?" waits for an answer. */
    const val ANSWER_SECONDS = 6
}

object CallAssistantRules {
    /** How long after a voice message its urgent alert may still be posted (a phone that was off doesn't alert hours later). */
    const val ALERT_WINDOW_MS = 60 * 60_000L
    const val WITHHELD_NAME = "Withheld number"
    private const val MAX_TEXT = 2_000

    /** The `held_message` id of the message left on one call: the same however often the phone service retries. */
    fun heldId(provider: String, callId: String): String = HeldMessages.entityId("voice:$provider:$callId")

    /** How the caller is shown: their number as the network gave it, or "Withheld number". */
    fun callerName(from: String?): String {
        val n = from?.trim().orEmpty()
        val hidden = CallScreeningRules.callerKey(n) == CallScreeningRules.WITHHELD || n.filter { it.isDigit() } in spelledHidden
        return if (hidden) WITHHELD_NAME else n.take(40)
    }

    /** What phone networks send instead of a hidden number, spelled on a keypad: ANONYMOUS, RESTRICTED, UNAVAILABLE. */
    private val spelledHidden = setOf("266696687", "7378742833", "86282452253")

    /**
     * The answer to "is it urgent?": true for 1 or a yes, false for 2 or a no, null when there was no answer the
     * assistant understood (it then asks once more; after that it is not treated as urgent, though the message's own
     * words still can be). A short "it is" / "it's urgent" / "very" / "one" counts as yes (polish 8d): callers answer
     * the question in its own words.
     */
    fun isUrgentAnswer(digits: String?, speech: String?): Boolean? {
        when (digits?.trim()?.firstOrNull()) {
            '1' -> return true
            '2' -> return false
        }
        val s = speech?.lowercase()?.replace('’', '\'')?.replace(Regex("[^a-z0-9' ]+"), " ")?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (s.isEmpty()) return null
        return when {
            notUrgent.containsMatchIn(s) -> false // "no, it's not urgent" says "urgent" too
            yes.containsMatchIn(s) -> true
            no.containsMatchIn(s) -> false
            s in shortYes -> true
            s in shortNo -> false
            else -> null
        }
    }

    private val notUrgent = Regex("\\b(not urgent|isn't urgent|is not urgent|it's not|it is not|it isn't|not really|can wait|no rush|no hurry)\\b")
    private val yes = Regex("\\b(yes|yeah|yep|yup|yea|urgent|urgently|emergency|asap|right away|straight away)\\b")
    private val no = Regex("\\b(no|nope|nah)\\b")
    /** Whole answers that mean yes or no on their own ("it is", or the digit said aloud). */
    private val shortYes = setOf("it is", "it's", "it is please", "very", "very much", "please", "one", "1", "it is actually", "sure", "definitely", "i think so")
    private val shortNo = setOf("two", "2", "later", "fine", "no it can wait")

    /** What the assistant heard when it asked, for Activity: "Pressed 1", "Heard “it is”" or "No answer". */
    fun answerHeard(digits: String?, speech: String?): String {
        digits?.trim()?.takeIf { it.isNotEmpty() }?.let { return "Pressed ${it.take(4)}" }
        val words = speech?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() } ?: return "No answer"
        val shown = if (words.length > 60) words.take(59).trimEnd() + "…" else words
        return "Heard “$shown”"
    }

    /**
     * The Activity entry for one answer to "is it urgent?" (polish 8d: Meka can see what the assistant heard). [urgent]
     * is [isUrgentAnswer]'s reading; [askingAgain] when it didn't understand and asks once more. Written by the server
     * like a published build's entry (ADR-008 addendum); [ActivityKind.CALL].
     */
    fun answerActivity(caller: String, digits: String?, speech: String?, urgent: Boolean?, askingAgain: Boolean, atMs: Long): Map<String, FieldValue> {
        val outcome = when {
            urgent == true -> "marked urgent"
            urgent == false -> "not urgent"
            askingAgain -> "didn't understand, asked again"
            else -> "still unclear, not marked urgent"
        }
        return linkedMapOf(
            ActivityFields.AT to FieldValue.Int64(atMs),
            ActivityFields.KIND to FieldValue.Text(ActivityKind.CALL.name),
            ActivityFields.SUMMARY to FieldValue.Text("Asked $caller if it was urgent".take(ActivityRules.MAX_LINE)),
            ActivityFields.DETAIL to FieldValue.Text("${answerHeard(digits, speech)} · $outcome".take(ActivityRules.MAX_LINE)),
            ActivityFields.WHY to FieldValue.Text("Call assistant · is it urgent?"),
            ActivityFields.SOURCE to FieldValue.Text("calls"),
        )
    }

    /** The entry id of that answer: one per call and attempt, however often the phone service retries. */
    fun answerActivityId(heldId: String, again: Boolean): String = "v" + ActivityRules.fnv64("urgentanswer:$heldId:${if (again) 2 else 1}")

    /** The phone service's transcript, tidied for display: whitespace collapsed, bounded, null when empty. */
    fun transcript(text: String?): String? =
        text?.replace(Regex("\\s+"), " ")?.trim()?.take(MAX_TEXT)?.takeIf { it.isNotEmpty() }

    /**
     * The fields of a new voice message (the transcript arrives later as [HeldMessageFields.TEXT]). [atWork] false marks
     * a message taken outside work hours ([HeldMessageFields.AWAY]), so the summary isn't titled "While you were at work".
     */
    fun messageFields(from: String?, atMs: Long, atWork: Boolean = true): Map<String, FieldValue> = buildMap {
        put(HeldMessageFields.APP, CaptureApp.PHONE.name.fv())
        put(HeldMessageFields.KIND, CaptureKind.VOICE_MESSAGE.name.fv())
        put(HeldMessageFields.PERSON, callerName(from).fv())
        put(HeldMessageFields.AT, FieldValue.Int64(atMs))
        // The server doesn't have the family list (it stays on the Fold); the Fold ranks by number with its lists.
        put(HeldMessageFields.FAMILY, false.fv())
        if (!atWork) put(HeldMessageFields.AWAY, true.fv())
    }

    /** How long a voice message with no words yet reads "Transcribing…" (the phone service takes a minute or two). */
    const val TRANSCRIBING_MS = 15 * 60_000L

    /**
     * Whether Meka is at work at this moment, from the synced work fields as stored ([WorkFields.SCHEDULE],
     * [WorkFields.SWITCH], [BankHolidayFields.DATES]; null when never written) and the local date and time. The server
     * uses it to pick the greeting ([CallAssistantScript.greeting]); the same rules the apps use ([WorkModeRules.state]).
     */
    fun atWork(schedule: String?, switch: String?, holidays: String?, epochDay: Long, minuteOfDay: Int, nowMs: Long): Boolean {
        val s = if (schedule == null || schedule == WorkSchedule.LEGACY_DEFAULT) WorkSchedule.DEFAULT else WorkSchedule.decode(schedule) ?: WorkSchedule.DEFAULT
        val clock = LocalClock(CivilDate.isoDayOfWeek(epochDay), minuteOfDay)
        return WorkModeRules.state(s, WorkSwitch.decode(switch), clock, nowMs, epochDay, HolidayCalendar.of(BankHolidays.decode(holidays))).atWork
    }

    /**
     * Urgent voice messages the Fold should alert about now: urgent (by the caller's answer or its words), left in the
     * last [ALERT_WINDOW_MS], and not alerted before ([alerted] holds the ids already posted).
     */
    fun toAlert(items: List<CapturedItem>, alerted: Set<String>, nowMs: Long): List<CapturedItem> =
        items.filter { it.kind == CaptureKind.VOICE_MESSAGE && it.isUrgent && it.atMs >= nowMs - ALERT_WINDOW_MS && it.id !in alerted }

    /**
     * Urgent voice messages whose alert should gain ▶ Play now (polish 8c): already alerted ([alerted]), their
     * recording has arrived since ([CapturedItem.hasAudio], which also honours "Keep callers' recordings"), still
     * within [ALERT_WINDOW_MS], and not given Play before ([withPlay]). The Fold updates the alert in place, silently,
     * and only while it is still showing, so a dismissed alert never comes back.
     */
    fun toAddPlay(items: List<CapturedItem>, alerted: Set<String>, withPlay: Set<String>, nowMs: Long): List<CapturedItem> =
        items.filter {
            it.kind == CaptureKind.VOICE_MESSAGE && it.isUrgent && it.hasAudio && it.atMs >= nowMs - ALERT_WINDOW_MS &&
                it.id in alerted && it.id !in withPlay
        }

    /** The alert's action button. */
    const val PLAY_ACTION = "▶ Play"

    /** What the Play action opens: "play:<held id>" (the after-work summary on that person, playing it). */
    const val OPEN_PLAY_PREFIX = "play:"

    fun openPlay(heldId: String): String = OPEN_PLAY_PREFIX + heldId

    /** The held message to play from an open value, or null for anything else (or an id the server couldn't make). */
    fun playFromOpen(open: String?): String? =
        open?.takeIf { it.startsWith(OPEN_PLAY_PREFIX) }?.removePrefix(OPEN_PLAY_PREFIX)?.takeIf { VoiceRecordingRules.isHeldId(it) }

    /** The person in [summary] whose card holds [heldId] (to unfold it), or null once it has gone (Done, or swept). */
    fun personWith(summary: AfterWorkSummary, heldId: String?): PersonSummary? =
        heldId?.let { id -> summary.people.firstOrNull { p -> p.items.any { it.id == id && it.hasAudio } } }
}

/**
 * Callers' recordings (call assistant polish 8c, Meka 2026-10-09: "can I not listen to it?"). Once the phone service
 * has transcribed a voice message, MEKA's server fetches the recording, keeps it encrypted in MEKA's own private
 * bucket (SSE-KMS with MEKA's key, no public address) and deletes the phone service's copy; then it writes
 * [HeldMessageFields.AUDIO] so both apps offer ▶ Play. Devices fetch the audio only over a signed request, keep it in
 * memory while it plays and never save it. Kept [KEEP_MS] at most (a bucket lifecycle rule is the backstop), and
 * deleted at once when Meka taps Done. Non-AI, pure.
 */
object VoiceRecordingRules {
    /** How long a recording is kept (and offered) after the call. */
    const val KEEP_MS = 30L * 24 * 60 * 60_000L
    /** Largest recording kept: two minutes of the phone service's MP3 is well under 1 MB. */
    const val MAX_BYTES = 5 * 1024 * 1024
    /** Where recordings live in the bucket; the lifecycle rule is on this prefix. */
    const val PREFIX = "voice/"

    const val PLAY = "▶ Play"
    const val STOP = "■ Stop"
    const val LOADING = "Loading…"
    const val FAILED = "Couldn't play this message"
    const val PRIVACY = "Recordings are kept encrypted on MEKA's server for 30 days, and deleted when you tap Done."

    /** "Keep callers' recordings" (Work → Call assistant): how long, in days, unless Meka picks otherwise. */
    const val DEFAULT_KEEP_DAYS = 30
    /** The choices, in the order the apps show them: 7 days · 30 days · Don't keep (0). */
    val KEEP_CHOICES = listOf(7, 30, 0)
    const val SETTING_TITLE = "Keep callers' recordings"
    /**
     * With "Don't keep", the server still holds a recording while Amazon Transcribe hears it (at most 5 minutes) and
     * deletes it straight after; the hourly sweep removes anything left after this long.
     */
    const val UNKEPT_GRACE_MS = 15 * 60_000L
    private const val DAY_MS = 24 * 60 * 60_000L

    /** The synced choice ([WorkFields.RECORDING_DAYS]) as days: 0 or 7 as stored, anything else (or nothing) 30. */
    fun keepDays(stored: Long?): Int = when (stored) {
        0L -> 0
        7L -> 7
        else -> DEFAULT_KEEP_DAYS
    }

    /** How long a recording kept for [days] is offered. */
    fun keepMs(days: Int): Long = days.coerceIn(0, DEFAULT_KEEP_DAYS) * DAY_MS

    /** A choice's chip: "7 days" · "30 days" · "Don't keep". */
    fun choiceLabel(days: Int): String = if (days == 0) "Don't keep" else "$days days"

    /** The line under the chips; cross-fades as the choice changes. */
    fun settingLine(days: Int): String =
        if (days == 0) "Not kept · you'll have the words; each recording is deleted once it has been transcribed"
        else "Kept $days days · encrypted on MEKA's server, deleted sooner when you tap Done"

    /** The note under the after-work summary when something in it can be played. */
    fun privacy(days: Int): String =
        if (days == 0) "Recordings aren't kept: MEKA's server deletes each one once it has been transcribed."
        else "Recordings are kept encrypted on MEKA's server for $days days, and deleted when you tap Done."

    /**
     * The server's sweep (hourly, and when the choice changes): recordings stored before this are deleted. "Don't
     * keep" leaves only those still being transcribed ([UNKEPT_GRACE_MS]).
     */
    fun sweepBeforeMs(days: Int, nowMs: Long): Long = nowMs - if (days == 0) UNKEPT_GRACE_MS else keepMs(days)

    private val heldIdPattern = Regex("^h[0-9a-f]{16}$")
    private val householdPattern = Regex("^[A-Za-z0-9_-]{1,64}$")

    /** A held message's id as the server makes them ([HeldMessages.entityId]); anything else is refused. */
    fun isHeldId(id: String): Boolean = heldIdPattern.matches(id)

    /** The bucket key of a household's recording, or null for an id or household that couldn't be one. */
    fun key(householdId: String, heldId: String): String? {
        if (!householdPattern.matches(householdId) || !isHeldId(heldId)) return null
        return "$PREFIX$householdId/$heldId.mp3"
    }

    /** Where a household's recordings (and transcripts) live, `voice/<household>/`; null for one that couldn't be. */
    fun householdPrefix(householdId: String): String? =
        if (householdPattern.matches(householdId)) "$PREFIX$householdId/" else null

    /**
     * Whether a recording is offered: a voice message, not cleared, kept ([HeldMessageFields.AUDIO]) and younger than
     * the chosen [keepDays] (30 by default; [KEEP_MS] at most; none with "Don't keep").
     */
    fun playable(kind: CaptureKind?, audio: Boolean, cleared: Boolean, atMs: Long?, nowMs: Long, keepDays: Int = DEFAULT_KEEP_DAYS): Boolean =
        kind == CaptureKind.VOICE_MESSAGE && audio && !cleared && atMs != null && nowMs - atMs in 0 until keepMs(keepDays)

    /** "0:12 / 0:40" while playing (whole seconds; a length not known yet shows only where it is). */
    fun progressLine(positionMs: Long, durationMs: Long): String {
        val at = clock(positionMs)
        return if (durationMs > 0) "$at / ${clock(durationMs)}" else at
    }

    /** 0..1 of the way through, for the bar. */
    fun fraction(positionMs: Long, durationMs: Long): Float =
        if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    private fun clock(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000).toInt()
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }
}
