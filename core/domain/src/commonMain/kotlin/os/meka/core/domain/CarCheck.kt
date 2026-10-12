package os.meka.core.domain

import os.meka.core.sync.FieldValue

/**
 * Car on autopilot, slice 2: what the DVLA says about the stored registration. MEKA's server looks the registration up
 * (only the registration is sent: the Vehicle Enquiry Service for tax, the MOT History API for the MOT) once a day and
 * whenever it changes, and writes the answer into one server-authored `context_mode` entity, id [CarCheckRules.ENTITY_ID]
 * (ADR-008 addendum 2026-10-12). The devices only read it: the Car row says "DVLA · MOT due Thu 12 Nov · taxed until
 * 1 Dec · MOT history · passed 3 Nov 2025 · 2 advisories", a date Meka adds takes the DVLA's day instead of a
 * placeholder, and a radar date that differs offers "Use the DVLA's date" (never changed without his tap).
 *
 * The lookup is off until Meka pastes the free keys into `meka-os-dev/car/dvla` (Needs Meka #26): the server's feed
 * reads [CarCheckRules.STATUS_OFF] and nothing is sent anywhere. Non-AI, pure.
 */
object CarCheckFields {
    /** The registration this answer is for ("AB12CDE"); an answer for an older registration is ignored. */
    const val REGISTRATION = "registration"
    /** False when the DVLA has no vehicle with this registration. */
    const val FOUND = "found"
    /** "FORD" as the DVLA has it, or null. */
    const val MAKE = "make"
    /** Local epoch day the MOT is due (the last pass's expiry, or a new car's first due date), or null. */
    const val MOT_DUE = "motDue"
    /** "Taxed", "Untaxed", "SORN", "Not Taxed for on Road Use", or null without the tax lookup. */
    const val TAX_STATUS = "taxStatus"
    /** Local epoch day the tax is due, or null. */
    const val TAX_DUE = "taxDue"
    /** Local epoch day of the last MOT test, or null (none yet, or without the MOT lookup). */
    const val LAST_TEST = "lastTest"
    /** "PASSED" or "FAILED". */
    const val LAST_RESULT = "lastResult"
    /** Advisories on the last test. */
    const val ADVISORIES = "advisories"
    /** When the server last asked (ms; every look, so the devices can say "checked today"). */
    const val CHECKED = "checked"
}

data class CarCheck(
    val registration: String,
    val found: Boolean,
    val make: String? = null,
    val motDueDay: Long? = null,
    val taxStatus: String? = null,
    val taxDueDay: Long? = null,
    val lastTestDay: Long? = null,
    val lastPassed: Boolean? = null,
    val advisories: Int = 0,
    val checkedMs: Long = 0,
)

object CarCheckRules {
    const val ENTITY_ID = "car_check"
    /** The server's feed id (Calendars lists it as "Car (DVLA)"). */
    const val PROVIDER = "dvla"
    const val LABEL = "DVLA · MOT and tax for the car"
    /** The feed's status while the server has no DVLA keys. */
    const val STATUS_OFF = "off"
    /** Asked again after a day (MOT and tax dates move once a year), or at once when the registration changes. */
    const val PERIOD_MS = 24 * 60 * 60_000L
    /** A failed look is tried again after this. */
    const val RETRY_MS = 60 * 60_000L
    private const val MAX_MAKE = 40
    private const val MAX_STATUS = 40

    /** Whether the server should ask about [registration] now, given what it wrote last ([current]). */
    fun due(registration: String?, current: CarCheck?, nowMs: Long): Boolean = when {
        registration == null -> false
        current == null || current.registration != registration -> true
        else -> nowMs - current.checkedMs >= PERIOD_MS || nowMs < current.checkedMs
    }

    /** The fields the server writes for [c]. */
    fun fields(c: CarCheck): Map<String, FieldValue> = linkedMapOf(
        CarCheckFields.REGISTRATION to FieldValue.Text(c.registration),
        CarCheckFields.FOUND to FieldValue.Bool(c.found),
        CarCheckFields.MAKE to (c.make?.trim()?.take(MAX_MAKE)?.takeIf { it.isNotEmpty() }?.let { FieldValue.Text(it) } ?: FieldValue.Null),
        CarCheckFields.MOT_DUE to (c.motDueDay?.let { FieldValue.Int64(it) } ?: FieldValue.Null),
        CarCheckFields.TAX_STATUS to (c.taxStatus?.trim()?.take(MAX_STATUS)?.takeIf { it.isNotEmpty() }?.let { FieldValue.Text(it) } ?: FieldValue.Null),
        CarCheckFields.TAX_DUE to (c.taxDueDay?.let { FieldValue.Int64(it) } ?: FieldValue.Null),
        CarCheckFields.LAST_TEST to (c.lastTestDay?.let { FieldValue.Int64(it) } ?: FieldValue.Null),
        CarCheckFields.LAST_RESULT to (c.lastPassed?.let { FieldValue.Text(if (it) "PASSED" else "FAILED") } ?: FieldValue.Null),
        CarCheckFields.ADVISORIES to FieldValue.Int64(c.advisories.coerceIn(0, 99).toLong()),
        CarCheckFields.CHECKED to FieldValue.Int64(c.checkedMs),
    )

