package os.meka.core.domain

/**
 * Obi on calls, the number looked up: when a call card has no number, the server searches the web for the business
 * (the name alone, through the Brave key the call assistant and errands already use) and keeps the best UK number it
 * finds on the card as a suggestion, with the site it came from. Nothing rings on a found number: the card shows it
 * ("Obi found 020 7946 0018 · on rosaskitchen.example · check it's theirs") with **Use this number**, and his Call now
 * still comes after that, through the same [BookingCallRules.numberCheck] (never emergency, premium-rate or personal).
 */
object BookingNumberRules {
    const val USE = "Use this number"
    const val USED = "Number added · check it's theirs, then Call now"
    const val LOOKING = "Obi is looking up their number…"
    const val NONE = "Obi couldn't find their number · tap Edit to add theirs"
    const val FAILED = "Obi couldn't look their number up just now · tap Edit to add theirs"
    const val PICK = "Tap Use this number if it's theirs, or Edit to add another"
    const val NOTHING_TO_USE = "No number found for that card"

    /** What the server wrote in [BookingCallFields.LOOKUP]. */
    const val FOUND = "found"
    const val NOT_FOUND = "none"
    const val LOOKUP_FAILED = "failed"

    /** A card is looked up only while it's this fresh (a stale draft isn't worth the search). */
    const val FRESH_MS = 2L * 24 * 60 * 60 * 1000
    /** At most this many lookups a day (each is one search on the month's free credit). */
    const val DAY_CAP = 20

    /** Words that don't tell one business from another, ignored when matching a result to the name. */
    private val GENERIC = setOf(
        "the", "and", "restaurant", "cafe", "bar", "ltd", "limited", "kitchen", "salon", "barber", "barbers", "shop",
        "club", "house", "grill", "uk", "of", "at", "in", "for", "a", "an",
    )

    /** The search: the business's name in quotes, asking for its phone number. Nothing about him goes in. */
    fun query(business: String): String = "\"${business.replace("\"", "").trim().take(BookingCallRules.MAX_BUSINESS)}\" phone number"

    /** The name's telling words, lower case: "Rosa's Kitchen" → [rosa, rosas]. */
    fun tokens(business: String): Set<String> {
        val words = business.lowercase().split(Regex("[^a-z0-9']+")).filter { it.isNotBlank() }
        return words.flatMap { w -> listOf(w.replace("'", ""), w.substringBefore("'")) }
            .filter { it.length >= 3 && it !in GENERIC }.toSet()
    }

    /** UK-looking numbers in [text] (+44 …, (0)…, 0…), as written; the check decides which may be rung. */
    private val CANDIDATE = Regex("""(?:\+44\s?(?:\(0\)\s?)?|\(?0)\d[\d\s().-]{7,14}\d""")

    /** (No look-behind: a match straight after a digit or a "+" is part of something longer and is skipped.) */
    fun numbersIn(text: String): List<String> = CANDIDATE.findAll(text)
        .filter { m -> m.range.first == 0 || text[m.range.first - 1].let { !it.isDigit() && it != '+' } }
        .filter { m -> m.range.last + 1 >= text.length || !text[m.range.last + 1].isDigit() }
        .map { it.value.replace("(0)", "") }.toList()

    /** The site a result came from: "rosaskitchen.example" (no scheme, no www, no path). */
    fun host(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore('?').removePrefix("www.").lowercase().take(60)

    /** The best number for [business] in [hits]: a number that may be rung, from a result naming the business. */
    data class Found(val e164: String, val from: String)

    /**
     * Each number that passes [BookingCallRules.numberCheck] (so never one of [personal]) scores 3 for a result whose
     * title or site names the business, 1 for any other; a number needs at least one naming result. Landlines win a
     * tie over mobiles (a business's own line), then the earliest found.
     */
    fun best(business: String, hits: List<WebHit>, personal: Collection<String?>): Found? {
        val want = tokens(business)
        if (want.isEmpty()) return null
        data class Score(var points: Int = 0, var named: Boolean = false, var from: String = "", val order: Int)
        val scores = LinkedHashMap<String, Score>()
        hits.forEach { h ->
            val site = host(h.url)
            val hay = (h.title + " " + site.replace(".", " ")).lowercase().replace("'", "")
            val named = want.any { it in hay }
            numbersIn(h.title + " " + h.text).forEach { raw ->
                val ok = BookingCallRules.numberCheck(raw, personal) as? NumberCheck.Ok ?: return@forEach
                val s = scores.getOrPut(ok.e164) { Score(order = scores.size) }
                s.points += if (named) 3 else 1
                if (named && !s.named) { s.named = true; s.from = site }
            }
        }
        val pick = scores.entries.filter { it.value.named }
            .sortedWith(compareByDescending<Map.Entry<String, Score>> { it.value.points }
                .thenBy { if (it.key.startsWith("+447")) 1 else 0 }.thenBy { it.value.order })
            .firstOrNull() ?: return null
        return Found(pick.key, pick.value.from)
    }

    /** Whether the server should look [c] up now: a fresh draft with no number, not yet looked up for this name. */
    fun wanted(c: BookingCall, nowMs: Long): Boolean =
        !c.cancelled && c.state == BookingCallState.DRAFT && c.number == null && c.lookedUpFor != c.business &&
            nowMs - c.askedAtMs in 0..FRESH_MS

    /** The found number for the card's current name that may be rung, or null. */
    fun usable(c: BookingCall, personal: Collection<String?>): String? {
        if (c.lookup != FOUND || c.lookedUpFor != c.business) return null
        return (BookingCallRules.numberCheck(c.foundNumber, personal) as? NumberCheck.Ok)?.e164
    }

    /** The card's number line while it has no number of its own. */
    fun line(c: BookingCall, personal: Collection<String?>, nowMs: Long): String {
        usable(c, personal)?.let { e164 ->
            return listOfNotNull("Obi found ${BookingCallRules.pretty(e164)}", c.foundFrom?.let { "on $it" }, "check it's theirs").joinToString(" · ")
        }
        if (c.lookedUpFor == c.business) return if (c.lookup == LOOKUP_FAILED) FAILED else NONE
        return if (wanted(c, nowMs)) LOOKING else BookingCallRules.NO_NUMBER
    }
}
