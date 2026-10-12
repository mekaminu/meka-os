package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Car on autopilot (build plan V1, Meka approved 2026-10-10), slice 1: the registration typed once, and the car's four
 * dates — MOT, car tax, insurance and service — on the renewals radar ([Renewals], `obligation` entities), each with a
 * reminder **a month and a week before** (a heads-up at 09:00 on each of those days, [CarRules.notices]). The week
 * ahead and Ask already know the radar, so the car's dates show there too. The service interval is the radar item's
 * own Repeats (every 6 months or every year).
 *
 * The registration lives on one `context_mode` entity, id [CarRules.ENTITY_ID] (ADR-008 addendum 2026-10-12): field
 * `registration`, LWW. A later slice has MEKA's server read the MOT due date and history (DVLA's MOT History API) and
 * the tax status (the Vehicle Enquiry Service) for it, once Meka has the free keys (Needs Meka #26). Non-AI, pure:
 * nothing here leaves the device.
 */
object CarFields {
    /** The registration as Meka typed it, normalised: upper case, no spaces ("AB12CDE"). */
    const val REGISTRATION = "registration"
}

data class CarPreset(
    val id: String,
    val title: String,
    /** What it covers, under the title. */
    val note: String,
    val kind: ObligationKind,
    val repeat: RenewalRepeat,
    /** Lower-case words that mean an item already on the radar is this one ("car insurance"). */
    val aliases: List<String>,
    /** What the reminders suggest doing ("book it now: …"). */
    val hint: String,
)

data class CarRow(
    val presetId: String,
    val title: String,
    val note: String,
    /** "Every year · reminders a month and a week before · you set the date", or "On the radar · due Thu 12 Nov". */
    val line: String,
    /** The radar item that is this one, or null while it isn't tracked. */
    val trackedId: String?,
) {
    val tracked: Boolean get() = trackedId != null
}

data class CarView(
    /** "AB12 CDE", or null before it is typed. */
    val registration: String?,
    val rows: List<CarRow>,
    /** "AB12 CDE · 2 of 4 on the radar", "MOT, tax, insurance and service · type the registration once". */
    val summary: String,
) {
    val trackedCount: Int get() = rows.count { it.tracked }

    /** Index access for Swift. */
    val rowCount: Int get() = rows.size
    fun rowAt(index: Int): CarRow = rows[index]

    companion object {
        val EMPTY = CarView(null, emptyList(), "")
    }
}

object CarRules {
    const val LABEL = "Car"
    const val ENTITY_ID = "car"

    /** Reminders go out this many days before (a month: the radar's "4 weeks before") and a week before. */
    const val MONTH_DAYS = 28
    const val WEEK_DAYS = 7
    /** Each reminder at 09:00 on its day. */
    const val NOTICE_MIN = 9 * 60
    /** A new item's placeholder date, days ahead (Meka sets the real one: only he knows it until the DVLA lookup). */
    const val PLACEHOLDER_DAYS = 30
    const val MAX_REG = 8

    val PRESETS: List<CarPreset> = listOf(
        CarPreset(
            "mot", "MOT", "Every year from the date on the last certificate",
            ObligationKind.MOT, RenewalRepeat.YEARLY, listOf("mot"),
            "an MOT up to a month early keeps the same date",
        ),
        CarPreset(
            "tax", "Car tax", "Vehicle tax with the DVLA",
            ObligationKind.CAR_TAX, RenewalRepeat.YEARLY, listOf("car tax", "vehicle tax", "road tax"),
            "renew it with the DVLA",
        ),
        CarPreset(
            "insurance", "Car insurance", "Compare quotes before it renews",
            ObligationKind.INSURANCE, RenewalRepeat.YEARLY, listOf("car insurance", "motor insurance"),
            "compare quotes before it renews",
        ),
        CarPreset(
            "service", "Car service", "Every year or every 6 months · set it under Repeats",
            ObligationKind.CAR_SERVICE, RenewalRepeat.YEARLY, listOf("car service", "service the car", "interim service", "full service"),
            "book the garage",
        ),
    )

    fun preset(id: String): CarPreset? = PRESETS.firstOrNull { it.id == id }

    /** Whether a radar item is this one: the same title, one of its words, or (MOT, tax, service) the same kind. */
    fun matches(p: CarPreset, item: RenewalItem): Boolean {
        val t = item.title.trim().lowercase()
        return t == p.title.lowercase() || p.aliases.any { containsWord(t, it) } ||
            (p.kind != ObligationKind.INSURANCE && item.kind == p.kind)
    }

    /** Whether a radar item is one of the car's dates (it gets the car's reminders instead of the radar's digest line). */
    fun isCar(item: RenewalItem): Boolean = PRESETS.any { matches(it, item) }

    /**
     * Reads a typed registration: "ab12 cde", "AB12-CDE", "A1 MEK". Blank is null (clear it). Upper case, spaces and
     * dashes dropped; 2 to 8 letters and digits with at least one of each. Anything else is a [ValidationException].
     */
    fun normalise(text: String?): String? {
        val t = text?.uppercase()?.filterNot { it == ' ' || it == '-' }?.takeIf { it.isNotEmpty() } ?: return null
        if (t.length !in 2..MAX_REG || !t.all { it in 'A'..'Z' || it in '0'..'9' } || t.none { it.isDigit() } || t.none { it.isLetter() }) {
            throw ValidationException("Type the registration, like AB12 CDE")
        }
        return t
    }

    /** What's wrong with a typed registration, or null when [normalise] can read it. Never throws (the Mac calls it). */
    fun registrationError(text: String?): String? = try { normalise(text); null } catch (e: ValidationException) { e.message }

    /** "AB12CDE" → "AB12 CDE" (today's format); any other plate as stored. */
    fun display(reg: String): String =
        if (reg.length == 7 && reg.take(2).all { it.isLetter() } && reg.substring(2, 4).all { it.isDigit() } && reg.takeLast(3).all { it.isLetter() }) {
            reg.take(4) + " " + reg.takeLast(3)
        } else reg

    /** The car's rows against what is on the radar ([items]: every live renewal), with the stored [registration]. */
    fun view(registration: String?, items: List<RenewalItem>): CarView {
        val reg = registration?.let(::display)
        val used = mutableSetOf<String>()
        val rows = PRESETS.map { p ->
            val item = items.sortedBy { it.dueDay }.firstOrNull { it.id !in used && matches(p, it) }
            item?.let { used += it.id }
            val line = if (item != null) "On the radar · ${item.meta}"
            else "${RenewalRules.repeatLabel(p.repeat)} · reminders a month and a week before · you set the date"
            CarRow(p.id, p.title, p.note, line, item?.id)
        }
        val n = rows.count { it.tracked }
        val count = when (n) {
            0 -> "add the dates that apply"
            rows.size -> "all ${rows.size} on the radar"
            else -> "$n of ${rows.size} on the radar"
        }
        val summary = when {
            reg == null && n == 0 -> "MOT, tax, insurance and service · type the registration once"
            else -> "${reg ?: "Your car"} · $count"
        }
        return CarView(reg, rows, summary)
    }

    /**
     * The reminders: for each of the car's dates on the radar, a heads-up at 09:00 [MONTH_DAYS] and [WEEK_DAYS] before
     * it ("MOT due Thu 12 Nov · AB12 CDE" · "In 4 weeks · an MOT up to a month early keeps the same date"), each only
     * on its own day. Opens Lists (the radar).
     */
    fun notices(items: List<RenewalItem>, registration: String?, nowMs: Long, cal: LocalCalendar): List<Notice> {
        val today = cal.epochDayOf(nowMs)
        val reg = registration?.let(::display)
        return items.mapNotNull { item ->
            val p = PRESETS.firstOrNull { matches(it, item) } ?: return@mapNotNull null
            val left = item.dueDay - today
            val stage = when (left) {
                MONTH_DAYS.toLong() -> "month"
                WEEK_DAYS.toLong() -> "week"
                else -> return@mapNotNull null
            }
            val inWords = if (stage == "month") "In 4 weeks" else "In a week"
            Notice(
                key = "car:${item.id}:${item.dueDay}:$stage", source = NoticeSource.CAR, tier = NoticeTier.HEADS_UP,
                title = "${item.title} ${RenewalRules.verb(item.kind)} ${CivilDate.shortLabel(item.dueDay)}" + (reg?.let { " · $it" } ?: ""),
                text = "$inWords · ${p.hint}",
                atMs = cal.toEpochMs(today, NOTICE_MIN), target = NoticeTarget.LISTS,
                expiresAtMs = cal.toEpochMs(today + 1, 0),
            )
        }
    }

    /** What the undo bar says after adding: "Added “MOT” · set its date". */
    fun addedLine(title: String): String = "Added “$title” · set its date"

    /** [word] in [text] with no letter either side ("mot" in "mot due", never in "motor"). Read by hand, no regex. */
    private fun containsWord(text: String, word: String): Boolean {
        var from = 0
        while (true) {
            val i = text.indexOf(word, from)
            if (i < 0) return false
            val before = text.getOrNull(i - 1)
            val after = text.getOrNull(i + word.length)
            if ((before == null || before !in 'a'..'z') && (after == null || after !in 'a'..'z')) return true
            from = i + 1
        }
    }
}

/** The car's registration on a replica. Every write is an op: offline-first, synced. */
class Car(private val replica: Replica) {
    /** The stored registration ("AB12CDE"), or null. */
    fun registration(): String? =
        replica.entity(EntityTypes.CONTEXT_MODE, CarRules.ENTITY_ID)?.get(CarFields.REGISTRATION)?.textOrNull?.takeIf { it.isNotBlank() }

    /** Sets the registration from typed text (blank clears it). False when nothing changes; throws when it doesn't read. */
    fun setRegistration(text: String?): Boolean {
        val reg = CarRules.normalise(text)
        if (reg == registration()) return false
        replica.commitLocal(EntityTypes.CONTEXT_MODE, CarRules.ENTITY_ID, mapOf(CarFields.REGISTRATION to (reg?.fv() ?: FieldValue.Null)))
        return true
    }
}

/**
 * Adds one of the car's dates to the radar (refused when it is already there) with its repeat, the reminders' lead
 * (shown 4 weeks before), the registration as its subject and a placeholder date a month ahead for Meka to set.
 */
fun Renewals.addCarDate(presetId: String, today: Long, registration: String?): String {
    val p = CarRules.preset(presetId) ?: throw ValidationException("That isn't one of the car's dates")
    if (items().any { CarRules.matches(p, it) }) throw ValidationException("“${p.title}” is already on the radar")
    return add(
        p.title, p.kind, today + CarRules.PLACEHOLDER_DAYS, p.repeat,
        subject = registration?.let(CarRules::display), leadDays = CarRules.MONTH_DAYS,
    )
}
