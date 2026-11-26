package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
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
import os.meka.android.work.AT_WORK_NOTE
import os.meka.android.work.COMMUTE_TO_WORK_TAG
import os.meka.android.work.CALL_ASSISTANT_TAG
import os.meka.android.work.WHATSAPP_CALLS_TAG
import os.meka.android.work.LEAVING_TO_TAG
import os.meka.android.work.MESSAGES_MARK_READ_TAG
import os.meka.android.work.DISCORD_CHANNEL_TAG
import os.meka.core.domain.DiscordCaptured
import os.meka.core.domain.DiscordChannel
import os.meka.core.domain.DiscordChoiceRules
import os.meka.core.domain.DiscordRules
import os.meka.android.notify.NOTIFY_CHOICE_SHEET_TAG
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import os.meka.android.work.HOURS_OFF_LINE
import os.meka.android.work.HOURS_ON_LINE
import os.meka.android.work.WORK_COLUMN_MAX
import os.meka.android.work.WorkPane
import os.meka.android.work.callAssistantNote
import os.meka.core.domain.CallScreeningRules
import os.meka.core.domain.CommuteChoiceRules
import os.meka.core.domain.CallChoiceRules
import os.meka.core.domain.WhatsAppCallRules
import os.meka.core.domain.LeavingChoiceRules
import os.meka.core.domain.MessagesChoiceRules
import os.meka.core.domain.MessagesSetupRules
import os.meka.core.domain.GroupMode
import os.meka.core.domain.LoneWordGlue
import os.meka.core.domain.PeopleLists
import os.meka.core.domain.ScreenUnknownRules
import os.meka.core.domain.WorkSchedule
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random
import kotlin.test.fail

