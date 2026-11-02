package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Ada plans with you, part 3 slice 1 (Meka 2026-10-21 19:54: "agents re agentic AI"): **Obi is working on**, the one
 * place that lists what Obi keeps an eye on for him. This slice is the standing errands he runs without being asked,
 * each switchable and synced: the **Renewals watcher** (his radar: four weeks ahead he asks whether to keep it or look
 * around), the **Birthday keeper** (Gifts: a heads-up ten days ahead with the ideas he noted) and the **Follow-up
 * chaser** (Waiting for: when a chase is due). Each is a card with "What I did" and, when there is one, "What I need from
 * you", and a way to the screen that holds it. Rules on the device, no AI, nothing sent, booked or bought: Obi only
 * says; every send stays a card for his tap. Errands he is asked for ("find someone to service the boiler") are the
 * next slices, on the server.
 */
enum class StandingErrand(val code: String, val title: String, val about: String, val place: HelpPlace, val openLabel: String) {
    RENEWALS("renewals", "Renewals watcher", "Your renewals, MOT and subscriptions: a word four weeks ahead", HelpPlace.LISTS, "Open Renewals"),
    BIRTHDAYS("birthdays", "Birthday keeper", "Birthdays and anniversaries: a heads-up ten days ahead with your gift ideas", HelpPlace.GIFTS, "Open Gifts"),
    FOLLOW_UPS("follow_ups", "Follow-up chaser", "Things you're waiting on: a word when it's time to chase", HelpPlace.LISTS, "Open Waiting for"),
    /** Part 3 slice 2: today's mail autopilot, reported here (what was tidied, what waits for his Approve or reply). */
    INBOX("inbox", "Inbox keeper", "Your mail: what Obi tidied today and what needs you", HelpPlace.MAIL, "Open Mail"),
    /** Part 3 slice 2: the children's next seven days from School, their club sessions and fees (Kids → THIS WEEK). */
    SCHOOL("school", "School & clubs", "School dates, kit, club sessions and fees in the next 7 days", HelpPlace.KIDS, "Open Kids"),
    ;

    companion object {
        fun of(code: String?): StandingErrand? = entries.firstOrNull { it.code == code?.trim() }
    }
}

/** One card: what Obi did ([did]) and, when he needs something, what ([need], lit). */
data class WorkingOnCard(
    val errand: StandingErrand,
    val did: String,
    val need: String?,
    /** What a screen reader and Obi say for the card. */
    val spoken: String,
) {
    val title: String get() = errand.title
    val needsYou: Boolean get() = need != null
}

/** A standing errand's switch: on or off, with its line and the button's words. */
data class WorkingOnSwitch(val errand: StandingErrand, val on: Boolean) {
    val line: String get() = if (on) errand.about else "Off · Obi isn't watching this"
    val action: String get() = if (on) "Turn off" else "Turn on"
}

data class WorkingOnView(
    val cards: List<WorkingOnCard>,
    val switches: List<WorkingOnSwitch>,
    /** "Keeping an eye on 3 things · 2 need you". */
    val summary: String,
) {
    val needCount: Int get() = cards.count { it.needsYou }
    val isEmpty: Boolean get() = cards.isEmpty()

    companion object {
        val EMPTY = WorkingOnView(emptyList(), StandingErrand.entries.map { WorkingOnSwitch(it, true) }, WorkingOnRules.EMPTY_LINE)
    }
}

/** The synced switches: absent means on. */
data class WorkingOnSetting(val off: Set<StandingErrand> = emptySet()) {
    fun isOn(e: StandingErrand): Boolean = e !in off
}

object WorkingOnRules {
    const val ENTITY_ID = "standing_errands"
    const val TITLE = "Obi is working on"
    const val ROW_LINE = "Renewals, birthdays, follow-ups, mail and school he keeps an eye on"
    const val INTRO = "Obi keeps an eye on these for you and says when he needs you. He never sends, books or buys anything without your tap."
    const val EMPTY_LINE = "Nothing to keep an eye on yet · add a renewal, a birthday, something you're waiting on, your mail or the children and Obi watches it here"
    const val ALL_OFF_LINE = "Every standing errand is off · turn one on below"
    const val SWITCHES = "Standing errands"
    const val DID = "What I did"
    const val NEED = "What I need from you"
    const val NEXT = "Coming next: errands you ask Obi for, like finding someone to service the boiler."

