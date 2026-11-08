package os.meka.backend

import os.meka.backend.integrations.Integrations
import os.meka.core.domain.AboutYouFields
import os.meka.core.domain.AboutYouRules
import os.meka.core.domain.BookingCallFields
import os.meka.core.domain.BookingCallRules
import os.meka.core.domain.BookingNumberRules
import os.meka.core.domain.EntityTypes
import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.Op
import os.meka.core.sync.ServerOpStore
import java.security.SecureRandom

/**
 * Obi on calls, the number looked up: every minute, one fresh call card with no number ([BookingNumberRules.wanted])
 * is searched on the web by the business's name alone ([BookingNumberRules.query], the Brave key the call assistant
 * and errands already use), and the best UK number from a result naming the business is written on the card as a
 * suggestion (found · none · failed, for which name, the number and the site). It never writes the card's own number
 * and never rings: he taps Use this number, then Call now. At most [BookingNumberRules.DAY_CAP] searches a day.
 * Nothing of a name, a query or a number is logged; [lastReport] holds counts.
 */
class BookingNumberFinder(
    private val ops: ServerOpStore,
    private val read: (householdId: String, entityType: String) -> Map<String, Map<String, Op>>,
    private val household: () -> String?,
    private val search: NumberSearch,
    private val now: () -> Long = System::currentTimeMillis,
    private val onWritten: (householdId: String) -> Unit = {},
) {
    private val clock = HlcClock(Integrations.SERVER_DEVICE, now)
    private val rng = SecureRandom()
    private var day = -1L
    private var today = 0

    @Volatile var lastReport: String = "not run yet"
        private set

    /** One look: at most one search. Returns whether anything was written. */
    @Synchronized fun tick(): Boolean {
        val hh = household() ?: return false
        if (!runCatching { search.configured() }.getOrDefault(false)) { lastReport = "no search key"; return false }
        val t = now()
        val d = Math.floorDiv(t, 86_400_000L)
        if (d != day) { day = d; today = 0 }
        val heads = read(hh, EntityTypes.BOOKING_CALL)
        val card = heads.mapNotNull { (id, f) -> BookingCallRules.read(id, f.mapValues { it.value.value }) }
            .filter { BookingNumberRules.wanted(it, t) }.minByOrNull { it.askedAtMs } ?: run { lastReport = "idle"; return false }
        if (today >= BookingNumberRules.DAY_CAP) { lastReport = "day's searches used"; return false }
        today++
        val profile = read(hh, EntityTypes.HOUSEHOLD_PROFILE)[AboutYouRules.ENTITY_ID].orEmpty()
        fun text(f: String) = (profile[f]?.value as? FieldValue.Text)?.value?.trim()?.takeIf { it.isNotEmpty() }
        val personal = listOf(text(AboutYouFields.MOBILE), text(AboutYouFields.ASSISTANT_NUMBER), text(AboutYouFields.PARTNER_PHONE), text(AboutYouFields.VOICEMAIL_NUMBER))
        val fields = LinkedHashMap<String, FieldValue>()
        fields[BookingCallFields.LOOKED_UP_FOR] = FieldValue.Text(card.business)
        try {
            val found = BookingNumberRules.best(card.business, search.search(BookingNumberRules.query(card.business)), personal)
            if (found == null) {
                fields[BookingCallFields.LOOKUP] = FieldValue.Text(BookingNumberRules.NOT_FOUND)
                lastReport = "looked up a card: none"
            } else {
                fields[BookingCallFields.LOOKUP] = FieldValue.Text(BookingNumberRules.FOUND)
                fields[BookingCallFields.FOUND_NUMBER] = FieldValue.Text(found.e164)
                fields[BookingCallFields.FOUND_FROM] = FieldValue.Text(found.from)
                lastReport = "looked up a card: found"
            }
        } catch (e: SearchFailed) {
            fields[BookingCallFields.LOOKUP] = FieldValue.Text(BookingNumberRules.LOOKUP_FAILED)
            lastReport = "looked up a card: failed (${e.reason})"
        }
        val wrote = write(hh, card.id, fields, heads[card.id])
        if (wrote) runCatching { onWritten(hh) }
        return wrote
    }

    /** Appends each field whose value differs from its head, after the head (so the server's write wins). */
    private fun write(hh: String, id: String, fields: Map<String, FieldValue>, heads: Map<String, Op>?): Boolean = ops.transaction {
        var wrote = false
        for ((field, value) in fields) {
            val last = heads?.get(field)
            if ((last?.value ?: FieldValue.Null) == value) continue
            val hlc = synchronized(clock) {
                last?.let { runCatching { clock.receive(it.hlc) } }
                clock.now()
            }
            ops.append(Op(
                opId = "srvnum" + hex(12), householdId = hh, entityType = EntityTypes.BOOKING_CALL, entityId = id, field = field, value = value,
                hlc = hlc, baseOpIds = listOfNotNull(last?.opId), deviceId = Integrations.SERVER_DEVICE,
            ))
            wrote = true
        }
        wrote
    }

    private fun hex(bytes: Int): String = ByteArray(bytes).also(rng::nextBytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val LOOK_MS = 60_000L
    }
}

fun startBookingNumberFinder(finder: BookingNumberFinder) {
    Thread.ofVirtual().name("booking-numbers").start {
        Thread.sleep(90_000)
        while (true) {
            runCatching { finder.tick() }
                .onSuccess { wrote -> if (wrote) System.err.println("booking numbers: ${finder.lastReport}") }
                .onFailure { System.err.println("booking numbers failed: ${it::class.simpleName}") }
            Thread.sleep(BookingNumberFinder.LOOK_MS)
        }
    }
}
