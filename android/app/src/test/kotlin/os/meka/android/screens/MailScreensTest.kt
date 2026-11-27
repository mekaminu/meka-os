package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
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
import os.meka.android.mail.MailPane
import os.meka.android.notify.NOTIFY_CHOICE_ROW_TAG
import os.meka.android.notify.NOTIFY_CHOICE_SHEET_TAG
import os.meka.core.domain.FollowUpWaitsRules
import os.meka.core.domain.MailChoiceRules
import os.meka.core.domain.MailFollowUpRules
import os.meka.core.domain.MailMomentTime
import os.meka.core.domain.MailWarrantyLevel
import os.meka.core.domain.MeetingPrepLead
import os.meka.core.domain.LifeAuditReviewRules
import os.meka.core.domain.LoneWordGlue
import os.meka.core.domain.MailAccountFacts
import os.meka.core.domain.MailAccountRules
import os.meka.core.domain.MailLabel
import os.meka.core.domain.MailLabelChoiceRules
import os.meka.core.domain.MailAccountsView
import os.meka.core.domain.MailAutopilotPanelRules
import os.meka.core.domain.MailAutoDraftRules
import os.meka.core.domain.MailProviders
import os.meka.core.facade.AccountsApi
import os.meka.core.facade.ConnectStart
import os.meka.core.facade.ConnectedAccount
import os.meka.core.facade.MekaCore
import os.meka.core.domain.EntityTypes
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.Op
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import kotlin.random.Random
import kotlin.test.fail

