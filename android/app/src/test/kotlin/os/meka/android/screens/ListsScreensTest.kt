package os.meka.android.screens

import android.os.Looper
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
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
import kotlin.test.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.android.lists.LISTS_LIST_TAG
import os.meka.android.lists.LIST_TAB_TAG
import os.meka.android.lists.ListTab
import os.meka.android.lists.ListsRoute
import os.meka.android.lists.TabLabelCut
import os.meka.core.domain.ListRules
import os.meka.core.domain.ObligationKind
import os.meka.core.domain.RenewalRepeat
import os.meka.core.domain.ShoppingRules
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
    /** Outermost: a hang fails within minutes with every thread's stack, instead of holding CI to its limit. */
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
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

    private fun shoot(name: String, tab: ListTab, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK): MekaCore {
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
            tab == ListTab.RENEWALS -> {
                ScreenShots.assertShows(compose, name, "Car insurance")
                // Renewals keep their email, slice 2: the approved cards: tiles, the tabs and a lit cancel-by badge.
                ScreenShots.assertShows(compose, name, "NEXT 30 DAYS")
                ScreenShots.assertShows(compose, name, "Coming up")
                ScreenShots.assertShows(compose, name, "cancel in 2 days")
            }
            else -> {
                ScreenShots.assertShows(compose, name, "Send Sam the photos")
                if (fill == Fill.MANY) {
                    // Something to chase today: the due line under the title is lit, never "Nothing to chase".
                    core.listsView.value.dueLine?.let { ScreenShots.assertShows(compose, name, it) }
                        ?: fail("$name: a chase due today but no due line")
                    ScreenShots.assertShows(compose, name, "YOU PROMISED") // section labels draw upper case
                    ScreenShots.assertShows(compose, name, "WAITING FOR")
                }
            }
        }
        if (fill == Fill.EMPTY) ScreenShots.assertShows(compose, name, "Nothing to chase, review or renew today.")
        return core
    }

    /** Runs the main looper and the frames by turns (a tap's change reaches the recomposer through the looper). */
    private fun turns(ms: Long = SETTLE_MS) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    /** Waits (core thread, looper and frames by turns) until [done] holds, then lets the screen settle. */
    private fun until(done: () -> Boolean) {
        repeat(40) {
            if (done()) return@repeat
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        turns()
    }

    /**
     * Scrolls the screen's own list until a node matching [m] is composed, then returns the matches (failing with [what]
     * if none). Not performScrollToNode: with the test's clock paused a lazy list's animated scroll never lands, so it
     * asked for one more scroll for ever (CI runs 1461–1467: the hang behind the reverted Lists tab slice). Each step asks
     * for 60 % of the list's height, then runs the clock; at most 20 steps down, then 40 up, so it always ends.
     */
    private fun reach(name: String, m: SemanticsMatcher, what: String): SemanticsNodeInteractionCollection {
        fun found() = compose.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()
        if (!found()) {
            val list = compose.onNodeWithTag(LISTS_LIST_TAG)
            val step = list.fetchSemanticsNode().size.height * 0.6f
            for (dy in List(20) { step } + List(40) { -step }) {
                if (found()) break
                list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, dy) }
                compose.mainClock.advanceTimeBy(1_000)
            }
            turns(500)
        }
        val n = compose.onAllNodes(m)
        if (n.fetchSemanticsNodes().isEmpty()) fail("$name: no $what")
        return n
    }

    /** Taps the first node [matcher] finds that answers a tap, scrolling the list to it first. */
    private fun tap(name: String, what: String, matcher: SemanticsMatcher) {
        reach(name, matcher and hasClickAction(), "\"$what\" to tap")[0].performSemanticsAction(SemanticsActions.OnClick)
    }

    /** Types [text] into the field TalkBack reads as [hint] and presses Done on the keyboard. */
    private fun type(name: String, hint: String, text: String) {
        val field = hasContentDescription(hint) and hasSetTextAction()
        reach(name, field, "\"$hint\" field")[0].performTextInput(text)
        turns(500)
        compose.onAllNodes(field)[0].performImeAction()
    }

    /**
     * Every button and tab drawn answers a 48 dp touch (an opened row's Kept and Delete, the chips, shopping's Remove were
     * ~30 dp; the five tabs 40 dp).
     */
    private fun assertTouchTargets(name: String) {
        val tappable = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button) or
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        val small = compose.onAllNodes(hasClickAction() and tappable)
            .fetchSemanticsNodes()
            .filter { it.size.height / compose.density.density < 47.5f }
            .map { n ->
                val label = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
                    ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: "?"
                "\"$label\" ${(n.size.height / compose.density.density).toInt()} dp"
            }
        if (small.isNotEmpty()) fail("$name: buttons under 48 dp to tap:\n" + small.joinToString("\n") { "- $it" })
    }

    /**
     * Works by hand, Promises: a tap on a promise opens its actions (each a 48 dp touch); a date chip moves its day;
     * Kept takes it off; typing in Waiting for… and Done on the keyboard adds what's waited for.
     */
    private fun promisesByHand(core: MekaCore) {
        val name = "lists-promises-by-hand"
        val p = core.listsView.value.promised.firstOrNull() ?: fail("$name: no promise to start from")
        tap(name, p.title, hasText(p.title))
        until { compose.onAllNodes(hasText("Kept") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        ScreenShots.assertShows(compose, name, "Delete")
        assertTouchTargets(name)
        val meta = p.meta
        tap(name, "Change the day: Today", hasText(ListRules.PROMISE_CHOICES.first().label))
        until { core.listsView.value.promised.firstOrNull()?.meta != meta }
        if (core.listsView.value.promised.firstOrNull()?.meta == meta) fail("$name: the day chip didn't move the promise's day ($meta)")
        tap(name, "Kept", hasText("Kept"))
        until { core.listsView.value.promised.none { it.id == p.id } }
        if (core.listsView.value.promised.any { it.id == p.id }) fail("$name: Kept left \"${p.title}\" on the list")
        type(name, "Waiting for…", "Parcel from Oakfield Garden Centre")
        until { core.listsView.value.waiting.any { it.title == "Parcel from Oakfield Garden Centre" } }
        if (core.listsView.value.waiting.none { it.title == "Parcel from Oakfield Garden Centre" }) fail("$name: Done on the field added nothing")
        ScreenShots.assertShows(compose, name, "Parcel from Oakfield Garden Centre")
    }

    /**
     * Works by hand, Shopping: every button a 48 dp touch; the ring ticks a thing into Got, Clear empties Got, Remove
     * takes a thing off, and the field adds two things at once.
     */
    private fun shoppingByHand(core: MekaCore) {
        val name = "lists-shopping-by-hand"
        assertTouchTargets(name)
        tap(name, "Got Milk", hasContentDescription("Got Milk"))
        until { core.listsView.value.shopping.got.any { it.title == "Milk" } }
        if (core.listsView.value.shopping.got.none { it.title == "Milk" }) fail("$name: the ring didn't tick Milk")
        tap(name, "Clear", hasText("Clear"))
        until { core.listsView.value.shopping.got.isEmpty() }
        if (core.listsView.value.shopping.got.isNotEmpty()) fail("$name: Clear left ${core.listsView.value.shopping.got.map { it.title }}")
        tap(name, "Remove Bread", hasContentDescription("Remove Bread"))
        until { core.listsView.value.shopping.toBuy.none { it.title == "Bread" } }
        if (core.listsView.value.shopping.toBuy.any { it.title == "Bread" }) fail("$name: Remove left Bread")
        type(name, ShoppingRules.ADD_HINT, "eggs, butter")
        until { core.listsView.value.shopping.toBuy.size >= 5 }
        val titles = core.listsView.value.shopping.toBuy.map { it.title.lowercase() }
        if ("eggs" !in titles || "butter" !in titles) fail("$name: the field didn't add eggs and butter ($titles)")
        tabsByHand(name)
    }

    /** The tabs by hand: a tap on Promises lights it and its promise shows; a tap on Shopping brings the list back. */
    private fun tabsByHand(name: String) {
        fun tab(t: ListTab) = hasTestTag(LIST_TAB_TAG) and hasContentDescription(t.label, substring = true) and hasClickAction()
        fun lit(t: ListTab) = tab(t) and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)
        reach(name, tab(ListTab.WAITING), "Promises tab")[0].performSemanticsAction(SemanticsActions.OnClick)
        until { compose.onAllNodes(lit(ListTab.WAITING)).fetchSemanticsNodes().isNotEmpty() }
        if (compose.onAllNodes(lit(ListTab.WAITING)).fetchSemanticsNodes().isEmpty()) fail("$name: tapping Promises didn't light it")
        reach(name, hasText("Send Sam the photos"), "promise on Promises")
        assertTouchTargets(name)
        reach(name, tab(ListTab.SHOPPING), "Shopping tab")[0].performSemanticsAction(SemanticsActions.OnClick)
        until { compose.onAllNodes(lit(ListTab.SHOPPING)).fetchSemanticsNodes().isNotEmpty() }
        if (compose.onAllNodes(lit(ListTab.SHOPPING)).fetchSemanticsNodes().isEmpty()) fail("$name: tapping Shopping didn't light it")
        reach(name, hasText("Coffee beans", substring = true), "Coffee beans back on Shopping")
    }

    /** All five tabs, each label whole inside its slot (TabLabelCut, set from the label's own layout). */
    private fun assertTabsWhole(name: String) {
        val tabs = compose.onAllNodes(hasTestTag(LIST_TAB_TAG), useUnmergedTree = true).fetchSemanticsNodes()
        if (tabs.size != ListTab.entries.size) fail("$name: ${tabs.size} Lists tabs on screen, not ${ListTab.entries.size}")
        val cut = tabs.filter { it.config.getOrElse(TabLabelCut) { false } }
        if (cut.isNotEmpty()) fail("$name: Lists tab labels cut at the slot's edges: " + cut.joinToString { it.config.toString() })
    }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverEmpty() { shoot("cover-dark-empty", ListTab.WAITING, Fill.EMPTY) }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverOne() { shoot("cover-dark-one", ListTab.WAITING, Fill.ONE) }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverMany() { shoot("cover-dark-many", ListTab.WAITING, Fill.MANY) }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverManyLight() { shoot("cover-light-many", ListTab.WAITING, Fill.MANY, ThemeChoice.LIGHT) }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverShopping() { shoot("cover-dark-shopping", ListTab.SHOPPING, Fill.MANY) }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverRenewals() { shoot("cover-dark-renewals", ListTab.RENEWALS, Fill.MANY) }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun promisesByHand() { promisesByHand(shoot("cover-dark-promises-by-hand", ListTab.WAITING, Fill.ONE)) }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun shoppingByHand() { shoppingByHand(shoot("cover-dark-shopping-by-hand", ListTab.SHOPPING, Fill.MANY)) }

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerEmpty() { shoot("inner-dark-empty", ListTab.WAITING, Fill.EMPTY) }

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerOne() { shoot("inner-dark-one", ListTab.WAITING, Fill.ONE) }

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerMany() { shoot("inner-dark-many", ListTab.WAITING, Fill.MANY) }

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerManyLight() { shoot("inner-light-many", ListTab.WAITING, Fill.MANY, ThemeChoice.LIGHT) }

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerShopping() { shoot("inner-dark-shopping", ListTab.SHOPPING, Fill.MANY) }

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerRenewals() { shoot("inner-dark-renewals", ListTab.RENEWALS, Fill.MANY) }
}
