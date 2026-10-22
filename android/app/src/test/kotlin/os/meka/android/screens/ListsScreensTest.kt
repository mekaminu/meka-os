package os.meka.android.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.android.lists.LIST_TAB_TAG
import os.meka.android.lists.ListTab
import os.meka.android.lists.ListsRoute
import os.meka.android.lists.TabLabelCut
import os.meka.core.domain.ObligationKind
import os.meka.core.domain.RenewalRepeat
import os.meka.core.domain.SomedayKind
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random

/**
 * The quality gate, slice 10 (build plan: "the same shots for every other screen, Lists next"): the Lists screen on the
 * Fold's cover and inner screens. Promises with nothing on, one promise and a full set (two promises, three things
 * waited for, one due to chase today, one with a long title), Dark and Light; then Shopping and Renewals with a full
 * list. Saved under `lists/` (the `fold-screens` artifact, then the `screens` branch's docs/screens/fold/lists/) and read
 * for nonsense on screen, cut lines, words broken in two, and the five tabs' labels cut at their slots' edges (the
 * cover screen gives each about 61 dp). Invented data only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class ListsScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(19), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private companion object {
        const val SETTLE_MS = 3_000L
    }

    private enum class Fill { EMPTY, ONE, MANY }

    private val longWait = "A refund for the cancelled flight to Lisbon in the summer"

    private fun titles(tab: ListTab, fill: Fill): List<String> = when {
        fill == Fill.EMPTY -> emptyList()
        tab == ListTab.SHOPPING -> listOf("Milk", "Bread", "Washing-up liquid", "Bananas", "Coffee beans")
        tab == ListTab.RENEWALS -> listOf("Car insurance", "Streaming subscription", "Boiler service")
        fill == Fill.ONE -> listOf("Send Sam the photos")
        else -> listOf("Send Sam the photos", "Fix the gate latch for Alex", "Parcel from the garden shop", "Quote for the fence", longWait)
    }

    private fun shoot(name: String, tab: ListTab, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK) {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val core = core()
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            if (fill != Fill.EMPTY) core.addPromise("Send Sam the photos", "Sam", 2)
            if (fill == Fill.MANY) {
                core.addPromise("Fix the gate latch for Alex", "Alex", 0)
                core.addWaiting("Parcel from the garden shop", "Garden shop", 0)
                core.addWaiting("Quote for the fence", "Oakfield Fencing", 3)
                core.addWaiting(longWait, null, 7)
                core.addSomeday("Weekend in the Lakes", SomedayKind.TRIP)
                core.recordDecision("Keep the old car one more year", "It passed its MOT", 90)
                val today = core.todayEpochDay()
                core.addRenewal("Car insurance", ObligationKind.INSURANCE, today + 5, RenewalRepeat.YEARLY, "£420", 3)
                core.addRenewal("Streaming subscription", ObligationKind.SUBSCRIPTION, today + 12, RenewalRepeat.MONTHLY, "£10.99", null)
                core.addRenewal("Boiler service", ObligationKind.BOILER, today + 40, RenewalRepeat.YEARLY, null, null)
                core.addShopping("Milk, Bread, Washing-up liquid, Bananas, Coffee beans")
            }
            core.tick()
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    ListsRoute(core, initialTab = tab)
                }
            }
        }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("lists", name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertReadable(compose, name, titles(tab, fill), mustShow = false)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertShows(compose, name, "Lists")
        assertTabsWhole(name)
        when {
            // Nothing on: the line says what the list is for, never a bare heading over an empty box.
            fill == Fill.EMPTY -> ScreenShots.assertShows(compose, name, "Nothing promised either way")
            tab == ListTab.SHOPPING -> ScreenShots.assertShows(compose, name, "Milk")
            tab == ListTab.RENEWALS -> ScreenShots.assertShows(compose, name, "Car insurance")
            else -> {
                ScreenShots.assertShows(compose, name, "Send Sam the photos")
                if (fill == Fill.MANY) {
                    // Something to chase today: the due line under the title is lit, never "Nothing to chase".
                    core.listsView.value.dueLine?.let { ScreenShots.assertShows(compose, name, it) }
                        ?: fail("$name: a chase due today but no due line")
                    ScreenShots.assertShows(compose, name, "You promised")
                    ScreenShots.assertShows(compose, name, "Waiting for")
                }
            }
        }
        if (fill == Fill.EMPTY) ScreenShots.assertShows(compose, name, "Nothing to chase, review or renew today.")
    }

    /** All five tabs, each label whole inside its slot (TabLabelCut, set from the label's own layout). */
    private fun assertTabsWhole(name: String) {
        val tabs = compose.onAllNodes(hasTestTag(LIST_TAB_TAG), useUnmergedTree = true).fetchSemanticsNodes()
        if (tabs.size != ListTab.entries.size) fail("$name: ${tabs.size} Lists tabs on screen, not ${ListTab.entries.size}")
        val cut = tabs.filter { it.config.getOrElse(TabLabelCut) { false } }
        if (cut.isNotEmpty()) fail("$name: Lists tab labels cut at the slot's edges: " + cut.joinToString { it.config.toString() })
    }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverEmpty() = shoot("cover-dark-empty", ListTab.WAITING, Fill.EMPTY)

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverOne() = shoot("cover-dark-one", ListTab.WAITING, Fill.ONE)

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverMany() = shoot("cover-dark-many", ListTab.WAITING, Fill.MANY)

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverManyLight() = shoot("cover-light-many", ListTab.WAITING, Fill.MANY, ThemeChoice.LIGHT)

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverShopping() = shoot("cover-dark-shopping", ListTab.SHOPPING, Fill.MANY)

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverRenewals() = shoot("cover-dark-renewals", ListTab.RENEWALS, Fill.MANY)

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerEmpty() = shoot("inner-dark-empty", ListTab.WAITING, Fill.EMPTY)

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerOne() = shoot("inner-dark-one", ListTab.WAITING, Fill.ONE)

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerMany() = shoot("inner-dark-many", ListTab.WAITING, Fill.MANY)

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerManyLight() = shoot("inner-light-many", ListTab.WAITING, Fill.MANY, ThemeChoice.LIGHT)

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerShopping() = shoot("inner-dark-shopping", ListTab.SHOPPING, Fill.MANY)

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerRenewals() = shoot("inner-dark-renewals", ListTab.RENEWALS, Fill.MANY)
}
