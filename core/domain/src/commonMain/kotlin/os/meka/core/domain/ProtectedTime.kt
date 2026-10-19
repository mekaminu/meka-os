package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Protected time (V1), slice 1: evenings and weekends defended by rules. Meka turns on either or both once (Ask →
 * More → Protected time); from then on MEKA never puts a task into that time itself:
 *
 * - **Evenings** from a set time (18:00–20:00, default 19:00) to midnight on every day that isn't wholly protected. A
 *   work day whose shift ends later starts its evening at the shift's end; a night shift has no protected evening.
 * - **Weekends**: Saturday, Sunday and bank holidays, all day, unless the work schedule has a shift that day.
 *
 * Plan my day ([DayPlanner.plan], `protected`) places no task there (habit room, booked sessions and kept-free blocks
 * are Meka's own time and are left as they are), and says so under the plan ([ProtectedRules.planNote]); on a protected
 * day the tasks that would have been planned "wait for Mon 12 Oct" instead of "won't fit today". Move to later
 * ([LateTaskRules.laterSlot]) never moves a task into it. Nothing Meka schedules himself is moved or refused.
 *
 * Stored as one `context_mode` entity, id [ProtectedRules.ENTITY_ID] (ADR-008 addendum 2026-10-10): `evenings` (Bool),
 * `fromMin` (local minutes), `weekends` (Bool). All LWW. Off until Meka turns it on. Plain, non-AI rules; nothing
 * leaves Meka's own synced data.
 *
 * Ask and Talk know the rules ([ProtectedRules.askLines], slice 4): the setting, today, and the next two weeks' whole
 * days and later evenings go with every question as `protected` lines.
 *
 * Slice 3 (2026-10-18, [ProtectedMailRules]): **work mail waits until morning.** While protected time is on and it is
 * protected now (an evening from its start, a protected whole day, or the small hours after either until
 * [ProtectedMailRules.MORNING_MIN]), the business account's emails leave "From your mail", the ten-minute inbox, its
 * reminder and the watch's count, and the card says "2 business emails wait till tomorrow 07:00"; they come back on
 * their own in the morning. A security alert, a reply he already sent or one that didn't send, and notes to self are
 * never held. One more additive field, `holdMail` (Bool, absent = on); Off in the pane shows them as before.
 *
 * Slice 4 (2026-10-18): **not protected tonight, just this once.** One tap in the pane ("Not protected tonight", or
 * "Not protected today" on a protected whole day) lifts today's protected time only: the planner and Move to later may
 * use it, the business mail isn't held tonight (nor in the small hours after), Ask is told; **Undo** puts it back. One
 * more additive field, `openDay` (epoch day, 0 = none); a day that has passed simply stops mattering.
 *
 * Slice 5 (2026-10-18): **the brief and the week ahead show it.** The morning brief says today's protected time under
 * the date ([ProtectedRules.briefLine]) and in its spoken words ([ProtectedRules.briefSentence]); each day of the week
 * ahead carries its mark beside the hours ([ProtectedRules.dayMark]) and the week a line under its summary
 * ([ProtectedRules.weekLine]). Read only; nothing new stored.
 *
 * Slice 6 (2026-10-19, [ProtectedMailRules.morningNotice]): **one morning note for what waited.** At 07:00 on the
 * morning protected time ends, one heads-up says how many business emails came in while it was protected and still
 * need him, and from whom ("3 work emails waited overnight" · "Sam Taylor, Alex Green and 1 more · in From your mail"),
 * opening Needs you; its own Notifications row, gone at midday. Nothing new stored.
 *
 * Later: work notifications (an Outlook work calendar's reminders) wait until morning too.
 */
object ProtectedFields {
    const val EVENINGS = "evenings"
    const val FROM = "fromMin"
    const val WEEKENDS = "weekends"
    /** Slice 3: business mail waits until morning (absent = on). */
    const val HOLD_MAIL = "holdMail"
    /** Slice 4: the one day (epoch day) he lifted protected time for; 0 or absent = none. */
    const val OPEN_DAY = "openDay"
}

/** The setting as stored. */
data class ProtectedSetting(
    val evenings: Boolean,
    val fromMin: Int,
    val weekends: Boolean,
    val holdMail: Boolean = true,
    /** Slice 4: the day he lifted protected time for ("not protected tonight"), or null. */
    val openDay: Long? = null,
) {
    val on: Boolean get() = evenings || weekends
}

/** What the pane shows: the two switches, the evening's time, and the summary. */
data class ProtectedView(
    val evenings: Boolean,
    val fromMin: Int,
    val weekends: Boolean,
    /** "Evenings from 19:00 and weekends · nothing is planned into them", or [ProtectedRules.OFF_LINE]. */
    val summary: String,
    /** "Today is protected · tasks wait for Mon 12 Oct" / "Protected from 19:00 tonight"; null when off. */
    val todayLine: String?,
    /** Slice 3: business mail waits until morning while it's protected. */
    val holdMail: Boolean = true,
    /** Slice 3: "Held now · 2 business emails wait till 07:00" while some are held, else null. */
    val mailLine: String? = null,
    /** Slice 4: "Not protected tonight" / "Not protected today" while today has protected time he could lift, else null. */
    val openAction: String? = null,
    /** Slice 4: true when he lifted today's protected time (the pane then offers Undo). */
    val openToday: Boolean = false,
) {
    val on: Boolean get() = evenings || weekends

    companion object {
        val EMPTY = ProtectedView(false, ProtectedRules.DEFAULT_FROM, false, ProtectedRules.OFF_LINE, null)
    }
}

object ProtectedRules {
    const val ENTITY_ID = "protected_time"
    const val DEFAULT_FROM = 19 * 60
    val FROM_CHOICES: List<Int> = listOf(18 * 60, 18 * 60 + 30, 19 * 60, 19 * 60 + 30, 20 * 60)

    const val EVENINGS_LABEL = "Evenings"
    const val WEEKENDS_LABEL = "Weekends and bank holidays"
    const val OFF_LINE = "Off · the planner may use evenings and weekends"
    const val NOTE = "Plan my day and Move to later never put a task into protected time. Habits, booked sessions, " +
        "date night and anything you plan yourself are left as they are. A weekend shift in Work mode keeps that day open."
    /** How far ahead [nextOpenDay] looks for a day that isn't wholly protected (a long run of holidays). */
    private const val LOOK_AHEAD_DAYS = 14

    fun read(fields: Map<String, FieldValue>?): ProtectedSetting? {
        if (fields == null) return null
        val from = fields[ProtectedFields.FROM]?.longOrNull?.toInt()?.takeIf { it in 0 until LocalClock.MINUTES_PER_DAY } ?: DEFAULT_FROM
        return ProtectedSetting(
            evenings = fields[ProtectedFields.EVENINGS]?.boolOrNull == true,
            fromMin = from,
            weekends = fields[ProtectedFields.WEEKENDS]?.boolOrNull == true,
            holdMail = fields[ProtectedFields.HOLD_MAIL]?.boolOrNull != false,
            openDay = fields[ProtectedFields.OPEN_DAY]?.longOrNull?.takeIf { it > 0 },
        )
    }

    /** Saturday, Sunday or a bank holiday with no shift in the work schedule, while weekends are protected. */
    fun wholeDay(s: ProtectedSetting?, day: Long, schedule: WorkSchedule, holidays: HolidayCalendar): Boolean {
        if (s == null || !s.weekends || s.openDay == day) return false
        val weekendish = CivilDate.isoDayOfWeek(day) >= 6 || holidays.isHoliday(day)
        return weekendish && !worksOn(schedule, holidays, day)
    }

    /** A shift really falls on [day] (a weekend shift counts; a bank holiday doesn't). */
    private fun worksOn(schedule: WorkSchedule, holidays: HolidayCalendar, day: Long): Boolean =
        WorkModeRules.isWorkDay(schedule, holidays, day)

    /**
     * When [day]'s protected evening starts (local minutes), or null: [ProtectedSetting.fromMin], or the shift's end
     * when that is later; none on a night shift or when evenings are off.
     */
    fun eveningFrom(s: ProtectedSetting?, day: Long, schedule: WorkSchedule, holidays: HolidayCalendar): Int? {
        if (s == null || !s.evenings || s.openDay == day) return null
        if (!worksOn(schedule, holidays, day)) return s.fromMin
        val hours = schedule.hoursOn(CivilDate.isoDayOfWeek(day))
        if (hours.crossesMidnight) return null
        return maxOf(s.fromMin, hours.endMinute).takeIf { it < LocalClock.MINUTES_PER_DAY }
    }

    /** [day]'s protected time as slots for the planner: the whole day, the evening to midnight, or none. */
    fun slots(s: ProtectedSetting?, day: Long, schedule: WorkSchedule, holidays: HolidayCalendar, cal: LocalCalendar): List<DayPlanner.Slot> {
        val end = cal.toEpochMs(day + 1, 0)
        if (wholeDay(s, day, schedule, holidays)) return listOf(DayPlanner.Slot(cal.toEpochMs(day, 0), end))
        val from = eveningFrom(s, day, schedule, holidays) ?: return emptyList()
        return listOf(DayPlanner.Slot(cal.toEpochMs(day, from), end))
    }

    /** The first day after [day] that isn't wholly protected (Monday after a weekend), or null within two weeks. */
    fun nextOpenDay(s: ProtectedSetting?, day: Long, schedule: WorkSchedule, holidays: HolidayCalendar): Long? =
        (1..LOOK_AHEAD_DAYS).map { day + it }.firstOrNull { !wholeDay(s, it, schedule, holidays) }

    /** "Evenings from 19:00 and weekends · nothing is planned into them". */
    fun summary(s: ProtectedSetting?): String {
        if (s == null || !s.on) return OFF_LINE
        val what = when {
            s.evenings && s.weekends -> "Evenings from ${LocalClock.formatMinute(s.fromMin)}, weekends and bank holidays"
            s.evenings -> "Evenings from ${LocalClock.formatMinute(s.fromMin)}"
            else -> "Weekends and bank holidays"
        }
        return "$what · nothing is planned into them"
    }

    /** "Waits for Mon 12 Oct" on a wholly protected day; null otherwise (the plan says "Won't fit today"). */
    fun waitLabel(s: ProtectedSetting?, day: Long, schedule: WorkSchedule, holidays: HolidayCalendar): String? {
        if (!wholeDay(s, day, schedule, holidays)) return null
        return "Waits for ${openLabel(s, day, schedule, holidays)}"
    }

    /** "tomorrow", "Mon 12 Oct", or "a day that isn't protected" past two weeks. */
    private fun openLabel(s: ProtectedSetting?, day: Long, schedule: WorkSchedule, holidays: HolidayCalendar): String {
        val next = nextOpenDay(s, day, schedule, holidays) ?: return "a day that isn't protected"
        return if (next == day + 1) "tomorrow" else CivilDate.shortLabel(next)
    }

    /**
     * The note under Plan my day: "Protected time: today is kept free, so no tasks are planned." or "Protected time:
     * nothing is planned after 19:00."; null when [day] has none.
     */
    fun planNote(s: ProtectedSetting?, day: Long, schedule: WorkSchedule, holidays: HolidayCalendar): String? {
        if (wholeDay(s, day, schedule, holidays)) return "Protected time: today is kept free, so no tasks are planned."
        val from = eveningFrom(s, day, schedule, holidays) ?: return null
        return "Protected time: nothing is planned after ${LocalClock.formatMinute(from)}."
    }

    /** The pane's line about today: "Today is protected · tasks wait for Mon 12 Oct" or "Protected from 19:00 tonight". */
    fun todayLine(s: ProtectedSetting?, today: Long, schedule: WorkSchedule, holidays: HolidayCalendar): String? {
        if (s != null && s.openDay == today) lifted(s, today, schedule, holidays)?.let { return it.line }
        if (wholeDay(s, today, schedule, holidays)) return "Today is protected · tasks wait for ${openLabel(s, today, schedule, holidays)}"
        val from = eveningFrom(s, today, schedule, holidays) ?: return null
        return "Protected from ${LocalClock.formatMinute(from)} tonight"
    }

    /** Slice 4: what lifting [day] means, worked out as if it weren't lifted. */
    enum class Lifted(val action: String, val line: String, val done: String, val again: String) {
        EVENING(
            "Not protected tonight",
            "Not protected tonight · you opened it · the planner and work mail as usual",
            "Tonight isn't protected · the planner may use it and work mail shows · Undo puts it back",
            "Protected again tonight",
        ),
        DAY(
            "Not protected today",
            "Not protected today · you opened it · the planner and work mail as usual",
            "Today isn't protected · the planner may use it and work mail shows · Undo puts it back",
            "Protected again today",
        ),
    }

    /** Whether [day] has protected time to lift (ignoring any lift already on it): a whole day, an evening, or none. */
    fun lifted(s: ProtectedSetting?, day: Long, schedule: WorkSchedule, holidays: HolidayCalendar): Lifted? {
        if (s == null || !s.on) return null
        val plain = s.copy(openDay = null)
        return when {
            wholeDay(plain, day, schedule, holidays) -> Lifted.DAY
            eveningFrom(plain, day, schedule, holidays) != null -> Lifted.EVENING
            else -> null
        }
    }

    /** Days ahead (after today) the Ask lines look for whole protected days and evenings that start later. */
    const val ASK_DAYS = 14
    /** At most this many Ask lines (the rules, today, whole days, evenings after a shift). */
    const val ASK_LINES = 4
    private const val ASK_LINE_MAX = 160

    /**
     * Protected time as MEKA describes it to the model (Ask's `protected` lines, slice 4), so "is Saturday protected?"
     * and "can you plan something tonight?" can be answered:
     *
     * - "Protected time · evenings from 19:00, weekends and bank holidays · nothing is planned into them" (or "Protected
     *   time · off · the planner may use evenings and weekends" — one line only, so "no" is an answer too);
     * - "Protected today · all day · tasks wait for Mon 12 Oct" / "Protected today · from 19:00 tonight" / "Protected
     *   today · none (a night shift)";
     * - "Whole days protected · Sun 11 Oct, Sat 17 Oct (bank holiday), … · open for a shift: Sat 24 Oct" (the next
     *   [ASK_DAYS] days, while weekends are protected);
     * - "Evenings that start later · Tue 13 Oct from 20:00 (after the shift) · Wed 14 Oct none (a night shift)" (only
     *   when a shift moves one, in the same days).
     *
     * Meka's own setting and work schedule in MEKA's words, so never untrusted. Each line at most 160 characters,
     * days left out from the end rather than cut mid-word.
     */
    fun askLines(s: ProtectedSetting?, today: Long, schedule: WorkSchedule, holidays: HolidayCalendar): List<String> {
        if (s == null || !s.on) return listOf("Protected time · off · the planner may use evenings and weekends")
        val out = mutableListOf("Protected time · " + summary(s).replaceFirstChar { it.lowercase() })
        out += "Protected today · " + when {
            s.openDay == today && lifted(s, today, schedule, holidays) != null -> "none · he opened today himself, just this once"
            wholeDay(s, today, schedule, holidays) -> "all day · tasks wait for ${openLabel(s, today, schedule, holidays)}"
            else -> eveningFrom(s, today, schedule, holidays)?.let { "from ${LocalClock.formatMinute(it)} tonight" }
                ?: if (s.evenings) "none (a night shift)" else "none"
        }
        val ahead = (1..ASK_DAYS).map { today + it }
        if (s.weekends) {
            val whole = ahead.filter { wholeDay(s, it, schedule, holidays) }.map { d ->
                CivilDate.shortLabel(d) + if (holidays.isHoliday(d)) " (bank holiday)" else ""
            }
            val open = ahead.filter { (CivilDate.isoDayOfWeek(it) >= 6 || holidays.isHoliday(it)) && !wholeDay(s, it, schedule, holidays) }
                .map { CivilDate.shortLabel(it) }
            val tail = if (open.isEmpty()) "" else " · open for a shift: " + open.joinToString(", ")
            out += fit("Whole days protected · ", whole.ifEmpty { listOf("none") }, tail)
        }
        if (s.evenings) {
            val moved = ahead.filterNot { wholeDay(s, it, schedule, holidays) }.mapNotNull { d ->
                val from = eveningFrom(s, d, schedule, holidays)
                when {
                    from == null -> CivilDate.shortLabel(d) + " none (a night shift)"
                    from != s.fromMin -> CivilDate.shortLabel(d) + " from ${LocalClock.formatMinute(from)} (after the shift)"
                    else -> null
                }
            }
            if (moved.isNotEmpty()) out += fit("Evenings that start later · ", moved, "", sep = " · ")
        }
        return out.take(ASK_LINES)
    }

    /** [head] + as many of [parts] as fit in [ASK_LINE_MAX] with [tail] (tail dropped first, then parts, "…" marking it). */
    private fun fit(head: String, parts: List<String>, tail: String, sep: String = ", "): String {
        fun build(n: Int, t: String) = head + parts.take(n).joinToString(sep) + (if (n < parts.size) "$sep…" else "") + t
        if (build(parts.size, tail).length <= ASK_LINE_MAX) return build(parts.size, tail)
        for (n in parts.size downTo 1) {
            build(n, "").takeIf { it.length <= ASK_LINE_MAX }?.let { return it }
        }
        return build(1, "").take(ASK_LINE_MAX)
    }

    // ---- Slice 5: the brief and the week ahead show protected time ----

    /**
     * [day]'s protected time as the week ahead marks it beside the day's hours: "Protected all day", "Protected from
     * 19:00", "Protected from 20:00 · after the shift", "Not protected · you opened it" (a lift on that day); null with
     * none (off, a night shift, a work day with evenings off).
     */
    fun dayMark(s: ProtectedSetting?, day: Long, schedule: WorkSchedule, holidays: HolidayCalendar): String? {
        if (s == null || !s.on) return null
        if (s.openDay == day && lifted(s, day, schedule, holidays) != null) return "Not protected · you opened it"
        if (wholeDay(s, day, schedule, holidays)) return "Protected all day"
        val from = eveningFrom(s, day, schedule, holidays) ?: return null
        return "Protected from ${LocalClock.formatMinute(from)}" + if (from != s.fromMin) " · after the shift" else ""
    }

    /**
     * The week ahead's line under its summary: "Protected · Sat, Sun all day · 5 evenings from 19:00" over [days]
     * (evenings that start later after a shift are counted too; each day's own mark says when). Null when off or none.
     */
    fun weekLine(s: ProtectedSetting?, days: List<Long>, schedule: WorkSchedule, holidays: HolidayCalendar): String? {
        if (s == null || !s.on) return null
        val whole = days.filter { wholeDay(s, it, schedule, holidays) }
        val evenings = days.count { !wholeDay(s, it, schedule, holidays) && eveningFrom(s, it, schedule, holidays) != null }
        val parts = listOfNotNull(
            whole.takeIf { it.isNotEmpty() }?.joinToString(", ") { LocalClock.DAY_SHORT[CivilDate.isoDayOfWeek(it) - 1] }?.let { "$it all day" },
            evenings.takeIf { it > 0 }?.let { n -> (if (n == 1) "1 evening" else "$n evenings") + " from ${LocalClock.formatMinute(s.fromMin)}" },
        )
        return if (parts.isEmpty()) null else "Protected · " + parts.joinToString(" · ")
    }

    /** [weekLine] read out: "Protected time: Sat, Sun all day, and 5 evenings from 7 o'clock." */
    fun weekSpoken(line: String?, s: ProtectedSetting?): String? {
        if (line == null || s == null) return null
        val parts = line.removePrefix("Protected · ").split(" · ").map {
            it.replace("from ${LocalClock.formatMinute(s.fromMin)}", "from ${BriefScriptRules.clock(s.fromMin)}")
        }
        return "Protected time: " + parts.joinToString(", and ") + "."
    }

    /**
     * The morning brief's line under the date: "Protected from 19:00 tonight", "Protected all day · tasks wait for Mon 19
     * Oct", "Not protected tonight · you opened it"; null when today has none.
     */
    fun briefLine(s: ProtectedSetting?, today: Long, schedule: WorkSchedule, holidays: HolidayCalendar): String? {
        if (s == null || !s.on) return null
        if (s.openDay == today) lifted(s, today, schedule, holidays)?.let { return "${it.action} · you opened it" }
        if (wholeDay(s, today, schedule, holidays)) return "Protected all day · tasks wait for ${openLabel(s, today, schedule, holidays)}"
        val from = eveningFrom(s, today, schedule, holidays) ?: return null
        return "Protected from ${LocalClock.formatMinute(from)} tonight"
    }

    /**
     * The spoken brief's sentence, after the kind of day: "Your evening is protected from 7 o'clock.", "Today is
     * protected, so no tasks are planned.", "You opened tonight, so it isn't protected."; null when today has none.
     */
    fun briefSentence(s: ProtectedSetting?, today: Long, schedule: WorkSchedule, holidays: HolidayCalendar): String? {
        if (s == null || !s.on) return null
        if (s.openDay == today) lifted(s, today, schedule, holidays)?.let {
            return if (it == Lifted.DAY) "You opened today, so it isn't protected." else "You opened tonight, so it isn't protected."
        }
        if (wholeDay(s, today, schedule, holidays)) return "Today is protected, so no tasks are planned."
        val from = eveningFrom(s, today, schedule, holidays) ?: return null
        return "Your evening is protected from ${BriefScriptRules.clock(from)}."
    }

    fun view(s: ProtectedSetting?, today: Long, schedule: WorkSchedule, holidays: HolidayCalendar, mailLine: String? = null): ProtectedView =
        ProtectedView(
            evenings = s?.evenings == true,
            fromMin = s?.fromMin ?: DEFAULT_FROM,
            weekends = s?.weekends == true,
            summary = summary(s),
            todayLine = todayLine(s, today, schedule, holidays),
            holdMail = s?.holdMail != false,
            mailLine = mailLine,
            openAction = lifted(s, today, schedule, holidays)?.takeIf { s?.openDay != today }?.action,
            openToday = s?.openDay == today && lifted(s, today, schedule, holidays) != null,
        )

    /**
     * Slice 3: whether [nowMs] is in protected time, and if so when it ends for mail: the morning ([ProtectedMailRules.MORNING_MIN])
     * of the next day that isn't wholly protected. Null when it isn't protected now (or protected time is off). The
     * small hours after a protected evening or day count as protected until that morning.
     */
    fun protectedUntil(s: ProtectedSetting?, nowMs: Long, schedule: WorkSchedule, holidays: HolidayCalendar, cal: LocalCalendar): Long? {
        if (s == null || !s.on) return null
        val today = cal.epochDayOf(nowMs)
        val minute = cal.minuteOfDay(nowMs)
        val morning = ProtectedMailRules.MORNING_MIN
        fun morningAfter(day: Long): Long {
            val next = if (!wholeDay(s, day + 1, schedule, holidays)) day + 1 else nextOpenDay(s, day, schedule, holidays) ?: (day + 1)
            return cal.toEpochMs(next, morning)
        }
        if (wholeDay(s, today, schedule, holidays)) return morningAfter(today)
        val from = eveningFrom(s, today, schedule, holidays)
        if (from != null && minute >= from) return morningAfter(today)
        if (minute < morning) {
            val yesterday = today - 1
            if (wholeDay(s, yesterday, schedule, holidays) || eveningFrom(s, yesterday, schedule, holidays) != null) {
                return cal.toEpochMs(today, morning)
            }
        }
        return null
    }
}

/** The protected-time setting on a replica. Every write is an op: offline-first, synced. */
class ProtectedTime(private val replica: Replica) {
    fun setting(): ProtectedSetting? = ProtectedRules.read(replica.entity(EntityTypes.CONTEXT_MODE, ProtectedRules.ENTITY_ID)?.fields)

    /** Sets both switches and the evening's start. False (nothing written) when it doesn't make sense or nothing changes. */
    fun set(evenings: Boolean, fromMin: Int, weekends: Boolean): Boolean {
        if (fromMin !in 0 until LocalClock.MINUTES_PER_DAY) return false
        val old = setting()
        if (old != null && old.evenings == evenings && old.fromMin == fromMin && old.weekends == weekends) return false
        replica.commitLocal(
            EntityTypes.CONTEXT_MODE, ProtectedRules.ENTITY_ID,
            mapOf(
                ProtectedFields.EVENINGS to evenings.fv(),
                ProtectedFields.FROM to fromMin.toLong().fv(),
                ProtectedFields.WEEKENDS to weekends.fv(),
            ),
        )
        return true
    }

    /** Slice 4: lifts protected time for [day] only (null puts it back). False when nothing changes. */
    fun setOpenDay(day: Long?): Boolean {
        if (setting()?.openDay == day) return false
        replica.commitLocal(EntityTypes.CONTEXT_MODE, ProtectedRules.ENTITY_ID, mapOf(ProtectedFields.OPEN_DAY to (day ?: 0L).fv()))
        return true
    }

    /** Slice 3: business mail waits until morning ([hold]) or shows as before. False when nothing changes. */
    fun setHoldMail(hold: Boolean): Boolean {
        if ((setting()?.holdMail ?: true) == hold) return false
        replica.commitLocal(EntityTypes.CONTEXT_MODE, ProtectedRules.ENTITY_ID, mapOf(ProtectedFields.HOLD_MAIL to hold.fv()))
        return true
    }
}

/**
 * Protected time, slice 3 (Meka's item: "work email and work notifications wait until morning"): which emails in "From
 * your mail" wait while it's protected. Work mail is the account he labelled Business. Non-AI, pure.
 */
object ProtectedMailRules {
    /** "Morning": held mail comes back at 07:00 on the next day that isn't wholly protected. */
    const val MORNING_MIN = 7 * 60
    const val LABEL = "Work mail"
    const val ON = "Waits till morning"
    const val OFF = "Shows as it comes"
    const val NOTE = "Emails to the Business account wait while it's protected and come back at 07:00 on your next " +
        "day that isn't. Security alerts and replies you've started still show."

    /** An email that waits: the business account's, not a security alert or a note to self, and no reply of his on the way or failed. */
    fun holdable(i: MailItem): Boolean =
        i.account == MailLabel.BUSINESS.label && i.security == null && i.note == null &&
            !MailNeedsRules.replyWaiting(i) && !MailNeedsRules.replyFailed(i)

    /** The emails held now: none unless [s] holds mail and [heldUntilMs] (from [ProtectedRules.protectedUntil]) is set. */
    fun held(items: List<MailItem>, s: ProtectedSetting?, heldUntilMs: Long?): Set<String> {
        if (s == null || !s.holdMail || heldUntilMs == null) return emptySet()
        return items.filter(::holdable).map { it.id }.toSet()
    }

    /** "07:00", "tomorrow 07:00", "Mon 19 Oct 07:00". */
    fun untilLabel(untilMs: Long, nowMs: Long, cal: LocalCalendar): String {
        val day = cal.epochDayOf(untilMs)
        val today = cal.epochDayOf(nowMs)
        val time = LocalClock.formatMinute(cal.minuteOfDay(untilMs))
        return when (day) {
            today -> time
            today + 1 -> "tomorrow $time"
            else -> "${CivilDate.shortLabel(day)} $time"
        }
    }

    /** The card's line: "2 business emails wait till tomorrow 07:00 · protected time"; null with none held. */
    fun cardLine(n: Int, untilMs: Long?, nowMs: Long, cal: LocalCalendar): String? {
        if (n <= 0 || untilMs == null) return null
        val what = if (n == 1) "1 business email waits" else "$n business emails wait"
        return "$what till ${untilLabel(untilMs, nowMs, cal)} · protected time"
    }

    /** The pane's line: "Held now · 2 business emails till tomorrow 07:00"; null with none held. */
    fun paneLine(n: Int, untilMs: Long?, nowMs: Long, cal: LocalCalendar): String? {
        if (n <= 0 || untilMs == null) return null
        val what = if (n == 1) "1 business email" else "$n business emails"
        return "Held now · $what till ${untilLabel(untilMs, nowMs, cal)}"
    }

    // ---- Slice 6: the morning note ----

    const val NOTICE_PREFIX = "protected-mail:"
    /** The morning note stands until midday; after that the card itself is enough. */
    const val NOTICE_UNTIL_MIN = 12 * 60
    private const val LOOK_BACK_DAYS = 14

    /**
     * Slice 6: when the protected time that ended at [day]'s morning began: the evening's start, or midnight of the
     * first wholly protected day before it. Null when [day]'s morning didn't end any protected time (a lifted evening,
     * protected time off, a weekday after an open evening).
     */
    fun windowStart(s: ProtectedSetting?, day: Long, schedule: WorkSchedule, holidays: HolidayCalendar, cal: LocalCalendar): Long? {
        val morning = cal.toEpochMs(day, MORNING_MIN)
        if (ProtectedRules.protectedUntil(s, morning - 60_000L, schedule, holidays, cal) != morning) return null
        var start: Long? = null
        var d = day - 1
        repeat(LOOK_BACK_DAYS) {
            if (ProtectedRules.wholeDay(s, d, schedule, holidays)) {
                start = cal.toEpochMs(d, 0)
                d -= 1
            } else {
                ProtectedRules.eveningFrom(s, d, schedule, holidays)?.let { start = cal.toEpochMs(d, it) }
                return start ?: cal.toEpochMs(day, 0)
            }
        }
        return start ?: cal.toEpochMs(day, 0)
    }

    /** "overnight" (from last evening), "since Fri 19:00", "since Sat" (a whole day). */
    fun sinceLabel(startMs: Long, day: Long, cal: LocalCalendar): String {
        val startDay = cal.epochDayOf(startMs)
        val minute = cal.minuteOfDay(startMs)
        if (startDay == day - 1 && minute > 0) return "overnight"
        val name = LocalClock.DAY_SHORT[CivilDate.isoDayOfWeek(startDay) - 1]
        return if (minute == 0) "since $name" else "since $name ${LocalClock.formatMinute(minute)}"
    }

    /**
     * Slice 6 (the item's "held work notifications arrive as one morning digest with a count"): one heads-up at 07:00
     * on the morning protected time ends, when business mail held through it still needs him — "3 work emails waited
     * overnight" · "Sam Taylor, Alex Green and 1 more · in From your mail". Once per morning (one key a day), gone at
     * midday, opening Needs you; nothing when Work mail shows as it comes, nothing came, or it's still protected.
     * Never the subjects or the words: only who wrote.
     */
    fun morningNotice(
        items: List<MailItem>, s: ProtectedSetting?, nowMs: Long, schedule: WorkSchedule, holidays: HolidayCalendar, cal: LocalCalendar,
    ): Notice? {
        if (s == null || !s.on || !s.holdMail) return null
        val day = cal.epochDayOf(nowMs)
        val morning = cal.toEpochMs(day, MORNING_MIN)
        val until = cal.toEpochMs(day, NOTICE_UNTIL_MIN)
        if (nowMs < morning || nowMs >= until) return null
        if (ProtectedRules.protectedUntil(s, nowMs, schedule, holidays, cal) != null) return null
        val start = windowStart(s, day, schedule, holidays, cal) ?: return null
        val waited = items.filter { holdable(it) && it.receivedAtMs in start until morning && MailNeedsRules.showing(it, nowMs) }
            .sortedWith(compareByDescending<MailItem> { it.receivedAtMs }.thenBy { it.id })
        if (waited.isEmpty()) return null
        val n = waited.size
        val title = (if (n == 1) "1 work email waited " else "$n work emails waited ") + sinceLabel(start, day, cal)
        return Notice(
            key = "$NOTICE_PREFIX$day", source = NoticeSource.PROTECTED_MAIL, tier = NoticeTier.HEADS_UP,
            title = title, text = "${whoLine(waited)} · in From your mail",
            atMs = morning, target = NoticeTarget.NEEDS_YOU, expiresAtMs = until,
        )
    }

    /** "Sam Taylor", "Sam Taylor and Alex Green", "Sam Taylor, Alex Green and 2 more" (each sender once, newest first). */
    fun whoLine(items: List<MailItem>): String {
        val names = items.map { it.from.trim().ifEmpty { "Someone" } }.distinct()
        return when {
            names.size == 1 -> names[0]
            names.size == 2 -> "${names[0]} and ${names[1]}"
            else -> "${names[0]}, ${names[1]} and ${names.size - 2} more"
        }
    }
}
