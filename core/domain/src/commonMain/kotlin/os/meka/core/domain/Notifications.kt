package os.meka.core.domain

import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Notification governor v1 (build plan M1, ADR-001: shared, testable). Every local notification MEKA posts goes
 * through these rules, on the Fold and the Mac alike. Non-AI: tiers, quiet hours and two digests a day.
 *
 * - [NoticeTier.CRITICAL] posts at once, even in quiet hours.
 * - [NoticeTier.ACTION] posts at once, or when quiet hours end.
 * - [NoticeTier.HEADS_UP] posts at once; one that falls in quiet hours rides in the next digest instead (or, with
 *   digests off, posts when quiet hours end). One that goes stale before it can post is dropped.
 * - [NoticeTier.DIGEST] is summed up in the midday and evening digests (12:30 and 18:00 until changed).
 * - [NoticeTier.SILENT] is never posted: it only shows in the app.
 *
 * Most reminders here are soft milestones (ADR-007): the platforms wake with inexact, windowed alarms. Precision is a
 * property of each notice: event reminders are CLOCK, and the Fold uses an exact alarm for them when Meka allows it.
 */
enum class NoticeTier(val label: String) {
    CRITICAL("Critical"),
    ACTION("Needs a decision"),
    HEADS_UP("Heads-up"),
    DIGEST("Digest"),
    SILENT("App only");

    /** Counts as an interruption in the weekly review (ADR-013). */
    val interrupts: Boolean get() = this == CRITICAL || this == ACTION || this == HEADS_UP
}

/** ADR-007: CLOCK reminders may use an exact alarm when allowed; everything else is windowed. */
enum class NoticePrecision { CLOCK, SOFT }

/**
 * Where tapping a notification takes you. [LEAVE] is Ask → More → Annual leave with its pane up (annual leave, slice 2);
 * [WEEK_AHEAD] is Ask → More → Week ahead with its pane up (Sunday week ahead, slice 1).
 */
enum class NoticeTarget { TODAY, NEEDS_YOU, LISTS, GOALS, REVIEW, LEAVE, WEEK_AHEAD, VAULT, KIDS, MONEY }

/**
 * A button on a posted notification, answered without opening the app (Gym slice 2b: "Did you go?" offers Went and
 * Didn't go). The app hands the notice's key and the action back to the core, which checks it still applies.
 */
enum class NoticeAction(val label: String) {
    WENT("Went"),
    DIDNT_GO("Didn't go"),
    /**
     * "Leaving now" ([LeavingRules]): sends the notification's own words to his wife, on Meka's tap (Level 3). Fold only.
     */
    SEND_MESSAGE("Send"),
    /** Opens the chat with the words filled in, for Meka to change and send himself. Fold only. */
    EDIT_MESSAGE("Edit"),
    /** Copies the words (the Mac, which can't send a WhatsApp message). Mac only. */
    COPY_MESSAGE("Copy"),
    /**
     * Sunday's week ahead ([WeekAheadRules.notices], slice 2): opens the week ahead reading itself aloud in MEKA's
     * voice. Both apps; it changes nothing.
     */
    LISTEN("Listen"),
    /**
     * Bills due ([BillRules.notices]): opens the page Meka saved as where he pays the bill ([Notice.link]). Both apps;
     * MEKA never pays anything.
     */
    PAY("Pay"),
    /**
     * The commute counts everywhere, slice 7 ([CommuteDisruptionRules.notices]): tomorrow's trains are in trouble, so
     * the wake alarm moves ten minutes earlier, on Meka's tap only. Both apps.
     */
    WAKE_EARLIER("Wake 10 min earlier"),
    /**
     * Live trains, slice 5 ([TrainWakeRules.notices]): this morning's usual train is cancelled or late, so the wake
     * alarm moves to the earlier time the heads-up names, on Meka's tap only. Both apps.
     */
    WAKE_FOR_TRAIN("Wake earlier"),
    /**
     * Weekly reflection, slice 4 ([ReviewRules.laterMinute]): Sunday's weekly review heads-up comes back in two hours
     * (never after 22:00), on both devices. Both apps.
     */
    LATER_TONIGHT(ReviewRules.LATER_LABEL),
    /**
     * Kids' admin, slice 6 ([KidsRules.notices]): the child's sizes still fit, so the check counts as done today and the
     * next one is three months on. Both apps; it changes no size.
     */
    ALL_STILL_FIT(KidsRules.ALL_FIT),
    /**
     * Done for the day, completeness ([ShutdownRules.laterMinute]): the evening card and its heads-up come back an hour
     * on (never after 23:00), on both devices. Both apps.
     */
    SHUT_DOWN_LATER(ShutdownRules.LATER_LABEL);

    /** Whether a device that can [send] messages (the Fold) shows it: Send and Edit there, Copy elsewhere. */
    fun shownOn(send: Boolean): Boolean = when (this) {
        SEND_MESSAGE, EDIT_MESSAGE -> send
        COPY_MESSAGE -> !send
        else -> true
    }
}