    /** A renewal comes up this many days ahead (the board: "reminds 4 weeks ahead"). */
    const val RENEWAL_AHEAD = 28L
    /** A birthday comes up this many days ahead (the board: "a heads-up 10 days ahead"). */
    const val BIRTHDAY_AHEAD = 10L
    /** Ask's lines: a head and up to two per errand. */
    const val ASK_MAX = 1 + 5 * 2

    private const val OFF = "off"

    fun read(fields: Map<String, FieldValue>?): WorkingOnSetting =
        WorkingOnSetting(StandingErrand.entries.filter { fields?.get(it.code)?.textOrNull == OFF }.toSet())

    private fun plural(n: Int, one: String, many: String = one + "s") = "${SpeechRules.grouped(n.toLong())} ${if (n == 1) one else many}"
    private fun tidy(s: String) = s.replace(Regex("""\s+"""), " ").trim()

    /** The Renewals watcher's card: null when the radar is empty. */
    fun renewals(v: RenewalsView, today: Long): WorkingOnCard? {
        val all = v.all
        if (all.isEmpty()) return null
        val first = all.minWith(compareBy<RenewalItem> { it.dueDay }.thenBy { it.title.lowercase() })
        fun says(i: RenewalItem) = "${tidy(i.title)} ${if (i.dueDay < today) "was due" else RenewalRules.verb(i.kind)} ${RenewalRules.dayWord(i.dueDay, today)}"
        val did = "Watching ${plural(all.size, "renewal")} · next: ${says(first)}"
        // What needs him: one in Needs you first (overdue, cancel-by), else the soonest within four weeks.
        val soon = v.attention.firstOrNull() ?: all.filter { it.dueDay <= today + RENEWAL_AHEAD }.minByOrNull { it.dueDay }
        val need = soon?.let { i ->
            val cancel = i.cancelByDay?.takeIf { it >= today && i.dueDay >= today }
            when {
                i.dueDay < today -> "${says(i)} · mark it done, or move the date?"
                cancel != null -> "${says(i)} · cancel by ${RenewalRules.dayWord(cancel, today)} if you're not keeping it"
                else -> "${says(i)} · keep it, or look around first?"
            }
        }
        return card(StandingErrand.RENEWALS, did, need)
    }

    /** The Birthday keeper's card: null when Gifts is empty. */
    fun birthdays(v: GiftsView, today: Long): WorkingOnCard? {
        if (v.rows.isEmpty()) return null
        val soon = v.rows.filter { r -> !r.sorted && r.day != null && r.day - today in 0..BIRTHDAY_AHEAD }
        val first = soon.firstOrNull()
        if (first == null) {
            val next = v.rows.first()
            val did = "Keeping ${plural(v.rows.size, "birthday or anniversary", "birthdays and anniversaries")} · next: ${tidy(next.title)}, ${next.line}"
            return card(StandingErrand.BIRTHDAYS, did, null)
        }
        val ideas = if (first.ideas.isEmpty()) "no gift ideas yet" else "${plural(first.ideas.size, "gift idea")} noted"
        val more = (soon.size - 1).takeIf { it > 0 }?.let { " · and $it more soon" }.orEmpty()
        val did = "${tidy(first.title)} · ${first.line} · $ideas$more"
        val note = first.note.replaceFirstChar { it.lowercase() }
        val need = when {
            first.day == today -> "It's today · send your wishes"
            first.ideas.isEmpty() -> "Pick a gift · $note"
            else -> "Choose from your ideas · $note"
        }
        return card(StandingErrand.BIRTHDAYS, did, need)
    }

    /** The Follow-up chaser's card: null when nothing is in Waiting for. */
    fun followUps(waiting: List<WaitingItem>, today: Long): WorkingOnCard? {
        if (waiting.isEmpty()) return null
        val due = waiting.filter { it.state == DueState.DUE }
        val nextChase = waiting.mapNotNull { it.chaseDay }.filter { it > today }.minOrNull()
        val tail = when {
            due.isNotEmpty() -> " · ${plural(due.size, "to chase", "to chase")} now"
            nextChase != null -> " · next chase ${RenewalRules.dayWord(nextChase, today)}"
            else -> " · no chase dates set"
        }
        val did = "Waiting on ${plural(waiting.size, "thing")} for you$tail"
        val need = due.firstOrNull()?.let { w ->
            "Time to chase: ${tidy(w.title)}" + (w.who?.let { tidy(it) }?.takeIf { it.isNotEmpty() }?.let { " from $it" }.orEmpty()) +
                ((due.size - 1).takeIf { it > 0 }?.let { " · and $it more" }.orEmpty())
        }
        return card(StandingErrand.FOLLOW_UPS, did, need)
    }

