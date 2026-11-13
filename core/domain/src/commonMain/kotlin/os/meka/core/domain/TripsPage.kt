package os.meka.core.domain

/**
 * Ask → Trips in the page shape (every page under Ask in one shape, slice 16; approved 2026-11-11,
 * `docs/design/look/LifePage.dc.html`): the title with its ⓘ (what Trips keeps, typed by him, Today's heads-up, the
 * documents from the Vault, nothing booked or sent), one gold + Add (A trip · A booking, each one typed line the
 * existing Trips field reads), three numbers (how soon the next trip is or which day of it he's on, what's booked for
 * it, the travel documents to check), then **Your trips**, **Booked for <the next trip>** (from today on),
 * **Travel documents** (the next trip's, from the Vault) and **Past trips** (five rows each, See all for the rest).
 * A trip's row opens its sheet (its timeline with ×, its lists, its documents, Remove trip); a booking's row its day,
 * Remove and the whole trip; a document's row Open copy. Built from the [TripsView] both apps already have; nothing
 * new is read or sent. Non-AI, pure.
 */
object TripsPageRules {
    const val TITLE = "Trips"
    const val INFO = "Navilife keeps the trips and bookings you type, shows each trip day by day, puts it on Today a week before and while you're away, and checks your passports and travel cards from the Vault last the trip. It books nothing and sends nothing."
    const val ADD_TITLE = "Add to Trips"
    const val GONE = "That isn't in Trips now"

    const val TRIPS = "trips"
    const val BOOKED = "booked"
    const val DOCS = "docs"
    const val PAST = "past"

    const val ADD_TRIP = "trip"
    const val ADD_BOOKING = "booking"

    /** + Add's choices: both go to the one Trips field, which tells a trip from a booking. */
    val ADD_CHOICES = listOf(
        AddChoice(ADD_TRIP, "A trip", TripRules.ADD_HINT),
        AddChoice(ADD_BOOKING, "A booking", TripRules.BOOKING_HINT),
    )

    fun hint(choice: String): String = ADD_CHOICES.firstOrNull { it.key == choice }?.hint ?: ADD_CHOICES.first().hint

    /** What TalkBack and VoiceOver read for the field. */
    fun spokenHint(choice: String): String = when (choice) {
        ADD_BOOKING -> "A booking with its day, time and reference, for example Flight BA478 14 Nov 07:40 ref ABC123"
        else -> "A trip with its dates, for example ${TripRules.ADD_HINT}"
    }

    const val TRIPS_EMPTY = "No trips yet · + Add → A trip with its dates"
    const val BOOKED_EMPTY = "Nothing booked yet · + Add → A booking"
    const val DOCS_EMPTY = "None in the Vault · add passports in Settings → Vault"
    /** A document's row on the right when it doesn't last the trip. */
    const val CHECK = "Check"
    /** A trip a week or less away is lit on its row and the first number. */
    const val SOON_DAYS = 7
    /** A card row's grey line fits one line on the cover screen; the whole line is in the row's sheet. */
    const val SHORT_LINE = 34

    fun tripKey(id: String): String = "trip:$id"
    fun bookingKey(lineKey: String): String = "bk:$lineKey"
    fun docKey(id: String): String = "doc:$id"

    /** As many of [line]'s " · " parts as fit [SHORT_LINE], else its first words with "…". */
    fun shortLine(line: String): String {
        if (line.length <= SHORT_LINE) return PhraseGlue.kept(line)
        val parts = line.split(" · ")
        var out = parts.first()
        for (p in parts.drop(1)) {
            val next = "$out · $p"
            if (next.length > SHORT_LINE) break
            out = next
        }
        if (out.length <= SHORT_LINE) return PhraseGlue.kept(out)
        val cut = out.take(SHORT_LINE - 1).let { it.substringBeforeLast(' ', it) }.trimEnd(',', ';', ' ', '.', '–')
        return PhraseGlue.kept(cut) + "…"
    }

    /** The trip on now, else the soonest to come; null when none is. */
    fun nextTrip(view: TripsView, today: Long): TripRow? =
        view.rows.firstOrNull { today in it.startDay..it.endDay } ?: view.rows.filter { it.startDay > today }.minByOrNull { it.startDay }

    private fun tripRows(view: TripsView, today: Long): List<PageRow> = view.rows.map { r ->
        val soon = r.startDay > today && r.startDay - today <= SOON_DAYS
        PageRow(tripKey(r.id), r.name, shortLine(r.line), r.status, r.statusLit || soon)
    }

    /** The next trip's bookings from today on, a row a line (a stay's check-out is its own row). */
    private fun bookedRows(view: TripsView, today: Long): List<PageRow> {
        val t = nextTrip(view, today) ?: return emptyList()
        return t.timeline.filter { it.epochDay >= today }.flatMap { d ->
            d.lines.map { l ->
                val name = if (l.key.endsWith("-end")) l.text else l.text.substringBefore(" · ")
                val time = l.lead.takeIf { it.contains(':') } ?: ""
                PageRow(bookingKey(l.key), name, shortLine(d.label), time, d.epochDay == today)
            }
        }
    }

    private fun docRows(view: TripsView, today: Long): List<PageRow> =
        nextTrip(view, today)?.documents?.rows.orEmpty().map { d ->
            PageRow(docKey(d.docId), d.title, shortLine(d.line), if (d.lit) CHECK else "", d.lit)
        }

