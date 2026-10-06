package os.meka.core.domain

/**
 * Repeating tasks and routines (build plan M1).
 *
 * A repeating task is a *series* of occurrences. Each occurrence is an ordinary task carrying the rule
 * ([TaskFields.RECURRENCE]), the series id and the local day it belongs to ([TaskFields.OCCURRENCE_DAY], an epoch day).
 * Completing or skipping an occurrence creates the next one with a deterministic id (series + day), so two devices
 * that complete the same occurrence while offline converge on one next occurrence instead of two.
 *
 * Rules are stored as a small, standard subset of iCalendar RRULE, so a newer rule written by a later app version is
 * kept as text even when this version can't read it.
 */
sealed class Recurrence {
    abstract val interval: Int

    data class Daily(override val interval: Int = 1) : Recurrence() {
        init { checkInterval(interval) }
    }

    /** [days] are ISO (1 = Monday). "Every 2nd Tuesday" in the every-other-week sense is `Weekly(2, {2})`. */
    data class Weekly(override val interval: Int, val days: Set<Int>) : Recurrence() {
        init {
            checkInterval(interval)
            require(days.isNotEmpty() && days.all { it in 1..7 }) { "days are ISO 1..7" }
        }
    }

    /** On day [day] of the month; months without it use their last day (the 31st becomes the 30th in November). */
    data class MonthlyOnDay(override val interval: Int, val day: Int) : Recurrence() {
        init {
            checkInterval(interval)
            require(day in 1..31) { "day must be 1..31" }
        }
    }

    /** On the [ordinal] (1–4, or -1 = last) [weekday] of the month: "the 2nd Tuesday", "the last Friday". */
    data class MonthlyOnWeekday(override val interval: Int, val ordinal: Int, val weekday: Int) : Recurrence() {
        init {
            checkInterval(interval)
            require(ordinal in ORDINALS && weekday in 1..7) { "ordinal must be 1..4 or -1; weekday ISO 1..7" }
        }
    }

    /** On [day] [month] each year; 29 February falls on the 28th in other years. */
    data class Yearly(override val interval: Int, val month: Int, val day: Int) : Recurrence() {
        init {
            checkInterval(interval)
            require(month in 1..12 && day in 1..CivilDate.lengthOfMonth(2000, month)) { "no such date" }
        }
    }

    fun encode(): String = when (this) {
        is Daily -> "FREQ=DAILY;INTERVAL=$interval"
        is Weekly -> "FREQ=WEEKLY;INTERVAL=$interval;BYDAY=${days.sorted().joinToString(",") { DAY_CODES[it - 1] }}"
        is MonthlyOnDay -> "FREQ=MONTHLY;INTERVAL=$interval;BYMONTHDAY=$day"
        is MonthlyOnWeekday -> "FREQ=MONTHLY;INTERVAL=$interval;BYDAY=$ordinal${DAY_CODES[weekday - 1]}"
        is Yearly -> "FREQ=YEARLY;INTERVAL=$interval;BYMONTH=$month;BYMONTHDAY=$day"
    }

    /** "Every day", "Every weekday", "Every 2 weeks on Tue", "Monthly on the 2nd Tue", "Every year on 14 Mar". */
    fun describe(): String = when (this) {
        is Daily -> if (interval == 1) "Every day" else "Every $interval days"
        is Weekly -> when {
            interval == 1 && days.size == 7 -> "Every day"
            interval == 1 && days == WEEKDAYS -> "Every weekday"
            interval == 1 -> "Every ${dayList(days)}"
            else -> "Every $interval weeks on ${dayList(days)}"
        }
        is MonthlyOnDay -> (if (interval == 1) "Monthly" else "Every $interval months") + " on the ${ordinalWord(day)}"
        is MonthlyOnWeekday -> (if (interval == 1) "Monthly" else "Every $interval months") +
            " on the ${if (ordinal == -1) "last" else ordinalWord(ordinal)} ${LocalClock.DAY_SHORT[weekday - 1]}"
        is Yearly -> (if (interval == 1) "Every year" else "Every $interval years") + " on $day ${MONTH_SHORT[month - 1]}"
    }

    /** Whether [epochDay] could be an occurrence of this rule, ignoring the interval (which depends on the series start). */
    fun matchesPattern(epochDay: Long): Boolean {
        val d = CivilDate.fromEpochDay(epochDay)
        return when (this) {
            is Daily -> true
            is Weekly -> CivilDate.isoDayOfWeek(epochDay) in days
            is MonthlyOnDay -> d.day == minOf(day, CivilDate.lengthOfMonth(d.year, d.month))
            is MonthlyOnWeekday -> epochDay == nthWeekday(d.year, d.month)
            is Yearly -> d.month == month && d.day == minOf(day, CivilDate.lengthOfMonth(d.year, month))
        }
    }

