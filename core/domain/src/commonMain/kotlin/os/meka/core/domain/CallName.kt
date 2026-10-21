package os.meka.core.domain

/*
 * One name everywhere (Fix first, Meka 2026-10-21 01:13: "It still doesn't say my name"). Non-AI and pure.
 *
 * What Navilife calls him comes from one place, About you → Your name, read the same way by Today's greeting, the
 * orb's hello, Talk's holding lines, the brief and the companion: its first word ([called]). When it is empty there is
 * no name in code to fall back to (rule 13): the greetings simply leave it out ("Good evening"), and the orb's next
 * hello asks once, out loud, "Before anything else, what should I call you?". What he answers comes back as a card,
 * "Call you “Alex” · kept in About you" with Save: his tap (or his spoken yes) writes it to Your name. "Call me Alex" or
 * "my name is Alex" said any time later does the same.
 */
object CallNameRules {
    const val ASK = "Before anything else, what should I call you?"
    /** How long after the ask a bare reply ("Alex") is read as the name. */
    const val PENDING_MS = 2 * 60_000L
    /** Said when he'd rather not say: nothing is asked again (About you still takes it). */
    const val DECLINED = "No problem. You can add it any time in About you. What can I do for you?"
    const val BUTTON = "Save"
    /** The longest name read from speech, in words ("Mary Ann"), within About you's own limit. */
    const val MAX_WORDS = 3

    /** His name as everything says it: Your name's first word; null when it is empty. */
    fun called(shownName: String?): String? = HelloRules.firstName(shownName)

    /**
     * Today's greeting, the same on the Fold and the Mac: "Good morning, Alex" (05:00–11:59), "Good afternoon" (12–17),
     * "Good evening" (18–23), "Good night" (00:00–04:59; Meka 2026-10-17 02:03); without a name, just the greeting.
     */
    fun todayGreeting(hour: Int, shownName: String?): String {
        val g = when (hour.mod(24)) {
            in 5..11 -> "Good morning"
            in 12..17 -> "Good afternoon"
            in 18..23 -> "Good evening"
            else -> "Good night"
        }
        return called(shownName)?.let { "$g, $it" } ?: g
    }

    /** "Good morning, Alex." for a spoken opening, "Good morning." without a name. */
    fun opening(greeting: String, name: String?): String = name?.trim()?.takeIf { it.isNotEmpty() }?.let { "$greeting, $it." } ?: "$greeting."

    /** The hello asks (once, on any device) when Your name is empty and it hasn't asked before. */
    fun asks(shownName: String?, askedBefore: Boolean): Boolean = called(shownName) == null && !askedBefore

    /** The hello that asks: "Good evening." then [ASK]; nothing else from his day until he has answered. */
    fun askGreeting(minuteOfDay: Int, welcome: Boolean = false): HelloGreeting = HelloGreeting(
        opening = (if (welcome) WelcomeBackRules.OPENING else HelloRules.salutation(minuteOfDay)) + ".",
        points = emptyList(),
        closing = ASK,
    )

    /** Whether the ask is still open at [nowMs] (asked at [askedAtMs]). */
    fun pending(askedAtMs: Long?, nowMs: Long): Boolean = askedAtMs != null && nowMs - askedAtMs in 0..PENDING_MS

    private val NOT_NAMES = setOf(
        "no", "nope", "nah", "nothing", "none", "skip", "pass", "later", "dunno", "whatever", "hello", "hi", "hey", "yes",
        "yeah", "yep", "ok", "okay", "thanks", "thank", "sorry", "what", "why", "who", "back", "a", "an", "the", "me",
        "when", "at", "on", "in", "tomorrow", "tonight", "today", "now", "please", "not", "fine", "good", "great",
        "tired", "busy", "here", "home", "sir", "it", "that", "this", "navilife", "ada", "you", "him", "her", "them",
        "us", "my", "your", "is", "am", "are", "and", "or", "to", "of", "for", "with", "up", "what's", "who's",
        "that's", "there's", "where's", "how", "how's", "doing", "well", "alright", "cool", "sure", "busy", "off",
    )
    private val DECLINES = listOf(
        "no", "nope", "nah", "not now", "skip", "skip it", "never mind", "nevermind", "i'd rather not", "rather not",
        "don't", "dont", "leave it", "pass", "no thanks", "no thank you", "later",
    )
    /** What comes before the name in an answer; the explicit ones ([EXPLICIT]) work any time, not just after the ask. */
    private val EXPLICIT = listOf("you can call me ", "just call me ", "please call me ", "call me ", "my name is ", "my name's ")
    private val LOOSE = listOf("it's ", "it is ", "i'm ", "i am ", "im ", "name's ", "just ", "it's just ")
    private val TAILS = listOf(" please", " thanks", " thank you", " mate", " then")

    /** He'd rather not say ("no thanks", "not now", "skip"). */
    fun declined(words: String): Boolean {
        val w = norm(words)
        return w in DECLINES || DECLINES.any { w.startsWith("$it ") && w.length <= it.length + 12 }
    }

    /**
     * The name in [words], capitalised as names are ("alex" → "Alex", "mary-ann" → "Mary-Ann"); null when none.
     * [pending] (just after the ask): a bare one- to three-word reply counts, as do "it's Alex", "I'm Alex", "just
     * Alex". Any time: only "call me Alex" and "my name is Alex", the whole of what was said, up to [MAX_WORDS] words.
     */
    fun heard(words: String, pending: Boolean): String? {
        var w = norm(words)
        if (w.isEmpty()) return null
        TAILS.forEach { if (w.endsWith(it)) w = w.dropLast(it.length).trim() }
        val prefix = EXPLICIT.firstOrNull { w.startsWith(it) } ?: if (pending) LOOSE.firstOrNull { w.startsWith(it) } else null
        val rest = when {
            prefix != null -> w.removePrefix(prefix).trim()
            pending -> w
            else -> return null
        }
        val parts = rest.split(' ').filter { it.isNotBlank() }
        if (parts.isEmpty() || parts.size > MAX_WORDS) return null
        if (parts.any { it in NOT_NAMES || !it.all { c -> c.isLetter() || c == '-' || c == '\'' } || !it.first().isLetter() }) return null
        val name = parts.joinToString(" ") { cap(it) }
        return name.takeIf { it.length <= AboutYouRules.MAX_NAME }
    }

    /** The card for [name]: "Call you “Alex” · kept in About you" with [BUTTON]. */
    fun card(name: String): AskCard = AskCard(AskProposal.SetName(name), cardLine(name), BUTTON)

    fun cardLine(name: String): String = "Call you “$name” · kept in About you"

    /** What Navilife says with the card: "Lovely to meet you, Alex. Shall I call you Alex from now on?" */
    fun answer(name: String): String = "Lovely to meet you, $name. Shall I call you $name from now on?"

    /** The undo bar's line once saved. */
    fun doneLine(name: String): String = "I'll call you $name · saved in About you"

    /** Talk's phrase: "call you Alex from now on" / "saved your name as Alex". */
    fun phrase(name: String, past: Boolean): String = if (past) "saved your name as $name" else "call you $name from now on"

    private fun norm(s: String): String = s.lowercase().replace('’', '\'').trim().trimEnd('.', '!', '?', ',').replace(Regex("\\s+"), " ")

    private fun cap(word: String): String = word.split('-').joinToString("-") { part ->
        part.split('\'').joinToString("'") { p -> p.replaceFirstChar { it.uppercase() } }
    }
}
