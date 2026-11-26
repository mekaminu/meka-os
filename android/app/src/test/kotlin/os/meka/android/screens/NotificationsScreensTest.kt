package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import os.meka.android.notify.NOTIFY_CHOICE_ROW_TAG
import os.meka.android.notify.NOTIFY_CHOICE_SHEET_TAG
import os.meka.android.notify.NotificationsPane
import os.meka.core.domain.DateGlue
import os.meka.core.domain.DeviceAlerts
import os.meka.core.domain.NoticeSource
import os.meka.core.domain.NoticeTier
import os.meka.core.domain.NotificationSettings
import os.meka.core.domain.NotifyRules
import os.meka.core.domain.CallChoiceRules
import os.meka.core.domain.NotifyQuietChoiceRules
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random
import kotlin.test.fail

/**
 * The quality gate, slice 57 (build plan: "the rest of Ask's places' shots", Notifications): Ask → More →
 * Notifications with nothing set (no quiet hours, no digests), as it comes (quiet 22:00–07:00, both digests) and set
 * up (quiet hours moved, three kinds of notice lowered), each on the Fold's cover and inner screens, Dark (and the
 * set-up one in Light). Saved under `notifications/` and read for nonsense, cut lines, broken words, split times and
 * numbers, the pane's lines leaving a word alone or starting a line with a dot, every chip whole on one line and inside
 * the pane (they used to scroll off the cover screen's edge), and every chip, switch and − / + answering a 48 dp touch.
 * By hand: quiet hours on, Until 15 minutes later, the midday digest off, a kind of notice moved to Digest from its
 * row's sheet, and This phone to Digests only and back (walk-through week 5, A17: rows opening sheets, no open chips).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class NotificationsScreensTest {
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
        secureRandom = Random(57), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private suspend fun fill(core: MekaCore, fill: Fill) {
        when (fill) {
            Fill.EMPTY -> {
                core.setQuietHours(false, 22 * 60, 7 * 60)
                core.setDigest(NotificationSettings.MIDDAY, false)
                core.setDigest(NotificationSettings.EVENING, false)
            }
            // Walk-through 2026-11-08 A23: one and many each have things waiting for the evening digest, so the line under
            // the title says what the state holds ("1 thing so far", "3 things so far" or more); both used to read "nothing yet".
            Fill.ONE -> core.addPromise("Send Sam the photos", "Sam", 0)
            Fill.MANY -> {
                core.addPromise("Send Sam the photos", "Sam", 0)
                core.addWaiting("Quote for the boiler", "Riley", 0)
                core.addPromise("Return the drill", "Alex", 0)
                core.setQuietHours(true, 21 * 60 + 30, 6 * 60 + 45)
                core.setNoticeTier(NoticeSource.BRIEF, NoticeTier.DIGEST)
                core.setNoticeTier(NoticeSource.WEEKLY_REVIEW, NoticeTier.SILENT)
                core.setNoticeTier(NoticeSource.BINS, NoticeTier.DIGEST)
            }
        }
    }

    /** Runs the main looper and the frames by turns (a tap's change reaches the recomposer through the looper). */
    private fun turns(ms: Long = SETTLE_MS) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    /** Waits (core thread, looper and frames by turns) until [done] holds, then lets the screen settle. */
    private fun until(done: () -> Boolean) {
        repeat(40) {
            if (done()) return@repeat
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        turns()
    }

    /** [text] is on screen as the pane draws it (glued words read plain). */
    private fun onScreen(text: String): Boolean = ScreenShots.texts(compose).any { DateGlue.plain(it).contains(text) }

    /** Every button, chip and switch drawn answers a 48 dp touch (the chips were ~32 dp, the switch rows ~36). */
    private fun assertTouchTargets(name: String) {
        val roles = listOf(Role.Button, Role.RadioButton, Role.Switch)
        val small = roles.flatMap { role ->
            compose.onAllNodes(hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.Role, role)).fetchSemanticsNodes()
        }
            .filter { it.size.height / compose.density.density < 47.5f }
            .map { n ->
                val label = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
                    ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: "?"
                "\"$label\" ${(n.size.width / compose.density.density).toInt()}×${(n.size.height / compose.density.density).toInt()} dp"
            }
        if (small.isNotEmpty()) fail("$name: buttons under 48 dp to tap:\n" + small.joinToString("\n") { "- $it" })
    }

    /** Every chip drawn sits inside the pane's width (they wrap; on the cover screen they scrolled off the edge). */
    private fun assertChipsInView(name: String, labels: Set<String>) {
        val pane = compose.onNodeWithTag("notifications-pane").fetchSemanticsNode().boundsInRoot
        labels.forEach { label ->
            compose.onAllNodes(hasText(label) and hasClickAction()).fetchSemanticsNodes().forEach { n ->
                val right = n.positionInRoot.x + n.size.width
                if (n.positionInRoot.x < pane.left - 1f || right > pane.right + 1f) {
                    fail("$name: \"$label\" sits outside the pane (${n.positionInRoot.x / compose.density.density}–${right / compose.density.density} dp)")
                }
            }
        }
    }

    private fun pane(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        context.governor.setDevice(DeviceAlerts.ALL)
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
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) { NotificationsPane(core, onClose = {}) }
            }
        }
        turns()
        compose.waitForIdle()
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("notifications", name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertDatesWhole(compose, name)
        ScreenShots.assertClocksWhole(compose, name)
        ScreenShots.assertNumbersWhole(compose, name)
        ScreenShots.assertShows(compose, name, "Notifications")
        val p = core.notificationPreview.value
        val s = core.notificationSettings.value
        val lines = listOfNotNull(p.quietLine, p.digestLine, p.explainLine.ifEmpty { null }, NotifyRules.DIGEST_NOTE, NotifyRules.TIER_NOTE) +
            (if (s.quiet.enabled) listOf(NotifyRules.quietNote(s.digestsOn)) else emptyList())
        lines.forEach { if (!onScreen(it)) fail("$name: \"$it\" isn't on screen") }
        // Found and fixing week 5 (A8): the page agrees with Health and with itself.
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        NotifyRules.phoneOffLine(os.meka.android.work.WorkAlerts.canPost(ctx), false)?.let {
            if (!onScreen(it)) fail("$name: notifications are off for Navilife (Health says nothing can reach you) but the page doesn't say so")
        }
        if (!s.quiet.enabled && p.explainLine.contains("quiet hours")) fail("$name: \"${p.explainLine}\" over No quiet hours")
        if (s.quiet.enabled && p.explainLine.contains("always come at once") && p.explainLine.contains("Decisions and urgent"))
            fail("$name: \"${p.explainLine}\" says decisions always come at once, but quiet hours hold them")
        if (s.quiet.enabled && !s.digestsOn && onScreen("wait for the next digest")) fail("$name: quiet hours promise the next digest with both digests off")
        ScreenShots.assertNoLoneWords(compose, name, lines)
        ScreenShots.assertNoDotStartingALine(compose, name, lines)
        val chips = DeviceAlerts.entries.map { it.label }.toSet() + NoticeSource.CHOICES.map { it.label }
        ScreenShots.assertEachOneLine(compose, name, chips)
        assertChipsInView(name, chips)
        assertTouchTargets(name)
        // Walk-through week 5, A17: no open chip row on the page; This phone and every kind of notice are a row saying
        // what's chosen.
        val radios = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).fetchSemanticsNodes()
        if (radios.isNotEmpty()) fail("$name: ${radios.size} open choice chips on the page; they belong in a sheet")
        val rows = compose.onAllNodesWithTag(NOTIFY_CHOICE_ROW_TAG).fetchSemanticsNodes()
        // Quiet hours and the two digests too: no switch rows left on the page.
        if (rows.size != 4 + NoticeSource.ADJUSTABLE.size) fail("$name: ${rows.size} choice rows, not This phone, quiet hours, two digests and ${NoticeSource.ADJUSTABLE.size} kinds")
        val switches = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).fetchSemanticsNodes()
        if (switches.isNotEmpty()) fail("$name: ${switches.size} open switches on the page; they belong in a sheet")
        val onOff = listOf(NotifyQuietChoiceRules.quietRow(s.quiet.enabled, s.quiet.summary)) +
            listOf(NotificationSettings.MIDDAY, NotificationSettings.EVENING).map { NotifyQuietChoiceRules.digestRow(it, s.hasDigest(it)) }
        (listOf(NotifyRules.deviceRow(DeviceAlerts.ALL, "This phone")) + onOff + NoticeSource.ADJUSTABLE.map { NotifyRules.tierRow(s, it) }).forEach { r ->
            if (compose.onAllNodes(hasContentDescription(r.spoken) and hasClickAction()).fetchSemanticsNodes().isEmpty())
                fail("$name: no row reading \"${r.title} · ${r.value}\"")
        }
        if (s.quiet.enabled) ScreenShots.assertSteppersEndOnTheEdge(compose, name, "notify", "Send a test")
        ScreenShots.assertReadable(compose, name, emptyList(), mustShow = false)
        when (fill) {
            Fill.EMPTY -> {
                if (s.quiet.enabled || s.digestMinutes.isNotEmpty()) fail("$name: something is set ($s)")
                ScreenShots.assertShows(compose, name, NotifyRules.QUIET_OFF)
                if (!onScreen("No digests")) fail("$name: doesn't say there are no digests")
            }
            Fill.ONE -> {
                if (!onScreen("Quiet hours 22:00–07:00")) fail("$name: the quiet hours line reads \"${p.quietLine}\"")
                if (!onScreen("Next digest 18:00 · 1 thing so far")) fail("$name: the digest line reads \"${p.digestLine}\"")
            }
            Fill.MANY -> {
                if (!onScreen("21:30–06:45")) fail("$name: quiet hours don't read 21:30–06:45")
                if (onScreen("On · 21:30–06:45")) fail("$name: the quiet hours row says On beside its own On pill")
                if (s.tierFor(NoticeSource.WEEKLY_REVIEW) != NoticeTier.SILENT) fail("$name: the weekly review wasn't lowered ($s)")
                if (!p.digestLine.endsWith("things so far") || !onScreen(p.digestLine)) fail("$name: the digest line reads \"${p.digestLine}\", not the things waiting")
            }
        }
        return core
    }

    /** Works by hand: quiet hours on; Until 15 minutes later; the midday digest off; Morning brief moved to Digest. */
    private fun byHand(core: MekaCore) {
        val name = "notifications-by-hand"
        fun tap(desc: String) {
            val n = compose.onAllNodes(hasContentDescription(desc) and hasClickAction())
            if (n.fetchSemanticsNodes().isEmpty()) fail("$name: no \"$desc\" to tap")
            n[0].performSemanticsAction(SemanticsActions.OnClick)
        }
        // Walk-through week 5, A17: quiet hours' row opens On · Off; On saves it and closes the sheet.
        val q = core.notificationSettings.value.quiet
        tap(NotifyQuietChoiceRules.quietRow(q.enabled, q.summary).spoken)
        until { compose.onAllNodesWithTag(NOTIFY_CHOICE_SHEET_TAG).fetchSemanticsNodes().isNotEmpty() }
        if (compose.onAllNodesWithTag(NOTIFY_CHOICE_SHEET_TAG).fetchSemanticsNodes().isEmpty()) fail("$name: quiet hours' row didn't open its sheet")
        tap(NotifyQuietChoiceRules.quietSheet(q.enabled, core.notificationSettings.value.digestsOn).choices.first { it.name == CallChoiceRules.ON }.spoken)
        until { core.notificationSettings.value.quiet.enabled && onScreen("22:00–07:00") && compose.onAllNodesWithTag(NOTIFY_CHOICE_SHEET_TAG).fetchSemanticsNodes().isEmpty() }
        if (!core.notificationSettings.value.quiet.enabled) fail("$name: On left quiet hours off")
        if (!onScreen(NotifyRules.quietNote(core.notificationSettings.value.digestsOn))) fail("$name: quiet hours on didn't unfold the times")

        tap("Until 15 minutes later")
        until { core.notificationSettings.value.quiet.endMinute == 7 * 60 + 15 && onScreen("22:00–07:15") }
        if (core.notificationSettings.value.quiet.endMinute != 7 * 60 + 15) fail("$name: + left Until at ${core.notificationSettings.value.quiet.endMinute}")
        if (!onScreen("22:00–07:15")) fail("$name: the switch row doesn't say the new time")

        // Digests are off in this fill: turn the midday one on, then off again.
        fun midday(on: Boolean) {
            val now = core.notificationSettings.value.hasDigest(NotificationSettings.MIDDAY)
            tap(NotifyQuietChoiceRules.digestRow(NotificationSettings.MIDDAY, now).spoken)
            until { compose.onAllNodesWithTag(NOTIFY_CHOICE_SHEET_TAG).fetchSemanticsNodes().isNotEmpty() }
            if (compose.onAllNodesWithTag(NOTIFY_CHOICE_SHEET_TAG).fetchSemanticsNodes().isEmpty()) fail("$name: the midday digest's row didn't open its sheet")
            val pick = if (on) CallChoiceRules.ON else CallChoiceRules.OFF
            tap(NotifyQuietChoiceRules.digestSheet(NotificationSettings.MIDDAY, now).choices.first { it.name == pick }.spoken)
        }
        midday(on = true)
        until { core.notificationSettings.value.hasDigest(NotificationSettings.MIDDAY) && onScreen("Next digest") }
        if (!core.notificationSettings.value.hasDigest(NotificationSettings.MIDDAY)) fail("$name: the midday digest didn't come on")
        midday(on = false)
        until { !core.notificationSettings.value.hasDigest(NotificationSettings.MIDDAY) && onScreen("No digests") }
        if (core.notificationSettings.value.hasDigest(NotificationSettings.MIDDAY)) fail("$name: the midday digest didn't go off")

        // The first kind listed: its row opens the sheet, Digest saves it and closes the sheet (A17: no open chips).
        val first = NoticeSource.ADJUSTABLE.first()
        tap(NotifyRules.tierRow(core.notificationSettings.value, first).spoken)
        until { compose.onAllNodesWithTag(NOTIFY_CHOICE_SHEET_TAG).fetchSemanticsNodes().isNotEmpty() }
        if (compose.onAllNodesWithTag(NOTIFY_CHOICE_SHEET_TAG).fetchSemanticsNodes().isEmpty()) fail("$name: ${first.label}'s row didn't open its sheet")
        val digest = NotifyRules.tierSheet(core.notificationSettings.value, first).choices.first { it.name == NoticeTier.DIGEST.name }
        tap(digest.spoken)
        until {
            core.notificationSettings.value.tierFor(first) == NoticeTier.DIGEST &&
                compose.onAllNodesWithTag(NOTIFY_CHOICE_SHEET_TAG).fetchSemanticsNodes().isEmpty()
        }
        if (core.notificationSettings.value.tierFor(first) != NoticeTier.DIGEST) fail("$name: Digest left ${first.label} at ${core.notificationSettings.value.tierFor(first)}")
        if (compose.onAllNodesWithTag(NOTIFY_CHOICE_SHEET_TAG).fetchSemanticsNodes().isNotEmpty()) fail("$name: the sheet stayed open after a choice")
        if (!onScreen("Digest")) fail("$name: ${first.label}'s row doesn't say Digest")

        // This phone: Digests only from its sheet, then back to Everything.
        val governor = ApplicationProvider.getApplicationContext<ShotApplication>().governor
        tap(NotifyRules.deviceRow(DeviceAlerts.ALL, "This phone").spoken)
        until { compose.onAllNodesWithTag(NOTIFY_CHOICE_SHEET_TAG).fetchSemanticsNodes().isNotEmpty() }
        tap(NotifyRules.deviceSheet(DeviceAlerts.ALL, "This phone").choices.first { it.name == DeviceAlerts.DIGESTS.name }.spoken)
        until { governor.device.value == DeviceAlerts.DIGESTS && compose.onAllNodesWithTag(NOTIFY_CHOICE_SHEET_TAG).fetchSemanticsNodes().isEmpty() }
        if (governor.device.value != DeviceAlerts.DIGESTS) fail("$name: This phone's sheet left it at ${governor.device.value}")
        tap(NotifyRules.deviceRow(DeviceAlerts.DIGESTS, "This phone").spoken)
        until { compose.onAllNodesWithTag(NOTIFY_CHOICE_SHEET_TAG).fetchSemanticsNodes().isNotEmpty() }
        tap(NotifyRules.deviceSheet(DeviceAlerts.DIGESTS, "This phone").choices.first { it.name == DeviceAlerts.ALL.name }.spoken)
        until { governor.device.value == DeviceAlerts.ALL && compose.onAllNodesWithTag(NOTIFY_CHOICE_SHEET_TAG).fetchSemanticsNodes().isEmpty() }
        if (governor.device.value != DeviceAlerts.ALL) fail("$name: Everything didn't come back")
        // Walk-through 2026-11-08 A20: the shot shows what the hand set (quiet hours on till 07:15, the first kind in Digest).
        ScreenShots.reshootAfterHand(compose, "notifications", "cover-dark-by-hand")
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun coverEmpty() { pane("cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverOne() { pane("cover-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverMany() { pane("cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverManyLight() { pane("cover-light-many", Fill.MANY, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerEmpty() { pane("inner-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerMany() { pane("inner-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun byHand() { byHand(pane("cover-dark-by-hand", Fill.EMPTY)) }
}
