package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AboutField
import os.meka.core.domain.AboutYouRules
import os.meka.core.domain.FootballRules
import os.meka.core.domain.SetupDevice
import os.meka.core.domain.SetupState
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** About you (rule 13, slice 1) through the facade: the Fold's one-off copy after a sync, edits on both apps, Setup. */
class AboutYouFacadeTest {
    private var now = 1_791_795_600_000L // Mon 12 Oct 2026, 10:00 in London
    private val ops = InMemoryServerOpStore()

    private fun core(device: String, store: InMemoryReplicaStore = InMemoryReplicaStore()) = MekaCore(
        householdId = "hh", deviceId = device, store = store,
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun theFoldCopiesWhatMekaHadOnceAndTheMacSeesIt() = runTest {
        val fold = core("android").apply { seedsAboutYou = true }
        val mac = core("mac")
        // The Mac never copies: its profile stays empty after a sync.
        mac.syncNow()
        assertTrue(mac.aboutYouView.value.isEmpty)
        // The Fold's earlier settings are what's copied (an invented name here).
        fold.setLeavingNow(true, "Alex", 80)
        fold.setWeatherPlace("Hilltown")
        fold.syncNow()
        val v = fold.aboutYouView.value
        assertEquals("Alex", v.rows.first { it.field == AboutField.PARTNER_NAME }.value)
        assertEquals("Hilltown", v.rows.first { it.field == AboutField.HOME }.value)
        assertEquals(FootballRules.CHILDREN.entries.sortedBy { it.key }.map { it.value }, v.children.map { it.name })
        assertTrue(v.summary.startsWith("MEKA filled this in today"))
        fold.syncNow(); mac.syncNow()
        assertEquals("Alex", mac.aboutYouView.value.rows.first { it.field == AboutField.PARTNER_NAME }.value)

        // Changed on the Mac: the Fold's next round never copies over it.
        assertEquals("Saved", mac.setAboutYou(AboutField.PARTNER_NAME, "Sam").line)
        mac.syncNow(); fold.syncNow(); fold.syncNow()
        assertEquals("Sam", fold.aboutYouView.value.rows.first { it.field == AboutField.PARTNER_NAME }.value)
    }

    @Test
    fun aReinstalledFoldPullsBeforeItCouldCopy() = runTest {
        val mac = core("mac")
        mac.setAboutYou(AboutField.HOME, "Hilltown")
        mac.syncNow()
        val fold = core("android").apply { seedsAboutYou = true }
        fold.syncNow()
        val v = fold.aboutYouView.value
        assertEquals("Hilltown", v.rows.first { it.field == AboutField.HOME }.value)
        assertNull(v.rows.first { it.field == AboutField.PARTNER_NAME }.value)
        assertEquals(AboutYouRules.TYPED, v.summary)
    }

    @Test
    fun setupAsksUntilSomethingIsFilledIn() = runTest {
        val mac = core("mac")
        val setup = mac.refreshSetup(SetupDevice(mac = true))
        val step = setup.steps.first { it.key == AboutYouRules.SETUP_KEY }
        assertEquals(SetupState.TODO, step.state)
        assertEquals(AboutYouRules.TITLE, setup.sections.first().title)
        val r = mac.addAboutYouChild("Alex, U7, Oakfield School")
        assertEquals("Added Alex · Under 7s · Oakfield School", r.line)
        assertEquals(SetupState.DONE, assertNotNull(mac.setupView.value).steps.first { it.key == AboutYouRules.SETUP_KEY }.state)
        val gone = mac.removeAboutYouChild(0)
        assertEquals(SetupState.TODO, assertNotNull(mac.setupView.value).steps.first { it.key == AboutYouRules.SETUP_KEY }.state)
        mac.addAboutYouChild(assertNotNull(gone.undo))
        assertEquals(listOf("Alex"), mac.aboutYouView.value.children.map { it.name })
    }
}