    /**
     * Part 3 slice 2, the **Inbox keeper**: today's mail autopilot, reported here. What he did: the new mail tidied
     * today and this week (not the clean-up, not Junk, not undone) and how many accounts Navilife reads. What it needs:
     * a clean-up, Mark read or Empty Junk waiting for his Approve, else the emails the server sorted as needing him
     * (counts only: senders and subjects are untrusted and stay on their card), else the accounts Navilife can't read.
     * Null while no mail is connected and nothing was ever tidied.
     */
    fun inbox(
        tidies: List<MailTidyBatch>,
        backlogs: List<MailBacklog>,
        accounts: List<MailAccountFacts>?,
        needs: MailNeedsCard?,
        nowMs: Long,
        cal: LocalCalendar,
    ): WorkingOnCard? {
        val mail = accounts.orEmpty().filter { MailProviders.isMail(it.provider) }
        val fresh = tidies.filter { !it.backlog && !it.junk && it.undoneAtMs == null }
        if (mail.isEmpty() && fresh.isEmpty() && backlogs.isEmpty() && needs == null) return null
        val today = cal.epochDayOf(nowMs)
        val todayN = fresh.filter { cal.epochDayOf(it.atMs) == today }.sumOf { it.count }
        val weekN = fresh.filter { today - cal.epochDayOf(it.atMs) in 0L..6L }.sumOf { it.count }
        val tidied = when {
            todayN > 0 -> "Tidied ${plural(todayN, "email")} today" + if (weekN > todayN) " · ${SpeechRules.grouped(weekN.toLong())} this week" else ""
            weekN > 0 -> "Nothing to tidy yet today · ${plural(weekN, "email")} this week"
            else -> "Nothing tidied yet"
        }
        val read = mail.count { !MailHealthRules.unreadable(it, nowMs) }
        val reading = if (mail.isEmpty()) "" else " · reading $read of ${plural(mail.size, "account")}"
        val approve = MailBacklogRules.cards(backlogs, false, nowMs).size
        val unreadable = MailHealthRules.unreadableNames(mail, nowMs)
        val need = when {
            approve > 0 -> "${plural(approve, "mail clean-up")} ${if (approve == 1) "waits" else "wait"} for your Approve in Needs you"
            needs != null && needs.rows.isNotEmpty() -> "${needs.headline} · in Needs you"
            unreadable != null -> "I can't read $unreadable yet · Mail says why"
            else -> null
        }
        return card(StandingErrand.INBOX, tidied + reading, need)
    }

    /** Within this many days a school date to pay, sign or send back needs him (Kids' THIS WEEK is 7 days). */
    const val SCHOOL_AHEAD = 7L

    /**
     * Part 3 slice 2, **School & clubs**: the children's next seven days as Kids → THIS WEEK has them (School's days
     * off, dates and weekly kit, their club sessions, their fees), with the first thing named. What it needs: a
     * week-ahead cover question ("off Mon 27 Oct · who's covering?") first, else a school date to pay, sign or send
     * back within the week. All his own typed lines or his calendar's; a club session's title is the calendar's words,
     * so it is never quoted here. Null with no children in About you.
     */
    fun school(week: KidWeek?, covers: List<SchoolCover>, items: List<SchoolItem>, today: Long): WorkingOnCard? {
        if (week == null && covers.isEmpty()) return null
        val first = week?.days?.firstOrNull()?.let { d -> d.items.firstOrNull { !it.fromCalendar }?.let { "${d.label}, ${tidy(it.text)}" } }
        val did = when {
            week == null || week.isEmpty -> "Nothing for the children in the next 7 days"
            first != null -> "${week.line} · next: $first"
            else -> week.line
        }
        val cover = covers.firstOrNull()
        val due = items.filter { it.kind == SchoolKind.DAY && it.startDay in today..today + SCHOOL_AHEAD && SchoolRules.isDue(it.title) }
            .minWithOrNull(compareBy<SchoolItem> { it.startDay }.thenBy { it.addedAtMs })
        val need = when {
            cover != null -> "${tidy(cover.title)} · ${tidy(cover.question)}"
            due != null -> (if (due.who.isEmpty()) "" else "${due.who}: ") + "${tidy(due.title)} ${RenewalRules.dayWord(due.startDay, today)} · pay, sign or send it back by then"
            else -> null
        }
        return card(StandingErrand.SCHOOL, did, need)
    }

