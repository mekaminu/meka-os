package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/**
 * Your team (Take first, approved by Meka 2026-11-06 21:34; mockup `docs/design/routines/team.dc.html`): one place that
 * shows each of Navilife's helpers as a team member, what each did today and what waits on him. Slice 1 is the screen:
 * WORKING · NEED YOU · DONE TODAY tiles, a card per agent (Obi, Inbox, Calls, Money, Kestrel) with its role, live status,
 * TODAY and WAITS ON YOU, and a tap opens its sheet (today's log from the activity log, how much it may do by itself,
 * what waits with Review). Rules on the device, no AI: every line is built from what Navilife already recorded (the
 * activity log, Needs you's cards, Obi's money check, Kestrel's requests). Nothing here can act: Review only takes him
 * to the place that holds it, and Kestrel's card is read-only (a trade is answered in Needs you, with his fingerprint).
 */
enum class TeamAgent(val code: String, val title: String, val role: String, val level: String) {
    OBI("obi", "Obi", "Your assistant · the orb and Ask", "Asks before acting"),
    INBOX("inbox", "Inbox", "Sorts and tidies your mail", "Tidies by itself · Undo in Activity"),
    CALLS("calls", "Calls", "Screens calls, takes messages", "Screens by itself · you call back"),
    MONEY("money", "Money", "Your money check", "Ideas only · never moves money"),
    KESTREL("kestrel", "Kestrel", "Trading agent · read only here", "Every trade needs your yes and your fingerprint"),
    ;

    companion object {
        fun of(code: String?): TeamAgent? = entries.firstOrNull { it.code == code?.trim() }
    }
}

/** An agent's status on its card; [live] ones show a pulsing dot (still, with reduced motion). */
enum class TeamStatus(val label: String, val live: Boolean) {
    READY("Ready", false),
    WORKING("Working", true),
    WATCHING("Watching", true),
    IDLE("Idle", false),
    NOT_SET_UP("Not set up", false),
    /** Slice 2: Pause for today on its sheet, till midnight. */
    PAUSED("Paused till tomorrow", false),
}

/** Where Review takes him: Needs you (cards, mail, trades) or Money (Obi's money check). */
enum class TeamTarget(val label: String) {
    NEEDS_YOU("Review in Needs you"),
    MONEY("Review in Money"),
}

/** One of the three tiles at the top: "WORKING" and "5". */
data class TeamTile(val label: String, val value: String, val lit: Boolean, val spoken: String)

/** One line of an agent's day: "08:02" and "Read out the morning brief". */
data class TeamLogRow(val time: String, val text: String)

/** One agent's card and its sheet. */
data class TeamCard(
    val agent: TeamAgent,
    val status: TeamStatus,
    /** TODAY: "5 reminders · 1 said out loud", or "Nothing yet today". */
    val today: String,
    /** WAITS ON YOU on the card: "2 emails", or "Nothing". */
    val wait: String,
    /** Something waits on him: the wait box is lit. */
    val waits: Boolean,
    /** The sheet: today's log, oldest first (at most [TeamRules.LOG_MAX]). */
    val log: List<TeamLogRow>,
    /** The sheet's wait line ("3 emails need you"), null with nothing waiting. */
    val waitLine: String?,
    /** Where Review goes, null with nothing waiting. */
    val review: TeamTarget?,
    val spoken: String,
    /** Slice 2: paused for the rest of today (Pause for today on its sheet). */
    val paused: Boolean = false,
) {
    /** Slice 2: its sheet offers Pause for today / Resume (Obi, Inbox and Money; Calls and Kestrel don't). */
    val pausable: Boolean get() = agent in TeamPauseRules.PAUSABLE
    /** Slice 2: the sheet's line under the pause button: what pausing does, or that it's paused. */
    val pauseLine: String? get() = if (paused) TeamPauseRules.pausedLine(agent) else TeamPauseRules.pauseLine(agent)
    /** Slice 2: the sheet's button, null when it can't be paused. */
    val pauseAction: String? get() = if (!pausable) null else if (paused) TeamPauseRules.RESUME else TeamPauseRules.PAUSE
    val title: String get() = agent.title
    val role: String get() = agent.role
    val level: String get() = agent.level
    val readOnly: Boolean get() = agent == TeamAgent.KESTREL
}

