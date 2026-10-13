package os.meka.core.domain

/*
 * "Plan my day" said to Talk (build plan V1, "A morning brief worth listening to", slice 5; non-AI, pure).
 *
 * The brief ends "Say “plan my day” or “more news”", and Talk keeps listening after it. "More news" is a read-out
 * ([ReadOutRules]); "plan my day" opens the planner (Today → Plan my day) on the device that heard it, exactly as its
 * button does: nothing is planned or changed until Meka taps Apply there. Recognised on the device, so nothing goes to
 * the AI for it and it doesn't count towards the question cap. MEKA says one short line, the conversation ends, then
 * the planner opens (ending first, so leaving Ask for Today never cuts MEKA off).
 */

/**
 * A place Talk can open when asked ([TalkOpenRules.request]). [BEDSIDE] (the bedside clock, slice 1 of "Bedside clock
 * doesn't appear on the Fold 8") only on a device that has one: the Fold asks with [TalkOpenRules.FOLD]; the Mac keeps
 * [TalkOpenRules.DEFAULT], so "bedside clock" there is a question like any other.
 */
enum class TalkPlace { PLAN, BEDSIDE, INBOX }

object TalkOpenRules {
    /** What MEKA says before the planner opens. */
    const val OPENING_PLAN = "Here's a plan for today. Apply it if it looks right."

    /** What the conversation remembers MEKA did. */
    const val OPENED_PLAN = "Opened Plan my day."

    /**
     * Inbox autopilot, slice 7b: "clear my inbox" starts the ten-minute inbox in Needs you, as its Start does (nothing
     * is archived or sent but by his own taps there).
     */
    const val OPENING_INBOX = "Here's your inbox, one email at a time."
    const val OPENED_INBOX = "Started the ten-minute inbox."

    /** What a device without a bedside clock (the Mac) can open. */
    val DEFAULT: Set<TalkPlace> = setOf(TalkPlace.PLAN, TalkPlace.INBOX)

    /** The Fold: the planner, the bedside clock and the ten-minute inbox. */
    val FOLD: Set<TalkPlace> = setOf(TalkPlace.PLAN, TalkPlace.BEDSIDE, TalkPlace.INBOX)

    /**
     * "Clear my inbox", "go through my emails", "sort out the inbox", "do my email", "inbox zero", "ten-minute inbox",
     * "start my ten minute inbox" (what's left once [DROP] is gone).
     */
    private val INBOX: Set<List<String>> = buildSet {
        val things = listOf("inbox", "email", "emails", "mail", "mails")
        for (verb in listOf("clear", "empty", "sort", "do", "through", "tidy")) for (det in listOf(null, "my", "the")) for (t in things) {
            add(listOfNotNull(verb, det, t))
        }
        for (verb in listOf(null, "start", "open", "do")) for (det in listOf(null, "my", "the")) for (n in listOf("ten", "10")) {
            add(listOfNotNull(verb, det, n, "minute", "inbox"))
        }
        add(listOf("inbox", "zero")); add(listOf("start", "inbox", "zero")); add(listOf("start", "my", "inbox"))
    }

    /** "Bedside clock", "open the bedside clock", "show me the bedside clock", "night clock", "bedside mode". */
    private val BEDSIDE = setOf(
        listOf("bedside", "clock"), listOf("the", "bedside", "clock"), listOf("open", "bedside", "clock"),
        listOf("open", "the", "bedside", "clock"), listOf("show", "bedside", "clock"), listOf("show", "the", "bedside", "clock"),
        listOf("bedside", "mode"), listOf("bedside"), listOf("night", "clock"), listOf("the", "night", "clock"),
        listOf("open", "the", "night", "clock"), listOf("show", "the", "night", "clock"), listOf("nightstand", "clock"),
    )

    /** Words around the request that change nothing ("ok MEKA, can you plan my day for me please"). */
    private val DROP = setOf(
        "please", "meka", "navilife", "hey", "ok", "okay", "yes", "yeah", "yep", "sure", "go", "ahead", "and", "then", "so", "now", "just",
        "um", "umm", "uh", "er", "erm", "can", "could", "would", "will", "you", "me", "for", "let's", "lets", "let", "us",
        "i", "want", "to", "like", "help", "need", "out",
    )