/** What a notice is about. Each source has a default tier, which the owner can lower (never raise). */
enum class NoticeSource(val label: String, val defaultTier: NoticeTier) {
    RENEWAL_CANCEL_BY("Cancel-by dates", NoticeTier.HEADS_UP),
    FAST_GOAL("Fasting goal reached", NoticeTier.HEADS_UP),
    /** Once a day during an extended fast (Fasting v2): "5-day fast · Day 3 of 5". */
    FAST_CHECK_IN("Fasting check-ins", NoticeTier.HEADS_UP),
    /** Half an hour before a booked session (Gym): "Gym · Push at 17:45" · "Leave by 17:30". */
    SESSION_LEAVE("Time to go (booked sessions)", NoticeTier.HEADS_UP),
    /** When a booked session's slot is over and it isn't answered: "Did you go?". */
    SESSION_ASK("Did you go? (booked sessions)", NoticeTier.HEADS_UP),
    BRIEF("Morning brief", NoticeTier.HEADS_UP),
    SHUTDOWN("Time to shut down the day", NoticeTier.HEADS_UP),
    WEEKLY_REVIEW("Weekly review", NoticeTier.HEADS_UP),
    /** Remind me / Leave by on a calendar event (calendar actions); Meka sets each one. */
    EVENT_REMINDER("Event reminders", NoticeTier.HEADS_UP),
    /** Remind me on a task (task detail); Meka sets each one. */
    TASK_REMINDER("Task reminders", NoticeTier.HEADS_UP),
    /** A fixture's kick-off moved (the fixtures feed, seen by the server). */
    FIXTURE_MOVED("Kick-off changes", NoticeTier.HEADS_UP),
    /** A calendar sign-in about to end or already expired (Reliability first, item 2; [SignInRules]). */
    SIGN_IN("Calendar sign-ins", NoticeTier.HEADS_UP),
    /** The call assistant's credit under £2, or the assistant paused for want of it ([CallCreditRules]). */
    CALL_CREDIT("Call assistant credit", NoticeTier.HEADS_UP),
    /**
     * School rhythm ([SchoolRules.notices]): the week-ahead "who's covering?" question, something to pay or send back
     * two days before, and a trip or non-uniform day the evening before.
     */
    SCHOOL("School", NoticeTier.HEADS_UP),
    /** Date night, slice 2 ([DateNightRules.notices]): a week before a kept night that isn't booked, at 09:00. */
    DATE_NIGHT("Date night", NoticeTier.HEADS_UP),
    /** Work wins log ([WinRules.notices]): Friday at 16:00, "What went well this week?", until a win is kept. */
    WINS("What went well this week (Friday)", NoticeTier.HEADS_UP),
    /**
     * Annual leave, slice 2 ([LeaveRules.notices]): a school holiday coming up with nothing booked, at 09:00 eight
     * weeks before its first work day — "Half term · nothing booked" · "book before the good weeks go".
     */
    LEAVE("Book leave for school holidays", NoticeTier.HEADS_UP),
    /**
     * Appointment nudges, slice 2b ([AppointmentRules.notices]): the day one starts asking, at 09:00 — "Dentist · due
     * Mon 2 Nov" · "Last went Wed 1 Apr. Booked the next one?".
     */
    APPOINTMENTS("Appointments due", NoticeTier.HEADS_UP),
    /**
     * Goals, properly, item 6 ([GoalRhythmRules.notices]): one gentle nudge the day a goal has gone 14 days without
     * moving, and the 1st of the month's look back at last month's goals, both at 09:00.
     */
    GOALS("Goals: quiet goals and the month's review", NoticeTier.HEADS_UP),
    /** The Sunday week ahead ([WeekAheadRules.notices]): Sunday at 18:00, "Your week ahead" · the coming week in a line. */
    WEEK_AHEAD("Sunday week ahead", NoticeTier.HEADS_UP),
    /** Bin days ([BinRules.notices]): the evening before a collection at 19:00, "Black bin and food caddy out tonight". */
    BINS("Bins out the night before", NoticeTier.HEADS_UP),
    /**
     * Gift planner ([GiftRules.notices]): two weeks before a birthday or anniversary and on its order-by day, at 09:00 —
     * "Mum's birthday in 2 weeks · Sat 31 Oct" · "Ideas: scarf · order by Tue 27 Oct"; "Order Mum's gift today".
     */
    GIFTS("Gifts: birthdays and anniversaries", NoticeTier.HEADS_UP),
    /**
     * Car on autopilot ([CarRules.notices]): the MOT, car tax, insurance and service, a heads-up at 09:00 four weeks
     * and a week before each — "MOT due Thu 12 Nov · AB12 CDE" · "In 4 weeks · an MOT up to a month early keeps the same date".
     */
    CAR("Car: MOT, tax, insurance and service", NoticeTier.HEADS_UP),
    /**
     * UK money deadlines ([MoneyDateRules.notices]): Self Assessment, the ISA allowance, energy and broadband contract
     * ends and a yearly pension check-in, a heads-up at 09:00 — "ISA allowance ends Mon 5 Apr" · "In 4 weeks · this tax
     * year's allowance doesn't carry over". Dates only, never advice. Money manager slice 18 adds the year in review's
     * heads-up on 2 January ([MoneyYear.notices], no amounts); Money manager (7) the month in review's on the 2nd of
     * the other months ([MoneyMonthReview.notices], no amounts).
     */
    MONEY("Money dates: tax, ISA, contracts, pension, month and year in review", NoticeTier.HEADS_UP),
    /**
     * Bills due ([BillRules.notices]): three days before each bill at 09:00, with the amount and Pay when a pay link is
     * saved — "Council tax due Thu 15 Oct · £180" · "In 3 days · Pay opens oakfield.gov.uk". Never pays.
     */
    BILLS("Bills due: a few days before each payment", NoticeTier.HEADS_UP),
    /**
     * Money manager, slice 7 ([MoneyCards.notices]): three days before each card payment at 09:00 until it's marked
     * Paid, and 30 and 7 days before a 0% period ends — "Credit card payment due Fri 23 Oct" · "In 3 days · pay in full
     * to avoid interest · …". No amounts (the figures stay behind the fingerprint). Never pays.
     */
    CARDS("Cards: payments due and 0% ending", NoticeTier.HEADS_UP),
    /**
     * Money manager, nudges ([MoneyNudges.notices]): something new worth a look in Money because of recent payments —
     * the watchdog's (possibly charged twice, bigger than usual, fees) and the tips from his own numbers — from 09:00,
     * "Possibly charged twice · Amazon" · "Worth a look · in Money, behind your fingerprint". No amounts.
     */
    MONEY_LOOK("Money: worth a look and tips from your numbers", NoticeTier.HEADS_UP),
    /**
     * Trading tracker, slice 3 ([TradingAlerts.notices]): a position near liquidation (Critical: it comes in quiet hours)
     * and an account well below this month's high (a heads-up), at his levels — "ETH long near liquidation" · "8% from
     * liquidation · Main account · …". No amounts. Lowering it here lowers both. Slice 7 adds a big order and a day's
     * high funding (heads-ups, measured against the account, no amounts).
     */
    TRADING("Trading: liquidation, drops, big orders, funding", NoticeTier.HEADS_UP),
    /**
     * Life admin hour ([LifeAdminRules.notices]): at the week's hour, "Life admin hour · 4 jobs" · "Renew passport · Call
     * the GP · and 2 more". None when skipped or nothing is waiting.
     */
    LIFE_ADMIN("Life admin hour", NoticeTier.HEADS_UP),
    /**
     * Travel mode, slice 2b ([TripRules.notices]): the evening before a trip's first day at 18:00 — "Barcelona tomorrow ·
     * Flight BA478 07:40" · "ref ABC123 · then Hotel Arts until Wed 18 Nov".
     */
    TRIPS("Trips: the evening before you go", NoticeTier.HEADS_UP),
    /**
     * The Vault, slice 1 ([DocumentRules.notices]): a document's renewal window opening (six months before a passport),
     * a month and a week before it runs out and on the day, at 09:00 — "Theo passport expires Thu 20 May 2027".
     */
    DOCUMENTS("Vault: documents running out", NoticeTier.HEADS_UP),
    /**
     * Inbox autopilot, slice 7b ([MailMomentRules.notices]): at the ten-minute inbox's time while emails need him —
     * "Ten-minute inbox · 6 need you" · "About 12 min · Inbox clear 4 days running". None on a Not today, a clear inbox,
     * or when the moment falls in protected time (the card still waits in Needs you).
     */
    MAIL_MOMENT("Ten-minute inbox", NoticeTier.HEADS_UP),
    /**
     * Deliveries, slice 2 ([ParcelRules.notices]): when a shipping email says a parcel is out for delivery today —
     * "Parcel out for delivery · Amazon" · "Between 10:15 and 11:15 · with DPD". Gone once it's delivered.
     */
    PARCELS("Parcels out for delivery", NoticeTier.HEADS_UP),
    /**
     * Returns and warranties, completeness ([MailReturnRules.notices]): two days before an order's last day to return
     * it and on the day, at 09:00 — "Last day to return · Amazon order is Fri 13 Nov · £40" · "In 2 days · in Lists:
     * Sent it back or Keeping it". Gone once he taps either.
     */
    RETURNS("Returns: the last days to send things back", NoticeTier.HEADS_UP),
    /**
     * Car and parking, slice 2 ([ParkingTimerRules.notices]): ten minutes before a parking timer ends — "Parking ends
     * at 15:30" · "10 min left · extend it in your parking app, or move the car". Again for an extended time.
     */
    PARKING("Parking timer", NoticeTier.HEADS_UP),
    /**
     * Kids' admin, slice 6 ([KidsRules.notices]): three months after a child's sizes were checked, on the Saturday at
     * 09:00 (again every four weeks while unchecked) — "Time to check Alex's sizes" · "Last checked Sat 11 Jul · …".
     */
    KIDS("Kids: time to check their sizes", NoticeTier.HEADS_UP),
    /**
     * Barça match day, slice 1 ([BarcaMatchRules.notices]): an hour before each Barça match in the Fixtures calendar,
     * "Barça v Getafe · 20:00" · "LaLiga · kick-off in an hour · on Premier Sports 1".
     */
    BARCA("Barça match day", NoticeTier.HEADS_UP),
    /**
     * Barça match day, slice 2 ([BarcaMatchRules.goalNotices]): each goal while a Barça match is on — "Goal · Barça
     * 1–0 Getafe" · "Lewandowski 23' · LaLiga". Turn it off (Silent) for full time only.
     */
    BARCA_GOALS("Barça goals", NoticeTier.HEADS_UP),
    /**
     * Barça match day, slice 2 ([BarcaMatchRules.fullTimeNotices]): the final score — "Full time · Barça 2–1 Getafe" ·
     * "Barça won · Lewandowski 23', Yamal 51' · LaLiga". Turn it off for goals only.
     */
    BARCA_RESULT("Barça full time", NoticeTier.HEADS_UP),
    /**
     * Meeting prep, slice 1 ([MeetingPrepRules.notices]): fifteen minutes (his choice since slice 2, or off) before a meeting with other people — "Prep ·
     * Quote review at 14:00" · "With Sam Taylor · last email Tue 13:02 · you promised 1". Opens Today.
     */
    MEETING_PREP("Meeting prep (before meetings)", NoticeTier.HEADS_UP),
    /**
     * Balance view, slice 4 ([BalanceRules.notices]): at most once a week, Thursday at 17:00, when family, health or his
     * own time is well short of its target even with what's planned — "Family time is short this week" · "3 h of 8 h
     * with what's planned · 5 h to find by Sunday". Opens Review.
     */
    BALANCE("Balance: an area short of its target (Thursday)", NoticeTier.HEADS_UP),
    /**
     * Protected time, slice 6 ([ProtectedMailRules.morningNotice]): at 07:00 on the morning protected time ends, one
     * note for the business mail that waited through it — "3 work emails waited overnight" · "Sam Taylor, Alex Green
     * and 1 more · in From your mail". Opens Needs you.
     */
    PROTECTED_MAIL("Work mail that waited (protected time)", NoticeTier.HEADS_UP),
    RENEWAL("Renewals and bills due", NoticeTier.DIGEST),
    CHASE("Things to chase", NoticeTier.DIGEST),
    /** People, slice 1: a promise Meka made whose day has come ("Promised: Fix the bike" · "Jordan · due today"). */
    PROMISE("Promises you made", NoticeTier.DIGEST),
    REVIEW("Decisions to review", NoticeTier.DIGEST),
    OVERDUE("Overdue tasks", NoticeTier.DIGEST),
    /** Rain or snow due at today's plans you go out for (Weather): "Light rain at 17:30 — Training at AB1". */
    WEATHER("Weather for your plans", NoticeTier.DIGEST),
    /**
     * A line on Meka's route to or from work not running well during the commute (Places item 4, from TfL's status
     * until live departures land): "South Rail severe delays" · "North Rail to Riverside is running".
     */
    TRAINS("Trains on your route", NoticeTier.HEADS_UP),
    /**
     * "Leaving now" ([LeavingRules.notices]): when an office day's work ends, the drafted "Leaving now, home about 18:40"
     * for his wife, with Send (Meka's tap) and Edit; once more if the trains change on the way.
     */
    LEAVING("Leaving now (a draft to send home)", NoticeTier.HEADS_UP),
    /**
     * A request card from someone Meka watches (V1, requests): summed in the next digest, or a heads-up straight away
     * for the people he marked "Notify straight away" ([NotificationSettings.requestNow]).
     */
    REQUEST("Requests from people you watch", NoticeTier.DIGEST),
    /**
     * The approvals inbox, slice 2 ([ApprovalRules.notices]): a request to trade, pay or share a document is a Needs a
     * decision notice when it arrives (held in quiet hours) and Critical in its last 15 minutes, posted in the same
     * place so it replaces the first. Never an Approve button on the notification: tapping opens Needs you, where the
     * fingerprint is asked. Not adjustable ([adjustable]): ADR-006 keeps these in front of Meka.
     */
    APPROVAL("Approvals (trades, payments, documents)", NoticeTier.ACTION);

