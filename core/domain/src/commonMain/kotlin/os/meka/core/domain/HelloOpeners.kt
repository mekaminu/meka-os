package os.meka.core.domain

import kotlin.random.Random

/*
 * The orb says hello like a person, differently each time (Fix first, Meka 2026-10-20 18:13 and 18:29). Non-AI and pure.
 *
 * A tap on the orb opens with one of 70+ hand-written openers chosen on the device (no AI wait, instant): by part of
 * the day, Monday morning, Friday, the weekend, a second tap ("Back again, Alex?"), the Mac's welcome back, and the
 * first hello of the day, where Ada introduces herself ("Morning, Alex. It's Ada."). Every opener says his name when
 * About you has one (even with "sir" on: sir is for the rest of the conversation) and ends with a question so Talk
 * listens straight after. None repeats within the last [RECENT] hellos. Openers never name anyone else or anything
 * private: the one touch from his day (if any) is [HelloRules]' own point, said between the opener and its question.
 */

/** One opener: [open] says hello ("Evening, {n}. Good day?"), [ask] hands back to him ("What can I do for you?"). */
data class HelloOpener(val id: String, val open: String, val ask: String) {
    /** [open] with his first name, or without it ("Hey {n}, how's…" → "Hey, how's…") when About you has none. */
    fun openFor(name: String?): String = HelloOpeners.fill(open, name)
}

/** When the hello happens: [isoDayOfWeek] 1 = Monday; [first] the day's first hello here; [again] a quick second tap. */
data class HelloMoment(
    val minuteOfDay: Int,
    val isoDayOfWeek: Int,
    val first: Boolean = false,
    val again: Boolean = false,
    val welcome: Boolean = false,
)

/** What the device keeps of its hellos (its own store, [HelloOpeners.KEY]): the last openers' ids, newest first, and when. */
data class HelloOpenerMemory(val recent: List<String> = emptyList(), val lastAtMs: Long? = null)

enum class HelloPart { MORNING, AFTERNOON, EVENING, LATE }

object HelloOpeners {
    /** The device's own store key for [HelloOpenerMemory]. */
    const val KEY = "hello.openers"
    /** No opener comes back within this many hellos. */
    const val RECENT = 10
    /** A tap this soon after the last hello is "again": "Back again, Alex?". */
    const val AGAIN_WITHIN_MS = 10 * 60_000L

    private const val N = "{n}"

    private fun pool(prefix: String, vararg pairs: Pair<String, String>): List<HelloOpener> =
        pairs.mapIndexed { i, (open, ask) -> HelloOpener("$prefix${i + 1}", open, ask) }

    val MORNING = pool(
        "m",
        "Morning, $N." to "What's first?",
        "Good morning, $N. Sleep all right?" to "What can I do for you?",
        "Morning, $N. How are you doing?" to "Anything I can help with?",
        "Hey $N, good morning." to "What's on your mind?",
        "Good morning, $N." to "How can I help?",
        "Morning, $N. Coffee first, or straight in?" to "What do you need?",
        "Hello $N, bright and early." to "What can I do for you?",
        "Morning, $N. Ready for the day?" to "Anything you'd like?",
        "Hi $N, how's the morning going?" to "Anything I can help with?",
        "Good morning, $N. Nice to hear from you." to "What's up?",
        "Morning, $N. Let's get you sorted." to "What do you need?",
        "Hey $N. Morning." to "How can I help?",
    )

    val AFTERNOON = pool(
        "a",
        "Afternoon, $N." to "What can I do for you?",
        "Hi $N, how's the day going?" to "Anything I can help with?",
        "Good afternoon, $N. Surviving so far?" to "What do you need?",
        "Hey $N, how's your afternoon?" to "What's up?",
        "Afternoon, $N. Busy one?" to "How can I help?",
        "Hello $N." to "What's on your mind?",
        "Hi $N. Halfway there." to "Anything you'd like?",
        "Good afternoon, $N." to "How can I help?",
        "Hey $N, good to hear from you." to "What do you need?",
        "Afternoon, $N. How are you doing?" to "Anything I can help with?",
        "Afternoon, $N. Keeping going?" to "What's on your mind?",
    )

    val EVENING = pool(
        "e",
        "Evening, $N. Good day?" to "What can I do for you?",
        "Hey $N, how's the evening treating you?" to "Anything I can help with?",
        "Good evening, $N." to "How can I help?",
        "Evening, $N. Feet up yet?" to "What do you need?",
        "Hello $N. How was today?" to "Anything you'd like?",
        "Hey $N. Winding down?" to "What's on your mind?",
        "Evening, $N." to "What can I do for you?",
        "Good evening, $N. Nice to hear from you." to "What's up?",
        "Hi $N. Long day?" to "How can I help?",
        "Evening, $N. All good?" to "Anything I can help with?",
        "Hi $N, how's your evening going?" to "What do you need?",
    )

