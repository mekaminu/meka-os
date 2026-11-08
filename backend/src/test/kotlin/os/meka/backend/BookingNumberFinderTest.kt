package os.meka.backend

import os.meka.core.domain.AboutYouFields
import os.meka.core.domain.AboutYouRules
import os.meka.core.domain.BookingCallFields
import os.meka.core.domain.BookingCallRules
import os.meka.core.domain.BookingCallState
import os.meka.core.domain.BookingCalls
import os.meka.core.domain.BookingNumberRules
import os.meka.core.domain.CivilDate
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.LocalCalendar
import os.meka.core.domain.MekaSchema
import os.meka.core.domain.WebHit
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Replica
import os.meka.core.sync.fv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Obi on calls, the number looked up: the server suggests a business's number on a call card; it never rings. Invented data. */
class BookingNumberFinderTest {
    private val today = CivilDate.toEpochDay(2026, 11, 7)
    private var nowMs = LocalCalendar.UTC.toEpochMs(today, 11 * 60)
    private val ops = InMemoryServerOpStore()
    private val queries = mutableListOf<String>()
    private var hits = listOf(WebHit("https://www.rosaskitchen.example/", "Rosa's Kitchen", "Bookings 0113 496 0123"))
    private var fail = false
    private var keyIn = true
    private val woken = mutableListOf<String>()
    private val search = object : NumberSearch {
        override fun configured() = keyIn
        override fun search(query: String): List<WebHit> {
            queries += query
            if (fail) throw SearchFailed("timed out")
            return hits
        }
    }
    private val finder = BookingNumberFinder(ops, BankSync.scanning(ops), { "hh" }, search, { nowMs }, onWritten = { woken += it })

    private val store = InMemoryReplicaStore()
    private var n = 0
    private val fold = Replica("hh", "fold", HlcClock("fold", { nowMs }), store, MekaSchema) { "fold-${n++}" }
    private val calls = BookingCalls(fold, { "call-${++n}" }, { nowMs })

    private fun push() = store.pendingPush(100_000).forEach { ops.append(it) }
    private fun pull() = fold.applyRemoteBatch(ops.after("hh", 0, 100_000).map { it.op })

    private fun card(title: String): String =
        calls.draft(BookingCallRules.proposal(title, today + 7, 19 * 60, null, today, 11 * 60)!!)!!.also { push() }

    @Test
    fun aCardWithNoNumberGetsOneSuggestedAndOnlyHisTapUsesIt() {
        fold.commitLocal(EntityTypes.HOUSEHOLD_PROFILE, AboutYouRules.ENTITY_ID, mapOf(AboutYouFields.MOBILE to "07700 900123".fv()))
        val id = card("Table for 4 at Rosa's Kitchen")
        assertTrue(finder.tick())
        assertEquals(listOf("\"Rosa's Kitchen\" phone number"), queries)
        assertEquals(listOf("hh"), woken)
        pull()
        val c = calls.all().single()
        assertEquals(BookingNumberRules.FOUND, c.lookup)
        assertEquals("+441134960123", c.foundNumber)
        assertEquals("rosaskitchen.example", c.foundFrom)
        assertNull(c.number) // a suggestion only
        assertEquals(BookingCallState.DRAFT, c.state)
        // Looked up once: the next look searches nothing.
        assertFalse(finder.tick())
        assertEquals(1, queries.size)
        assertEquals(BookingNumberRules.USED, calls.useFound(id, listOf("07700 900123")))
        assertEquals("+441134960123", calls.all().single().number)
    }

    @Test
    fun hisOwnNumberIsNeverSuggestedAndAFailedSearchSaysSo() {
        fold.commitLocal(EntityTypes.HOUSEHOLD_PROFILE, AboutYouRules.ENTITY_ID, mapOf(AboutYouFields.MOBILE to "07700 900123".fv()))
        hits = listOf(WebHit("https://rosas.example", "Rosa's Kitchen", "WhatsApp 07700 900123"))
        card("Table for 2 at Rosa's Kitchen")
        assertTrue(finder.tick())
        pull()
        assertEquals(BookingNumberRules.NOT_FOUND, calls.all().single().lookup)
        assertNull(calls.all().single().foundNumber)
        // Renamed: looked up again; the search fails and the card says so.
        val id = calls.all().single().id
        assertTrue(calls.edit(id, "Rosa's Trattoria", null, null)); push()
        fail = true
        assertTrue(finder.tick())
        pull()
        val c = calls.all().single()
        assertEquals(BookingNumberRules.LOOKUP_FAILED, c.lookup)
        assertEquals("Rosa's Trattoria", c.lookedUpFor)
        assertFalse(finder.tick())
    }

    @Test
    fun noKeyStaleCardsAndTheDaysCapSearchNothing() {
        keyIn = false
        card("Table for 4 at Rosa's Kitchen")
        assertFalse(finder.tick())
        assertEquals("no search key", finder.lastReport)
        keyIn = true
        nowMs += BookingNumberRules.FRESH_MS + 60_000
        assertFalse(finder.tick())
        assertTrue(queries.isEmpty())
        // Fresh cards, more than the day's searches: the rest wait for tomorrow.
        hits = emptyList()
        repeat(BookingNumberRules.DAY_CAP + 2) { i ->
            fold.commitLocal(EntityTypes.BOOKING_CALL, "c$i", BookingCallRules.draftFields(
                BookingCallRules.proposal("Table for 2 at Place Number$i", today + 9, 19 * 60, null, today, 11 * 60)!!, nowMs))
        }
        push()
        repeat(BookingNumberRules.DAY_CAP + 2) { finder.tick() }
        assertEquals(BookingNumberRules.DAY_CAP, queries.size)
        assertEquals("day's searches used", finder.lastReport)
    }
}