    /** Whether the settings screens offer a lower tier for it (approvals: never). */
    val adjustable: Boolean get() = this != APPROVAL

    companion object {
        /** The tiers a source can be set to in the settings screen. */
        val CHOICES = listOf(NoticeTier.HEADS_UP, NoticeTier.DIGEST, NoticeTier.SILENT)
        /** The sources the settings screens list, in order. */
        val ADJUSTABLE: List<NoticeSource> get() = entries.filter { it.adjustable }
    }
}

data class Notice(
    /** Stable: a notice posts once per key (a digest sums up what is still due each time). */
    val key: String,
    val source: NoticeSource,
    val tier: NoticeTier,
    val title: String,
    val text: String,
    /** When it becomes due. */
    val atMs: Long,
    val target: NoticeTarget,
    /** After this it is stale and never posts on its own. */
    val expiresAtMs: Long? = null,
    val precision: NoticePrecision = NoticePrecision.SOFT,
    /** Buttons on the posted notification, in order; none for most. */
    val actions: List<NoticeAction> = emptyList(),
    /**
     * Where it is posted, when that isn't its own [key]: notices sharing one replace each other on screen (an
     * approval's "expires soon" replaces its first notice). See [postId].
     */
    val postAs: String? = null,
    /** The web page [NoticeAction.PAY] opens (Bills due: where Meka pays it); http(s) only, null for most. */
    val link: String? = null,
) {
    /** The platform notification's identity: [postAs], else [key]. */
    val postId: String get() = postAs ?: key
}

/** Quiet hours as local minutes of the day; an end at or before the start crosses midnight. */
data class QuietHours(val enabled: Boolean, val startMinute: Int, val endMinute: Int) {
    init {
        require(startMinute in 0 until LocalClock.MINUTES_PER_DAY && endMinute in 0 until LocalClock.MINUTES_PER_DAY) { "minutes must be 0..1439" }
    }

    fun isQuiet(minuteOfDay: Int): Boolean {
        if (!enabled || startMinute == endMinute) return false
        return if (endMinute > startMinute) minuteOfDay in startMinute until endMinute
        else minuteOfDay >= startMinute || minuteOfDay < endMinute
    }

    fun isQuietAt(epochMs: Long, cal: LocalCalendar): Boolean = isQuiet(cal.minuteOfDay(epochMs))

    /** When the quiet hours that include [epochMs] end; [epochMs] itself when it isn't quiet. */
    fun endAfter(epochMs: Long, cal: LocalCalendar): Long {
        if (!isQuietAt(epochMs, cal)) return epochMs
        val day = cal.epochDayOf(epochMs)
        val endDay = if (endMinute > cal.minuteOfDay(epochMs)) day else day + 1
        return cal.toEpochMs(endDay, endMinute)
    }

    /** "22:00–07:00" or "Off". */
    val summary: String get() = if (!enabled) "Off" else "${LocalClock.formatMinute(startMinute)}–${LocalClock.formatMinute(endMinute)}"

    fun encode(): String = "${if (enabled) 1 else 0};$startMinute;$endMinute"

    companion object {
        val DEFAULT = QuietHours(true, 22 * 60, 7 * 60)

        fun decode(s: String?): QuietHours? {
            val p = s?.split(';') ?: return null
            if (p.size != 3) return null
            return try { QuietHours(p[0] == "1", p[1].toInt(), p[2].toInt()) } catch (e: IllegalArgumentException) { null }
        }
    }
}

/** What this device posts. Kept on the device; the rest of the settings sync. */
enum class DeviceAlerts(val label: String) {
    ALL("Everything"),
    /** Digests (with the heads-ups riding in them) and anything critical. */
    DIGESTS("Digests only"),
    OFF("Off"),
}

/** Synced notification settings: quiet hours, digest times and any source moved to a lower tier. */
data class NotificationSettings(
    val quiet: QuietHours = QuietHours.DEFAULT,
    /** Local minutes of the day, ascending; empty means no digests. */
    val digestMinutes: List<Int> = DEFAULT_DIGESTS,
    val tiers: Map<NoticeSource, NoticeTier> = emptyMap(),
    /** Watched people whose requests are a heads-up straight away rather than digest items (names as Meka set them). */
    val requestNow: Set<String> = emptySet(),
) {
    val digestsOn: Boolean get() = digestMinutes.isNotEmpty()
    fun tierFor(source: NoticeSource): NoticeTier = tiers[source] ?: source.defaultTier
    fun hasDigest(minute: Int): Boolean = minute in digestMinutes
    /** Whether a request from [name] is a heads-up straight away (spelling and case don't matter). */
    fun notifiesNow(name: String): Boolean = People.key(name).let { k -> requestNow.any { People.key(it) == k } }

    companion object {
        const val MIDDAY = 12 * 60 + 30
        const val EVENING = 18 * 60
        val DEFAULT_DIGESTS = listOf(MIDDAY, EVENING)
        val DEFAULT = NotificationSettings()

        /** "Midday digest" for 12:30, "Evening digest" for 18:00, else "Digest". */
        fun digestName(minute: Int): String = when {
            minute < 11 * 60 -> "Morning digest"
            minute < 17 * 60 -> "Midday digest"
            else -> "Evening digest"
        }

        /** One name per line, sorted, cleaned; empty for nobody. */
        fun encodeRequestNow(names: Set<String>): String =
            names.mapNotNull(RequestWatchRules::clean).distinctBy(People::key).sortedBy { it.lowercase() }.joinToString("\n")

        fun decodeRequestNow(s: String?): Set<String> =
            s.orEmpty().split('\n').mapNotNull(RequestWatchRules::clean).distinctBy(People::key).toSet()

        fun encodeDigests(minutes: List<Int>): String = minutes.sorted().joinToString(",")

        fun decodeDigests(s: String?): List<Int>? {
            if (s == null) return null
            if (s.isBlank()) return emptyList()
            val m = s.split(',').map { it.trim().toIntOrNull() ?: return null }
            return m.filter { it in 0 until LocalClock.MINUTES_PER_DAY }.distinct().sorted()
        }

        /** "SHUTDOWN=DIGEST,FAST_GOAL=SILENT". Unknown names (from a newer version) are ignored. */
        fun encodeTiers(t: Map<NoticeSource, NoticeTier>): String =
            t.entries.sortedBy { it.key.ordinal }.joinToString(",") { "${it.key.name}=${it.value.name}" }

        fun decodeTiers(s: String?): Map<NoticeSource, NoticeTier> {
            if (s.isNullOrBlank()) return emptyMap()
            return s.split(',').mapNotNull { pair ->
                val (k, v) = pair.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
                val source = NoticeSource.entries.firstOrNull { it.name == k } ?: return@mapNotNull null
                val tier = NoticeTier.entries.firstOrNull { it.name == v } ?: return@mapNotNull null
                // Only lowering is allowed; a raised tier from anywhere is ignored, and so is any tier for approvals.
                if (tier.ordinal < source.defaultTier.ordinal || !source.adjustable) null else source to tier
            }.toMap()
        }
    }
}

