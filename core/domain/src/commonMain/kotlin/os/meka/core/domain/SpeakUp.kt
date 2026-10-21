package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica

// ---- Ada speaks up on her own when it really matters (the JARVIS step, slice 1; non-AI, pure) ----

/**
 * The moments Ada says out loud on her own (Meka 2026-10-20 19:49), each its own switch in Ask → Settings → Speaking up.
 * A notice carries its moment ([Notice.speak]) and the words to say after his name ([Notice.spoken]) when it is one;
 * everything else stays a silent notification. Slice 1 has the train and time to leave; slice 2 an urgent message from
 * family or Always notify; slice 3 tomorrow stacking up, once in the evening; the rest of the list (an event in 10
 * minutes, a reply he was waiting for) follows. Listed most important first: when two are due at once, the first is said.
 */
enum class SpeakMoment(val label: String, val line: String, val defaultOn: Boolean) {
    URGENT("Urgent from family", "A message or call from family or someone on Always notify that says it's urgent", true),
    TRAIN("Your train", "Cancelled, or 10 minutes or more late, in the 2 hours before you leave", true),
    LEAVE_NOW("Time to leave", "For an event or a booked session with its travel time set", true),
    PRESSURE("Tomorrow stacking up", "Once in the evening, when tomorrow has three or more things on it", true),
}

/** "Speak less / speak more": how many moments a day at most. */
enum class SpeakOften(val label: String, val perDay: Int) {
    MORE("Up to 6 a day", 6),
    LESS("Up to 3 a day", 3),
}

/**
 * Where this device would say it. Only somewhere he hears it privately or is at home: headphones, earbuds or the car
 * ([PRIVATE]), the bedside clock in the evening ([BEDSIDE]), the Mac while he is using it ([MAC]). [SPEAKER] (the
 * phone's own speaker, anywhere) never speaks.
 */
enum class SpeakPlace { PRIVATE, BEDSIDE, MAC, SPEAKER }

/** Why a moment stayed silent (for tests and Health later); null in [SpeakUpRules.check] means "say it". */
enum class SpeakHold { OFF, NOT_PRIVATE, QUIET_HOURS, MEETING, ON_CALL, TALKING, MUTED, DAILY_CAP, ALREADY_SAID }

/** His choices, synced (one `context_mode` row), the same on every device. */
data class SpeakUpSettings(
    /** Only the switches he has changed; the rest follow [SpeakMoment.defaultOn]. */
    val choices: Map<SpeakMoment, Boolean> = emptyMap(),
    val often: SpeakOften = SpeakOften.MORE,
    /** "Mute for 2 hours" (or "shh" to Ada): nothing is said before this. */
    val mutedUntilMs: Long? = null,
) {
    fun isOn(m: SpeakMoment): Boolean = choices[m] ?: m.defaultOn
    fun muted(nowMs: Long): Boolean = mutedUntilMs != null && nowMs < mutedUntilMs
}

/** What the device says now: the notice it is for, and the line ("Meka, your 07:42 is cancelled. …"). */
data class SpeakUpPick(val key: String, val moment: SpeakMoment, val line: String)

/** Ask → Settings → Speaking up, in the Talk pane's shape: a section per switch, then how often, then mute. */
data class SpeakUpView(val title: String, val intro: String, val sections: List<TalkSetupSection>, val footer: String)

/** The device's situation as it posts: where it would speak and what it is busy with. */
data class SpeakContext(
    val place: SpeakPlace,
    /** He is on a phone or video call on this device. */
    val onCall: Boolean = false,
    /** A Talk or Hello conversation is running: Ada never talks over herself. */
    val talking: Boolean = false,
)

object SpeakUpRules {
    const val ENTITY_ID = "speak_up"
    const val FIELD_MOMENTS = "moments"
    const val FIELD_OFTEN = "often"
    const val FIELD_MUTED = "muted_until"

