package os.meka.core.domain

/**
 * Travel mode × the Vault: a trip's documents, offline. Each trip not yet over shows, under its timeline, TRAVEL
 * DOCUMENTS from the Vault: every passport, ID card and health card (GHIC/EHIC), a driving licence when the trip has
 * a car booked, and travel insurance (an insurance whose name says travel). Each says whether it lasts the trip:
 * expired or running out before the trip ends is lit; a passport with under six months left on the day he's back is
 * lit too (many countries want six months; facts only, never a ruling for a country). A kept copy opens from here the
 * Vault's way (behind the fingerprint, from this device's sealed copy, so offline). Read on the device, nothing stored.
 *
 * Ask and Talk: "are our passports OK for Barcelona?", "what documents do we need for the trip?" are answered from the
 * same rows ([askAnswer]), never the AI (the Vault's names and dates stay on the device, ADR-006 addendum).
 */
data class TripDocRow(
    val docId: String,
    val title: String,
    /** "Valid for the trip · expires Thu 20 May 2027", "Runs out during the trip · Mon 16 Nov 2026". */
    val line: String,
    val lit: Boolean,
    val hasCopy: Boolean,
    val copyIsPdf: Boolean,
    val spoken: String,
)

data class TripDocs(
    val rows: List<TripDocRow>,
    /** "3 documents for this trip · all last the trip", "… · 1 to check", or the empty line. */
    val summary: String,
    val summaryLit: Boolean,
) {
    val isEmpty: Boolean get() = rows.isEmpty()
}

object TripDocRules {
    const val LABEL = "TRAVEL DOCUMENTS"
    /** A passport wants this many days left on the day he's back. */
    const val PASSPORT_SPARE_DAYS = 183L
    const val EMPTY = "No passports or travel cards in the Vault yet. Add them in Ask → Settings → Vault with the day each runs out; they show here with their copies, offline too."
    const val NOTE = "From the Vault · copies open after your fingerprint, offline too"

    private val opt = setOf(RegexOption.IGNORE_CASE)
    private val travelInsurance = Regex("""\btravel\b""", opt)

    /** The Vault's documents that matter for [trip]: passports, ID and health cards, a licence with a car booked, travel insurance. */
    fun relevant(trip: TripItem, docs: List<DocumentItem>): List<DocumentItem> {
        val car = trip.bookings.any { it.kind == BookingKind.CAR }
        return docs.filter { d ->
            when (d.kind) {
                DocKind.PASSPORT, DocKind.ID_CARD, DocKind.HEALTH_CARD -> true
                DocKind.DRIVING_LICENCE -> car
                DocKind.INSURANCE -> travelInsurance.containsMatchIn(d.title)
                else -> false
            }
        }
    }

    private fun order(k: DocKind): Int = when (k) {
        DocKind.PASSPORT -> 0; DocKind.ID_CARD -> 1; DocKind.HEALTH_CARD -> 2; DocKind.DRIVING_LICENCE -> 3; else -> 4
    }

    /** One document against [trip]: its line and whether it's lit. */
    fun check(d: DocumentItem, trip: TripItem, today: Long): Pair<String, Boolean> {
        val e = d.expiresDay ?: return "No expiry date in the Vault" to false
        val date = DocumentRules.dateLabel(e)
        return when {
            e < today -> "Expired $date · renew it before you go" to true
            e < trip.startDay -> "Runs out before the trip · $date" to true
            e < trip.endDay -> "Runs out during the trip · $date" to true
            d.kind == DocKind.PASSPORT && e - trip.endDay < PASSPORT_SPARE_DAYS ->
                "Expires $date · ${DocumentRules.spanWords(maxOf(1L, e - trip.endDay))} after you're back · many countries want 6 months left" to true
            else -> "Lasts the trip · expires $date" to false
        }
    }

