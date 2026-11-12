package os.meka.core.domain

/** What an Also today tile is about ([AlsoTodayRules]); its place decides what a tap opens and the dot's colour. */
enum class AlsoKind {
    RENEWAL, STUDY, WEEK_AHEAD, BINS, GIFTS, TRIP, PARCELS, DINNER, DATE_NIGHT, LIFE_ADMIN, REFLECTION,
    // Slice 2b-2b: the header's remaining one-line notes, folded into the row.
    CLASH, TRAINS, TRAIN_QUEUE, SCHOOL, REPLIES, TRADING, GOALS,
    // Slice 2b-2b-2: meeting prep and a day stacking up, the header's last two notes; a tap unfolds their detail.
    PREP, PRESSURE,
    // Meka 2026-11-09 08:09: the Sunday study check-in and Monday's "Review last week" were big cards; now tiles.
    STUDY_WEEK, REVIEW,
    // Handle her requests for me (Take first 2026-11-11), what remains: what was done for him, as a tile on Today.
    DONE_FOR_YOU,
}

/** Where an Also today tile's tap goes ([AlsoTodayRules.place]). */
enum class AlsoPlace {
    STUDY, RENEWALS, WEEK_AHEAD, TRAIN_QUEUE, BINS, TRIPS, GIFTS, DINNERS, DATE_NIGHT, LIFE_ADMIN, SCHOOL, MAIL, REVIEW,
    UNFOLD, INFO,
    // Meka 2026-11-11 (Today regression 2, "no Also today tile may be a dead end"): the tiles that only told now open
    // a place too: this week's goals open Goals, trading opens Money on its Trading tab, a clash opens the Calendar.
    GOALS, MONEY_TRADING, CALENDAR,
    /** The Done for you tile opens Needs you, where its rows (Undo · Change · the drafts) are. */
    NEEDS_YOU,
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
        AlsoKind.PREP, AlsoKind.CLASH, AlsoKind.DONE_FOR_YOU, AlsoKind.TRAINS, AlsoKind.TRAIN_QUEUE, AlsoKind.SCHOOL, AlsoKind.PRESSURE,
        AlsoKind.REVIEW, AlsoKind.STUDY_WEEK, AlsoKind.STUDY, AlsoKind.RENEWAL, AlsoKind.WEEK_AHEAD, AlsoKind.BINS, AlsoKind.TRIP, AlsoKind.GIFTS,
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
        AlsoKind.STUDY_WEEK -> "STUDY WEEK"
        AlsoKind.REVIEW -> "REVIEW"
        AlsoKind.DONE_FOR_YOU -> "DONE FOR YOU"
    }

    /** What a tap on a [AlsoKind.PREP] or [AlsoKind.PRESSURE] tile does: it unfolds the detail under the row. */
    const val UNFOLD_PREP = "Shows who's coming and what's open between you."
    const val UNFOLD_PRESSURE = "Shows ways to ease it."
    const val UNFOLD_STUDY_WEEK = "Shows the check-in."
    const val OPENS_REVIEW = "Opens Review."
    /** Meka 2026-11-11 07:30: the Trains tile stays where it is and its tap shows what the trains are doing. */
    const val UNFOLD_TRAINS = "Shows the train details."
    /** Meka 2026-11-11: no tile is a dead end; the parcels tile unfolds the parcels ([ParcelRules.detail]). */
    const val UNFOLD_PARCELS = "Shows the parcels."

    /** The kinds whose tap unfolds a detail card under the row rather than opening a place. */
    fun unfolds(kind: AlsoKind): Boolean =
        kind == AlsoKind.PREP || kind == AlsoKind.PRESSURE || kind == AlsoKind.STUDY_WEEK || kind == AlsoKind.TRAINS ||
            kind == AlsoKind.PARCELS

    /**
     * The Today rebuild, slice 2d-2b (the Mac's Also today): where a tile's tap goes, so both apps agree. [AlsoPlace.UNFOLD]
     * unfolds the detail under the row ([unfolds]: prep, a day stacking up, the study check-in and the trains, whose
     * details are [RouteRules.detail], and the parcels, [ParcelRules.detail]). Meka 2026-11-11: no tile is a dead end, so
     * the ones that only told open a place: this week's goals Goals, trading Money's Trading tab, this week's one thing
     * Review (where the reflection lives), a clash the Calendar. [AlsoPlace.INFO] is left for a study or renewal tile
     * with no [AlsoTile.targetId] and a day stacking up with no fix to offer.
     */
    fun place(tile: AlsoTile): AlsoPlace = when (tile.kind) {
        AlsoKind.STUDY -> if (tile.targetId != null) AlsoPlace.STUDY else AlsoPlace.INFO
        AlsoKind.RENEWAL -> if (tile.targetId != null) AlsoPlace.RENEWALS else AlsoPlace.INFO
        AlsoKind.WEEK_AHEAD -> AlsoPlace.WEEK_AHEAD
        AlsoKind.TRAIN_QUEUE -> AlsoPlace.TRAIN_QUEUE
        AlsoKind.BINS -> AlsoPlace.BINS
        AlsoKind.TRIP -> AlsoPlace.TRIPS
        AlsoKind.GIFTS -> AlsoPlace.GIFTS
        AlsoKind.DINNER -> AlsoPlace.DINNERS
        AlsoKind.DATE_NIGHT -> AlsoPlace.DATE_NIGHT
        AlsoKind.LIFE_ADMIN -> AlsoPlace.LIFE_ADMIN
        AlsoKind.SCHOOL -> AlsoPlace.SCHOOL
        AlsoKind.REPLIES -> AlsoPlace.MAIL
        AlsoKind.REVIEW -> AlsoPlace.REVIEW
        AlsoKind.PREP, AlsoKind.STUDY_WEEK, AlsoKind.TRAINS, AlsoKind.PARCELS -> AlsoPlace.UNFOLD
        // A day stacking up unfolds only when there's a fix to offer (its tile is lit then).
        AlsoKind.PRESSURE -> if (tile.lit) AlsoPlace.UNFOLD else AlsoPlace.INFO
        AlsoKind.GOALS -> AlsoPlace.GOALS
        AlsoKind.TRADING -> AlsoPlace.MONEY_TRADING
        AlsoKind.REFLECTION -> AlsoPlace.REVIEW
        AlsoKind.CLASH -> AlsoPlace.CALENDAR
        AlsoKind.DONE_FOR_YOU -> AlsoPlace.NEEDS_YOU
    }

    /** What a screen reader hears after the tile: "Opens Renewals", "Shows the check-in"; empty for a tile that only tells. */
    fun hint(tile: AlsoTile): String = when (place(tile)) {
        AlsoPlace.STUDY -> "Opens the course"
        AlsoPlace.RENEWALS -> "Opens Renewals"
        AlsoPlace.WEEK_AHEAD -> "Opens the week ahead"
        AlsoPlace.TRAIN_QUEUE -> "Opens For the train"
        AlsoPlace.BINS -> "Opens Bins"
        AlsoPlace.TRIPS -> "Opens Trips"
        AlsoPlace.GIFTS -> "Opens Gifts"
        AlsoPlace.DINNERS -> "Opens Dinners"
        AlsoPlace.DATE_NIGHT -> "Opens Date night"
        AlsoPlace.LIFE_ADMIN -> "Opens Life admin"
        AlsoPlace.SCHOOL -> "Opens School"
        AlsoPlace.MAIL -> "Opens mail"
        AlsoPlace.REVIEW -> "Opens Review"
        AlsoPlace.GOALS -> "Opens Goals"
        AlsoPlace.MONEY_TRADING -> "Opens Trading"
        AlsoPlace.CALENDAR -> "Opens the Calendar"
        AlsoPlace.NEEDS_YOU -> "Opens Needs you"
        AlsoPlace.UNFOLD -> when (tile.kind) {
            AlsoKind.PREP -> UNFOLD_PREP.removeSuffix(".")
            AlsoKind.PRESSURE -> UNFOLD_PRESSURE.removeSuffix(".")
            AlsoKind.TRAINS -> UNFOLD_TRAINS.removeSuffix(".")
            AlsoKind.PARCELS -> UNFOLD_PARCELS.removeSuffix(".")
            else -> UNFOLD_STUDY_WEEK.removeSuffix(".")
        }
        AlsoPlace.INFO -> ""
    }

    /**
     * The tiles for [today]; [renewals] are the renewals needing him (overdue, cancel-by, due soon), first first.
     * [doneForYou] is what was done for him from his wife's requests ([HandleForMeRules.alsoTile]), or null.
     */
    fun tiles(today: Today, renewals: List<RenewalItem>, todayDay: Long, doneForYou: AlsoTile? = null): List<AlsoTile> {
        val found = listOfNotNull(
            doneForYou,
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
        checkin: StudyCheckin? = null, review: ReviewCard? = null,
    ): List<AlsoTile> {
        val have = tiles.map { it.kind }.toSet()
        val extra = listOfNotNull(
            route?.takeIf { AlsoKind.TRAINS !in have }?.let { trains(it) },
            goalsLine?.takeIf { AlsoKind.GOALS !in have }?.let { colon(AlsoKind.GOALS, it, goalsSpoken, lit = false) },
            prep?.takeIf { AlsoKind.PREP !in have }?.let { prep(it) },
            pressure?.takeIf { AlsoKind.PRESSURE !in have }?.let { pressure(it) },
            checkin?.takeIf { AlsoKind.STUDY_WEEK !in have }?.let { checkin(it) },
            review?.takeIf { AlsoKind.REVIEW !in have }?.let { review(it) },
        )
        return if (extra.isEmpty()) tiles else sorted(tiles + extra)
    }

    /**
     * The trains on an office day's commute: "South Rail" · "leave by 07:32 for the 07:42 · 07:42 on time · …" (lit
     * when a train is cancelled or late); its tap unfolds the details ([RouteRules.detail]), so the spoken line says so.
     */
    fun trains(r: RouteLine): AlsoTile {
        val base = split(AlsoKind.TRAINS, r.text, r.spoken, r.lit)
        val said = r.spoken.trimEnd().let { if (it.isEmpty() || it.last() in ".!?") it else "$it." }
        return base.copy(spoken = "$said $UNFOLD_TRAINS")
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

    /**
     * Meka 2026-11-09 08:09 (b): the Sunday study check-in is a tile, not the first block on Today: "0 of 2 courses" ·
     * "this week · plan next" (a course with no sessions next week) or "this week · next week set"; lit (it's a look to
     * take today). A tap unfolds the check-in itself (Looks good, Change plan) under the row. Null with no courses in it.
     */
    fun checkin(c: StudyCheckin): AlsoTile? {
        if (c.rows.isEmpty()) return null
        val moved = c.rows.count { !it.week.contains(StudyCheckinRules.NOTHING_TICKED) }
        val total = c.rows.size
        val value = "$moved of $total ${if (total == 1) "course" else "courses"}"
        val plan = if (c.rows.any { it.next.contains(StudyCheckinRules.NO_SESSIONS) }) "plan next" else "next week set"
        val line = "this week · $plan"
        val spoken = "Study week: $moved of $total ${if (total == 1) "course" else "courses"} moved this week, " +
            (if (plan == "plan next") "next week needs planning." else "next week is set.") + " $UNFOLD_STUDY_WEEK"
        return AlsoTile(AlsoKind.STUDY_WEEK, key(AlsoKind.STUDY_WEEK), value, line, spoken, lit = true)
    }

    /**
     * Meka 2026-11-09 08:09 (f): Monday's "Review last week" card is a tile: "Review last week" · "12 done · 2 still
     * open" (lit; a tap opens Review on that week). Null when the card isn't offered. Sunday's review rides on the
     * evening card ([EveningCardRules]), so Today passes only the card it would otherwise have drawn.
     */
    fun review(r: ReviewCard): AlsoTile? {
        if (!r.offered || r.title.isEmpty()) return null
        val spoken = r.title + (if (r.line.isNotEmpty()) ": ${r.line.replace(" · ", ", ")}" else "") + ". $OPENS_REVIEW"
        return AlsoTile(AlsoKind.REVIEW, key(AlsoKind.REVIEW), r.title, r.line, spoken, lit = true)
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