data class TeamView(
    val tiles: List<TeamTile>,
    val cards: List<TeamCard>,
    /** "What each one did today, and what waits on you". */
    val subtitle: String,
    val needCount: Int,
) {
    fun card(code: String): TeamCard? = cards.firstOrNull { it.agent.code == code }

    companion object {
        val EMPTY = TeamView(emptyList(), emptyList(), TeamRules.SUBTITLE, 0)
    }
}

/**
 * What the team's lines are made from, as the core's last refresh has them. Plain numbers and words only, so the rules
 * are the same on both apps and testable without a replica.
 */
data class TeamInputs(
    /** The activity log (all of it; today's entries are picked here). */
    val activity: List<ActivityItem>,
    /** Cards from his messages waiting for a tap in Needs you ("Add milk to shopping?"). */
    val requestsWaiting: Int = 0,
    /** Needs you's mail card headline ("3 emails need you"), null with none. */
    val mailWait: String? = null,
    /** Obi's money check ideas showing now. */
    val moneyIdeas: Int = 0,
    /** Accounts Money reads (banks and statements). */
    val moneyAccounts: Int = 0,
    /** When the banks last synced ("06:10", "Tue 06:10"), null before. */
    val moneyUpdated: String? = null,
    /** Kestrel's card from Money → Trading, null until a trade was asked in the last 30 days. */
    val kestrel: TradingKestrelView? = null,
    /** Trade requests waiting in Needs you. */
    val tradesWaiting: Int = 0,
    /** Slice 2: the helpers paused for today ([TeamPauseRules.read]). */
    val paused: Set<TeamAgent> = emptySet(),
)

object TeamRules {
    const val TITLE = "Your team"
    const val ROW_LINE = "Obi, Inbox, Calls, Money and Kestrel · what each did today"
    const val SUBTITLE = "What each one did today, and what waits on you"
    const val WORKING = "Working"
    const val NEED_YOU = "Need you"
    const val DONE_TODAY = "Done today"
    const val TODAY = "Today"
    const val WAITS = "Waits on you"
    const val NOTHING = "Nothing"
    const val NOTHING_YET = "Nothing yet today"
    const val LOG_EMPTY = "Nothing logged today yet"
    const val CLOSE = "Close"
    const val FOOT = "Each one shows what it did. None sends messages, pays or trades by itself."
    const val KESTREL_FOOT = "Kestrel runs outside Navilife. Navilife never approves a trade: you answer in Needs you, with your fingerprint."
    const val LOG_MAX = 8

    private val MAIL_SOURCES = setOf(ActivityRules.MAIL_TIDY_SOURCE, ActivityRules.UNSUBSCRIBE_SOURCE, MailSentActivityRules.SOURCE)
    private val MONEY_SOURCES = setOf(NoticeSource.MONEY, NoticeSource.BILLS, NoticeSource.CARDS).map { "notice:${it.name}" }.toSet()

    private fun plural(n: Int, one: String, many: String = one + "s") = "${SpeechRules.grouped(n.toLong())} ${if (n == 1) one else many}"

    /**
     * Whose work an activity entry is: mail tidies, unsubscribes and held replies are the Inbox's; screened calls and the
     * call assistant's messages are Calls'; money heads-ups are Money's; Kestrel's answered requests are Kestrel's;
     * reminders, digests, what Obi said out loud and his changes are Obi's. His own calendar edits, new builds, family
     * links and devices are nobody's (null).
     */
    fun agentOf(i: ActivityItem): TeamAgent? = when {
        i.mailTidy || i.source in MAIL_SOURCES -> TeamAgent.INBOX
        i.kind == ActivityKind.SCREENED || i.kind == ActivityKind.CALL -> TeamAgent.CALLS
        i.kind == ActivityKind.APPROVAL -> if (i.why.startsWith("Kestrel")) TeamAgent.KESTREL else null
        i.source in MONEY_SOURCES -> TeamAgent.MONEY
        i.kind == ActivityKind.REMINDED || i.kind == ActivityKind.DIGEST || i.kind == ActivityKind.SPOKE ||
            i.kind == ActivityKind.CHANGED -> TeamAgent.OBI
        else -> null
    }