/** What this device has already posted. Local only (never synced); old entries are pruned. */
data class GovernorState(val delivered: Map<String, Long> = emptyMap(), val lastDigestSlotMs: Long = 0L) {
    fun encode(): String = buildString {
        append(lastDigestSlotMs)
        delivered.forEach { (k, v) -> append('\n').append(k.replace('\n', ' ').replace('\t', ' ')).append('\t').append(v) }
    }

    companion object {
        fun decode(s: String?): GovernorState {
            if (s.isNullOrEmpty()) return GovernorState()
            val lines = s.split('\n')
            val slot = lines.first().toLongOrNull() ?: 0L
            val delivered = lines.drop(1).mapNotNull { l ->
                val i = l.lastIndexOf('\t')
                if (i <= 0) null else l.substring(i + 1).toLongOrNull()?.let { l.substring(0, i) to it }
            }.toMap()
            return GovernorState(delivered, slot)
        }
    }
}

/** One digest notification. */
data class Digest(
    /** "Evening digest · 3 things" (or "4 things need you" for a burst of heads-ups). */
    val title: String,
    /** "1 renewal due · 2 to chase · 1 overdue task" */
    val summary: String,
    /** One line per item, most pressing first, at most [Governor.MAX_DIGEST_LINES] (then "+2 more"). */
    val lines: List<String>,
    val count: Int,
    /** Lock-screen text: no names, no titles. */
    val publicTitle: String,
    val target: NoticeTarget,
)

data class GovernorResult(
    /** Post each of these now, on its tier's channel. */
    val post: List<Notice>,
    /** Post this digest now (it replaces the previous one). */
    val digest: Digest?,
    /** Save this and pass it back next time. */
    val state: GovernorState,
    /** When to look again (an inexact alarm); null when nothing is coming. */
    val nextWakeMs: Long?,
    val nextWakePrecision: NoticePrecision,
    /**
     * Take these notifications down (their [Notice.postId]s): what was posted here and no longer applies, such as an
     * approval answered on either device or run out ([Governor.withdrawable]). Cancelling one that isn't showing is fine.
     */
    val withdraw: List<String> = emptyList(),
) {
    /** For Swift: the state to store. */
    val stateEncoded: String get() = state.encode()
}

/** For the settings screens: what happens next. */
data class NotificationPreview(
    /** "Quiet until 07:00" · "Quiet hours 22:00–07:00" · "No quiet hours" */
    val quietLine: String,
    /** "Next digest at 18:00 · 3 things so far" · "No digests" */
    val digestLine: String,
    /**
     * Notifications never seen (slice 3): why silence isn't broken — "Most things wait for the digests at 12:30 and
     * 18:00, so quiet in between is normal. Decisions and urgent things come at once." · digests off: heads-ups as they come.
     */
    val explainLine: String = "",
)

object Governor {
    /** A digest whose time was missed (phone off) still goes out within this long; after that it is skipped. */
    const val MISSED_DIGEST_GRACE_MS = 2 * 60 * 60_000L
    /** More heads-ups than this at once are folded into one notification. */
    const val MAX_BURST = 3
    const val MAX_DIGEST_LINES = 6
    /** Delivered keys are forgotten after this long (every key includes its day, so nothing comes back). */
    const val KEEP_MS = 30L * 24 * 60 * 60_000L

    fun evaluate(
        notices: List<Notice>,
        settings: NotificationSettings,
        device: DeviceAlerts,
        state: GovernorState,
        nowMs: Long,
        cal: LocalCalendar,
    ): GovernorResult {
        val quietNow = settings.quiet.isQuietAt(nowMs, cal)
        val tiered = notices.map { it.copy(tier = effectiveTier(it, settings)) }.filter { it.tier != NoticeTier.SILENT }
        val due = tiered.filter { it.atMs <= nowMs }
        fun fresh(n: Notice) = n.key !in state.delivered && (n.expiresAtMs == null || nowMs < n.expiresAtMs)

        val slot = dueDigestSlot(settings, state.lastDigestSlotMs, nowMs, cal)
        val digestNow = slot != null && device != DeviceAlerts.OFF

        val post = mutableListOf<Notice>()
        val ridesInDigest = mutableListOf<Notice>()
        for (n in due.filter(::fresh)) {
            when (n.tier) {
                NoticeTier.CRITICAL -> post += n
                NoticeTier.ACTION -> if (!quietNow) post += n
                NoticeTier.HEADS_UP -> {
                    val toDigest = settings.digestsOn && (device == DeviceAlerts.DIGESTS || settings.quiet.isQuietAt(n.atMs, cal) || quietNow)
                    when {
                        toDigest || digestNow -> if (digestNow) ridesInDigest += n
                        !quietNow -> post += n
                    }
                }
                else -> Unit
            }
        }
        if (device == DeviceAlerts.DIGESTS) post.retainAll { it.tier == NoticeTier.CRITICAL }
        if (device == DeviceAlerts.OFF) post.clear()

        // A burst of heads-ups is folded into one notification.
        var digest: Digest? = null
        val headsNow = post.filter { it.tier == NoticeTier.HEADS_UP }
        if (headsNow.size > MAX_BURST) {
            post.removeAll(headsNow)
            digest = buildDigest("${headsNow.size} things need you", headsNow, emptyList())
        }
        if (digestNow) {
            val summed = due.filter { it.tier == NoticeTier.DIGEST && (it.expiresAtMs == null || nowMs < it.expiresAtMs) }
            val all = ridesInDigest + headsNow.takeIf { digest != null }.orEmpty()
            if (all.isNotEmpty() || summed.isNotEmpty()) {
                val count = all.size + summed.size
                digest = buildDigest("${NotificationSettings.digestName(cal.minuteOfDay(slot!!))} · ${plural(count, "thing")}", all, summed)
            }
        }

        val postedKeys = post.map { it.key } + ridesInDigest.map { it.key } + (if (digest != null) headsNow.map { it.key } else emptyList())
        // Posted notices that must come down once they no longer apply (an answered or expired approval): withdrawn
        // once, then forgotten (their keys never come back).
        val current = notices.map { it.key }.toSet()
        val gone = state.delivered.keys.filter { withdrawable(it) && it !in current }
        val stillShown = notices.map { it.postId }.toSet()
        val withdraw = gone.map(::withdrawSlot).distinct().filter { it !in stillShown }
        val delivered = (state.delivered - gone.toSet() + postedKeys.associateWith { nowMs }).filterValues { nowMs - it < KEEP_MS }
        val newState = GovernorState(delivered, if (slot != null) maxOf(slot, state.lastDigestSlotMs) else state.lastDigestSlotMs)

        // Next look: the next digest, the end of quiet hours, or the next notice that would post on its own.
        val wakes = mutableListOf<Pair<Long, NoticePrecision>>()
        if (device != DeviceAlerts.OFF) {
            nextDigestSlot(settings, nowMs, cal)?.let { wakes += it to NoticePrecision.SOFT }
            if (quietNow) wakes += settings.quiet.endAfter(nowMs, cal) to NoticePrecision.SOFT
            tiered.filter { it.atMs > nowMs && it.tier.interrupts && it.key !in delivered && (it.expiresAtMs == null || it.expiresAtMs > it.atMs) }
                .forEach { wakes += it.atMs to it.precision }
        }
        val next = wakes.minByOrNull { it.first }
        return GovernorResult(post, digest, newState, next?.first, next?.second ?: NoticePrecision.SOFT, withdraw)
    }

    /** Keys whose notification comes down once the notice stops being produced: approvals (they can't come back). */
    fun withdrawable(key: String): Boolean = key.startsWith(ApprovalRules.NOTICE_PREFIX) || key.startsWith(ReviewRules.NOTICE_PREFIX)

    /** Where a withdrawable key was posted: an approval's notices share "approval:<id>". */
    internal fun withdrawSlot(key: String): String = if (key.startsWith(ApprovalRules.NOTICE_PREFIX)) ApprovalRules.noticeSlot(key) else key

    /** What the settings screens show. */
    fun preview(notices: List<Notice>, settings: NotificationSettings, nowMs: Long, cal: LocalCalendar): NotificationPreview {
        val q = settings.quiet
        val quietLine = when {
            !q.enabled -> "No quiet hours"
            q.isQuietAt(nowMs, cal) -> "Quiet until ${LocalClock.formatMinute(q.endMinute)}"
            else -> "Quiet hours ${q.summary}"
        }
        val next = nextDigestSlot(settings, nowMs, cal)
        val digestLine = if (next == null) "No digests" else {
            val pending = notices.count {
                val t = effectiveTier(it, settings)
                t == NoticeTier.DIGEST && it.atMs <= next && (it.expiresAtMs == null || it.expiresAtMs > next)
            }
            val day = cal.epochDayOf(next) - cal.epochDayOf(nowMs)
            val whenText = (if (day > 0) "tomorrow " else "") + LocalClock.formatMinute(cal.minuteOfDay(next))
            "Next digest $whenText" + if (pending > 0) " · ${plural(pending, "thing")} so far" else " · nothing yet"
        }
        return NotificationPreview(quietLine, digestLine, explainLine(settings))
    }