    const val TITLE = "Speaking up"
    const val ROW_LINE = "When Ada says something out loud on her own"
    const val INTRO = "For a few moments that really matter, Ada lights the orb, plays a soft chime and says it in a sentence or two, starting with your name. Only through headphones or the car, on the bedside clock in the evening, or on the Mac while you're using it; never out of the phone's speaker. Never in quiet hours, in a meeting or on a call. It only tells you: anything to do is the usual card."
    const val OFTEN_LABEL = "How often"
    const val MUTE_LABEL = "Mute"
    const val MUTE_ACTION = "Mute for 2 hours"
    const val UNMUTE_ACTION = "Speak again now"
    const val TURN_ON = "Turn on"
    const val TURN_OFF = "Turn off"
    const val ACTIVITY_WHY = "Said out loud: it's one of your Speaking up moments, and you could hear it privately"

    /** The train: cancelled or at least this many minutes late. */
    const val TRAIN_LATE_MIN = 10
    /** "shh" / "not now" mutes for this long. */
    const val MUTE_MS = 2 * 60 * 60_000L
    /** The bedside clock counts as "at home" from this minute of the day (quiet hours end it). */
    const val EVENING_FROM_MIN = 17 * 60
    /** The Mac counts as "in use" with a key or the mouse touched this recently. */
    const val MAC_ACTIVE_MS = 2 * 60_000L

    /** Where a device would speak: the Mac in use; else headphones/the car; else the bedside clock in the evening. */
    fun place(output: BargeInOutput, bedsideShowing: Boolean, minuteOfDay: Int): SpeakPlace = when {
        output == BargeInOutput.HEADPHONES -> SpeakPlace.PRIVATE
        bedsideShowing && minuteOfDay >= EVENING_FROM_MIN -> SpeakPlace.BEDSIDE
        else -> SpeakPlace.SPEAKER
    }

    fun macPlace(idleMs: Long): SpeakPlace = if (idleMs in 0 until MAC_ACTIVE_MS) SpeakPlace.MAC else SpeakPlace.SPEAKER

    /** A meeting is under way: a timed event now with other people on it or a video link (not a whole-day block). */
    fun inMeeting(events: List<CalendarEvent>, nowMs: Long): Boolean = events.any {
        !it.allDay && it.startAtMs <= nowMs && nowMs < it.endAtMs && (it.guests.isNotEmpty() || it.joinUrl != null)
    }

    /**
     * Whether [n] is said now, or why not: a moment of its own and switched on; somewhere private; not in quiet hours,
     * a meeting, a call or a conversation; not muted; under the day's cap; not already said (on any device).
     */
    fun check(
        n: Notice, s: SpeakUpSettings, ctx: SpeakContext, quiet: Boolean, meeting: Boolean,
        spokenToday: Int, alreadySaid: Boolean, nowMs: Long,
    ): SpeakHold? {
        val m = n.speak ?: return SpeakHold.OFF
        return checkMoment(m, n.spoken, s, ctx, quiet, meeting, spokenToday, alreadySaid, nowMs)
    }

    /** [check] for a moment that isn't a governor notice (an urgent alert, slice 2). */
    fun checkMoment(
        m: SpeakMoment, spoken: String?, s: SpeakUpSettings, ctx: SpeakContext, quiet: Boolean, meeting: Boolean,
        spokenToday: Int, alreadySaid: Boolean, nowMs: Long,
    ): SpeakHold? {
        return when {
            spoken.isNullOrBlank() || !s.isOn(m) -> SpeakHold.OFF
            ctx.place == SpeakPlace.SPEAKER -> SpeakHold.NOT_PRIVATE
            quiet -> SpeakHold.QUIET_HOURS
            meeting -> SpeakHold.MEETING
            ctx.onCall -> SpeakHold.ON_CALL
            ctx.talking -> SpeakHold.TALKING
            s.muted(nowMs) -> SpeakHold.MUTED
            spokenToday >= s.often.perDay -> SpeakHold.DAILY_CAP
            alreadySaid -> SpeakHold.ALREADY_SAID
            else -> null
        }
    }

    /** The first of [posted] to say now (in the order [SpeakMoment] lists them), or null. Only one at a time. */
    fun pick(
        posted: List<Notice>, s: SpeakUpSettings, ctx: SpeakContext, quiet: Boolean, meeting: Boolean,
        spokenToday: Int, nowMs: Long, address: String?, alreadySaid: (String) -> Boolean,
    ): SpeakUpPick? = posted
        .filter { it.speak != null }
        .sortedBy { it.speak!!.ordinal }
        .firstOrNull { check(it, s, ctx, quiet, meeting, spokenToday, alreadySaid(it.key), nowMs) == null }
        ?.let { SpeakUpPick(it.key, it.speak!!, line(it.spoken!!, address)) }

