package os.meka.core.domain

import os.meka.core.sync.Replica

/**
 * UK bank holidays (England and Wales), mirrored by the server from GOV.UK's public list (work mode, Meka 2026-10-07:
 * "work hours Mon–Fri 09:00–17:30 except UK bank holidays"). Work mode stays off on those days.
 *
 * Stored as one server-written `context_mode` entity with the fixed id [BankHolidayStore.ENTITY_ID] (ADR-008 addendum),
 * so every device keeps the list and works offline. The apps never write it.
 */
object BankHolidayFields {
    /** "2026-12-25=Christmas Day;2026-12-28=Boxing Day (substitute day)", sorted by date. */
    const val DATES = "dates"
    /** "GOV.UK · England and Wales". */
    const val SOURCE = "source"
}

data class BankHoliday(val epochDay: Long, val title: String)

object BankHolidays {
    private const val MAX_TITLE = 80

    fun encode(list: List<BankHoliday>): String =
        list.distinctBy { it.epochDay }.sortedBy { it.epochDay }.joinToString(";") { h ->
            val d = CivilDate.fromEpochDay(h.epochDay)
            val title = h.title.replace(Regex("[;=\\n\\r]"), " ").replace(Regex("\\s+"), " ").trim().take(MAX_TITLE)
            "${d.year}-${d.month.toString().padStart(2, '0')}-${d.day.toString().padStart(2, '0')}=$title"
        }

    /** Unreadable entries are skipped (the text came from the network). */
    fun decode(s: String?): List<BankHoliday> {
        if (s.isNullOrBlank()) return emptyList()
        return s.split(';').mapNotNull { part ->
            val eq = part.indexOf('=')
            val date = parseDate(if (eq < 0) part else part.substring(0, eq)) ?: return@mapNotNull null
            BankHoliday(date, if (eq < 0) "Bank holiday" else part.substring(eq + 1).trim().ifEmpty { "Bank holiday" })
        }.distinctBy { it.epochDay }.sortedBy { it.epochDay }
    }

    /** "2026-12-25" → epoch day; null unless it's a real date. */
    fun parseDate(s: String): Long? {
        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$").find(s.trim()) ?: return null
        val (y, mo, d) = m.destructured.toList().map { it.toInt() }
        if (mo !in 1..12 || d !in 1..CivilDate.lengthOfMonth(y, mo)) return null
        return CivilDate.toEpochDay(y, mo, d)
    }
}

/** The bank holidays every rule asks about: day → name. */
data class HolidayCalendar(val byDay: Map<Long, String> = emptyMap()) {
    fun isHoliday(epochDay: Long): Boolean = epochDay in byDay
    fun title(epochDay: Long): String? = byDay[epochDay]
    val days: Set<Long> get() = byDay.keys

    companion object {
        val NONE = HolidayCalendar()
        fun of(list: List<BankHoliday>) = HolidayCalendar(list.associate { it.epochDay to it.title })
    }
}

/** Reads the mirrored list. */
class BankHolidayStore(private val replica: Replica) {
    fun calendar(): HolidayCalendar =
        HolidayCalendar.of(BankHolidays.decode(replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID)?.get(BankHolidayFields.DATES)?.textOrNull))

    companion object {
        const val ENTITY_ID = "bank_holidays"
    }
}
