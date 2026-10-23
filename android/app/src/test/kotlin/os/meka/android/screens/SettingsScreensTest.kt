package os.meka.android.screens

import androidx.compose.foundation.background
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
import os.meka.android.ask.AboutYouPane
import os.meka.android.ask.SettingsPane
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.android.shell.MoreItem
import os.meka.android.shell.ShellNav
import os.meka.core.domain.AboutField
import os.meka.core.domain.AboutYouRules
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
    @get:Rule
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
