package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.Replica
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Rule 13, release 2 slice 1: the children and clubs the football and club rules name come from About you only. */
class HouseholdTest {
    private var now = 1_791_795_600_000L // Mon 12 Oct 2026, 09:00 UTC
    private var n = 0
    private val replica = Replica("hh", "android", HlcClock("android", { now }), InMemoryReplicaStore(), MekaSchema) { "op" + n++ }
    private val store = AboutYouStore(replica, { now })

    private fun event(title: String, calendarName: String? = "Personal") =
        CalendarEvent("ev1", title, now + 86_400_000L, now + 90_000_000L, false, "Bury Field", "google", "me@example.com", calendarName)

    @AfterTest
    fun noHousehold() { Household.set(HouseholdNames.EMPTY) }

    @Test
    fun clubsAreTypedShortNameFirstAndKeptTidy() {
        assertEquals("OTFC Oakfield Town, HRFC", AboutYouRules.clean(AboutField.CLUBS, " otfc  Oakfield Town ; hrfc "))
        assertEquals("OTFC Oakfield Town", AboutYouRules.clean(AboutField.CLUBS, "OTFC = Oakfield Town"))
        assertEquals("OTFC Oakfield Town", AboutYouRules.clean(AboutField.CLUBS, "OTFC: Oakfield Town, otfc"))
        assertEquals("", AboutYouRules.clean(AboutField.CLUBS, "   "))
        assertNull(AboutYouRules.clean(AboutField.CLUBS, "X"))
        assertNull(AboutYouRules.clean(AboutField.CLUBS, "1234"))
        assertNull(AboutYouRules.clean(AboutField.CLUBS, "OTFC <script>"))
        assertNull(AboutYouRules.clean(AboutField.CLUBS, "A1, B2, C3, D4, E5, F6, G7"))
        assertEquals(AboutYouRules.NOT_CLUBS, AboutYouRules.refusal(AboutField.CLUBS))
        assertEquals(listOf(AboutClub("OTFC", "Oakfield Town"), AboutClub("HRFC")), AboutYouRules.clubList("OTFC Oakfield Town, HRFC"))
        assertEquals(emptyList(), AboutYouRules.clubList(null))
    }

    @Test
    fun anEmptyProfileNamesNoChildAndSpotsNoClubFixture() {
        Household.use(AboutYou.EMPTY)
        assertTrue(FootballRules.CLUBS.isEmpty())
        assertFalse(FootballRules.isClubFixture(event("OTFC U7s v Hilltop")))
        assertNull(FootballRules.child(event("OTFC U7s v Hilltop")))
        assertEquals(emptyList(), ClubChildRules.NAMES)
        assertEquals(emptyList(), SchoolRules.CHILDREN)
        // No child to choose from: a training row asks nothing and offers no choice.
        val training = event("Training")
        assertNull(ClubChildRules.line(training, emptyMap(), LocalCalendar.UTC))
        assertFalse(ClubChildRules.canSet(training))
    }

    @Test
    fun theRulesFollowWhatAboutYouHolds() {
        store.addChild("Sam, U10")
        store.addChild("Alex, U7, Oakfield School")
        store.addChild("Jo")
        store.set(AboutField.CLUBS, "OTFC Oakfield Town, HRFC")
        assertTrue(Household.use(store.profile()))
        assertFalse(Household.use(store.profile()))
        assertEquals(mapOf(10 to "Sam", 7 to "Alex"), FootballRules.CHILDREN)
        assertEquals(listOf("Alex", "Sam"), ClubChildRules.NAMES)
        assertEquals("Alex or Sam?", ClubChildRules.ASK)
        assertEquals(listOf("Alex", "Sam"), SchoolRules.CHILDREN)
        assertEquals("Alex", FootballRules.child(event("OTFC U7s v Hilltop")))
        assertEquals("HRFC", FootballRules.club(event("Training", calendarName = "hrfc")))
        assertEquals("Alex's under-sevens play Hilltop", FootballRules.spokenFixture("OTFC U7s v Hilltop"))
        assertEquals("Oakfield Town under-nines play Hilltop", FootballRules.spokenFixture("OTFC U9s v Hilltop"))
        // The server passes each household's clubs, whatever this process holds.
        assertFalse(FootballRules.isClubFixture(event("OTFC U7s v Hilltop"), emptyList()))
        assertTrue(FootballRules.isClubFixture(event("ZZFC U7s v Hilltop"), listOf("ZZFC")))
        // A club removed on either app stops being spotted.
        store.set(AboutField.CLUBS, "HRFC")
        Household.use(store.profile())
        assertFalse(FootballRules.isClubFixture(event("OTFC U7s v Hilltop")))
    }

    @Test
    fun theClubsAreCopiedOnceIntoAProfileThatPredatesThem() {
        assertFalse(store.seedClubsOnce("OTFC Oakfield Town"), "no profile: nothing is made")
        store.set(AboutField.HOME, "Hilltown")
        assertFalse(store.seedClubsOnce("not a club!"))
        assertTrue(store.seedClubsOnce("OTFC Oakfield Town"))
        assertEquals("OTFC Oakfield Town", store.profile().clubs)
        assertFalse(store.seedClubsOnce("HRFC"), "only once")
        // Cleared by him: never copied back.
        store.set(AboutField.CLUBS, "")
        assertNull(store.profile().clubs)
        assertFalse(store.seedClubsOnce("OTFC Oakfield Town"))
        assertEquals(FieldValue.Null, replica.entity(EntityTypes.HOUSEHOLD_PROFILE, AboutYouRules.ENTITY_ID)?.fields?.get(AboutYouFields.CLUBS))
    }

    @Test
    fun clubsCountInSetupAndTheFirstCopy() {
        assertEquals("Clubs", AboutYouRules.held(AboutYou(clubs = "HRFC")))
        val f = AboutYouRules.seedFields(AboutYou(home = "Hilltown", clubs = "otfc oakfield town"), now)
        assertEquals(FieldValue.Text("OTFC oakfield town"), f[AboutYouFields.CLUBS])
    }
}
