package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
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
import os.meka.android.today.CalendarsPane
import os.meka.android.today.WEATHER_HINT
import os.meka.core.domain.CalendarAccessAction
import os.meka.core.domain.CalendarAccessRules
import os.meka.core.domain.CalendarRules
import os.meka.core.domain.DateGlue
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.EventFields
import os.meka.core.domain.LoneWordGlue
import os.meka.core.domain.MekaSchema
import os.meka.core.facade.AccountsApi
import os.meka.core.facade.ConnectStart
import os.meka.core.facade.ConnectedAccount
import os.meka.core.facade.MekaCore
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.Replica
import os.meka.core.sync.ReplicaStore
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.sync.fv
import kotlin.random.Random
import kotlin.test.fail

/**
 * The quality gate, slice 33 (build plan: "the remaining screens' shots", Calendars next): Ask → Settings → Calendars
 * with nothing connected, one Google calendar (read-only, two calendars on Today) and three accounts (Google with editing
 * allowed, an Outlook whose sign-in ended, the bank holidays feed synced yesterday), each on the Fold's cover and inner
 * screens, Dark (and the full one in Light). Saved under `calendars/` and read for nonsense, cut lines, broken words,
 * split dates, times and numbers, a word left alone and a line starting with a dot; on the cover screen an account's
 * Reconnect / Allow editing sit under its words. By hand: a calendar's On turns it off Today, Rename opens its field.
 * Talks to a stand-in server held in the test; events are written as the server's calendar mirror writes them.
 * Invented data only (rule 13): example.com addresses.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class CalendarsScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val london = TimeZone.of("Europe/London")
    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London
    private val minute = 60_000L
    private val day = 24 * 60 * minute

    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime(2026, 10, d, h, m).toInstant(london).toEpochMilliseconds()

    private companion object { const val SETTLE_MS = 3_000L }

    private enum class Fill { EMPTY, ONE, MANY }

    private val personalEmail = "alex@example.com"
    private val workEmail = "alex.work@example.com"

    /** Navilife's server, as far as Calendars asks it: sync and the connected accounts (never a token). */
    private inner class Server(var list: List<ConnectedAccount>) : SyncTransport, AccountsApi {
        val service = SyncService(InMemoryServerOpStore())
        override suspend fun push(request: PushRequest) = service.push(request)
        override suspend fun pull(request: PullRequest) = service.pull(request)
        override suspend fun startConnect(provider: String, editing: Boolean): ConnectStart = ConnectStart.NotSetUp
        override suspend fun accounts(): List<ConnectedAccount> = list
        override suspend fun stopEditing(provider: String, email: String): List<ConnectedAccount> {
            list = list.map { if (it.provider == provider && it.email == email) it.copy(canEdit = false) else it }
            return list
        }
    }

    private fun accounts(fill: Fill): List<ConnectedAccount> = when (fill) {
        Fill.EMPTY -> emptyList()
        Fill.ONE -> listOf(ConnectedAccount("google", personalEmail, "ok", now - 4 * minute))
        Fill.MANY -> listOf(
            ConnectedAccount("google", personalEmail, "ok", now - 4 * minute, canEdit = true),
            ConnectedAccount("microsoft", workEmail, "needs_reconnect", now - 2 * day),
            ConnectedAccount("bank_holidays", "GOV.UK · England and Wales", "ok", now - day),
        )
    }

    /** Writes the events into [store] as the server's calendar mirror does, before the core opens it. */
    private fun seed(store: ReplicaStore, fill: Fill) {
        if (fill == Fill.EMPTY) return
        var n = 0
        val mirror = Replica("hh", "server", HlcClock("server", { now }), store, MekaSchema) { "seed${n++}" }
        fun event(id: String, title: String, start: Long, end: Long, calendar: String, provider: String = "google", account: String = personalEmail) =
            mirror.commitLocal(EntityTypes.EVENT, id, buildMap {
                put(EventFields.TITLE, title.fv()); put(EventFields.START_AT, start.fv()); put(EventFields.END_AT, end.fv())
                put(EventFields.PROVIDER, provider.fv()); put(EventFields.ACCOUNT, account.fv())
                put(EventFields.CALENDAR, calendar.fv())
            })
        event("ev1", "Swimming lesson", at(22, 18, 30), at(22, 19, 15), "Kids")
        event("ev2", "Dentist", at(23, 9, 30), at(23, 10, 0), "Personal")
        if (fill != Fill.MANY) return
        event("ev3", "Quarterly planning", at(26, 10, 0), at(26, 11, 30), "Calendar", provider = "microsoft", account = workEmail)
        event("ev4", "Football training", at(24, 10, 0), at(24, 11, 0), "Kids")
    }

    private fun setUp(theme: ThemeChoice, fill: Fill): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val store = InMemoryReplicaStore()
        seed(store, fill)
        val core = MekaCore(
            householdId = "hh", deviceId = "android", store = store, transport = Server(accounts(fill)),
            secureRandom = Random(33), timeZone = { london }, nowMs = { now },
        )
        context.useCoreForScreens(core)
        runBlocking { core.tick() }
        return core
    }

    /** Runs the main looper and the frames by turns (the pane reads the accounts once resumed). */
    private fun turns(ms: Long = SETTLE_MS) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    private fun calendars(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK): MekaCore {
        val core = setUp(theme, fill)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) { CalendarsPane(core, onClose = {}) }
            }
        }
        turns()
        val list = runBlocking { core.connectedAccounts() }
        turns()
        compose.waitForIdle()
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("calendars", name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertDatesWhole(compose, name)
        ScreenShots.assertClocksWhole(compose, name)
        ScreenShots.assertNumbersWhole(compose, name)
        ScreenShots.assertShows(compose, name, "Calendars")
        val lines = listOf(CalendarAccessRules.header(list.any { it.canEdit }), CalendarRules.ON_TODAY_HINT, WEATHER_HINT) +
            list.flatMap { listOfNotNull(it.shownStatus, it.eventsLine, it.editingShown) }
        ScreenShots.assertNoLoneWords(compose, name, lines)
        ScreenShots.assertNoDotStartingALine(compose, name, lines)
        ScreenShots.assertEachOneLine(compose, name, list.map { it.title } + CalendarAccessAction.entries.map { it.label } + "Reconnect")
        ScreenShots.assertReadable(compose, name, list.map { it.title }, mustShow = false)
        providerNeverAlone(name, core)
        // Every time on screen is the core's (17:15 in London), never the test machine's zone.
        list.filter { it.lastSyncAtMs != null && !it.needsReconnect }.forEach { a ->
            val synced = a.syncedAt ?: fail("$name: ${a.title} has no synced time")
            if (a.lastSyncAtMs == now - 4 * minute && synced != "17:11") fail("$name: ${a.title} synced at \"$synced\", not 17:11 London")
            if (a.lastSyncAtMs == now - day && synced == "17:15") fail("$name: yesterday's sync reads as today's \"$synced\"")
        }
        if (name.startsWith("cover")) actionsUnderWords(name, list)
        // Quality gate slice 34: the line never says Reconnect over its own Reconnect button.
        list.filter { it.needsReconnect }.forEach { a ->
            if ("Reconnect" in a.shownStatus) fail("$name: ${a.title}'s line says Reconnect above its button: \"${a.shownStatus}\"")
        }
        // Quality gate slice 36: a card titled by its provider ("Outlook") doesn't say it again on its line, and an
        // expired account's line is never drawn ending on a "·" with "access expired" under it.
        list.forEach { a ->
            if (a.shownStatus.replace(' ', ' ').startsWith("${a.title} · ")) fail("$name: ${a.title} said twice: \"${a.shownStatus}\"")
        }
        ScreenShots.assertNoDotEndingALine(compose, name, list.filter { it.needsReconnect }.map { it.shownStatus })
        when (fill) {
            Fill.EMPTY -> {
                if (list.isNotEmpty()) fail("$name: ${list.size} accounts with nothing connected")
                ScreenShots.assertShows(compose, name, "No calendars connected yet.")
                ScreenShots.assertShows(compose, name, "Connect Google Calendar")
            }
            Fill.ONE -> {
                val a = list.singleOrNull() ?: fail("$name: ${list.size} accounts for one")
                ScreenShots.assertShows(compose, name, a.shownStatus)
                ScreenShots.assertShows(compose, name, CalendarAccessAction.ALLOW_EDITING.label)
                if (core.calendarsOnToday.value.count { it.canRename } < 2) fail("$name: ${core.calendarsOnToday.value.map { it.label }} on Today for Kids and Personal")
                ScreenShots.assertShows(compose, name, "ON TODAY")
            }
            Fill.MANY -> {
                if (list.size != 3) fail("$name: ${list.size} accounts for three")
                ScreenShots.assertShows(compose, name, "Reconnect")
                ScreenShots.assertShows(compose, name, CalendarAccessAction.STOP_EDITING.label)
                // Quality gate slice 34: the feed is titled by what it brings, its source under it.
                ScreenShots.assertShows(compose, name, "Bank holidays")
                if (list.none { it.title == "Bank holidays" }) fail("$name: the bank holidays feed is titled ${list.map { it.title }}")
            }
        }
        return core
    }

    /** Quality gate slice 33: on the cover screen an account's actions sit under its words, never squeezing them. */
    private fun actionsUnderWords(name: String, list: List<ConnectedAccount>) {
        list.forEach { a ->
            val label = when {
                a.needsReconnect -> "Reconnect"
                else -> a.editingAction?.label ?: return@forEach
            }
            val status = compose.onAllNodes(hasText(a.shownStatus), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
                ?: return@forEach // below the fold
            val button = compose.onAllNodes(hasText(label), useUnmergedTree = true).fetchSemanticsNodes()
                .firstOrNull { it.boundsInRoot.top >= status.boundsInRoot.top - 1f } ?: fail("$name: no $label for ${a.title}")
            if (button.boundsInRoot.top < status.boundsInRoot.bottom - 1f) fail("$name: ${a.title}'s $label sits beside its words")
            // Quality gate slice 34: the card ends just under its button (its 48 dp touch height is its room), no tall
            // empty band under it.
            ScreenShots.assertHugs(compose, name, "account:${a.provider}:${a.email}", label, maxGapDp = 8f)
        }
    }

    /**
     * Quality gate slice 35: an ON TODAY row's line never leaves "Outlook ·" alone above its address; a line too narrow
     * for it whole drops the provider (core CalendarRules.detailShort). Each detail line is shown whole or short.
     */
    private fun providerNeverAlone(name: String, core: MekaCore) {
        val choices = core.calendarsOnToday.value.filter { it.detail != null }
        val wanted = choices.flatMap { listOfNotNull(it.detail, it.detailShort) }.map { DateGlue.plain(it) }.toSet()
        val bad = mutableListOf<String>()
        var seen = 0
        fun walk(n: SemanticsNode) {
            val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
            val action = n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
            if (text != null && action != null && DateGlue.plain(text) in wanted) {
                seen++
                val results = mutableListOf<TextLayoutResult>()
                action(results)
                results.firstOrNull()?.let { r ->
                    for (line in 0 until r.lineCount) {
                        val words = DateGlue.plain(text.substring(r.getLineStart(line), r.getLineEnd(line))).trim()
                        if (CalendarRules.PROVIDER_NAMES.any { words == "$it ·" || words == it && r.lineCount > 1 }) bad += "\"$words\" in \"${DateGlue.plain(text)}\""
                    }
                }
            }
            n.children.forEach { walk(it) }
        }
        walk(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        if (seen < choices.size) fail("$name: ${choices.size - seen} of ON TODAY's lines not drawn as their whole or short line")
        if (bad.isNotEmpty()) fail("$name leaves a provider alone on a line:\n" + bad.joinToString("\n") { "- $it" })
    }

    /** Works by hand: a calendar's On takes it off Today (and its pill says Off); Rename opens its name field. */
    private fun byHand(core: MekaCore) {
        val first = core.calendarsOnToday.value.firstOrNull { it.canRename } ?: fail("Calendars: nothing on Today (${core.calendarsOnToday.value})")
        if (!first.onToday) fail("Calendars: ${first.label} starts off Today")
        val row = compose.onAllNodes(hasContentDescription("${first.label} on Today") and hasClickAction())
        if (row.fetchSemanticsNodes().isEmpty()) fail("Calendars: no switch for ${first.label}")
        row[0].performSemanticsAction(SemanticsActions.OnClick)
        repeat(40) {
            if (core.calendarsOnToday.value.firstOrNull { it.key == first.key }?.onToday == false) return@repeat
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        turns()
        val after = core.calendarsOnToday.value.firstOrNull { it.key == first.key } ?: fail("Calendars: ${first.label} went away")
        if (after.onToday) fail("Calendars: tapping ${first.label} left it on Today")
        ScreenShots.assertShows(compose, "calendars-by-hand", "Off")
        // Rename unfolds the field under the row.
        val rename = compose.onAllNodes(hasContentDescription("Rename ${after.label}") and hasClickAction(), useUnmergedTree = true)
        if (rename.fetchSemanticsNodes().isEmpty()) fail("Calendars: no Rename for ${after.label}")
        rename[0].performSemanticsAction(SemanticsActions.OnClick)
        turns()
        ScreenShots.assertShows(compose, "calendars-by-hand", "Name for ${after.defaultLabel}")
        ScreenShots.assertShows(compose, "calendars-by-hand", "Cancel")
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun coverEmpty() { calendars("cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverOne() { calendars("cover-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverMany() { calendars("cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverManyLight() { calendars("cover-light-many", Fill.MANY, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerEmpty() { calendars("inner-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerMany() { calendars("inner-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun byHand() { byHand(calendars("inner-dark-by-hand", Fill.ONE)) }
}
