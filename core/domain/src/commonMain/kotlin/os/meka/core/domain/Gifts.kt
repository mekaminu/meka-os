package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Gift planner (V1), slice 1: birthdays and anniversaries typed once — "Mum's birthday 3 Nov", "Jeanette birthday 14
 * March 1988", "Wedding anniversary 20 Aug 2015", "Rex 5 June · delivery 5 days" — gift ideas noted against each, and
 * an order-by date worked out from how long delivery takes (3 days unless typed, plus a spare day).
 *
 * Two weeks before at 09:00 a heads-up "Mum's birthday in 2 weeks · Sat 31 Oct" with the ideas and the order-by date;
 * on the order-by day at 09:00 "Order Mum's gift today"; both stop once Meka marks the gift sorted for that year. Today's
 * header says "Order Mum's gift today · birthday Sat 31 Oct", "Mum's birthday tomorrow" and "Mum's birthday today ·
 * turns 60"; the week ahead shows the day and the order-by day. Plain, non-AI rules; nothing is ordered or sent.
 *
 * One synced `occasion` entity per person and kind, and one `gift_idea` per idea (ADR-008 addendum 2026-10-12).
 */
object OccasionFields {
    /** "Mum", "Jeanette", "Uncle Tony"; "" for a wedding anniversary with no one named (at most [GiftRules.MAX_PERSON]). */
    const val PERSON = "person"
    /** [OccasionKind.key]. */
    const val KIND = "kind"
    const val MONTH = "month"
    const val DAY = "day"
    /** The year born or married, 0 when not typed. */
    const val YEAR = "year"
    /** Days delivery takes, 1..[GiftRules.MAX_DELIVERY]. */
    const val DELIVERY = "deliveryDays"
    /** The epoch day of the occurrence whose gift is sorted; 0 when none. */
    const val SORTED_FOR = "sortedForDay"
    const val ADDED_AT = "addedAtMs"
    const val DELETED = ActionableFields.DELETED
}

object GiftIdeaFields {
    const val OCCASION = "occasionId"
    const val TEXT = "text"
    const val ADDED_AT = "addedAtMs"
    const val DELETED = ActionableFields.DELETED
}

enum class OccasionKind(val key: String, val word: String) {
    BIRTHDAY("birthday", "birthday"), ANNIVERSARY("anniversary", "anniversary");

    companion object { fun of(key: String?): OccasionKind? = entries.firstOrNull { it.key == key } }
}

/** What one typed line says ([GiftRules.read]); [deliveryDays] null when not typed. */
data class OccasionEntry(
    val person: String, val kind: OccasionKind, val month: Int, val day: Int, val year: Int, val deliveryDays: Int?,
)

data class GiftIdea(val id: String, val occasionId: String, val text: String, val addedAtMs: Long)

/** One stored birthday or anniversary. */
data class Occasion(
    val id: String, val person: String, val kind: OccasionKind, val month: Int, val day: Int, val year: Int,
    val deliveryDays: Int, val sortedForDay: Long, val addedAtMs: Long,
    /** Oldest first. */
    val ideas: List<GiftIdea> = emptyList(),
)

data class GiftIdeaRow(val id: String, val text: String)

/**
 * A row in Gifts: "Mum's birthday" · "Sat 31 Oct · in 19 days · turns 60" · "Order by Tue 27 Oct" ([noteLit] within
 * two weeks and not sorted) or "✓ Gift sorted"; its ideas, and whether Gift sorted or Not sorted yet is offered.
 */
data class GiftRow(
    val id: String,
    val title: String,
    val line: String,
    val note: String,
    val noteLit: Boolean,
    val sorted: Boolean,
    val ideas: List<GiftIdeaRow>,
    val spoken: String,
)

data class GiftsView(val rows: List<GiftRow>, val summary: String) {
    val isEmpty: Boolean get() = rows.isEmpty()

    companion object { val EMPTY = GiftsView(emptyList(), GiftRules.EMPTY_LINE) }
}

/** Today's header: "Order Mum's gift today · birthday Sat 31 Oct", lit when there's something to do. */
data class GiftLine(val text: String, val spoken: String, val lit: Boolean)

data class GiftAdded(val line: String, val refused: Boolean)