    /** What's left once [DROP] is gone: the planner, asked for plainly. */
    private val PLAN = setOf(
        listOf("plan", "my", "day"), listOf("plan", "the", "day"), listOf("plan", "today"), listOf("plan", "my", "today"),
        listOf("plan", "day"), listOf("plan", "my", "day", "today"), listOf("plan", "the", "day", "today"),
        listOf("make", "a", "plan"), listOf("make", "a", "plan", "today"), listOf("make", "a", "plan", "the", "day"),
        listOf("make", "my", "plan"), listOf("make", "a", "day", "plan"), listOf("day", "plan"),
        listOf("open", "the", "planner"), listOf("open", "planner"), listOf("open", "plan", "my", "day"),
        listOf("show", "my", "plan"), listOf("show", "the", "plan"), listOf("my", "plan", "today"),
        listOf("what's", "my", "plan", "today"), listOf("whats", "my", "plan", "today"),
        listOf("what's", "the", "plan", "today"), listOf("whats", "the", "plan", "today"),
    )

    /**
     * The place Meka asked Talk to open, or null: "plan my day", "plan my day please", "can you plan the day for me",
     * "let's plan today", "make a plan for the day", "what's my plan for today". Anything with more in it ("plan my day
     * around the dentist", "plan a trip") goes to MEKA as a question.
     */
    fun request(utterance: String): TalkPlace? = request(utterance, DEFAULT)

    /** As [request], opening only what [places] holds ("bedside clock" on the Fold). */
    fun request(utterance: String, places: Set<TalkPlace>): TalkPlace? {
        val ws = ReadOutRules.words(utterance).filter { it !in DROP }
        return when {
            ws in PLAN && TalkPlace.PLAN in places -> TalkPlace.PLAN
            ws in BEDSIDE && TalkPlace.BEDSIDE in places -> TalkPlace.BEDSIDE
            ws in INBOX && TalkPlace.INBOX in places -> TalkPlace.INBOX
            else -> null
        }
    }

    /** What MEKA says before opening [place]. */
    fun opening(place: TalkPlace): String = when (place) {
        TalkPlace.PLAN -> OPENING_PLAN
        TalkPlace.BEDSIDE -> BedsideEntryRules.OPENING
        TalkPlace.INBOX -> OPENING_INBOX
    }

    /** What the conversation remembers about opening [place]. */
    fun remembered(place: TalkPlace): String = when (place) {
        TalkPlace.PLAN -> OPENED_PLAN
        TalkPlace.BEDSIDE -> BedsideEntryRules.OPENED
        TalkPlace.INBOX -> OPENED_INBOX
    }
}

/*
 * After the brief pane's Listen (build plan V1, "A morning brief worth listening to", slice 7; non-AI, pure).
 *
 * The brief ends "Say “plan my day” or “more news”". Read from Talk ("read my brief"), Talk already listens after it;
 * read from the brief's own Listen, the device now hands over to Talk the same way: Ask comes forward with the orb
 * listening ([TalkFlow.afterBrief], which remembers the brief was read so "more news" and "plan my day" work as in
 * Talk) for up to [WINDOW_MS]. Nothing said by then → listening stops and the screen the brief was opened from comes
 * back. Only a read that ran to its end hands over (never Stop, Got it, closing the brief or a length change), only
 * when the brief ends asking ([BriefScriptRules.CLOSE]), and only when the microphone (and the Mac's speech
 * recognition) are already allowed: the brief never asks for them.
 */
object BriefFollowOnRules {
    /** How long MEKA keeps listening after the brief before handing back. */
    const val WINDOW_MS = 8_000L

    /** What the conversation remembers as asked before the brief was read (the brief was read by its own Listen). */
    const val ASKED = "Read my brief"

    /** The brief [script] just read ends asking "plan my day" or "more news". */
    fun asks(script: String): Boolean = script.trimEnd().endsWith(BriefScriptRules.CLOSE)

    /**
     * Whether the read that just ended hands over to Talk: [finished] (it ran to its end on its own), [micAllowed] (the
     * microphone, and on the Mac speech recognition, already allowed; nothing asks), and the [script] [asks].
     */
    fun handOff(script: String, finished: Boolean, micAllowed: Boolean): Boolean =
        finished && micAllowed && asks(script)

    /** The window is over with nothing said ([speechBegan] false): stop listening and hand back. */
    fun windowLapsed(startedAtMs: Long, nowMs: Long, speechBegan: Boolean): Boolean =
        !speechBegan && nowMs - startedAtMs >= WINDOW_MS

    /**
     * The recogniser gave up on silence ([speechBegan] false) inside the window: listen again rather than end, so
     * the window is the whole [WINDOW_MS] (Android's on-device recogniser times out sooner on its own).
     */
    fun listenAgain(startedAtMs: Long, nowMs: Long, speechBegan: Boolean): Boolean =
        !speechBegan && nowMs - startedAtMs < WINDOW_MS
}
