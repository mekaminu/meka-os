package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.ask.ABOUT_YOU_LIST_TAG
import os.meka.android.ask.AboutYouPane
import os.meka.android.ask.SETTINGS_LIST_TAG
import os.meka.android.ask.SettingsPane
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.MotionPrefs
import os.meka.android.designsystem.ThemeChoice
import os.meka.android.news.TickerChoice
import os.meka.android.shell.MoreItem
import os.meka.android.shell.ShellNav
import os.meka.core.domain.AboutField
import os.meka.core.domain.AboutYouRules
import os.meka.core.domain.DoneShow
import os.meka.core.domain.MotionCheckRules
import os.meka.core.domain.MotionChoice
import os.meka.core.domain.MotionRules
import os.meka.core.domain.TickerMode
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random
import kotlin.test.fail

/**
 * The quality gate, slice 25 (build plan: "the screens outside Ask's places"): Ask → Settings (not connected, connected,
 * and with Appearance unfolded) and Settings → About you (nothing typed, a name, and every line filled with two
 * children), each on the Fold's cover and inner screens, Dark (and the full one in Light). Saved under `settings/` and
 * `about-you/` (the `fold-screens` artifact, then the `screens` branch's docs/screens/fold/…) and read for nonsense, cut
 * lines, words broken in two, dates, times and numbers split across lines, and each empty field's hint on one line.
 * Invented data only (rule 13): the numbers are Ofcom's drama ranges, the places Oakfield and Riverside.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class SettingsScreensTest {
    /** Outermost: a hang fails within minutes with every thread's stack, instead of holding CI to its limit. */
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(47), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private companion object { const val SETTLE_MS = 3_000L }

    private enum class Fill { EMPTY, ONE, MANY }

    /** Every About you line, as Meka might type it (invented). */
    private val typed = mapOf(
        AboutField.SHOWN_NAME to "Jordan",
        AboutField.PARTNER_NAME to "Sam",
        AboutField.PARTNER_PHONE to "07700 900123",
        AboutField.PARTNER_EMAIL to "sam@example.com",
        AboutField.HOME to "Oakfield",
        AboutField.WORK to "Riverside",
        AboutField.ROUTE to "South Rail to Oakfield Junction, then Metro; or North Rail to Riverside",
        AboutField.HOME_STATION to "Oakfield",
        AboutField.STATION_WALK to "10, 15",
        AboutField.SCHOOL_WEBSITE to "oakfield.sch.uk",
        AboutField.SCHOOL_MAIL to "office@oakfield.sch.uk, parentmail.co.uk",
        AboutField.CLUBS to "OFC Oakfield Town, HRFC",
        AboutField.MOBILE to "07700 900456",
        AboutField.ASSISTANT_NUMBER to "01632 960001",
        AboutField.VOICEMAIL_NUMBER to "07700 900222",
        AboutField.OFFICIAL_NUMBERS to "police 01632 960000, bank 0306 999 0000",
    )

    private suspend fun fill(core: MekaCore, fill: Fill) {
        if (fill == Fill.EMPTY) return
        check(!core.setAboutYou(AboutField.SHOWN_NAME, "Jordan").refused)
        if (fill != Fill.MANY) return
        typed.forEach { (f, t) -> core.setAboutYou(f, t).let { if (it.refused) fail("${f.name}: \"$t\" refused: ${it.line}") } }
        // Watch for requests is on by default once a partner is named.
        check(!core.addAboutYouChild("Alex, U7, Oakfield School").refused)
        check(!core.addAboutYouChild("Robin, U10, Oakfield School").refused)
    }

    private fun setUp(theme: ThemeChoice, fill: Fill): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val core = core()
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            fill(core, fill)
            core.tick()
        }
        compose.mainClock.autoAdvance = false
        return core
    }

    private fun settle(area: String, name: String) {
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path(area, name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertDatesWhole(compose, name)
        ScreenShots.assertClocksWhole(compose, name)
        ScreenShots.assertNumbersWhole(compose, name)
    }

    private fun settings(name: String, connected: Boolean, appearanceOpen: Boolean = false, theme: ThemeChoice = ThemeChoice.DARK) {
        val core = setUp(theme, Fill.EMPTY)
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    SettingsPane(
                        core, connected = connected, pane = null, onClose = {}, open = {},
                        appearanceOpen = appearanceOpen, toggleAppearance = {}, openTopics = {}, playOpening = {},
                    )
                }
            }
        }
        settle("settings", name)
        ScreenShots.assertShows(compose, name, MoreItem.SETTINGS.label)
        val rows = ShellNav.settings(connected)
        if (rows.isEmpty()) fail("$name: Settings has no rows")
        // The first rows are on screen on both sizes; every row's name and line reads whole where it's drawn.
        ScreenShots.assertShows(compose, name, rows.first().label)
        ScreenShots.assertReadable(compose, name, rows.flatMap { listOf(it.label, it.line) }, mustShow = false)
        ScreenShots.assertEachOneLine(compose, name, rows.map { it.label })
        ScreenShots.assertNoLoneWords(compose, name, rows.map { it.line } + ShellNav.moreLine(MoreItem.WORK, 0, false, null, null))
    }

    private fun aboutYou(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK) {
        val core = setUp(theme, fill)
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    AboutYouPane(core, onClose = {})
                }
            }
        }
        settle("about-you", name)
        val v = core.aboutYouView.value
        ScreenShots.assertShows(compose, name, AboutYouRules.TITLE)
        ScreenShots.assertShows(compose, name, v.summary)
        // An empty field shows one short example on one line (the quality gate's rule for hints).
        ScreenShots.assertEachOneLine(compose, name, AboutField.entries.map { it.hint } + AboutYouRules.CHILD_HINT)
        ScreenShots.assertEachOneLine(compose, name, AboutField.entries.map { it.label })
        ScreenShots.assertNoLoneWords(compose, name, v.rows.map { it.line } + listOfNotNull(v.watchLine))
        // A field's line sits with its field, clear of the next label (slice 25's shots: they ran together).
        assertFieldsApart(name, v.rows.map { it.label })
        ScreenShots.assertReadable(compose, name, v.rows.flatMap { listOf(it.label, it.line) } +
            v.children.flatMap { listOf(it.name, it.line) }, mustShow = false)
        when (fill) {
            Fill.EMPTY -> {
                if (v.summary != AboutYouRules.WELCOME) fail("$name: \"${v.summary}\" with nothing typed")
                if (v.rows.any { it.value != null }) fail("$name: a line holds something with nothing typed")
            }
            Fill.ONE -> if (v.rows.count { it.value != null } != 1) fail("$name: ${v.rows.count { it.value != null }} lines held for one")
            Fill.MANY -> {
                val empty = v.rows.filter { it.value == null }.map { it.field.name }
                if (empty.isNotEmpty()) fail("$name: still empty after typing every line: $empty")
                if (v.children.size != 2) fail("$name: ${v.children.size} children for two")
                if (v.watchLine == null || !v.partnerWatch) fail("$name: Watch for requests isn't on with a partner named")
            }
        }
    }

    /** Each label after the first sits at least 20 dp below the line (or field) above it. */
    private fun assertFieldsApart(name: String, labels: List<String>) {
        val d = compose.density.density
        val tops = labels.mapNotNull { l ->
            compose.onAllNodes(androidx.compose.ui.test.hasText(l), useUnmergedTree = true).fetchSemanticsNodes()
                .firstOrNull()?.boundsInRoot?.let { l to it.top }
        }
        val bottoms = ScreenShots.laidOutBounds(compose)
        tops.drop(1).forEach { (label, top) ->
            val above = bottoms.filter { it < top - 0.5f }.maxOrNull() ?: return@forEach
            val gap = (top - above) / d
            if (gap < 20f) fail("$name: \"$label\" sits ${gap.toInt()} dp under the line above it (at least 20 dp)")
        }
    }


    /** Runs the main looper and the frames by turns (a tap's change reaches the recomposer through the looper). */
    private fun turns(ms: Long = SETTLE_MS) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    /** Waits (core thread, looper and frames by turns) until [done] holds, then lets the screen settle for [settle] ms. */
    private fun until(settle: Long = SETTLE_MS, done: () -> Boolean) {
        repeat(40) {
            if (done()) return@repeat
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        turns(settle)
    }

    /**
     * Scrolls the pane's own list [listTag] (bounded: at most 20 steps of 60 % down, then 40 up, never
     * performScrollToNode with the clock paused) until a node matching [m] is composed, then returns the matches.
     */
    private fun reach(listTag: String, name: String, m: SemanticsMatcher, what: String): SemanticsNodeInteractionCollection {
        fun found() = compose.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()
        if (!found()) {
            val list = compose.onNodeWithTag(listTag)
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

    private fun tap(listTag: String, name: String, what: String, matcher: SemanticsMatcher, last: Boolean = false) {
        val n = reach(listTag, name, matcher and hasClickAction(), "\"$what\" to tap")
        n[if (last) n.fetchSemanticsNodes().size - 1 else 0].performSemanticsAction(SemanticsActions.OnClick)
    }

    /** Types [text] into the field TalkBack reads as [description] and presses Done on the keyboard. */
    private fun type(listTag: String, name: String, description: String, text: String, replace: Boolean = false) {
        val field = hasContentDescription(description) and hasSetTextAction()
        val node = reach(listTag, name, field, "\"$description\" field")[0]
        if (replace) node.performTextReplacement(text) else node.performTextInput(text)
        turns(500)
        compose.onAllNodes(field)[0].performImeAction()
    }

    private fun shows(m: SemanticsMatcher) = compose.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()

    /**
     * Every button and switch drawn answers a 48 dp touch (Appearance's Topics › and Play the opening, and About you's
     * Remove on a child, were about 24–34 dp before this check).
     */
    private fun assertTouchTargets(name: String) {
        val tappable = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button) or
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        val small = compose.onAllNodes(hasClickAction() and tappable)
            .fetchSemanticsNodes()
            .filter { it.size.height / compose.density.density < 47.5f }
            .map { n ->
                val label = n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                    ?: n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } ?: "?"
                "\"$label\" ${(n.size.height / compose.density.density).toInt()} dp"
            }
        if (small.isNotEmpty()) fail("$name: buttons under 48 dp to tap:\n" + small.joinToString("\n") { "- $it" })
    }

    /**
     * Works by hand, Settings: each row opens its own place; Appearance unfolds in place, where a theme, a Motion, a
     * Done today and a News ticker chip each take, Topics › and Play the opening answer, and a second tap folds it.
     */
    private fun handSettings() {
        val name = "settings-by-hand"
        val t = SETTINGS_LIST_TAG
        val core = setUp(ThemeChoice.DARK, Fill.EMPTY)
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        val opened = mutableListOf<MoreItem>()
        var topics = 0
        var opening = 0
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    var unfolded by remember { mutableStateOf(false) }
                    SettingsPane(
                        core, connected = true, pane = null, onClose = {}, open = { opened += it },
                        appearanceOpen = unfolded, toggleAppearance = { unfolded = !unfolded },
                        openTopics = { topics++ }, playOpening = { opening++ },
                    )
                }
            }
        }
        turns()
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("settings", "cover-dark-settings-by-hand"))
        assertTouchTargets(name)
        // Every row that opens a place opens that one (MEKA's AI opens nothing; Appearance unfolds in place).
        ShellNav.settings(true).filter { ShellNav.opens(it) && !ShellNav.unfoldsInPlace(it) }.forEach { item ->
            tap(t, name, item.label, hasText(item.label))
            until(300) { opened.lastOrNull() == item }
            if (opened.lastOrNull() != item) fail("$name: ${item.label} opened ${opened.lastOrNull()?.label ?: "nothing"}")
        }
        // Appearance unfolds in place.
        val appearance = hasText(MoreItem.APPEARANCE.label)
        val light = hasText(ThemeChoice.LIGHT.label) and hasClickAction()
        tap(t, name, MoreItem.APPEARANCE.label, appearance)
        until { shows(light) }
        if (!shows(light)) fail("$name: Appearance didn't unfold its choices")
        assertTouchTargets(name)
        // A theme chip takes, and Dark puts it back.
        tap(t, name, ThemeChoice.LIGHT.label, light)
        until { ThemeChoice.load(context) == ThemeChoice.LIGHT }
        if (ThemeChoice.load(context) != ThemeChoice.LIGHT) fail("$name: Light didn't take (${ThemeChoice.load(context)})")
        tap(t, name, ThemeChoice.DARK.label, hasText(ThemeChoice.DARK.label))
        until { ThemeChoice.load(context) == ThemeChoice.DARK }
        if (ThemeChoice.load(context) != ThemeChoice.DARK) fail("$name: Dark didn't take back")
        // Motion: the chip not lit now ("Off" is also the ticker's, so Expressive or Subtle).
        val lit = MotionRules.lit(MotionPrefs.state(context).value)
        val motion = listOf(MotionChoice.EXPRESSIVE, MotionChoice.SUBTLE).first { it != lit }
        tap(t, name, motion.label, hasText(motion.label))
        until { MotionPrefs.state(context).value == motion }
        if (MotionPrefs.state(context).value != motion) fail("$name: ${motion.label} didn't take")
        // Done today on Today: a chip other than the one chosen, kept in the core.
        val shown = core.doneTodayChoice.value.show
        val done = DoneShow.entries.first { it != shown }
        tap(t, name, done.label, hasText(done.label))
        until { core.doneTodayChoice.value.show == done }
        if (core.doneTodayChoice.value.show != done) fail("$name: ${done.label} didn't take (${core.doneTodayChoice.value.show})")
        // News ticker: Calm or Always moving, whichever isn't chosen.
        val ticker = listOf(TickerMode.CALM, TickerMode.MOVING).first { it != TickerChoice.state(context).value }
        tap(t, name, ticker.label, hasText(ticker.label))
        until { TickerChoice.state(context).value == ticker }
        if (TickerChoice.state(context).value != ticker) fail("$name: ${ticker.label} didn't take")
        assertTouchTargets(name)
        tap(t, name, "Topics ›", hasText("Topics ›"))
        until(300) { topics == 1 }
        if (topics != 1) fail("$name: Topics › opened nothing")
        tap(t, name, MotionCheckRules.PLAY_OPENING, hasText(MotionCheckRules.PLAY_OPENING))
        until(300) { opening == 1 }
        if (opening != 1) fail("$name: ${MotionCheckRules.PLAY_OPENING} played nothing")
        // A second tap folds it.
        tap(t, name, MoreItem.APPEARANCE.label, appearance)
        until { !shows(light) }
        if (shows(light)) fail("$name: a second tap on Appearance left it unfolded")
    }

    /**
     * Works by hand, About you: Done on a field keeps what's typed; a blank clears it and Undo puts it back; Watch for
     * requests turns off and on; Remove takes a child off and Undo brings them back; the child field adds one.
     */
    private fun handAboutYou() {
        val name = "about-you-by-hand"
        val t = ABOUT_YOU_LIST_TAG
        val core = setUp(ThemeChoice.DARK, Fill.MANY)
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    AboutYouPane(core, onClose = {})
                }
            }
        }
        turns()
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("about-you", "cover-dark-about-you-by-hand"))
        assertTouchTargets(name)
        fun v() = core.aboutYouView.value
        fun value(f: AboutField) = v().rows.first { it.field == f }.value
        val undo = hasText("Undo")
        // A field keeps what's typed.
        type(t, name, AboutField.SHOWN_NAME.spokenHint, "Jordan Lee", replace = true)
        until { value(AboutField.SHOWN_NAME) == "Jordan Lee" }
        if (value(AboutField.SHOWN_NAME) != "Jordan Lee") fail("$name: Done on Your name kept \"${value(AboutField.SHOWN_NAME)}\"")
        // A blank clears it; Undo puts it back.
        val home = value(AboutField.HOME) ?: fail("$name: Home isn't kept")
        type(t, name, AboutField.HOME.spokenHint, "", replace = true)
        until { value(AboutField.HOME) == null }
        if (value(AboutField.HOME) != null) fail("$name: a blank Home kept \"${value(AboutField.HOME)}\"")
        tap(t, name, "Undo", undo)
        until { value(AboutField.HOME) == home }
        if (value(AboutField.HOME) != home) fail("$name: Undo didn't put Home back (\"${value(AboutField.HOME)}\")")
        // Watch for requests, off and on.
        val watch = hasContentDescription("Watch for requests")
        val on = v().partnerWatch
        tap(t, name, "Watch for requests", watch)
        until { v().partnerWatch != on }
        if (v().partnerWatch == on) fail("$name: Watch for requests stayed ${if (on) "on" else "off"}")
        tap(t, name, "Watch for requests", watch)
        until { v().partnerWatch == on }
        if (v().partnerWatch != on) fail("$name: Watch for requests didn't turn back")
        assertTouchTargets(name)
        // Remove a child, then Undo (the children's Undo is the last on screen).
        val child = v().children.lastOrNull() ?: fail("$name: no children")
        val kids = v().children.size
        tap(t, name, "Remove", hasContentDescription("Remove ${child.name}"))
        until { v().children.size < kids }
        if (v().children.any { it.name == child.name }) fail("$name: Remove left ${child.name}")
        tap(t, name, "Undo", undo, last = true)
        until { v().children.size == kids }
        if (v().children.none { it.name == child.name }) fail("$name: Undo didn't bring ${child.name} back")
        // The child field adds one.
        type(t, name, v().childDescription, "Casey, U5, Oakfield School")
        until { v().children.any { it.name == "Casey" } }
        if (v().children.none { it.name == "Casey" }) fail("$name: Done on the child field added nobody")
        assertTouchTargets(name)
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun settingsByHand() = handSettings()
    @Test @Config(qualifiers = ScreenShots.COVER) fun aboutYouByHand() = handAboutYou()

    @Test @Config(qualifiers = ScreenShots.COVER) fun settingsCover() = settings("cover-dark", connected = false)
    @Test @Config(qualifiers = ScreenShots.COVER) fun settingsCoverConnected() = settings("cover-dark-connected", connected = true)
    @Test @Config(qualifiers = ScreenShots.COVER) fun settingsCoverAppearance() = settings("cover-dark-appearance", connected = true, appearanceOpen = true)
    @Test @Config(qualifiers = ScreenShots.COVER) fun settingsCoverLight() = settings("cover-light-connected", connected = true, theme = ThemeChoice.LIGHT)
    @Test @Config(qualifiers = ScreenShots.INNER) fun settingsInner() = settings("inner-dark-connected", connected = true)

    @Test @Config(qualifiers = ScreenShots.COVER) fun aboutYouCoverEmpty() = aboutYou("cover-dark-empty", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun aboutYouCoverOne() = aboutYou("cover-dark-one", Fill.ONE)
    @Test @Config(qualifiers = ScreenShots.COVER) fun aboutYouCoverMany() = aboutYou("cover-dark-many", Fill.MANY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun aboutYouCoverManyLight() = aboutYou("cover-light-many", Fill.MANY, ThemeChoice.LIGHT)
    @Test @Config(qualifiers = ScreenShots.INNER) fun aboutYouInnerEmpty() = aboutYou("inner-dark-empty", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.INNER) fun aboutYouInnerMany() = aboutYou("inner-dark-many", Fill.MANY)
}
