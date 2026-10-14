package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AboutField
import os.meka.core.domain.AboutYouDevice
import os.meka.core.domain.AboutYouRules
import os.meka.core.domain.DeviceHealth
import os.meka.core.domain.HealthState
import os.meka.core.domain.FootballRules
import os.meka.core.domain.SetupDevice
import os.meka.core.domain.SetupState
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** About you (rule 13, slice 1) through the facade: the Fold's one-off copy after a sync, edits on both apps, Setup. */
class AboutYouFacadeTest {
    // MekaCore works on Dispatchers.Default (real time), so runTest's 60 s default is wall-clock: the loaded macOS runner's
    // Kotlin/Native build took longer than that for the many syncs here (dispatched ci run 557: UncompletedCoroutinesError).
    private companion object { val SLOW_RUNNER = 5.minutes }

    private var now = 1_791_795_600_000L // Mon 12 Oct 2026, 10:00 in London
    private val ops = InMemoryServerOpStore()

    private fun core(device: String, store: InMemoryReplicaStore = InMemoryReplicaStore()) = MekaCore(
        householdId = "hh", deviceId = device, store = store,
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun theFoldCopiesWhatMekaHadOnceAndTheMacSeesIt() = runTest(timeout = SLOW_RUNNER) {
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
        assertTrue(v.summary.startsWith("Navilife filled this in today"))
        fold.syncNow(); mac.syncNow()
        assertEquals("Alex", mac.aboutYouView.value.rows.first { it.field == AboutField.PARTNER_NAME }.value)

        // Changed on the Mac: the Fold's next round never copies over it.
        assertEquals("Saved", mac.setAboutYou(AboutField.PARTNER_NAME, "Sam").line)
        mac.syncNow(); fold.syncNow(); fold.syncNow()
        assertEquals("Sam", fold.aboutYouView.value.rows.first { it.field == AboutField.PARTNER_NAME }.value)
    }

    @Test
    fun aReinstalledFoldPullsBeforeItCouldCopy() = runTest(timeout = SLOW_RUNNER) {
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
    fun setupAsksUntilSomethingIsFilledIn() = runTest(timeout = SLOW_RUNNER) {
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

    @Test
    fun healthSaysWhenBothAppsHoldTheProfile() = runTest(timeout = SLOW_RUNNER) {
        val fold = core("android").apply { seedsAboutYou = true; aboutYouDevice = AboutYouDevice.FOLD }
        val mac = core("mac").apply { aboutYouDevice = AboutYouDevice.MAC }
        // No profile yet: no row (Setup asks), and an empty profile is never created by the Mac's mark.
        mac.syncNow()
        assertNull(mac.refreshHealth(DeviceHealth(mac = true)).rows.firstOrNull { it.key == AboutYouRules.HEALTH_KEY })
        fold.setLeavingNow(true, "Alex", 80)
        fold.syncNow()
        val one = assertNotNull(fold.refreshHealth(DeviceHealth(mac = false)).rows.firstOrNull { it.key == AboutYouRules.HEALTH_KEY })
        assertEquals(HealthState.WARN, one.state)
        assertEquals("On the Fold · not on the Mac yet · open Navilife on the Mac", one.line)
        fold.syncNow(); mac.syncNow(); mac.syncNow(); fold.syncNow()
        val both = assertNotNull(fold.refreshHealth(DeviceHealth(mac = false)).rows.firstOrNull { it.key == AboutYouRules.HEALTH_KEY })
        assertEquals(HealthState.OK, both.state)
        assertEquals("On the Fold and the Mac · kept in your data", both.line)
        // Health keeps it to itself: Today's line isn't about it.
        assertNull(assertNotNull(fold.healthView.value).todayLine?.takeIf { it.startsWith(AboutYouRules.TITLE) })
    }
}
