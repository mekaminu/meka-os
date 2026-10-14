package os.meka.core.domain

/*
 * Car and parking, slice 5 (build plan V1, "Car and parking"; non-AI, pure, on the device): the parking timer from a
 * shared confirmation.
 *
 * He pays in RingGo, JustPark, PayByPhone (or any app or email that confirms a parking session) and shares the
 * confirmation to Navilife, or pastes it into the Car pane's timer field: the words are read **on the device**, no AI
 * and nothing sent anywhere. What's read: when the paid time ends ("Expires 15:30", "Valid until 15:30 on 14/10/2026",
 * "Wed 14 Oct 13:30 – Wed 14 Oct 15:30", "Start 13:30 · Duration 2 hours"), the provider and the location code
 * ("RingGo 81234", kept as the timer's note) and, with two cars, which car from its registration in the text. The
 * share sheet offers "Parking timer until 15:30 · RingGo 81234"; nothing starts until he taps it. A confirmation whose
 * time has already gone says so ("That parking ended at 15:30") instead of starting anything.
 *
 * The registration is only compared with the cars' own (never kept from the text); the timer keeps the provider and
 * location code only, like a typed one ([ParkingTimerRules]).
 */

/** What a shared confirmation read: when it ends, from when, the note and the car it names (or null). */
data class ParkingShared(
    val endsAtMs: Long,
    /** When the session started, if the confirmation says and it's before now; else null. */
    val startedAtMs: Long?,
    /** "RingGo", "JustPark", or null. */
    val provider: String?,
    /** The location code ("81234"), or null. */
    val location: String?,
    /** The car whose registration the text holds, or null. */
    val carId: String?,
    /** The paid time has already ended. */
    val ended: Boolean,
) {
    /** The timer's note: "RingGo 81234", "RingGo", "Location 81234", or null. */
    val note: String? get() = ParkingTimerRules.cleanNote(
        listOfNotNull(provider, location?.let { if (provider == null) "Location $it" else it }).joinToString(" "),
    )
}

/** What the share sheet offers for a confirmation. Index access for Swift. */
data class ParkingShareView(
    /** "Parking timer until 15:30 · RingGo 81234", or "That parking ended at 15:30 · nothing to remind you of". */
    val line: String,
    val ended: Boolean,
    /** The car to start it on, or null when he has to pick ([choices]). */
    val carId: String?,
    /** carId to title, when there are two cars and the text names neither. */
    val choices: List<Pair<String, String>>,
    val shared: ParkingShared,
) {
    val choiceCount: Int get() = choices.size
    fun choiceIdAt(index: Int): String = choices[index].first
    fun choiceTitleAt(index: Int): String = choices[index].second
}

object ParkingConfirmationRules {
    const val ACTION = "Parking timer"
    const val WHICH_CAR = "Which car?"
    const val PASTE_HINT = "Or paste the parking app's confirmation: Navilife reads when it ends"

    /** Names that say "this is a parking confirmation" on their own (lower case → as shown). */
    private val PROVIDERS = listOf(
        "ringgo" to "RingGo", "justpark" to "JustPark", "paybyphone" to "PayByPhone", "pay by phone" to "PayByPhone",
        "parkmobile" to "Parkmobile", "mipermit" to "MiPermit", "apcoa" to "APCOA", "flowbird" to "Flowbird",
        "yourparkingspace" to "YourParkingSpace", "your parking space" to "YourParkingSpace", "ncp" to "NCP",
        "q-park" to "Q-Park", "saba" to "Saba", "easypark" to "EasyPark", "parkonomy" to "Parkonomy", "evopark" to "Evopark",
    )
    private val PARKING_WORDS = Regex("""\bpark(?:ing|ed)?\b""")
    private val SESSION_WORDS = Regex("""\b(?:session|booking|expir\w*|paid|valid|ticket|tariff|bay|location|duration|receipt|confirm\w*)\b""")

