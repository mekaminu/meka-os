package os.meka.core.domain

/*
 * "Hello, Navilife" (build plan Fix first, Meka approved 2026-10-19 17:52; a new face on Talk, not a new feature).
 * Non-AI and pure: the greeting is built by rules from what the apps already show (instant, free, nothing sent), and
 * the orb's look follows the conversation's phase. The greeting is said in his chosen voice; then Talk listens as usual.
 */

/** Today's next event, as the greeting needs it: [minutesAway] is negative once it has started (until [endMinute]). */
data class HelloEvent(val title: String, val startMinute: Int, val endMinute: Int, val minutesAway: Int)

/**
 * What the greeting is built from, read by the facade from the current views (nothing new is fetched or stored).
 * [urgent] the urgent Needs you cards' titles (overdue, edited on two devices); [needsYou] all Needs you cards;
 * [train] the next live departure on today's commute with [trainMinutesAway]; [partnerAsks] what his partner asked
 * for that's still open; [dueToday] tasks due today with no time; [quiet] quiet hours are on now.
 */
data class HelloFacts(
    val minuteOfDay: Int,
    val name: String? = null,
    val urgent: List<String> = emptyList(),
    val needsYou: Int = urgent.size,
    val event: HelloEvent? = null,
    val train: TrainDeparture? = null,
    val trainMinutesAway: Int? = null,
    val partnerName: String? = null,
    val partnerAsks: Int = 0,
    val dueToday: List<String> = emptyList(),
    val quiet: Boolean = false,
)

enum class HelloKind { NEEDS_YOU, EVENT, TRAIN, PARTNER, DUE }

/** One thing the greeting says, and which card would rise beside the orb for it (a later slice draws the cards). */
data class HelloPoint(val kind: HelloKind, val text: String)

/** The greeting: "Good evening, Meka." · up to three points · "Anything you'd like?" */
data class HelloGreeting(val opening: String, val points: List<HelloPoint>, val closing: String) {
    /** What Navilife says, in one go. */
    val spoken: String get() = (listOf(opening) + points.map { it.text } + closing).joinToString(" ")
}

object HelloRules {
    /** At most this many things, in order: urgent Needs you, the next event or train, his partner's asks, due today. */
    const val MAX_POINTS = 3
    /** In quiet hours only the one thing that can't wait (urgent, or something starting within the hour). */
    const val QUIET_POINTS = 1
    /** A train or event this far ahead or less is "next"; beyond it the evening is still clear of it. */
    const val NEXT_WITHIN_MIN = 3 * 60
    const val QUIET_NEXT_WITHIN_MIN = 60
    const val CLOSING = "Anything you'd like?"
    /** What Talk remembers was asked, so "tell me more" follows on from the greeting. */
    const val ASKED = "Hello Navilife"

    /**
     * ⌥Space on the Mac (and the Fold's stand orb, which always greets): a greeting when none was said in the last
     * [REGREET_MIN] (a return to the desk), else straight to listening so a quick question isn't slowed by it.
     */
    const val REGREET_MIN = 120

    /** [minutesSinceLast] since the last greeting on this device, or negative when there hasn't been one. */
    fun hotkeyGreets(minutesSinceLast: Int): Boolean = minutesSinceLast < 0 || minutesSinceLast >= REGREET_MIN

    /** Who a request card names when it's his partner's though the name isn't hers ("Wife" in the contacts). */
    private val PARTNER_ROLES = setOf("wife", "husband", "partner", "hubby", "wifey")

    /**
     * His partner's open asks, from the requests' [people] (as each message's notification named them): the ones
     * whose first name is About you's partner's first name, or who are saved by role ("Wife"). Zero without a partner.
     */
    fun partnerAsks(partnerName: String?, people: List<String>): Int {
        val partner = firstName(partnerName)?.lowercase()
        return people.count { who ->
            val first = firstName(who)?.lowercase()?.trimEnd(':', ',') ?: return@count false
            (partner != null && first == partner) || (partner != null && first in PARTNER_ROLES)
        }
    }

