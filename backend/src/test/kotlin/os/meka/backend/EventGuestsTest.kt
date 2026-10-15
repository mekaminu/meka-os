package os.meka.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import os.meka.backend.integrations.EventGuests
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Meeting prep, slice 1: an event's other people, names only, from Google's and Microsoft's own shapes. */
class EventGuestsTest {
    private fun arr(s: String): JsonArray = Json.parseToJsonElement(s).jsonArray
    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test
    fun googleLeavesOutHimRoomsAndDeclinesAndNamesAnAddressOnlyGuest() {
        val attendees = arr("""[
            {"email":"me@example.com","self":true,"responseStatus":"accepted"},
            {"email":"sam.taylor@example.com","displayName":"Sam Taylor","responseStatus":"accepted"},
            {"email":"alex_green@example.org","responseStatus":"needsAction"},
            {"email":"room-3@resource.example.com","displayName":"Room 3","resource":true},
            {"email":"robin@example.com","displayName":"Robin Lee","responseStatus":"declined"},
            {"email":"SAM.TAYLOR@example.com","displayName":"Sam T"}
        ]""")
        assertEquals(listOf("Sam Taylor", "Alex Green"), EventGuests.google(attendees, obj("""{"email":"me@example.com","self":true}""")))
        // Someone else's invite: the organiser is a guest too, once.
        assertEquals(
            listOf("Jo Bloggs", "Sam Taylor", "Alex Green"),
            EventGuests.google(attendees, obj("""{"email":"jo.bloggs@example.com"}""")),
        )
        assertEquals(emptyList(), EventGuests.google(null, null))
    }

    @Test
    fun graphLeavesOutTheAccountsOwnAddress() {
        val attendees = arr("""[
            {"type":"required","status":{"response":"accepted"},"emailAddress":{"name":"Meka","address":"Me@Example.com"}},
            {"type":"required","status":{"response":"none"},"emailAddress":{"name":"Sam Taylor","address":"sam@example.com"}},
            {"type":"resource","emailAddress":{"name":"Room 3","address":"room3@example.com"}},
            {"type":"optional","status":{"response":"declined"},"emailAddress":{"name":"Robin Lee","address":"robin@example.com"}}
        ]""")
        val organizer = obj("""{"emailAddress":{"name":"Meka","address":"me@example.com"}}""")
        assertEquals(listOf("Sam Taylor"), EventGuests.graph(attendees, organizer, "me@example.com"))
    }

    @Test
    fun aNameFromAnAddress() {
        assertEquals("Sam Taylor", EventGuests.fromAddress("sam.taylor@example.com"))
        assertEquals("Alex", EventGuests.fromAddress("alex+work@example.com"))
        assertNull(EventGuests.fromAddress("noreply@example.com"))
        assertNull(EventGuests.fromAddress("1234@example.com"))
    }
}
