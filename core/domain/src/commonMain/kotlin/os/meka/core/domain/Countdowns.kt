package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Countdowns (V1, approved 2026-11-11, `docs/design/countdowns/Countdowns.dc.html`), slice 1: the days (or, for the
 * children, the sleeps) to a holiday, a birthday, an anniversary or Christmas. The next one sits large with a ring and
 * its number; the rest are two-column cards, soonest first; a tap shows one full screen for the kids.
 *
 * Meka's own countdowns are typed once ("Holiday 13 Feb", "Sports day 3 July 2027", "Pancake day 17 Feb every year");
 * the birthdays and anniversaries in Gifts, the trips still ahead in Trips and Christmas appear by themselves (an
 * automatic one can be hidden, and shown again). One countdown can be chosen to show on Today. Plain, non-AI rules.
 *
 * One synced `countdown` entity per countdown typed, and one `countdown_setting` holding the choice for Today, Days or
 * Sleeps and the hidden automatic ones (ADR-008 addendum 2026-11-12).
 */
object CountdownFields {
    /** "Holiday", "Sports day" (at most [CountdownRules.MAX_NAME]). */
    const val NAME = "name"
    const val MONTH = "month"
    const val DAY = "day"
    /** The year it falls in; 0 when it comes round every year. */
    const val YEAR = "year"
    const val ADDED_AT = "addedAtMs"
    const val DELETED = ActionableFields.DELETED
}

object CountdownSettingFields {
    /** The one shown on Today ([CountdownRow.key]); "" for none. */
    const val PINNED = "pinnedKey"
    /** True: counted in sleeps (the children's way), else days. */
    const val SLEEPS = "sleeps"
    /** The automatic ones hidden, one key a line. */
    const val HIDDEN = "hiddenKeys"
}

enum class CountdownIcon { STAR, GIFT, CAKE, PLANE, HEART }

/** Where a countdown comes from: typed in Countdowns, a birthday or anniversary in Gifts, a trip, or Christmas. */
/** [SCHOOL]: the boys' next school holiday from School (slice 3). */
enum class CountdownSource { OWN, GIFTS, TRIP, CHRISTMAS, SCHOOL }

/** One typed countdown as stored. */
data class CountdownItem(
    val id: String, val name: String, val month: Int, val day: Int, val year: Int, val addedAtMs: Long,
) {
    val yearly: Boolean get() = year == 0
}

data class CountdownSettings(val pinnedKey: String = "", val sleeps: Boolean = false, val hidden: Set<String> = emptySet()) {
    companion object { val DEFAULT = CountdownSettings() }
}

/** What one typed line says ([CountdownRules.read]); [year] 0 for every year. */
data class CountdownEntry(val name: String, val month: Int, val day: Int, val year: Int)

/**
 * A countdown on the screen: "Christmas" · "Fri 25 Dec" · [number] "43" · [unit] "days" (or "sleeps"; "Today" and
 * "" on the day); [fill] how much of the ring is drawn (0..1, fuller as it gets closer); [extra] a short line under the
 * date ("turns 9", "4 nights"); [canRemove] for one typed here (else it can only be hidden).
 */
data class CountdownRow(
    val key: String,
    val name: String,
    val dateLine: String,
    val days: Long,
    val number: String,
    val unit: String,
    val fill: Double,
    val icon: CountdownIcon,
    val source: CountdownSource,
    val extra: String?,
    val canRemove: Boolean,
    val spoken: String,
)

/**
 * The Countdowns screen: [hero] (the one on Today, else the soonest) and the rest as cards, soonest first; [sleeps]
 * the Days · Sleeps switch; [heroOnToday] whether the hero is the one chosen for Today; [hiddenCount] automatic ones
 * hidden (a "Show hidden" offers them back); [tileLine] Ask's Life tile ("Christmas in 43 days").
 */
data class CountdownsView(
    val hero: CountdownRow?,
    val rows: List<CountdownRow>,
    val sleeps: Boolean,
    val heroOnToday: Boolean,
    val hiddenCount: Int,
    val tileLine: LifeTileLine?,
) {
    val isEmpty: Boolean get() = hero == null

    companion object { val EMPTY = CountdownsView(null, emptyList(), sleeps = false, heroOnToday = false, hiddenCount = 0, tileLine = null) }
}