    /** "Good morning" (05:00–11:59), "Good afternoon" (12:00–16:59), "Good evening" (17:00–04:59). */
    fun salutation(minuteOfDay: Int): String = when (minuteOfDay.mod(1440) / 60) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }

    /** "morning", "afternoon", "evening" for "Your evening's clear." */
    fun partOfDay(minuteOfDay: Int): String = salutation(minuteOfDay).removePrefix("Good ")

    /** His first name as he typed it in About you; null when blank. */
    fun firstName(shown: String?): String? = shown?.trim()?.split(' ')?.firstOrNull()?.takeIf { it.isNotBlank() }

    fun greeting(f: HelloFacts): HelloGreeting {
        val name = firstName(f.name)
        val opening = salutation(f.minuteOfDay) + (name?.let { ", $it." } ?: ".")
        val points = points(f)
        val closing = if (points.isEmpty()) "Nothing needs you. Your ${partOfDay(f.minuteOfDay)}'s clear. $CLOSING" else CLOSING
        return HelloGreeting(opening, points, closing)
    }

    fun points(f: HelloFacts): List<HelloPoint> {
        val out = mutableListOf<HelloPoint>()
        needsYouLine(f.urgent)?.let { out += HelloPoint(HelloKind.NEEDS_YOU, it) }
        next(f)?.let { out += it }
        if (!f.quiet) {
            partnerLine(f.partnerName, f.partnerAsks)?.let { out += HelloPoint(HelloKind.PARTNER, it) }
            dueLine(f.dueToday)?.let { out += HelloPoint(HelloKind.DUE, it) }
        }
        return out.take(if (f.quiet) QUIET_POINTS else MAX_POINTS)
    }

    /** "One thing needs you: Pay the council tax." · "3 things need you, starting with Pay the council tax." */
    fun needsYouLine(urgent: List<String>): String? {
        val first = urgent.firstOrNull()?.let(::clean) ?: return null
        return if (urgent.size == 1) "One thing needs you: $first." else "${urgent.size} things need you, starting with $first."
    }

    /** The next train or event, whichever comes first within [NEXT_WITHIN_MIN] (an hour in quiet hours). */
    private fun next(f: HelloFacts): HelloPoint? {
        val within = if (f.quiet) QUIET_NEXT_WITHIN_MIN else NEXT_WITHIN_MIN
        val train = f.train?.takeIf { (f.trainMinutesAway ?: Int.MAX_VALUE) in 0..within }
        val event = f.event?.takeIf { it.minutesAway <= within }
        return when {
            train != null && (event == null || f.trainMinutesAway!! <= event.minutesAway) -> HelloPoint(HelloKind.TRAIN, trainLine(train))
            event != null -> HelloPoint(HelloKind.EVENT, eventLine(event))
            else -> null
        }
    }

    /** "Your 07:12 train is on time." · "… is 5 minutes late." · "… is cancelled." · "… is delayed." */
    fun trainLine(d: TrainDeparture): String {
        val t = LocalClock.formatMinute(d.depMin)
        val late = d.lateMin
        val how = when {
            d.cancelled -> "is cancelled"
            late == null -> "is delayed"
            late == 0 -> "is on time"
            late == 1 -> "is 1 minute late"
            else -> "is $late minutes late"
        }
        return "Your $t train $how."
    }

    /** "Standup is on now, until 15:30." · "Standup starts in 12 minutes." · "Next, Dentist at 16:00." */
    fun eventLine(e: HelloEvent): String {
        val title = clean(e.title)
        return when {
            e.minutesAway <= 0 -> "$title is on now, until ${LocalClock.formatMinute(e.endMinute)}."
            e.minutesAway == 1 -> "$title starts in a minute."
            e.minutesAway <= 30 -> "$title starts in ${e.minutesAway} minutes."
            else -> "Next, $title at ${LocalClock.formatMinute(e.startMinute)}."
        }
    }

    /** "Sam asked you for something." · "Sam asked you for 2 things." (his partner's name from About you). */
    fun partnerLine(partnerName: String?, asks: Int): String? {
        if (asks <= 0) return null
        val who = firstName(partnerName) ?: "Your partner"
        return if (asks == 1) "$who asked you for something." else "$who asked you for $asks things."
    }

    /** "Send the invoice is due today." · "Send the invoice and 2 more are due today." */
    fun dueLine(due: List<String>): String? {
        val first = due.firstOrNull()?.let(::clean) ?: return null
        return when (due.size) {
            1 -> "$first is due today."
            2 -> "$first and 1 more are due today."
            else -> "$first and ${due.size - 1} more are due today."
        }
    }

    private fun clean(title: String): String = TalkRules.speakable(title.trim()).trimEnd('.', '!', '?')
}