    /** The first occurrence on or after [epochDay]; the series counts its interval from there. */
    fun firstOnOrAfter(epochDay: Long): Long {
        var day = epochDay
        // Every rule has an occurrence within about four years (29 Feb rules are clamped), so this is bounded.
        repeat(MAX_SCAN_DAYS) { if (matchesPattern(day)) return day; day++ }
        error("no occurrence found for ${encode()}")
    }

    /** The occurrence after [occurrence], which must itself be an occurrence of this series. Always > [occurrence]. */
    fun next(occurrence: Long): Long = when (this) {
        is Daily -> occurrence + interval
        is Weekly -> {
            val dow = CivilDate.isoDayOfWeek(occurrence)
            val weekStart = occurrence - (dow - 1)
            val later = days.filter { it > dow }.minOrNull()
            if (later != null) weekStart + (later - 1) else weekStart + 7L * interval + (days.min() - 1)
        }
        is MonthlyOnDay -> {
            val (y, m) = addMonths(occurrence, interval)
            CivilDate.toEpochDay(y, m, minOf(day, CivilDate.lengthOfMonth(y, m)))
        }
        is MonthlyOnWeekday -> {
            val (y, m) = addMonths(occurrence, interval)
            nthWeekday(y, m)
        }
        is Yearly -> {
            val y = CivilDate.fromEpochDay(occurrence).year + interval
            CivilDate.toEpochDay(y, month, minOf(day, CivilDate.lengthOfMonth(y, month)))
        }
    }

    private fun addMonths(epochDay: Long, months: Int): Pair<Int, Int> {
        val d = CivilDate.fromEpochDay(epochDay)
        val index = d.year * 12 + (d.month - 1) + months
        return Pair(index.floorDiv(12), index.mod(12) + 1)
    }

    private fun MonthlyOnWeekday.nthWeekday(year: Int, month: Int): Long {
        val first = CivilDate.toEpochDay(year, month, 1)
        val firstMatch = first + (weekday - CivilDate.isoDayOfWeek(first)).mod(7)
        if (ordinal > 0) return firstMatch + 7L * (ordinal - 1)
        val last = CivilDate.toEpochDay(year, month, CivilDate.lengthOfMonth(year, month))
        return last - (CivilDate.isoDayOfWeek(last) - weekday).mod(7)
    }

    companion object {
        const val MAX_INTERVAL = 99
        private const val MAX_SCAN_DAYS = 5 * 366
        private val DAY_CODES = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
        val MONTH_SHORT = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val WEEKDAYS = setOf(1, 2, 3, 4, 5)

        /** Parses the subset written by [encode]; anything else (a newer rule, a typo) is null and left untouched. */
        fun decode(rule: String?): Recurrence? {
            if (rule.isNullOrBlank()) return null
            val parts = rule.split(';').mapNotNull { p ->
                val i = p.indexOf('=')
                if (i <= 0) null else p.substring(0, i).trim().uppercase() to p.substring(i + 1).trim().uppercase()
            }.toMap()
            return try {
                val interval = parts["INTERVAL"]?.toInt() ?: 1
                val known = setOf("FREQ", "INTERVAL", "BYDAY", "BYMONTHDAY", "BYMONTH")
                if (parts.keys.any { it !in known }) return null
                when (parts["FREQ"]) {
                    "DAILY" -> if (parts.keys == setOf("FREQ", "INTERVAL") || parts.keys == setOf("FREQ")) Daily(interval) else null
                    "WEEKLY" -> {
                        val days = parts["BYDAY"]?.split(',')?.map { code -> DAY_CODES.indexOf(code).also { require(it >= 0) } + 1 }?.toSet()
                        if (days.isNullOrEmpty() || "BYMONTHDAY" in parts || "BYMONTH" in parts) null else Weekly(interval, days)
                    }
                    "MONTHLY" -> {
                        val byDay = parts["BYDAY"]
                        val byMonthDay = parts["BYMONTHDAY"]
                        when {
                            "BYMONTH" in parts -> null
                            byDay != null && byMonthDay == null -> {
                                val code = byDay.takeLast(2)
                                val ordinal = byDay.dropLast(2).toInt()
                                val weekday = DAY_CODES.indexOf(code) + 1
                                if (weekday == 0 || ordinal !in ORDINALS) null else MonthlyOnWeekday(interval, ordinal, weekday)
                            }
                            byMonthDay != null && byDay == null -> byMonthDay.toInt().let { if (it in 1..31) MonthlyOnDay(interval, it) else null }
                            else -> null
                        }
                    }
                    "YEARLY" -> {
                        val month = parts["BYMONTH"]?.toInt() ?: return null
                        val day = parts["BYMONTHDAY"]?.toInt() ?: return null
                        if ("BYDAY" in parts || month !in 1..12 || day !in 1..CivilDate.lengthOfMonth(2000, month)) null
                        else Yearly(interval, month, day)
                    }
                    else -> null
                }
            } catch (e: IllegalArgumentException) { // NumberFormatException, a failed require
                null
            }
        }

        private val ORDINALS = setOf(1, 2, 3, 4, -1)

        private fun checkInterval(interval: Int) = require(interval in 1..MAX_INTERVAL) { "interval must be 1..$MAX_INTERVAL" }

        /**
         * The choices offered by the Repeat picker for a task on [epochDay]: every day, every weekday, weekly and every
         * other week on that weekday, monthly on that date, monthly on that weekday ("the 1st Tue", or "the last Fri"
         * when it is the last one), and yearly on that date.
         */
        fun presets(epochDay: Long): List<Recurrence> {
            val d = CivilDate.fromEpochDay(epochDay)
            val dow = CivilDate.isoDayOfWeek(epochDay)
            val nth = (d.day - 1) / 7 + 1
            val isLast = d.day + 7 > CivilDate.lengthOfMonth(d.year, d.month)
            return listOf(
                Daily(1),
                Weekly(1, WEEKDAYS),
                Weekly(1, setOf(dow)),
                Weekly(2, setOf(dow)),
                MonthlyOnDay(1, d.day),
                MonthlyOnWeekday(1, if (isLast && nth >= 4) -1 else nth, dow),
                Yearly(1, d.month, d.day),
            ).distinct()
        }

        fun ordinalWord(n: Int): String {
            val suffix = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
            return "$n$suffix"
        }

        private fun dayList(days: Set<Int>) = days.sorted().joinToString(", ") { LocalClock.DAY_SHORT[it - 1] }
    }
}

