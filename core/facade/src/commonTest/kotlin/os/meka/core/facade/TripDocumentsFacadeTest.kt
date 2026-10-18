package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AskOutcome
import os.meka.core.domain.HelpPlace
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Travel mode × the Vault: a trip's documents on both apps, and Ask's answer about them without the AI. */
class TripDocumentsFacadeTest {
    private var now = 1_792_317_600_000L // Sun 18 Oct 2026, 11:00 London
    private val transport = os.meka.core.testing.FaultyTransport(SyncService(InMemoryServerOpStore()))

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(if (device == "android") 5 else 6), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun aTripShowsItsDocumentsOnBothAppsAndAskChecksThem() = runTest {
        val fold = core("android")
        val mac = core("mac")
        assertTrue(!fold.addTrip("Barcelona 14–18 Nov").refused)
        assertTrue(!fold.addDocument("Alex passport 1 May 2027").refused)
        assertTrue(!fold.addDocument("Sam passport 3 Mar 2030").refused)
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())

        for (c in listOf(fold, mac)) {
            val docs = c.tripsView.value.rows.single().documents!!
            assertEquals("2 documents for this trip · 1 to check", docs.summary)
            assertEquals(listOf("Alex passport", "Sam passport"), docs.rows.map { it.title })
            val a = assertIs<AskOutcome.Answered>(c.askMeka("Are our passports OK for Barcelona?")).answer
            assertTrue(a.text.startsWith("For Barcelona (Sat 14 Nov – Wed 18 Nov): Alex passport: expires Sat 1 May 2027"), a.text)
            assertEquals(HelpPlace.TRIPS, a.help?.place)
        }
    }
}
