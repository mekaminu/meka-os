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
import kotlin.test.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.ask.MoneyPane
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.core.domain.MoneyRules
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random

/**
 * The quality gate, slice 11 (build plan: "the same shots for every other screen (Money, …)"): Ask → Life → Money on the
 * Fold's cover and inner screens, opened (the fingerprint's yes stood in by [MekaCore.openMoney]): nothing imported yet,
 * one account with a few payments, and a full month (two accounts, September and October, groceries, eating out,
 * transport, bills, salary, a pension counted in net worth), Dark and Light. Saved under `money/` (the `fold-screens`
 * artifact, then the `screens` branch's docs/screens/fold/money/) and read for nonsense, cut lines and words broken in
 * two. Invented data only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class MoneyScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(23), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private companion object {
        const val SETTLE_MS = 3_000L

        const val ONE = "Date,Description,Amount\n" +
            "02/10/2026,TESCO STORES 2041,-40.00\n" +
            "03/10/2026,COSTA COFFEE,-3.20\n" +
            "05/10/2026,SALARY OAKFIELD LTD,2500.00\n"

        const val CURRENT = "Date,Description,Amount\n" +
            "01/09/2026,OAKFIELD COUNCIL TAX,-142.00\n" +
            "04/09/2026,TESCO STORES 2041,-62.40\n" +
            "05/09/2026,SALARY OAKFIELD LTD,2500.00\n" +
            "11/09/2026,TESCO STORES 2041,-58.10\n" +
            "15/09/2026,NETFLIX.COM,-10.99\n" +
            "18/09/2026,TFL TRAVEL CH,-38.50\n" +
            "25/09/2026,COSTA COFFEE,-3.20\n" +
            "01/10/2026,OAKFIELD COUNCIL TAX,-142.00\n" +
            "02/10/2026,TESCO STORES 2041,-71.25\n" +
            "03/10/2026,COSTA COFFEE,-3.20\n" +
            "05/10/2026,SALARY OAKFIELD LTD,2500.00\n" +
            "07/10/2026,PRET A MANGER,-6.85\n" +
            "09/10/2026,TESCO STORES 2041,-64.90\n" +
            "12/10/2026,TFL TRAVEL CH,-41.20\n" +
            "14/10/2026,COSTA COFFEE,-3.40\n" +
            "15/10/2026,NETFLIX.COM,-10.99\n" +
            "16/10/2026,SHELL FORECOURT 112,-58.00\n" +
            "17/10/2026,THE GARDEN SHOP OAKFIELD,-24.99\n" +
            "19/10/2026,COSTA COFFEE,-3.40\n" +
            "20/10/2026,TESCO STORES 2041,-55.60\n"

        const val CARD = "Date,Description,Amount\n" +
            "06/10/2026,AMAZON MARKETPLACE,-32.49\n" +
            "13/10/2026,DELIVEROO,-21.80\n" +
            "18/10/2026,CINEWORLD OAKFIELD,-17.50\n"
    }

    private enum class Fill { EMPTY, ONE, MANY }

    private fun shoot(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK) {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val core = core()
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            // The fingerprint's yes: the pane only asks for it while Money is locked.
            core.openMoney()
            when (fill) {
                Fill.EMPTY -> Unit
                Fill.ONE -> check(!core.importMoneyStatement("Current account", ONE).refused)
                Fill.MANY -> {
                    check(!core.importMoneyStatement("Current account", CURRENT).refused)
                    check(!core.importMoneyStatement("Credit card", CARD).refused)
                    check(!core.moneyWorthAdd("Pension £42,000").refused)
                }
            }
            core.tick()
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    MoneyPane(core, onClose = {})
                }
            }
        }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertReadable(compose, name, emptyList(), mustShow = false)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertShows(compose, name, MoneyRules.TITLE)
        val v = core.moneyView.value
        if (v.locked) fail("$name: Money is still locked after opening")
        when (fill) {
            // Nothing in: the line says how to start, never a month of "£0".
            Fill.EMPTY -> ScreenShots.assertShows(compose, name, MoneyRules.EMPTY.substringBefore(" ·"))
            else -> {
                val m = v.month ?: fail("$name: no month shown with a statement in")
                ScreenShots.assertShows(compose, name, m.label)
                ScreenShots.assertShows(compose, name, m.spentLine)
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
}