data class CountdownAdded(val line: String, val refused: Boolean)

object CountdownRules {
    const val TITLE = "Countdowns"
    const val ROW_LINE = "Days to go to holidays, birthdays and Christmas"
    const val MAX_NAME = 40
    const val MAX_OWN = 40
    const val CHRISTMAS_KEY = "christmas"
    /** A countdown further off than this isn't drawn (a typed date years ahead). */
    const val MAX_AHEAD_DAYS = 3 * 366L

    /** One example on one line; TalkBack and VoiceOver read [ADD_DESCRIPTION]. */
    const val ADD_HINT = "Holiday 13 Feb"
    const val ADD_DESCRIPTION = "Add a countdown, for example Holiday 13 Feb, or Pancake day 17 Feb every year"
    const val NOT_READ = "Navilife couldn't read a date in that. Try \"Holiday 13 Feb\" or \"Sports day 3 July 2027\"."
    const val NO_NAME = "Give it a name as well, like \"Holiday 13 Feb\"."
    const val PASSED = "That date has already passed."
    const val TOO_MANY = "That's as many countdowns as Navilife keeps. Remove one first."
    const val NOTE = "Tap one to show it big for the kids. Birthdays and anniversaries in Gifts, trips, the next school holiday and Christmas appear here on their own. Type \"every year\" for one that comes round each year."

    private val YEARLY_WORDS = setOf("every", "yearly", "annually", "annual", "each")
    private val FILLER = setOf(
        "on", "the", "is", "at", "in", "of", "until", "till", "til", "to", "for", "every", "yearly", "annually", "annual",
        "each", "year", "countdown", "from", "and",
    )
    private val ORDINALS = setOf("st", "nd", "rd", "th")

    private fun monthOf(s: String): Int? {
        val l = s.lowercase()
        if (l.length < 3) return null
        val m = when (l.take(3)) {
            "jan" -> 1; "feb" -> 2; "mar" -> 3; "apr" -> 4; "may" -> 5; "jun" -> 6
            "jul" -> 7; "aug" -> 8; "sep" -> 9; "oct" -> 10; "nov" -> 11; "dec" -> 12
            else -> return null
        }
        val full = CivilDate.MONTH_LONG[m - 1].lowercase()
        return m.takeIf { full.startsWith(l) || l == "sept" }
    }

