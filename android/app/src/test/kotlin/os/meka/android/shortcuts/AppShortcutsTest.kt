package os.meka.android.shortcuts

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import os.meka.android.MainActivity
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.screens.ShotApplication
import os.meka.android.today.DayListPane
import os.meka.core.domain.DayListRules
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore

/**
 * The Day list, slice 2 (`docs/design/day-list/Today.dc.html`: "hold the app icon → Add tasks"): the app icon's menu
 * carries Add tasks, which opens MainActivity asking for today's Day list ready to type; opened that way the add bar has
 * the focus at once (opened from Today's heading it doesn't), and the first task typed with Enter lands on today.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ShotApplication::class)
class AppShortcutsTest {
    @get:Rule(order = 0)
    val hangWatch = os.meka.android.screens.HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val london = TimeZone.of("Europe/London")
    private val now = 1_792_606_200_000L // Wed 21 Oct 2026, 19:10 in London

    @Test
    fun theIconsMenuCarriesAddTasksForTodaysDayList() {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        AppShortcuts.publish(context)
        val shortcut = ShortcutManagerCompat.getDynamicShortcuts(context).single { it.id == DayListRules.SHORTCUT_ID }
        assertEquals("Add tasks", shortcut.shortLabel.toString())
        assertEquals("Add tasks for today", shortcut.longLabel.toString())
        val intent = shortcut.intent
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(MainActivity.OPEN_DAY_LIST_ADD, intent.getStringExtra(MainActivity.EXTRA_OPEN))
        // Published again at the next start: still one, never two.
        AppShortcuts.publish(context)
        assertEquals(1, ShortcutManagerCompat.getDynamicShortcuts(context).count { it.id == DayListRules.SHORTCUT_ID })
    }

    private fun core(): MekaCore {
        val core = MekaCore(
            householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
            secureRandom = Random(7), timeZone = { london }, nowMs = { now },
        )
        ApplicationProvider.getApplicationContext<ShotApplication>().useCoreForScreens(core)
        runBlocking { core.tick() }
        return core
    }

    @Test
    fun fromTheIconTheAddBarIsReadyAndEnterAddsToToday() {
        val core = core()
        compose.setContent {
            MekaTheme { Box(Modifier.fillMaxSize().background(Meka.colors.background)) { DayListPane(core, onClose = {}, readyToType = true) } }
        }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.onNode(hasSetTextAction()).performTextInput("Call Sam 15m")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitUntil(5_000) {
            runBlocking { core.tick() }
            core.dayList(core.weekBoard.value, core.weekBoard.value.todayEpochDay, true).rows.any { it.title == "Call Sam" }
        }
        compose.onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test
    fun fromTodaysHeadingTheListOpensWithoutTheKeyboard() {
        val core = core()
        compose.setContent {
            MekaTheme { Box(Modifier.fillMaxSize().background(Meka.colors.background)) { DayListPane(core, onClose = {}) } }
        }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
    }
}
