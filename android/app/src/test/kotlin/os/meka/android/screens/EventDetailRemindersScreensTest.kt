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
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performSemanticsAction
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.calendar.EventDetailPane
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.notify.NOTIFY_CHOICE_SHEET_TAG
import os.meka.core.domain.CalendarEvent
import os.meka.core.domain.DateGlue
import os.meka.core.domain.EventReminderChoiceRules
import os.meka.core.domain.EventDetailChoiceRules
import os.meka.core.domain.Household
import os.meka.core.domain.HouseholdNames
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random
import kotlin.test.fail

/**
 * Walk-through week 5, A17 on an event's detail: Remind me and Leave by were open chip rows ("REMIND ME" 5 min · 10 min
 * · 15 min · 30 min, "LEAVE BY · HOW LONG TO GET THERE" 10 min away … 1 h away). By hand: no reminder or travel chip is
 * left on the page; the Remind me row opens its sheet, each choice saying when its heads-up comes, and a choice sets the
 * reminder and closes the sheet; the Leave by row opens its sheet, each saying when to leave, and a choice sets it; Off
 * turns the reminder off again; Ring as an alarm, once a travel time is set, is a row opening On · Off. On a club session
 * Whose is it? is a row opening Not set · the children, each saying what the series reads, a name setting it.
 * Invented data only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class EventDetailRemindersScreensTest {
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private var zoneBefore: java.util.TimeZone? = null

    @Before
    fun london() {
        zoneBefore = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/London"))
    }

    @After
    fun zoneBack() {
        zoneBefore?.let { java.util.TimeZone.setDefault(it) }
        Household.set(HouseholdNames.EMPTY)
    }

    private companion object {
        /** Wed 21 Oct 2026, 17:15 in London: the day every other shot shows. */
        const val NOW = 1_792_599_300_000L
        /** 19:00 that evening (BST, so 18:00 UTC). */
        const val START = NOW + 105 * 60_000L
    }

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(43), timeZone = { TimeZone.of(java.util.TimeZone.getDefault().id) }, nowMs = { NOW },
    )

    private val event = CalendarEvent(
        id = "ev-parents", title = "Parents' evening", startAtMs = START, endAtMs = START + 60 * 60_000L, allDay = false,
        location = "Oakfield School hall", provider = "google", account = "alex@example.com", calendarName = "Family",
    )

    private fun turns(ms: Long = 2_000L) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    private fun until(done: () -> Boolean) {
        repeat(40) {
            if (done()) return
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
    }

    private fun sheetOpen() = compose.onAllNodesWithTag(NOTIFY_CHOICE_SHEET_TAG).fetchSemanticsNodes().isNotEmpty()

    private fun inSheet(text: String): Boolean {
        val shows = SemanticsMatcher("shows \"$text\"") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { DateGlue.plain(it.text).contains(text) } == true
        }
        return compose.onAllNodes(shows and hasAnyAncestor(hasTestTag(NOTIFY_CHOICE_SHEET_TAG)), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
    }

    private fun clickRow(spoken: String, what: String) {
        val m = hasContentDescription(spoken) and hasClickAction()
        if (compose.onAllNodes(m).fetchSemanticsNodes().isEmpty()) fail("$what isn't a row you can tap")
        compose.onAllNodes(m)[0].performSemanticsAction(SemanticsActions.OnClick)
        until { sheetOpen() }
        if (!sheetOpen()) fail("$what didn't open its sheet")
    }

    private fun choose(label: String, what: String) {
        val m = hasText(label) and hasClickAction() and hasAnyAncestor(hasTestTag(NOTIFY_CHOICE_SHEET_TAG))
        if (compose.onAllNodes(m).fetchSemanticsNodes().isEmpty()) fail("$what: $label isn't in the sheet")
        compose.onAllNodes(m)[0].performSemanticsAction(SemanticsActions.OnClick)
        until { !sheetOpen() }
        turns()
        if (sheetOpen()) fail("$what: $label didn't close the sheet")
    }

    @Test
    fun remindMeAndLeaveByAreRowsOpeningTheirSheetsByHand() {
        val core = core()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    EventDetailPane(core, event, onClose = {})
                }
            }
        }
        turns()
        val name = "event-detail-reminders"
        for (chip in listOf("10 min", "15 min", "30 min away", "45 min away")) {
            if (compose.onAllNodes(hasText(chip) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()) {
                fail("$name: \"$chip\" is an open chip on the page")
            }
        }

        clickRow(EventReminderChoiceRules.remindRow(0).spoken, "$name: Remind me")
        if (!inSheet(EventReminderChoiceRules.REMIND_TITLE)) fail("$name: the sheet isn't when to remind you")
        if (!inSheet("Heads-up at 18:50")) fail("$name: 10 min before doesn't say when its heads-up comes")
        choose("10 min before", "$name: Remind me")
        if (core.eventMarks.value.reminderOf(event.id) != 10) fail("$name: 10 min before didn't set the reminder")
        if (compose.onAllNodes(hasContentDescription(EventReminderChoiceRules.remindRow(10).spoken)).fetchSemanticsNodes().isEmpty()) {
            fail("$name: the row doesn't say 10 min before")
        }

        clickRow(EventReminderChoiceRules.leaveRow(0).spoken, "$name: Leave by")
        if (!inSheet(EventReminderChoiceRules.LEAVE_TITLE)) fail("$name: the sheet isn't how long to get there")
        if (!inSheet("Leave at 18:30")) fail("$name: 30 min away doesn't say when to leave")
        choose("30 min away", "$name: Leave by")
        if (core.eventMarks.value.travelOf(event.id) != 30) fail("$name: 30 min away didn't set Leave by")

        turns()
        if (compose.onAllNodes(hasText("On") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()) {
            fail("$name: Ring as an alarm is still an open On/Off pill")
        }
        clickRow(EventDetailChoiceRules.alarmRow(false).spoken, "$name: Ring as an alarm")
        if (!inSheet(EventDetailChoiceRules.ALARM_ON_LINE)) fail("$name: On doesn't say it rings full screen")
        choose("On", "$name: Ring as an alarm")
        if (event.id !in core.eventMarks.value.leaveAlarms) fail("$name: On didn't make Leave by ring as an alarm")
        if (compose.onAllNodes(hasContentDescription(EventDetailChoiceRules.alarmRow(true).spoken)).fetchSemanticsNodes().isEmpty()) {
            fail("$name: the alarm row doesn't say On")
        }

        clickRow(EventReminderChoiceRules.remindRow(10).spoken, "$name: Remind me again")
        choose(EventReminderChoiceRules.OFF, "$name: Remind me")
        if (core.eventMarks.value.reminderOf(event.id) != 0) fail("$name: Off didn't turn the reminder off")
    }

    @Test
    fun whoseIsItIsARowOpeningTheChildrenByHand() {
        val core = core()
        kotlinx.coroutines.runBlocking {
            check(!core.addAboutYouChild("Alex, U7, Oakfield School").refused)
            check(!core.addAboutYouChild("Sam, U10, Oakfield School").refused)
            core.tick()
        }
        val training = CalendarEvent(
            id = "ev-training", title = "Training", startAtMs = START, endAtMs = START + 60 * 60_000L, allDay = false,
            location = null, provider = "google", account = "alex@example.com", calendarName = "Kids",
        )
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    EventDetailPane(core, training, onClose = {})
                }
            }
        }
        turns()
        val name = "event-detail-whose"
        for (chip in listOf("Alex", "Sam")) {
            if (compose.onAllNodes(hasText(chip) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()) {
                fail("$name: \"$chip\" is an open chip on the page")
            }
        }
        clickRow(EventDetailChoiceRules.whoseRow(null).spoken, "$name: Whose is it?")
        if (!inSheet("Every Wed 19:00 reads Sam")) fail("$name: Sam doesn't say what the series reads")
        if (!inSheet("Every Wed 19:00 asks Alex or Sam?")) fail("$name: Not set doesn't say what it asks")
        choose("Sam", "$name: Whose is it?")
        until { compose.onAllNodes(hasContentDescription(EventDetailChoiceRules.whoseRow("Sam").spoken)).fetchSemanticsNodes().isNotEmpty() }
        if (compose.onAllNodes(hasContentDescription(EventDetailChoiceRules.whoseRow("Sam").spoken)).fetchSemanticsNodes().isEmpty()) {
            fail("$name: the row doesn't say Sam")
        }
    }
}
