package os.meka.core.domain

/** What an Also today tile is about ([AlsoTodayRules]); its place decides what a tap opens and the dot's colour. */
enum class AlsoKind {
    RENEWAL, STUDY, WEEK_AHEAD, BINS, GIFTS, TRIP, PARCELS, DINNER, DATE_NIGHT, LIFE_ADMIN, REFLECTION,
    // Slice 2b-2b: the header's remaining one-line notes, folded into the row.
    CLASH, TRAINS, TRAIN_QUEUE, SCHOOL, REPLIES, TRADING, GOALS,
    // Slice 2b-2b-2: meeting prep and a day stacking up, the header's last two notes; a tap unfolds their detail.
    PREP, PRESSURE,
}

/**
 * One Also today tile: a small caps [key] ("BINS"), the [value] in the heavier weight ("Black bin and food caddy") and
 * the quieter [line] under it ("out tonight"), said whole by [spoken]. [lit] marks something to do today (gold dot).
 * [targetId] is what a tap opens when it needs one: the renewal's id, the study course's id; null otherwise.
 */
data class AlsoTile(
    val kind: AlsoKind,
    val key: String,
    val value: String,
    val line: String,
    val spoken: String,
    val lit: Boolean,
    val targetId: String? = null,
)

/**
 * The Today rebuild (Meka approved 2026-11-08 22:58, `docs/design/day-list/TodayNew.dc.html`), slice 2b-2: **ALSO
 * TODAY**, one side-swiping row of small tiles below the schedule, in place of the header's one-line notes (study,
 * the week ahead, bins, gifts, a trip, parcels, dinner, date night, the life admin hour, the week's one thing) and the
 * bare "1 renewal due" figure. The renewal tile names it and says by when: "Streaming app · cancel by Sat 22 Nov".
 * Non-AI and pure, shared by the Fold and the Mac.
 *
 * Order: lit tiles first (something to do today), then the mockup's order (study, renewal, week ahead, bins, then the
 * rest); at most [MAX] tiles.
 *
 * Slice 2b-2b: the header's other one-line notes fold in too, so the header is only the date, greeting and weather (as
 * the mockup): a clash with work ("HEADS UP", lit), the trains on an office day (lit when a line isn't running well),
 * For the train (lit; opens the queue), the school's note, the replies waiting on him, trading (never an amount) and
 * this week's goals. Each tile's spoken line is the note's own, so a screen reader hears what the header said.
 */
object AlsoTodayRules {
    const val LABEL = "ALSO TODAY"
    const val MAX = 12

    val ORDER: List<AlsoKind> = listOf(
        AlsoKind.PREP, AlsoKind.CLASH, AlsoKind.TRAINS, AlsoKind.TRAIN_QUEUE, AlsoKind.SCHOOL, AlsoKind.PRESSURE,
        AlsoKind.STUDY, AlsoKind.RENEWAL, AlsoKind.WEEK_AHEAD, AlsoKind.BINS, AlsoKind.TRIP, AlsoKind.GIFTS,
        AlsoKind.PARCELS, AlsoKind.DINNER, AlsoKind.DATE_NIGHT, AlsoKind.LIFE_ADMIN, AlsoKind.REFLECTION,
        AlsoKind.REPLIES, AlsoKind.TRADING, AlsoKind.GOALS,
    )

    fun key(kind: AlsoKind): String = when (kind) {
        AlsoKind.RENEWAL -> "RENEWAL"
        AlsoKind.STUDY -> "STUDY"
        AlsoKind.WEEK_AHEAD -> "WEEK AHEAD"
        AlsoKind.BINS -> "BINS"
        AlsoKind.GIFTS -> "GIFTS"
        AlsoKind.TRIP -> "TRIP"
        AlsoKind.PARCELS -> "PARCELS"
        AlsoKind.DINNER -> "DINNER"
        AlsoKind.DATE_NIGHT -> "DATE NIGHT"
        AlsoKind.LIFE_ADMIN -> "LIFE ADMIN"
        AlsoKind.REFLECTION -> "THIS WEEK"
        AlsoKind.CLASH -> "HEADS UP"
        AlsoKind.TRAINS -> "TRAINS"
        AlsoKind.TRAIN_QUEUE -> "FOR THE TRAIN"
        AlsoKind.SCHOOL -> "SCHOOL"
        AlsoKind.REPLIES -> "REPLIES"
        AlsoKind.TRADING -> "TRADING"
        AlsoKind.GOALS -> "GOALS"
        AlsoKind.PREP -> "PREP"
        AlsoKind.PRESSURE -> "STACKING UP"
    }

    /** What a tap on a [AlsoKind.PREP] or [AlsoKind.PRESSURE] tile does: it unfolds the detail under the row. */
    const val UNFOLD_PREP = "Shows who's coming and what's open between you."
    const val UNFOLD_PRESSURE = "Shows ways to ease it."

    /** The kinds whose tap unfolds a detail card under the row rather than opening a place. */
    fun unfolds(kind: AlsoKind): Boolean = kind == AlsoKind.PREP || kind == AlsoKind.PRESSURE

