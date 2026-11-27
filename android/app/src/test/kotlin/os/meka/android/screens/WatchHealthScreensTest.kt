package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.ask.HealthDevice
import os.meka.android.ask.HealthPane
import os.meka.android.ask.WATCH_LINK_ROW_TAG
import os.meka.android.ask.WatchPane
import os.meka.android.goals.GOAL_ADD_SHEET_TAG
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.core.domain.HealthRules
import os.meka.core.domain.LoneWordGlue
import os.meka.core.domain.WatchLinkRules
import os.meka.core.facade.AccountsApi
import os.meka.core.facade.ConnectStart
import os.meka.core.facade.ConnectedAccount
import os.meka.core.facade.DeviceLinkApi
import os.meka.core.facade.HealthApi
import os.meka.core.facade.LinkRefusedException
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.wire.DeviceLinkCodec
import os.meka.core.wire.HealthCodec
import kotlin.random.Random
import kotlin.test.fail

/**
 * The quality gate, slice 29 (build plan: "Watch's and Health's shots"): Ask → Settings → Watch (not connected,
 * connected with none linked, one watch, and two with a wrong code typed) and Health (not connected, connected and
 * working, and a full set with a sign-in that ran out), each on the Fold's cover and inner screens, Dark (and the full
 * one in Light). Saved under `watch/` and `health/` and read for nonsense, cut lines, broken words, split dates, times
 * and numbers and a word left alone; Health's "Checked …" must read the core's clock. The first "works by hand" tap on
 * Watch: type the watch's code, tap Link, and the watch is linked. Both talk to a stand-in server held in the test.
 * Invented data only (rule 13): example.com addresses.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class WatchHealthScreensTest {
    /** Outermost: a hang fails within minutes with every thread's stack, instead of holding CI to its limit. */
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London
    private val minute = 60_000L
    private val day = 24 * 60 * minute

    private companion object {
        const val SETTLE_MS = 3_000L
        const val LINKED_BY_HAND = "Galaxy Watch7"
    }

    private enum class Fill { EMPTY, ONE, MANY }

    /** Navilife's server, as far as these two screens ask it: sync, the household's health, the accounts, the watches. */
    private inner class Server : SyncTransport, DeviceLinkApi, HealthApi, AccountsApi {
        val service = SyncService(InMemoryServerOpStore())
        val watches = mutableListOf<DeviceLinkCodec.Watch>()
        var accounts = listOf<ConnectedAccount>()
        var refuse: String? = null
        override suspend fun push(request: PushRequest) = service.push(request)
        override suspend fun pull(request: PullRequest) = service.pull(request)
        override suspend fun linkWatch(code: String): DeviceLinkCodec.Linked {
            refuse?.let { throw LinkRefusedException(it) }
            val id = "watch00000000000000a" + watches.size
            // Week 5 A11: the watch linked by hand has its own name, so its shot isn't One's (a Galaxy Watch linked today).
            watches.add(0, DeviceLinkCodec.Watch(id, LINKED_BY_HAND, now))
            return DeviceLinkCodec.Linked(id, LINKED_BY_HAND)
        }
        var listed = 0
        override suspend fun linkedWatches(): List<DeviceLinkCodec.Watch> { listed++; return watches.toList() }
        override suspend fun unlinkWatch(id: String) { watches.removeAll { it.id == id } }
        override suspend fun answerDeviceJoin(requestId: String, allow: Boolean) = "allowed"
        override suspend fun householdHealth() = HealthCodec.Response(
            push = HealthCodec.Response.PUSH_ON, calls = true, speech = true, atMs = now, macs = 1,
            lastPushAtMs = now - 20 * minute, lastPushResult = HealthCodec.Response.PUSH_SENT,
        )
        override suspend fun startConnect(provider: String, editing: Boolean): ConnectStart = ConnectStart.NotSetUp
        override suspend fun accounts(): List<ConnectedAccount> = accounts
        override suspend fun stopEditing(provider: String, email: String): List<ConnectedAccount> = accounts
    }

    private fun core(server: Server?): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(29), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private fun setUp(theme: ThemeChoice, server: Server?): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val core = core(server)
        context.useCoreForScreens(core)
        runBlocking { core.tick() }
        return core
    }

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) { content() }
            }
        }
    }

    private fun settle(area: String, name: String, shoot: Boolean = true) {
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        if (shoot) compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path(area, name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertDatesWhole(compose, name)
        ScreenShots.assertClocksWhole(compose, name)
        ScreenShots.assertNumbersWhole(compose, name)
    }

    // ---- Watch ----

    private fun watch(name: String, fill: Fill, connected: Boolean = true, theme: ThemeChoice = ThemeChoice.DARK, wrongCode: Boolean = false): MekaCore {
        val server = if (connected) Server() else null
        if (fill != Fill.EMPTY) server!!.watches += DeviceLinkCodec.Watch("watch00000000000000b1", WatchLinkRules.DEFAULT_NAME, now - 2 * 60 * minute)
        if (fill == Fill.MANY) server!!.watches += DeviceLinkCodec.Watch("watch00000000000000b2", "Galaxy Watch8 Classic", now - 9 * day)
        val core = setUp(theme, server)
        runBlocking { core.refreshWatches() }
        show { WatchPane(core, onClose = {}) }
        // A wrong code's shot is the code's sheet (below), so the page itself isn't shot under its name.
        settle("watch", name, shoot = !wrongCode)
        val v = core.watchLinkView.value ?: fail("$name: Watch never read the server")
        val row = WatchLinkRules.linkRow(v.rows.size)
        ScreenShots.assertShows(compose, name, "Watch")
        ScreenShots.assertShows(compose, name, LoneWordGlue.kept(v.summary))
        ScreenShots.assertShows(compose, name, row.label)
        ScreenShots.assertEachOneLine(compose, name, listOf(row.label) + v.rows.map { it.title })
        val lines = listOf(v.summary, v.how, row.line) + v.rows.map { it.line } + listOfNotNull(v.problem)
        ScreenShots.assertNoLoneWords(compose, name, lines)
        ScreenShots.assertNoDotStartingALine(compose, name, lines)
        ScreenShots.assertReadable(compose, name, v.rows.map { it.title }, mustShow = false)
        // Walk-through week 5, A17: the code is typed in a sheet opened from "+ Link a watch", not an open box.
        if (codeBoxes().isNotEmpty()) fail("$name: an open \"${WatchLinkRules.CODE_SPOKEN}\" box on the page")
        when {
            !connected -> if (v.problem != WatchLinkRules.NOT_CONNECTED) fail("$name: not connected, but the problem says \"${v.problem}\"")
            fill == Fill.EMPTY -> if (v.rows.isNotEmpty() || v.problem != null) fail("$name: $v with nothing linked")
            fill == Fill.ONE -> {
                if (v.rows.size != 1) fail("$name: ${v.rows.size} rows for one watch")
                ScreenShots.awaitShows(compose, name, "Linked today")
            }
            fill == Fill.MANY -> {
                if (v.rows.size != 2) fail("$name: ${v.rows.size} rows for two watches")
                if (row.label != WatchLinkRules.LINK_ANOTHER_ROW) fail("$name: two watches linked, but the row says \"${row.label}\"")
                // Walk-through 2026-11-22 A14: the two-watch shot shows the two watches and no error; the wrong code
                // has shots of its own.
                if (v.problem != null) fail("$name: two watches linked, but it says \"${v.problem}\"")
                // The newest first.
                if (v.rows.first().title != WatchLinkRules.DEFAULT_NAME) fail("$name: the older watch is first: ${v.rows}")
            }
        }
        if (wrongCode) wrongCodeInSheet(name, core, server!!)
        return core
    }

    /** The code field in the sheet (or, wrongly, on the page): what a screen reader calls it, with text to edit. */
    private fun codeBoxes() = compose.onAllNodes(
        hasContentDescription(WatchLinkRules.CODE_SPOKEN) and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText),
        useUnmergedTree = true,
    ).fetchSemanticsNodes()

    private fun turns(n: Int = 5, sleepMs: Long = 0) = repeat(n) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        if (sleepMs > 0) Thread.sleep(sleepMs)
        compose.mainClock.advanceTimeBy(100)
    }

    private fun sheetOpen() = compose.onAllNodes(hasTestTag(GOAL_ADD_SHEET_TAG), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Opens the code's sheet from "+ Link a watch" and types [code] into it. */
    private fun openSheetAndType(name: String, code: String) {
        compose.onNodeWithTag(WATCH_LINK_ROW_TAG, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        turns(10)
        if (!sheetOpen()) fail("$name: + Link a watch opened no sheet")
        if (linkLit()) fail("$name: Link is lit with nothing typed")
        val box = compose.onAllNodes(
            hasContentDescription(WatchLinkRules.CODE_SPOKEN) and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText),
            useUnmergedTree = true,
        )
        if (box.fetchSemanticsNodes().isEmpty()) fail("$name: the sheet has no code field")
        box[0].performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(code)) }
        // The typed code reaches the recomposer (and lights Link) through a main-looper post: run it by turns.
        turns(5)
        if (!linkLit()) fail("$name: Link stayed dark with a code typed")
    }

    private fun linkNode() = compose.onAllNodes(hasContentDescription("${WatchLinkRules.LINK}, ${WatchLinkRules.linkSheet.title}"), useUnmergedTree = true)

    private fun linkLit() = linkNode().fetchSemanticsNodes().firstOrNull()?.config?.contains(SemanticsProperties.Disabled) == false

    /**
     * A wrong code typed in the sheet, as he would: the sheet stays open, saying why under the code it's about (A11),
     * in the critical colour (A22); the shot is the sheet.
     */
    private fun wrongCodeInSheet(name: String, core: MekaCore, server: Server) {
        // Wait for the screen's own read (the second) to finish, so it can't clear the problem afterwards.
        var waited = 0
        while (waited++ < 40 && server.listed < 2) turns(1, sleepMs = 50)
        if (server.listed < 2) fail("$name: Watch didn't read the watches on open")
        Thread.sleep(100)
        server.refuse = DeviceLinkCodec.ERR_CODE
        openSheetAndType(name, "1234 5678")
        linkNode()[0].performSemanticsAction(SemanticsActions.OnClick)
        repeat(40) {
            if (core.watchLinkView.value?.problem != null) return@repeat
            turns(1, sleepMs = 50)
        }
        turns((SETTLE_MS / 100).toInt())
        compose.waitForIdle()
        val v = core.watchLinkView.value ?: fail("$name: no view after Link")
        if (v.problem != WatchLinkRules.WRONG_CODE) fail("$name: a wrong code says \"${v.problem}\"")
        if (!sheetOpen()) fail("$name: a wrong code closed the sheet")
        compose.onNodeWithTag(GOAL_ADD_SHEET_TAG, useUnmergedTree = true).captureRoboImage(ScreenShots.path("watch", name))
        val said = LoneWordGlue.kept(WatchLinkRules.WRONG_CODE)
        if (compose.onAllNodes(hasText(said), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            fail("$name: the sheet doesn't say \"$said\"")
        }
        if (v.rows.size != 2) fail("$name: a wrong code changed the watches (${v.rows.size})")
    }

    /** Works by hand: + Link a watch, type the code the watch shows, tap Link, and the sheet goes and the watch's row arrives. */
    private fun linkByHand(core: MekaCore) {
        openSheetAndType("watch-by-hand", "1234 5678")
        linkNode()[0].performSemanticsAction(SemanticsActions.OnClick)
        // The link runs on the screen's scope and then the core's own thread: give it real time, then let the screen catch up.
        repeat(40) {
            if (core.watchLinkView.value?.rows?.isNotEmpty() == true) return@repeat
            turns(1, sleepMs = 50)
        }
        turns((SETTLE_MS / 100).toInt())
        val v = core.watchLinkView.value ?: fail("Watch: no view after Link")
        if (v.rows.size != 1) fail("Watch: Link didn't link the watch (${v.rows.size} rows, problem \"${v.problem}\")")
        if (sheetOpen()) fail("Watch: the code's sheet stayed open once the watch was linked")
        ScreenShots.assertShows(compose, "watch-by-hand", "Linked today")
        ScreenShots.assertShows(compose, "watch-by-hand", LINKED_BY_HAND)
        ScreenShots.assertShows(compose, "watch-by-hand", LoneWordGlue.kept(v.summary))
        ScreenShots.assertShows(compose, "watch-by-hand", WatchLinkRules.LINK_ANOTHER_ROW)
        // Week 5 A11: the by-hand shot shows the watch the hand linked, not the empty state it started from.
        ScreenShots.reshootAfterHand(compose, "watch", "cover-dark-by-hand")
        if (codeBoxes().isNotEmpty()) fail("Watch: a code box is still open after linking")
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun watchCoverNotConnected() { watch("cover-dark-not-connected", Fill.EMPTY, connected = false) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun watchCoverEmpty() { watch("cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun watchCoverOne() { watch("cover-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun watchCoverMany() { watch("cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun watchCoverManyLight() { watch("cover-light-many", Fill.MANY, theme = ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun watchInnerEmpty() { watch("inner-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun watchInnerMany() { watch("inner-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun watchCoverWrongCode() { watch("cover-dark-wrong-code", Fill.MANY, wrongCode = true) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun watchCoverWrongCodeLight() { watch("cover-light-wrong-code", Fill.MANY, theme = ThemeChoice.LIGHT, wrongCode = true) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun watchByHand() { linkByHand(watch("cover-dark-by-hand", Fill.EMPTY)) }

    // ---- Health ----

    private fun health(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK) {
        val server = if (fill == Fill.EMPTY) null else Server()
        server?.accounts = when (fill) {
            Fill.EMPTY -> emptyList()
            Fill.ONE -> listOf(ConnectedAccount("google", "jordan@example.com", "ok", now - 5 * minute))
            Fill.MANY -> listOf(
                ConnectedAccount("google", "jordan@example.com", "ok", now - 5 * minute),
                ConnectedAccount("microsoft", "jordan.work@example.com", "needs_reconnect", now - 2 * day),
                ConnectedAccount("news", "", "ok", now - 30 * minute),
            )
        }
        val core = setUp(theme, server)
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        runBlocking {
            if (server != null) core.syncNow()
            // Walk-through 2026-11-08 A8: Setup's full shot has the call assistant on, so Health's full one does too
            // (both read the same Work mode switch); they said Off here beside On there.
            if (fill == Fill.MANY) core.setCallAssistant(ShotLife.CALL_ASSISTANT_ON_WHEN_FULL)
            // Walk-through 2026-11-22 A8: Setup's full shot has Navilife screening calls, so Health's full phone holds
            // Android's call screening role too (it said "isn't screening calls" beside Setup's "screens calls").
            if (fill == Fill.MANY && ShotLife.CALL_SCREENING_WHEN_FULL) {
                val roles = org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowRoleManager>(
                    context.getSystemService(android.app.role.RoleManager::class.java),
                )
                roles.addAvailableRole(android.app.role.RoleManager.ROLE_CALL_SCREENING)
                roles.addHeldRole(android.app.role.RoleManager.ROLE_CALL_SCREENING)
            }
            core.refreshHealth(HealthDevice.facts(context))
        }
        show { HealthPane(core, onClose = {}, openPane = {}) }
        settle("health", name)
        val v = core.healthView.value ?: fail("$name: Health never checked")
        ScreenShots.assertShows(compose, name, "Health")
        // The core's clock and zone, like every other time on screen: 17:15 in London, whatever the test machine's zone.
        if (v.checkedLine != "Checked 17:15") fail("$name: the check reads \"${v.checkedLine}\" at 17:15 in London")
        // CI run 1561: the screen can still be on "Checking…" (walk-through A14 keeps the two apart) when the core has
        // checked; wait for it by turns rather than fail on a frame that hasn't caught up. CI run 42685826065: the
        // pane's own on-resume check (the updater, then the health refresh) outlasted 80 turns on a slow runner, so
        // this one waits longer; a screen that never settles still fails.
        ScreenShots.awaitShows(compose, name, v.checkedLine, turns = 320)
        // CI run 43719093575 (twice): while the pane's on-resume check runs, the top line is the last check's count
        // ("4 things needed a look at the last check", A32 slice 4), so the summary is waited for once the check is done.
        ScreenShots.awaitShows(compose, name, LoneWordGlue.kept(v.summary), turns = 320)
        ScreenShots.assertEachOneLine(compose, name, v.rows.mapNotNull { it.fixLabel })
        ScreenShots.assertNoLoneWords(compose, name, listOf(v.summary) + v.rows.map { it.line })
        ScreenShots.assertNoDotStartingALine(compose, name, listOf(v.summary) + v.rows.map { it.line })
        // Walk-through A28 (2026-11-15): "Navilife can't see notifications ·" ended a line on the dot.
        ScreenShots.assertNoDotEndingALine(compose, name, listOf(v.summary) + v.rows.map { it.line })
        ScreenShots.assertReadable(compose, name, v.rows.map { it.title }, mustShow = false)
        // Walk-through A32: a row's Reconnect / Fix pill and every tappable line are whole 48 dp tall targets.
        ScreenShots.assertWholeTargets(compose, name)
        // The walk-through's A7: a row he switched off never wears the working green.
        v.rows.filter { it.line.startsWith("Off ·") && it.state == os.meka.core.domain.HealthState.OK }
            .forEach { fail("$name: ${it.title} is off but drawn as working") }
        // Walk-through A32: Obi's voice row says the same Face setting as Obi's voice's own shots (ShotLife.FACE_ON).
        v.rows.firstOrNull { it.key == "voice" }?.detail?.let { d ->
            if (d.contains("Face · off") == ShotLife.FACE_ON) fail("$name: Obi's voice row says \"$d\" while the Voice shots have Face ${if (ShotLife.FACE_ON) "on" else "off"}")
        }
        val server0 = v.rows.first { it.key == "server" }
        when (fill) {
            Fill.EMPTY -> if (server0.line != "This device isn't connected yet") fail("$name: not connected, the server row says \"${server0.line}\"")
            Fill.ONE -> if (!server0.line.startsWith("Connected")) fail("$name: connected, the server row says \"${server0.line}\"")
            Fill.MANY -> {
                val calls = v.rows.firstOrNull { it.key == "calls" } ?: fail("$name: no Call assistant row")
                if (calls.line.startsWith("Off")) fail("$name: Call assistant says \"${calls.line}\" while Setup's full shot has it on")
                if (calls.line.contains("isn't screening")) fail("$name: Call assistant says \"${calls.line}\" while Setup's full shot screens calls")
                val expired = v.rows.firstOrNull { it.key.startsWith("calendar:microsoft") } ?: fail("$name: no row for the work calendar")
                if (expired.fixLabel != "Reconnect") fail("$name: an expired sign-in offers \"${expired.fixLabel}\"")
                if (v.attention < 1) fail("$name: an expired sign-in doesn't need a look (${v.summary})")
            }
        }
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun healthCoverEmpty() = health("cover-dark-not-connected", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun healthCoverOne() = health("cover-dark-working", Fill.ONE)
    @Test @Config(qualifiers = ScreenShots.COVER) fun healthCoverMany() = health("cover-dark-many", Fill.MANY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun healthCoverManyLight() = health("cover-light-many", Fill.MANY, ThemeChoice.LIGHT)
    @Test @Config(qualifiers = ScreenShots.INNER) fun healthInnerEmpty() = health("inner-dark-not-connected", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.INNER) fun healthInnerMany() = health("inner-dark-many", Fill.MANY)

    /** The line under the summary reads the core's time, checking or not. */
    @Test fun checkedLineReadsTheCoresZone() {
        val view = os.meka.core.domain.HealthView(emptyList(), "", 0, false, null, now, "Checked 17:15")
        if (HealthRules.checkedShown(view, checking = true) != "Checking now · last checked 17:15") fail(HealthRules.checkedShown(view, true))
        if (HealthRules.checkedShown(view, checking = false) != "Checked 17:15") fail(HealthRules.checkedShown(view, false))
    }

    /** Walk-through A32: while a check runs, the last check's count reads as the last check's. */
    @Test fun summaryWhileCheckingIsTheLastChecks() {
        val view = os.meka.core.domain.HealthView(emptyList(), "5 things need a look", 5, false, null, now, "Checked 17:15")
        if (HealthRules.summaryShown(view, checking = true) != "5 things needed a look at the last check") fail(HealthRules.summaryShown(view, true))
        if (HealthRules.summaryShown(view, checking = false) != "5 things need a look") fail(HealthRules.summaryShown(view, false))
    }
}
