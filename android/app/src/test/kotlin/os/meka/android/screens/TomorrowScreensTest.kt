package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
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
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.android.today.TomorrowPane
import os.meka.android.today.TomorrowPeekCard
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.EventFields
import os.meka.core.domain.LoneWordGlue
import os.meka.core.domain.MekaSchema
import os.meka.core.facade.MekaCore
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.Replica
import os.meka.core.sync.ReplicaStore
import os.meka.core.sync.fv

/**
 * The Tomorrow peek and sheet (Meka 2026-10-26: "I asked the AI to add a task for tomorrow, it did, but on this screen how
 * do I see tomorrow's stuff?"), on the Fold at 19:10 on a Wednesday with ShotLife's Thursday (two tasks, one at 07:00, a bill
 * and the parents' evening at 18:00): the peek
 * card says what's on and the sheet lists both; **Today** moves one to today in the same frame and says so; the tick
 * ring completes the other. Every button answers a 48 dp touch. Dark and Light; saved under `tomorrow/`. Invented data.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class TomorrowScreensTest {
    /** Outermost: a hang fails within minutes with every thread's stack, instead of holding CI to its limit. */
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private companion object { const val PEEK_TAG = "tomorrow-peek" }

    private val london = TimeZone.of("Europe/London")
    private val now = 1_792_606_200_000L // Wed 21 Oct 2026, 19:10 in London

    private fun setUp(theme: ThemeChoice): Pair<MekaCore, Pair<String, String>> {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val store = InMemoryReplicaStore()
        seed(store)
        val core = MekaCore(
            householdId = "hh", deviceId = "android", store = store, transport = null,
            secureRandom = Random(27), timeZone = { london }, nowMs = { now },
        )
        context.useCoreForScreens(core)
        val ids = runBlocking {
            core.tick()
            // Walk-through 2026-11-15 A13: the Thursday the bedside and Week ahead show (ShotLife), the gym at 07:00
            // first, the school call, the water bill due (Tomorrow on Today, slice 2) and the parents' evening at 18:00.
            val made = ShotLife.addThursday(core, core.todayEpochDay() + 1)
            core.tick()
            made
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    // Walk-through 2026-11-08 A7: the peek as Today draws it, from the live view (read once, it kept
                    // "2 tasks" after a move and the old title over an edit).
                    val peek by core.shutdownView.collectAsState()
                    // Walk-through 2026-11-15 A22: the peek is Today's card and the sheet covers Today when it opens
                    // (MekaPane), so they're never on screen together; the shot drew the peek as a banner over the
                    // sheet, which said the header twice and left a ~70 dp gap above "‹ Today". Each is drawn alone.
                    if (onSheet) {
                        TomorrowPane(core, onClose = {})
                    } else Column {
                        // Walk-through 2026-11-08 A19: inside Today's gutter and top space, as Today's list holds it
                        // (the shot drew it against the screen's top and side edges).
                        peek.peek?.let {
                            TomorrowPeekCard(
                                it, onOpen = {},
                                Modifier.padding(start = MekaSpace.gutter, end = MekaSpace.gutter, top = MekaSpace.xl, bottom = MekaSpace.l)
                                    .testTag(PEEK_TAG),
                            )
                        }
                    }
                }
            }
        }
        turns()
        return core to ids
    }

    /** ShotLife's week, written as the calendar mirror writes it (only Thursday's parents' evening is tomorrow's). */
    private fun seed(store: ReplicaStore) {
        var k = 0
        val mirror = Replica("hh", "server", HlcClock("server", { now }), store, MekaSchema) { "seed${k++}" }
        ShotLife.WEEK.forEach { e ->
            mirror.commitLocal(EntityTypes.EVENT, e.id, buildMap {
                put(EventFields.TITLE, e.title.fv())
                put(EventFields.START_AT, at(e.day, e.startMin).fv())
                put(EventFields.END_AT, at(e.day, e.endMin).fv())
                put(EventFields.PROVIDER, "google".fv()); put(EventFields.ACCOUNT, "jordan@example.com".fv())
                put(EventFields.CALENDAR, e.calendar.fv())
                e.place?.let { put(EventFields.LOCATION, it.fv()) }
            })
        }
    }

    private fun at(day: Int, minute: Int) =
        LocalDateTime(2026, 10, day, minute / 60, minute % 60).toInstant(london).toEpochMilliseconds()

    private fun turns(ms: Long = 3_000L) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    private fun until(what: () -> Boolean) {
        var i = 0
        while (!what() && i++ < 80) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        turns(1_000)
    }

    private fun shows(text: String) = compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun assertTouchTargets(name: String) {
        val small = compose.onAllNodes(hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .fetchSemanticsNodes()
            .filter { it.size.height / compose.density.density < 47.5f }
            .map { n -> (n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } ?: "?") + " ${(n.size.height / compose.density.density).toInt()} dp" }
        if (small.isNotEmpty()) fail("$name: buttons under 48 dp to tap:\n" + small.joinToString("\n") { "- $it" })
        // Walk-through 2026-11-15 A20: each task's tick circle (a checkbox, so the check above missed it) is a whole
        // 48 × 48 target too; it was ~27 dp.
        val dp = compose.density.density
        val ticks = compose.onAllNodes(hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .fetchSemanticsNodes()
        if (ticks.isEmpty()) fail("$name: no tick circles on tomorrow's tasks")
        val smallTicks = ticks.filter { it.size.height / dp < 47.5f || it.size.width / dp < 47.5f }
            .map { n -> (n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("") ?: "?") +
                " ${(n.size.width / dp).toInt()} × ${(n.size.height / dp).toInt()} dp" }
        if (smallTicks.isNotEmpty()) fail("$name: tick circles under 48 dp to tap:\n" + smallTicks.joinToString("\n") { "- $it" })
    }

    /** Today's peek card ([onSheet] false) or the Tomorrow sheet that covers Today (true). */
    private var onSheet by mutableStateOf(false)

    private fun page(sheet: Boolean) {
        onSheet = sheet
        turns()
    }

    private fun run(theme: ThemeChoice, name: String) {
        val (core, ids) = setUp(theme)
        val (gym, school) = ids
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("tomorrow", "$name-peek"))
        val peek = assertNotNull(core.shutdownView.value.peek)
        assertTrue(peek.line.endsWith("1 event · 2 tasks · 1 bill due · first 07:00 Gym before work"), peek.line)
        // Lines are drawn through LoneWordGlue (the last words held together), so look for them as drawn.
        assertTrue(shows(LoneWordGlue.kept(peek.line)), "$name: the peek isn't shown")
        // A19: the peek stands clear of the screen's edges (Today's gutter at the sides, its top space above).
        val screen = compose.onNodeWithTag("screen").fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithTag(PEEK_TAG).fetchSemanticsNode().boundsInRoot
        val dp = compose.density.density
        assertTrue((card.top - screen.top) / dp >= 31.5f, "$name: the peek sits ${((card.top - screen.top) / dp).toInt()} dp under the top edge")
        assertTrue((card.left - screen.left) / dp >= 15.5f && (screen.right - card.right) / dp >= 15.5f,
            "$name: the peek runs into the side edges")

        // The sheet on its own, as it covers Today: its header is said once and "‹ Today" sits at its top.
        page(sheet = true)
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("tomorrow", name))
        assertTrue(!shows(LoneWordGlue.kept(peek.line)), "$name: the peek's line is drawn over the sheet")
        val close = compose.onAllNodes(hasText("‹ Today") and hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(close.isNotEmpty(), "$name: no ‹ Today on the sheet")
        val gap = (close.first().boundsInRoot.top - screen.top) / dp
        assertTrue(gap <= 40f, "$name: ‹ Today sits $gap dp under the top edge (a gap above it)")
        assertTrue(shows("Gym before work") && shows("Ring the school about the trip"), "$name: tomorrow's tasks aren't listed")
        // A13: Thursday's parents' evening is in the evening, as the bedside and Week ahead show it, after the gym.
        val rows = core.shutdownView.value.tomorrow.rows
        val evening = rows.firstOrNull { it.title == ShotLife.PARENTS_EVENING.title } ?: fail("$name: no parents' evening tomorrow ($rows)")
        assertEquals("18:00", evening.time, "$name: the parents' evening's time")
        assertTrue(rows.indexOfFirst { it.title == ShotLife.GYM } < rows.indexOf(evening), "$name: the gym isn't before the evening")
        // A22: the gym is before he leaves at 07:40, so its row carries no "After you leave" mark.
        val gymRow = rows.first { it.title == ShotLife.GYM }
        assertTrue(gymRow.detail?.contains("After you leave") != true, "$name: the gym clashes with leaving (${gymRow.detail})")
        assertTrue(shows(LoneWordGlue.kept("Due tomorrow · Water bill £32.50")), "$name: tomorrow's bill isn't shown")
        // Walk-through 2026-11-15 A16: under the "Tomorrow" title the header says the day, the first time and the counts
        // once each: "THU 22 OCT", "Starts at 07:00 · Gym before work", "1 event · 2 tasks" (no "first at 07:00" again).
        assertTrue(shows("THU 22 OCT") && !shows("TOMORROW · THU 22 OCT"), "$name: the date is said with Tomorrow again")
        assertTrue(shows(LoneWordGlue.kept("Starts at 07:00 · Gym before work")) && !shows("Tomorrow starts at"),
            "$name: the first line says Tomorrow again")
        assertTrue(shows(LoneWordGlue.kept("1 event · 2 tasks")) && !shows("first at 07:00"), "$name: the counts say the first time again")
        assertTouchTargets(name)

        // Today on the school task: it leaves tomorrow and the sheet says where it went.
        val todays = compose.onAllNodes(hasText("Today") and hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes()
        if (todays.size < 2) fail("$name: no Today on each task")
        compose.onAllNodes(hasText("Today") and hasClickAction(), useUnmergedTree = true)[1].performSemanticsAction(SemanticsActions.OnClick)
        until { core.shutdownView.value.tomorrow.rows.none { it.id == "t-$school" } }
        assertTrue(core.shutdownView.value.left.any { it.task.id == school })
        val moved = LoneWordGlue.kept("Moved “Ring the school about the trip” to Today")
        until { shows(moved) }
        assertTrue(shows(moved), "$name: the move isn't said")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("tomorrow", "$name-moved"))
        assertEquals(listOf("t-$gym"), core.shutdownView.value.tomorrow.rows.filter { !it.isEvent }.map { it.id })
        val afterMove = assertNotNull(core.shutdownView.value.peek).line
        assertTrue(afterMove.contains("1 task ·") && !afterMove.contains("2 tasks"), afterMove)
        assertTrue(!shows("2 tasks"), "$name: the sheet still counts the moved task")
        // Back on Today, the peek counts it too.
        page(sheet = false)
        until { shows(LoneWordGlue.kept(afterMove)) }
        assertTrue(shows(LoneWordGlue.kept(afterMove)) && !shows("2 tasks"), "$name: the peek still counts the moved task")
        page(sheet = true)

        // Tomorrow on Today, slice 3: Edit opens the task's full detail over the sheet; an edit shows in it at once,
        // and once the task is no longer tomorrow's (done here) the detail closes.
        assertTrue(!shows("Close"), "$name: a detail is open before Edit")
        compose.onAllNodes(hasText("Edit") and hasClickAction(), useUnmergedTree = true)[0].performSemanticsAction(SemanticsActions.OnClick)
        until { shows("Close") }
        assertTrue(shows("Close"), "$name: Edit didn't open the task")
        runBlocking { core.rename(gym, "Gym, legs day") }
        until { shows("Gym, legs day") }
        assertTrue(shows("Gym, legs day"), "$name: the edit isn't shown")
        // A7: the row behind the detail says the new name too, never the old one beside it.
        until { !shows("Gym before work") }
        assertTrue(!shows("Gym before work"), "$name: the old name is still shown beside the edited detail")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("tomorrow", "$name-edit"))
        // Walk-through 2026-11-02 A4: the line under the title is When's own time on the core's clock (it drew 06:40,
        // the phone's zone, while When said 07:40; the gym is at 07:00 since A22), and on the cover screen the Repeat row stands whole above the pills.
        assertTrue(!shows("06:00"), "$name: a time under the title disagrees with When")
        assertTrue(compose.onAllNodes(hasText("07:00"), useUnmergedTree = true).fetchSemanticsNodes().size >= 2,
            "$name: the title's line doesn't say When's 07:00")
        val repeat = compose.onAllNodes(hasText("Repeat"), useUnmergedTree = true).fetchSemanticsNodes()
        val done = compose.onAllNodes(hasText("Done") and hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(repeat.isNotEmpty() && done.isNotEmpty(), "$name: no Repeat row or Done pill on the detail")
        assertTrue(repeat.first().boundsInRoot.bottom <= done.minOf { it.boundsInRoot.top },
            "$name: the Repeat row sits under the pills")
        // Walk-through A10: the detail's pills wrap rather than running off the right edge (Someday was cut).
        val screenRight = compose.onNodeWithTag("screen").fetchSemanticsNode().boundsInRoot.right
        val someday = compose.onAllNodes(hasText("Someday") and hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(someday.isNotEmpty(), "$name: no Someday on the detail")
        someday.forEach { assertTrue(it.boundsInRoot.right <= screenRight + 0.5f, "$name: Someday is cut at the right edge") }
        runBlocking { core.complete(gym) }
        until { !shows("Close") }
        assertTrue(!shows("Close"), "$name: the detail stayed open after the task left tomorrow")
    }

    // On the Fold's cover screen (walk-through A4: the shots were Robolectric's default 320 × 470).
    @Test @Config(qualifiers = ScreenShots.COVER) fun tomorrowDark() = run(ThemeChoice.DARK, "fold-dark")
    @Test @Config(qualifiers = ScreenShots.COVER) fun tomorrowLight() = run(ThemeChoice.LIGHT, "fold-light")
}