    val LATE = pool(
        "l",
        "Still up, $N?" to "What can I do for you?",
        "Hey $N, it's late." to "Anything I can help with?",
        "Evening, $N. Burning the midnight oil?" to "What do you need?",
        "Hi $N. Can't sleep?" to "What's on your mind?",
        "Hello $N, night owl." to "How can I help?",
        "Late one, $N." to "What can I do for you?",
        "Hey $N. Quiet out there." to "Anything you'd like?",
        "Hi $N, still going?" to "How can I help?",
        "Evening, $N. Nearly bedtime." to "Anything before you turn in?",
        "Hello $N." to "What do you need?",
        "Hey $N. All right?" to "What can I do for you?",
        "Late night, $N?" to "Anything I can help with?",
    )

    /** Any part of the day but late at night. */
    val ANY = pool(
        "y",
        "There he is, $N." to "What can I do for you?",
        "Hi $N, how are you doing?" to "Anything I can help with?",
        "Hey $N." to "What's up?",
        "Hello $N, good to hear from you." to "How can I help?",
    )

    val MONDAY = pool(
        "mo",
        "Morning, $N. Monday again." to "What's first?",
        "Morning, $N. New week, clean slate." to "What can I do for you?",
        "Hey $N, happy Monday. Well, Monday." to "How can I help?",
    )

    val FRIDAY = pool(
        "f",
        "Friday, $N. Nearly there." to "What do you need?",
        "Hey $N, it's Friday." to "Anything I can help with?",
        "Happy Friday, $N." to "What can I do for you?",
    )

    val WEEKEND = pool(
        "w",
        "Hey $N, enjoying the weekend?" to "Anything I can help with?",
        "Hi $N. Weekend mode?" to "What can I do for you?",
        "Hello $N. Nice and easy today?" to "How can I help?",
    )

    val AGAIN = pool(
        "g",
        "Back again, $N?" to "What's up?",
        "Hi again, $N." to "What else can I do?",
        "Hello again, $N." to "What else?",
        "Back so soon, $N?" to "What do you need?",
        "Yes, $N?" to "What can I do for you?",
        "Still here, $N." to "What else can I help with?",
    )

    /** The Mac after a while away ([WelcomeBackRules]). */
    val WELCOME = pool(
        "b",
        "Welcome back, $N." to "Anything you'd like?",
        "Welcome back, $N. Good break?" to "What can I do for you?",
        "Hey $N, welcome back." to "Where were we?",
        "There you are, $N." to "Anything I can help with?",
        "Good to have you back, $N." to "How can I help?",
    )

    /** The first hello of the day: Ada says who she is (and then not again that day, [AdaRules]). */
    val FIRST_MORNING = pool(
        "im",
        "Morning, $N. It's Ada." to "What can I do for you?",
        "Good morning, $N. Ada here." to "How are you doing?",
        "Hi $N, it's Ada. Morning." to "What's first?",
    )
    val FIRST_AFTERNOON = pool(
        "ia",
        "Afternoon, $N. It's Ada." to "How's the day going?",
        "Hi $N, Ada here." to "Anything I can help with?",
        "Good afternoon, $N. It's Ada." to "What can I do for you?",
    )
    val FIRST_EVENING = pool(
        "ie",
        "Evening, $N. Ada here." to "How are you doing?",
        "Hi $N, it's Ada." to "Anything I can help with?",
        "Good evening, $N. It's Ada." to "How was today?",
    )
    val FIRST_LATE = pool(
        "il",
        "Hi $N, it's Ada. Still up?" to "What can I do for you?",
        "Evening, $N. Ada here." to "Anything before you turn in?",
        "Hey $N, it's Ada. Late one?" to "How can I help?",
    )

    /** Every opener, for tests and checks. */
    val ALL: List<HelloOpener> =
        MORNING + AFTERNOON + EVENING + LATE + ANY + MONDAY + FRIDAY + WEEKEND + AGAIN + WELCOME +
            FIRST_MORNING + FIRST_AFTERNOON + FIRST_EVENING + FIRST_LATE

    /** Morning 05:00–11:59, afternoon 12:00–16:59, evening 17:00–22:59, late 23:00–04:59. */
    fun part(minuteOfDay: Int): HelloPart = when (minuteOfDay.mod(1440) / 60) {
        in 5..11 -> HelloPart.MORNING
        in 12..16 -> HelloPart.AFTERNOON
        in 17..22 -> HelloPart.EVENING
        else -> HelloPart.LATE
    }

