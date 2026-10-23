package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import os.meka.android.export.YourData
import os.meka.core.domain.DataExport
import os.meka.core.domain.DateGlue
import os.meka.core.domain.DocumentRules
import os.meka.core.domain.LoneWordGlue
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random
import kotlin.test.fail

/**
 * The quality gate, slice 42 (build plan: "the rest of Ask's places"): the Vault (documents, the export) and Your data,
 * on the Fold's cover and inner screens with nothing in, one document and a full set, Dark (and the full set in Light).
 * Saved under `vault/` and `your-data/` and read for nonsense, cut lines, words broken in two, dates split across lines,
 * the add field's hint on one line, the Vault's line never saying the same as the empty summary under it, the export's
 * line naming documents (they are in the file), and Export everything never a dead button with nothing to export.
 * Invented data only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class VaultScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(44), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private companion object { const val SETTLE_MS = 3_000L }

    private enum class Place(val area: String, val title: String) { VAULT("vault", "Vault"), YOUR_DATA("your-data", "Your data") }

    private enum class Fill { EMPTY, ONE, MANY }

    private suspend fun fill(core: MekaCore, fill: Fill) {
        if (fill == Fill.EMPTY) return
        check(!core.addDocument("Passport expires 3 Mar 2029").refused)
        if (fill == Fill.MANY) {
            check(!core.addDocument("Alex passport 20 May 2027").refused)
            check(!core.addDocument("Railcard 05/2027").refused)
            check(!core.addDocument("Driving licence expires 12 Nov 2026").refused)
            check(!core.addDocument("Home insurance renews 1 Dec 2026").refused)
            check(!core.addDocument("GHIC 14 Feb 2028").refused)
        }
    }

    private fun shoot(place: Place, name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK) {
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
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    when (place) {
                        Place.VAULT -> YourData(core, vaultLine = DocumentRules.VAULT_LINE)
                        Place.YOUR_DATA -> YourData(core, onClose = {})
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
        // The export's count comes from the core's own thread (a LaunchedEffect), so wait for it in real time too.
        val summary = runBlocking { core.exportSummary() }
        var n = 0
        while (compose.onAllNodes(hasText(summary.totalLine), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() && n++ < 80) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path(place.area, name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertDatesWhole(compose, name)
        ScreenShots.assertNumbersWhole(compose, name)
        ScreenShots.assertShows(compose, name, place.title)
        ScreenShots.assertShows(compose, name, LoneWordGlue.kept(DataExport.intro(DataExport.PHONE)))
        if ("documents" !in DataExport.intro(DataExport.PHONE)) fail("$name: the export's line doesn't name documents")
        val shown = ScreenShots.texts(compose)
        if (shown.any { "when the Vault lands" in DateGlue.plain(it) }) fail("$name: the export still waits on the Vault")
        ScreenShots.assertShows(compose, name, summary.totalLine)
        if (summary.total == 0) compose.onNodeWithText(DataExport.EXPORT_BUTTON).assertIsNotEnabled()
        if (place == Place.VAULT) {
            val v = core.documentsView.value
            ScreenShots.assertShows(compose, name, LoneWordGlue.kept(v.summary))
            ScreenShots.assertEachOneLine(compose, name, listOf(DocumentRules.ADD_HINT))
            ScreenShots.assertReadable(compose, name, v.rows.flatMap { listOf(it.title, it.line, it.status) }, mustShow = false)
            ScreenShots.assertNoLoneWords(compose, name, listOf(DocumentRules.NOTE, DocumentRules.VAULT_LINE, v.summary))
            // The line under the title and the summary under DOCUMENTS never say the same thing twice.
            val tail = DocumentRules.VAULT_LINE.substringAfter(";", "").trim()
            if (tail.isNotEmpty() && tail in v.summary) fail("$name: the Vault's line and its summary both say \"$tail\"")
            if (fill == Fill.EMPTY && v.summary != DocumentRules.EMPTY_LINE) fail("$name: \"${v.summary}\" with no documents")
            if (fill == Fill.MANY && v.rows.size != 6) fail("$name: ${v.rows.size} rows for six documents")
            if (fill == Fill.MANY && v.rows.none { it.statusLit }) fail("$name: the licence running out next month isn't lit")
        }
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun vaultCoverEmpty() = shoot(Place.VAULT, "cover-dark-empty", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun vaultCoverOne() = shoot(Place.VAULT, "cover-dark-one", Fill.ONE)
    @Test @Config(qualifiers = ScreenShots.COVER) fun vaultCoverMany() = shoot(Place.VAULT, "cover-dark-many", Fill.MANY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun vaultCoverManyLight() = shoot(Place.VAULT, "cover-light-many", Fill.MANY, ThemeChoice.LIGHT)
    @Test @Config(qualifiers = ScreenShots.INNER) fun vaultInnerEmpty() = shoot(Place.VAULT, "inner-dark-empty", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.INNER) fun vaultInnerMany() = shoot(Place.VAULT, "inner-dark-many", Fill.MANY)

    @Test @Config(qualifiers = ScreenShots.COVER) fun yourDataCoverEmpty() = shoot(Place.YOUR_DATA, "cover-dark-empty", Fill.EMPTY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun yourDataCoverMany() = shoot(Place.YOUR_DATA, "cover-dark-many", Fill.MANY)
    @Test @Config(qualifiers = ScreenShots.COVER) fun yourDataCoverManyLight() = shoot(Place.YOUR_DATA, "cover-light-many", Fill.MANY, ThemeChoice.LIGHT)
    @Test @Config(qualifiers = ScreenShots.INNER) fun yourDataInnerMany() = shoot(Place.YOUR_DATA, "inner-dark-many", Fill.MANY)
}