    fun view(input: TeamInputs, nowMs: Long, cal: LocalCalendar): TeamView {
        val today = cal.epochDayOf(nowMs)
        val todays = input.activity.filter { cal.epochDayOf(it.atMs) == today && it.atMs <= nowMs }.sortedBy { it.atMs }
        val by = todays.groupBy { agentOf(it) }
        fun items(a: TeamAgent) = by[a].orEmpty()
        fun log(a: TeamAgent) = items(a).takeLast(LOG_MAX).map { TeamLogRow(LocalClock.formatMinute(cal.minuteOfDay(it.atMs)), it.summary) }
        fun count(a: TeamAgent, pred: (ActivityItem) -> Boolean) = items(a).count(pred)
        fun joined(parts: List<String>) = parts.joinToString(" · ").ifEmpty { NOTHING_YET }

        val obi = run {
            val a = TeamAgent.OBI
            val reminders = count(a) { it.kind == ActivityKind.REMINDED || it.kind == ActivityKind.DIGEST }
            val said = count(a) { it.kind == ActivityKind.SPOKE }
            val changed = count(a) { it.kind == ActivityKind.CHANGED }
            val today = joined(listOfNotNull(
                reminders.takeIf { it > 0 }?.let { plural(it, "reminder") },
                said.takeIf { it > 0 }?.let { "$it said out loud" },
                changed.takeIf { it > 0 }?.let { plural(it, "change") },
            ))
            val n = input.requestsWaiting
            card(a, TeamStatus.READY, today, n.takeIf { it > 0 }?.let { plural(it, "card") + " to tap" },
                n.takeIf { it > 0 }?.let { plural(it, "card") + " from your messages in Needs you" }, TeamTarget.NEEDS_YOU, log(a))
        }
        val inbox = run {
            val a = TeamAgent.INBOX
            val tidies = count(a) { it.source == ActivityRules.MAIL_TIDY_SOURCE || it.mailTidy }
            val unsubs = count(a) { it.source == ActivityRules.UNSUBSCRIBE_SOURCE }
            val replies = count(a) { it.source == MailSentActivityRules.SOURCE }
            val today = joined(listOfNotNull(
                tidies.takeIf { it > 0 }?.let { plural(it, "tidy", "tidies") },
                unsubs.takeIf { it > 0 }?.let { plural(it, "unsubscribe") },
                replies.takeIf { it > 0 }?.let { plural(it, "reply", "replies") + " sent" },
            ))
            val wait = input.mailWait?.trim()?.takeIf { it.isNotEmpty() }
            val short = wait?.let { w -> Regex("""^([\d,]+ emails?)\b""").find(w)?.groupValues?.get(1) ?: w }
            card(a, if (items(a).isNotEmpty()) TeamStatus.WORKING else TeamStatus.READY, today, short, wait, TeamTarget.NEEDS_YOU, log(a))
        }
        val calls = run {
            val a = TeamAgent.CALLS
            val screened = count(a) { it.kind == ActivityKind.SCREENED }
            val messages = count(a) { it.kind == ActivityKind.CALL }
            val today = joined(listOfNotNull(
                screened.takeIf { it > 0 }?.let { "$it screened" },
                messages.takeIf { it > 0 }?.let { plural(it, "message") },
            ))
            card(a, TeamStatus.READY, today, null, null, null, log(a))
        }
        val money = run {
            val a = TeamAgent.MONEY
            val heads = items(a).size
            val accounts = input.moneyAccounts
            val today = joined(listOfNotNull(
                accounts.takeIf { it > 0 }?.let { plural(it, "account") + (input.moneyUpdated?.let { u -> " · updated $u" } ?: "") },
                heads.takeIf { it > 0 }?.let { plural(it, "heads-up", "heads-ups") },
            ))
            val n = input.moneyIdeas
            val status = if (accounts == 0 && n == 0) TeamStatus.NOT_SET_UP else TeamStatus.IDLE
            card(a, status, today, n.takeIf { it > 0 }?.let { plural(it, "idea") },
                n.takeIf { it > 0 }?.let { plural(it, "idea") + " in Obi's money check" }, TeamTarget.MONEY, log(a))
        }
        val kestrel = run {
            val a = TeamAgent.KESTREL
            val k = input.kestrel
            val today = k?.tally?.removePrefix("Today: ")?.takeIf { it.isNotBlank() } ?: "No trades asked"
            val n = maxOf(input.tradesWaiting, if (k?.waitingTitle != null) 1 else 0)
            val line = when {
                n == 0 -> null
                k?.waitingTitle != null && n == 1 -> "${k.waitingTitle} · answer in Needs you"
                else -> plural(n, "trade") + " to answer in Needs you"
            }
            card(a, if (k != null) TeamStatus.WATCHING else TeamStatus.IDLE, today, n.takeIf { it > 0 }?.let { plural(it, "trade") },
                line, TeamTarget.NEEDS_YOU, log(a))
        }
        val cards = listOf(obi, inbox, calls, money, kestrel).map { c ->
            if (c.agent !in input.paused || c.agent !in TeamPauseRules.PAUSABLE) c
            else c.copy(status = TeamStatus.PAUSED, paused = true, spoken = c.spoken.replace(". ${c.status.label}. ", ". ${TeamStatus.PAUSED.label}. "))
        }
        val need = cards.count { it.waits }
        val done = cards.sumOf { by[it.agent].orEmpty().size }
        val working = cards.count { it.status != TeamStatus.NOT_SET_UP && it.status != TeamStatus.PAUSED }
        val tiles = listOf(
            TeamTile(WORKING.uppercase(), working.toString(), false, "$working of ${cards.size} working"),
            TeamTile(NEED_YOU.uppercase(), need.toString(), need > 0, if (need == 0) "Nobody needs you" else "${plural(need, "helper")} need you"),
            TeamTile(DONE_TODAY.uppercase(), SpeechRules.grouped(done.toLong()), false, "${plural(done, "thing")} done today"),
        )
        return TeamView(tiles, cards, SUBTITLE, need)
    }