    /** One moment that isn't a governor notice ([checkMoment]), said as [line] would; null when it stays silent. */
    fun pickOne(
        key: String, m: SpeakMoment, spoken: String, s: SpeakUpSettings, ctx: SpeakContext, quiet: Boolean, meeting: Boolean,
        spokenToday: Int, nowMs: Long, address: String?, alreadySaid: Boolean,
    ): SpeakUpPick? = if (checkMoment(m, spoken, s, ctx, quiet, meeting, spokenToday, alreadySaid, nowMs) == null)
        SpeakUpPick(key, m, line(spoken, address, keepCase = m == SpeakMoment.URGENT)) else null

    /** "Meka, your 07:42 is cancelled." with a name ("Sir, …" when he chose sir); "Your 07:42 is cancelled." without. */
    fun line(spoken: String, address: String?, keepCase: Boolean = false): String {
        val words = spoken.trim()
        return if (address.isNullOrBlank()) words.replaceFirstChar { it.uppercaseChar() }
        // An urgent alert starts with the sender's name ("Meka, Sam left …"): never lower-cased.
        else "$address, " + if (keepCase) words else words.replaceFirstChar { it.lowercaseChar() }
    }

    /** How Ada addresses him: "Sir" when chosen, else his first name from About you, else none. */
    fun address(shownName: String?, sir: Boolean): String? = if (sir) "Sir" else HelloRules.firstName(shownName)

    // ---- The spoken words, written where each notice is made ----

    /** "your 07:42 is cancelled. The 07:54 is on time, platform 3. Leave by 07:44." */
    fun trainWords(worst: TrainDeparture, good: TrainDeparture?, leaveByMin: Int?): String? {
        val late = worst.lateMin
        val t = LocalClock.formatMinute(worst.depMin)
        val head = when {
            worst.cancelled -> "your $t is cancelled."
            late != null && late >= TRAIN_LATE_MIN -> "your $t is running $late minutes late."
            else -> return null
        }
        val next = good?.let { g ->
            val gl = g.lateMin
            val state = if (gl == null || gl == 0) "on time" else "$gl ${if (gl == 1) "minute" else "minutes"} late"
            " The ${LocalClock.formatMinute(g.depMin)} is $state" + (g.platform?.let { ", platform $it." } ?: ".") +
                (leaveByMin?.let { " Leave by ${LocalClock.formatMinute(it)}." }.orEmpty())
        }.orEmpty()
        return head + next
    }

    /** "it's time to leave for Dentist. It starts at 14:00." */
    fun leaveWords(title: String, start: String): String = "it's time to leave for ${title.trim()}. It starts at $start."

    /** "time to head off: Gym at 17:45. Leave by 17:30." */
    fun sessionWords(heading: String, start: String, leaveBy: String): String = "time to head off: ${heading.trim()} at $start. Leave by $leaveBy."

    // ---- Settings ----

    fun encodeChoices(c: Map<SpeakMoment, Boolean>): String =
        c.entries.sortedBy { it.key.ordinal }.joinToString(",") { "${it.key.name}:${if (it.value) 1 else 0}" }

    fun decodeChoices(raw: String?): Map<SpeakMoment, Boolean> = raw.orEmpty().split(',').mapNotNull { p ->
        val (k, v) = p.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
        val m = SpeakMoment.entries.firstOrNull { it.name == k } ?: return@mapNotNull null
        m to (v == "1")
    }.toMap()

    fun settings(fields: Map<String, FieldValue>?): SpeakUpSettings = SpeakUpSettings(
        choices = decodeChoices(fields?.get(FIELD_MOMENTS)?.textOrNull),
        often = SpeakOften.entries.firstOrNull { it.name == fields?.get(FIELD_OFTEN)?.textOrNull } ?: SpeakOften.MORE,
        mutedUntilMs = fields?.get(FIELD_MUTED)?.longOrNull?.takeIf { it > 0 },
    )