    /** Reads what the server wrote; null before the first answer. */
    fun read(f: Map<String, FieldValue>?): CarCheck? {
        if (f == null) return null
        val reg = (f[CarCheckFields.REGISTRATION] as? FieldValue.Text)?.value?.takeIf { it.isNotBlank() } ?: return null
        fun day(k: String) = (f[k] as? FieldValue.Int64)?.value
        fun text(k: String) = (f[k] as? FieldValue.Text)?.value?.takeIf { it.isNotBlank() }
        return CarCheck(
            registration = reg,
            found = (f[CarCheckFields.FOUND] as? FieldValue.Bool)?.value ?: true,
            make = text(CarCheckFields.MAKE),
            motDueDay = day(CarCheckFields.MOT_DUE),
            taxStatus = text(CarCheckFields.TAX_STATUS),
            taxDueDay = day(CarCheckFields.TAX_DUE),
            lastTestDay = day(CarCheckFields.LAST_TEST),
            lastPassed = text(CarCheckFields.LAST_RESULT)?.let { it == "PASSED" },
            advisories = (day(CarCheckFields.ADVISORIES) ?: 0L).toInt(),
            checkedMs = day(CarCheckFields.CHECKED) ?: 0L,
        )
    }

    /** The answer when it is for [registration] (the stored one); an answer for an older plate is ignored. */
    fun forRegistration(c: CarCheck?, registration: String?): CarCheck? = c?.takeIf { registration != null && it.registration == registration }

    /** "Thu 12 Nov 2026": the DVLA's dates are often a year or more away. */
    fun dayLabel(day: Long, today: Long): String {
        val y = CivilDate.fromEpochDay(day).year
        return if (y == CivilDate.fromEpochDay(today).year || day - today in 0..300) CivilDate.shortLabel(day) else "${CivilDate.shortLabel(day)} $y"
    }

    /** "3 Nov 2025" for the history line. */
    private fun plainDate(day: Long): String {
        val d = CivilDate.fromEpochDay(day)
        return "${d.day} ${Recurrence.MONTH_SHORT[d.month - 1]} ${d.year}"
    }

    /** "Taxed until Tue 1 Dec", "SORN", "Untaxed · tax was due Sat 1 Aug", or null without the tax lookup. */
    fun taxLine(c: CarCheck, today: Long): String? {
        val s = c.taxStatus ?: return null
        val due = c.taxDueDay
        return when {
            s.equals("Taxed", ignoreCase = true) && due != null -> "taxed until ${dayLabel(due, today)}"
            s.equals("Taxed", ignoreCase = true) -> "taxed"
            s.equals("SORN", ignoreCase = true) -> "SORN"
            s.equals("Untaxed", ignoreCase = true) && due != null -> "untaxed · tax was due ${dayLabel(due, today)}"
            else -> s.lowercase()
        }
    }

    /** "MOT history · passed 3 Nov 2025 · 2 advisories", "MOT history · failed 3 Nov 2025", or null without a test. */
    fun historyLine(c: CarCheck): String? {
        val day = c.lastTestDay ?: return null
        val result = if (c.lastPassed == false) "failed" else "passed"
        val adv = when (c.advisories) {
            0 -> if (c.lastPassed == false) "" else " · no advisories"
            1 -> " · 1 advisory"
            else -> " · ${c.advisories} advisories"
        }
        return "MOT history · $result ${plainDate(day)}$adv"
    }

    /**
     * The Car row's DVLA line: "DVLA · Ford · MOT due Thu 12 Nov · taxed until Tue 1 Dec · checked today", or "DVLA has
     * no vehicle AB12 CDE · check the registration". Null without an answer for the stored registration.
     */
    fun line(c: CarCheck?, registration: String?, today: Long, cal: LocalCalendar? = null): String? {
        val k = forRegistration(c, registration) ?: return null
        if (!k.found) return "DVLA has no vehicle ${CarRules.display(k.registration)} · check the registration"
        val parts = mutableListOf("DVLA")
        k.make?.let { m -> parts += m.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } } }
        k.motDueDay?.let { parts += (if (it < today) "MOT was due " else "MOT due ") + dayLabel(it, today) }
        taxLine(k, today)?.let { parts += it }
        if (cal != null && k.checkedMs > 0) {
            val d = cal.epochDayOf(k.checkedMs)
            parts += when (today - d) { 0L -> "checked today"; 1L -> "checked yesterday"; else -> "checked ${CivilDate.shortLabel(d)}" }
        }
        return parts.joinToString(" · ")
    }

    /** The DVLA's day for one of the car's presets (MOT and tax only), or null. */
    fun dayFor(presetId: String, c: CarCheck?): Long? = when {
        c == null || !c.found -> null
        presetId == "mot" -> c.motDueDay
        presetId == "tax" -> c.taxDueDay?.takeIf { c.taxStatus.equals("Taxed", ignoreCase = true) }
        else -> null
    }
}