/** Proleptic-Gregorian civil dates on epoch days (days since 1970-01-01). Stdlib only, so the kernel has no date library. */
object CivilDate {
    data class Ymd(val year: Int, val month: Int, val day: Int)

    const val DAY_MS = 86_400_000L

    /** H. Hinnant's days-from-civil. */
    fun toEpochDay(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    fun fromEpochDay(epochDay: Long): Ymd {
        val z = epochDay + 719_468
        val era = (if (z >= 0) z else z - 146_096) / 146_097
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val year = (yoe + era * 400 + if (month <= 2) 1 else 0).toInt()
        return Ymd(year, month, day)
    }

    /** ISO day of week, 1 = Monday. 1970-01-01 was a Thursday. */
    fun isoDayOfWeek(epochDay: Long): Int = (epochDay + 3).mod(7) + 1

    fun isLeap(year: Int) = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

    fun lengthOfMonth(year: Int, month: Int): Int = when (month) {
        2 -> if (isLeap(year)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    /** "Tue 6 Oct" */
    fun shortLabel(epochDay: Long): String {
        val d = fromEpochDay(epochDay)
        return "${LocalClock.DAY_SHORT[isoDayOfWeek(epochDay) - 1]} ${d.day} ${Recurrence.MONTH_SHORT[d.month - 1]}"
    }

    /** "Tuesday 6 October" */
    fun longLabel(epochDay: Long): String {
        val d = fromEpochDay(epochDay)
        return "${DAY_LONG[isoDayOfWeek(epochDay) - 1]} ${d.day} ${MONTH_LONG[d.month - 1]}"
    }

    val DAY_LONG = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    val MONTH_LONG = listOf(
        "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December",
    )
}

/**
 * The user's local calendar, supplied by the platform (kotlinx-datetime in the facade), so repeating tasks keep their
 * wall-clock time across daylight-saving changes. Tests use [fixedOffset].
 */
interface LocalCalendar {
    fun epochDayOf(epochMs: Long): Long
    fun minuteOfDay(epochMs: Long): Int
    /** Local [minuteOfDay] on [epochDay] as an instant; a time skipped by a DST change moves forward. */
    fun toEpochMs(epochDay: Long, minuteOfDay: Int): Long

    companion object {
        val UTC: LocalCalendar = fixedOffset(0)

        fun fixedOffset(offsetMs: Long): LocalCalendar = object : LocalCalendar {
            override fun epochDayOf(epochMs: Long) = (epochMs + offsetMs).floorDiv(CivilDate.DAY_MS)
            override fun minuteOfDay(epochMs: Long) = ((epochMs + offsetMs).mod(CivilDate.DAY_MS) / 60_000L).toInt()
            override fun toEpochMs(epochDay: Long, minuteOfDay: Int) = epochDay * CivilDate.DAY_MS + minuteOfDay * 60_000L - offsetMs
        }
    }
}
