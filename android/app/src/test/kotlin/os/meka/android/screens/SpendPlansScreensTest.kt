package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.ask.SpendPlansPage
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.android.notify.NOTIFY_CHOICE_SHEET_TAG
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
 * Walk-through week 5, A17 on Spending plans: the plans were an open chip row along the top of the page, the third cut
 * at the cover's edge. Now one "Showing" · "Christmas gifts" › row opens a sheet of the plans, each saying its total
 * and day, and a pick shows that plan. Invented plans (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class SpendPlansScreensTest {
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    /** Sat 17 Oct 2026, 09:30 London; a minute on per plan added, so the plans keep the order they were added in. */
    private var now = 1_792_225_800_000L
    private val ops = InMemoryServerOpStore()
    private val sync = SyncService(ops)
    private val transport = object : SyncTransport {
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
    }

    private fun turns(ms: Long = 2_000L) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    private fun waitFor(ok: () -> Boolean) { repeat(30) { if (!ok()) turns(200) } }

    private fun has(spoken: String) = compose.onAllNodes(hasContentDescription(spoken)).fetchSemanticsNodes().isNotEmpty()

    private fun sheetOpen() = compose.onAllNodes(hasTestTag(NOTIFY_CHOICE_SHEET_TAG)).fetchSemanticsNodes().isNotEmpty()

    /** A sheet line read with plain spaces (the sheet keeps short words with their neighbours). */
    private fun sheetSays(line: String): Boolean {
        val says = SemanticsMatcher("reads \"$line\"") { n ->
            n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }?.replace(' ', ' ') == line
        }
        return compose.onAllNodes(says and hasAnyAncestor(hasTestTag(NOTIFY_CHOICE_SHEET_TAG)), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
    }

    private fun open() {
        ThemeChoice.save(ApplicationProvider.getApplicationContext<ShotApplication>(), ThemeChoice.DARK)
        val core = MekaCore(
            householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = transport,
            secureRandom = Random(53), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
        )
        val nov = 2026 * 12 + 10
        runBlocking {
            val gifts = core.addSpendPlan("Christmas gifts 20 Dec", "GBP").id!!
            core.addSpendThing(gifts, "Lego set", "For Alex", "60", nov)
            now += 60_000L
            core.addSpendPlan("Weekend away 6 Feb", "GBP")
            now += 60_000L
            core.addSpendPlan("Birthday party", "GBP")
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    SpendPlansPage(core, onBack = {})
                }
            }
        }
        turns()
        waitFor { has(SHOWING_GIFTS) }
    }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun thePlansAreAShowingRowOpeningTheirSheetByHand() {
        open()
        if (!has(SHOWING_GIFTS)) fail("no \"$SHOWING_GIFTS\" row")
        // No open chip of another plan on the page.
        listOf("Weekend away", "Birthday party").forEach { name ->
            if (ScreenShots.texts(compose).any { it == name }) fail("an open \"$name\" chip is still on the page")
        }
        compose.onNode(hasContentDescription(SHOWING_GIFTS)).performClick()
        waitFor { sheetOpen() }
        if (!sheetOpen()) fail("Showing didn't open the plans' sheet")
        if (!sheetSays("£60 · needed by 20 Dec · £60 to go")) fail("the sheet doesn't say what Christmas gifts holds")
        if (!sheetSays("Nothing on it yet")) fail("the sheet doesn't say an empty plan is empty")
        compose.onAllNodes(hasText("Weekend away") and hasClickAction() and hasAnyAncestor(hasTestTag(NOTIFY_CHOICE_SHEET_TAG)))[0]
            .performSemanticsAction(SemanticsActions.OnClick)
        waitFor { !sheetOpen() }
        if (sheetOpen()) fail("choosing Weekend away didn't close the sheet")
        val away = "Showing Weekend away, 3 plans. Opens the choices"
        waitFor { has(away) }
        if (!has(away)) fail("the row doesn't say Weekend away")
    }

    private companion object {
        const val SHOWING_GIFTS = "Showing Christmas gifts, 3 plans. Opens the choices"
    }
}