    /** The openers that fit [m], in no particular order. */
    fun candidates(m: HelloMoment): List<HelloOpener> {
        val part = part(m.minuteOfDay)
        if (m.first) return when (part) {
            HelloPart.MORNING -> FIRST_MORNING
            HelloPart.AFTERNOON -> FIRST_AFTERNOON
            HelloPart.EVENING -> FIRST_EVENING
            HelloPart.LATE -> FIRST_LATE
        }
        if (m.again) return AGAIN
        if (m.welcome) return WELCOME
        val base = when (part) {
            HelloPart.MORNING -> MORNING
            HelloPart.AFTERNOON -> AFTERNOON
            HelloPart.EVENING -> EVENING
            HelloPart.LATE -> LATE
        }
        if (part == HelloPart.LATE) return base
        val day = m.isoDayOfWeek
        val special = when {
            day == 1 && part == HelloPart.MORNING -> MONDAY
            day == 5 && part != HelloPart.MORNING -> FRIDAY
            day >= 6 -> WEEKEND
            else -> emptyList()
        }
        return special + base + ANY
    }

    /**
     * The opener for [m]: the fitting ones in an order shuffled by [day] (so each day runs differently, and the same
     * on a re-check, which lets the next one be made ahead in Ada's voice), the first not among [recent] (the last
     * [RECENT] said). If every one was said lately, the one said longest ago.
     */
    fun pick(m: HelloMoment, recent: List<String>, day: Long): HelloOpener {
        val pool = candidates(m).shuffled(Random(day * 31 + m.part().ordinal * 7 + kindSeed(m)))
        val last = recent.take(RECENT)
        pool.firstOrNull { it.id !in last }?.let { return it }
        return pool.maxBy { last.indexOf(it.id) }
    }

    private fun HelloMoment.part() = part(minuteOfDay)
    private fun kindSeed(m: HelloMoment) = when {
        m.first -> 1
        m.again -> 2
        m.welcome -> 3
        else -> 0
    }

    /** The moment of a hello at [nowMs] ([day], [minuteOfDay] his local ones) after [memory]; [introduced] Ada already did today. */
    fun moment(
        minuteOfDay: Int, day: Long, nowMs: Long, memory: HelloOpenerMemory, introduced: Boolean, welcome: Boolean,
    ): HelloMoment = HelloMoment(
        minuteOfDay = minuteOfDay,
        isoDayOfWeek = CivilDate.isoDayOfWeek(day),
        first = !introduced,
        again = memory.lastAtMs?.let { nowMs - it in 0 until AGAIN_WITHIN_MS } ?: false,
        welcome = welcome,
    )

    /** [memory] after [opener] was said at [nowMs]. */
    fun said(memory: HelloOpenerMemory, opener: HelloOpener, nowMs: Long): HelloOpenerMemory =
        HelloOpenerMemory((listOf(opener.id) + memory.recent.filter { it != opener.id }).take(RECENT), nowMs)

    /** "{n}" filled with his first name, or taken out with its comma or space when there is none. */
    fun fill(text: String, name: String?): String {
        val n = HelloRules.firstName(name)
        if (n != null) return text.replace(N, n)
        return text.replace(", $N", "").replace(" $N", "").replace(N, "").trim()
    }

    /** [HelloOpenerMemory] as kept: "lastAtMs;id,id,…" (the time may be empty). */
    fun encode(memory: HelloOpenerMemory): String = "${memory.lastAtMs ?: ""};${memory.recent.joinToString(",")}"

    /** [encode]'s text read back; an empty memory when missing or unreadable. */
    fun decode(stored: String?): HelloOpenerMemory {
        val s = stored?.trim().orEmpty()
        if (s.isEmpty() || ';' !in s) return HelloOpenerMemory()
        val at = s.substringBefore(';').toLongOrNull()
        val ids = s.substringAfter(';').split(',').map { it.trim() }.filter { it.isNotEmpty() && it.length <= 8 }.take(RECENT)
        return HelloOpenerMemory(ids, at)
    }

    /**
     * The pieces of the next hellos worth having in Ada's voice before a tap ([SpeechRules.pieces] of opener + question,
     * the clear-day form): the one a tap now would say, the one an hour on, and a quick second tap's.
     */
    fun warm(minuteOfDay: Int, day: Long, nowMs: Long, memory: HelloOpenerMemory, introduced: Boolean, welcome: Boolean, name: String?): List<String> {
        val now = moment(minuteOfDay, day, nowMs, memory, introduced, welcome)
        val laterMin = minuteOfDay + HelloRules.WARM_AHEAD_MIN
        val later = moment(laterMin.mod(1440), day + laterMin / 1440, nowMs, memory, introduced = true, welcome = welcome)
            .copy(again = false)
        val nextPick = pick(now, memory.recent, day)
        val after = said(memory, nextPick, nowMs)
        val picks = listOf(
            nextPick,
            pick(later, after.recent, day + laterMin / 1440),
            pick(now.copy(first = false, again = true), after.recent, day),
        )
        val pieces = picks.map { o -> SpeechRules.pieces("${o.openFor(name)} ${o.ask}") }
        return (pieces.mapNotNull { it.firstOrNull() } + pieces.flatMap { it.drop(1) }).distinct()
    }
}