    private fun card(e: StandingErrand, did: String, need: String?) =
        WorkingOnCard(e, did, need, listOfNotNull(e.title, did, need?.let { "Needs you: $it" }).joinToString(". "))

    /** The pane: the errands that are on and have something, those needing him first; every switch underneath. */
    fun view(
        setting: WorkingOnSetting, renewals: RenewalsView, gifts: GiftsView, waiting: List<WaitingItem>, today: Long,
        inbox: WorkingOnCard? = null, school: WorkingOnCard? = null,
    ): WorkingOnView {
        val cards = StandingErrand.entries.filter { setting.isOn(it) }.mapNotNull { e ->
            when (e) {
                StandingErrand.RENEWALS -> renewals(renewals, today)
                StandingErrand.BIRTHDAYS -> birthdays(gifts, today)
                StandingErrand.FOLLOW_UPS -> followUps(waiting, today)
                StandingErrand.INBOX -> inbox
                StandingErrand.SCHOOL -> school
            }
        }.sortedBy { if (it.needsYou) 0 else 1 }
        val switches = StandingErrand.entries.map { WorkingOnSwitch(it, setting.isOn(it)) }
        val need = cards.count { it.needsYou }
        val summary = when {
            switches.none { it.on } -> ALL_OFF_LINE
            cards.isEmpty() -> EMPTY_LINE
            else -> "Keeping an eye on ${plural(cards.size, "thing")}" + if (need > 0) " · $need need${if (need == 1) "s" else ""} you" else " · nothing needs you"
        }
        return WorkingOnView(cards, switches, summary)
    }

    /** Ask → Daily's row: "2 need you" (lit), "Watching 3 things", "All off"; null (the fixed words) with nothing to watch. */
    fun dailyLine(v: WorkingOnView): DailyLine? = when {
        v.switches.none { it.on } -> DailyLine("All off", false)
        v.needCount > 0 -> DailyLine("${v.needCount} need${if (v.needCount == 1) "s" else ""} you", true)
        v.cards.isNotEmpty() -> DailyLine("Watching ${plural(v.cards.size, "thing")}", false)
        else -> null
    }

    private val ASKED = Regex(
        """\b(what are you (working on|doing|up to|keeping an eye on)|what('re| are) you (working on|watching)|working on for me|""" +
            """standing errands?|keeping an eye on|renewals watcher|birthday keeper|follow-?up chaser|what's obi (doing|working on))\b""",
    )

    /** Whether [q] asks what Obi is working on (then every line goes, else the head alone). */
    fun asked(q: String): Boolean = ASKED.containsMatchIn(q.lowercase())

    /**
     * Ask's `working_on` lines: "Obi is working on · 3 standing errands on · 2 need you", then per errand "Renewals
     * watcher · Watching 6 renewals · next: … · needs you: …" and "Birthday keeper · off". Nothing at all while there is
     * nothing to watch and every errand is on; the head alone when not [full].
     */
    fun askLines(v: WorkingOnView, full: Boolean): List<String> {
        val off = v.switches.filter { !it.on }
        if (v.cards.isEmpty() && off.isEmpty()) return emptyList()
        val on = v.switches.count { it.on }
        val head = "$TITLE · ${plural(on, "standing errand")} on" +
            if (v.cards.isEmpty()) " · nothing to watch yet" else if (v.needCount > 0) " · ${v.needCount} need${if (v.needCount == 1) "s" else ""} you" else " · nothing needs you"
        if (!full) return listOf(head)
        val lines = v.cards.map { c -> listOfNotNull(c.title, c.did, c.need?.let { "needs you: $it" }).joinToString(" · ") } +
            off.map { "${it.errand.title} · off" }
        return (listOf(head) + lines).take(ASK_MAX)
    }
}

/** The switches, synced (one entity, id [WorkingOnRules.ENTITY_ID]). */
class WorkingOnSettings(private val replica: Replica) {
    fun setting(): WorkingOnSetting = WorkingOnRules.read(replica.entity(EntityTypes.CONTEXT_MODE, WorkingOnRules.ENTITY_ID)?.fields)

    /** False (nothing written) when it's already so. */
    fun set(e: StandingErrand, on: Boolean): Boolean {
        if (setting().isOn(e) == on) return false
        replica.commitLocal(EntityTypes.CONTEXT_MODE, WorkingOnRules.ENTITY_ID, mapOf(e.code to (if (on) "on" else "off").fv()))
        return true
    }
}