    private val END_ANCHOR = Regex(
        """\b(?:expir(?:es|y|ing)(?:\s+(?:time|at|on))?|session\s+end(?:s)?|parking\s+end(?:s)?|end(?:s|\s+time|ing)?|valid\s+(?:until|till|to)|until|till|finish(?:es)?|leave\s+by)\b"""
    )
    private val START_ANCHOR = Regex("""\b(?:start(?:s|ed)?(?:\s+time)?|session\s+start|from|parked\s+at|arrival|arrive)\b""")
    private val CLOCK = Regex("""(?<![\d:.£$])(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm)?(?![\d%])""")
    private val SLASH_DATE = Regex("""\b(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?\b""")
    private val ISO_DATE = Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""")
    private val WORD_DATE = Regex("""\b(\d{1,2})(?:st|nd|rd|th)?\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?(?:\s+(\d{4}))?\b""")
    private val RANGE_JOIN = Regex("""^\s*(?:-|–|—|to|until|till)\s*""")
    private val DURATION = Regex(
        """\b(?:duration|length\s+of\s+stay|stay|for)\b\W{0,3}(?:(\d+(?:\.\d+)?)\s*(?:hours?|hrs?|h)\b(?:\s*(?:and\s*)?(\d{1,2})\s*(?:minutes?|mins?|m)\b)?|(\d{1,3})\s*(?:minutes?|mins?)\b)"""
    )
    private val LOCATION = Regex("""\blocation(?:\s+(?:code|number|no\.?|id))?\s*[:#]?\s*(\d{3,7})\b""")
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private const val WINDOW = 48

    /** Whether [text] reads like a parking confirmation at all (a provider, or "parking" with a session word). */
    fun looksLikeOne(text: String?): Boolean {
        val s = flat(text).lowercase()
        if (s.isEmpty()) return false
        return providerOf(s) != null || (PARKING_WORDS.containsMatchIn(s) && SESSION_WORDS.containsMatchIn(s))
    }

    /**
     * Reads a shared confirmation ([text], with the share's [subject]). Null when it isn't one or no end reads; a
     * confirmation that has already ended comes back with [ParkingShared.ended]. [cars] lets a registration in the
     * text pick the car.
     */
    fun read(text: String?, subject: String?, nowMs: Long, cal: LocalCalendar, cars: CarsView = CarsView.EMPTY): ParkingShared? {
        val whole = flat(listOfNotNull(subject, text).joinToString(" · "))
        val s = whole.lowercase()
        if (!looksLikeOne(whole)) return null
        val today = cal.epochDayOf(nowMs)

        var end: Pair<Int, Long?>? = null // minute of day, epoch day (if a date was near)
        var start: Pair<Int, Long?>? = null

        // 1. "Expires 15:30 on 14/10/2026", "Valid until 6pm", "End time: 15:30".
        for (a in END_ANCHOR.findAll(s)) {
            val c = firstClock(s, a.range.last + 1, a.range.last + 1 + WINDOW) ?: continue
            end = c.first to dateNear(s, a.range.first, c.second + 28, today)
            break
        }
        // 2. A range: "13:30 – 15:30", "Wed 14 Oct 13:30 - Wed 14 Oct 15:30".
        if (end == null) {
            val clocks = clocks(s)
            for (i in 0 until clocks.size - 1) {
                val gap = s.substring(clocks[i].second, clocks[i + 1].third)
                val joined = RANGE_JOIN.find(gap) ?: continue
                val between = gap.substring(joined.range.last + 1)
                if (between.length > 20) continue
                start = clocks[i].first to dateNear(s, maxOf(0, clocks[i].third - 20), clocks[i].third, today)
                end = clocks[i + 1].first to dateNear(s, clocks[i].second, clocks[i + 1].third, today)
                break
            }
        }
        if (start == null) {
            for (a in START_ANCHOR.findAll(s)) {
                val c = firstClock(s, a.range.last + 1, a.range.last + 1 + WINDOW) ?: continue
                start = c.first to dateNear(s, a.range.first, c.second + 28, today)
                break
            }
        }
        // 3. "Start 13:30 · Duration 2 hours".
        if (end == null && start != null) {
            val d = DURATION.find(s)
            if (d != null) {
                val mins = when {
                    d.groupValues[1].isNotEmpty() -> (d.groupValues[1].toDouble() * 60).toInt() + (d.groupValues[2].toIntOrNull() ?: 0)
                    else -> d.groupValues[3].toIntOrNull() ?: 0
                }
                if (mins in 1..(24 * 60)) {
                    val startDay = start.second ?: today
                    val total = start.first + mins
                    end = total % (24 * 60) to (startDay + total / (24 * 60))
                }
            }
        }
        val e = end ?: return null

        val st = start
        val startDay = st?.let { it.second ?: today }
        val endDay = e.second ?: when {
            st == null -> today
            // An overnight session: starts in the evening, ends next morning.
            st.first > e.first -> (st.second ?: today) + 1
            else -> st.second ?: today
        }
        val endsAt = cal.toEpochMs(endDay, e.first)
        val startedAt = if (st != null && startDay != null) cal.toEpochMs(startDay, st.first).takeIf { it < endsAt && it <= nowMs } else null
        if (endsAt - nowMs > ParkingTimerRules.MAX_MS) return null
        // A confirmation without a date whose end has gone today: ended (shared later), not tomorrow's.
        val ended = endsAt <= nowMs
        if (ended && nowMs - endsAt > 7 * CivilDate.DAY_MS) return null

        return ParkingShared(
            endsAtMs = endsAt,
            startedAtMs = startedAt,
            provider = providerOf(s),
            location = LOCATION.find(s)?.groupValues?.get(1),
            carId = carOf(whole, cars),
            ended = ended,
        )
    }

    /** What the share sheet offers, or null when [text] isn't a parking confirmation that reads. */
    fun view(text: String?, subject: String?, nowMs: Long, cal: LocalCalendar, cars: CarsView): ParkingShareView? {
        val p = read(text, subject, nowMs, cal, cars) ?: return null
        val endText = ParkingTimerRules.endText(p.endsAtMs, nowMs, cal)
        if (p.ended) {
            return ParkingShareView(endedLine(p, nowMs, cal), true, null, emptyList(), p)
        }
        val carId = p.carId ?: if (cars.cars.size <= 1) ParkingRules.onlyCar(cars) else null
        val who = carId?.let { id -> ParkingRules.titleOf(id, cars).takeIf { cars.cars.size > 1 } }
        val line = "$ACTION until $endText" + (who?.let { " · $it" } ?: "") + (p.note?.let { " · $it" } ?: "")
        return ParkingShareView(line, false, carId, if (carId == null) ParkingRules.choices(cars) else emptyList(), p)
    }

    /** "That parking ended at 15:30 · nothing to remind you of". */
    fun endedLine(p: ParkingShared, nowMs: Long, cal: LocalCalendar): String =
        "That parking ended at ${ParkingTimerRules.endText(p.endsAtMs, nowMs, cal).removePrefix("tomorrow ")} · nothing to remind you of"

    /** The timer a confirmation starts on [carId]. */
    fun timer(p: ParkingShared, carId: String, nowMs: Long): ParkingTimer =
        ParkingTimer(carId, p.startedAtMs ?: nowMs, p.endsAtMs, p.note)

    // ---- reading ----

    internal fun flat(text: String?): String =
        text.orEmpty().replace(' ', ' ').replace(Regex("""\s+"""), " ").trim()

    private fun providerOf(lower: String): String? {
        val squashed = lower.replace(" ", "")
        return PROVIDERS.firstOrNull { (key, _) ->
            if (key.length <= 4) Regex("""\b${Regex.escape(key)}\b""").containsMatchIn(lower) else lower.contains(key) || squashed.contains(key.replace(" ", ""))
        }?.second
    }

    /** Clocks in [s]: minute of day, end index, start index. A bare number counts only with am/pm. */
    private fun clocks(s: String): List<Triple<Int, Int, Int>> = CLOCK.findAll(s).mapNotNull { m ->
        val minute = clockMinute(m) ?: return@mapNotNull null
        Triple(minute, m.range.last + 1, m.range.first)
    }.toList()

    private fun firstClock(s: String, from: Int, to: Int): Pair<Int, Int>? {
        if (from >= s.length) return null
        val window = s.substring(from, minOf(s.length, to))
        // A date before the time ("on 14/10/2026 at 15:30") never reads as one: a clock needs minutes or am/pm.
        for (m in CLOCK.findAll(window)) {
            val minute = clockMinute(m) ?: continue
            return minute to (from + m.range.last + 1)
        }
        return null
    }

    private fun clockMinute(m: MatchResult): Int? {
        var h = m.groupValues[1].toIntOrNull() ?: return null
        val mm = m.groupValues[2]
        val ampm = m.groupValues[3]
        if (mm.isEmpty() && ampm.isEmpty()) return null
        val minute = if (mm.isEmpty()) 0 else mm.toInt()
        if (minute !in 0..59) return null
        if (ampm.isNotEmpty()) {
            if (h !in 1..12) return null
            h = (h % 12) + if (ampm == "pm") 12 else 0
        }
        if (h !in 0..23) return null
        return h * 60 + minute
    }

    /** A date written between [from] and [to] (UK order), as an epoch day; "today"/"tomorrow" too. */
    private fun dateNear(s: String, from: Int, to: Int, today: Long): Long? {
        val a = maxOf(0, from)
        val b = minOf(s.length, maxOf(a, to))
        val w = s.substring(a, b)
        val year = CivilDate.fromEpochDay(today).year
        ISO_DATE.find(w)?.let { m ->
            return safeDay(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
        SLASH_DATE.find(w)?.let { m ->
            val y = m.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it } ?: year
            return safeDay(y, m.groupValues[2].toInt(), m.groupValues[1].toInt())
        }
        WORD_DATE.find(w)?.let { m ->
            val month = MONTHS.indexOf(m.groupValues[2]) + 1
            val y = m.groupValues[3].toIntOrNull() ?: year
            return safeDay(y, month, m.groupValues[1].toInt())
        }
        if (Regex("""\btomorrow\b""").containsMatchIn(w)) return today + 1
        if (Regex("""\btoday\b""").containsMatchIn(w)) return today
        return null
    }

    private fun safeDay(y: Int, m: Int, d: Int): Long? =
        if (m in 1..12 && d in 1..CivilDate.lengthOfMonth(y, m)) CivilDate.toEpochDay(y, m, d) else null

    /** The car whose registration appears in [text] (spaces ignored), or null. */
    private fun carOf(text: String, cars: CarsView): String? {
        val squashed = text.uppercase().filter { it.isLetterOrDigit() }
        if (squashed.isEmpty()) return null
        return cars.cars.firstOrNull { c ->
            val reg = c.registration?.uppercase()?.filter { it.isLetterOrDigit() }
            !reg.isNullOrEmpty() && reg.length >= 4 && squashed.contains(reg)
        }?.carId
    }
}
