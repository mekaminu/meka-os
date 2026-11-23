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
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.ask.MoneyPane
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.core.domain.MoneyTab
import os.meka.core.domain.MoneyTabs
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore

/**
 * The payment sheet (Meka 2026-10-26 23:18: a tap on a payment opened an unthemed white dropdown). A tap now raises the
 * dark sheet with the payee, category chips and the toggles; a chip moves every payment from that payee and the line
 * says so under the chips with Undo; Done closes it. Shot on the cover screen in Dark and Light, on [MoneyShotMonth]'s
 * shared month. Invented data (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class MoneyPaymentSheetScreensTest {
    /** Outermost: a hang fails within minutes with every thread's stack, instead of holding CI to its limit. */
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private companion object {
        const val SETTLE_MS = 3_000L
    }

    private fun open(theme: ThemeChoice): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val core = MekaCore(
            householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
            secureRandom = Random(23), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
        )
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            core.openMoney()
            // Found and fixing week 5, A5 (g1b 4): these shots imported a statement of their own (Corner Stores, one Costa
            // at £3.40), so their Coffee and Other read £3.40 and £126.85 beside Spending's and Obi's month; they now open
            // on the Money shots' shared month.
            MoneyShotMonth.fill(core)
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) { MoneyPane(core, onClose = {}) }
            }
        }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
        return core
    }

    /** Lets the main looper run between frames (a tap's state change recomposes from there), as BriefScreensTest does. */
    private fun settle() {
        repeat((SETTLE_MS / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(100)
    }

    /** Taps the newest payment, or the newest from [merchant], in Spending's latest payments; its id. */
    private fun tapNewest(core: MekaCore, merchant: String? = null): String {
        val rows = core.moneyView.value.month?.spending?.latest.orEmpty().flatMap { it.rows }
        val row = (if (merchant == null) rows.firstOrNull() else rows.firstOrNull { it.merchant == merchant }) ?: fail("no latest payment")
        val tag = "money-txn-${row.id}"
        // A lazy list's scroll waits for a frame, so the clock runs while it scrolls (as BriefScreensTest does).
        compose.mainClock.autoAdvance = true
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        settle()
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
        settle()
        return row.id
    }

    private fun shows(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /**
     * Waits, by short turns, for a node tagged [tag], then asserts it (flaky on CI 2026-11-16/17: the core's view had
     * the write but the sheet hadn't drawn its line or chip yet). Stops as soon as it shows; still fails when it never does.
     */
    private fun awaitTag(tag: String, why: String) {
        var i = 0
        while (!shows(tag) && i++ < 160) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(25)
            compose.mainClock.advanceTimeBy(100)
        }
        compose.waitForIdle()
        assertTrue(shows(tag), why)
    }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun aTapOpensTheSheetAndAChipMovesEveryPaymentFromThePayee() {
        val core = open(ThemeChoice.DARK)
        val id = tapNewest(core)
        val payee = core.moneyView.value.month!!.txns.first { it.id == id }.merchant
        val fromPayee = core.moneyView.value.month!!.txns.count { it.merchant == payee }
        assertTrue(fromPayee > 1, "the newest payee has one payment, so the shot can't show every payment moving")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "cover-dark-payment-sheet"))
        assertTrue(shows("money-sheet"), "the payment sheet didn't open")
        assertTrue(shows("money-sheet-cat-groceries"), "no Groceries chip")
        assertTrue(shows("money-sheet-mark-payment_shared"), "no Shared toggle")
        ScreenShots.assertNoBrokenWords(compose, "cover-dark-payment-sheet")
        compose.onNodeWithTag("money-sheet-cat-fun", useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        settle()
        // The write runs on the core's own thread; wait for it to land in the view.
        var i = 0
        fun movedAll(): Boolean {
            val txns = core.moneyView.value.month?.txns ?: return false
            val merchant = txns.firstOrNull { it.id == id }?.takeIf { it.category == "fun" }?.merchant ?: return false
            return txns.count { it.merchant == merchant && it.category == "fun" } == fromPayee
        }
        while (!movedAll() && i++ < 160) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        settle()
        val moved = core.moneyView.value.month?.txns?.first { it.id == id } ?: fail("the payment went")
        assertEquals("fun", moved.category)
        // Every payment from the payee moved, not only the one tapped.
        assertEquals(fromPayee, core.moneyView.value.month!!.txns.count { it.merchant == moved.merchant && it.category == "fun" })
        awaitTag("money-sheet-said", "no line after the pick")
        awaitTag("money-sheet-undo", "no Undo after the pick")
        compose.onNodeWithTag("money-sheet-done", useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        settle()
        if (shows("money-sheet")) fail("Done didn't close the sheet")
    }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun theSheetInLight() {
        val core = open(ThemeChoice.LIGHT)
        tapNewest(core)
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "cover-light-payment-sheet"))
        assertTrue(shows("money-sheet"), "the payment sheet didn't open")
        ScreenShots.assertNoBrokenWords(compose, "cover-light-payment-sheet")
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    /** His own categories (Meka 2026-10-26 23:18, "a Vape category"): + New category, name, colour, icon, Create. */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun aNewCategoryOfHisOwnTakesThePayee() {
        val core = open(ThemeChoice.DARK)
        // Walk-through 2026-11-15, A13: Treats is made from Costa Coffee, as in the category's own shots (its sheet's
        // "1 payee · … of £10"); it was made from Corner Stores' −£55.60 here.
        val id = tapNewest(core, "Costa Coffee")
        assertTrue(shows("money-sheet-new"), "no + New category chip")
        tap("money-sheet-new")
        assertTrue(shows("money-new-category"), "the New category form didn't open")
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("money-new-name")), useUnmergedTree = true).performTextInput("Treats")
        settle()
        tap("money-new-tone-3")
        tap("money-new-icon-cup")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "cover-dark-new-category"))
        ScreenShots.assertNoBrokenWords(compose, "cover-dark-new-category")
        tap("money-new-create")
        var i = 0
        while (core.moneyView.value.month?.txns?.firstOrNull { it.id == id }?.categoryLabel != "Treats" && i++ < 160) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        settle()
        val v = core.moneyView.value
        val own = v.ownCategories.singleOrNull() ?: fail("the category wasn't made")
        assertEquals("Treats", own.name)
        assertEquals(3, own.tone)
        assertEquals("Treats", v.month!!.txns.first { it.id == id }.categoryLabel)
        // The form closed and the chips are back with his category lit.
        if (shows("money-new-category")) fail("the form stayed open after Create")
        awaitTag("money-sheet-cat-${own.key}", "no chip for his new category")
        awaitTag("money-sheet-undo", "no Undo after making it")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "cover-dark-new-category-made"))
    }

    private fun waitFor(core: MekaCore, done: () -> Boolean) {
        var i = 0
        while (!done() && i++ < 80) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        settle()
    }

    /** Slice 3: Accounts → YOUR CATEGORIES; a tap opens the category's sheet: edit, hide from the ring, merge, delete. */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun hisCategoriesAreManagedFromAccounts() {
        val core = open(ThemeChoice.DARK)
        val costa = core.moneyView.value.month!!.txns.first { it.merchant == "Costa Coffee" }
        runBlocking { check(!core.addMoneyCategory("Treats", 3, "CUP", costa.id).refused) }
        settle()
        compose.onNodeWithContentDescription(MoneyTabs.spoken(MoneyTab.ACCOUNTS, false)).performSemanticsAction(SemanticsActions.OnClick)
        settle()
        val own = core.moneyView.value.ownRows.single()
        compose.mainClock.autoAdvance = true
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("money-own-card"))
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        settle()
        assertTrue(shows("money-own-${own.id}"), "no row for his category on Accounts")
        tap("money-own-${own.id}")
        assertTrue(shows("money-own-sheet"), "the category's sheet didn't open")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "cover-dark-own-category"))
        ScreenShots.assertNoBrokenWords(compose, "cover-dark-own-category")
        // Found and fixing week 5, A12: the cap field's hint fades into Cancel · Save while more is below, never cut through.
        assertTrue("foot" in ScreenShots.assertEdgesSoft(compose, "cover-dark-own-category", "money-own-body"),
            "the sheet's body has nothing below the foot, so the shot can't show its foot fading")
        // Walk-through A26: Cancel · Save sit at the sheet's foot, below Hide from the ring and the cap (never between the
        // form and them), the form draws no buttons of its own, and the sheet stops short of the screen's top edge.
        if (shows("money-new-create") || shows("money-new-cancel")) fail("the edit form still has its own Cancel / Save mid-sheet")
        val sheet = compose.onNodeWithTag("money-own-sheet").fetchSemanticsNode().boundsInRoot
        val hide = compose.onNodeWithTag("money-own-hide", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val foot = compose.onNodeWithTag("money-own-foot").fetchSemanticsNode().boundsInRoot
        assertTrue(foot.top >= hide.bottom, "Cancel / Save sit above Hide from the ring: foot $foot, hide $hide")
        assertTrue(foot.bottom <= sheet.bottom + 1f, "Cancel / Save run off the sheet: foot $foot in $sheet")
        assertTrue(sheet.top >= 16f, "the sheet sits against the screen's top edge: $sheet")
        tap("money-own-hide")
        waitFor(core) { core.moneyView.value.ownRows.firstOrNull()?.hidden == true }
        assertTrue(core.moneyView.value.ownRows.single().hidden, "Hide from the ring didn't land")
        tap("money-own-delete")
        assertTrue(shows("money-own-delete-yes"), "Delete didn't ask first")
        tap("money-own-delete-yes")
        waitFor(core) { core.moneyView.value.ownRows.isEmpty() }
        assertTrue(core.moneyView.value.ownRows.isEmpty(), "the category wasn't deleted")
        assertEquals("Other", core.moneyView.value.month!!.txns.first { it.id == costa.id }.categoryLabel)
        if (shows("money-own-sheet")) fail("the sheet stayed open after Delete")
    }

    /** Walk-through A26: the sheet's Save renames it and closes; Save with nothing changed only closes, Cancel keeps the old name. */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun saveAtTheFootRenamesAndCloses() {
        val core = open(ThemeChoice.DARK)
        val costa = core.moneyView.value.month!!.txns.first { it.merchant == "Costa Coffee" }
        runBlocking { check(!core.addMoneyCategory("Treats", 3, "CUP", costa.id).refused) }
        settle()
        compose.onNodeWithContentDescription(MoneyTabs.spoken(MoneyTab.ACCOUNTS, false)).performSemanticsAction(SemanticsActions.OnClick)
        settle()
        val own = core.moneyView.value.ownRows.single()
        compose.mainClock.autoAdvance = true
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("money-own-card"))
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        settle()
        tap("money-own-${own.id}")
        val name = hasSetTextAction() and hasAnyAncestor(hasTestTag("money-new-name"))
        compose.onNode(name, useUnmergedTree = true).performTextReplacement("Snacks")
        settle()
        tap("money-own-cancel")
        if (shows("money-own-sheet")) fail("Cancel didn't close the sheet")
        assertEquals("Treats", core.moneyView.value.ownRows.single().name, "Cancel saved the new name")
        tap("money-own-${own.id}")
        tap("money-own-save")
        if (shows("money-own-sheet")) fail("Save with nothing changed didn't close the sheet")
        assertEquals("Treats", core.moneyView.value.ownRows.single().name)
        tap("money-own-${own.id}")
        compose.onNode(name, useUnmergedTree = true).performTextReplacement("Snacks")
        settle()
        tap("money-own-save")
        waitFor(core) { core.moneyView.value.ownRows.firstOrNull()?.name != "Treats" }
        if (shows("money-own-sheet")) fail("Save didn't close the sheet")
        assertEquals("Snacks", core.moneyView.value.ownRows.single().name, "Save didn't rename it")
    }

    /**
     * His categories in caps: MONTHLY CAP in the category's sheet; "£15" caps it and its bar shows this month against it
     * (the shared month's October Costas: £3.20, £3.40 and £3.40).
     */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun aMonthlyCapOnHisCategory() {
        val core = open(ThemeChoice.DARK)
        val costa = core.moneyView.value.month!!.txns.first { it.merchant == "Costa Coffee" }
        runBlocking { check(!core.addMoneyCategory("Treats", 3, "CUP", costa.id).refused) }
        settle()
        compose.onNodeWithContentDescription(MoneyTabs.spoken(MoneyTab.ACCOUNTS, false)).performSemanticsAction(SemanticsActions.OnClick)
        settle()
        val own = core.moneyView.value.ownRows.single()
        compose.mainClock.autoAdvance = true
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("money-own-card"))
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        settle()
        tap("money-own-${own.id}")
        assertTrue(shows("money-own-cap-field"), "no MONTHLY CAP field in the category's sheet")
        val field = hasSetTextAction() and hasAnyAncestor(hasTestTag("money-own-cap-field"))
        compose.onNode(field, useUnmergedTree = true).performTextInput("£15")
        // The field's Done reads what was typed from the last composition, so let it recompose first.
        settle()
        compose.onNode(field, useUnmergedTree = true).performImeAction()
        waitFor(core) { core.moneyView.value.ownRows.firstOrNull()?.cap != null }
        val cap = core.moneyView.value.ownRows.single().cap ?: fail("the cap didn't land")
        assertEquals("£10 of £15", cap.amount)
        assertTrue(shows("money-own-cap"), "the cap's bar didn't show in the sheet")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "cover-dark-own-category-cap"))
        ScreenShots.assertNoBrokenWords(compose, "cover-dark-own-category-cap")
        // Walk-through 2026-11-08, A17: with the sheet scrolled to the cap, the category's head is still whole at the
        // sheet's top (not a sliver of it over "Edit category"), and the scrolling body starts below it.
        val sheet = compose.onNodeWithTag("money-own-sheet").fetchSemanticsNode().boundsInRoot
        val head = compose.onNodeWithTag("money-own-head", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val body = compose.onNodeWithTag("money-own-body").fetchSemanticsNode().boundsInRoot
        assertTrue(head.top >= sheet.top && head.height > 0f, "the category's head isn't whole at the sheet's top: $head in $sheet")
        assertTrue(body.top >= head.bottom - 1f, "the sheet's body scrolls over its head: body $body, head $head")
        assertTrue(compose.onNodeWithTag("money-own-body").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke()?.let { it > 0f } == true,
            "the shot isn't of a scrolled sheet, so it can't show the head staying put")
        // Found and fixing week 5, A12: the name field that scrolled under the head fades out, never half a "Treats".
        assertTrue("top" in ScreenShots.assertEdgesSoft(compose, "cover-dark-own-category-cap", "money-own-body"),
            "the sheet's body isn't scrolled, so the shot can't show its head's edge")
        // Found and fixing week 5, A12: the cap's line with Undo is a bar above Cancel · Save, not a line mid-sheet.
        val bar = compose.onNodeWithTag("money-own-said-bar").fetchSemanticsNode().boundsInRoot
        val foot = compose.onNodeWithTag("money-own-foot").fetchSemanticsNode().boundsInRoot
        assertTrue(bar.top >= body.bottom - 1f && bar.bottom <= foot.top + 1f,
            "the cap's line isn't a bar between the body and Cancel · Save: bar $bar, body $body, foot $foot")
        val line = compose.onNodeWithTag("money-own-said", useUnmergedTree = true).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()
        assertTrue(line.startsWith("Treats capped at £15"), "the bar says \"$line\"")
        tap("money-own-undo")
        waitFor(core) { core.moneyView.value.ownRows.firstOrNull()?.cap == null }
        assertTrue(!shows("money-own-said-bar"), "the bar stayed after Undo")
    }

    /** Needs and wants: the sheet's Need toggle marks the payee; Spending's "Needs vs wants" chip splits the month in two. */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun theNeedToggleAndTheNeedsView() {
        val core = open(ThemeChoice.DARK)
        // Walk-through 2026-11-15, A13: the Need toggle is tried on a grocery payment, so the Needs vs wants shot isn't
        // uncategorised money filed as a Need (the toggle had made Other a need). The shared month's Tesco is Groceries.
        val tesco = core.moneyView.value.month!!.txns.first { it.merchant.startsWith("Tesco") }
        assertEquals("groceries", tesco.category, "the shared month's Tesco isn't Groceries")
        val id = tapNewest(core, tesco.merchant)
        assertTrue(shows("money-sheet-mark-need"), "no Need toggle")
        val was = core.moneyView.value.month!!.txns.first { it.id == id }.need
        tap("money-sheet-mark-need")
        waitFor(core) { core.moneyView.value.month?.txns?.firstOrNull { it.id == id }?.needMarked == true }
        val now = core.moneyView.value.month!!.txns.first { it.id == id }
        assertEquals(!was, now.need, "Need didn't flip")
        assertTrue(now.needMarked)
        assertTrue(shows("money-sheet-undo"), "no Undo after Need")
        // Undo puts Groceries back as a need, so the view shows the month as it is: groceries a need, the coffee a want.
        tap("money-sheet-undo")
        waitFor(core) { core.moneyView.value.month?.txns?.firstOrNull { it.id == id }?.need == was }
        assertEquals(was, core.moneyView.value.month!!.txns.first { it.id == id }.need, "Undo didn't put Need back")
        tap("money-sheet-done")
        compose.mainClock.autoAdvance = true
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("money-view-needs"))
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        settle()
        assertTrue(shows("money-view-needs"), "no Needs vs wants chip on Spending")
        // A19: "All N ›" beside the chips sits on their centre line (it was xs/2 above it).
        if (shows("money-cats-all")) {
            fun mid(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.y
            val off = kotlin.math.abs(mid("money-cats-all") - mid("money-view-needs"))
            assertTrue(off <= 1.5f, "\"All\" is ${off}px off the chips' centre line")
        }
        tap("money-view-needs")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "cover-dark-needs-wants"))
        ScreenShots.assertNoBrokenWords(compose, "cover-dark-needs-wants")
        // Walk-through 2026-11-22 A12: "£91 over September by the 21st" sat on the ring here.
        ScreenShots.assertInsideRing(compose, "cover-dark-needs-wants")
        // A15: "2 payments without a / category" and "Income · Current / account" left a word alone on the cover.
        val m15 = core.moneyView.value.month!!
        ScreenShots.assertNoLoneWords(compose, "cover-dark-needs-wants",
            listOfNotNull(m15.unsorted?.line) + m15.txns.flatMap { listOf(os.meka.core.domain.MoneyPaymentSheet.rowLine(it), os.meka.core.domain.MoneyPaymentSheet.rowLine(it, showDay = false)) })
        compose.mainClock.autoAdvance = true
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("Wants, ", substring = true))
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        settle()
        assertTrue(compose.onAllNodes(hasContentDescription("Needs, ", substring = true)).fetchSemanticsNodes().isNotEmpty(), "no Needs row")
        val needs = core.moneyView.value.month!!.spending!!.needs!!.rows
        assertTrue(needs.first { it.key == "needs" }.note.contains("Groceries"), "Groceries isn't under Needs: $needs")
        assertTrue(needs.none { it.note.contains("Other") && it.key == "needs" }, "uncategorised money is under Needs: $needs")
        tap("money-view-categories")
    }
}
