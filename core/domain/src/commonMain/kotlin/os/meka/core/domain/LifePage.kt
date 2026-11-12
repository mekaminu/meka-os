package os.meka.core.domain

/**
 * Every page under Ask in one shape (approved by Meka 2026-11-11 22:19, `docs/design/look/LifePage.dc.html`, look
 * review batch 1; Car and People first): the title with an ⓘ that unfolds what the page does, one gold **+ Add** that
 * opens a sheet (no open input boxes or chip rows on the page), **three number tiles**, then **cards of at most five
 * rows** with See all. Both apps draw a page from a [LifePageView]; what a row or + Add's choice does stays the app's
 * own (each is named by a key). Non-AI, pure.
 */
data class PageTile(
    /** "3 wks", "£1.38", "Level 2"; a word while there is nothing to count ("No dates": never a lone dash, the quality gate). */
    val number: String,
    /** "to the MOT", "cheapest unleaded near", "parked · 08:10". */
    val label: String,
    /** Brass: something is due soon or needs him. */
    val lit: Boolean,
)

data class PageRow(
    /** What the app opens on a tap ("date:car:mot", "parked:car2"). */
    val key: String,
    val name: String,
    /** The grey line under the name. */
    val line: String,
    /** On the right: "Wed 2 Dec", "08:10", or "". */
    val whenText: String,
    /** The right-hand text in brass (due within a week). */
    val lit: Boolean,
)

data class PageCard(
    val key: String,
    val title: String,
    /** The count pill: every row the card has, shown or not. */
    val count: Int,
    /** At most [LifePageRules.MAX_ROWS]. */
    val rows: List<PageRow>,
    /** The gold link at the card's top right ("See all", "Find my car"), or null. */
    val more: String?,
    /** What an empty card says ("No dates yet · + Add puts the MOT, tax, insurance and service here"). */
    val empty: String? = null,
) {
    val rowCount: Int get() = rows.size
    fun rowAt(index: Int): PageRow = rows[index]
}

/** One choice in + Add's sheet ("A date", "Where I parked"): the app maps [key] to what it already does. */
data class AddChoice(val key: String, val label: String, val hint: String)

data class LifePageView(
    val title: String,
    /** What the ⓘ unfolds: one or two plain sentences. */
    val info: String,
    /** Always three. */
    val tiles: List<PageTile>,
    val cards: List<PageCard>,
    /** "Add to Car". */
    val addTitle: String,
    val addChoices: List<AddChoice>,
) {
    val tileCount: Int get() = tiles.size
    fun tileAt(index: Int): PageTile = tiles[index]
    val cardCount: Int get() = cards.size
    fun cardAt(index: Int): PageCard = cards[index]
    val addChoiceCount: Int get() = addChoices.size
    fun addChoiceAt(index: Int): AddChoice = addChoices[index]
}

object LifePageRules {
    /** A card shows at most this many rows; the rest are behind See all. */
    const val MAX_ROWS = 5
    const val SEE_ALL = "See all"
    const val ADD = "+ Add"
    /** The ⓘ's spoken name. */
    const val INFO_LABEL = "What this page does"

    /**
     * A card of [rows]: the first [MAX_ROWS] shown, the count of all of them, and [more] (else See all once there are
     * more than fit).
     */
    fun card(key: String, title: String, rows: List<PageRow>, more: String? = null, empty: String? = null): PageCard =
        PageCard(key, title, rows.size, rows.take(MAX_ROWS), more ?: if (rows.size > MAX_ROWS) SEE_ALL else null, empty)

    /** "today", "tomorrow", "5 days", "3 wks", "2 mths": how far off [day] is, for a tile's number. */
    fun until(day: Long, today: Long): String {
        val d = day - today
        return when {
            d < 0 -> "overdue"
            d == 0L -> "today"
            d == 1L -> "tomorrow"
            d < 14 -> "$d days"
            d < 63 -> "${d / 7} wks"
            else -> "${d / 30} mths"
        }
    }
}

/** Ask → Life → Car in the page shape (slice 1 of "every page under Ask in one shape"). */
object CarPageRules {
    const val INFO = "Navilife keeps your cars' dates, where you parked and fuel prices near you, and reminds you a month and a week before anything is due."
    const val MAC_INFO = "Navilife keeps your cars' dates and reminds you a month and a week before anything is due. Where you parked and fuel prices near you are on your phone; a parking timer is on both."
    const val ADD_TITLE = "Add to Car"
    const val DATES = "Dates"
    const val PARKED = "Parked"
    /** The Dates card's link: every car's dates in Lists → Renewals (the registration, the DVLA's dates, adding one). */
    const val ALL_DATES = "All dates"
    const val DATES_EMPTY = "No dates yet · + Add puts the MOT, tax, insurance and service here"
    const val PARKED_EMPTY = "Not parked · + Add → Where I parked when you leave the car"
    /** A date is lit (brass) this many days ahead. */
    const val SOON_DAYS = 7

    const val ADD_PARKED = "parked"
    const val ADD_TIMER = "timer"
    const val ADD_DATE = "date"
    val ADD_CHOICES = listOf(
        AddChoice(ADD_PARKED, "Where I parked", "Saves where you are now; type the bay after"),
        AddChoice(ADD_TIMER, "A parking timer", "e.g. 2 hours, or until 15:30"),
        AddChoice(ADD_DATE, "A date", "MOT, tax, insurance or service: opens the car's dates"),
    )