    /** The tiles for [today]; [renewals] are the renewals needing him (overdue, cancel-by, due soon), first first. */
    fun tiles(today: Today, renewals: List<RenewalItem>, todayDay: Long): List<AlsoTile> {
        val found = listOfNotNull(
            today.study?.let { split(AlsoKind.STUDY, it.text, it.spoken, it.lit, drop = "Study today", id = it.courseId) },
            renewal(renewals, todayDay),
            today.weekAhead?.let { split(AlsoKind.WEEK_AHEAD, it.text, it.spoken, lit = false, drop = "Your week ahead") },
            today.bins?.let { bins(it) },
            today.trip?.let { split(AlsoKind.TRIP, it.text, it.spoken, it.lit) },
            today.gifts?.let { split(AlsoKind.GIFTS, it.text, it.spoken, it.lit) },
            today.parcels?.let { split(AlsoKind.PARCELS, it.text, it.spoken, it.lit) },
            today.dinner?.let { dinner(it) },
            today.dateNight?.let { split(AlsoKind.DATE_NIGHT, it.text, it.spoken, lit = true, drop = "Date night") },
            today.lifeAdmin?.let { split(AlsoKind.LIFE_ADMIN, it.text, it.spoken, lit = !it.text.startsWith("✓"), drop = "Life admin hour") },
            today.reflection?.let { reflection(it) },
            today.timeline.clashLine?.let { clash(it) },
            today.trainQueue?.let { split(AlsoKind.TRAIN_QUEUE, it.text, it.spoken, lit = true, drop = QueueRules.ACTION) },
            today.school?.let { colon(AlsoKind.SCHOOL, it.text, it.spoken, lit = false) },
            today.mailReplies?.let { split(AlsoKind.REPLIES, it.text, it.spoken, it.lit) },
            today.trading?.let { split(AlsoKind.TRADING, it.text, it.spoken, it.lit, drop = "Trading") },
        )
        return sorted(found)
    }

    /**
     * [tiles] with the notes Today itself doesn't carry: the trains on an office day's commute ([route], from the
     * weather) and this week's goals ([goalsLine], "This week: 2 of 3 goals moving"). A kind already there isn't added twice.
     */
    fun withHeader(
        tiles: List<AlsoTile>, route: RouteLine?, goalsLine: String?, goalsSpoken: String = goalsLine?.let { "$it." }.orEmpty(),
        prep: MeetingPrep? = null, pressure: DayPressure? = null,
    ): List<AlsoTile> {
        val have = tiles.map { it.kind }.toSet()
        val extra = listOfNotNull(
            route?.takeIf { AlsoKind.TRAINS !in have }?.let { split(AlsoKind.TRAINS, it.text, it.spoken, it.lit) },
            goalsLine?.takeIf { AlsoKind.GOALS !in have }?.let { colon(AlsoKind.GOALS, it, goalsSpoken, lit = false) },
            prep?.takeIf { AlsoKind.PREP !in have }?.let { prep(it) },
            pressure?.takeIf { AlsoKind.PRESSURE !in have }?.let { pressure(it) },
        )
        return if (extra.isEmpty()) tiles else sorted(tiles + extra)
    }

    /**
     * Slice 2b-2b-2: "Prep · Quote review at 14:00 · in 12 min" with "With Sam Taylor and Alex Green" → "Quote review at
     * 14:00" · "in 12 min · with Sam Taylor and Alex Green" (lit: it's minutes away). [AlsoTile.targetId] is the event.
     */
    fun prep(p: MeetingPrep): AlsoTile {
        val base = split(AlsoKind.PREP, p.headline, p.spoken, lit = true, drop = "Prep", id = p.eventId)
        val who = p.whoLine.trim().replaceFirstChar { it.lowercase() }
        val line = listOf(base.line, who).filter { it.isNotEmpty() }.joinToString(" · ")
        return base.copy(line = line, spoken = p.spoken.trimEnd() + " " + UNFOLD_PREP)
    }

    /**
     * "Friday is stacking up: Trip form, Council tax and Gym" → "Friday" · "Trip form, Council tax and Gym"; lit when
     * there's a one-tap fix to ease it (then the tap unfolds them), the amber dot otherwise.
     */
    fun pressure(p: DayPressure): AlsoTile {
        val value = p.dayName
        val line = p.line.substringAfter(": ", "").trim()
        val lit = p.fixes.isNotEmpty()
        val spoken = p.spoken.trimEnd() + if (lit) " $UNFOLD_PRESSURE" else ""
        return AlsoTile(AlsoKind.PRESSURE, key(AlsoKind.PRESSURE), value, line, spoken, lit)
    }

    private fun sorted(tiles: List<AlsoTile>): List<AlsoTile> =
        tiles.sortedWith(compareBy<AlsoTile>({ !it.lit }, { ORDER.indexOf(it.kind) })).take(MAX)

