package os.meka.android.screens

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
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
import org.robolectric.shadows.ShadowAlarmManager
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.android.fold.BedsideClock
import os.meka.android.fold.BedsideDream
import os.meka.android.fold.FoldState
import os.meka.android.today.WATCH_FACE_TAG
import os.meka.core.domain.AboutField
import os.meka.core.domain.BedsideSection
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.EventFields
import os.meka.core.domain.FoldModeRules
import os.meka.core.domain.FoldPosture
import os.meka.core.domain.HeadlineFields
import os.meka.core.domain.LoneWordGlue
import os.meka.core.domain.MekaSchema
import os.meka.core.domain.WatchFaceRules
import os.meka.core.facade.MekaCore
import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Op
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
 * The quality gate, slice 63 (build plan: "the rest of Ask's places", Bedside the last): the bedside clock on the Fold's
 * inner screen standing half folded on a table (landscape, the hinge across the middle), at the four parts of the day
 * it reads differently — the morning with the brief unread (07:40), the day with what's next (13:00), the evening with
 * tomorrow and the alarm set (20:30), and quiet hours dimmed (23:30) — plus nothing on at all, Dark and Light; and the
 * charging screen saver on both screens. Saved under `bedside/` and read for nonsense, cut lines, broken words, split
 * dates, times and numbers, a word left alone and a dot starting a line, with ‹ Back a 48 dp touch. By hand: ‹ Back
 * leaves the clock, the morning's section opens the brief, the evening's opens the shutdown, and the watch face springs
 * up the whole day ("‹ Clock"). The alarm is the phone's own (Robolectric's alarm service); events are written as the
 * calendar mirror writes them, headlines as the server's feed does. Invented data only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class BedsideScreensTest {
    /** Outermost: a hang fails within minutes with every thread's stack, instead of holding CI to its limit. */
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val london = TimeZone.of("Europe/London")

    /** Wed 21 Oct 2026 in London (or the next day with [day] = 22). */
    private fun at(h: Int, m: Int = 0, day: Int = 21) = LocalDateTime(2026, 10, day, h, m).toInstant(london).toEpochMilliseconds()

    private companion object { const val SETTLE_MS = 3_000L }

    private enum class Scene(val hour: Int, val minute: Int, val section: BedsideSection, val alarm: Boolean) {
        EMPTY(13, 0, BedsideSection.DAY, alarm = false),
        MORNING(7, 40, BedsideSection.MORNING, alarm = false),
        DAY(13, 0, BedsideSection.DAY, alarm = false),
        EVENING(20, 30, BedsideSection.EVENING, alarm = true),
        QUIET(23, 30, BedsideSection.EVENING, alarm = true),
    }

    private var now = 0L
    private val ops = InMemoryServerOpStore()
    private val serverClock = HlcClock("server", { now })
    private var n = 0

    private fun headline(id: String, title: String, topic: String, agoMin: Long, source: String) {
        listOf(
            HeadlineFields.TITLE to FieldValue.Text(title),
            HeadlineFields.URL to FieldValue.Text("https://news.example/$id"),
            HeadlineFields.SOURCE to FieldValue.Text(source),
            HeadlineFields.TOPIC to FieldValue.Text(topic),
            HeadlineFields.PUBLISHED_AT to FieldValue.Int64(now - agoMin * 60_000L),
            HeadlineFields.REMOVED to FieldValue.Bool(false),
        ).forEach { (field, value) ->
            ops.append(Op("srv${n++}", "hh", EntityTypes.HEADLINE, id, field, value, serverClock.now(), emptyList(), "server"))
        }
    }

    /** Navilife's server, as far as the bedside asks it: sync (the headlines its news strip drifts). */
    private inner class Server : SyncTransport {
        val service = SyncService(ops)
        override suspend fun push(request: PushRequest) = service.push(request)
        override suspend fun pull(request: PullRequest) = service.pull(request)
    }

    private fun seed(store: ReplicaStore, scene: Scene) {
        if (scene == Scene.EMPTY) return
        var k = 0
        val mirror = Replica("hh", "server", HlcClock("server", { now }), store, MekaSchema) { "seed${k++}" }
        fun event(id: String, title: String, start: Long, end: Long, place: String? = null) =
            mirror.commitLocal(EntityTypes.EVENT, id, buildMap {
                put(EventFields.TITLE, title.fv()); put(EventFields.START_AT, start.fv()); put(EventFields.END_AT, end.fv())
                put(EventFields.PROVIDER, "google".fv()); put(EventFields.ACCOUNT, "alex@example.com".fv())
                put(EventFields.CALENDAR, "Personal".fv())
                place?.let { put(EventFields.LOCATION, it.fv()) }
            })
        event("ev1", "Quarterly planning with the design and engineering leads", at(10, 0), at(11, 30), place = "Harbour House, Dock Street")
        // Within the hour, so it is what Up next names (Today's next event looks an hour ahead, TimelineRules.UP_NEXT_WINDOW_MIN).
        event("ev2", "Dentist", at(13, 45), at(14, 15), place = "Oakfield Dental")
        event("ev3", "Swimming lesson", at(18, 30), at(19, 15), place = "Oakfield Leisure Centre")
        event("ev4", "Parents' evening at Oakfield School", at(9, 30, day = 22), at(10, 0, day = 22))
        headline("top-0", "Rail fares to rise less than expected next year", "top", 40, "BBC News")
        headline("world-0", "Leaders meet to agree a new climate fund", "world", 95, "BBC News")
    }

    /** The phone's own next alarm: Thursday 06:30 (the Clock app's, as AlarmManager reports it). */
    private fun setAlarm(context: Context) {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val show = PendingIntent.getBroadcast(context, 0, Intent("os.meka.test.ALARM"), PendingIntent.FLAG_IMMUTABLE)
        am.setAlarmClock(AlarmManager.AlarmClockInfo(at(6, 30, day = 22), show), show)
    }

    private fun core(scene: Scene, theme: ThemeChoice): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        now = at(scene.hour, scene.minute)
        val store = InMemoryReplicaStore()
        seed(store, scene)
        val core = MekaCore(
            householdId = "hh", deviceId = "android", store = store, transport = if (scene == Scene.EMPTY) null else Server(),
            secureRandom = Random(63), timeZone = { london }, nowMs = { now },
        )
        context.useCoreForScreens(core)
        if (scene.alarm) setAlarm(context)
        runBlocking {
            core.tick()
            if (scene != Scene.EMPTY) {
                core.syncNow()
                core.setWorkSchedule(listOf(1, 2, 3, 4, 5), 9 * 60, 17 * 60 + 30, true)
                core.setAboutYou(AboutField.SHOWN_NAME, "Alex")
                val today = core.weekBoard.value.todayEpochDay
                core.addOnBoardSized("Book the MOT", today, 30)
                core.addOnBoardSized("Plan Saturday's football run with Sam", today, 60)
                core.addWaiting("Quote for the new fence", "Jordan", 0)
            }
            core.tick()
        }
        return core
    }

    /** The clock standing half folded: the hinge across the middle of the inner screen (window coordinates). */
    private fun standing(context: Context): FoldState {
        val m = context.resources.displayMetrics
        return FoldState(FoldPosture.TABLETOP, Rect(0, m.heightPixels / 2, m.widthPixels, m.heightPixels / 2))
    }

    private var backs = 0

    private fun setUp(scene: Scene, theme: ThemeChoice = ThemeChoice.DARK): MekaCore {
        val core = core(scene, theme)
        val fold = standing(ApplicationProvider.getApplicationContext())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    BedsideClock(core, fold, onBack = { backs++ })
                }
            }
        }
        turns()
        return core
    }

    /** Runs the main looper and the frames by turns (a tap reaches the recomposer through it). */
    private fun turns(ms: Long = SETTLE_MS) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    private fun tap(name: String, what: String, matcher: SemanticsMatcher) {
        val nodes = compose.onAllNodes(matcher and hasClickAction())
        if (nodes.fetchSemanticsNodes().isEmpty()) fail("$name: no $what to tap")
        nodes[0].performSemanticsAction(SemanticsActions.OnClick)
        turns()
    }

    private fun readAll(name: String, lines: List<String>) {
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertDatesWhole(compose, name)
        ScreenShots.assertClocksWhole(compose, name)
        ScreenShots.assertNumbersWhole(compose, name)
        ScreenShots.assertNoLoneWords(compose, name, lines)
        ScreenShots.assertNoDotStartingALine(compose, name, lines)
    }

    private fun shoot(name: String, scene: Scene, theme: ThemeChoice = ThemeChoice.DARK) {
        val core = setUp(scene, theme)
        val v = core.bedside(if (scene.alarm) at(6, 30, day = 22) else null)
        compose.waitForIdle()
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("bedside", name))
        if (v.section != scene.section) fail("$name: the clock reads the ${v.section} at ${v.time}, not the ${scene.section}")
        readAll(name, v.lines + v.alarmLine + v.heading)
        // The time is drawn digit by digit (each rolls on its own); its words are the clock's description.
        ScreenShots.assertShows(compose, name, v.time)
        ScreenShots.assertShows(compose, name, v.dateLabel)
        // The lines are drawn with their last words, dots and times kept together (LoneWordGlue).
        ScreenShots.assertShows(compose, name, LoneWordGlue.kept(v.alarmLine))
        ScreenShots.assertShows(compose, name, v.heading.uppercase())
        v.lines.forEach { ScreenShots.assertShows(compose, name, LoneWordGlue.kept(it)) }
        ScreenShots.assertEachOneLine(compose, name, listOf(LoneWordGlue.kept(v.alarmLine), v.dateLabel))
        // ‹ Back is a real button: 48 dp to tap.
        compose.onAllNodes(hasText("‹ Back") and hasClickAction()).fetchSemanticsNodes().firstOrNull()?.let { back ->
            val touchDp = back.boundsInRoot.height / compose.density.density
            if (touchDp < 47.5f) fail("$name: ‹ Back is ${touchDp.toInt()} dp tall to tap (at least 48)")
        } ?: fail("$name: no ‹ Back")
        // The watch face beside the time on the inner screen's clock half.
        if (compose.onAllNodes(hasTestTag(WATCH_FACE_TAG), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            fail("$name: no watch face beside the time")
        }
        when (scene) {
            Scene.EMPTY -> {
                if (v.lines != listOf(FoldModeRules.NOTHING_NEXT)) fail("$name: nothing on, but the clock reads ${v.lines}")
                if (v.alarmLine != FoldModeRules.NO_ALARM) fail("$name: no alarm, but \"${v.alarmLine}\"")
            }
            Scene.MORNING -> ScreenShots.assertShows(compose, name, "Open the brief ›")
            Scene.DAY -> if (v.lines.none { "Dentist" in it }) fail("$name: the dentist at 13:45 isn't what's next (${v.lines})")
            Scene.EVENING -> {
                if (!v.alarmSet || !v.alarmLine.startsWith("Alarm 06:30")) fail("$name: the alarm reads \"${v.alarmLine}\"")
                ScreenShots.assertShows(compose, name, "See tomorrow ›")
            }
            Scene.QUIET -> if (!v.dim) fail("$name: 23:30 is in quiet hours, but the clock isn't dimmed")
        }
    }

    // ---- Shots: the inner screen standing half folded ----

    @Test @Config(qualifiers = ScreenShots.INNER) fun standEmpty() = shoot("stand-dark-empty", Scene.EMPTY)
    @Test @Config(qualifiers = ScreenShots.INNER) fun standMorning() = shoot("stand-dark-morning", Scene.MORNING)
    @Test @Config(qualifiers = ScreenShots.INNER) fun standDay() = shoot("stand-dark-day", Scene.DAY)
    @Test @Config(qualifiers = ScreenShots.INNER) fun standEvening() = shoot("stand-dark-evening", Scene.EVENING)
    @Test @Config(qualifiers = ScreenShots.INNER) fun standQuiet() = shoot("stand-dark-quiet", Scene.QUIET)
    @Test @Config(qualifiers = ScreenShots.INNER) fun standDayLight() = shoot("stand-light-day", Scene.DAY, ThemeChoice.LIGHT)
    @Test @Config(qualifiers = ScreenShots.INNER) fun standEveningLight() = shoot("stand-light-evening", Scene.EVENING, ThemeChoice.LIGHT)

    // ---- Shots: the charging screen saver, on both screens ----

    private fun dream(name: String, scene: Scene, theme: ThemeChoice = ThemeChoice.DARK) {
        val core = core(scene, theme)
        var dimmed: Boolean? = null
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    BedsideDream(core, onDim = { dimmed = it })
                }
            }
        }
        turns()
        compose.waitForIdle()
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("bedside", name))
        val v = core.bedsideDream(if (scene.alarm) at(6, 30, day = 22) else null)
        readAll(name, v.lines + v.alarmLine + v.heading)
        ScreenShots.assertShows(compose, name, v.dateLabel)
        ScreenShots.assertShows(compose, name, LoneWordGlue.kept(v.alarmLine))
        ScreenShots.assertEachOneLine(compose, name, listOf(LoneWordGlue.kept(v.alarmLine), v.dateLabel))
        if (dimmed != v.dim) fail("$name: the screen saver told the screen dim = $dimmed, but quiet hours say ${v.dim}")
        // Nothing to press on the screen saver: a touch ends it.
        if (compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().isNotEmpty()) fail("$name: the screen saver has something to tap")
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun dreamCoverQuiet() = dream("dream-cover-dark-quiet", Scene.QUIET)
    @Test @Config(qualifiers = ScreenShots.COVER) fun dreamCoverDayLight() = dream("dream-cover-light-day", Scene.DAY, ThemeChoice.LIGHT)
    @Test @Config(qualifiers = ScreenShots.INNER) fun dreamInnerEvening() = dream("dream-inner-dark-evening", Scene.EVENING)

    // ---- By hand ----

    /** ‹ Back leaves the clock for the app. */
    @Test @Config(qualifiers = ScreenShots.INNER)
    fun backLeavesByHand() {
        val name = "stand-dark-back"
        setUp(Scene.DAY)
        tap(name, "‹ Back", hasText("‹ Back"))
        if (backs != 1) fail("$name: ‹ Back didn't leave the clock")
    }

    /** In the morning the section opens the brief over the clock. */
    @Test @Config(qualifiers = ScreenShots.INNER)
    fun morningOpensTheBriefByHand() {
        val name = "stand-dark-brief"
        setUp(Scene.MORNING)
        tap(name, "the morning's section", hasText("Open the brief ›", substring = true))
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("bedside", name))
        ScreenShots.assertShows(compose, name, "Good morning")
    }

    /** In the evening the section opens the shutdown over the clock. */
    @Test @Config(qualifiers = ScreenShots.INNER)
    fun eveningOpensTheShutdownByHand() {
        val name = "stand-dark-shutdown"
        setUp(Scene.EVENING)
        tap(name, "the evening's section", hasText("See tomorrow ›", substring = true))
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("bedside", name))
        if (compose.onAllNodes(hasText("Shut down", substring = true)).fetchSemanticsNodes().isEmpty()) {
            fail("$name: the section's tap didn't open Shut down the day")
        }
    }

    /** The watch face springs the whole day up; ‹ Clock drops it away. */
    @Test @Config(qualifiers = ScreenShots.INNER)
    fun watchFaceOpensTheDayByHand() {
        val name = "stand-dark-day-ring"
        setUp(Scene.DAY)
        tap(name, "the watch face", hasTestTag(WATCH_FACE_TAG))
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("bedside", name))
        ScreenShots.assertShows(compose, name, WatchFaceRules.BEDSIDE_SHEET_BACK)
        tap(name, WatchFaceRules.BEDSIDE_SHEET_BACK, hasText(WatchFaceRules.BEDSIDE_SHEET_BACK))
        if (compose.onAllNodes(hasText(WatchFaceRules.BEDSIDE_SHEET_BACK)).fetchSemanticsNodes().isNotEmpty()) {
            fail("$name: ‹ Clock didn't drop the day away")
        }
    }
}