/** The orb's look: one of five states, from the conversation's phase and whether anything needs him. */
enum class OrbState { IDLE, LISTENING, THINKING, SPEAKING, NEEDS_YOU }

/**
 * The Hello orb's numbers (motion catalogue "Hello, Navilife"), the same on the Fold and the Mac: a larger brass orb
 * than the field's mic, with a soft core. Idle breathes (the empty states' breath); listening swells with his voice
 * ([TalkOrb.scale]); thinking turns two arcs round the rim ([swirl]); speaking sends ripples out ([TalkOrb.ripple]);
 * needs-you breathes a faint warm halo ([pulse]) while Needs you has cards. Reduced motion: a still glow, no turning,
 * and the level bar while listening.
 */
object HelloOrb {
    /** The orb's box on the Fold (dp) and the Mac (pt): room for the swell and the ripples inside it. */
    const val SIZE = 132
    /** The bedside clock's orb (slice 2), where its mic was: large enough to tap half asleep, small beside the clock. */
    const val BEDSIDE_SIZE = 64
    /** The Mac's sidebar orb, at the sidebar's foot (slice 2), and the menu bar window's. */
    const val SIDEBAR_SIZE = 40
    /** One turn of the thinking arcs. */
    const val SWIRL_MS = 1_800L
    /** One breath of the needs-you halo. */
    const val PULSE_MS = 2_600L
    /** Each thinking arc's sweep, in degrees (two, opposite). */
    const val ARC_DEG = 70f

    fun state(phase: TalkPhase, needsYou: Int): OrbState = when (phase) {
        TalkPhase.LISTENING -> OrbState.LISTENING
        TalkPhase.THINKING, TalkPhase.DOING -> OrbState.THINKING
        TalkPhase.SPEAKING -> OrbState.SPEAKING
        TalkPhase.ENDED -> if (needsYou > 0) OrbState.NEEDS_YOU else OrbState.IDLE
    }

    /** How far round the thinking arcs are at [elapsedMs]: 0 → 1 a turn (0 with reduced motion). */
    fun swirl(elapsedMs: Long, reduced: Boolean): Float =
        if (reduced) 0f else (elapsedMs.coerceAtLeast(0L) % SWIRL_MS).toFloat() / SWIRL_MS

    /** The needs-you halo at [elapsedMs]: 0 → 1 → 0, eased (a still 0.5 with reduced motion). */
    fun pulse(elapsedMs: Long, reduced: Boolean): Float {
        if (reduced) return 0.5f
        val p = (elapsedMs.coerceAtLeast(0L) % PULSE_MS).toFloat() / PULSE_MS
        val tri = if (p < 0.5f) p * 2 else (1 - p) * 2
        return tri * tri * (3 - 2 * tri) // smoothstep
    }

    /** The orb's size for a phase: [TalkOrb.scale], speaking held a touch larger so the ripples read as its voice. */
    fun scale(state: OrbState, level: Float, breath: Float): Float = when (state) {
        OrbState.LISTENING -> TalkOrb.scale(TalkPhase.LISTENING, level, breath)
        OrbState.SPEAKING -> 1f
        else -> TalkOrb.scale(TalkPhase.ENDED, 0f, breath)
    }

    /** The line under the resting orb (and its screen-reader label): "Tap to say hello" · "1 thing needs you · tap to say hello". */
    fun restingLine(needsYou: Int, mac: Boolean): String {
        val press = if (mac) "click" else "tap"
        return when {
            needsYou <= 0 -> "${press.replaceFirstChar { it.uppercase() }} to say hello"
            needsYou == 1 -> "1 thing needs you · $press to say hello"
            else -> "$needsYou things need you · $press to say hello"
        }
    }
}