    private fun pastRows(view: TripsView): List<PageRow> = view.past.map { r ->
        PageRow(tripKey(r.id), r.name, shortLine(r.line), "", false)
    }

    /** Every row of the card [cardKey] (See all), not only the first five. */
    fun allRows(view: TripsView, cardKey: String, today: Long): List<PageRow> = when (cardKey) {
        TRIPS -> tripRows(view, today)
        BOOKED -> bookedRows(view, today)
        DOCS -> docRows(view, today)
        PAST -> pastRows(view)
        else -> emptyList()
    }

    /** The card's title for See all's sheet. */
    fun cardTitle(view: TripsView, cardKey: String, today: Long): String = when (cardKey) {
        TRIPS -> "Your trips"
        BOOKED -> nextTrip(view, today)?.let { "Booked for ${it.name}" } ?: "Booked"
        DOCS -> "Travel documents"
        PAST -> "Past trips"
        else -> TITLE
    }

    private fun bookingCount(t: TripRow): Int = t.timeline.flatMap { d -> d.lines.map { it.bookingId } }.distinct().size

    private fun tripTile(next: TripRow?, today: Long): PageTile = when {
        next == null -> PageTile("No trips", "coming up", false)
        today >= next.startDay -> PageTile("Day ${today - next.startDay + 1} of ${next.endDay - next.startDay + 1}", "in ${next.name}", true)
        else -> PageTile(LifePageRules.until(next.startDay, today), "to ${next.name}", next.startDay - today <= SOON_DAYS)
    }

    private fun bookedTile(next: TripRow?): PageTile {
        if (next == null) return PageTile("None", "booked yet", false)
        val n = bookingCount(next)
        return if (n == 0) PageTile("None", "booked for ${next.name}", false)
        else PageTile("$n", (if (n == 1) "booking" else "bookings") + " for ${next.name}", false)
    }

    private fun docsTile(next: TripRow?): PageTile {
        val docs = next?.documents
        if (docs == null || docs.isEmpty) return PageTile("None", "documents in the Vault", false)
        val check = docs.rows.count { it.lit }
        return if (check > 0) PageTile("$check", if (check == 1) "document to check" else "documents to check", true)
        else PageTile("All set", "travel documents", false)
    }

    fun page(view: TripsView, today: Long): LifePageView {
        val next = nextTrip(view, today)
        val cards = buildList {
            add(LifePageRules.card(TRIPS, cardTitle(view, TRIPS, today), allRows(view, TRIPS, today), empty = TRIPS_EMPTY))
            if (next != null) {
                add(LifePageRules.card(BOOKED, cardTitle(view, BOOKED, today), allRows(view, BOOKED, today), empty = BOOKED_EMPTY))
                if (next.documents != null) {
                    add(LifePageRules.card(DOCS, cardTitle(view, DOCS, today), allRows(view, DOCS, today), empty = DOCS_EMPTY))
                }
            }
            if (view.past.isNotEmpty()) add(LifePageRules.card(PAST, cardTitle(view, PAST, today), allRows(view, PAST, today)))
        }
        return LifePageView(TITLE, INFO, listOf(tripTile(next, today), bookedTile(next), docsTile(next)), cards, ADD_TITLE, ADD_CHOICES)
    }

    /** The trip behind a "trip:…" key (to come, on, just ended or past); null once it's removed. */
    fun trip(view: TripsView, key: String): TripRow? {
        val id = key.takeIf { it.startsWith("trip:") }?.removePrefix("trip:") ?: return null
        return view.rows.firstOrNull { it.id == id } ?: view.past.firstOrNull { it.id == id }
    }

    /** The trip a "bk:…" key's booking is in; null once it's gone. */
    fun bookingTrip(view: TripsView, key: String): TripRow? {
        val lineKey = key.takeIf { it.startsWith("bk:") }?.removePrefix("bk:") ?: return null
        return (view.rows + view.past).firstOrNull { t -> t.timeline.any { d -> d.lines.any { it.key == lineKey } } }
    }

    /** The day a "bk:…" key's line is on; null once it's gone. */
    fun bookingDay(view: TripsView, key: String): TripDayRow? {
        val lineKey = key.removePrefix("bk:")
        return bookingTrip(view, key)?.timeline?.firstOrNull { d -> d.lines.any { it.key == lineKey } }
    }

    /** The line behind a "bk:…" key; null once it's gone. */
    fun booking(view: TripsView, key: String): TripLineRow? {
        val lineKey = key.removePrefix("bk:")
        return bookingDay(view, key)?.lines?.firstOrNull { it.key == lineKey }
    }

    /** The document behind a "doc:…" key (the next trip's); null once it's gone from the Vault. */
    fun doc(view: TripsView, key: String, today: Long): TripDocRow? {
        val id = key.takeIf { it.startsWith("doc:") }?.removePrefix("doc:") ?: return null
        return nextTrip(view, today)?.documents?.rows?.firstOrNull { it.docId == id }
    }

    /** What a row's sheet is titled. */
    fun sheetTitle(view: TripsView, key: String, today: Long): String =
        trip(view, key)?.name ?: booking(view, key)?.let { b -> if (b.key.endsWith("-end")) b.text else b.text.substringBefore(" · ") }
            ?: doc(view, key, today)?.title ?: TITLE
}