    private fun card(
        a: TeamAgent, status: TeamStatus, today: String, wait: String?, waitLine: String?, target: TeamTarget?, log: List<TeamLogRow>,
    ): TeamCard {
        val waits = wait != null && waitLine != null
        val spoken = "${a.title}, ${a.role}. ${status.label}. $TODAY: $today. $WAITS: ${if (waits) waitLine else NOTHING}."
        return TeamCard(a, status, today, if (waits) wait!! else NOTHING, waits, log,
            waitLine.takeIf { waits }, target.takeIf { waits }, spoken)
    }

    /**
     * Ask → Daily's row line: how many helpers wait on him ("2 need you", lit), from what the core already holds without
     * reading the log; null (the row's fixed words) when nothing waits.
     */
    fun dailyLine(requestsWaiting: Int, mailWaits: Boolean, moneyIdeas: Int, tradesWaiting: Int): DailyLine? {
        val n = listOf(requestsWaiting > 0, mailWaits, moneyIdeas > 0, tradesWaiting > 0).count { it }
        return if (n == 0) null else DailyLine(if (n == 1) "1 needs you" else "$n need you", true)
    }
}

/**
 * Your team, slice 2: **Pause for today** on Obi's, Inbox's and Money's sheets. A pause lasts till the next local
 * midnight and is synced (a `context_mode` entity, [ENTITY_ID], one Int64 field per helper's code: the moment it ends,
 * 0 after Resume; LWW), so both apps and the server see it. While paused:
 * - the governor turns that helper's heads-ups into digest lines ([SOURCES]): they wait for the next digest, nothing
 *   is lost, and reminders he set himself (tasks, events, sessions, parking, trains, bins, school) still come;
 * - Obi doesn't speak up;
 * - Inbox autopilot tidies nothing, marks nothing read and applies no clean-up (the server reads [inboxPaused]).
 * Calls and Kestrel can't be paused here: Calls has its own switch in Settings → Work mode, and Kestrel runs outside
 * Navilife. Pausing never sends, pays or trades anything; it only makes a helper do less.
 */
