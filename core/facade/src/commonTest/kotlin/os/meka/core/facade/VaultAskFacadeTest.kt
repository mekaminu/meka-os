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

/** The Vault, slice 3: Ask (and so Talk) answers about the documents from the Vault, with no AI connected. */
class VaultAskFacadeTest {
    private var now = 1_792_317_600_000L // Sun 18 Oct 2026, 11:00 London
    private val transport = os.meka.core.testing.FaultyTransport(SyncService(InMemoryServerOpStore()))

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(if (device == "android") 3 else 4), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun aDocumentQuestionIsAnsweredFromTheVaultOnBothDevicesWithoutTheAi() = runTest {
        val fold = core("android")
        val mac = core("mac")
        assertTrue(!fold.addDocument("Theo passport 20 Feb 2027").refused)
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())

        for (c in listOf(fold, mac)) {
            val a = assertIs<AskOutcome.Answered>(c.askMeka("When does Theo's passport run out?")).answer
            assertEquals("Theo passport expires Sat 20 Feb 2027, in 4 months: it's time to renew it.", a.text)
            assertEquals(HelpPlace.VAULT, a.help?.place)
            val none = assertIs<AskOutcome.Answered>(c.talk("when does my railcard expire", emptyList())).answer
            assertTrue(none.text.startsWith("There's no railcard in your Vault yet."), none.text)
        }
    }
}