/**
 * The quality gate, slice 37 (build plan: "the remaining screens' shots", Work mode next): Ask → More → Work mode with
 * nothing set (Navilife's default hours, empty lists, also shot below the fold), one set of hours (Mon–Fri, a commute, one family member) and a full set (four work days with Thursday
 * short, at work, the call assistant on and screening unknown callers all the time, Leaving now on, a blocked number,
 * two family members (one without a number) and one always-notify), each on the Fold's cover and inner screens, Dark
 * (and the full one in Light). Saved under `work/` and read for nonsense, cut lines, broken words, split dates, times
 * and numbers, and Work mode's own lines leaving a word alone or starting a line with a dot. By hand: Stop work now
 * stops work and offers Back to my hours; a day's chip adds that day to the hours; the commute's To work row opens its
 * sheet and a time there sets it.
 * Invented data only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class WorkScreensTest {
    /** Outermost: a hang fails within minutes with every thread's stack, instead of holding CI to its limit. */
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private companion object { const val SETTLE_MS = 3_000L }

    private enum class Fill { EMPTY, ONE, MANY }

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(37), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private suspend fun fill(core: MekaCore, fill: Fill): PeopleLists {
        if (fill == Fill.EMPTY) return PeopleLists()
        if (fill == Fill.ONE) {
            core.setWorkSchedule(listOf(1, 2, 3, 4, 5), 9 * 60, 17 * 60 + 30, true)
            core.setCommute(60, 60)
            return PeopleLists(family = setOf("Morgan")).withNumbers("Morgan", listOf("07700 900123"))
        }
        core.setWorkSchedule(listOf(1, 2, 3, 4), 9 * 60, 17 * 60 + 30, true)
        core.setWorkDayHours(4, 9 * 60, 15 * 60 + 30)
        core.setCallAssistant(true)
        core.setScreenUnknownAlways(true)
        core.setCommute(75, 80)
        core.setLeavingNow(true, "Morgan", 45)
        check(core.blockCaller("01632 960691", "Scam call claiming to be the police"))
        return PeopleLists(family = setOf("Morgan", "Sam Okonkwo-Lee"), alwaysNotify = setOf("Riley"))
            .withNumbers("Morgan", listOf("07700 900123"))
            .withNumbers("Riley", listOf("07700 900456"))
    }

    /** Runs the main looper and the frames by turns (a tap's change reaches the recomposer through the looper). */
    private fun turns(ms: Long = SETTLE_MS) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    private fun work(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val core = core()
        context.useCoreForScreens(core)
        val lists = runBlocking {
            core.tick()
            fill(core, fill).also { core.tick() }
        }
        // The lists stay on the phone, sealed with the device's keystore, which Robolectric hasn't got: the list is
        // set before the save that can't seal it.
        runCatching { context.captures.setLists(lists) }
        // Messages' people and groups (walk-through week 5, A17): a group switched to Ignore and a person kept from the
        // AI show whether or not they wrote lately (the save can't seal here, the choice still stands).
        if (fill == Fill.MANY) {
            runCatching { context.captures.setGroupMode("Football parents", GroupMode.IGNORE) }
            runCatching { context.captures.setNeverToAi("Riley", true) }
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) { WorkPane(core, onClose = {}) }
            }
        }
        turns()
        compose.waitForIdle()
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("work", name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertDatesWhole(compose, name)
        ScreenShots.assertClocksWhole(compose, name)
        ScreenShots.assertNumbersWhole(compose, name)
        ScreenShots.assertShows(compose, name, "Work mode")
        val w = core.workMode.value
        // The call line as the Fold draws it whether Android has let Navilife screen calls or not.
        val callLine = listOf(true, false).map {
            CallScreeningRules.statusLine(w.callAssistant, w.atWork, it, w.callAssistantPaused, w.screenUnknownAlways)
        }
        // Work mode's own lines.
        val lines = listOf(
            w.line, AT_WORK_NOTE, callAssistantNote(), CallScreeningRules.testLine(),
            ScreenUnknownRules.line(w.screenUnknownAlways, w.callAssistant), ScreenUnknownRules.TRADE_OFF,
            if (w.schedule.enabled) HOURS_ON_LINE else HOURS_OFF_LINE,
            "Shown first after work. Their calls ring while the call assistant screens.",
            "Messages and missed calls from these people alert you straight away, and their calls ring.",
        )
        ScreenShots.assertShows(compose, name, LoneWordGlue.kept(w.line))
        ScreenShots.assertNoLoneWords(compose, name, lines + callLine)
        ScreenShots.assertNoDotStartingALine(compose, name, lines + callLine)
        ScreenShots.assertEachOneLine(compose, name, listOf("Stop work now", "Start work now", "Usual start", "Usual end"))
        ScreenShots.assertReadable(compose, name, emptyList(), mustShow = false)
        // Walk-through A32: every tappable line, row and pill is a whole 48 dp tall target (Back to my hours, the hours
        // line, a day's row, Add from contacts, Remove, …); the day circles are 44 wide so the week fits the cover.
        ScreenShots.assertWholeTargets(compose, name, narrowOk = os.meka.core.domain.LocalClock.DAY_SHORT.toSet())
        // Walk-through A27: the hours' + ends on the column's edge, where Stop / Start work now ends.
        ScreenShots.assertSteppersEndOnTheEdge(compose, name, "work", if (w.atWork) "Stop work now" else "Start work now")
        // Unfolded, the column keeps to Fasting's 600 dp, centred, so Stop work now and the steppers don't sit at the far edge.
        val pane = compose.onNodeWithTag("work-pane").fetchSemanticsNode().boundsInRoot
        val screen = compose.onNodeWithTag("screen").fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        val paneDp = pane.width / density
        if (paneDp > WORK_COLUMN_MAX.value + 0.5f) fail("$name: Work mode's column is ${paneDp.toInt()} dp wide (max ${WORK_COLUMN_MAX.value.toInt()})")
        val leftGap = pane.left - screen.left
        val rightGap = screen.right - pane.right
        if (kotlin.math.abs(leftGap - rightGap) > density * 2) fail("$name: Work mode's column isn't centred (${leftGap / density} dp left, ${rightGap / density} dp right)")
        // Walk-through week 5, A17: the commute's directions are card rows saying the time in use ("To work" · "1 h 20",
        // "About 1 h 20" while it's the guess), not chip rows (A32's "one lit" now lives in the sheet, CommuteChoicesTest).
        val c = w.commute
        for (toWork in listOf(true, false)) {
            val r = CommuteChoiceRules.row(c, toWork)
            if (compose.onAllNodes(hasContentDescription(r.spoken)).fetchSemanticsNodes().isEmpty()) fail("$name: no commute \"${r.title}\" row saying ${r.value}")
        }
        if (compose.onAllNodes(hasContentDescription("Commute to work:", substring = true)).fetchSemanticsNodes().isNotEmpty()) {
            fail("$name: the commute still has a chip row")
        }
        // Walk-through week 5, A17: the call assistant's pill and Screen unknown callers' switch are card rows saying On/Off.
        for (r in listOf(CallChoiceRules.assistantRow(w.callAssistant), CallChoiceRules.unknownRow(w.screenUnknownAlways))) {
            if (compose.onAllNodes(hasContentDescription(r.spoken)).fetchSemanticsNodes().isEmpty()) fail("$name: no call assistant \"${r.title}\" row saying ${r.value}")
        }
        if (compose.onAllNodes(hasText("Turn the call assistant", substring = true)).fetchSemanticsNodes().isNotEmpty()) {
            fail("$name: the call assistant still has its Turn on / off pill")
        }
        // Walk-through week 5, A17: WhatsApp calls' switch is a card row saying On/Off.
        val waOn = ApplicationProvider.getApplicationContext<ShotApplication>().whatsAppCalls.settings.value.on
        if (compose.onAllNodes(hasContentDescription(CallChoiceRules.whatsAppRow(waOn).spoken)).fetchSemanticsNodes().isEmpty()) {
            fail("$name: no WhatsApp calls row saying ${CallChoiceRules.whatsAppRow(waOn).value}")
        }
        if (compose.onAllNodes(hasContentDescription(WhatsAppCallRules.SWITCH)).fetchSemanticsNodes().isNotEmpty()) fail("$name: WhatsApp calls still has a switch")
        // Walk-through week 5, A17: Leaving now's On/Off pill, To chips and When chips are card rows saying what's chosen.
        val lv = core.leavingView.value
        val leavingRows = listOf(LeavingChoiceRules.switchRow(lv)) +
            if (lv.on) listOf(LeavingChoiceRules.toRow(lv, lists), LeavingChoiceRules.whenRow(lv)) else emptyList()
        for (r in leavingRows) {
            if (compose.onAllNodes(hasContentDescription(r.spoken)).fetchSemanticsNodes().isEmpty()) fail("$name: no Leaving now \"${r.title}\" row saying ${r.value}")
        }
        if (compose.onAllNodes(hasContentDescription("Send to ", substring = true)).fetchSemanticsNodes().isNotEmpty()) {
            fail("$name: Leaving now still has a row of name chips")
        }
        // Walk-through week 5, A17: Messages' Mark read pill, people's and groups' pills and mode chips are card rows.
        val captures = ApplicationProvider.getApplicationContext<ShotApplication>().captures
        val ts = captures.triageSettings.value
        val ml = MessagesSetupRules.rows(captures.items.value + captures.digest.value, ts, System.currentTimeMillis())
        val messageRows = listOf(MessagesChoiceRules.markReadRow(ts.markReadOnDone)) + ml.people.map(MessagesChoiceRules::personRow) +
            ml.groups.flatMap { listOf(MessagesChoiceRules.groupRow(it), MessagesChoiceRules.groupAiRow(it)) }
        for (r in messageRows) {
            if (compose.onAllNodes(hasContentDescription(r.spoken)).fetchSemanticsNodes().isEmpty()) fail("$name: no Messages \"${r.title}\" row saying ${r.value}")
        }
        if (fill == Fill.MANY && (ml.people.none { it.name == "Riley" && it.private } || ml.groups.none { it.mode == GroupMode.IGNORE })) {
            fail("$name: Messages doesn't list Riley kept from the AI and the ignored group (${ml})")
        }
        if (compose.onAllNodes(hasContentDescription(" · kept from Navilife's AI", substring = true) or hasContentDescription(" · back to Navilife's AI", substring = true))
                .fetchSemanticsNodes().isNotEmpty()) fail("$name: Messages still has a Keep from AI pill")
        if (compose.onAllNodes(hasContentDescription(": Digest", substring = true)).fetchSemanticsNodes().isNotEmpty()) {
            fail("$name: Messages still has a group's chip row")
        }
        when (fill) {
            Fill.EMPTY -> {
                ScreenShots.assertShows(compose, name, "Add from contacts")
                // Walk-through 2026-11-08 A23: a fresh Fold comes with Navilife's own hours (WorkSchedule.DEFAULT, so at
                // work at 17:15 on a Wednesday), so the top of the empty page reads like a set-up one; what's empty is
                // further down (nobody on the lists, the call assistant off). The empty state is shot there too.
                if (w.schedule != WorkSchedule.DEFAULT) fail("$name: a fresh Fold's hours aren't Navilife's default (${w.schedule})")
                if (w.callAssistant) fail("$name: the call assistant is on with nothing set")
                ScreenShots.shootBelow(compose, "work", name, "Add from contacts", settle = { turns(1_000) })
            }
            Fill.ONE -> {
                if (!w.atWork) fail("$name: not at work at 17:15 on a Wednesday inside 09:00–17:30 (${w.line})")
                ScreenShots.assertShows(compose, name, "Stop work now")
                ScreenShots.assertShows(compose, name, "Morgan")
            }
            Fill.MANY -> {
                if (!w.callAssistant || !w.screenUnknownAlways) fail("$name: the call assistant isn't on and screening ($w)")
                ScreenShots.assertShows(compose, name, LoneWordGlue.kept(ScreenUnknownRules.line(true, true)))
                ScreenShots.assertShows(compose, name, "Sam Okonkwo-Lee")
                ScreenShots.assertShows(compose, name, "Riley")
            }
        }
        return core
    }

    /** Works by hand: Stop work now stops work (Back to my hours appears); Saturday's chip adds Saturday to the hours. */
    private fun byHand(core: MekaCore) {
        val stop = compose.onAllNodes(hasText("Stop work now") and hasClickAction())
        if (stop.fetchSemanticsNodes().isEmpty()) fail("Work: no Stop work now at work")
        stop[0].performSemanticsAction(SemanticsActions.OnClick)
        var i = 0
        while (core.workMode.value.atWork && i++ < 40) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        turns()
        if (core.workMode.value.atWork) fail("Work: Stop work now left work on")
        ScreenShots.awaitShows(compose, "work-by-hand", "Start work now")
        ScreenShots.assertShows(compose, "work-by-hand", "Back to my hours")
        val sat = compose.onAllNodes(hasContentDescription("Sat") and hasClickAction())
        if (sat.fetchSemanticsNodes().isEmpty()) fail("Work: no Saturday chip")
        sat[0].performSemanticsAction(SemanticsActions.OnClick)
        repeat(40) {
            if (6 in core.workMode.value.schedule.days) return@repeat
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        if (6 !in core.workMode.value.schedule.days) fail("Work: Saturday's chip didn't add Saturday (${core.workMode.value.schedule.days})")
        // Walk-through week 5, A17: the commute's To work row opens its sheet; 1 h 30 there sets it and closes the sheet.
        fun sheetOpen() = compose.onAllNodes(hasTestTag(NOTIFY_CHOICE_SHEET_TAG)).fetchSemanticsNodes().isNotEmpty()
        val toWork = compose.onAllNodes(hasTestTag(COMMUTE_TO_WORK_TAG) and hasClickAction())
        if (toWork.fetchSemanticsNodes().isEmpty()) fail("Work: no commute To work row")
        toWork[0].performSemanticsAction(SemanticsActions.OnClick)
        turns(1_000)
        if (!sheetOpen()) fail("Work: the commute's To work row didn't open its sheet")
        compose.onAllNodes(hasText("1 h 30", substring = false) and hasClickAction() and hasAnyAncestor(hasTestTag(NOTIFY_CHOICE_SHEET_TAG)))[0]
            .performSemanticsAction(SemanticsActions.OnClick)
        repeat(40) {
            if (core.workMode.value.commute.toWorkMin == 90) return@repeat
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        turns(1_000)
        if (core.workMode.value.commute.toWorkMin != 90) fail("Work: 1 h 30 in the To work sheet didn't set the commute (${core.workMode.value.commute})")
        if (sheetOpen()) fail("Work: choosing 1 h 30 didn't close the commute's sheet")
        ScreenShots.awaitShows(compose, "work-by-hand", "1 h 30 there · 1 h home")
        // Walk-through week 5, A17: Leaving now's To row opens the names; Morgan there sends the draft to Morgan.
        val leavingTo = compose.onAllNodes(hasTestTag(LEAVING_TO_TAG) and hasClickAction())
        if (leavingTo.fetchSemanticsNodes().isEmpty()) fail("Work: no Leaving now To row")
        leavingTo[0].performSemanticsAction(SemanticsActions.OnClick)
        turns(1_000)
        if (!sheetOpen()) fail("Work: Leaving now's To row didn't open its sheet")
        compose.onAllNodes(hasText("Morgan", substring = false) and hasClickAction() and hasAnyAncestor(hasTestTag(NOTIFY_CHOICE_SHEET_TAG)))[0]
            .performSemanticsAction(SemanticsActions.OnClick)
        repeat(40) {
            if (core.leavingView.value.to == "Morgan") return@repeat
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        turns(1_000)
        if (core.leavingView.value.to != "Morgan") fail("Work: Morgan in Leaving now's To sheet didn't set who it goes to (${core.leavingView.value})")
        if (sheetOpen()) fail("Work: choosing Morgan didn't close Leaving now's sheet")
        // Walk-through week 5, A17: Messages' Mark read row opens On · Off; On there marks chats read on Done.
        val captures = ApplicationProvider.getApplicationContext<ShotApplication>().captures
        val markRead = compose.onAllNodes(hasTestTag(MESSAGES_MARK_READ_TAG) and hasClickAction())
        if (markRead.fetchSemanticsNodes().isEmpty()) fail("Work: no Messages Mark read row")
        markRead[0].performSemanticsAction(SemanticsActions.OnClick)
        turns(1_000)
        if (!sheetOpen()) fail("Work: Messages' Mark read row didn't open its sheet")
        compose.onAllNodes(hasText("On", substring = false) and hasClickAction() and hasAnyAncestor(hasTestTag(NOTIFY_CHOICE_SHEET_TAG)))[0]
            .performSemanticsAction(SemanticsActions.OnClick)
        repeat(40) {
            if (captures.triageSettings.value.markReadOnDone) return@repeat
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        turns(1_000)
        if (!captures.triageSettings.value.markReadOnDone) fail("Work: On in Mark read's sheet didn't switch it on")
        if (sheetOpen()) fail("Work: choosing On didn't close Mark read's sheet")
        if (compose.onAllNodes(hasContentDescription(MessagesChoiceRules.markReadRow(true).spoken)).fetchSemanticsNodes().isEmpty()) {
            fail("Work: Mark read's row doesn't say On after choosing it")
        }
        // Walk-through week 5, A17: Discord's channels and alerts are rows opening sheets. A channel Discord notified about
        // is a row ("#signals · Example Traders" · "Not followed"); Following in its sheet follows it, and then a post
        // from Sam there makes a Posting lately row whose Alerts at once puts Sam on the list.
        fun await(what: () -> Boolean) {
            repeat(40) {
                if (what()) return@repeat
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(50)
                compose.mainClock.advanceTimeBy(100)
            }
            turns(1_000)
        }
        fun pick(label: String) = compose.onAllNodes(hasText(label, substring = false) and hasClickAction() and hasAnyAncestor(hasTestTag(NOTIFY_CHOICE_SHEET_TAG)))[0]
            .performSemanticsAction(SemanticsActions.OnClick)
        val signals = DiscordChannel(DiscordRules.key("Example Traders", "signals"), "Example Traders", "signals")
        runBlocking { core.noteDiscord(listOf(DiscordCaptured(signals, "Alex", "Morning all", now - 60_000))) }
        await { core.discordWatch.value.rows.isNotEmpty() }
        val channelRow = core.discordWatch.value.rows.firstOrNull() ?: fail("Work: Discord didn't list the channel it was told about")
        if (compose.onAllNodes(hasContentDescription(DiscordChoiceRules.channelRow(channelRow).spoken)).fetchSemanticsNodes().isEmpty()) {
            fail("Work: no Discord row for ${channelRow.label} saying Not followed")
        }
        if (compose.onAllNodes(hasContentDescription("Follow ${channelRow.label}")).fetchSemanticsNodes().isNotEmpty()) fail("Work: Discord still has a Follow pill")
        compose.onAllNodes(hasTestTag(DISCORD_CHANNEL_TAG) and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
        turns(1_000)
        if (!sheetOpen()) fail("Work: Discord's channel row didn't open its sheet")
        pick(DiscordChoiceRules.FOLLOWING_LABEL)
        await { core.discordWatch.value.rows.firstOrNull()?.watched == true }
        if (core.discordWatch.value.rows.firstOrNull()?.watched != true) fail("Work: Following in the channel's sheet didn't follow it (${core.discordWatch.value})")
        if (sheetOpen()) fail("Work: choosing Following didn't close the channel's sheet")
        runBlocking { core.noteDiscord(listOf(DiscordCaptured(signals, "Sam", "Entry at the open", now - 30_000))) }
        await { "Sam" in core.discordWatch.value.suggestedPeople }
        val samRow = DiscordChoiceRules.alertRow("Sam", person = true, alerting = false)
        val sam = compose.onAllNodes(hasContentDescription(samRow.spoken) and hasClickAction())
        if (sam.fetchSemanticsNodes().isEmpty()) fail("Work: no Posting lately row for Sam (${core.discordWatch.value.suggestedPeople})")
        sam[0].performSemanticsAction(SemanticsActions.OnClick)
        turns(1_000)
        if (!sheetOpen()) fail("Work: Sam's row didn't open its sheet")
        pick(DiscordChoiceRules.ALERT_LABEL)
        await { "Sam" in core.discordWatch.value.people }
        if ("Sam" !in core.discordWatch.value.people) fail("Work: Alerts at once in Sam's sheet didn't put Sam on the list (${core.discordWatch.value})")
        if (compose.onAllNodes(hasContentDescription(DiscordChoiceRules.alertRow("Sam", person = true, alerting = true).spoken)).fetchSemanticsNodes().isEmpty()) {
            fail("Work: Sam's row doesn't say Alerts at once after choosing it")
        }
        // Walk-through week 5, A17: the call assistant's row opens On · Off; the other one there switches it, and back.
        for (step in 0..1) {
            val was = core.workMode.value.callAssistant
            val assistant = compose.onAllNodes(hasTestTag(CALL_ASSISTANT_TAG) and hasClickAction())
            if (assistant.fetchSemanticsNodes().isEmpty()) fail("Work: no call assistant row")
            assistant[0].performSemanticsAction(SemanticsActions.OnClick)
            turns(1_000)
            if (!sheetOpen()) fail("Work: the call assistant's row didn't open its sheet")
            pick(if (was) CallChoiceRules.OFF_LABEL else CallChoiceRules.ON_LABEL)
            await { core.workMode.value.callAssistant != was }
            if (core.workMode.value.callAssistant == was) fail("Work: ${if (was) "Off" else "On"} in the call assistant's sheet didn't switch it (step $step)")
            if (sheetOpen()) fail("Work: choosing in the call assistant's sheet didn't close it")
            if (compose.onAllNodes(hasContentDescription(CallChoiceRules.assistantRow(!was).spoken)).fetchSemanticsNodes().isEmpty()) {
                fail("Work: the call assistant's row doesn't say what was chosen")
            }
        }
        // Walk-through week 5, A17: WhatsApp calls' row opens On · Off; On turns it on (his yes), Off back off again.
        val wa = ApplicationProvider.getApplicationContext<ShotApplication>().whatsAppCalls
        for (step in 0..1) {
            val was = wa.settings.value.on
            val row = compose.onAllNodes(hasTestTag(WHATSAPP_CALLS_TAG) and hasClickAction())
            if (row.fetchSemanticsNodes().isEmpty()) fail("Work: no WhatsApp calls row")
            row[0].performSemanticsAction(SemanticsActions.OnClick)
            turns(1_000)
            if (!sheetOpen()) fail("Work: WhatsApp calls' row didn't open its sheet")
            pick(if (was) CallChoiceRules.OFF_LABEL else CallChoiceRules.ON_LABEL)
            await { wa.settings.value.on != was }
            if (wa.settings.value.on == was) fail("Work: ${if (was) "Off" else "On"} in WhatsApp calls' sheet didn't switch it (step $step)")
            if (sheetOpen()) fail("Work: choosing in WhatsApp calls' sheet didn't close it")
            if (compose.onAllNodes(hasContentDescription(CallChoiceRules.whatsAppRow(!was).spoken)).fetchSemanticsNodes().isEmpty()) {
                fail("Work: WhatsApp calls' row doesn't say what was chosen")
            }
        }
        // Walk-through 2026-11-15 A12 ("work inner by-hand = inner empty"): the shot was the opening screen, at work on
        // Navilife's hours like the empty page; it is taken again after the hand: work stopped (Start work now, Back to
        // my hours) and Saturday lit.
        ScreenShots.reshootAfterHand(compose, "work", "inner-dark-by-hand")
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun coverEmpty() { work("cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverOne() { work("cover-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverMany() { work("cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverManyLight() { work("cover-light-many", Fill.MANY, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerEmpty() { work("inner-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerMany() { work("inner-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun byHand() { byHand(work("inner-dark-by-hand", Fill.ONE)) }
}