    /** "Training at 16:55 while you're at work — who's taking Alex?" → that first part · "who's taking Alex?" (lit). */
    fun clash(text: String): AlsoTile {
        val value = text.substringBefore(" — ").trim()
        val line = text.substringAfter(" — ", "").trim()
        val spoken = if (line.isEmpty()) "$value." else "$value. ${line.replaceFirstChar { it.uppercase() }}"
        return AlsoTile(AlsoKind.CLASH, key(AlsoKind.CLASH), value, line, spoken, lit = true)
    }

    /**
     * "Alex: PE today" → "PE today" · "Alex"; "This week: 2 of 3 goals moving" → "2 of 3 goals moving" · "this week".
     * A note with no single lead before ": " is [split] as usual.
     */
    fun colon(kind: AlsoKind, text: String, spoken: String, lit: Boolean): AlsoTile {
        val head = text.substringBefore(": ", "").trim()
        val rest = text.substringAfter(": ", "").trim()
        if (head.isEmpty() || rest.isEmpty() || head.contains(" · ")) return split(kind, text, spoken, lit)
        val line = if (head.equals("This week", ignoreCase = true)) "this week" else head
        return AlsoTile(kind, key(kind), rest, line, spoken, lit)
    }

    /**
     * The first renewal needing him: "Home insurance" · "cancel by Sat 22 Nov" (lit), "overdue · was Mon 2 Nov" (lit),
     * "renews Thu 12 Nov"; with "· 2 more" when others need him too. Null when none does.
     */
    fun renewal(renewals: List<RenewalItem>, todayDay: Long): AlsoTile? {
        val r = renewals.firstOrNull() ?: return null
        val more = renewals.size - 1
        val day = { d: Long -> RenewalRules.dayWord(d, todayDay) }
        val (what, lit) = when {
            r.state == RenewalState.OVERDUE -> "overdue · was ${day(r.dueDay)}" to true
            r.state == RenewalState.CANCEL_BY && r.cancelByDay != null -> "cancel by ${day(r.cancelByDay)}" to true
            else -> "renews ${day(r.dueDay)}" to false
        }
        val line = if (more > 0) "$what · $more more" else what
        val spoken = "${r.title}: ${what.replace(" · ", ", ")}." +
            (if (more > 0) " $more more ${if (more == 1) "renewal needs" else "renewals need"} you." else "") + " Opens Renewals."
        return AlsoTile(AlsoKind.RENEWAL, key(AlsoKind.RENEWAL), r.title, line, spoken, lit, r.id)
    }

    /** "Black bin and food caddy out tonight · garden bin moved to Sat" → "Black bin and food caddy" · "out tonight · …". */
    fun bins(l: BinLine): AlsoTile {
        val at = l.text.indexOf(" out tonight")
        if (at > 0 && !l.text.substring(0, at).contains(" · ")) {
            return AlsoTile(AlsoKind.BINS, key(AlsoKind.BINS), l.text.substring(0, at), l.text.substring(at + 1), l.spoken, lit = true)
        }
        return split(AlsoKind.BINS, l.text, l.spoken, lit = false)
    }

    /** "Dinner tonight: Chilli" → "Chilli" · "dinner tonight". */
    fun dinner(l: MealLine): AlsoTile {
        val head = l.text.substringBefore(": ", "")
        val dish = l.text.substringAfter(": ", "").trim()
        return if (head.isNotEmpty() && dish.isNotEmpty()) {
            AlsoTile(AlsoKind.DINNER, key(AlsoKind.DINNER), dish, head.lowercase(), l.spoken, lit = false)
        } else split(AlsoKind.DINNER, l.text, l.spoken, lit = false)
    }

    /** "This week's one thing · Run 5k" → "Run 5k" · "this week's one thing". */
    fun reflection(l: ReflectionWeekLine): AlsoTile {
        val parts = l.text.split(" · ")
        return if (parts.size >= 2) {
            AlsoTile(AlsoKind.REFLECTION, key(AlsoKind.REFLECTION), parts.drop(1).joinToString(" · "),
                parts[0].replaceFirstChar { it.lowercase() }, l.spoken, l.lit)
        } else split(AlsoKind.REFLECTION, l.text, l.spoken, l.lit)
    }

    /**
     * A one-line note as a tile: the first " · " part is the value, the rest the line under it. [drop] is a lead that
     * only repeats the tile's key ("Study today · 3 lessons · about 1 h 30" → "3 lessons" · "about 1 h 30"); a note
     * with no parts keeps the whole text as its value.
     */
    fun split(kind: AlsoKind, text: String, spoken: String, lit: Boolean, drop: String? = null, id: String? = null): AlsoTile {
        var parts = text.split(" · ").map { it.trim() }.filter { it.isNotEmpty() }
        if (drop != null && parts.size >= 2 && parts[0].equals(drop, ignoreCase = true)) parts = parts.drop(1)
        val value = parts.firstOrNull() ?: text.trim()
        val line = parts.drop(1).joinToString(" · ")
        return AlsoTile(kind, key(kind), value, line, spoken, lit, id)
    }
}