    /** [NotificationPreview.explainLine]: the digest times as set, so silence between them isn't mistaken for broken. */
    fun explainLine(settings: NotificationSettings): String {
        val times = settings.digestMinutes.sorted().map { LocalClock.formatMinute(it) }
        if (times.isEmpty()) return "Digests are off: heads-ups come as they happen, except in quiet hours. Decisions and urgent things always come at once."
        val at = when (times.size) {
            1 -> "the digest at ${times[0]}"
            else -> "the digests at ${times.dropLast(1).joinToString(", ")} and ${times.last()}"
        }
        return "Most things wait for $at, so quiet in between is normal. Decisions and urgent things come at once."
    }

    fun effectiveTier(n: Notice, settings: NotificationSettings): NoticeTier {
        if (!n.source.adjustable) return n.tier
        val chosen = settings.tiers[n.source] ?: return n.tier
        // A notice can be lowered by the owner's choice, never raised by it.
        return if (chosen.ordinal > n.tier.ordinal) chosen else n.tier
    }

    /** The latest digest time at or before now that hasn't gone out, isn't in quiet hours and isn't too old. */
    internal fun dueDigestSlot(settings: NotificationSettings, lastSlotMs: Long, nowMs: Long, cal: LocalCalendar): Long? {
        if (!settings.digestsOn) return null
        val today = cal.epochDayOf(nowMs)
        val slot = listOf(today - 1, today).flatMap { d -> settings.digestMinutes.map { cal.toEpochMs(d, it) } }
            .filter { it <= nowMs }.maxOrNull() ?: return null
        if (slot <= lastSlotMs || nowMs - slot > MISSED_DIGEST_GRACE_MS) return null
        if (settings.quiet.isQuietAt(slot, cal)) return null
        return slot
    }

    internal fun nextDigestSlot(settings: NotificationSettings, nowMs: Long, cal: LocalCalendar): Long? {
        if (!settings.digestsOn) return null
        val today = cal.epochDayOf(nowMs)
        return (today..today + 2).flatMap { d -> settings.digestMinutes.map { cal.toEpochMs(d, it) } }
            .filter { it > nowMs && !settings.quiet.isQuietAt(it, cal) }.minOrNull()
    }

    private fun buildDigest(title: String, heads: List<Notice>, summed: List<Notice>): Digest {
        val ordered = heads + summed.sortedBy { it.source.ordinal }
        val lines = ordered.take(MAX_DIGEST_LINES).map { "${it.title} · ${it.text}".trimEnd(' ', '·') } +
            listOfNotNull((ordered.size - MAX_DIGEST_LINES).takeIf { it > 0 }?.let { "+$it more" })
        val bySource = ordered.groupingBy { it.source }.eachCount()
        val summary = bySource.entries.sortedBy { it.key.ordinal }.joinToString(" · ") { (s, n) -> countLine(s, n) }
        val target = when {
            ordered.isNotEmpty() && ordered.all { it.target == ordered.first().target } -> ordered.first().target
            else -> NoticeTarget.NEEDS_YOU
        }
        return Digest(title, summary, lines, ordered.size, "Navilife · ${plural(ordered.size, "thing")} for you", target)
    }

    private fun countLine(s: NoticeSource, n: Int): String = when (s) {
        NoticeSource.RENEWAL_CANCEL_BY -> "$n to cancel or keep"
        NoticeSource.FAST_GOAL -> "fasting goal reached"
        NoticeSource.FAST_CHECK_IN -> "a fasting check-in"
        NoticeSource.SESSION_LEAVE -> plural(n, "session") + " to go to"
        NoticeSource.SESSION_ASK -> if (n == 1) "did you go?" else "$n sessions to answer"
        NoticeSource.BRIEF -> "your morning brief"
        NoticeSource.SHUTDOWN -> "time to shut down"
        NoticeSource.WEEKLY_REVIEW -> "your weekly review"
        NoticeSource.EVENT_REMINDER -> plural(n, "event reminder")
        NoticeSource.TASK_REMINDER -> plural(n, "task reminder")
        NoticeSource.FIXTURE_MOVED -> plural(n, "kick-off") + " moved"
        NoticeSource.SIGN_IN -> plural(n, "sign-in") + " to renew"
        NoticeSource.CALL_CREDIT -> "call assistant credit"
        NoticeSource.SCHOOL -> plural(n, "school reminder")
        NoticeSource.DATE_NIGHT -> "date night to book"
        NoticeSource.WINS -> "this week's wins"
        NoticeSource.LEAVE -> if (n == 1) "leave to book" else "leave to book for $n holidays"
        NoticeSource.APPOINTMENTS -> plural(n, "appointment") + " to book"
        NoticeSource.GOALS -> if (n == 1) "a word on your goals" else "$n words on your goals"
        NoticeSource.WEEK_AHEAD -> "your week ahead"
        NoticeSource.BINS -> "bins out tonight"
        NoticeSource.GIFTS -> plural(n, "gift") + " to sort"
        NoticeSource.CAR -> plural(n, "car date") + " coming up"
        NoticeSource.MONEY -> plural(n, "money date") + " coming up"
        NoticeSource.BILLS -> plural(n, "bill") + " due"
        NoticeSource.CARDS -> plural(n, "card reminder")
        NoticeSource.MONEY_LOOK -> if (n == 1) "one thing worth a look in Money" else "$n things worth a look in Money"
        NoticeSource.TRADING -> plural(n, "trading alert")
        NoticeSource.LIFE_ADMIN -> "your life admin hour"
        NoticeSource.TRIPS -> if (n == 1) "a trip tomorrow" else "$n trips tomorrow"
        NoticeSource.DOCUMENTS -> plural(n, "document") + " to renew"
        NoticeSource.MAIL_MOMENT -> "your ten-minute inbox"
        NoticeSource.PARCELS -> if (n == 1) "a parcel out for delivery" else "$n parcels out for delivery"
        NoticeSource.RETURNS -> if (n == 1) "a return due" else "$n returns due"
        NoticeSource.PARKING -> if (n == 1) "parking about to end" else "$n parking timers about to end"
        NoticeSource.KIDS -> if (n == 1) "sizes to check" else "$n children's sizes to check"
        NoticeSource.BARCA -> if (n == 1) "a Barça match soon" else "$n Barça matches soon"
        NoticeSource.BARCA_GOALS -> if (n == 1) "a Barça goal" else "$n Barça goals"
        NoticeSource.BARCA_RESULT -> if (n == 1) "a Barça result" else "$n Barça results"
        NoticeSource.MEETING_PREP -> if (n == 1) "a meeting to prepare for" else "$n meetings to prepare for"
        NoticeSource.BALANCE -> "an area short of its target"
        NoticeSource.PROTECTED_MAIL -> "work mail that waited"
        NoticeSource.RENEWAL -> plural(n, "renewal") + " due"
        NoticeSource.CHASE -> "$n to chase"
        NoticeSource.PROMISE -> if (n == 1) "a promise due" else "$n promises due"
        NoticeSource.REVIEW -> plural(n, "decision") + " to review"
        NoticeSource.OVERDUE -> plural(n, "overdue task")
        NoticeSource.WEATHER -> "rain on " + plural(n, "plan")
        NoticeSource.TRAINS -> if (n == 1) "a line on your route" else "$n lines on your route"
        NoticeSource.LEAVING -> "a leaving-now draft"
        NoticeSource.REQUEST -> plural(n, "request")
        NoticeSource.APPROVAL -> plural(n, "approval")
    }
}

/**
 * Turns what MEKA already knows (lists, renewals, fasting, the evening shutdown, Today) into notices. Deterministic,
 * re-run on every evaluation: what is no longer true simply stops producing a notice.
 */
object NoticeSources {
    /** Heads-ups for a cancel-by date go out at 09:00 the day before. */
    const val CANCEL_BY_NOTICE_MIN = 9 * 60
    /** A fasting-goal heads-up older than this is stale. */
    const val FAST_GOAL_STALE_MS = 3 * 60 * 60_000L