object GiftRules {
    const val MAX_PERSON = 30
    const val MAX_IDEA = 80
    const val MAX_IDEAS = 12
    const val DEFAULT_DELIVERY = 3
    const val MAX_DELIVERY = 30
    /** The order-by day leaves this many spare days before the occasion on top of delivery. */
    const val SPARE_DAYS = 1
    /** The first heads-up this many days before. */
    const val AHEAD_DAYS = 14
    const val NOTICE_MIN = 9 * 60

    const val ADD_HINT = "Add… (Mum's birthday 3 Nov, Wedding anniversary 20 Aug 2015)"
    const val IDEA_HINT = "Add a gift idea…"
    const val EMPTY_LINE = "Nothing entered yet. Type each birthday or anniversary once; Navilife reminds you two weeks ahead and on the day to order."
    const val NOT_READ = "Navilife couldn't read that. Try \"Mum's birthday 3 Nov\" or \"Wedding anniversary 20 Aug 2015\"."
    const val NOTE = "Two weeks before, a heads-up with your ideas; on the order-by day, a reminder to order. Delivery is 3 days unless you type \"delivery 5 days\". Navilife never orders anything."

    private val BIRTHDAY_WORDS = setOf("birthday", "birthdays", "bday", "b'day", "b’day", "born", "dob")
    private val ANNIVERSARY_WORDS = setOf("anniversary", "anniversaries", "anniv", "wedding", "married")
    private val FILLER = setOf(
        "is", "on", "the", "a", "an", "of", "my", "our", "his", "her", "their", "date", "day", "in", "at", "every", "year",
        "each", "delivery", "deliver", "delivers", "post", "posting", "shipping", "takes", "days", "for", "to",
        "turns", "turning", "was",
    )
    private val ORDINALS = setOf("st", "nd", "rd", "th")

    private fun monthOf(s: String): Int? = when (s.lowercase().take(3)) {
        "jan" -> 1; "feb" -> 2; "mar" -> 3; "apr" -> 4; "may" -> 5; "jun" -> 6
        "jul" -> 7; "aug" -> 8; "sep" -> 9; "oct" -> 10; "nov" -> 11; "dec" -> 12
        else -> null
    }

    private fun isMonthWord(s: String): Boolean {
        val l = s.lowercase()
        val m = monthOf(l) ?: return false
        val full = CivilDate.MONTH_LONG[m - 1].lowercase()
        return l.length >= 3 && (full.startsWith(l) || l == "sept")
    }