    /** The pane: one section per moment (Turn on / Turn off), How often (the other choice), Mute (for 2 hours / again now). */
    fun view(s: SpeakUpSettings, spokenToday: Int, nowMs: Long, cal: LocalCalendar): SpeakUpView {
        val moments = SpeakMoment.entries.map { m ->
            val on = s.isOn(m)
            TalkSetupSection(m.label, if (on) "On" else "Off", on, listOf(m.line), if (on) TURN_OFF else TURN_ON)
        }
        val other = SpeakOften.entries.first { it != s.often }
        val often = TalkSetupSection(OFTEN_LABEL, s.often.label, false,
            listOf("Said today: $spokenToday of ${s.often.perDay}"), other.label)
        val muted = s.muted(nowMs)
        val mute = TalkSetupSection(MUTE_LABEL,
            if (muted) "Muted till ${LocalClock.formatMinute(cal.minuteOfDay(s.mutedUntilMs!!))}" else "Not muted", muted,
            listOf("Nothing is said out loud on any of your devices until then; notifications still arrive."),
            if (muted) UNMUTE_ACTION else MUTE_ACTION)
        return SpeakUpView(TITLE, INTRO, moments + often + mute, "Everything Ada says on her own is in Activity.")
    }

    /** Activity's line for a moment said: "Said: Meka, your 07:42 is cancelled. …". */
    fun activitySummary(line: String): String = "Said: $line"

    /** Activity's id for [key] said aloud, so it is said (and logged) once across devices. */
    fun activityId(key: String): String = "k" + ActivityRules.fnv64("spoke:$key")

    // ---- Slice 2: an urgent message from family or Always notify ----

    /** At most this many of their words are said ("Sam says it's urgent: “…”"). */
    const val URGENT_WORDS = 14
    /** A message older than this when the alert posts (a re-post, a late sync) is never said. */
    const val URGENT_FRESH_MS = 10 * 60_000L

    /**
     * The words for an urgent alert ([Capture.breakThrough]), or null when it isn't one to say: only from someone on his
     * family or Always notify list (or held as family), only when it says it's urgent (a voice message by
     * [CallNoticeRules.urgent], a message by its own words), never automated, a scam, a group chat or stale. Their own
     * words (the AI's summary first) are said, cut to [URGENT_WORDS]: it only tells him, nothing in them is acted on
     * (ADR-006). "Sam left your call assistant an urgent message: “call me when you can.”"
     */
    fun urgentWords(item: CapturedItem, lists: PeopleLists, nowMs: Long): String? {
        val name = item.personName.trim()
        if (name.isEmpty() || item.automated || item.endedAsScam || item.conversation != null) return null
        if (nowMs - item.atMs !in 0..URGENT_FRESH_MS) return null
        if (!(item.family || lists.isFamily(name) || lists.isAlwaysNotify(name))) return null
        val voice = item.kind == CaptureKind.VOICE_MESSAGE
        if (!item.isUrgent) return null
        val said = (item.summary?.takeIf { voice } ?: item.text)?.let(::clip)
        val head = when (item.kind) {
            CaptureKind.VOICE_MESSAGE -> "$name left your call assistant an urgent message"
            CaptureKind.MESSAGE -> "$name says it's urgent on ${item.app.label}"
            CaptureKind.MISSED_CALL -> return null
        }
        return head + (said?.let { ": “$it”" } ?: ".")
    }

    /** "Call me when you can." — the first [URGENT_WORDS] words as written, ending in a stop or "…". */
    private fun clip(text: String): String? {
        val words = text.replace(Regex("\\s+"), " ").trim().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val cut = words.take(URGENT_WORDS).joinToString(" ")
        val end = if (words.size > URGENT_WORDS) "…" else if (cut.last() in ".!?") "" else "."
        return cut + end
    }

    /** The alert's key for [pickOne] and Activity: once per message across devices. */
    fun urgentKey(item: CapturedItem): String = "urgent:${item.id}"

    // ---- Slice 3: tomorrow stacking up, said once in the evening ----

    /** The evening heads-up's time (after the bins' 19:00), and when it stops being worth saying. */
    const val PRESSURE_AT_MIN = 19 * 60 + 30
    const val PRESSURE_UNTIL_MIN = 22 * 60

