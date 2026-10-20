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
    /** Slice 3: the Mac's welcome back after a while away ([WelcomeBackRules]): "Welcome back, Meka." opens it. */
    val welcome: Boolean = false,
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
        val opening = (if (f.welcome) WelcomeBackRules.OPENING else salutation(f.minuteOfDay)) + (name?.let { ", $it." } ?: ".")
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

// ---- "Hello, Navilife" slice 5: placement B, the docked orb ----

/** What a tap on the docked orb does: say hello (Ask comes forward and greets), or go back to the conversation. */
enum class DockTap { HELLO, CONVERSATION }

/**
 * Placement B (Meka approved 2026-10-19 20:14): a small docked Hello orb in the **middle of the Fold's bottom bar on
 * every screen** (Today · Needs you · ◉ · Calendar · Ask), and in the same place in the open Fold's rail: the small
 * constellation ([Constellation.POINTS_SMALL] points, no dust), raised a little above the bar's line, amber with two
 * comets while Needs you has cards, its points shivering faster while Navilife listens. A tap says hello from wherever
 * he is (Ask comes forward and greets, then listens); during a conversation it takes him back to it. (Non-AI, pure.)
 */
object HelloDock {
    /** The docked orb's box (dp): the sphere sits inside it with room for its comets. */
    const val SIZE = 48
    /** How far it sits above the bar's centre line (dp), so it reads as docked rather than another tab. */
    const val RAISE = 8
    /** Its place among the bar's slots: between Needs you and Calendar. */
    const val SLOT = 2
    /** The bar's slots: the four tabs and the orb. */
    const val SLOTS = 5
    /** The level it shivers with while listening or speaking (the bar doesn't hear his voice; the conversation's orb does). */
    const val LIVE_LEVEL = 0.55f

    /** The slot a tab sits in, by its index among the four tabs: the tabs after the orb move one along. */
    fun slot(tabIndex: Int): Int = if (tabIndex >= SLOT) tabIndex + 1 else tabIndex

    fun state(phase: TalkPhase, needsYou: Int): OrbState = HelloOrb.state(phase, needsYou)

    fun level(state: OrbState): Float = if (state == OrbState.LISTENING || state == OrbState.SPEAKING) LIVE_LEVEL else 0f

    /** A conversation running takes him back to it; otherwise the tap says hello. */
    fun tap(phase: TalkPhase): DockTap = if (phase == TalkPhase.ENDED) DockTap.HELLO else DockTap.CONVERSATION

    /** The screen reader's label: "Say hello to Navilife · 2 things need you" · "Navilife is listening · back to the conversation". */
    fun label(phase: TalkPhase, needsYou: Int): String = when (phase) {
        TalkPhase.LISTENING -> "Navilife is listening · back to the conversation"
        TalkPhase.THINKING, TalkPhase.DOING -> "Navilife is thinking · back to the conversation"
        TalkPhase.SPEAKING -> "Navilife is speaking · back to the conversation"
        TalkPhase.ENDED -> when {
            needsYou <= 0 -> "Say hello to Navilife"
            needsYou == 1 -> "Say hello to Navilife · 1 thing needs you"
            else -> "Say hello to Navilife · $needsYou things need you"
        }
    }
}

// ---- "Hello, Navilife" slice 3: the wake phrase and the Mac's welcome back ----

/**
 * "Hello Navilife" said aloud starts the greeting (slice 3). Off until he turns it on (kept on each device, never
 * synced), and it listens only where it's clearly wanted: the Fold on its stand (the bedside clock on screen, charging,
 * outside quiet hours) or the Mac with Navilife the app in front. Recognised on the device only (the phone's on-device
 * recogniser, the Mac's on-device speech): the words never leave it and nothing is kept. While it listens a small brass
 * dot and [LISTENING_LINE] show beside the orb. Never in the pocket, never while a conversation is already running.
 * (Non-AI, pure.)
 */
object HelloWakeRules {
    const val LABEL = "Say “Hello Navilife”"
    const val DEFAULT_ON = false
    const val TURN_ON = "Turn on"
    const val TURN_OFF = "Turn off"
    /** Beside the orb while it listens for the phrase (the visible indicator). */
    const val LISTENING_LINE = "Listening for “Hello Navilife”"

    /** The Mac's on-device speech stops a task after about a minute: a fresh one starts each window. */
    const val MAC_WINDOW_MS = 50_000L
    /** After a result or an error the next listen starts after this, doubling with errors in a row up to [MAX_DELAY_MS]. */
    const val RESTART_MS = 400L
    const val MAX_DELAY_MS = 30_000L
    /** This many errors in a row (the recogniser busy or missing) and it stops until the stand or the app comes back. */
    const val MAX_ERRORS = 8

    private val GREETINGS = setOf("hello", "hey", "hi", "hiya", "ok", "okay")

    /** How the recogniser writes "Navilife" (one word or two; its common mishearings of a new name). */
    private val NAMES: List<List<String>> = listOf(
        listOf("navilife"), listOf("navi", "life"), listOf("navy", "life"), listOf("navie", "life"), listOf("navvy", "life"),
        listOf("navalife"), listOf("navelife"), listOf("navylife"), listOf("navi", "live"), listOf("nav", "e", "life"),
        listOf("navi", "lyfe"), listOf("navilyfe"), listOf("navigate", "life"),
    )

    /** The words, lower case, letters and digits only, split on everything else ("Hey, Navi-life!" → hey navi life). */
    fun words(text: String): List<String> =
        text.lowercase().map { if (it.isLetterOrDigit() || it == '\'') it else ' ' }.joinToString("")
            .replace("'", "").split(' ').filter { it.isNotEmpty() }