object TeamPauseRules {
    const val ENTITY_ID = "team_pause"
    const val PAUSE = "Pause for today"
    const val RESUME = "Resume"

    val PAUSABLE: Set<TeamAgent> = setOf(TeamAgent.OBI, TeamAgent.INBOX, TeamAgent.MONEY)

    /** Each pausable helper's heads-ups (never approvals, critical alerts or reminders he set himself). */
    val SOURCES: Map<TeamAgent, Set<NoticeSource>> = mapOf(
        TeamAgent.OBI to setOf(
            NoticeSource.BRIEF, NoticeSource.SHUTDOWN, NoticeSource.WEEKLY_REVIEW, NoticeSource.WEEK_AHEAD,
            NoticeSource.PRESSURE, NoticeSource.MEETING_PREP, NoticeSource.BALANCE, NoticeSource.GOALS, NoticeSource.WINS,
            NoticeSource.LIFE_ADMIN, NoticeSource.ROUTINES, NoticeSource.LEAVE, NoticeSource.APPOINTMENTS,
            NoticeSource.GIFTS, NoticeSource.KIDS, NoticeSource.DATE_NIGHT,
        ),
        TeamAgent.INBOX to setOf(NoticeSource.MAIL_MOMENT, NoticeSource.PROTECTED_MAIL, NoticeSource.REPLIED),
        TeamAgent.MONEY to setOf(NoticeSource.MONEY, NoticeSource.BILLS, NoticeSource.CARDS, NoticeSource.MONEY_LOOK),
    )

    fun pauseLine(a: TeamAgent): String? = when (a) {
        TeamAgent.OBI -> "Till midnight his nudges wait for your next digest and he won't speak up. Reminders you set still come."
        TeamAgent.INBOX -> "Till midnight Autopilot tidies nothing and its heads-ups wait for your next digest. Mail still arrives."
        TeamAgent.MONEY -> "Till midnight bills, cards and money heads-ups wait for your next digest."
        else -> null
    }

    fun pausedLine(a: TeamAgent): String? = if (a in PAUSABLE) "Paused till midnight · Resume to start again now" else null

    /** What the button says once tapped. */
    fun switchedLine(a: TeamAgent, paused: Boolean): String =
        if (paused) "${a.title} is paused till midnight" else "${a.title} is back at work"

    /** The helpers paused now: each field whose end is still ahead. */
    fun read(fields: Map<String, FieldValue>?, nowMs: Long): Set<TeamAgent> =
        PAUSABLE.filter { a -> ((fields?.get(a.code) as? FieldValue.Int64)?.value ?: 0L) > nowMs }.toSet()

    /** The server's check before Inbox autopilot acts on its own. */
    fun inboxPaused(fields: Map<String, FieldValue>?, nowMs: Long): Boolean = TeamAgent.INBOX in read(fields, nowMs)

    /** The next local midnight: when a pause made now ends. */
    fun untilMs(nowMs: Long, cal: LocalCalendar): Long = cal.toEpochMs(cal.epochDayOf(nowMs) + 1, 0)

    /** The write for Pause for today ([on]) or Resume; null for a helper that can't be paused. */
    fun fields(a: TeamAgent, on: Boolean, nowMs: Long, cal: LocalCalendar): Map<String, FieldValue>? =
        if (a !in PAUSABLE) null else mapOf(a.code to (if (on) untilMs(nowMs, cal) else 0L).fv())

    /** The heads-up sources the governor sends to the digest while these helpers are paused. */
    fun sources(paused: Set<TeamAgent>): Set<NoticeSource> = paused.flatMap { SOURCES[it].orEmpty() }.toSet()
}