    /** Words by hand (letters, digits, apostrophes, slashes kept for "3/11"), so Kotlin/Native's regexes can't read them differently. */
    private fun words(text: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (c in text) {
            if (c.isLetterOrDigit() || c == '\'' || c == '’' || c == '/') sb.append(c) else if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    /** "3rd" → 3, "14" → 14; null for anything else. */
    private fun dayNumber(w: String): Int? {
        val l = w.lowercase()
        val digits = l.takeWhile { it.isDigit() }
        if (digits.isEmpty() || digits.length > 2) return null
        val rest = l.substring(digits.length)
        if (rest.isNotEmpty() && rest !in ORDINALS) return null
        return digits.toInt().takeIf { it in 1..31 }
    }

    private fun yearNumber(w: String, thisYear: Int): Int? =
        w.takeIf { it.length == 4 && it.all(Char::isDigit) }?.toInt()?.takeIf { it in 1900..thisYear }

    /** "mum's" → "mum", "james'" → "james". */
    private fun stripPossessive(w: String): String {
        for (suffix in listOf("'s", "’s")) if (w.lowercase().endsWith(suffix)) return w.dropLast(2)
        if (w.endsWith("'") || w.endsWith("’")) return w.dropLast(1)
        return w
    }

    /** "mum" → "Mum", "uncle tony" → "Uncle Tony". */
    fun tidyPerson(raw: String): String {
        val parts = raw.trim().split(' ').filter { it.isNotEmpty() }.toMutableList()
        while (parts.isNotEmpty() && parts.first().lowercase() == "and") parts.removeAt(0)
        while (parts.isNotEmpty() && parts.last().lowercase() == "and") parts.removeAt(parts.size - 1)
        return parts.joinToString(" ") { w -> if (w.lowercase() == "and") "and" else w.replaceFirstChar { it.uppercase() } }
            .take(MAX_PERSON).trim()
    }

    /**
     * What one typed line says, or null when it names no day and month MEKA can read, or no one for a birthday.
     * "Mum's birthday 3 Nov", "Jeanette birthday 14 March 1988", "3rd November: Dad", "Rex 5/6/2016", "Wedding
     * anniversary 20 Aug 2015", "Mum birthday 3 Nov delivery 5 days". With no kind word it's a birthday.
     */
    fun read(text: String, thisYear: Int): OccasionEntry? {
        val w = words(text.trim())
        if (w.isEmpty()) return null
        val lower = w.map { it.lowercase() }
        val used = BooleanArray(w.size)
        var month = 0
        var day = 0
        var year = 0
        // "3/11" or "03/11/1960" (UK order, day first).
        for (i in w.indices) {
            if ('/' !in w[i]) continue
            val parts = w[i].split('/')
            if (parts.size !in 2..3 || parts.any { p -> p.isEmpty() || !p.all(Char::isDigit) }) return null
            val d = parts[0].toInt()
            val m = parts[1].toInt()
            if (m !in 1..12 || d !in 1..31) return null
            month = m; day = d; used[i] = true
            if (parts.size == 3) {
                val y = parts[2].toInt().let { if (parts[2].length == 2) (if (2000 + it > thisYear) 1900 + it else 2000 + it) else it }
                year = y.takeIf { it in 1900..thisYear } ?: return null
            }
            break
        }
        if (month == 0) {
            for (i in w.indices) {
                if (!isMonthWord(w[i])) continue
                val before = if (i > 0) dayNumber(w[i - 1]) else null
                val after = if (i + 1 < w.size) dayNumber(w[i + 1]) else null
                val d = before ?: after ?: continue
                month = monthOf(w[i])!!; day = d; used[i] = true
                if (before != null) used[i - 1] = true else used[i + 1] = true
                break
            }
        }
        if (month == 0 || day > lengthOf(month)) return null
        for (i in w.indices) if (!used[i] && year == 0) yearNumber(w[i], thisYear)?.let { year = it; used[i] = true }
        // Delivery: "delivery 5 days", "5 days delivery", "5 day delivery".
        var delivery: Int? = null
        if (lower.any { it.startsWith("deliver") || it == "post" || it == "posting" || it == "shipping" }) {
            for (i in 0 until w.size - 1) {
                val n = lower[i].takeIf { it.isNotEmpty() && it.length <= 2 && it.all(Char::isDigit) }?.toInt() ?: continue
                if (used[i] || !lower[i + 1].startsWith("day")) continue
                delivery = n.coerceIn(1, MAX_DELIVERY); used[i] = true; used[i + 1] = true
                break
            }
        }
        val kind = when {
            lower.any { it in ANNIVERSARY_WORDS } -> OccasionKind.ANNIVERSARY
            else -> OccasionKind.BIRTHDAY
        }
        val nameWords = w.filterIndexed { i, _ ->
            val l = lower[i]
            !used[i] && l !in FILLER && l !in BIRTHDAY_WORDS && l !in ANNIVERSARY_WORDS && dayNumber(l) == null &&
                !(l.all(Char::isDigit))
        }.map(::stripPossessive).filter { it.isNotEmpty() }
        val person = tidyPerson(nameWords.joinToString(" "))
        if (person.isEmpty() && kind == OccasionKind.BIRTHDAY) return null
        return OccasionEntry(person, kind, month, day, year, delivery)
    }

    /** The longest the month runs in any year (29 for February). */
    private fun lengthOf(month: Int): Int = if (month == 2) 29 else CivilDate.lengthOfMonth(2001, month)

    /** "Mum's birthday", "James's birthday", "Wedding anniversary", "Mum and Dad's anniversary". */
    fun title(person: String, kind: OccasionKind): String = when {
        person.isEmpty() -> "Wedding anniversary"
        else -> "${possessive(person)} ${kind.word}"
    }

    fun title(o: Occasion): String = title(o.person, o.kind)

    private fun possessive(person: String): String = if (person.endsWith("s'") || person.endsWith("'s")) person else "$person's"

    /** "Order Mum's gift", "Order the anniversary gift". */
    private fun giftOf(o: Occasion): String = if (o.person.isEmpty()) "the anniversary gift" else "${possessive(o.person)} gift"

    /** The occasion's day in [year]; 29 Feb falls on 28 Feb in other years. */
    fun dayIn(o: Occasion, year: Int): Long = CivilDate.toEpochDay(year, o.month, minOf(o.day, CivilDate.lengthOfMonth(year, o.month)))

    /** The next time it comes round, today counting. */
    fun next(o: Occasion, today: Long): Long {
        val y = CivilDate.fromEpochDay(today).year
        val d = dayIn(o, y)
        return if (d >= today) d else dayIn(o, y + 1)
    }

    /** The day to order by for the occurrence on [day]: delivery plus a spare day before. */
    fun orderBy(o: Occasion, day: Long): Long = day - o.deliveryDays - SPARE_DAYS

    fun isSorted(o: Occasion, day: Long): Boolean = o.sortedForDay == day

    /** "turns 60", "11 years"; null without a year. */
    fun age(o: Occasion, day: Long): String? {
        if (o.year <= 0) return null
        val n = CivilDate.fromEpochDay(day).year - o.year
        if (n <= 0) return null
        return when (o.kind) {
            OccasionKind.BIRTHDAY -> "turns $n"
            OccasionKind.ANNIVERSARY -> if (n == 1) "1 year" else "$n years"
        }
    }

    private fun dayWord(day: Long, today: Long): String = when (day) {
        today -> "today"
        today + 1 -> "tomorrow"
        else -> CivilDate.shortLabel(day)
    }

    private fun inDays(day: Long, today: Long): String = when (val n = day - today) {
        0L -> "today"
        1L -> "tomorrow"
        else -> "in $n days"
    }

    private fun ideasLine(o: Occasion): String? =
        o.ideas.takeIf { it.isNotEmpty() }?.let { i -> "Ideas: " + i.take(3).joinToString(", ") { it.text } + if (i.size > 3) " and ${i.size - 3} more" else "" }

    private fun Map<String, FieldValue>.f(field: String): FieldValue = this[field] ?: FieldValue.Null

    fun ideas(entities: Map<String, Map<String, FieldValue>>): Map<String, List<GiftIdea>> = entities.mapNotNull { (id, f) ->
        if (f.f(GiftIdeaFields.DELETED).boolOrNull == true) return@mapNotNull null
        val occ = f.f(GiftIdeaFields.OCCASION).textOrNull ?: return@mapNotNull null
        val text = f.f(GiftIdeaFields.TEXT).textOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        GiftIdea(id, occ, text, f.f(GiftIdeaFields.ADDED_AT).longOrNull ?: 0L)
    }.sortedWith(compareBy<GiftIdea> { it.addedAtMs }.thenBy { it.id }).groupBy { it.occasionId }

    /** The live occasions from each entity's fields; unreadable ones are skipped. */
    fun items(entities: Map<String, Map<String, FieldValue>>): List<Occasion> = entities.mapNotNull { (id, s) ->
        if (s.f(OccasionFields.DELETED).boolOrNull == true) return@mapNotNull null
        val kind = OccasionKind.of(s.f(OccasionFields.KIND).textOrNull) ?: return@mapNotNull null
        val person = s.f(OccasionFields.PERSON).textOrNull ?: ""
        if (person.isBlank() && kind == OccasionKind.BIRTHDAY) return@mapNotNull null
        val month = (s.f(OccasionFields.MONTH).longOrNull ?: return@mapNotNull null).toInt()
        val day = (s.f(OccasionFields.DAY).longOrNull ?: return@mapNotNull null).toInt()
        if (month !in 1..12 || day !in 1..lengthOf(month)) return@mapNotNull null
        Occasion(
            id, person.trim(), kind, month, day,
            year = (s.f(OccasionFields.YEAR).longOrNull ?: 0L).toInt(),
            deliveryDays = (s.f(OccasionFields.DELIVERY).longOrNull ?: DEFAULT_DELIVERY.toLong()).toInt().coerceIn(1, MAX_DELIVERY),
            sortedForDay = s.f(OccasionFields.SORTED_FOR).longOrNull ?: 0L,
            addedAtMs = s.f(OccasionFields.ADDED_AT).longOrNull ?: 0L,
        )
    }.sortedWith(compareBy<Occasion> { it.addedAtMs }.thenBy { it.id })

    /** Soonest first from [today]. */
    fun bySoonest(items: List<Occasion>, today: Long): List<Occasion> =
        items.sortedWith(compareBy<Occasion> { next(it, today) }.thenBy { title(it).lowercase() }.thenBy { it.id })

    /** The Gifts pane. */
    fun view(items: List<Occasion>, today: Long): GiftsView {
        if (items.isEmpty()) return GiftsView.EMPTY
        val soonest = bySoonest(items, today)
        val rows = soonest.map { o ->
            val day = next(o, today)
            val by = orderBy(o, day)
            val sorted = isSorted(o, day)
            val line = listOfNotNull(
                if (day == today) "Today" else CivilDate.shortLabel(day),
                if (day == today) null else inDays(day, today),
                age(o, day),
            ).joinToString(" · ")
            val note = when {
                sorted -> "✓ Gift sorted"
                day == today -> "Today · no gift sorted"
                by < today -> "Order-by day passed · ${o.deliveryDays} days' delivery"
                by == today -> "Order today · ${o.deliveryDays} days' delivery"
                else -> "Order by ${CivilDate.shortLabel(by)} · ${o.deliveryDays} days' delivery"
            }
            val lit = !sorted && day - today <= AHEAD_DAYS
            GiftRow(
                id = o.id, title = title(o), line = line, note = note, noteLit = lit, sorted = sorted,
                ideas = o.ideas.map { GiftIdeaRow(it.id, it.text) },
                spoken = listOfNotNull(title(o), line, note, ideasLine(o)).joinToString(". "),
            )
        }
        val first = soonest.first()
        return GiftsView(rows, "Next: ${title(first)} · ${dayWord(next(first, today), today).replaceFirstChar { it.uppercase() }}")
    }

    /**
     * Today's header: an occasion today ("Mum's birthday today · turns 60"), tomorrow ("Mum's birthday tomorrow"),
     * else a gift to order, from its order-by day until the day before ("Order Mum's gift today · birthday Sat 31
     * Oct", lit). One line; "· and 1 more" when several.
     */
    fun todayLine(items: List<Occasion>, today: Long): GiftLine? {
        fun line(found: List<Occasion>, text: (Occasion) -> String, lit: Boolean): GiftLine? {
            if (found.isEmpty()) return null
            val first = text(found.first())
            val more = if (found.size > 1) " · and ${found.size - 1} more" else ""
            return GiftLine(first + more, first + (if (found.size > 1) ", and ${found.size - 1} more" else "") + ".", lit)
        }
        val soonest = bySoonest(items, today)
        line(soonest.filter { next(it, today) == today }, { o ->
            title(o) + " today" + (age(o, today)?.let { " · $it" } ?: "")
        }, lit = false)?.let { return it }
        line(soonest.filter { next(it, today) == today + 1 }, { o -> title(o) + " tomorrow" }, lit = false)?.let { return it }
        return line(soonest.filter { o ->
            val day = next(o, today)
            !isSorted(o, day) && orderBy(o, day) <= today && day > today
        }, { o ->
            val day = next(o, today)
            val by = orderBy(o, day)
            (if (by == today) "Order ${giftOf(o)} today" else "${giftOf(o).replaceFirstChar { it.uppercase() }} not sorted yet") +
                " · ${o.kind.word} ${CivilDate.shortLabel(day)}"
        }, lit = true)
    }

    /** The week ahead's lines on [day]: the occasion itself and, while not sorted, its order-by day (lit). */
    fun weekLines(items: List<Occasion>, day: Long): List<Pair<String, Boolean>> {
        val out = mutableListOf<Pair<String, Boolean>>()
        for (o in bySoonest(items, day)) {
            val d = next(o, day)
            if (d == day) out += (title(o) + (age(o, d)?.let { " · $it" } ?: "")) to false
        }
        for (o in bySoonest(items, day)) {
            val d = next(o, day + 1)
            if (orderBy(o, d) == day && !isSorted(o, d)) out += "Order ${giftOf(o)} · ${o.kind.word} ${CivilDate.shortLabel(d)}" to true
        }
        return out
    }

    /**
     * The heads-ups (source [NoticeSource.GIFTS]) at [NOTICE_MIN]: [AHEAD_DAYS] before, "Mum's birthday in 2 weeks ·
     * Sat 31 Oct" · "Ideas: scarf, book · order by Tue 27 Oct"; on the order-by day, "Order Mum's gift today" ·
     * "Birthday Sat 31 Oct · 3 days' delivery · Ideas: …". Neither once the gift is sorted for that day.
     */
    fun notices(items: List<Occasion>, nowMs: Long, cal: LocalCalendar): List<Notice> {
        val today = cal.epochDayOf(nowMs)
        val out = mutableListOf<Notice>()
        for (o in items) {
            val day = next(o, today)
            if (day - today > AHEAD_DAYS || isSorted(o, day)) continue
            val by = orderBy(o, day)
            // Added too late for the order-by day: Today's line says it's not sorted yet, nothing posts.
            if (by < today) continue
            val ideas = ideasLine(o)
            val aheadDay = day - AHEAD_DAYS
            if (aheadDay < by && today < by) {
                out += Notice(
                    key = "gift:${o.id}:$day:ahead", source = NoticeSource.GIFTS, tier = NoticeTier.HEADS_UP,
                    title = "${title(o)} in 2 weeks · ${CivilDate.shortLabel(day)}",
                    text = (ideas ?: "No gift ideas yet · add some in Gifts") + " · order by ${CivilDate.shortLabel(by)}",
                    atMs = cal.toEpochMs(aheadDay, NOTICE_MIN), target = NoticeTarget.TODAY,
                    expiresAtMs = cal.toEpochMs(by, NOTICE_MIN),
                )
            }
            out += Notice(
                key = "gift:${o.id}:$day:order", source = NoticeSource.GIFTS, tier = NoticeTier.HEADS_UP,
                title = "Order ${giftOf(o)} today",
                text = "${o.kind.word.replaceFirstChar { it.uppercase() }} ${CivilDate.shortLabel(day)} · ${o.deliveryDays} days' delivery" +
                    (ideas?.let { " · $it" } ?: ""),
                atMs = cal.toEpochMs(by, NOTICE_MIN), target = NoticeTarget.TODAY,
                expiresAtMs = cal.toEpochMs(day, 0),
            )
        }
        return out
    }

    fun fields(e: OccasionEntry, nowMs: Long): Map<String, FieldValue> = buildMap {
        put(OccasionFields.PERSON, e.person.fv())
        put(OccasionFields.KIND, e.kind.key.fv())
        put(OccasionFields.MONTH, e.month.toLong().fv())
        put(OccasionFields.DAY, e.day.toLong().fv())
        put(OccasionFields.ADDED_AT, nowMs.fv())
        put(OccasionFields.DELETED, false.fv())
        put(OccasionFields.YEAR, e.year.toLong().fv())
        put(OccasionFields.DELIVERY, (e.deliveryDays ?: DEFAULT_DELIVERY).toLong().fv())
    }

    /** "Added Mum's birthday · Tue 3 Nov · in 22 days · order by Fri 30 Oct". */
    fun addedLine(o: Occasion, today: Long, replaced: Boolean): String {
        val day = next(o, today)
        return listOfNotNull(
            "${if (replaced) "Updated" else "Added"} ${title(o)}",
            CivilDate.shortLabel(day),
            inDays(day, today),
            age(o, day),
            orderBy(o, day).takeIf { it > today }?.let { "order by ${CivilDate.shortLabel(it)}" },
        ).joinToString(" · ")
    }
}

/**
 * Gift planner, slice 2: Ask and Talk know the birthdays, anniversaries and ideas ("when's Mum's birthday?", "what
 * did I note for Jeanette?"), and a model's `add_gift_idea` ("Mum: silk scarf") becomes a card that notes the idea
 * on Meka's tap, with Undo. Plain rules; nothing is ordered or sent.
 */
object GiftAskRules {
    /** Occasions the Ask lines name (soonest first). */
    const val ASK_ITEMS = 8
    /** Ideas named on one line. */
    const val ASK_IDEAS = 4
    const val ALREADY = "That gift idea is already noted"
    const val GONE = "That birthday or anniversary isn't in Gifts any more"

    private val KIND_WORDS = setOf("birthday", "bday", "anniversary", "wedding", "gift", "gifts", "present", "presents", "idea", "ideas", "for", "the", "my", "our")

    /**
     * The gifts as MEKA describes them to the model (Ask's `gifts` lines): "Gifts · 3 birthdays and anniversaries",
     * then each, soonest first: "Mum's birthday · Tue 3 Nov · in 22 days · turns 60 · order by Fri 30 Oct · 3 days'
     * delivery · ideas: Silk scarf, Garden voucher". Nothing at all while none are entered.
     */
    fun askLines(view: GiftsView): List<String> {
        if (view.rows.isEmpty()) return emptyList()
        val n = view.rows.size
        val head = "Gifts · $n " + (if (n == 1) "birthday or anniversary" else "birthdays and anniversaries") +
            if (n > ASK_ITEMS) " · the soonest $ASK_ITEMS below" else ""
        return listOf(head) + view.rows.take(ASK_ITEMS).map { r ->
            val note = r.note.removePrefix("✓ ").replaceFirstChar { it.lowercase() }
            val ideas = if (r.ideas.isEmpty()) "no gift ideas yet" else
                "ideas: " + r.ideas.take(ASK_IDEAS).joinToString(", ") { it.text } + if (r.ideas.size > ASK_IDEAS) " and ${r.ideas.size - ASK_IDEAS} more" else ""
            listOf(r.title, r.line, note, ideas).joinToString(" · ")
        }
    }

    private fun words(s: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (c in s) {
            if (c.isLetterOrDigit() || c == '\'' || c == '’' || c == '-') sb.append(c) else if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    private fun bare(w: String): String {
        val l = w.lowercase()
        for (suffix in listOf("'s", "’s")) if (l.endsWith(suffix)) return l.dropLast(2)
        return l.trimEnd('\'', '’')
    }

    /**
     * The occasion "Mum", "Mum's birthday", "Jeanette's", "the wedding anniversary" or "Mum and Dad's anniversary"
     * names; a birthday before an anniversary when only the person is given; null when none matches.
     */
    fun match(items: List<Occasion>, who: String): Occasion? {
        val w = words(who).map(::bare)
        val wantsAnniversary = w.any { it == "anniversary" || it == "wedding" }
        val wantsBirthday = w.any { it == "birthday" || it == "bday" }
        val person = w.filter { it.isNotEmpty() && it !in KIND_WORDS }.joinToString(" ")
        val found = items.filter { o ->
            o.person.lowercase() == person &&
                (!wantsAnniversary || o.kind == OccasionKind.ANNIVERSARY) && (!wantsBirthday || o.kind == OccasionKind.BIRTHDAY)
        }
        return found.firstOrNull { it.kind == OccasionKind.BIRTHDAY } ?: found.firstOrNull()
    }

    /**
     * A model's `add_gift_idea` ("Mum: silk scarf", "Wedding anniversary: weekend away") as a card: the occasion as
     * Gifts has it and the idea, at most [GiftRules.MAX_IDEA] characters. Null when it names no occasion Meka has,
     * no idea, one already noted, or one with [GiftRules.MAX_IDEAS] already.
     */
    fun proposal(items: List<Occasion>, title: String?): AskProposal.AddGiftIdea? {
        val t = title?.replace('\n', ' ')?.trim() ?: return null
        val colon = t.indexOf(':').takeIf { it > 0 } ?: return null
        val o = match(items, t.substring(0, colon)) ?: return null
        val idea = t.substring(colon + 1).trim().take(GiftRules.MAX_IDEA).trim()
        if (idea.isEmpty() || o.ideas.size >= GiftRules.MAX_IDEAS) return null
        if (o.ideas.any { it.text.equals(idea, ignoreCase = true) }) return null
        return AskProposal.AddGiftIdea(o.id, GiftRules.title(o), idea)
    }

    /** The card's words: "Gift idea for Mum's birthday · Silk scarf". */
    fun cardLine(p: AskProposal.AddGiftIdea): String = "Gift idea for ${p.title} · ${p.idea}"

    /** The undo bar's line: "Noted Silk scarf for Mum's birthday". */
    fun doneLine(p: AskProposal.AddGiftIdea): String = "Noted ${p.idea} for ${p.title}"
}

/** The birthdays, anniversaries and gift ideas on a replica. */
class Gifts(
    private val replica: Replica,
    private val newId: () -> String,
    private val nowMs: () -> Long,
    private val calendar: LocalCalendar = LocalCalendar.UTC,
) {
    private fun today() = calendar.epochDayOf(nowMs())

    fun items(): List<Occasion> {
        val ideas = GiftRules.ideas(replica.entities(EntityTypes.GIFT_IDEA).associate { it.ref.entityId to it.fields })
        return GiftRules.items(replica.entities(EntityTypes.OCCASION).associate { it.ref.entityId to it.fields })
            .map { o -> ideas[o.id]?.let { o.copy(ideas = it) } ?: o }
    }

    fun view(): GiftsView = GiftRules.view(items(), today())

    /** Adds (or, for the same person and kind, updates) what [text] says; refused when MEKA couldn't read it. */
    fun add(text: String): GiftAdded {
        val today = today()
        val e = GiftRules.read(text, CivilDate.fromEpochDay(today).year) ?: return GiftAdded(GiftRules.NOT_READ, refused = true)
        val existing = items().firstOrNull { it.kind == e.kind && it.person.equals(e.person, ignoreCase = true) }
        val id = existing?.id ?: newId()
        var fields = GiftRules.fields(e, nowMs())
        if (existing != null) {
            fields = fields - OccasionFields.ADDED_AT
            // Typing it again without a year or delivery keeps what was there.
            if (e.year == 0) fields = fields - OccasionFields.YEAR
            if (e.deliveryDays == null) fields = fields - OccasionFields.DELIVERY
        }
        replica.commitLocal(EntityTypes.OCCASION, id, fields)
        val saved = items().first { it.id == id }
        return GiftAdded(GiftRules.addedLine(saved, today, replaced = existing != null), refused = false)
    }

    private fun live(id: String): Occasion? = items().firstOrNull { it.id == id }

    fun remove(id: String): Boolean {
        live(id) ?: return false
        replica.commitLocal(EntityTypes.OCCASION, id, mapOf(OccasionFields.DELETED to true.fv()))
        return true
    }

    /** Notes a gift idea against [occasionId]; false when it's empty, the occasion is gone or it already has [GiftRules.MAX_IDEAS]. */
    fun addIdea(occasionId: String, text: String): Boolean {
        val o = live(occasionId) ?: return false
        val t = text.trim().replace('\n', ' ').take(GiftRules.MAX_IDEA).trim()
        if (t.isEmpty() || o.ideas.size >= GiftRules.MAX_IDEAS) return false
        if (o.ideas.any { it.text.equals(t, ignoreCase = true) }) return true
        replica.commitLocal(EntityTypes.GIFT_IDEA, newId(), mapOf(
            GiftIdeaFields.OCCASION to occasionId.fv(),
            GiftIdeaFields.TEXT to t.fv(),
            GiftIdeaFields.ADDED_AT to nowMs().fv(),
            GiftIdeaFields.DELETED to false.fv(),
        ))
        return true
    }

    /**
     * Slice 2: the Ask card's add, the new idea's id; null when the occasion is gone, full, or the idea is already
     * noted (so Undo never removes one Meka typed himself).
     */
    fun addIdeaFromAsk(occasionId: String, text: String): String? {
        val o = live(occasionId) ?: return null
        val t = text.trim().replace('\n', ' ').take(GiftRules.MAX_IDEA).trim()
        if (t.isEmpty() || o.ideas.size >= GiftRules.MAX_IDEAS || o.ideas.any { it.text.equals(t, ignoreCase = true) }) return null
        val id = newId()
        replica.commitLocal(EntityTypes.GIFT_IDEA, id, mapOf(
            GiftIdeaFields.OCCASION to occasionId.fv(),
            GiftIdeaFields.TEXT to t.fv(),
            GiftIdeaFields.ADDED_AT to nowMs().fv(),
            GiftIdeaFields.DELETED to false.fv(),
        ))
        return id
    }

    fun removeIdea(id: String): Boolean {
        replica.entity(EntityTypes.GIFT_IDEA, id)?.takeIf { it[GiftIdeaFields.DELETED].boolOrNull != true } ?: return false
        replica.commitLocal(EntityTypes.GIFT_IDEA, id, mapOf(GiftIdeaFields.DELETED to true.fv()))
        return true
    }

    /** Marks the gift for the next occurrence sorted (or not); the reminders for it stop. */
    fun setSorted(id: String, sorted: Boolean): Boolean {
        val o = live(id) ?: return false
        val day = GiftRules.next(o, today())
        replica.commitLocal(EntityTypes.OCCASION, id, mapOf(OccasionFields.SORTED_FOR to (if (sorted) day else 0L).fv()))
        return true
    }
}
