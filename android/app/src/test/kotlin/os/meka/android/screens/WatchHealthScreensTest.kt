package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
import os.meka.android.ask.WatchPane
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

    private companion object { const val SETTLE_MS = 3_000L }

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
            watches.add(0, DeviceLinkCodec.Watch(id, WatchLinkRules.DEFAULT_NAME, now))
            return DeviceLinkCodec.Linked(id, WatchLinkRules.DEFAULT_NAME)
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

    // ---- Watch ----

    private fun watch(name: String, fill: Fill, connected: Boolean = true, theme: ThemeChoice = ThemeChoice.DARK): MekaCore {
        val server = if (connected) Server() else null
        if (fill != Fill.EMPTY) server!!.watches += DeviceLinkCodec.Watch("watch00000000000000b1", WatchLinkRules.DEFAULT_NAME, now - 2 * 60 * minute)
        if (fill == Fill.MANY) server!!.watches += DeviceLinkCodec.Watch("watch00000000000000b2", "Galaxy Watch8 Classic", now - 9 * day)
        val core = setUp(theme, server)
        runBlocking { core.refreshWatches() }
        show { WatchPane(core, onClose = {}) }
        if (fill == Fill.MANY) {
            // A wrong code typed once the screen is open (opening it reads the watches afresh and clears a problem).
            compose.mainClock.advanceTimeBy(SETTLE_MS)
            compose.waitForIdle()
            // Wait for the screen's own read (the second) to finish, so it can't clear the problem afterwards.
            var turns = 0
            while (turns++ < 40 && server!!.listed < 2) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(50)
                compose.mainClock.advanceTimeBy(100)
            }
            if (server!!.listed < 2) fail("$name: Watch didn't read the watches on open")
            Thread.sleep(100)
            server.refuse = DeviceLinkCodec.ERR_CODE
            runBlocking { check(!core.linkWatch("1234 5678")) }
        }
        settle("watch", name)
        val v = core.watchLinkView.value ?: fail("$name: Watch never read the server")
        ScreenShots.assertShows(compose, name, "Watch")
        ScreenShots.assertShows(compose, name, LoneWordGlue.kept(v.summary))
        ScreenShots.assertEachOneLine(compose, name, listOf("Code from the watch") + v.rows.map { it.title })
        val lines = listOf(v.summary, v.how) + v.rows.map { it.line } + listOfNotNull(v.problem)
        ScreenShots.assertNoLoneWords(compose, name, lines)
        ScreenShots.assertNoDotStartingALine(compose, name, lines)
        ScreenShots.assertReadable(compose, name, v.rows.map { it.title }, mustShow = false)
        when {
            !connected -> if (v.problem != WatchLinkRules.NOT_CONNECTED) fail("$name: not connected, but the problem says \"${v.problem}\"")
            fill == Fill.EMPTY -> if (v.rows.isNotEmpty() || v.problem != null) fail("$name: $v with nothing linked")
            fill == Fill.ONE -> {
                if (v.rows.size != 1) fail("$name: ${v.rows.size} rows for one watch")
                ScreenShots.assertShows(compose, name, "Linked today")
            }
            fill == Fill.MANY -> {
                if (v.rows.size != 2) fail("$name: ${v.rows.size} rows for two watches")
                if (v.problem != WatchLinkRules.WRONG_CODE) fail("$name: a wrong code says \"${v.problem}\"")
                // The newest first.
                if (v.rows.first().title != WatchLinkRules.DEFAULT_NAME) fail("$name: the older watch is first: ${v.rows}")
            }
        }
        return core
    }

    /** Works by hand: type the code the watch shows, tap Link, and the watch's row arrives. */
    private fun linkByHand(core: MekaCore) {
        compose.onAllNodes(hasContentDescription("Code from the watch") and hasSetTextAction())[0].performTextInput("1234 5678")
        // The typed code reaches the recomposer (and enables Link) through a main-looper post: run it by turns.
        repeat(5) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
        compose.onAllNodes(hasText("Link") and hasClickAction())[0].performClick()
        // The link runs on the screen's scope and then the core's own thread: give it real time, then let the screen catch up.
        repeat(40) {
            if (core.watchLinkView.value?.rows?.isNotEmpty() == true) return@repeat
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        repeat((SETTLE_MS / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
        val v = core.watchLinkView.value ?: fail("Watch: no view after Link")
        if (v.rows.size != 1) fail("Watch: Link didn't link the watch (${v.rows.size} rows, problem \"${v.problem}\")")
        ScreenShots.assertShows(compose, "watch-by-hand", "Linked today")
        ScreenShots.assertShows(compose, "watch-by-hand", LoneWordGlue.kept(v.summary))
        // The field clears for the next one.
        if (compose.onAllNodes(hasText("1234 5678"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
            fail("Watch: the code is still in the field after linking")
        }
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun watchCoverNotConnected() { watch("cover-dark-not-connected", Fill.EMPTY, connected = false) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun watchCoverEmpty() { watch("cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun watchCoverOne() { watch("cover-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun watchCoverMany() { watch("cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun watchCoverManyLight() { watch("cover-light-many", Fill.MANY, theme = ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun watchInnerEmpty() { watch("inner-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun watchInnerMany() { watch("inner-dark-many", Fill.MANY) }
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
            core.refreshHealth(HealthDevice.facts(context))
        }
        show { HealthPane(core, onClose = {}, openPane = {}) }
        settle("health", name)
        val v = core.healthView.value ?: fail("$name: Health never checked")
        ScreenShots.assertShows(compose, name, "Health")
        ScreenShots.assertShows(compose, name, LoneWordGlue.kept(v.summary))
        // The core's clock and zone, like every other time on screen: 17:15 in London, whatever the test machine's zone.
        if (v.checkedLine != "Checked 17:15") fail("$name: the check reads \"${v.checkedLine}\" at 17:15 in London")
        ScreenShots.assertShows(compose, name, v.checkedLine)
        ScreenShots.assertEachOneLine(compose, name, v.rows.mapNotNull { it.fixLabel })
        ScreenShots.assertNoLoneWords(compose, name, listOf(v.summary) + v.rows.map { it.line })
        ScreenShots.assertNoDotStartingALine(compose, name, listOf(v.summary) + v.rows.map { it.line })
        ScreenShots.assertReadable(compose, name, v.rows.map { it.title }, mustShow = false)
        // The walk-through's A7: a row he switched off never wears the working green.
        v.rows.filter { it.line.startsWith("Off ·") && it.state == os.meka.core.domain.HealthState.OK }
            .forEach { fail("$name: ${it.title} is off but drawn as working") }
        val server0 = v.rows.first { it.key == "server" }
        when (fill) {
            Fill.EMPTY -> if (server0.line != "This device isn't connected yet") fail("$name: not connected, the server row says \"${server0.line}\"")
            Fill.ONE -> if (!server0.line.startsWith("Connected")) fail("$name: connected, the server row says \"${server0.line}\"")
            Fill.MANY -> {
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
        if (HealthRules.checkedShown(view, checking = true) != "Checking… · Checked 17:15") fail(HealthRules.checkedShown(view, true))
        if (HealthRules.checkedShown(view, checking = false) != "Checked 17:15") fail(HealthRules.checkedShown(view, false))
    }
}