    fun collect(
        lists: ListsView,
        fasting: FastingView,
        shutdown: ShutdownView,
        today: Today,
        nowMs: Long,
        cal: LocalCalendar,
        brief: MorningBriefView = MorningBriefView.EMPTY,
        review: ReviewCard = ReviewCard.NONE,
        events: List<CalendarEvent> = emptyList(),
        marks: EventMarks = EventMarks.NONE,
        sessions: SessionsView = SessionsView.EMPTY,
        tasks: List<Task> = emptyList(),
        forecast: WeatherForecast = WeatherForecast.EMPTY,
        requests: List<RequestCard> = emptyList(),
        settings: NotificationSettings = NotificationSettings.DEFAULT,
        signIns: List<SignIn> = emptyList(),
        lines: LineStatusSnapshot = LineStatusSnapshot.EMPTY,
        office: OfficeWindow? = null,
        /** The call assistant's credit and whether its switch is on ([CallCreditRules.notices]). */
        callCredit: CallCredit? = null,
        callAssistantOn: Boolean = false,
        /** School rhythm: the school items and the open week-ahead questions ([SchoolRules.notices]). */
        school: List<SchoolItem> = emptyList(),
        schoolCovers: List<SchoolCover> = emptyList(),
        /** Date night's setting, for the week-before nudge ([DateNightRules.notices]). */
        dateNight: DateNightSetting? = null,
        /** The work wins log, for Friday's question ([WinRules.notices]). */
        wins: List<Win> = emptyList(),
        /** Annual leave: the booked stretches and the schedule with the bank holidays ([LeaveRules.notices]). */
        leave: List<LeaveItem> = emptyList(),
        leaveWork: WorkHours? = null,
        /** The approvals waiting for Meka ([ApprovalRules.pending]), for their notices. */
        approvals: List<Approval> = emptyList(),
        /** Appointment nudges: the list, for the heads-up the day one starts asking ([AppointmentRules.notices]). */
        appointments: List<AppointmentItem> = emptyList(),
        /** Goals' rhythm: the open goals and last month's review ([GoalRhythmRules.notices]). */
        goals: GoalsView = GoalsView.EMPTY,
        /** "Leaving now": the setting, for the draft when an office day's work ends ([LeavingRules.notices]). */
        leaving: LeavingSetting? = null,
        /** The Sunday week ahead, for Sunday evening's heads-up ([WeekAheadRules.notices]), and its time setting. */
        weekAhead: WeekAheadView? = null,
        weekAheadSetting: WeekAheadSetting = WeekAheadSetting.DEFAULT,
        /** Bin days: the bins and the bank holidays that move them ([BinRules.notices]). */
        bins: List<BinItem> = emptyList(),
        holidays: HolidayCalendar = HolidayCalendar.NONE,
        /** Car on autopilot: the registration, for the reminders' titles ([CarRules.notices]). */
        carRegistration: String? = null,
        /** Life admin hour: this week's view, for the heads-up at its start ([LifeAdminRules.notices]). */
        lifeAdmin: LifeAdminView? = null,
        /** Gift planner: the birthdays and anniversaries with their ideas ([GiftRules.notices]). */
        occasions: List<Occasion> = emptyList(),
        /** Travel mode: the trips with their bookings, for the evening before each ([TripRules.notices]). */
        trips: List<TripItem> = emptyList(),
        /** The Vault: the documents and when they run out ([DocumentRules.notices]). */
        documents: List<DocumentItem> = emptyList(),
        /** Health Connect slice 2: minutes "Did you go?" waits after a session while workouts are read in the background. */
        sessionAskGraceMin: Int = 0,
        /** The next morning's wake alarm, for the trains' "wake 10 min earlier?" ([CommuteDisruptionRules.notices]). */
        wake: WakeView? = null,
        /** Live trains, slice 5: this morning's wake alarm anchored on the train ([TrainWakeRules.notices]). */
        trainWake: TrainWake? = null,
        trainWakeAlarmMinute: Int? = null,
        /**
         * The ten-minute inbox ([MailMomentRules.notices]): its setting, how many emails need him, and when today's
         * protected time starts (local minutes; 0 for a wholly protected day, null for none).
         */
        mailMoment: MailMomentSetting? = null,
        mailWaiting: Int = 0,
        protectedFromMin: Int? = null,
        /** Deliveries, slice 2: the parcels read from shipping emails, for "out for delivery" ([ParcelRules.notices]). */
        parcels: List<Parcel> = emptyList(),
        /** Car and parking, slice 2: the cars' parking timers, ten minutes before each ends ([ParkingTimerRules.notices]). */
        parkingTimers: List<ParkingTimer> = emptyList(),
        cars: CarsView = CarsView.EMPTY,
        /** Kids' admin, slice 6: the children in About you and what Kids keeps for them ([KidsRules.notices]). */
        kids: List<AboutChild> = emptyList(),
        kidRecords: Map<String, KidRecord> = emptyMap(),
        /** Meeting prep, slice 1: the synced emails, for each guest's last one ([MeetingPrepRules.notices]). */
        mail: List<MailItem> = emptyList(),
        /** Meeting prep, slice 2: his lead time in minutes (0 = off; [MeetingPrepRules.read]). */
        prepLeadMin: Int = MeetingPrepRules.LEAD_MIN,
        /** Money manager, slice 7: his cards, for the payment and 0% heads-ups ([MoneyCards.notices]). */
        cards: List<MoneyCard> = emptyList(),
        /** Trading tracker, slice 3: his public addresses as last read, and his alert levels ([TradingAlerts.notices]). */
        trading: List<TradingWallet> = emptyList(),
        tradingAlerts: TradingAlertSetting = TradingAlertSetting.DEFAULT,
        /** Money manager, slice 18: the year whose review is ready (January only; [MoneyYear.readyYear]). */
        moneyYear: Int? = null,
        /** Money manager (7): the month whose review is ready (its first days; [MoneyMonthReview.readyMonth]). */
        moneyMonth: Int? = null,
        /** Money manager, nudges: what's newly worth a look in Money ([MoneyNudges.items]). */
        moneyLook: List<MoneyNudges.Item> = emptyList(),
        /** Balance view, slice 4: this week's nudge as planned, if an area is well short ([BalanceRules.notices]). */
        balance: BalanceNudge? = null,
        /** Protected time, slice 6: the setting and the work schedule, for the morning note on what waited. */
        protectedTime: ProtectedSetting? = null,
        workSchedule: WorkSchedule? = null,
    ): List<Notice> {
        val day = cal.epochDayOf(nowMs)
        val todayStart = cal.toEpochMs(day, 0)
        val out = mutableListOf<Notice>()

        for (r in lists.renewals.attention + lists.renewals.upcoming) {
            val cancelBy = r.cancelByDay
            if (cancelBy != null && cancelBy >= day && cancelBy - day <= 7) {
                val whenText = when (cancelBy - day) { 0L -> "today"; 1L -> "tomorrow"; else -> CivilDate.shortLabel(cancelBy) }
                out += Notice(
                    key = "renewal:${r.id}:cancel:$cancelBy", source = NoticeSource.RENEWAL_CANCEL_BY, tier = NoticeTier.HEADS_UP,
                    title = "Cancel or keep ${r.title}?", text = "Cancel by $whenText",
                    atMs = cal.toEpochMs(cancelBy - 1, CANCEL_BY_NOTICE_MIN), target = NoticeTarget.LISTS,
                    expiresAtMs = cal.toEpochMs(cancelBy + 1, 0),
                )
            }
        }
        // The car's dates, the money dates and bills get their own reminders (Car on autopilot, UK money deadlines, Bills
        // due), not the digest line; once overdue they are summed in the digest like any renewal.
        for (r in lists.renewals.attention.filter { it.state == RenewalState.OVERDUE || !(CarRules.isCar(it) || MoneyDateRules.isMoney(it) || BillRules.isBill(it) || MailReturnRules.isReturn(it.title)) }) {
            out += Notice(
                key = "renewal:${r.id}:${r.dueDay}", source = NoticeSource.RENEWAL, tier = NoticeTier.DIGEST,
                title = r.title, text = r.meta, atMs = todayStart, target = NoticeTarget.LISTS,
            )
        }
        for (w in lists.waiting.filter { it.state == DueState.DUE }) {
            out += Notice(
                key = "chase:${w.id}:${w.chaseDay}", source = NoticeSource.CHASE, tier = NoticeTier.DIGEST,
                title = "Chase: ${w.title}", text = w.who.orEmpty(), atMs = todayStart, target = NoticeTarget.LISTS,
            )
        }
        for (p in lists.promised.filter { it.state == DueState.DUE }) {
            out += Notice(
                key = "promise:${p.id}:${p.byDay}", source = NoticeSource.PROMISE, tier = NoticeTier.DIGEST,
                title = "Promised: ${p.title}", text = p.meta, atMs = todayStart, target = NoticeTarget.LISTS,
            )
        }
        for (d in lists.decisions.filter { it.state == DueState.DUE }) {
            out += Notice(
                key = "review:${d.id}:${d.reviewDay}", source = NoticeSource.REVIEW, tier = NoticeTier.DIGEST,
                title = "Review: ${d.statement}", text = "", atMs = todayStart, target = NoticeTarget.LISTS,
            )
        }
        // Life admin hour, slice 2: an overdue admin task gathered into the hour waits for it (its heads-up sums them).
        for (item in today.needsYou.filter { it.reason == NeedsYouReason.OVERDUE && !LifeAdminRules.holdsOverdue(lifeAdmin, it.task.id) }) {
            out += Notice(
                key = "overdue:${item.task.id}:$day", source = NoticeSource.OVERDUE, tier = NoticeTier.DIGEST,
                title = item.task.title, text = "overdue", atMs = todayStart, target = NoticeTarget.NEEDS_YOU,
            )
        }
        fasting.current?.let { f ->
            out += if (f.extended) Notice(
                // Fasting v2: an extended fast's goal is the "you did it" moment.
                key = "fast:${f.id}:goal:${f.goalAtMs}", source = NoticeSource.FAST_GOAL, tier = NoticeTier.HEADS_UP,
                title = FastingRules.doneLine(f.startedAtMs, f.goalAtMs), text = "${f.title} · end it whenever you're ready",
                atMs = f.goalAtMs, target = NoticeTarget.GOALS, expiresAtMs = f.goalAtMs + FAST_GOAL_STALE_MS,
            ) else Notice(
                key = "fast:${f.id}:goal:${f.targetHours}", source = NoticeSource.FAST_GOAL, tier = NoticeTier.HEADS_UP,
                title = "Fasting goal reached", text = "${f.targetHours} h · end it whenever you're ready",
                atMs = f.goalAtMs, target = NoticeTarget.GOALS, expiresAtMs = f.goalAtMs + FAST_GOAL_STALE_MS,
            )
            // Gentle once-a-day check-ins on an extended fast; each stands until the next (or the goal). Quiet hours
            // apply as to any heads-up: one that falls in them rides in the next digest.
            if (f.extended) {
                val times = FastingRules.checkInTimes(f.startedAtMs, f.goalAtMs)
                times.forEachIndexed { i, t ->
                    val hours = (t - f.startedAtMs) / 3_600_000L
                    out += Notice(
                        key = "fast:${f.id}:day:${i + 2}", source = NoticeSource.FAST_CHECK_IN, tier = NoticeTier.HEADS_UP,
                        title = "${f.title} · ${FastingRules.dayOf(f.startedAtMs, f.goalAtMs, t)}",
                        text = "$hours h so far · goal ${f.goalWhen} · ending early is fine",
                        atMs = t, target = NoticeTarget.GOALS, expiresAtMs = times.getOrNull(i + 1) ?: f.goalAtMs,
                    )
                }
            }
        }
        val shutdownAt = cal.toEpochMs(day, shutdown.startMinute)
        // Done for the day, completeness: none when the card is switched off or on annual leave; "Later" moves it on
        // under a fresh key so it posts again when it comes back.
        val shutdownLater = shutdown.laterUntilMs.takeIf { it > shutdownAt }
        val shutdownWhen = shutdownLater ?: shutdownAt
        if (!shutdown.doneToday && !shutdown.cardOff && !shutdown.onLeave && (shutdown.offered || nowMs < shutdownWhen)) {
            out += Notice(
                key = shutdownLater?.let { ShutdownRules.laterKey(day, it) } ?: "${ShutdownRules.NOTICE_PREFIX}$day",
                source = NoticeSource.SHUTDOWN, tier = NoticeTier.HEADS_UP,
                title = "Shut down the day", text = shutdown.cardLine,
                atMs = shutdownWhen, target = NoticeTarget.TODAY, expiresAtMs = cal.toEpochMs(day + 1, 0),
                actions = if (ShutdownRules.laterMinute(cal.minuteOfDay(maxOf(nowMs, shutdownWhen))) != null) listOf(NoticeAction.SHUT_DOWN_LATER) else emptyList(),
            )
        }
        // The morning brief: a heads-up when it starts (the end of quiet hours), until noon or until it's read.
        if (brief.dateLabel.isNotEmpty() && !brief.seenToday && cal.minuteOfDay(nowMs) < BriefRules.END_MIN) {
            out += Notice(
                key = "brief:$day", source = NoticeSource.BRIEF, tier = NoticeTier.HEADS_UP,
                title = "Morning brief", text = brief.cardLine,
                atMs = cal.toEpochMs(day, brief.startMinute), target = NoticeTarget.TODAY,
                expiresAtMs = cal.toEpochMs(day, BriefRules.END_MIN),
            )
        }
        // The weekly review: a heads-up at its time (18:00 unless set) on the week's Sunday, standing until it's reviewed or Monday ends.
        // Weekly reflection, slice 4: "Later tonight" (Sunday only) puts it off; it comes back under a fresh key then.
        if (review.weekStart > 0 && !review.reviewed) {
            val sunday = cal.toEpochMs(review.weekStart + 6, review.startMinute)
            val later = review.laterUntilMs.takeIf { it > sunday }
            out += Notice(
                key = later?.let { ReviewRules.laterKey(review.weekStart, it) } ?: "${ReviewRules.NOTICE_PREFIX}${review.weekStart}",
                source = NoticeSource.WEEKLY_REVIEW, tier = NoticeTier.HEADS_UP,
                title = review.title.ifEmpty { "Review your week" }, text = review.line,
                atMs = later ?: sunday, target = NoticeTarget.REVIEW,
                expiresAtMs = cal.toEpochMs(review.weekStart + 8, 0),
                actions = if (review.laterOffered) listOf(NoticeAction.LATER_TONIGHT) else emptyList(),
            )
        }
        // Remind me and Leave by, set on calendar events.
        out += ReminderRules.notices(events, marks, nowMs, cal)
        out += FixtureMoves.notices(events, marks, nowMs, cal)
        // Weekend football: "How did it go?" after a club match with nothing kept yet.
        out += FootballRules.notices(events, marks, nowMs, cal)
        // Barça match day: an hour before kick-off, with where to watch it in the UK.
        out += BarcaMatchRules.notices(events, marks, nowMs, cal)
        // …and while it's on, each goal, then full time (one place per match, updated in place).
        out += BarcaMatchRules.goalNotices(events, marks, nowMs)
        out += BarcaMatchRules.fullTimeNotices(events, marks, nowMs)
        // Booked sessions (Gym): time to go, then "Did you go?" once the slot is over.
        out += SessionRules.notices(sessions, cal, sessionAskGraceMin)
        // Remind me, set on tasks.
        out += TaskReminderRules.notices(tasks, nowMs, cal)
        // Rain at today's plans you go out for (Weather), summed in the next digest.
        out += WeatherRules.notices(forecast, events, marks, sessions, nowMs, cal)
        out += SignInRules.notices(signIns, nowMs, cal)
        out += CallCreditRules.notices(callCredit, callAssistantOn)
        out += SchoolRules.notices(school, schoolCovers, nowMs, cal)
        out += SchoolTerms.notices(school, nowMs, cal)
        out += DateNightRules.notices(dateNight, nowMs, cal)
        out += WinRules.notices(wins, nowMs, cal)
        out += LeaveRules.notices(school, leave, leaveWork, nowMs, cal)
        out += AppointmentRules.notices(appointments, nowMs, cal)
        out += GoalRhythmRules.notices(goals.goals, goals.month, nowMs, cal)
        if (leaving != null) out += LeavingRules.notices(leaving, office, lines, nowMs, cal)
        out += BinRules.notices(bins, nowMs, cal, holidays)
        out += GiftRules.notices(occasions, nowMs, cal)
        out += CarRules.notices(lists.renewals.all, carRegistration, nowMs, cal)
        out += MoneyDateRules.notices(lists.renewals.all, nowMs, cal)
        // Bills due, completeness: a card's payment on the radar too gets one reminder, the card's.
        out += BillRules.notices(lists.renewals.all, nowMs, cal, cards)
        out += MoneyCards.notices(cards, nowMs, cal, lists.renewals.all)
        out += MoneyYear.notices(moneyYear, nowMs, cal)
        out += MoneyMonthReview.notices(moneyMonth, nowMs, cal)
        out += MoneyNudges.notices(moneyLook, nowMs, cal)
        out += TradingAlerts.notices(trading, tradingAlerts, nowMs, cal)
        out += MailReturnRules.notices(lists.renewals.all, nowMs, cal)
        out += TripRules.notices(trips, nowMs, cal)
        out += DocumentRules.notices(documents, nowMs, cal)
        if (lifeAdmin != null) out += LifeAdminRules.notices(lifeAdmin, nowMs)
        if (mailMoment != null) out += MailMomentRules.notices(mailMoment, mailWaiting, protectedFromMin, nowMs, cal)
        out += ParcelRules.notices(parcels, nowMs, cal)
        out += ParkingTimerRules.notices(parkingTimers, cars, nowMs, cal)
        out += KidsRules.notices(kids, kidRecords, nowMs, cal)
        out += BalanceRules.notices(balance, nowMs, cal)
        if (workSchedule != null) ProtectedMailRules.morningNotice(mail, protectedTime, nowMs, workSchedule, holidays, cal)?.let { out += it }
        // Meeting prep, slice 1: who's coming and what's open with them, fifteen minutes before a meeting.
        out += MeetingPrepRules.notices(events, marks, mail, lists.promised, lists.waiting, nowMs, cal, prepLeadMin)
        if (weekAhead != null) out += WeekAheadRules.notices(weekAhead, nowMs, cal, weekAheadSetting, settings.digestMinutes)
        // A line on the route not running well during an office day's commute (Places item 4): a heads-up, once.
        out += RouteRules.notices(lines, office, settings.quiet, nowMs, cal)
        // Tomorrow morning's trains in trouble: offer to wake ten minutes earlier, never moving the alarm itself.
        out += CommuteDisruptionRules.notices(lines, wake, settings.quiet, nowMs, cal)
        // This morning's usual train in trouble before the alarm: offer to wake in time for the earlier one.
        out += TrainWakeRules.notices(trainWake, lines, trainWakeAlarmMinute, settings.quiet, nowMs, cal)
        // Request cards from people Meka watches: in the digest, or a heads-up straight away for those he chose.
        out += requestNotices(requests, settings)
        // The approvals inbox: Needs a decision when a request arrives, Critical in its last 15 minutes.
        out += ApprovalRules.notices(approvals, cal)
        return out
    }

