package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
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
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.android.mail.MailPane
import os.meka.core.domain.LoneWordGlue
import os.meka.core.domain.MailAccountFacts
import os.meka.core.domain.MailAccountRules
import os.meka.core.domain.MailAccountsView
import os.meka.core.domain.MailAutopilotPanelRules
import os.meka.core.facade.AccountsApi
import os.meka.core.facade.ConnectStart
import os.meka.core.facade.ConnectedAccount
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import kotlin.random.Random
import kotlin.test.fail

/**
 * The quality gate, slice 31 (build plan: "the remaining screens' shots", Mail first): Ask → Settings → Mail when the
 * server can't be reached, with nothing connected, one Gmail sorted and tidied, and three accounts (one sorted, one whose
 * sign-in ended, one whose last check failed), each on the Fold's cover and inner screens, Dark (and the full one in
 * Light). Saved under `mail/` and read for nonsense, cut lines, broken words, split dates, times and numbers, a word left
 * alone and a line starting with a dot. Mail by hand: tap Autopilot's Turn off and Autopilot is off, its line saying so.
 * Talks to a stand-in server held in the test. Invented data only (rule 13): example.com addresses.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class MailScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London
    private val minute = 60_000L
    private val day = 24 * 60 * minute

    private companion object { const val SETTLE_MS = 3_000L }

    private enum class Fill { OFFLINE, EMPTY, ONE, MANY }

    /** Navilife's server, as far as Mail asks it: sync and the mail accounts (never a token). */
    private inner class Server(val mail: List<MailAccountFacts>) : SyncTransport, AccountsApi {
        val service = SyncService(InMemoryServerOpStore())
        override suspend fun push(request: PushRequest) = service.push(request)
        override suspend fun pull(request: PullRequest) = service.pull(request)
        override suspend fun startConnect(provider: String, editing: Boolean): ConnectStart = ConnectStart.NotSetUp
        override suspend fun accounts(): List<ConnectedAccount> = emptyList()
        override suspend fun stopEditing(provider: String, email: String): List<ConnectedAccount> = emptyList()
        override suspend fun mailAccounts(): List<MailAccountFacts> = mail
    }

    private val personal = MailAccountFacts(
        "gmail", "jordan@example.com", "ok", now - 4 * minute, "PERSONAL",
        sorted = mapOf("NEEDS_YOU" to 3, "FYI" to 8, "NEWSLETTER" to 30, "RECEIPT" to 4, "PROMO" to 12),
        wouldTidy = mapOf("NEWSLETTER" to 30, "RECEIPT" to 4, "ARCHIVE" to 12),
        inboxTotal = 1_240, inboxUnread = 86,
    )
    private val business = MailAccountFacts("gmail", "jordan.studio@example.com", "needs_reconnect", now - 2 * day, "BUSINESS")
    private val outlook = MailAccountFacts("outlook", "jordan.work@example.com", "error", now - 3 * 60 * minute, "OUTLOOK", why = "busy")

    private fun accounts(fill: Fill): List<MailAccountFacts> = when (fill) {
        Fill.OFFLINE, Fill.EMPTY -> emptyList()
        Fill.ONE -> listOf(personal)
        Fill.MANY -> listOf(personal, business, outlook)
    }

    private fun setUp(theme: ThemeChoice, fill: Fill): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val server = if (fill == Fill.OFFLINE) null else Server(accounts(fill))
        val core = MekaCore(
            householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = server,
            secureRandom = Random(31), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
        )
        context.useCoreForScreens(core)
        runBlocking { core.tick() }
        return core
    }

    private fun show(core: MekaCore) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) { MailPane(core, onClose = {}) }
            }
        }
    }

    /** Runs the main looper and the frames by turns (the pane reads the accounts on a lifecycle post). */
    private fun turns(ms: Long = SETTLE_MS) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    /** Every descriptive line Mail draws through [LoneWordGlue.kept]. */
    private fun lines(v: MailAccountsView): List<String> =
        listOf(MailAccountRules.HEADER) + listOfNotNull(v.emptyLine, v.toConnect, v.repliesLine) +
            v.rows.flatMap { listOfNotNull(it.line, it.sortedLine, it.tidyLine) } +
            listOfNotNull(v.autopilot?.line, v.autopilot?.markReadLine, v.autopilot?.readerReadLine)

    private fun mail(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK): MekaCore {
        val core = setUp(theme, fill)
        show(core)
        turns()
        compose.waitForIdle()
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("mail", name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertDatesWhole(compose, name)
        ScreenShots.assertClocksWhole(compose, name)
        ScreenShots.assertNumbersWhole(compose, name)
        ScreenShots.assertShows(compose, name, MailAccountRules.TITLE)
        if (fill == Fill.OFFLINE) {
            ScreenShots.assertShows(compose, name, MailAccountRules.OFFLINE)
            return core
        }
        val v = runBlocking { core.mailAccounts() } ?: fail("$name: Mail never read the accounts")
        val lines = lines(v)
        ScreenShots.assertNoLoneWords(compose, name, lines)
        ScreenShots.assertNoDotStartingALine(compose, name, lines)
        // Titles ("Personal · Gmail"), the buttons and the label chips each on one line.
        ScreenShots.assertEachOneLine(
            compose, name,
            v.rows.map { it.title } + v.connects.map { it.label } + listOf("Reconnect", MailAccountRules.DISCONNECT) +
                listOfNotNull(v.autopilot?.action),
        )
        ScreenShots.assertReadable(compose, name, v.rows.map { it.title }, mustShow = false)
        when (fill) {
            Fill.EMPTY -> {
                if (v.rows.isNotEmpty() || v.autopilot != null) fail("$name: $v with nothing connected")
                ScreenShots.assertShows(compose, name, LoneWordGlue.kept(MailAccountRules.EMPTY))
                v.connects.forEach { ScreenShots.assertShows(compose, name, it.label) }
            }
            Fill.ONE -> {
                val row = v.rows.singleOrNull() ?: fail("$name: ${v.rows.size} rows for one account")
                if (row.sortedLine == null) fail("$name: a sorted inbox says nothing about it")
                ScreenShots.assertShows(compose, name, LoneWordGlue.kept(row.line))
                ScreenShots.assertShows(compose, name, "Still to connect")
            }
            Fill.MANY -> {
                if (v.rows.size != 3) fail("$name: ${v.rows.size} rows for three accounts")
                if (v.toConnect != null) fail("$name: all three connected, yet \"${v.toConnect}\"")
                val ended = v.rows.first { it.email == business.email }
                if (!ended.needsReconnect) fail("$name: an ended sign-in doesn't offer Reconnect")
                ScreenShots.assertShows(compose, name, "Reconnect")
                // A failed check says why, not the catch-all.
                val failed = v.rows.first { it.email == outlook.email }
                if (failed.line.startsWith("Couldn't reach it last time")) fail("$name: a busy check reads \"${failed.line}\"")
                // Personal first, then Business, then Outlook.
                if (v.rows.map { it.email } != listOf(personal.email, business.email, outlook.email)) fail("$name: order ${v.rows.map { it.title }}")
            }
            Fill.OFFLINE -> Unit
        }
        return core
    }

    /** Works by hand: Autopilot's Turn off turns it off, and its line and button say so. */
    private fun autopilotByHand(core: MekaCore) {
        val before = runBlocking { core.mailAccounts() }?.autopilot ?: fail("Mail: no Autopilot with an account connected")
        if (!before.on) fail("Mail: Autopilot starts off (${before.line})")
        val buttons = compose.onAllNodes(hasText(MailAutopilotPanelRules.TURN_OFF) and hasClickAction())
        if (buttons.fetchSemanticsNodes().isEmpty()) fail("Mail: no Turn off")
        // Autopilot's card is the first switch on the screen; its button's own click action (no scroll, no touch).
        buttons[0].performSemanticsAction(SemanticsActions.OnClick)
        repeat(40) {
            if (runBlocking { core.mailAccounts() }?.autopilot?.on == false) return@repeat
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        turns()
        val after = runBlocking { core.mailAccounts() }?.autopilot ?: fail("Mail: Autopilot went away after Turn off")
        if (after.on) fail("Mail: Turn off didn't turn Autopilot off")
        ScreenShots.assertShows(compose, "mail-by-hand", LoneWordGlue.kept(MailAutopilotPanelRules.OFF_LINE))
        ScreenShots.assertShows(compose, "mail-by-hand", LoneWordGlue.kept(MailAutopilotPanelRules.switchedLine(false)))
        if (compose.onAllNodes(hasText(MailAutopilotPanelRules.TURN_ON) and hasClickAction()).fetchSemanticsNodes().isEmpty()) {
            fail("Mail: no Turn on after Turn off")
        }
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun coverOffline() { mail("cover-dark-offline", Fill.OFFLINE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverEmpty() { mail("cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverOne() { mail("cover-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverMany() { mail("cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverManyLight() { mail("cover-light-many", Fill.MANY, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerEmpty() { mail("inner-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerMany() { mail("inner-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun autopilotByHand() { autopilotByHand(mail("cover-dark-by-hand", Fill.ONE)) }
}
