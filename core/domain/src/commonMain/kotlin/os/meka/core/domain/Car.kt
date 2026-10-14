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
 * `registration`, LWW. Slice 2 ([CarCheck]) has MEKA's server read the MOT due date and history (DVLA's MOT History
 * API) and the tax status (the Vehicle Enquiry Service) for it, once Meka has the free keys (Needs Meka #26). Non-AI,
 * pure: nothing here leaves the device.
 */
object CarFields {
    /** The registration as Meka typed it, normalised: upper case, no spaces ("AB12CDE"). */
    const val REGISTRATION = "registration"
    /** Two cars (2026-10-14, additive, LWW): the name he gives a car ("the Golf"), on any car's entity. */
    const val NAME = "name"
    /** A second (third, fourth) car exists: written true when he adds it. Car 1 ([CarRules.ENTITY_ID]) never needs it. */
    const val ADDED = "added"
    /** He removed this car (not car 1); Undo writes false. Its dates stay on the radar, no longer under a car. */
    const val REMOVED = "removed"
    /** Car and parking, slice 6 (additive, LWW): the fuel it takes, a [FuelKind] code ("E10", "E5", "B7", "SDV"). */
    const val FUEL = "fuel"
}

/**
 * One of his cars (two cars, 2026-10-14). Car 1 is the original `context_mode` entity [CarRules.ENTITY_ID], unchanged;
 * the others are [CarRules.CAR_IDS] "car2"…"car4", each with its own registration and name.
 */
data class CarEntry(
    val id: String,
    /** Normalised ("AB12CDE"), or null. */
    val registration: String?,
    val name: String?,
    val removed: Boolean = false,
    /** The fuel it takes (slice 6), or null for the default ([FuelKind.DEFAULT]). */
    val fuel: FuelKind? = null,
) {
    /** 1 for the original car, 2 for "car2", … */
    val number: Int get() = CarRules.CAR_IDS.indexOf(id).let { if (it < 0) 1 else it + 1 }

    /**
     * What the car's dates on the radar carry as their subject, so each date knows its car: the registration as shown,
     * else his name for it, else "Car 2" (null for car 1 with neither: its dates kept no subject before two cars).
     */
    val key: String? get() = registration?.let(CarRules::display) ?: name ?: if (number == 1) null else "Car $number"
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
    /** The DVLA's day for it (MOT and tax, slice 2), or null. */
    val dvlaDay: Long? = null,
    /** "Use the DVLA's date · Thu 12 Nov" when the radar's date differs from the DVLA's; null otherwise. */
    val dvlaOffer: String? = null,
) {
    val tracked: Boolean get() = trackedId != null
}

data class CarView(
    /** "AB12 CDE", or null before it is typed. */
    val registration: String?,
    val rows: List<CarRow>,
    /** "AB12 CDE · 2 of 4 on the radar", "MOT, tax, insurance and service · type the registration once". */
    val summary: String,
    /** "DVLA · Ford · MOT due Thu 12 Nov · taxed until Tue 1 Dec · checked today" (slice 2), or null. */
    val dvlaLine: String? = null,
    /** "MOT history · passed 3 Nov 2025 · 2 advisories", or null. */
    val historyLine: String? = null,
    /** Two cars: which car ([CarRules.ENTITY_ID] for the first). */
    val carId: String = CarRules.ENTITY_ID,
    /** The row's title: his name for it, else "Car" (one car) or "Car 2". */
    val title: String = CarRules.LABEL,
    /** His name for it, or null. */
    val name: String? = null,
    /** Every car but the first can be removed (with Undo). */
    val removable: Boolean = false,
    /** The fuel it takes (Car and parking, slice 6): unleaded (E10) unless he picked another. */
    val fuel: FuelKind = FuelKind.DEFAULT,
) {
    val trackedCount: Int get() = rows.count { it.tracked }

    /** Index access for Swift. */
    val rowCount: Int get() = rows.size
    fun rowAt(index: Int): CarRow = rows[index]

    companion object {
        val EMPTY = CarView(null, emptyList(), "")
    }
}

/** Two cars (2026-10-14): every car's view, first car first, and whether another can be added. */
data class CarsView(
    val cars: List<CarView>,
    val canAdd: Boolean,
) {
    val count: Int get() = cars.size
    fun carAt(index: Int): CarView = cars[index]
    val first: CarView get() = cars.firstOrNull() ?: CarView.EMPTY

    /** "Add another car · each keeps its own dates and reminders", or null at the limit. */
    val addLine: String? get() = if (canAdd) CarRules.ADD_LINE else null

    companion object {
        val EMPTY = CarsView(listOf(CarView.EMPTY), true)
    }
}

object CarRules {
    const val LABEL = "Car"
    const val ENTITY_ID = "car"
    /** The cars' fixed `context_mode` ids, first car first (two cars, 2026-10-14). */
    val CAR_IDS = listOf(ENTITY_ID, "car2", "car3", "car4")
    val MAX_CARS: Int get() = CAR_IDS.size
    const val MAX_NAME = 30
    const val ADD_LINE = "Add another car · each keeps its own dates and reminders"

    /** Reminders go out this many days before (a month: the radar's "4 weeks before") and a week before. */
    const val MONTH_DAYS = 28
    const val WEEK_DAYS = 7
    /** Each reminder at 09:00 on its day. */
    const val NOTICE_MIN = 9 * 60
    /** A new item's placeholder date, days ahead (Meka sets the real one), unless the DVLA's answer has it. */
    const val PLACEHOLDER_DAYS = 30
    const val MAX_REG = 8
    /** A DVLA day further than this from today is not used for a new item (it is still shown). */
    const val DVLA_DAYS_MAX = 3 * 366L

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
    fun view(registration: String?, items: List<RenewalItem>): CarView = view(registration, items, null, 0, null)

    /**
     * The same with the DVLA's answer ([check], slice 2): its line and the MOT history under the summary; the MOT and tax
     * rows say the DVLA's day, and a tracked one whose radar date differs offers it ([CarRow.dvlaOffer]).
     */
    fun view(registration: String?, items: List<RenewalItem>, check: CarCheck?, today: Long, cal: LocalCalendar?): CarView {
        val reg = registration?.let(::display)
        val k = CarCheckRules.forRegistration(check, registration)
        val used = mutableSetOf<String>()
        val rows = PRESETS.map { p ->
            val item = items.sortedBy { it.dueDay }.firstOrNull { it.id !in used && matches(p, it) }
            item?.let { used += it.id }
            val dvla = CarCheckRules.dayFor(p.id, k)
            val differs = item != null && dvla != null && dvla != item.dueDay
            val line = when {
                item != null && differs -> "On the radar · ${item.meta} · the DVLA says ${CarCheckRules.dayLabel(dvla!!, today)}"
                item != null -> "On the radar · ${item.meta}"
                dvla != null -> "DVLA: due ${CarCheckRules.dayLabel(dvla, today)} · reminders a month and a week before"
                else -> "${RenewalRules.repeatLabel(p.repeat)} · reminders a month and a week before · you set the date"
            }
            val offer = if (differs) "Use the DVLA's date · ${CarCheckRules.dayLabel(dvla!!, today)}" else null
            CarRow(p.id, p.title, p.note, line, item?.id, dvla, offer)
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
        return CarView(
            reg, rows, summary,
            dvlaLine = CarCheckRules.line(k, registration, today, cal),
            historyLine = k?.takeIf { it.found }?.let(CarCheckRules::historyLine),
        )
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
                // Two cars: each date carries its own car (its subject); a date from before then, the first car's registration.
                title = "${item.title} ${RenewalRules.verb(item.kind)} ${CivilDate.shortLabel(item.dueDay)}" +
                    ((item.subject?.trim()?.takeIf { it.isNotEmpty() } ?: reg)?.let { " · $it" } ?: ""),
                text = "$inWords · ${p.hint}",
                atMs = cal.toEpochMs(today, NOTICE_MIN), target = NoticeTarget.LISTS,
                expiresAtMs = cal.toEpochMs(today + 1, 0),
            )
        }
    }

    /** A name as typed: trimmed, at most [MAX_NAME]; blank is null (clear it). */
    fun cleanName(text: String?): String? = text?.trim()?.take(MAX_NAME)?.trim()?.takeIf { it.isNotEmpty() }

    /** The row's title for [car]: his name, else "Car" while it's the only one, else "Car 2". */
    fun title(car: CarEntry, liveCount: Int): String = car.name ?: if (liveCount <= 1) LABEL else "$LABEL ${car.number}"

    /** Whether a radar item's [subject] names [car]: its key, its registration however typed, or its name. */
    fun names(subject: String, car: CarEntry): Boolean {
        val s = subject.trim()
        if (s.isEmpty()) return false
        if (car.key?.equals(s, ignoreCase = true) == true) return true
        if (car.name?.equals(s, ignoreCase = true) == true) return true
        val reg = car.registration ?: return false
        return try { normalise(s) == reg } catch (_: ValidationException) { false }
    }

    /**
     * Which car one of the car's dates belongs to ([cars]: every car, removed ones too): the later car its subject
     * names (null when that car was removed: the date stays on the radar under no car), else the first car — so every
     * date from before two cars stays the first car's.
     */
    fun ownerOf(item: RenewalItem, cars: List<CarEntry>): String? {
        val s = item.subject?.trim().orEmpty()
        if (s.isNotEmpty()) {
            cars.filter { it.id != ENTITY_ID }.firstOrNull { names(s, it) }?.let { return if (it.removed) null else it.id }
        }
        return ENTITY_ID
    }

    /** The car's dates of [carId] among [items]. */
    fun itemsOf(carId: String, items: List<RenewalItem>, cars: List<CarEntry>): List<RenewalItem> =
        items.filter { isCar(it) && ownerOf(it, cars) == carId }

    /**
     * Every live car's view ([cars]: from [Car.cars], first car first): each with only its own dates, its title and
     * name; the DVLA's answer shows on the car whose registration it is for.
     */
    fun views(cars: List<CarEntry>, items: List<RenewalItem>, check: CarCheck?, today: Long, cal: LocalCalendar?): CarsView {
        val live = cars.filter { !it.removed }.ifEmpty { listOf(CarEntry(ENTITY_ID, null, null)) }
        val views = live.map { c ->
            view(c.registration, itemsOf(c.id, items, cars), check, today, cal).copy(
                carId = c.id, title = title(c, live.size), name = c.name, removable = c.id != ENTITY_ID,
                fuel = c.fuel ?: FuelKind.DEFAULT,
            ).let { v ->
                // A later car with no registration yet: its summary names it rather than "Your car".
                if (c.registration == null && c.id != ENTITY_ID && v.trackedCount > 0) v.copy(summary = v.summary.replaceFirst("Your car", title(c, live.size))) else v
            }
        }
        return CarsView(views, live.size < MAX_CARS)
    }

    /** What the undo bar says after removing a car: "Removed the Golf · its dates stay on the radar". */
    fun removedLine(title: String): String = "Removed $title · its dates stay on the radar"

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

/** The cars on a replica (the first car's registration, and two cars' list). Every write is an op: offline-first, synced. */
class Car(private val replica: Replica) {
    /** Every car, first car first (always there), then each added one, removed ones too (they keep their dates' owner). */
    fun cars(): List<CarEntry> = CarRules.CAR_IDS.mapNotNull { id ->
        val e = replica.entity(EntityTypes.CONTEXT_MODE, id)
        val reg = e?.get(CarFields.REGISTRATION)?.textOrNull?.takeIf { it.isNotBlank() }
        val name = e?.get(CarFields.NAME)?.textOrNull?.let(CarRules::cleanName)
        val fuel = FuelKind.of(e?.get(CarFields.FUEL)?.textOrNull)
        if (id == CarRules.ENTITY_ID) CarEntry(id, reg, name, fuel = fuel)
        else if (e?.get(CarFields.ADDED)?.boolOrNull == true) CarEntry(id, reg, name, e.get(CarFields.REMOVED)?.boolOrNull == true, fuel)
        else null
    }

    fun car(id: String): CarEntry? = cars().firstOrNull { it.id == id }

    /**
     * Adds another car with [name] (optional): a fresh id, else (all four used) one he removed, wiped. Its id, or null
     * at the limit.
     */
    fun addCar(name: String?): String? {
        val all = cars()
        val live = all.count { !it.removed }
        if (live >= CarRules.MAX_CARS) return null
        val id = CarRules.CAR_IDS.drop(1).firstOrNull { i -> all.none { it.id == i } }
            ?: all.firstOrNull { it.removed }?.id ?: return null
        replica.commitLocal(EntityTypes.CONTEXT_MODE, id, mapOf(
            CarFields.ADDED to true.fv(), CarFields.REMOVED to false.fv(),
            CarFields.NAME to (CarRules.cleanName(name)?.fv() ?: FieldValue.Null), CarFields.REGISTRATION to FieldValue.Null,
        ))
        return id
    }

    /** Removes a later car (never the first); [back] true puts it back (Undo). False when nothing changes. */
    fun setRemoved(id: String, removed: Boolean): Boolean {
        val c = car(id) ?: return false
        if (id == CarRules.ENTITY_ID || c.removed == removed) return false
        replica.commitLocal(EntityTypes.CONTEXT_MODE, id, mapOf(CarFields.REMOVED to removed.fv()))
        return true
    }

    /** The fuel car [id] takes (slice 6). False when nothing changes or there is no such car. */
    fun setFuel(id: String, fuel: FuelKind): Boolean {
        val c = car(id) ?: return false
        if ((c.fuel ?: FuelKind.DEFAULT) == fuel && c.fuel != null) return false
        replica.commitLocal(EntityTypes.CONTEXT_MODE, id, mapOf(CarFields.FUEL to fuel.code.fv()))
        return true
    }

    /** Names a car ("the Golf"; blank clears it). False when nothing changes or there is no such car. */
    fun setName(id: String, text: String?): Boolean {
        val c = car(id) ?: return false
        val name = CarRules.cleanName(text)
        if (name == c.name) return false
        replica.commitLocal(EntityTypes.CONTEXT_MODE, id, mapOf(CarFields.NAME to (name?.fv() ?: FieldValue.Null)))
        return true
    }

    /** Sets car [id]'s registration from typed text (blank clears it). False when nothing changes; throws when it doesn't read. */
    fun setRegistration(id: String, text: String?): Boolean {
        if (id == CarRules.ENTITY_ID) return setRegistration(text)
        val c = car(id) ?: return false
        val reg = CarRules.normalise(text)
        if (reg == c.registration) return false
        replica.commitLocal(EntityTypes.CONTEXT_MODE, id, mapOf(CarFields.REGISTRATION to (reg?.fv() ?: FieldValue.Null)))
        return true
    }

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
fun Renewals.addCarDate(presetId: String, today: Long, registration: String?, check: CarCheck? = null): String =
    addCarDate(presetId, today, registration, check, items(), registration?.let(CarRules::display))

/**
 * Two cars: the same for one car — refused only when that car's own dates ([own]) already have it; the new date
 * carries [subject] (the car's [CarEntry.key]) so it stays that car's.
 */
fun Renewals.addCarDate(presetId: String, today: Long, registration: String?, check: CarCheck?, own: List<RenewalItem>, subject: String?): String {
    val p = CarRules.preset(presetId) ?: throw ValidationException("That isn't one of the car's dates")
    if (own.any { CarRules.matches(p, it) }) throw ValidationException("“${p.title}” is already on the radar")
    // The DVLA's day when its answer is for this registration and the day is a sensible one (slice 2).
    val dvla = CarCheckRules.dayFor(p.id, CarCheckRules.forRegistration(check, registration))
        ?.takeIf { kotlin.math.abs(it - today) <= CarRules.DVLA_DAYS_MAX }
    return add(
        p.title, p.kind, dvla ?: (today + CarRules.PLACEHOLDER_DAYS), p.repeat,
        subject = subject, leadDays = CarRules.MONTH_DAYS,
    )
}

/**
 * Car on autopilot, slice 3: the car as MEKA describes it to the model (Ask's `car` lines), so "is the car taxed?",
 * "when's the MOT?" and "any advisories?" have an answer. A head line ("Car · AB12 CDE · 2 of 4 on the radar"), the
 * DVLA's answer as the Car row shows it (or that there is none yet), the MOT history, then each of the four dates with
 * what the radar has ("MOT · On the radar · due Mon 9 Nov · the DVLA says Mon 2 Nov") or "not on the radar". Meka's
 * own registration and radar, and the DVLA's structured fields (dates, a status, a make) in MEKA's words, so never
 * untrusted. Nothing before a registration is typed or a date is tracked.
 */
object CarAskRules {
    /** Head, DVLA, history and the four dates. */
    const val ASK_LINES = 3 + 4

    const val NO_ANSWER = "DVLA · no answer yet (the lookup waits for the DVLA keys) · the dates below are as Meka set them"

    /** Two cars: at most this many lines in all (two cars in full). */
    const val ASK_LINES_ALL = ASK_LINES * 2

    /**
     * Every car's lines, first car first; with more than one car each head names it ("Car · the Golf · AB12 CDE · …").
     * At most [ASK_LINES_ALL].
     */
    fun askLines(cars: CarsView, today: Long): List<String> =
        if (cars.count <= 1) askLines(cars.first, today)
        else cars.cars.flatMap { askLines(it, today, it.name, it.carId == CarRules.ENTITY_ID) }.take(ASK_LINES_ALL)

    fun askLines(view: CarView, today: Long): List<String> = askLines(view, today, null, true)

    private fun askLines(view: CarView, today: Long, title: String?, dvlaAsked: Boolean): List<String> {
        if (view.registration == null && view.trackedCount == 0) return emptyList()
        val head = "Car · " + (title?.let { "$it · " } ?: "") + (view.registration ?: "registration not typed") + " · " + when (val n = view.trackedCount) {
            0 -> "none of its dates on the radar"
            view.rows.size -> "all ${view.rows.size} dates on the radar"
            else -> "$n of ${view.rows.size} dates on the radar"
        }
        // The DVLA is asked about the first car only for now; a later car's lines don't claim a lookup is waiting.
        val dvla = view.dvlaLine ?: NO_ANSWER.takeIf { view.registration != null && dvlaAsked }
        val rows = view.rows.map { row ->
            if (row.tracked) "${row.title} · ${row.line}"
            else "${row.title} · not on the radar" + (row.dvlaDay?.let { " · the DVLA says due ${CarCheckRules.dayLabel(it, today)}" } ?: "")
        }
        return (listOfNotNull(head, dvla, view.historyLine) + rows).take(ASK_LINES)
    }
}
