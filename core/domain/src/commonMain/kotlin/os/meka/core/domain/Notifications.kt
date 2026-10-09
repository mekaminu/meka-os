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

/** Where tapping a notification takes you. */
enum class NoticeTarget { TODAY, NEEDS_YOU, LISTS, GOALS, REVIEW }

/**
 * A button on a posted notification, answered without opening the app (Gym slice 2b: "Did you go?" offers Went and
 * Didn't go). The app hands the notice's key and the action back to the core, which checks it still applies.
 */
enum class NoticeAction(val label: String) {
    WENT("Went"),
    DIDNT_GO("Didn't go"),
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
    RENEWAL("Renewals and bills due", NoticeTier.DIGEST),
    CHASE("Things to chase", NoticeTier.DIGEST),
    REVIEW("Decisions to review", NoticeTier.DIGEST),
    OVERDUE("Overdue tasks", NoticeTier.DIGEST),
    /** Rain or snow due at today's plans you go out for (Weather): "Light rain at 17:30 — Training at SG18". */
    WEATHER("Weather for your plans", NoticeTier.DIGEST),
    /**
     * A request card from someone Meka watches (V1, requests): summed in the next digest, or a heads-up straight away
     * for the people he marked "Notify straight away" ([NotificationSettings.requestNow]).
     */
    REQUEST("Requests from people you watch", NoticeTier.DIGEST);

    companion object {
        /** The tiers a source can be set to in the settings screen. */
        val CHOICES = listOf(NoticeTier.HEADS_UP, NoticeTier.DIGEST, NoticeTier.SILENT)
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
)

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
                // Only lowering is allowed; a raised tier from anywhere is ignored.
                if (tier.ordinal < source.defaultTier.ordinal) null else source to tier
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
        val delivered = (state.delivered + postedKeys.associateWith { nowMs }).filterValues { nowMs - it < KEEP_MS }
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
        return GovernorResult(post, digest, newState, next?.first, next?.second ?: NoticePrecision.SOFT)
    }

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
        return NotificationPreview(quietLine, digestLine)
    }

    fun effectiveTier(n: Notice, settings: NotificationSettings): NoticeTier {
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
        return Digest(title, summary, lines, ordered.size, "MEKA · ${plural(ordered.size, "thing")} for you", target)
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
        NoticeSource.RENEWAL -> plural(n, "renewal") + " due"
        NoticeSource.CHASE -> "$n to chase"
        NoticeSource.REVIEW -> plural(n, "decision") + " to review"
        NoticeSource.OVERDUE -> plural(n, "overdue task")
        NoticeSource.WEATHER -> "rain on " + plural(n, "plan")
        NoticeSource.REQUEST -> plural(n, "request")
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
        for (r in lists.renewals.attention) {
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
        for (d in lists.decisions.filter { it.state == DueState.DUE }) {
            out += Notice(
                key = "review:${d.id}:${d.reviewDay}", source = NoticeSource.REVIEW, tier = NoticeTier.DIGEST,
                title = "Review: ${d.statement}", text = "", atMs = todayStart, target = NoticeTarget.LISTS,
            )
        }
        for (item in today.needsYou.filter { it.reason == NeedsYouReason.OVERDUE }) {
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
        if (!shutdown.doneToday && (shutdown.offered || nowMs < shutdownAt)) {
            out += Notice(
                key = "shutdown:$day", source = NoticeSource.SHUTDOWN, tier = NoticeTier.HEADS_UP,
                title = "Shut down the day", text = shutdown.cardLine,
                atMs = shutdownAt, target = NoticeTarget.TODAY, expiresAtMs = cal.toEpochMs(day + 1, 0),
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
        // The weekly review: a heads-up at 18:00 on the week's Sunday, standing until it's reviewed or Monday ends.
        if (review.weekStart > 0 && !review.reviewed) {
            out += Notice(
                key = "weekly-review:${review.weekStart}", source = NoticeSource.WEEKLY_REVIEW, tier = NoticeTier.HEADS_UP,
                title = review.title.ifEmpty { "Review your week" }, text = review.line,
                atMs = cal.toEpochMs(review.weekStart + 6, ReviewRules.CARD_START_MIN), target = NoticeTarget.REVIEW,
                expiresAtMs = cal.toEpochMs(review.weekStart + 8, 0),
            )
        }
        // Remind me and Leave by, set on calendar events.
        out += ReminderRules.notices(events, marks, nowMs, cal)
        out += FixtureMoves.notices(events, marks, nowMs, cal)
        // Booked sessions (Gym): time to go, then "Did you go?" once the slot is over.
        out += SessionRules.notices(sessions, cal)
        // Remind me, set on tasks.
        out += TaskReminderRules.notices(tasks, nowMs, cal)
        // Rain at today's plans you go out for (Weather), summed in the next digest.
        out += WeatherRules.notices(forecast, events, marks, sessions, nowMs, cal)
        out += SignInRules.notices(signIns, nowMs, cal)
        // Request cards from people Meka watches: in the digest, or a heads-up straight away for those he chose.
        out += requestNotices(requests, settings)
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
    fun requestNotices(cards: List<RequestCard>, settings: NotificationSettings): List<Notice> = cards.map { c ->
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
    val sourceCount: Int get() = NoticeSource.entries.size
    fun sourceAt(index: Int): NoticeSource = NoticeSource.entries[index]
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
}
