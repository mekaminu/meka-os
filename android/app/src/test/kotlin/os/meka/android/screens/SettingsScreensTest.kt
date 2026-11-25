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
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToIndex
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
import os.meka.android.ask.ABOUT_EDIT_SHEET_TAG
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
import os.meka.core.domain.AskContext
import os.meka.core.domain.AskRules
import os.meka.core.domain.TalkTurn
import os.meka.core.facade.AiApi
import os.meka.core.facade.AiStatusReply
import os.meka.core.facade.AskReply
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
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

    private fun core(transport: SyncTransport? = null): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(47), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private companion object {
        const val SETTLE_MS = 3_000L
        /** The connected phone's AI: on, $1.82 of a $20 month (invented). */
        val AI_ON = AiStatusReply("on", null, spentCents = 182, budgetCents = 2_000, level = "ok")
    }

    private enum class Fill { EMPTY, ONE, MANY }

    /** Every About you line, as Meka might type it (invented). */
    private val typed = mapOf(
        AboutField.SHOWN_NAME to "Jordan",
        AboutField.PARTNER_NAME to "Morgan",
        AboutField.PARTNER_PHONE to "07700 900123",
        AboutField.PARTNER_EMAIL to "morgan@example.com",
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

    /**
     * Walk-through 2026-11-22 A8 ("the connected Settings shot says the device isn't connected"): the connected shots'
     * phone has a server whose AI is on, so Navilife's AI reads "On · …" there, not "Ask works once this device is
     * connected". Sync goes to an in-memory server.
     */
    private class ConnectedServer : SyncTransport, AiApi {
        private val sync = SyncService(InMemoryServerOpStore())
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun ask(question: String, context: AskContext, history: List<TalkTurn>, voice: Boolean): AskReply =
            AskReply.Unavailable("off", null)
        /** How many times the AI's status was asked: the pane asks once it opens, off the main thread. */
        val asked = java.util.concurrent.atomic.AtomicInteger()
        override suspend fun aiStatus(): AiStatusReply = AI_ON.also { asked.incrementAndGet() }
    }

    private fun setUp(theme: ThemeChoice, fill: Fill, server: ConnectedServer? = null): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val core = core(server)
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
        val server = if (connected) ConnectedServer() else null
        val core = setUp(theme, Fill.EMPTY, server)
        val askedBefore = server?.asked?.get() ?: 0
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
        // The AI's status lands off the main thread: wait for the pane's ask, so the shot isn't taken mid-change.
        if (server != null) until { server.asked.get() > askedBefore }
        settle("settings", name)
        ScreenShots.assertShows(compose, name, MoreItem.SETTINGS.label)
        val rows = ShellNav.settings(connected)
        if (rows.isEmpty()) fail("$name: Settings has no rows")
        // The first rows are on screen on both sizes; every row's name and line reads whole where it's drawn.
        ScreenShots.assertShows(compose, name, rows.first().label)
        ScreenShots.assertReadable(compose, name, rows.flatMap { listOf(it.label, it.line) }, mustShow = false)
        ScreenShots.assertEachOneLine(compose, name, rows.map { it.label })
        ScreenShots.assertNoLoneWords(compose, name, rows.map { it.line } + ShellNav.moreLine(MoreItem.WORK, 0, false, null, null))
        // Walk-through 2026-11-22 A8: a connected phone's Navilife's AI never says it isn't connected.
        val aiLine = AskRules.statusView(AI_ON.state, AI_ON.reason, AI_ON.spentCents, AI_ON.budgetCents, AI_ON.level).line
        if (connected) {
            if (ScreenShots.anyOnScreen(compose, listOf(AskRules.NOT_CONNECTED_LINE))) fail("$name: connected, yet Navilife's AI says \"${AskRules.NOT_CONNECTED_LINE}\"")
        } else if (ScreenShots.anyOnScreen(compose, listOf(aiLine))) fail("$name: not connected, yet Navilife's AI reads \"$aiLine\"")
        if (theme == ThemeChoice.LIGHT && connected) assertAiRowInsideObiBox(name)
        if (appearanceOpen) shootAppearance(name)
    }

    /**
     * Walk-through 2026-11-22 A12 ("the Navilife's AI card overlaps the Obi card", `settings/cover-light-connected`):
     * Navilife's AI is the Obi box's last row, so between the box's side and the rows' words (the strip the hairlines
     * never reach) nothing is drawn from "What Navilife may do" down to "Navilife's AI": no second rounded edge sitting
     * on the box's last divider. Read at the Obi heading's left, less half the box's padding.
     */
    private fun assertAiRowInsideObiBox(name: String) {
        fun bounds(text: String) = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInRoot
            ?: fail("$name: \"$text\" isn't on screen")
        val heading = bounds("Obi")
        val above = bounds(MoreItem.AUTONOMY.label)
        val ai = bounds(MoreItem.AI.label)
        val shot = compose.onNodeWithTag("screen").captureToImage().asAndroidBitmap()
        val inset = with(compose.density) { (os.meka.android.designsystem.MekaSpace.m.toPx() / 2f).toInt() }.coerceAtLeast(2)
        val x = heading.left.toInt() - inset
        val bg = shot.getPixel(x, heading.center.y.toInt())
        for (y in above.top.toInt() until ai.bottom.toInt().coerceAtMost(shot.height)) {
            val p = shot.getPixel(x, y)
            val d = maxOf(kotlin.math.abs(android.graphics.Color.red(p) - android.graphics.Color.red(bg)),
                kotlin.math.abs(android.graphics.Color.green(p) - android.graphics.Color.green(bg)),
                kotlin.math.abs(android.graphics.Color.blue(p) - android.graphics.Color.blue(bg)))
            if (d > 14) fail("$name: a line crosses the Obi box's side strip at y $y (x $x, off by $d): Navilife's AI draws a card of its own inside the box")
        }
    }

    /**
     * Walk-through 2026-11-15 A12 ("settings appearance = connected"): Appearance sits low on the list, so its unfolded
     * choices were under the cover's shot and Appearance open drew the connected shot byte for byte. Swipe down to the
     * choices' last line (Play the opening) and shoot that as `<name>-below`; when none of the choices was on the opening
     * screen, its top (a copy of the connected shot) is dropped.
     */
    private fun shootAppearance(name: String) {
        val differs = listOf(MotionCheckRules.PLAY_OPENING, "Topics ›", TickerMode.CALM.label, DoneShow.entries.first().label)
        turns(1_000) // the AI row's line may still be changing (connected phones): let the list's sizes settle
        val topIsCopy = !ScreenShots.anyOnScreen(compose, differs)
        val below = ScreenShots.shootBelow(compose, "settings", name, MotionCheckRules.PLAY_OPENING, settle = { turns(1_000) })
        if (!below && topIsCopy) fail("$name: Appearance's choices aren't on screen")
        if (below && topIsCopy) java.io.File(ScreenShots.path("settings", name)).delete()
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
        // Walk-through week 5, A17: no open input box on the page; each line is a row that opens its sheet.
        if (compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()) fail("$name: an open text box on the page")
        v.rows.forEach { r ->
            if (compose.onAllNodes(hasContentDescription(AboutYouRules.rowSpoken(r)) and hasClickAction()).fetchSemanticsNodes().isEmpty() &&
                r.field == AboutField.SHOWN_NAME) fail("$name: Your name isn't a row that opens its sheet")
        }
        ScreenShots.assertEachOneLine(compose, name, AboutField.entries.map { it.label })
        ScreenShots.assertNoLoneWords(compose, name, v.rows.mapNotNull { it.line } + listOfNotNull(v.watchLine))
        // A field's line sits with its field, clear of the next label (slice 25's shots: they ran together).
        assertFieldsApart(name, v.rows.map { it.label })
        // Walk-through 2026-11-02 A9: Watch for requests' On pill drawn 32 dp, every target whole.
        ScreenShots.assertWholeTargets(compose, name)
        ScreenShots.assertReadable(compose, name, v.rows.flatMap { listOfNotNull(it.label, it.line) } +
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
        // Walk-through 2026-11-15 A12 ("settings by-hand = connected"): the by-hand shot was the opening (connected)
        // screen; it is taken again here, Appearance unfolded with the Motion, Done today and ticker chips just changed.
        ScreenShots.reshootAfterHand(compose, "settings", "cover-dark-settings-by-hand")
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
        fun row(f: AboutField) = v().rows.first { it.field == f }
        fun sheetOpen() = compose.onAllNodesWithTag(ABOUT_EDIT_SHEET_TAG).fetchSemanticsNodes().isNotEmpty()
        // Walk-through week 5, A17: a row opens its sheet, holding what's there; Save keeps what's typed and closes it.
        if (sheetOpen()) fail("$name: a sheet is open before any row is tapped")
        tap(t, name, "Your name", hasContentDescription(AboutYouRules.rowSpoken(row(AboutField.SHOWN_NAME))))
        until { sheetOpen() }
        if (!sheetOpen()) fail("$name: Your name's row opened no sheet")
        val held = row(AboutField.SHOWN_NAME).value!!
        if (compose.onAllNodesWithText(held, useUnmergedTree = true).fetchSemanticsNodes().size < 2) fail("$name: the sheet doesn't hold \"$held\"")
        type(t, name, AboutField.SHOWN_NAME.spokenHint, "Jordan Lee", replace = true)
        until { value(AboutField.SHOWN_NAME) == "Jordan Lee" && !sheetOpen() }
        if (value(AboutField.SHOWN_NAME) != "Jordan Lee") fail("$name: Save on Your name kept \"${value(AboutField.SHOWN_NAME)}\"")
        if (sheetOpen()) fail("$name: Your name's sheet stayed open after Save")
        // A refused line keeps the sheet open with why, in the sheet.
        tap(t, name, "Partner's email", hasContentDescription(AboutYouRules.rowSpoken(row(AboutField.PARTNER_EMAIL))))
        until { sheetOpen() }
        type(t, name, AboutField.PARTNER_EMAIL.spokenHint, "not an email", replace = true)
        turns()
        if (!sheetOpen()) fail("$name: a refused email closed its sheet")
        if (compose.onAllNodesWithText(AboutYouRules.NOT_EMAIL, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) fail("$name: the sheet doesn't say why")
        tap(t, name, "Close", hasText("Close") and hasAnyAncestor(hasTestTag(ABOUT_EDIT_SHEET_TAG)))
        until { !sheetOpen() }
        if (value(AboutField.PARTNER_EMAIL) == "not an email") fail("$name: the refused email was kept")
        // Clear clears it; Undo under the row puts it back.
        val home = value(AboutField.HOME) ?: fail("$name: Home isn't kept")
        tap(t, name, "Home", hasContentDescription(AboutYouRules.rowSpoken(row(AboutField.HOME))))
        until { sheetOpen() }
        tap(t, name, "Clear", hasContentDescription("${AboutYouRules.CLEAR} Home"))
        until { value(AboutField.HOME) == null && !sheetOpen() }
        if (value(AboutField.HOME) != null) fail("$name: Clear kept Home \"${value(AboutField.HOME)}\"")
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
        // + Add a child opens its sheet, which adds one.
        tap(t, name, AboutYouRules.ADD_CHILD, hasContentDescription(v().childDescription) and hasClickAction())
        until { sheetOpen() }
        type(t, name, v().childDescription, "Casey, U5, Oakfield School")
        until { v().children.any { it.name == "Casey" } && !sheetOpen() }
        if (v().children.none { it.name == "Casey" }) fail("$name: Add on the child sheet added nobody")
        assertTouchTargets(name)
        // Walk-through 2026-11-15 A14 ("About you by-hand starting mid-scroll"): the child field left the list scrolled to
        // its foot, so the shot began on a field cut by the top edge with no title. It goes back to the top first, so the
        // shot opens on Close and the title with the hand's new name under them.
        compose.onNodeWithTag(t).performScrollToIndex(0)
        turns()
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        fun wholeOnScreen(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes()
            .any { it.boundsInRoot.top >= screen.top && it.boundsInRoot.bottom <= screen.bottom }
        if (!wholeOnScreen(AboutYouRules.TITLE)) fail("$name: the by-hand shot doesn't open on the title (still scrolled)")
        if (!wholeOnScreen("Jordan Lee")) fail("$name: the by-hand shot doesn't show the name the hand typed")
        // Walk-through 2026-11-08 A20: the shot shows what the hand changed (the new name), not the opening page again.
        ScreenShots.reshootAfterHand(compose, "about-you", "cover-dark-about-you-by-hand")
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