/**
 * The quality gate, slice 31 (build plan: "the remaining screens' shots", Mail first): Ask → Settings → Mail when the
 * server can't be reached, with nothing connected, one Gmail sorted and tidied, and three accounts (one sorted, one whose
 * sign-in ended, one whose last check failed), each on the Fold's cover and inner screens, Dark (and the full one in
 * Light). Saved under `mail/` and read for nonsense, cut lines, broken words, split dates, times and numbers, a word left
 * alone and a line starting with a dot. Mail by hand: tap Autopilot's Turn off and Autopilot is off, its line saying so;
 * Meeting prep's row opens its sheet and 30 min, then Off, save and close it; a Chase waits row opens its sheet and 2
 * weeks, then Back to usual, save and close it (walk-through week 5, A17).
 * Talks to a stand-in server held in the test. Invented data only (rule 13): example.com addresses.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class MailScreensTest {
    /** Outermost: a hang fails within minutes with every thread's stack, instead of holding CI to its limit. */
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London
    private val minute = 60_000L
    private val day = 24 * 60 * minute

    private companion object { const val SETTLE_MS = 3_000L }

    private enum class Fill { OFFLINE, EMPTY, ONE, MANY }

    /** Invented people with their own chase waits (rule 13). */
    private val waits = listOf("Sam Taylor" to 7, "Alex Brown" to 1)

    /** Navilife's server, as far as Mail asks it: sync and the mail accounts (never a token). */
    private inner class Server(var mail: List<MailAccountFacts>) : SyncTransport, AccountsApi {
        val ops = InMemoryServerOpStore()
        val service = SyncService(ops)

        init {
            // Found and fixing week 5, A8: with mail connected, the phone holds the morning's seven emails to answer that
            // Needs you's Mail to answer shows ("1–5 of 7"), so the Life audit counts them instead of saying nothing needs him.
            if (mail.isNotEmpty()) {
                val clock = HlcClock("server", { now })
                var k = 0
                ShotMailToAnswer.serverItems(now).forEach { (id, fields) ->
                    fields.forEach { (f, v) -> ops.append(Op("srv${k++}", "hh", EntityTypes.MAIL_ITEM, id, f, v, clock.now(), emptyList(), "server")) }
                }
                // Walk-through week 5, A17: with three accounts, two people he set his own chase wait for, so Chase waits
                // has rows (each one card row opening its sheet, not a chip row).
                if (mail.size > 1) waits.forEach { (who, days) ->
                    MailFollowUpRules.waitFields(who, days, now - 3 * day).forEach { (f, v) ->
                        ops.append(Op("srv${k++}", "hh", EntityTypes.FOLLOW_UP_WAIT, MailFollowUpRules.waitId(who), f, v, clock.now(), emptyList(), "server"))
                    }
                }
            }
        }
        override suspend fun push(request: PushRequest) = service.push(request)
        override suspend fun pull(request: PullRequest) = service.pull(request)
        override suspend fun startConnect(provider: String, editing: Boolean): ConnectStart = ConnectStart.NotSetUp
        override suspend fun accounts(): List<ConnectedAccount> = emptyList()
        override suspend fun stopEditing(provider: String, email: String): List<ConnectedAccount> = emptyList()
        override suspend fun mailAccounts(): List<MailAccountFacts> = mail
        // A17: the Label row's sheet relabels an account as the server does.
        override suspend fun setMailLabel(provider: String, email: String, label: String): List<MailAccountFacts> {
            mail = mail.map { if (it.provider == provider && it.email == email) it.copy(label = label) else it }
            return mail
        }
    }

    private val personal = MailAccountFacts(
        MailProviders.GMAIL, "jordan@example.com", "ok", now - 4 * minute, "PERSONAL",
        sorted = mapOf("NEEDS_YOU" to 3, "FYI" to 8, "NEWSLETTER" to 30, "RECEIPT" to 4, "PROMO" to 12),
        wouldTidy = mapOf("NEWSLETTER" to 30, "RECEIPT" to 4, "ARCHIVE" to 12),
        inboxTotal = 1_240, inboxUnread = 86,
    )
    private val business = MailAccountFacts(MailProviders.GMAIL, "jordan.studio@example.com", "needs_reconnect", now - 2 * day, "BUSINESS")
    private val outlook = MailAccountFacts(MailProviders.OUTLOOK, "jordan.work@example.com", "error", now - 3 * 60 * minute, "OUTLOOK", why = "busy")

    private fun accounts(fill: Fill): List<MailAccountFacts> = when (fill) {
        Fill.OFFLINE, Fill.EMPTY -> emptyList()
        Fill.ONE -> listOf(personal)
        Fill.MANY -> listOf(personal, business, outlook)
    }

    private fun setUp(theme: ThemeChoice, fill: Fill): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val server = if (fill == Fill.OFFLINE) null else Server(accounts(fill))
        val core = MekaCore(
            householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = server,
            secureRandom = Random(31), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
        )
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            if (server != null && !core.syncNow()) fail("the stand-in server didn't sync")
            core.tick()
        }
        return core
    }

    private fun show(core: MekaCore) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) { MailPane(core, onClose = {}) }
            }
        }
    }

    /** Runs the main looper and the frames by turns (the pane reads the accounts on a lifecycle post). */
    private fun turns(ms: Long = SETTLE_MS) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    /** Every descriptive line Mail draws through [LoneWordGlue.kept]. */
    private fun lines(v: MailAccountsView): List<String> =
        listOf(MailAccountRules.HEADER) + listOfNotNull(v.emptyLine, v.toConnect, v.repliesLine) +
            v.rows.flatMap { listOfNotNull(it.line, it.sortedLine, it.tidyLine) } +
            listOfNotNull(v.autopilot?.shortLine, v.autopilot?.markReadShort, v.autopilot?.readerReadShort) +
            listOfNotNull(v.autopilot?.title, v.autopilot?.markReadTitle, v.autopilot?.readerReadTitle)

    /** Lines [text] takes as laid out (its first drawn node), or null when it isn't drawn. */
    private fun lineCount(text: String): Int? =
        compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.let { n ->
            val results = mutableListOf<TextLayoutResult>()
            n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            results.firstOrNull()?.lineCount
        }

    /**
     * Quality gate slice 32 (Mail's shots: Autopilot's 14-line paragraph beside its Turn off): every switch shows a
     * short line of at most three lines (titles keep their last words, see [lines]), and on the cover screen its button under the words.
     */
    private fun switchesShort(name: String, v: MailAccountsView, cover: Boolean) {
        val p = v.autopilot ?: return
        val r = MailAutopilotPanelRules
        // Each switch's short line in whichever state the screen drew (it may still be catching up with the sync).
        val switches = listOf(
            listOf(r.ON_SHORT, r.OFF_SHORT, r.PAUSED_SHORT) to listOf(r.ON_LINE, r.OFF_LINE, r.PAUSED_LINE),
            listOf(r.MARK_READ_ON_SHORT, r.MARK_READ_OFF_SHORT) to listOf(r.MARK_READ_ON_LINE, r.MARK_READ_OFF_LINE),
            listOf(r.READER_READ_ON_SHORT, r.READER_READ_OFF_SHORT) to listOf(r.READER_READ_ON_LINE, r.READER_READ_OFF_LINE),
        )
        val drawn = switches.map { (shorts, fulls) ->
            fulls.forEach {
                if (compose.onAllNodes(hasText(LoneWordGlue.kept(it)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
                    fail("$name: the full \"$it\" shows before How it works is tapped")
            }
            val shown = shorts.firstOrNull { lineCount(LoneWordGlue.kept(it)) != null }
                ?: fail("$name: none of ${shorts.map { "\"$it\"" }} is drawn (${p.shortLine}); on screen:\n" +
                    ScreenShots.texts(compose).filter { "·" in it }.joinToString("\n") { "- $it" })
            val lines = lineCount(LoneWordGlue.kept(shown)) ?: 0
            if (lines > 3) fail("$name: \"$shown\" takes $lines lines")
            shown
        }
        if (cover) {
            val line = compose.onAllNodes(hasText(LoneWordGlue.kept(drawn[0])), useUnmergedTree = true).fetchSemanticsNodes().first()
            val button = compose.onAllNodes(hasText(r.TURN_OFF) or hasText(r.TURN_ON), useUnmergedTree = true).fetchSemanticsNodes().first()
            if (button.boundsInRoot.top < line.boundsInRoot.bottom - 1f) fail("$name: Autopilot's button sits beside its words on the cover screen")
        }
    }

    private fun mail(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK, below: Boolean = true): MekaCore {
        val core = setUp(theme, fill)
        show(core)
        turns()
        compose.waitForIdle()
        // Reading the accounts here can land the screen's own read too: let it draw before the shot and the checks
        // (slice 33: the inner shot was taken before it, still drawing its first read).
        val v = if (fill == Fill.OFFLINE) null else runBlocking { core.mailAccounts() } ?: fail("$name: Mail never read the accounts")
        if (v != null) {
            turns()
            compose.waitForIdle()
        }
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        val top = File(ScreenShots.path("mail", name))
        compose.onNodeWithTag("screen").captureRoboImage(top.path)
        // Walk-through A20 ("mail one = many"): One's accounts and Still to connect sit below the switches, so with none of
        // them on screen its top is Many's top byte for byte; then only its -below shot is kept (once the test passes).
        val topIsMany = fill == Fill.ONE && v != null &&
            !ScreenShots.anyOnScreen(compose, v.rows.map { it.title } + v.connects.map { it.label } + listOfNotNull(v.toConnect))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertDatesWhole(compose, name)
        ScreenShots.assertClocksWhole(compose, name)
        ScreenShots.assertNumbersWhole(compose, name)
        ScreenShots.assertShows(compose, name, MailAccountRules.TITLE)
        if (v == null) {
            ScreenShots.assertShows(compose, name, MailAccountRules.OFFLINE)
            return core
        }
        val lines = lines(v)
        ScreenShots.assertNoLoneWords(compose, name, lines)
        ScreenShots.assertNoDotStartingALine(compose, name, lines)
        // Titles ("Personal · Gmail"), the buttons and the label chips each on one line.
        ScreenShots.assertEachOneLine(
            compose, name,
            v.rows.map { it.title } + v.connects.map { it.label } + listOf("Reconnect", MailAccountRules.DISCONNECT) +
                listOfNotNull(v.autopilot?.action),
        )
        ScreenShots.assertReadable(compose, name, v.rows.map { it.title }, mustShow = false)
        switchesShort(name, v, cover = name.startsWith("cover"))
        noChoiceChips(name, core)
        // Found and fixing week 5, A8: the Life audit counts the emails Needs you lists (its own rule), never the all-clear
        // over them.
        if (fill == Fill.ONE || fill == Fill.MANY) {
            val audit = runBlocking { core.lifeAuditReview() } ?: fail("$name: no Life audit with mail connected")
            val onYou = "${ShotMailToAnswer.size} emails waiting on you"
            if (onYou !in audit.summary) fail("$name: the Life audit reads \"${audit.summary}\" while Needs you lists ${ShotMailToAnswer.size} to answer")
            if (ScreenShots.anyOnScreen(compose, listOf(LifeAuditReviewRules.CLEAR, LoneWordGlue.kept(LifeAuditReviewRules.CLEAR))))
                fail("$name: \"${LifeAuditReviewRules.CLEAR}\" beside ${ShotMailToAnswer.size} emails to answer")
        }
        // Found and fixing week 4, A16: the all-clear is said once, not "No emails waiting on you ✓" over the life
        // audit's "Nothing needs you from 60 days of mail ✓".
        if (ScreenShots.anyOnScreen(compose, listOf(LifeAuditReviewRules.CLEAR)) &&
            ScreenShots.anyOnScreen(compose, listOf(LoneWordGlue.kept(MailAutoDraftRules.NONE_WAITING), MailAutoDraftRules.NONE_WAITING))
        ) fail("$name: says nothing waits on him twice")
        when (fill) {
            Fill.EMPTY -> {
                if (v.rows.isNotEmpty() || v.autopilot != null) fail("$name: $v with nothing connected")
                ScreenShots.assertShows(compose, name, LoneWordGlue.kept(MailAccountRules.EMPTY))
                v.connects.forEach { ScreenShots.assertShows(compose, name, it.label) }
                // Found and fixing week 5, A13: the line and its buttons are one card, the buttons straight under the line
                // (not past the page's other cards), each drawn once.
                val lineBottom = compose.onNode(hasText(LoneWordGlue.kept(MailAccountRules.EMPTY)), useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot.bottom
                v.connects.forEachIndexed { i, c ->
                    val nodes = compose.onAllNodes(hasText(c.label), useUnmergedTree = true).fetchSemanticsNodes()
                    if (nodes.size != 1) fail("$name: \"${c.label}\" is drawn ${nodes.size} times")
                    val gap = nodes[0].boundsInRoot.top - lineBottom
                    val most = (i + 1) * 80 * compose.density.density
                    if (gap < 0 || gap > most) fail("$name: \"${c.label}\" sits ${gap / compose.density.density} dp under the empty line, not in its card")
                }
            }
            Fill.ONE -> {
                val row = v.rows.singleOrNull() ?: fail("$name: ${v.rows.size} rows for one account")
                if (row.sortedLine == null) fail("$name: a sorted inbox says nothing about it")
                ScreenShots.assertShows(compose, name, LoneWordGlue.kept(row.line))
                ScreenShots.assertShows(compose, name, "Still to connect")
            }
            Fill.MANY -> {
                if (v.rows.size != 3) fail("$name: ${v.rows.size} rows for three accounts")
                if (v.toConnect != null) fail("$name: all three connected, yet \"${v.toConnect}\"")
                val ended = v.rows.first { it.email == business.email }
                if (!ended.needsReconnect) fail("$name: an ended sign-in doesn't offer Reconnect")
                ScreenShots.assertShows(compose, name, "Reconnect")
                // A failed check says why, not the catch-all.
                val failed = v.rows.first { it.email == outlook.email }
                if (failed.line.startsWith("Couldn't reach it last time")) fail("$name: a busy check reads \"${failed.line}\"")
                // Personal first, then Business, then Outlook.
                if (v.rows.map { it.email } != listOf(personal.email, business.email, outlook.email)) fail("$name: order ${v.rows.map { it.title }}")
            }
            Fill.OFFLINE -> Unit
        }
        // Walk-through A15: One, Many and by-hand shot the same top of the screen (the switches; the accounts sit below
        // them), so the end of the page gets its own shot, which must differ from every other.
        val last = if (fill == Fill.ONE || fill == Fill.MANY) v.connects.lastOrNull()?.label ?: v.rows.last().title else null
        val shotBelow = below && last != null && ScreenShots.shootBelow(compose, "mail", name, last, settle = { turns(1_000) })
        // The whole page on screen from the start (nothing to scroll): the top is One's only shot, and it shows the end.
        if (topIsMany && shotBelow) top.delete()
        return core
    }

    /**
     * Walk-through week 5, A17: no open chip row for the ten-minute inbox, meeting prep or warranty reminders; their
     * choices live in each row's sheet, and Meeting prep's row (shown with or without mail) is on the page.
     */
    private fun noChoiceChips(name: String, core: MekaCore) {
        val labels = MailMomentTime.entries.map { it.label } + MeetingPrepLead.entries.map { it.label } + MailWarrantyLevel.entries.map { it.label }
        labels.toSet().forEach { label ->
            if (compose.onAllNodes(hasText(label) and hasClickAction() and !hasTestTag(NOTIFY_CHOICE_ROW_TAG)).fetchSemanticsNodes().isNotEmpty())
                fail("$name: a \"$label\" chip on the page; it belongs in its row's sheet")
        }
        // Each account's label is one "Label" · "Personal" row, no open Personal · Business · Outlook chip row.
        MailLabel.entries.forEach { l ->
            if (compose.onAllNodes(hasText(l.label) and hasClickAction() and !hasTestTag(NOTIFY_CHOICE_ROW_TAG)).fetchSemanticsNodes().isNotEmpty())
                fail("$name: a \"${l.label}\" label chip on the page; it belongs in the account's Label sheet")
        }
        runBlocking { core.mailAccounts() }?.rows?.forEach { r ->
            if (compose.onAllNodes(hasContentDescription(MailLabelChoiceRules.row(r.label).spoken)).fetchSemanticsNodes().isEmpty())
                fail("$name: no Label row reading \"${r.label.label}\" on ${r.title}")
        }
        val prep = runBlocking { core.meetingPrepLeadSetting() }
        if (compose.onAllNodes(hasContentDescription(MailChoiceRules.prepRow(prep).spoken)).fetchSemanticsNodes().isEmpty())
            fail("$name: no Meeting prep row reading \"${prep.label}\"")
        // Chase waits: each person is one row reading their wait; no "1 day … 2 weeks" or Back to usual chip on the page.
        val panel = runBlocking { core.followUpWaitsPanel() }
        val waitLabels = MailFollowUpRules.WAIT_CHOICES.map { MailFollowUpRules.waitLabel(it) } + FollowUpWaitsRules.RESET
        if (panel.rows.isNotEmpty()) waitLabels.forEach { label ->
            if (compose.onAllNodes(hasText(label) and hasClickAction() and !hasTestTag(NOTIFY_CHOICE_ROW_TAG)).fetchSemanticsNodes().isNotEmpty())
                fail("$name: a \"$label\" chase-wait chip on the page; it belongs in the person's sheet")
        }
        panel.rows.forEach { r ->
            if (compose.onAllNodes(hasContentDescription(r.choiceRow.spoken)).fetchSemanticsNodes().isEmpty())
                fail("$name: no Chase waits row reading \"${r.who} · ${r.choiceRow.value}\"")
        }
    }

    /** Works by hand (A17): Sam Taylor's Chase waits row opens its sheet; 2 weeks saves, closes it and the row says so; Back to usual takes him off. */
    private fun waitsByHand(core: MekaCore) {
        val name = "mail-waits-by-hand"
        val sam = runBlocking { core.followUpWaitsPanel() }.rows.firstOrNull { it.who == "Sam Taylor" } ?: fail("$name: no Sam Taylor in Chase waits")
        if (sam.days != 7) fail("$name: Sam Taylor starts on ${sam.choiceRow.value}")
        // Its opening shot is Many's top byte for byte, and the row sits below the fold; scrolling the paused-clock page
        // to it never settles (slice 89), so this test keeps no shot and works through the semantics alone.
        File(ScreenShots.path("mail", "cover-dark-waits-by-hand")).delete()
        tapSaid(name, sam.choiceRow.spoken)
        until { sheetOpen() }
        if (!sheetOpen()) fail("$name: Sam Taylor's row didn't open its sheet")
        fun samDays() = runBlocking { core.followUpWaitsPanel() }.rows.firstOrNull { it.who == "Sam Taylor" }?.days
        tapSaid(name, sam.sheet.choices.first { it.name == "14" }.spoken)
        until { samDays() == 14 && !sheetOpen() }
        if (samDays() != 14) fail("$name: 2 weeks didn't save")
        if (sheetOpen()) fail("$name: the sheet stayed open after a choice")
        val row = FollowUpWaitsRules.choiceRow("Sam Taylor", 14).spoken
        until { compose.onAllNodes(hasContentDescription(row)).fetchSemanticsNodes().isNotEmpty() }
        if (compose.onAllNodes(hasContentDescription(row)).fetchSemanticsNodes().isEmpty()) fail("$name: the row doesn't say 2 weeks")
        tapSaid(name, row)
        until { sheetOpen() }
        val reset = runBlocking { core.followUpWaitsPanel() }.rows.first { it.who == "Sam Taylor" }.sheet.choices.first { it.name == FollowUpWaitsRules.RESET_NAME }
        tapSaid(name, reset.spoken)
        until { samDays() == null && !sheetOpen() }
        if (samDays() != null) fail("$name: Back to usual left Sam Taylor on his own wait")
    }

    /** Works by hand (A17): the account's Label row opens its sheet, which says what Business does; Business relabels it, closes the sheet and the row says so. */
    private fun labelByHand(core: MekaCore) {
        val name = "mail-label-by-hand"
        File(ScreenShots.path("mail", "cover-dark-label-by-hand")).delete()
        fun label() = runBlocking { core.mailAccounts() }?.rows?.firstOrNull()?.label
        if (label() != MailLabel.PERSONAL) fail("$name: the account starts as ${label()}")
        tapSaid(name, MailLabelChoiceRules.row(MailLabel.PERSONAL).spoken)
        until { sheetOpen() }
        if (!sheetOpen()) fail("$name: the Label row didn't open its sheet")
        val business = MailLabelChoiceRules.sheet(MailLabel.PERSONAL).choices.first { it.name == MailLabel.BUSINESS.name }
        tapSaid(name, business.spoken)
        until { label() == MailLabel.BUSINESS && !sheetOpen() }
        if (label() != MailLabel.BUSINESS) fail("$name: Business didn't relabel the account")
        if (sheetOpen()) fail("$name: the sheet stayed open after a choice")
        val row = MailLabelChoiceRules.row(MailLabel.BUSINESS).spoken
        until { compose.onAllNodes(hasContentDescription(row)).fetchSemanticsNodes().isNotEmpty() }
        if (compose.onAllNodes(hasContentDescription(row)).fetchSemanticsNodes().isEmpty()) fail("$name: the row doesn't say Business")
    }

    private fun sheetOpen() = compose.onAllNodesWithTag(NOTIFY_CHOICE_SHEET_TAG).fetchSemanticsNodes().isNotEmpty()

    /** Taps the node TalkBack reads as [desc] (in the sheet's window too). */
    private fun tapSaid(name: String, desc: String) {
        val n = compose.onAllNodes(hasContentDescription(desc) and hasClickAction())
        if (n.fetchSemanticsNodes().isEmpty()) fail("$name: no \"$desc\" to tap")
        n[0].performSemanticsAction(SemanticsActions.OnClick)
        turns(1_000)
    }

    private fun until(check: () -> Boolean) {
        repeat(40) {
            if (check()) return
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
    }

    /** Works by hand (A17): Meeting prep's row opens its sheet; 30 min saves, closes it and the row says 30 min; then Off. */
    private fun prepByHand(core: MekaCore) {
        val name = "mail-prep-by-hand"
        val before = runBlocking { core.meetingPrepLeadSetting() }
        tapSaid(name, MailChoiceRules.prepRow(before).spoken)
        until { sheetOpen() }
        if (!sheetOpen()) fail("$name: Meeting prep's row didn't open its sheet")
        tapSaid(name, MailChoiceRules.prepSheet(before).choices.first { it.name == MeetingPrepLead.THIRTY.name }.spoken)
        until { runBlocking { core.meetingPrepLeadSetting() } == MeetingPrepLead.THIRTY && !sheetOpen() }
        if (runBlocking { core.meetingPrepLeadSetting() } != MeetingPrepLead.THIRTY) fail("$name: 30 min didn't save")
        if (sheetOpen()) fail("$name: the sheet stayed open after a choice")
        val row = MailChoiceRules.prepRow(MeetingPrepLead.THIRTY).spoken
        until { compose.onAllNodes(hasContentDescription(row)).fetchSemanticsNodes().isNotEmpty() }
        if (compose.onAllNodes(hasContentDescription(row)).fetchSemanticsNodes().isEmpty()) fail("$name: the row doesn't say 30 min")
        // The by-hand shot is the row after its choice (its top before the taps is the empty shot byte for byte).
        compose.onAllNodes(hasContentDescription(row))[0].performScrollTo()
        ScreenShots.reshootAfterHand(compose, "mail", "cover-dark-prep-by-hand")
        tapSaid(name, row)
        until { sheetOpen() }
        tapSaid(name, MailChoiceRules.prepSheet(MeetingPrepLead.THIRTY).choices.first { it.name == MeetingPrepLead.OFF.name }.spoken)
        until { runBlocking { core.meetingPrepLeadSetting() } == MeetingPrepLead.OFF && !sheetOpen() }
        if (runBlocking { core.meetingPrepLeadSetting() } != MeetingPrepLead.OFF) fail("$name: Off didn't save")
    }

    /** Works by hand: Autopilot's Turn off turns it off, and its line and button say so. */
    private fun autopilotByHand(core: MekaCore) {
        val before = runBlocking { core.mailAccounts() }?.autopilot ?: fail("Mail: no Autopilot with an account connected")
        if (!before.on) fail("Mail: Autopilot starts off (${before.line})")
        val buttons = compose.onAllNodes(hasText(MailAutopilotPanelRules.TURN_OFF) and hasClickAction())
        if (buttons.fetchSemanticsNodes().isEmpty()) fail("Mail: no Turn off")
        // Autopilot's card is the first switch on the screen; its button's own click action (no scroll, no touch).
        buttons[0].performSemanticsAction(SemanticsActions.OnClick)
        repeat(40) {
            if (runBlocking { core.mailAccounts() }?.autopilot?.on == false) return@repeat
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        turns()
        val after = runBlocking { core.mailAccounts() }?.autopilot ?: fail("Mail: Autopilot went away after Turn off")
        if (after.on) fail("Mail: Turn off didn't turn Autopilot off")
        ScreenShots.assertShows(compose, "mail-by-hand", LoneWordGlue.kept(MailAutopilotPanelRules.OFF_SHORT))
        // How it works opens the full line under the short one (Autopilot's card is the first).
        val how = compose.onAllNodes(hasText(MailAutopilotPanelRules.HOW_IT_WORKS) and hasClickAction(), useUnmergedTree = true)
        if (how.fetchSemanticsNodes().isEmpty()) fail("Mail: no How it works")
        how[0].performSemanticsAction(SemanticsActions.OnClick)
        turns()
        if (compose.onAllNodes(hasText(LoneWordGlue.kept(MailAutopilotPanelRules.OFF_LINE)), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
            fail("Mail: How it works didn't open Autopilot's full line")
        // Walk-through A11 (2026-11-08): the full line takes the short one's place, so "new mail stays where it lands" shows once.
        val repeats = compose.onAllNodes(hasText("new mail stays", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            .count { n -> n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text) != null }
        if (repeats != 1) fail("Mail: \"new mail stays\" is drawn $repeats times with How it works open")
        ScreenShots.assertShows(compose, "mail-by-hand", MailAutopilotPanelRules.HIDE_HOW)
        ScreenShots.assertShows(compose, "mail-by-hand", LoneWordGlue.kept(MailAutopilotPanelRules.switchedLine(false)))
        if (compose.onAllNodes(hasText(MailAutopilotPanelRules.TURN_ON) and hasClickAction()).fetchSemanticsNodes().isEmpty()) {
            fail("Mail: no Turn on after Turn off")
        }
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun coverOffline() { mail("cover-dark-offline", Fill.OFFLINE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverEmpty() { mail("cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverOne() { mail("cover-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverMany() { mail("cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverManyLight() { mail("cover-light-many", Fill.MANY, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerEmpty() { mail("inner-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerMany() { mail("inner-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun prepByHand() { prepByHand(mail("cover-dark-prep-by-hand", Fill.EMPTY, below = false)) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun labelByHand() { labelByHand(mail("cover-dark-label-by-hand", Fill.ONE, below = false)) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun waitsByHand() { waitsByHand(mail("cover-dark-waits-by-hand", Fill.MANY, below = false)) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun autopilotByHand() {
        autopilotByHand(mail("cover-dark-by-hand", Fill.ONE, below = false))
        ScreenShots.reshootAfterHand(compose, "mail", "cover-dark-by-hand") // walk-through A15
    }
}
