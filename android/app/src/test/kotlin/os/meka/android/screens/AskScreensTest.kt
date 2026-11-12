package os.meka.android.screens

import androidx.compose.foundation.background
import android.os.Looper
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import org.robolectric.Shadows
import os.meka.android.ask.ASK_FIELD_TAG
import os.meka.android.ask.ASK_LIST_TAG
import os.meka.android.ask.ASK_MATCH_TAG
import os.meka.android.ask.ASK_MIC_TAG
import os.meka.android.shell.OpenItem
import os.meka.android.shell.MoreItem
import os.meka.android.shell.ShellDestination
import os.meka.android.shell.ShellNav
import kotlin.test.fail
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.ask.AskRoute
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.core.domain.HabitTiming
import os.meka.core.domain.LifeTileRules
import os.meka.core.domain.ObligationKind
import os.meka.core.domain.RenewalRepeat
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random

/**
 * The quality gate, slice 7 (build plan: "the same shots for every other screen, Ask next"): the Ask tab on the Fold's
 * cover screen (2 tile columns) and inner screen (3 columns), with nothing set up, with one thing (one item to buy) and
 * with a full life (shopping, a habit, dinners, an errand, a birthday, a trip, a renewal, someone waited on), Dark and
 * Light. Saved under `ask/` (the `fold-screens` artifact, then the `screens` branch's docs/screens/fold/ask/) and read
 * for nonsense on screen and for any short line cut before it can be read (a tile's line, a row's line). Invented data
 * only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class AskScreensTest {
    /** Outermost: a hang fails within minutes with every thread's stack, instead of holding CI to its limit. */
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(11), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private companion object { const val SETTLE_MS = 3_000L }

    private enum class Fill { EMPTY, ONE, MANY }

    private fun shoot(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK) {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val core = core()
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            if (fill != Fill.EMPTY) core.addShopping("Milk")
            if (fill == Fill.MANY) {
                core.addShopping("Bread")
                core.addShopping("Washing-up liquid")
                // Walk-through 2026-11-08 A8: the same three habits as Today's and Goals' shots (Stretch done this
                // morning), so Ask's tile and Today's Day ring count the same "1 of 2", not "0 of 1" beside it.
                core.addHabit("Read 20 pages", 5, HabitTiming.EVENING, 20, null)
                core.setHabitDone(core.addHabit("Stretch", 7, HabitTiming.MORNING, 10, null), true)
                core.addHabit("Run", 3, HabitTiming.EVENING, 40, null)
                core.addMeal("Fish pie")
                core.addErrand("Post office: return the parcel")
                core.addOccasion(ShotLife.SAM_BIRTHDAY)
                core.addTrip(ShotLife.NEXT_TRIP)
                core.addRenewal("Home insurance", ObligationKind.INSURANCE, core.todayEpochDay() + 60, RenewalRepeat.YEARLY, null, null)
                core.addWaiting("The quote for the fence", "Casey", 3)
            }
            core.tick()
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    AskRoute(core, connected = true, openPlace = {}, openItem = {})
                }
            }
        }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("ask", name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertReadable(compose, name, emptyList(), mustShow = false)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertShows(compose, name, "Ask")
        // The Ask hub: Life · Daily · Settings, Life first.
        os.meka.core.domain.AskHubRules.TABS.forEach { ScreenShots.assertShows(compose, name, it.label) }
        when (fill) {
            // Nothing set up: one line says so over the open set-up group, never an empty grid.
            Fill.EMPTY -> ScreenShots.assertShows(compose, name, LifeTileRules.WELCOME)
            Fill.ONE -> ScreenShots.assertShows(compose, name, "1 to buy")
            Fill.MANY -> {
                ScreenShots.assertShows(compose, name, "3 to buy")
                // Slice 8 (the Ask re-shot's findings): the errand counts, and a two-line name keeps its icon on top.
                // The Ask hub pages the tiles: Errands may be on the next page folded.
                val errands = hasText("1 errand at", substring = true)
                if (!shows(errands)) {
                    val next = hasContentDescription(os.meka.android.ask.ASK_NEXT_PAGE) and hasClickAction()
                    if (!shows(next)) fail("$name: Errands isn't on the first page and there's no next page")
                    compose.onAllNodes(next)[0].performSemanticsAction(SemanticsActions.OnClick)
                    compose.mainClock.advanceTimeBy(SETTLE_MS)
                    compose.waitForIdle()
                    compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("ask", "$name-page-2"))
                    ScreenShots.assertNoBrokenWords(compose, "$name-page-2")
                    ScreenShots.assertShows(compose, "$name-page-2", "1 errand at")
                    compose.onAllNodes(hasContentDescription(os.meka.android.ask.ASK_PREV_PAGE) and hasClickAction())[0]
                        .performSemanticsAction(SemanticsActions.OnClick)
                    compose.mainClock.advanceTimeBy(SETTLE_MS)
                    compose.waitForIdle()
                }
                // A8: Ask's habits line is the Day ring's count on Today (one rule, DayTileRules), for the same habits.
                val goals = core.goalsView.value
                val total = os.meka.core.domain.DayTileRules.habitsToday(goals.habits)
                val done = os.meka.core.domain.DayTileRules.habitsDone(goals.habits)
                check(total == 2 && done == 1) { "$name: the shots' habits should be 1 of 2 today, as on Today; got $done of $total" }
                ScreenShots.assertShows(compose, name, "1 of 2 habits done today")
                ScreenShots.assertIconOnFirstLine(compose, name, os.meka.android.ask.TILE_ICON_TAG, "Goals and habits")
            }
        }
    }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverEmpty() = shoot("cover-dark-empty", Fill.EMPTY)

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverOne() = shoot("cover-dark-one", Fill.ONE)

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverMany() = shoot("cover-dark-many", Fill.MANY)

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverManyLight() = shoot("cover-light-many", Fill.MANY, ThemeChoice.LIGHT)

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerEmpty() = shoot("inner-dark-empty", Fill.EMPTY)

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerOne() = shoot("inner-dark-one", Fill.ONE)

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerMany() = shoot("inner-dark-many", Fill.MANY)

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerManyLight() = shoot("inner-light-many", Fill.MANY, ThemeChoice.LIGHT)

    /** Runs the main looper and the frames by turns (a tap's change reaches the recomposer through the looper). */
    private fun turns(ms: Long = SETTLE_MS) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    /** Waits (core thread, looper and frames by turns) until [done] holds, then lets the screen settle for [settle] ms. */
    private fun until(settle: Long = 300, done: () -> Boolean) {
        repeat(40) {
            if (done()) return@repeat
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        turns(settle)
    }

    private fun shows(m: SemanticsMatcher) = compose.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()

    /**
     * Scrolls Ask's own list (bounded: at most 20 steps of 60 % down, then 40 up, never performScrollToNode with the
     * clock paused) until a node matching [m] is composed, then returns the matches.
     */
    private fun reach(name: String, m: SemanticsMatcher, what: String): SemanticsNodeInteractionCollection {
        fun found() = shows(m)
        if (!found()) {
            val list = compose.onNodeWithTag(ASK_LIST_TAG)
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

    private fun tap(name: String, what: String, matcher: SemanticsMatcher) {
        reach(name, matcher and hasClickAction(), "\"$what\" to tap")[0].performSemanticsAction(SemanticsActions.OnClick)
    }

    /** Every button drawn answers a 48 dp touch (the field's mic was 36 dp before this check). */
    private fun assertTouchTargets(name: String) {
        val small = compose.onAllNodes(hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .fetchSemanticsNodes()
            .filter { it.size.height / compose.density.density < 47.5f }
            .map { n ->
                val label = n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                    ?: n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } ?: "?"
                "\"$label\" ${(n.size.height / compose.density.density).toInt()} dp"
            }
        if (small.isNotEmpty()) fail("$name: buttons under 48 dp to tap:\n" + small.joinToString("\n") { "- $it" })
    }

    /** A pane's way back: Close, or News's "‹ Ask". */
    private val close = (hasText("Close") or hasText("‹ Ask")) and hasClickAction()

    /** Ask with a full life (and six things with "milk" in them, so the field's matches run past five). */
    private fun handSetUp(): Pair<MekaCore, Pair<MutableList<ShellDestination>, MutableList<OpenItem>>> {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, ThemeChoice.DARK)
        val core = core()
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            listOf("Milk", "Oat milk", "Milk powder", "Coconut milk", "Almond milk", "Milk chocolate", "Bread").forEach { core.addShopping(it) }
            core.addHabit("Read 20 pages", 5, HabitTiming.EVENING, 20, null)
            core.addMeal("Fish pie")
            core.addErrand("Post office: return the parcel")
            core.addOccasion(ShotLife.SAM_BIRTHDAY)
            core.addTrip(ShotLife.NEXT_TRIP)
            core.addRenewal("Home insurance", ObligationKind.INSURANCE, core.todayEpochDay() + 60, RenewalRepeat.YEARLY, null, null)
            core.addWaiting("The quote for the fence", "Casey", 3)
            core.tick()
        }
        compose.mainClock.autoAdvance = false
        val places = mutableListOf<ShellDestination>()
        val items = mutableListOf<OpenItem>()
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    AskRoute(core, connected = true, openPlace = { places += it }, openItem = { items += it })
                }
            }
        }
        turns()
        return core to (places to items)
    }

    /** Taps [item] by [matcher]: a place is handed to the shell; a pane springs up with Close, and Close drops it. */
    private fun opens(name: String, item: MoreItem, matcher: SemanticsMatcher, places: List<ShellDestination>) {
        val d = item.destination
        val before = places.size
        tap(name, item.label, matcher)
        if (d != null) {
            until { places.size > before }
            if (places.lastOrNull() != d) fail("$name: ${item.label} opened ${places.lastOrNull() ?: "nothing"}, not $d")
            return
        }
        until { shows(close) }
        if (!shows(close)) fail("$name: ${item.label} opened nothing (no Close)")
        compose.onAllNodes(close)[0].performSemanticsAction(SemanticsActions.OnClick)
        until { !shows(close) }
        if (shows(close)) fail("$name: Close left ${item.label} open")
    }

    /**
     * Works by hand, Ask's Life tiles: every tile (live and under To set up, unfolded if it was folded) opens its own
     * place or pane and Close drops the pane; To set up folds and unfolds; every button a 48 dp touch.
     */
    private fun handTiles() {
        val name = "ask-tiles-by-hand"
        val (core, opened) = handSetUp()
        val places = opened.first
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("ask", "cover-dark-tiles-by-hand"))
        assertTouchTargets(name)
        val lines = core.lifeLines.value
        val live = ShellNav.lifeLive(lines)
        val toSetUp = ShellNav.lifeToSetUp(lines)
        fun tile(item: MoreItem) = hasContentDescription("${item.label}, ", substring = true) and hasClickAction()
        if (toSetUp.isNotEmpty()) {
            // To set up says Open or Closed; whichever way it starts, taps fold and unfold it, ending open.
            val label = hasText(LifeTileRules.setUpLabel(toSetUp.size).uppercase()) and hasClickAction()
            fun isOpen() = reach(name, label, "To set up").fetchSemanticsNodes()[0]
                .config.getOrNull(SemanticsProperties.StateDescription) == "Open"
            val wasOpen = isOpen()
            tap(name, "To set up", label)
            until { isOpen() != wasOpen }
            if (isOpen() == wasOpen) fail("$name: To set up didn't ${if (wasOpen) "fold" else "unfold"}")
            if (!isOpen()) {
                tap(name, "To set up", label)
                until { isOpen() }
                if (!isOpen()) fail("$name: To set up didn't unfold again")
            }
            reach(name, tile(toSetUp.first()), "${toSetUp.first().label} under To set up")
        }
        toSetUp.forEach { opens(name, it, tile(it), places) }
        // The Ask hub: the live tiles a page at a time; › turns to the next, whose first tile then shows.
        val width = ApplicationProvider.getApplicationContext<ShotApplication>().resources.configuration.screenWidthDp
        val columns = LifeTileRules.columns(width.toFloat())
        val pages = os.meka.core.domain.AskHubRules.pages(live.size, columns)
        val next = hasContentDescription(os.meka.android.ask.ASK_NEXT_PAGE) and hasClickAction()
        for (p in 0 until pages) {
            val from = os.meka.core.domain.AskHubRules.pageStart(p, live.size, columns)
            val to = os.meka.core.domain.AskHubRules.pageEnd(p, live.size, columns)
            live.subList(from, to).forEach { opens(name, it, tile(it), places) }
            if (p < pages - 1) {
                tap(name, "Next page", next)
                until { shows(tile(live[to])) }
                if (!shows(tile(live[to]))) fail("$name: › didn't turn to page ${p + 2} (${live[to].label})")
                if (shows(tile(live[from]))) fail("$name: page ${p + 2} still shows ${live[from].label}")
            }
        }
        if (pages > 1 && shows(next)) fail("$name: › still turns on the last page")
        assertTouchTargets(name)
    }

    /**
     * Works by hand, the rest of Ask: the status row, each Daily row (not the bedside clock, which takes the whole app)
     * and Settings open their place or pane and Close drops it; the field shows matches as you type, a match opens where
     * it lives, "See all" opens Search everything (Close drops it), and clearing the field folds the matches.
     */
    private fun handRows() {
        val name = "ask-rows-by-hand"
        val (core, opened) = handSetUp()
        val (places, items) = opened
        assertTouchTargets(name)
        if (shows(hasTestTag(ASK_MIC_TAG))) {
            val mic = compose.onNodeWithTag(ASK_MIC_TAG).fetchSemanticsNode()
            if (mic.size.height / compose.density.density < 47.5f) fail("$name: the mic is under 48 dp to tap")
        }
        // The field: typing shows the matches; past five, "See all".
        compose.onNodeWithTag(ASK_FIELD_TAG).performTextInput("milk")
        val match = hasTestTag(ASK_MATCH_TAG)
        until { shows(match) }
        if (!shows(match)) fail("$name: typing \"milk\" showed no matches")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("ask", "cover-dark-field-by-hand"))
        assertTouchTargets(name)
        val seeAll = hasText("See all", substring = true) and hasClickAction()
        if (!shows(seeAll)) fail("$name: seven matches for \"milk\" and no See all")
        compose.onAllNodes(seeAll)[0].performSemanticsAction(SemanticsActions.OnClick)
        until { shows(close) }
        if (!shows(close)) fail("$name: See all opened nothing")
        compose.onAllNodes(close)[0].performSemanticsAction(SemanticsActions.OnClick)
        until { !shows(close) }
        if (shows(close)) fail("$name: Close left Search everything open")
        // A match opens where it lives.
        until { shows(match and hasClickAction()) }
        val beforeItems = items.size
        compose.onAllNodes(match and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
        until { items.size > beforeItems || shows(close) }
        if (items.size == beforeItems && !shows(close)) fail("$name: a match opened nothing")
        if (shows(close)) {
            compose.onAllNodes(close)[0].performSemanticsAction(SemanticsActions.OnClick)
            until { !shows(close) }
        }
        // Clearing the field folds the matches away.
        compose.onNodeWithTag(ASK_FIELD_TAG).performTextReplacement("")
        until { !shows(match) }
        if (shows(match)) fail("$name: an empty field left the matches")
        // The status row (Setup or Health), when it shows.
        val status = ShellNav.statusItem(core.healthView.value?.attention ?: 0, core.setupView.value?.toDo ?: 0)
        if (status != null) opens(name, status, hasText(status.label) and hasClickAction(), places)
        // The Ask hub: Daily's tab, then each of its rows.
        fun tab(t: os.meka.core.domain.AskTab) = hasText(t.label) and hasClickAction() and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        tap(name, "Daily", tab(os.meka.core.domain.AskTab.DAILY))
        val firstDaily = ShellNav.daily().first()
        until { shows(hasText(firstDaily.label) and hasClickAction()) }
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("ask", "cover-dark-daily-by-hand"))
        ShellNav.daily().filter { it != MoreItem.BEDSIDE }.forEach { opens(name, it, hasText(it.label) and hasClickAction(), places) }
        // Settings' tab: You and your day open the first time, its rows open their panes; another box unfolds and folds.
        tap(name, "Settings", tab(os.meka.core.domain.AskTab.SETTINGS))
        val you = ShellNav.settingsRows(os.meka.core.domain.SettingsBox.YOU, connected = true)
        until { shows(hasText(you.first().label) and hasClickAction()) }
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("ask", "cover-dark-settings-by-hand"))
        assertTouchTargets(name)
        // Every row's own pane is checked by SettingsScreensTest; here the first one opens over Ask and Close drops it.
        you.forEach { reach(name, hasText(it.label), "${it.label} in ${os.meka.core.domain.SettingsBox.YOU.title}") }
        opens(name, you.first(), hasText(you.first().label) and hasClickAction(), places)
        val data = os.meka.core.domain.SettingsBox.DATA
        val vault = hasText(MoreItem.VAULT.label) and hasClickAction()
        if (shows(vault)) fail("$name: ${data.title} is open before it's tapped")
        tap(name, data.title, hasText(data.title))
        until { shows(vault) }
        if (!shows(vault)) fail("$name: ${data.title} didn't unfold")
        // Every row of the box is drawn once it's open (scrolled to as needed), and Vault opens its place.
        ShellNav.settingsRows(data, connected = true).forEach { reach(name, hasText(it.label), "${it.label} in ${data.title}") }
        opens(name, MoreItem.VAULT, vault, places)
        tap(name, data.title, hasText(data.title))
        until { !shows(vault) }
        if (shows(vault)) fail("$name: a second tap on ${data.title} left it open")
        // Back to Life: the tiles are there again.
        tap(name, "Life", tab(os.meka.core.domain.AskTab.LIFE))
        val firstTile = ShellNav.lifeLive(core.lifeLines.value).first()
        until { shows(hasContentDescription("${firstTile.label}, ", substring = true)) }
        if (!shows(hasContentDescription("${firstTile.label}, ", substring = true))) fail("$name: Life didn't come back")
        assertTouchTargets(name)
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun askTilesByHand() = handTiles()
    @Test @Config(qualifiers = ScreenShots.COVER) fun askRowsByHand() = handRows()
}