    /** Words by hand (letters, digits, apostrophes and slashes kept), so Kotlin/Native's regexes can't read them differently. */
    private fun words(text: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (c in text) {
            if (c.isLetterOrDigit() || c == '\'' || c == '’' || c == '/' || c == '-') sb.append(c) else if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out.map { it.trim('-') }.filter { it.isNotEmpty() }
    }

    private fun dayNumber(w: String): Int? {
        val l = w.lowercase()
        val digits = l.takeWhile { it.isDigit() }
        if (digits.isEmpty() || digits.length > 2) return null
        if (l.substring(digits.length).let { it.isNotEmpty() && it !in ORDINALS }) return null
        return digits.toInt().takeIf { it in 1..31 }
    }

    private fun lengthOf(month: Int, year: Int): Int = if (year == 0) (if (month == 2) 29 else CivilDate.lengthOfMonth(2001, month)) else CivilDate.lengthOfMonth(year, month)

    /**
     * What one typed line says, or null when there's no day and month in it. "Holiday 13 Feb", "13th February:
     * holiday", "Sports day 3/7/2027", "Pancake day 17 Feb every year". With no year it's the next time that date
     * comes ([year] filled in) unless "every year" (or yearly, annually) says it repeats ([year] 0). The name may be ""
     * (the caller refuses it).
     */
    fun read(text: String, today: Long): CountdownEntry? {
        val w = words(text.trim())
        if (w.isEmpty()) return null
        val lower = w.map { it.lowercase() }
        val used = BooleanArray(w.size)
        var month = 0
        var day = 0
        var year = 0
        val thisYear = CivilDate.fromEpochDay(today).year
        for (i in w.indices) {
            if ('/' !in w[i]) continue
            val parts = w[i].split('/')
            if (parts.size !in 2..3 || parts.any { p -> p.isEmpty() || !p.all(Char::isDigit) }) return null
            val d = parts[0].toInt()
            val m = parts[1].toInt()
            if (m !in 1..12 || d !in 1..31) return null
            month = m; day = d; used[i] = true
            if (parts.size == 3) year = parts[2].toInt().let { if (parts[2].length == 2) 2000 + it else it }
            break
        }
        if (month == 0) {
            for (i in w.indices) {
                val m = monthOf(w[i]) ?: continue
                val before = if (i > 0 && !used[i - 1]) dayNumber(w[i - 1]) else null
                val after = if (i + 1 < w.size) dayNumber(w[i + 1]) else null
                val d = before ?: after ?: continue
                month = m; day = d; used[i] = true
                if (before != null) used[i - 1] = true else used[i + 1] = true
                break
            }
        }
        if (month == 0) return null
        if (year == 0) {
            for (i in w.indices) {
                if (used[i]) continue
                val y = w[i].takeIf { it.length == 4 && it.all(Char::isDigit) }?.toInt() ?: continue
                if (y in thisYear..thisYear + 5) { year = y; used[i] = true; break }
            }
        }
        val yearly = lower.any { it in YEARLY_WORDS }
        if (year != 0 && year !in thisYear - 1..thisYear + 5) return null
        if (day > lengthOf(month, if (yearly) 0 else year)) return null
        // The name is what's left, with filler trimmed off either end ("Back to school" keeps its "to").
        val left = w.indices.filter { i -> !used[i] && !lower[i].all(Char::isDigit) }.toMutableList()
        while (left.isNotEmpty() && lower[left.first()] in FILLER) left.removeAt(0)
        while (left.isNotEmpty() && lower[left.last()] in FILLER) left.removeAt(left.size - 1)
        val nameWords = left.filter { !(lower[it] in YEARLY_WORDS) }.map { w[it] }
        val name = nameWords.joinToString(" ").take(MAX_NAME).trim().replaceFirstChar { it.uppercase() }
        if (yearly) return CountdownEntry(name, month, day, 0)
        if (year == 0) {
            val thisOne = CivilDate.toEpochDay(thisYear, month, minOf(day, CivilDate.lengthOfMonth(thisYear, month)))
            year = if (thisOne >= today) thisYear else thisYear + 1
            if (day > CivilDate.lengthOfMonth(year, month)) return null
        }
        return CountdownEntry(name, month, day, year)
    }

    /** The day a typed countdown next falls on (today counting); 29 Feb is the 28th in other years. */
    fun next(c: CountdownItem, today: Long): Long {
        if (!c.yearly) return CivilDate.toEpochDay(c.year, c.month, minOf(c.day, CivilDate.lengthOfMonth(c.year, c.month)))
        return yearlyNext(c.month, c.day, today)
    }

    private fun yearlyNext(month: Int, day: Int, today: Long): Long {
        val y = CivilDate.fromEpochDay(today).year
        fun inYear(yy: Int) = CivilDate.toEpochDay(yy, month, minOf(day, CivilDate.lengthOfMonth(yy, month)))
        val d = inYear(y)
        return if (d >= today) d else inYear(y + 1)
    }

    /** The icon a name suggests: Christmas a present, a birthday a cake, an anniversary a heart, a holiday a plane. */
    fun iconFor(name: String): CountdownIcon {
        val l = words(name).map { it.lowercase() }
        return when {
            l.any { it.startsWith("christmas") || it == "xmas" || it == "eid" || it == "diwali" || it == "hanukkah" } -> CountdownIcon.GIFT
            l.any { it.startsWith("birthday") || it == "bday" || it == "party" } -> CountdownIcon.CAKE
            l.any { it.startsWith("anniversary") || it == "wedding" || it.startsWith("valentine") } -> CountdownIcon.HEART
            l.any { it.startsWith("holiday") || it == "trip" || it == "flight" || it == "flights" || it == "vacation" || it == "break" } -> CountdownIcon.PLANE
            else -> CountdownIcon.STAR
        }
    }

    private fun Map<String, FieldValue>.f(field: String): FieldValue = this[field] ?: FieldValue.Null

    fun items(entities: Map<String, Map<String, FieldValue>>): List<CountdownItem> = entities.mapNotNull { (id, s) ->
        if (s.f(CountdownFields.DELETED).boolOrNull == true) return@mapNotNull null
        val name = s.f(CountdownFields.NAME).textOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val month = (s.f(CountdownFields.MONTH).longOrNull ?: return@mapNotNull null).toInt()
        val day = (s.f(CountdownFields.DAY).longOrNull ?: return@mapNotNull null).toInt()
        val year = (s.f(CountdownFields.YEAR).longOrNull ?: 0L).toInt()
        if (month !in 1..12 || day !in 1..lengthOf(month, 0)) return@mapNotNull null
        CountdownItem(id, name.take(MAX_NAME), month, day, year, s.f(CountdownFields.ADDED_AT).longOrNull ?: 0L)
    }.sortedWith(compareBy<CountdownItem> { it.addedAtMs }.thenBy { it.id })

    fun settings(fields: Map<String, FieldValue>?): CountdownSettings {
        if (fields == null) return CountdownSettings.DEFAULT
        return CountdownSettings(
            pinnedKey = fields.f(CountdownSettingFields.PINNED).textOrNull ?: "",
            sleeps = fields.f(CountdownSettingFields.SLEEPS).boolOrNull ?: false,
            hidden = (fields.f(CountdownSettingFields.HIDDEN).textOrNull ?: "").split('\n').filter { it.isNotBlank() }.toSet(),
        )
    }

    fun fields(e: CountdownEntry, nowMs: Long): Map<String, FieldValue> = mapOf(
        CountdownFields.NAME to e.name.fv(),
        CountdownFields.MONTH to e.month.toLong().fv(),
        CountdownFields.DAY to e.day.toLong().fv(),
        CountdownFields.YEAR to e.year.toLong().fv(),
        CountdownFields.ADDED_AT to nowMs.fv(),
        CountdownFields.DELETED to false.fv(),
    )

    fun ownKey(id: String) = "c:$id"
    fun giftKey(id: String) = "g:$id"
    fun tripKey(id: String) = "t:$id"
    fun schoolKey(id: String) = "s:$id"

    /** A day off this long or longer (first to last day) is a holiday the children count down to; an INSET day isn't. */
    const val SCHOOL_HOLIDAY_MIN_DAYS = 3

    /**
     * Countdowns, slice 3: the boys' next school holiday from School, so they can count the sleeps to breaking up.
     * The next day off of at least [SCHOOL_HOLIDAY_MIN_DAYS] days that hasn't started yet (today counts), for both boys
     * if there is one that soon, else one boy's (named under it). Counted to its first day off; [CountdownRow.extra]
     * "breaks up Fri 18 Dec" (the last weekday before it), with "· <name> only" for one boy. Null for none.
     */
    fun nextSchoolHoliday(school: List<SchoolItem>, today: Long): SchoolItem? = school
        .filter { it.kind == SchoolKind.OFF && it.endDay - it.startDay + 1 >= SCHOOL_HOLIDAY_MIN_DAYS && it.startDay >= today && it.startDay - today <= MAX_AHEAD_DAYS }
        .sortedWith(compareBy<SchoolItem> { it.startDay }.thenBy { it.who.isNotEmpty() }.thenBy { it.id })
        .firstOrNull()

    /** The last weekday before [startDay]: "breaks up" (a holiday from Monday breaks up the Friday before). */
    fun breaksUpDay(startDay: Long): Long {
        var d = startDay - 1
        while (CivilDate.isoDayOfWeek(d) > 5) d--
        return d
    }

    private fun schoolExtra(s: SchoolItem, today: Long): String? {
        val up = breaksUpDay(s.startDay)
        val first = if (up >= today) "breaks up ${CivilDate.shortLabel(up)}" else null
        val whose = s.who.takeIf { it.isNotEmpty() }?.let { "$it only" }
        return listOfNotNull(first, whose).joinToString(" · ").ifEmpty { null }
    }

    /** "day"/"days", or "sleep"/"sleeps". */
    fun unitWord(n: Long, sleeps: Boolean): String = when {
        sleeps -> if (n == 1L) "sleep" else "sleeps"
        else -> if (n == 1L) "day" else "days"
    }

    private data class Found(
        val key: String, val name: String, val day: Long, val span: Long, val icon: CountdownIcon,
        val source: CountdownSource, val extra: String?,
    )

    private fun row(f: Found, today: Long, sleeps: Boolean): CountdownRow {
        val n = f.day - today
        val number = if (n == 0L) "Today" else n.toString()
        val unit = if (n == 0L) "" else unitWord(n, sleeps)
        val fill = if (n <= 0L) 1.0 else (1.0 - n.toDouble() / maxOf(f.span, n, 1L).toDouble()).coerceIn(0.0, 1.0)
        val dateLine = CivilDate.shortLabel(f.day) + (CivilDate.fromEpochDay(f.day).year.takeIf { it > CivilDate.fromEpochDay(today).year && n > 300 }?.let { " $it" } ?: "")
        val spokenCount = when (n) {
            0L -> "today"
            else -> "$n ${unitWord(n, sleeps)} to go"
        }
        return CountdownRow(
            key = f.key, name = f.name, dateLine = dateLine, days = n, number = number, unit = unit, fill = fill,
            icon = f.icon, source = f.source, extra = f.extra, canRemove = f.source == CountdownSource.OWN,
            spoken = listOfNotNull(f.name, spokenCount, dateLine, f.extra).joinToString(". "),
        )
    }

    /** Everything counted down to from [today], soonest first (hidden automatic ones left out). */
    private fun found(
        own: List<CountdownItem>, occasions: List<Occasion>, trips: List<TripItem>, settings: CountdownSettings, today: Long,
        school: List<SchoolItem>,
    ): List<Found> {
        val out = mutableListOf<Found>()
        nextSchoolHoliday(school, today)?.let { s ->
            val key = schoolKey(s.id)
            if (key !in settings.hidden) {
                val span = maxOf(1L, s.startDay - (if (s.addedAtMs > 0) s.addedAtMs.floorDiv(CivilDate.DAY_MS) else today), 42L)
                out += Found(key, s.title, s.startDay, span, iconFor(s.title), CountdownSource.SCHOOL, schoolExtra(s, today))
            }
        }
        for (c in own) {
            val d = next(c, today)
            if (d < today || d - today > MAX_AHEAD_DAYS) continue
            val span = if (c.yearly) 365L else maxOf(1L, d - (if (c.addedAtMs > 0) c.addedAtMs.floorDiv(CivilDate.DAY_MS) else today))
            out += Found(ownKey(c.id), c.name, d, span, iconFor(c.name), CountdownSource.OWN, null)
        }
        for (o in occasions) {
            val key = giftKey(o.id)
            if (key in settings.hidden) continue
            val d = GiftRules.next(o, today)
            out += Found(
                key, GiftRules.title(o), d, 365L,
                if (o.kind == OccasionKind.ANNIVERSARY) CountdownIcon.HEART else CountdownIcon.CAKE,
                CountdownSource.GIFTS, GiftRules.age(o, d),
            )
        }
        for (t in trips) {
            val key = tripKey(t.id)
            if (key in settings.hidden || t.startDay < today || t.startDay - today > MAX_AHEAD_DAYS) continue
            val nights = t.endDay - t.startDay
            val span = maxOf(1L, t.startDay - (if (t.addedAtMs > 0) t.addedAtMs.floorDiv(CivilDate.DAY_MS) else today))
            out += Found(key, t.name, t.startDay, span, CountdownIcon.PLANE, CountdownSource.TRIP,
                if (nights <= 0) "a day" else if (nights == 1L) "1 night" else "$nights nights")
        }
        if (CHRISTMAS_KEY !in settings.hidden && own.none { it.name.equals("Christmas", ignoreCase = true) || it.name.equals("Christmas day", ignoreCase = true) }) {
            out += Found(CHRISTMAS_KEY, "Christmas", yearlyNext(12, 25, today), 365L, CountdownIcon.GIFT, CountdownSource.CHRISTMAS, null)
        }
        return out.sortedWith(compareBy<Found> { it.day }.thenBy { it.name.lowercase() }.thenBy { it.key })
    }

    /** The automatic ones that are hidden and would still show (so "Show hidden" only appears when it brings something back). */
    private fun hiddenCount(occasions: List<Occasion>, trips: List<TripItem>, settings: CountdownSettings, today: Long, school: List<SchoolItem>): Int =
        settings.hidden.count { k ->
            when {
                k == CHRISTMAS_KEY -> true
                k.startsWith("g:") -> occasions.any { giftKey(it.id) == k }
                k.startsWith("t:") -> trips.any { tripKey(it.id) == k && it.startDay >= today }
                k.startsWith("s:") -> nextSchoolHoliday(school, today)?.let { schoolKey(it.id) == k } == true
                else -> false
            }
        }

    /** The screen from what's typed, Gifts, Trips and School, the settings and [today]. */
    fun view(
        own: List<CountdownItem>, occasions: List<Occasion>, trips: List<TripItem>, settings: CountdownSettings, today: Long,
        school: List<SchoolItem> = emptyList(),
    ): CountdownsView {
        val all = found(own, occasions, trips, settings, today, school).map { row(it, today, settings.sleeps) }
        val hidden = hiddenCount(occasions, trips, settings, today, school)
        if (all.isEmpty()) return CountdownsView.EMPTY.copy(sleeps = settings.sleeps, hiddenCount = hidden)
        val pinned = all.firstOrNull { it.key == settings.pinnedKey }
        val hero = pinned ?: all.first()
        return CountdownsView(
            hero = hero, rows = all.filter { it.key != hero.key }, sleeps = settings.sleeps, heroOnToday = pinned != null,
            hiddenCount = hidden, tileLine = tileLine(hero, settings.sleeps),
        )
    }

    /** Ask's Life tile: "Christmas in 43 days", "Holiday tomorrow", "Christmas today" (lit on the day). */
    fun tileLine(r: CountdownRow, sleeps: Boolean): LifeTileLine = when (r.days) {
        0L -> LifeTileLine("${r.name} today", lit = true)
        1L -> LifeTileLine(if (sleeps) "${r.name} · 1 sleep" else "${r.name} tomorrow", lit = false)
        else -> LifeTileLine("${r.name} in ${r.days} ${unitWord(r.days, sleeps)}", lit = false)
    }

    /** What a tap on the Today tile does, said after it. */
    const val OPENS = "Opens Countdowns."

    /**
     * Countdowns, slice 2: the one he chose with Show on Today as an Also today tile: "Christmas" · "43 days to go ·
     * Fri 25 Dec" ("43 sleeps to go" with Sleeps on; "tomorrow" or "1 sleep to go" the day before; "today", lit, on the
     * day; a birthday's "turns 9" after the date). Null when none is chosen (the soonest only leads the Countdowns
     * screen; Today shows a countdown only when he asked for it) or the chosen one has passed.
     */
    fun alsoTile(v: CountdownsView): AlsoTile? {
        val h = v.hero?.takeIf { v.heroOnToday } ?: return null
        val count = when {
            h.days == 0L -> "today"
            h.days == 1L && !v.sleeps -> "tomorrow"
            else -> "${h.days} ${unitWord(h.days, v.sleeps)} to go"
        }
        val line = listOfNotNull(count, h.dateLine.takeIf { h.days != 0L }, h.extra).joinToString(" · ")
        val spoken = "Countdown: ${h.name}, " + (if (h.days == 0L) "today" else "$count, ${h.dateLine}") +
            (h.extra?.let { ", $it" } ?: "") + ". $OPENS"
        return AlsoTile(AlsoKind.COUNTDOWN, AlsoTodayRules.key(AlsoKind.COUNTDOWN), h.name, line, spoken, lit = h.days == 0L, targetId = h.key)
    }

    /** "Added Holiday · Sat 13 Feb · 93 days to go", "… · every year". */
    fun addedLine(c: CountdownItem, today: Long, replaced: Boolean): String {
        val d = next(c, today)
        val n = d - today
        return listOfNotNull(
            "${if (replaced) "Updated" else "Added"} ${c.name}",
            CivilDate.shortLabel(d),
            if (n == 0L) "today" else "$n ${unitWord(n, false)} to go",
            if (c.yearly) "every year" else null,
        ).joinToString(" · ")
    }
}

/** The countdowns typed on a replica, and the Countdowns setting. */
class Countdowns(
    private val replica: Replica,
    private val newId: () -> String,
    private val nowMs: () -> Long,
    private val calendar: LocalCalendar = LocalCalendar.UTC,
) {
    private fun today() = calendar.epochDayOf(nowMs())

    fun items(): List<CountdownItem> = CountdownRules.items(replica.entities(EntityTypes.COUNTDOWN).associate { it.ref.entityId to it.fields })

    fun settings(): CountdownSettings = CountdownRules.settings(replica.entity(EntityTypes.COUNTDOWN_SETTING, SETTING_ID)?.fields)

    /** Adds (or, for the same name, updates) what [text] says; refused with a reason when it can't. */
    fun add(text: String): CountdownAdded {
        val today = today()
        val e = CountdownRules.read(text, today) ?: return CountdownAdded(CountdownRules.NOT_READ, refused = true)
        if (e.name.isEmpty()) return CountdownAdded(CountdownRules.NO_NAME, refused = true)
        val items = items()
        val existing = items.firstOrNull { it.name.equals(e.name, ignoreCase = true) }
        if (existing == null && items.size >= CountdownRules.MAX_OWN) return CountdownAdded(CountdownRules.TOO_MANY, refused = true)
        if (e.year != 0 && CivilDate.toEpochDay(e.year, e.month, minOf(e.day, CivilDate.lengthOfMonth(e.year, e.month))) < today) {
            return CountdownAdded(CountdownRules.PASSED, refused = true)
        }
        val id = existing?.id ?: newId()
        var fields = CountdownRules.fields(e, nowMs())
        if (existing != null) fields = fields - CountdownFields.ADDED_AT
        replica.commitLocal(EntityTypes.COUNTDOWN, id, fields)
        val saved = items().first { it.id == id }
        return CountdownAdded(CountdownRules.addedLine(saved, today, replaced = existing != null), refused = false)
    }

    /**
     * Removes one typed here for good, or hides an automatic one (a birthday, a trip, Christmas); false when [key]
     * names nothing. Either way it stops being the one on Today.
     */
    fun remove(key: String): Boolean {
        val s = settings()
        val unpin = if (s.pinnedKey == key) mapOf(CountdownSettingFields.PINNED to "".fv()) else emptyMap()
        if (key.startsWith("c:")) {
            val id = key.removePrefix("c:")
            if (items().none { it.id == id }) return false
            replica.commitLocal(EntityTypes.COUNTDOWN, id, mapOf(CountdownFields.DELETED to true.fv()))
            if (unpin.isNotEmpty()) replica.commitLocal(EntityTypes.COUNTDOWN_SETTING, SETTING_ID, unpin)
            return true
        }
        if (key != CountdownRules.CHRISTMAS_KEY && !key.startsWith("g:") && !key.startsWith("t:") && !key.startsWith("s:")) return false
        if (key in s.hidden) return false
        replica.commitLocal(EntityTypes.COUNTDOWN_SETTING, SETTING_ID, unpin + mapOf(
            CountdownSettingFields.HIDDEN to (s.hidden + key).sorted().joinToString("\n").fv(),
        ))
        return true
    }

    /** Shows every hidden automatic countdown again; false when none was hidden. */
    fun showHidden(): Boolean {
        if (settings().hidden.isEmpty()) return false
        replica.commitLocal(EntityTypes.COUNTDOWN_SETTING, SETTING_ID, mapOf(CountdownSettingFields.HIDDEN to "".fv()))
        return true
    }

    /** Makes [key] the one on Today ("" for none). */
    fun pin(key: String): Boolean {
        if (settings().pinnedKey == key) return false
        replica.commitLocal(EntityTypes.COUNTDOWN_SETTING, SETTING_ID, mapOf(CountdownSettingFields.PINNED to key.fv()))
        return true
    }

    /** Counts in sleeps (true) or days. */
    fun setSleeps(sleeps: Boolean): Boolean {
        if (settings().sleeps == sleeps) return false
        replica.commitLocal(EntityTypes.COUNTDOWN_SETTING, SETTING_ID, mapOf(CountdownSettingFields.SLEEPS to sleeps.fv()))
        return true
    }

    companion object { const val SETTING_ID = "countdowns" }
}