    /**
     * Whether [transcript] (a partial or final result) holds the phrase: a greeting word straight before the name, or
     * the name alone as the first thing said ("Navilife, …"). Anything else (the name in the middle of a sentence on
     * the radio, "hello" alone) isn't a wake.
     */
    fun heard(transcript: String?): Boolean {
        val w = words(transcript ?: return false)
        if (w.isEmpty()) return false
        for (i in w.indices) {
            val nameAt = i + if (w[i] in GREETINGS) 1 else 0
            if (nameAt == i && i != 0) continue
            if (NAMES.any { n -> nameAt + n.size <= w.size && n.indices.all { w[nameAt + it] == n[it] } }) return true
        }
        return false
    }

    /**
     * Whether to listen for the phrase now: switched on ([on]), the microphone already allowed (never asked for by this),
     * the right place ([place]: the Fold on its stand, the Mac's Navilife in front), no conversation running
     * ([talking]), and the Fold [charging] (battery-light: the Mac passes true) outside [quiet] hours.
     */
    fun listens(on: Boolean, micAllowed: Boolean, place: Boolean, talking: Boolean, charging: Boolean = true, quiet: Boolean = false): Boolean =
        on && micAllowed && place && !talking && charging && !quiet

    /** The wait before the next listen, after [errorsInRow] errors in a row (0 after a plain result). Null: give up. */
    fun restartDelayMs(errorsInRow: Int): Long? = when {
        errorsInRow <= 0 -> RESTART_MS
        errorsInRow >= MAX_ERRORS -> null
        else -> minOf(MAX_DELAY_MS, RESTART_MS shl errorsInRow)
    }

    /** The Talk pane's section (the Fold's, [mac] false) and the Mac's sheet's, with its switch; lit while on. */
    fun section(on: Boolean, mac: Boolean, available: Boolean = true): TalkSetupSection = TalkSetupSection(
        LABEL,
        when {
            !available -> "This ${if (mac) "Mac" else "phone"} can't recognise speech on the device, so the phrase can't be listened for."
            on && mac -> "On: while Navilife is the app in front, say “Hello Navilife” and it greets you."
            on -> "On: with the phone on its stand (the bedside clock, charging), say “Hello Navilife” and it greets you."
            else -> "Off: tap the orb to say hello."
        },
        lit = on && available,
        steps = listOf(
            if (mac) "It listens only while Navilife is in front, never in the background, and stops while you talk to it."
            else "It listens only on the stand while charging, never in your pocket, and not in quiet hours.",
            "Your words are recognised on this ${if (mac) "Mac" else "phone"} only: never sent anywhere, never kept.",
            "While it listens the orb shows “$LISTENING_LINE”.",
            if (mac) "Allow the microphone once by clicking the orb." else "Allow the microphone once by tapping the orb.",
        ),
        action = if (!available) null else if (on) TURN_OFF else TURN_ON,
    )
}

/**
 * The Mac's welcome back (slice 3): when the Mac wakes or unlocks after [AWAY_MIN] or more away, Navilife comes forward
 * and greets him ("Welcome back, Meka. …"), then listens, once per return (a greeting in the last two hours counts).
 * Never in quiet hours or protected time; on by default, kept on the Mac, switched in Ask → More → Talk; Esc or ⌥Space
 * skips it. (Non-AI, pure.)
 */
object WelcomeBackRules {
    const val LABEL = "Welcome back"
    const val DEFAULT_ON = true
    const val OPENING = "Welcome back"
    const val AWAY_MIN = 120
    const val TURN_ON = "Turn on"
    const val TURN_OFF = "Turn off"

    /**
     * Whether this return greets: on, away [awayMin] (from the lock or sleep to now) at least [AWAY_MIN], no greeting in
     * the last two hours ([minutesSinceHello], negative for none: [HelloRules.hotkeyGreets]), not [quiet] hours, not
     * [protectedNow] (his evenings and weekends stay his), and the microphone already allowed ([micAllowed]).
     */
    fun greets(on: Boolean, awayMin: Int, minutesSinceHello: Int, quiet: Boolean, protectedNow: Boolean, micAllowed: Boolean): Boolean =
        on && awayMin >= AWAY_MIN && HelloRules.hotkeyGreets(minutesSinceHello) && !quiet && !protectedNow && micAllowed

    fun section(on: Boolean): TalkSetupSection = TalkSetupSection(
        LABEL,
        if (on) "On: back at the Mac after two hours or more, Navilife says hello and what matters now."
        else "Off: Navilife waits for you to say hello.",
        lit = on,
        steps = listOf(
            "Once per return, never in quiet hours or protected time.",
            "Press Esc or ⌥Space to skip it.",
        ),
        action = if (on) TURN_OFF else TURN_ON,
    )

    /**
     * The Mac's whole Talk sheet with slice 3's two sections after When I open Navilife: Say “Hello Navilife” and
     * Welcome back.
     */
    fun macSetup(base: TalkSetupView, wake: Boolean, wakeAvailable: Boolean, welcome: Boolean): TalkSetupView {
        val at = base.sections.indexOfFirst { it.label == TalkOnOpenRules.LABEL }.let { if (it < 0) base.sections.size - 1 else it + 1 }
        val s = base.sections.toMutableList()
        s.addAll(at.coerceIn(0, s.size), listOf(HelloWakeRules.section(wake, mac = true, available = wakeAvailable), section(welcome)))
        return base.copy(sections = s)
    }
}