    /** [trip]'s documents from [docs]; null once the trip is over. */
    fun forTrip(trip: TripItem, docs: List<DocumentItem>, today: Long): TripDocs? {
        if (trip.endDay < today) return null
        val list = relevant(trip, docs)
        if (list.isEmpty()) return TripDocs(emptyList(), EMPTY, summaryLit = false)
        val rows = list.map { d -> d to check(d, trip, today) }
            .sortedWith(compareBy({ !it.second.second }, { order(it.first.kind) }, { it.first.title.lowercase() }))
            .map { (d, c) ->
                val copy = d.file != null
                TripDocRow(
                    d.id, d.title, c.first, c.second, copy, d.file?.isPdf == true,
                    "${d.title}. ${c.first}." + (if (copy) " Copy kept." else ""),
                )
            }
        val count = if (rows.size == 1) "1 document for this trip" else "${rows.size} documents for this trip"
        val toCheck = rows.count { it.lit }
        val copies = rows.count { it.hasCopy }
        val tail = when {
            toCheck > 0 -> "$toCheck to check"
            rows.size == 1 -> "it lasts the trip"
            else -> "all last the trip"
        }
        val copyPart = when (copies) {
            0 -> null
            rows.size -> if (rows.size == 1) "copy kept" else "copies of all"
            else -> "$copies with copies"
        }
        return TripDocs(rows, listOfNotNull(count, tail, copyPart).joinToString(" · "), summaryLit = toCheck > 0)
    }

    // ---- Ask and Talk ----

    private val space = Regex("""\s+""")
    private val docWords = Regex("""\b(?:passports?|documents?|docs|papers|ghic|ehic|id\s+cards?|identity\s+cards?|health\s+cards?|travel\s+insurance|visas?)\b""", opt)
    private val tripWords = Regex("""\b(?:trip|holiday|holidays|travel|travelling|traveling|away|abroad|flight|fly|flying)\b""", opt)
    private val askWords = Regex("""\b(?:ok|okay|fine|good|valid|need|needs|enough|expire\w*|run\s+out|runs\s+out|last|lasts|in\s+date|sorted|ready|check|have|got|what|which)\b""", opt)
    private val howTo = Regex("""^\s*(?:how\s+(?:do|can|should)\s+i|how\s+to|where\s+(?:do|can)\s+i)\b""", opt)

    /**
     * The trip [question] names: an open trip by name ("Barcelona"), or "the trip"/"holiday" when exactly one is still
     * to come or on. Null when none.
     */
    private fun tripOf(q: String, trips: List<TripItem>, today: Long): TripItem? {
        val open = trips.filter { it.endDay >= today }.sortedBy { it.startDay }
        val words = q.lowercase().split(' ').map { it.trim { c -> !c.isLetterOrDigit() } }.filter { it.length >= 3 }.toSet()
        open.firstOrNull { t ->
            val name = t.name.lowercase().split(' ').map { it.trim { c -> !c.isLetterOrDigit() } }.filter { it.length >= 3 && it != "trip" }
            name.isNotEmpty() && name.all { it in words }
        }?.let { return it }
        return if (tripWords.containsMatchIn(q) && open.size == 1) open.first() else null
    }

    /** The words for "are our passports OK for Barcelona?"; null when it isn't about a trip's documents. */
    fun askAnswer(question: String, trips: List<TripItem>, docs: List<DocumentItem>, today: Long): String? {
        val q = question.replace('’', '\'').replace(space, " ").trim()
        if (q.isEmpty() || q.length > 200 || howTo.containsMatchIn(q)) return null
        if (!docWords.containsMatchIn(q) || !askWords.containsMatchIn(q)) return null
        val trip = tripOf(q, trips, today) ?: return null
        val view = forTrip(trip, docs, today) ?: return null
        val head = "For ${trip.name} (${CivilDate.shortLabel(trip.startDay)} – ${CivilDate.shortLabel(trip.endDay)}):"
        if (view.isEmpty) return "$head there are no passports or travel cards in your Vault yet. Add each with the day it runs out and Navilife checks them against the trip."
        val lines = view.rows.take(VaultAskRules.SHOWN).joinToString(" ") { r ->
            "${r.title}: " + r.line.replaceFirstChar { it.lowercase() } + "."
        }
        val more = (view.rows.size - VaultAskRules.SHOWN).takeIf { it > 0 }?.let { " And $it more in the trip's documents." } ?: ""
        val verdict = if (view.rows.any { it.lit }) " Worth checking before you go." else " They all last the trip."
        return "$head $lines$more$verdict"
    }

    /** Open Trips under the answer (the help's Take me there). */
    val link: HelpLink get() = HelpLink("trips", HelpPlace.TRIPS, "Open Trips")
}