    /** A heads-up for a request older than this is stale (it still waits in Needs you). */
    const val REQUEST_HEADS_UP_STALE_MS = 3 * 60 * 60_000L

    /**
     * One notice per open request card, due when the message came: "From Wife · 14:02" · "Add task: Pick up dry
     * cleaning · Tomorrow". A digest item while the card is open, or a heads-up straight away (stale after
     * [REQUEST_HEADS_UP_STALE_MS]) when its sender is in [NotificationSettings.requestNow]. Never the message's text:
     * only the proposal, so the quote stays in the app.
     */
    fun requestNotices(cards: List<RequestCard>, settings: NotificationSettings): List<Notice> =
        // A card from "Handle this" is one Meka just made himself, looking at it: no notification ever.
        cards.filterNot { HandleThisRules.isShared(it.messageId) }.map { c ->
            val now = c.personKey.isNotEmpty() && settings.requestNow.any { People.key(it) == c.personKey }
            Notice(
                key = "request:${c.id}", source = NoticeSource.REQUEST,
                tier = if (now) NoticeTier.HEADS_UP else NoticeTier.DIGEST,
                title = c.from, text = c.action, atMs = c.atMs, target = NoticeTarget.NEEDS_YOU,
                expiresAtMs = if (now) c.atMs + REQUEST_HEADS_UP_STALE_MS else null,
            )
        }
}

