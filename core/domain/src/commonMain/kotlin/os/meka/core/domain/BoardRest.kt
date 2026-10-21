package os.meka.core.domain

/**
 * "How are you feeling?", part 2 (Week board, item (11); Meka 2026-10-20 19:56): saying it to Ada. "I'm shattered",
 * "I'm knackered", "I'm exhausted" are [Feeling.TIRED]; "I'm stretched", "I'm swamped", "I've got too much on" are
 * [Feeling.STRETCHED]. Only the whole remark counts ("I'm tired of waiting" is not one): it is kept as today's feeling on
 * this device (the same as tapping it on Shut down the day) and answered with the offer, with Open Shut down the day
 * under it, where the taps are. Nothing moves from the words alone. Plain, non-AI rules.
 */
object FeelingAskRules {
    const val BUTTON = "Open Shut down the day"
    val link: HelpLink get() = HelpLink("shut-down", HelpPlace.SHUTDOWN, BUTTON)

    private fun words(q: String): String = q.lowercase()
        .replace('’', '\'')
        .replace(Regex("[^a-z0-9' ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private val LEAD = Regex("^(hey ada |hello ada |ada |hey navilife |navilife |honestly |to be honest |ugh |god |oh |man |well )+")
    private val TAIL = Regex("( today| tonight| right now| now| at the moment| this week| lately| ada| mate)+$")

    private const val I_AM = "(i'm|i am|im|feeling|i feel|i'm feeling|i am feeling|i'm so|i feel so)"
    private const val SO = "( so| really| absolutely| completely| totally| utterly| proper| well| a bit| pretty| very)*"

    private val TIRED_WORDS = "(shattered|knackered|exhausted|tired|wiped out|worn out|done in|drained|whacked|cream crackered|beat|zonked|dead on my feet|running on empty|burnt out|burned out|spent)"
    private val STRETCHED_WORDS = "(stretched|stretched thin|overwhelmed|swamped|stressed|stressed out|snowed under|under pressure|overloaded|maxed out|frazzled|drowning)"

    private val TIRED = listOf(
        Regex("^$I_AM$SO $TIRED_WORDS$"),
        Regex("^(it's been|its been|what) a (long|tiring|exhausting|knackering) day$"),
        Regex("^(i need|i could do with) (a rest|an early night|some rest)$"),
    )

    private val STRETCHED = listOf(
        Regex("^$I_AM$SO $STRETCHED_WORDS$"),
        Regex("^(i've|i have|ive|i) got (way )?too much (on|to do)( on)?$"),
        Regex("^(there's|there is|it's|it is) (way )?too much (on|to do|going on)$"),
        Regex("^it's all (getting )?too much$"),
    )

    fun request(q: String): Feeling? {
        val t = words(q).replace(LEAD, "").replace(TAIL, "").trim()
        if (t.isEmpty() || t.length > 60) return null
        if (STRETCHED.any { it.matches(t) }) return Feeling.STRETCHED
        if (TIRED.any { it.matches(t) }) return Feeling.TIRED
        return null
    }

    /**
     * What Ada says back: a kind word, the offer's own line, and where the taps are. "Sorry to hear it. Let's go easy
     * tomorrow: move “Gym” to Fri? Lighten tomorrow is in Shut down the day."
     */
    fun answer(offer: FeelingOffer): String {
        val lead = if (offer.feeling == Feeling.STRETCHED) "That's a lot to carry." else "Sorry to hear it."
        val taps = listOfNotNull(
            offer.lightenLabel,
            offer.dropLabel?.let { "Someday" },
            offer.holdLabel?.let { "holding alerts" },
        )
        val where = when {
            taps.isEmpty() -> null
            else -> "${listLine(taps).replaceFirstChar { it.uppercaseChar() }} ${if (taps.size == 1) "is" else "are"} in Shut down the day, nothing moves till you tap."
        }
        return listOfNotNull(lead, offer.line, offer.holdLine, where).joinToString(" ")
    }

    private fun listLine(xs: List<String>): String = when (xs.size) {
        1 -> xs[0]
        else -> xs.dropLast(1).joinToString(", ") + " and " + xs.last()
    }
}

/**
 * Stretched holds the non-urgent alerts for a day (Week board, item (11) part 2). Offered with the Stretched answer as
 * "Hold alerts till tomorrow 21:30"; on his tap, for [HOLD_MS] mail and call alerts that can wait don't ring on this
 * device: the ten-minute inbox, work mail that waited, parcels, the call assistant's credit, replies he was waiting for
 * ([HELD_SOURCES]), and on the
 * Fold the after-call notes for strangers and automatic blocks. They stay in the app (Needs you, Activity → Calls); a
 * held notice that still applies when the hold ends posts then. Urgent calls and messages and Always notify people still
 * come through, and nothing else changes (event reminders, trains, approvals, bills). Resume ends it at once. Kept on
 * this device only (its own store, never synced), like how he felt.
 */
object AlertHoldRules {
    const val HOLD_MS = 24 * 60 * 60_000L
    /** The device's own store (never synced): the hold's end, in ms. */
    const val KEY = "feeling.hold"
    const val RESUME = "Resume"
    const val STILL = "urgent calls and Always notify still come through"

    val HELD_SOURCES: Set<NoticeSource> = setOf(
        NoticeSource.MAIL_MOMENT, NoticeSource.PROTECTED_MAIL, NoticeSource.PARCELS, NoticeSource.CALL_CREDIT,
        NoticeSource.REPLIED,
    )

    fun active(untilMs: Long?, nowMs: Long): Boolean = untilMs != null && nowMs < untilMs

    /** Whether [n] waits while a hold is on. */
    fun holds(n: Notice): Boolean = n.source in HELD_SOURCES

    fun untilFrom(nowMs: Long): Long = nowMs + HOLD_MS

    /** "tomorrow 21:30", or "Thu 21:30" further out, or "21:30" later today. */
    fun whenLine(untilMs: Long, nowMs: Long, cal: LocalCalendar): String {
        val at = LocalClock.formatMinute(cal.minuteOfDay(untilMs))
        val d = cal.epochDayOf(untilMs) - cal.epochDayOf(nowMs)
        return when (d) {
            0L -> at
            1L -> "tomorrow $at"
            else -> "${PressureRules.shortDay(cal.epochDayOf(untilMs), cal.epochDayOf(nowMs))} $at"
        }
    }

    /** The offer's button: "Hold alerts till tomorrow 21:30". */
    fun offerLabel(nowMs: Long, cal: LocalCalendar): String = "Hold alerts till ${whenLine(untilFrom(nowMs), nowMs, cal)}"

    /** The offer's line under Stretched: "I can hold mail and call alerts that can wait till tomorrow 21:30." */
    fun offerLine(nowMs: Long, cal: LocalCalendar): String =
        "I can hold mail and call alerts that can wait till ${whenLine(untilFrom(nowMs), nowMs, cal)}."

    /** While held: "Mail and call alerts held till tomorrow 21:30 · urgent calls and Always notify still come through". */
    fun heldLine(untilMs: Long, nowMs: Long, cal: LocalCalendar): String =
        "Mail and call alerts held till ${whenLine(untilMs, nowMs, cal)} · $STILL"

    const val RESUMED = "Alerts back on · held ones that still apply come now"

    fun decode(text: String?): Long? = text?.trim()?.toLongOrNull()?.takeIf { it > 0 }
    fun encode(untilMs: Long?): String = untilMs?.toString() ?: ""
}