    /** One car's tracked date, with its car. */
    private data class Dated(val car: CarView, val row: CarRow, val day: Long)

    private fun dated(cars: CarsView): List<Dated> =
        cars.cars.flatMap { c -> c.rows.mapNotNull { r -> r.dueDay?.let { Dated(c, r, it) } } }.sortedBy { it.day }

    private fun dateTile(cars: CarsView, dates: List<Dated>, today: Long): PageTile {
        val many = cars.cars.size > 1
        val next = dates.firstOrNull { it.day >= today } ?: dates.firstOrNull()
        val lapsed = cars.cars.firstOrNull { it.isLapsed }
        return when {
            lapsed != null -> lapseTile(lapsed.lapses.first(), if (many) ParkingRules.heading(lapsed.title) else null)
            next != null -> PageTile(LifePageRules.until(next.day, today), "to the ${shortName(next.row)}${if (many) " · ${ParkingRules.heading(next.car.title)}" else ""}", next.day - today <= SOON_DAYS)
            else -> PageTile("No dates", "added yet", false)
        }
    }

    private fun datesCard(cars: CarsView, dates: List<Dated>, today: Long): PageCard {
        val many = cars.cars.size > 1
        val rows = dates.map { d ->
            PageRow(
                "date:${d.car.carId}:${d.row.presetId}", d.row.title,
                if (many) "${ParkingRules.heading(d.car.title)} · ${d.row.note}" else d.row.note,
                CarCheckRules.dayLabel(d.day, today), d.day - today in 0..SOON_DAYS.toLong() || d.day < today,
            )
        }
        return LifePageRules.card(DATES, DATES, rows, more = ALL_DATES, empty = DATES_EMPTY)
    }

    private fun timerTile(timers: TimersView, many: Boolean): PageTile? =
        timers.rows.firstOrNull { !it.ended }?.let { running ->
            PageTile(running.endText, "paid until${if (many) " · ${ParkingRules.heading(running.title)}" else ""}", running.lit)
        }

    /**
     * The Mac's Car page: where he parked stays on the phone and fuel is looked up there, so the Mac's three numbers are
     * the next date, how many dates are on the radar and the parking timer; one Dates card; + Add offers a timer or a date.
     */
    fun macPage(cars: CarsView, timers: TimersView, today: Long): LifePageView {
        val dates = dated(cars)
        return LifePageView(
            ParkingRules.TITLE, MAC_INFO,
            listOf(
                dateTile(cars, dates, today),
                PageTile("${dates.size}", if (dates.size == 1) "date on the radar" else "dates on the radar", false),
                timerTile(timers, cars.cars.size > 1) ?: PageTile("No timer", "for parking", false),
            ),
            listOf(datesCard(cars, dates, today)),
            ADD_TITLE, ADD_CHOICES.filter { it.key != ADD_PARKED },
        )
    }

    fun page(cars: CarsView, parked: List<ParkedRow>, timers: TimersView, fuel: FuelView, today: Long, nowMs: Long, cal: LocalCalendar): LifePageView {
        val many = cars.cars.size > 1
        val dates = dated(cars)
        val dateTile = dateTile(cars, dates, today)
        val cheapest = fuel.blocks.flatMap { b -> b.rows.filter { it.cheapest }.map { b to it } }.firstOrNull()
        val fuelTile = cheapest?.let { (b, r) -> PageTile(r.price, "cheapest ${b.fuel.short} near", false) }
            ?: PageTile("Fuel", "prices below", false)
        val parkTile = timerTile(timers, many) ?: when {
            parked.size > 1 -> PageTile("${parked.size} cars", "parked", false)
            parked.size == 1 -> parked.first().let { p ->
                val at = LocalClock.formatMinute(cal.minuteOfDay(p.spot.savedAtMs))
                PageTile(p.label ?: "Parked", "parked · $at", false)
            }
            else -> PageTile("Not parked", "right now", false)
        }
        val parkedRows = parked.map { p ->
            PageRow(
                "parked:${p.carId}", p.label ?: ParkingRules.heading(p.title),
                if (p.label != null && many) "${ParkingRules.heading(p.title)} · ${p.line}" else p.line,
                LocalClock.formatMinute(cal.minuteOfDay(p.spot.savedAtMs)), false,
            )
        }
        return LifePageView(
            ParkingRules.TITLE, INFO,
            listOf(dateTile, fuelTile, parkTile),
            listOf(
                datesCard(cars, dates, today),
                LifePageRules.card(PARKED, PARKED, parkedRows, more = if (parked.isNotEmpty()) ParkingRules.FIND else null, empty = PARKED_EMPTY),
            ),
            ADD_TITLE, ADD_CHOICES,
        )
    }

    /** Something has run out: "Untaxed" / "tax it before you drive", or "Ran out" / "MOT · book one before you drive". */
    private fun lapseTile(lapse: String, car: String?): PageTile {
        val suffix = car?.let { " · $it" } ?: ""
        return if (lapse.startsWith("Untaxed")) PageTile("Untaxed", "tax it before you drive$suffix", true)
        else PageTile("Ran out", "${lapse.substringBefore(" ran out")}$suffix · ${lapse.substringAfter(" · ", "book it")}", true)
    }

    /** "MOT", "tax", "insurance", "service" for the tile's "to the …". */
    private fun shortName(r: CarRow): String = when (r.presetId) {
        "mot" -> "MOT"
        else -> r.title.removePrefix("Car ").lowercase()
    }
}