/** Synced notification settings on the `context_mode` entity [ENTITY_ID] (ADR-008: Quiet is a context mode). */
object NotificationFields {
    /** "on;start;end" in local minutes. */
    const val QUIET = "quietHours"
    /** "750,1080"; empty for no digests. */
    const val DIGESTS = "digestTimes"
    /** "SHUTDOWN=DIGEST,…": sources moved to a lower tier. */
    const val TIERS = "noticeTiers"
    /** Watched people whose requests notify straight away, one name per line (V1, requests; ADR-008 addendum). */
    const val REQUEST_NOW = "requestNotifyNow"
}

class NotificationPrefs(private val replica: Replica) {
    private fun field(name: String) = replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID)?.get(name)?.textOrNull

    fun settings(): NotificationSettings = NotificationSettings(
        quiet = QuietHours.decode(field(NotificationFields.QUIET)) ?: QuietHours.DEFAULT,
        digestMinutes = NotificationSettings.decodeDigests(field(NotificationFields.DIGESTS)) ?: NotificationSettings.DEFAULT_DIGESTS,
        tiers = NotificationSettings.decodeTiers(field(NotificationFields.TIERS)),
        requestNow = NotificationSettings.decodeRequestNow(field(NotificationFields.REQUEST_NOW)),
    )

    /** "Notify straight away" beside a watched person (Work mode → Watch for requests from), on every device. */
    fun setRequestNow(name: String, on: Boolean) {
        val n = RequestWatchRules.clean(name) ?: return
        val current = settings().requestNow
        if (on && current.any { People.key(it) == People.key(n) }) return
        val without = current.filterNot { People.key(it) == People.key(n) }.toSet()
        val encoded = NotificationSettings.encodeRequestNow(if (on) without + n else without)
        if ((field(NotificationFields.REQUEST_NOW) ?: "") == encoded) return
        replica.commitLocal(EntityTypes.CONTEXT_MODE, ENTITY_ID, mapOf(NotificationFields.REQUEST_NOW to encoded.fv()))
    }

    fun setQuietHours(q: QuietHours) {
        if (field(NotificationFields.QUIET) == q.encode()) return
        replica.commitLocal(EntityTypes.CONTEXT_MODE, ENTITY_ID, mapOf(NotificationFields.QUIET to q.encode().fv()))
    }

    /** Turns the digest at [minute] on or off. */
    fun setDigest(minute: Int, on: Boolean) {
        require(minute in 0 until LocalClock.MINUTES_PER_DAY) { "minute must be 0..1439" }
        val current = settings().digestMinutes
        val next = if (on) (current + minute).distinct().sorted() else current - minute
        val encoded = NotificationSettings.encodeDigests(next)
        if (field(NotificationFields.DIGESTS) == encoded) return
        replica.commitLocal(EntityTypes.CONTEXT_MODE, ENTITY_ID, mapOf(NotificationFields.DIGESTS to encoded.fv()))
    }

    /** Moves a source to [tier]; its default tier clears the override. Only lowering is possible. */
    fun setTier(source: NoticeSource, tier: NoticeTier) {
        require(tier.ordinal >= source.defaultTier.ordinal) { "a source can only be lowered" }
        if (!source.adjustable) return
        val tiers = settings().tiers.toMutableMap()
        if (tier == source.defaultTier) tiers.remove(source) else tiers[source] = tier
        val encoded = NotificationSettings.encodeTiers(tiers)
        if ((field(NotificationFields.TIERS) ?: "") == encoded) return
        replica.commitLocal(EntityTypes.CONTEXT_MODE, ENTITY_ID, mapOf(NotificationFields.TIERS to encoded.fv()))
    }

    companion object {
        const val ENTITY_ID = "quiet"
    }
}

/** Helpers for the Mac (Kotlin enum members and companions are awkward from Swift). */
object NotifyRules {
    const val MIDDAY = NotificationSettings.MIDDAY
    const val EVENING = NotificationSettings.EVENING
    /** The sources the settings sheet lists (approvals aren't adjustable, so they aren't listed). */
    val sourceCount: Int get() = NoticeSource.ADJUSTABLE.size
    fun sourceAt(index: Int): NoticeSource = NoticeSource.ADJUSTABLE[index]
    fun sourceLabel(s: NoticeSource): String = s.label
    /** The tiers [s] can be set to: its default and anything lower. */
    fun tierChoices(s: NoticeSource): List<NoticeTier> = NoticeSource.CHOICES.filter { it.ordinal >= s.defaultTier.ordinal }
    fun tierLabel(t: NoticeTier): String = t.label
    fun tierOf(settings: NotificationSettings, s: NoticeSource): NoticeTier = settings.tierFor(s)
    fun hasDigest(settings: NotificationSettings, minute: Int): Boolean = settings.hasDigest(minute)
    val deviceCount: Int get() = DeviceAlerts.entries.size
    fun deviceAt(index: Int): DeviceAlerts = DeviceAlerts.entries[index]
    fun deviceLabel(d: DeviceAlerts): String = d.label
    /** For storing this device's choice. */
    fun deviceName(d: DeviceAlerts): String = d.name
    /** A stored choice back; [fallback] when there is none or it isn't known. */
    fun deviceFromName(name: String?, fallback: DeviceAlerts): DeviceAlerts = DeviceAlerts.entries.firstOrNull { it.name == name } ?: fallback
    fun targetName(t: NoticeTarget): String = t.name
    /** A notification button's label ("Went") and its stored name ("WENT"), and the name back (null if unknown). */
    fun actionLabel(a: NoticeAction): String = a.label
    fun actionName(a: NoticeAction): String = a.name
    fun actionFromName(name: String?): NoticeAction? = NoticeAction.entries.firstOrNull { it.name == name }
    /** [n]'s buttons on a device that can (the Fold) or can't (the Mac) send messages ([NoticeAction.shownOn]). */
    fun actionsOn(n: Notice, send: Boolean): List<NoticeAction> = n.actions.filter { it.shownOn(send) }
    /** "Leaving now" ([LeavingRules]): a notice whose buttons send, edit or copy its words. */
    fun isLeaving(key: String): Boolean = LeavingRules.isKey(key)
}