    /**
     * "tomorrow is stacking up: Trip form, Council tax and Gym. Today has a way to lighten it." ([DayPressure.spoken],
     * with the hint when there is a one-tap fix on Today).
     */
    fun pressureWords(p: DayPressure): String =
        p.spoken.trim() + if (p.fixes.isNotEmpty()) " Today has a way to lighten it." else ""

    /**
     * The evening heads-up for tomorrow stacking up ([PressureRules], Week board item (10)): one notice at
     * [PRESSURE_AT_MIN] the evening before, standing till [PRESSURE_UNTIL_MIN], once per day (its key is tomorrow's
     * day), Ada saying it when she may ([SpeakMoment.PRESSURE]). "Tomorrow is stacking up" · "Trip form, Council tax
     * and Gym · Move “Gym” to Fri". Null when [tomorrow] isn't tomorrow's or it is too late in the evening.
     */
    fun pressureNotice(tomorrow: DayPressure?, nowMs: Long, cal: LocalCalendar): Notice? {
        val p = tomorrow ?: return null
        val day = cal.epochDayOf(nowMs)
        if (p.epochDay != day + 1 || p.items.isEmpty()) return null
        val until = cal.toEpochMs(day, PRESSURE_UNTIL_MIN)
        if (nowMs >= until) return null
        val titles = PressureRules.names(if (p.evening) p.items.filter { it.evening } else p.items)
        return Notice(
            key = "pressure:${p.epochDay}", source = NoticeSource.PRESSURE, tier = NoticeTier.HEADS_UP,
            title = "${p.dayName} is stacking up",
            text = listOfNotNull(titles, p.fixes.firstOrNull()?.label).joinToString(" · "),
            atMs = cal.toEpochMs(day, PRESSURE_AT_MIN), target = NoticeTarget.TODAY, expiresAtMs = until,
            speak = SpeakMoment.PRESSURE, spoken = pressureWords(p),
        )
    }

    // ---- Slice 2: "shh" said to Ada in Talk ----

    /** What Ada says when told "shh" in a conversation; the conversation ends after it. */
    const val MUTED_REPLY = "Okay. I'll keep quiet for two hours. Notifications still come through."

    /**
     * Whether what he said in a conversation mutes speaking up: [isMute], except that "not now" answers a question when
     * one is open ([answersSomething]: cards on offer, or the hello that may have asked his name) and stays that answer.
     */
    fun mutesInTalk(words: String, answersSomething: Boolean): Boolean =
        isMute(words) && !(answersSomething && words.lowercase().contains("not now"))

    /** "shh", "not now", "quiet please", "stop talking": mutes speaking up for [MUTE_MS]. */
    fun isMute(words: String): Boolean {
        val w = words.lowercase().replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim()
            .removePrefix("ada ").removePrefix("hey ada ").trim()
        return w.matches(Regex("(s+h+|shush|hush|not now( ada)?|quiet( please)?|be quiet|stop talking|not now thanks)"))
    }
}

/** His Speaking up choices, synced. */
class SpeakUpStore(private val replica: Replica) {
    fun settings(): SpeakUpSettings = SpeakUpRules.settings(replica.entity(EntityTypes.CONTEXT_MODE, SpeakUpRules.ENTITY_ID)?.fields)

    fun setMoment(m: SpeakMoment, on: Boolean) {
        val s = settings()
        if (s.isOn(m) == on) return
        val c = s.choices + (m to on)
        replica.commitLocal(EntityTypes.CONTEXT_MODE, SpeakUpRules.ENTITY_ID, mapOf(SpeakUpRules.FIELD_MOMENTS to FieldValue.Text(SpeakUpRules.encodeChoices(c))))
    }

    fun setOften(o: SpeakOften) {
        if (settings().often == o) return
        replica.commitLocal(EntityTypes.CONTEXT_MODE, SpeakUpRules.ENTITY_ID, mapOf(SpeakUpRules.FIELD_OFTEN to FieldValue.Text(o.name)))
    }

    /** Mutes till [untilMs]; null speaks again now. */
    fun mute(untilMs: Long?) {
        replica.commitLocal(EntityTypes.CONTEXT_MODE, SpeakUpRules.ENTITY_ID, mapOf(SpeakUpRules.FIELD_MUTED to FieldValue.Int64(untilMs ?: 0L)))
    }
}
