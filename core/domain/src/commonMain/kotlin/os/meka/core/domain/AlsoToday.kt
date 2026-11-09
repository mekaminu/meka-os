package os.meka.core.domain

/** What an Also today tile is about ([AlsoTodayRules]); its place decides what a tap opens and the dot's colour. */
enum class AlsoKind { RENEWAL, STUDY, WEEK_AHEAD, BINS, GIFTS, TRIP, PARCELS, DINNER, DATE_NIGHT, LIFE_ADMIN, REFLECTION }

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
 */
object AlsoTodayRules {
    const val LABEL = "ALSO TODAY"
    const val MAX = 8

    val ORDER: List<AlsoKind> = listOf(
        AlsoKind.STUDY, AlsoKind.RENEWAL, AlsoKind.WEEK_AHEAD, AlsoKind.BINS, AlsoKind.TRIP, AlsoKind.GIFTS,
        AlsoKind.PARCELS, AlsoKind.DINNER, AlsoKind.DATE_NIGHT, AlsoKind.LIFE_ADMIN, AlsoKind.REFLECTION,
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
    }

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
        )
        return found.sortedWith(compareBy<AlsoTile>({ !it.lit }, { ORDER.indexOf(it.kind) })).take(MAX)
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
