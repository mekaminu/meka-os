package os.meka.android.ask

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.drawscope.clipRect
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.withFrameNanos
import os.meka.core.domain.FeelMoment
import kotlinx.coroutines.withContext
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.designsystem.sharedTitleInPane
import os.meka.android.lists.Field
import os.meka.android.security.FoldBiometric
import os.meka.android.shell.MoreItem
import os.meka.android.shell.SharedMotion
import os.meka.core.domain.MoneyLook
import os.meka.core.domain.MoneyMore
import os.meka.core.domain.MoneyMoreKind
import os.meka.core.domain.MoneySorter
import os.meka.core.domain.MoneyBank
import os.meka.core.domain.MoneyBankView
import os.meka.core.domain.MoneyCardRow
import os.meka.core.domain.MoneyCards
import os.meka.core.domain.MoneyCardsView
import os.meka.core.domain.MoneyCategoryRow
import os.meka.core.domain.MoneyChallengeBar
import os.meka.core.domain.MoneyChallenges
import os.meka.core.domain.MoneyChallengesView
import os.meka.core.domain.MoneyCutChip
import os.meka.core.domain.MoneyForecast
import os.meka.core.domain.MoneyForecastRow
import os.meka.core.domain.MoneyForecastView
import os.meka.core.domain.MoneyPayday
import os.meka.core.domain.MoneyPaydayView
import os.meka.core.domain.MoneyRefundRow
import os.meka.core.domain.MoneyRefunds
import os.meka.core.domain.MoneyRules
import os.meka.core.domain.MoneyRegularRow
import os.meka.core.domain.MoneyOwnCategories
import os.meka.core.domain.MoneyAccountList
import os.meka.core.domain.MoneySave
import os.meka.core.domain.MoneyClear
import os.meka.core.domain.MoneySaved
import os.meka.core.domain.MoneySection
import os.meka.core.domain.MoneySheetMark
import os.meka.core.domain.MoneyNeeds
import os.meka.core.domain.MoneyTab
import os.meka.core.domain.MoneyTabs
import os.meka.core.domain.MoneySpending
import os.meka.core.domain.InstantPaymentRules
import os.meka.core.domain.PaymentTap
import os.meka.core.domain.MoneyTip
import os.meka.core.domain.MoneyTips
import os.meka.core.domain.MoneyTxnRow
import os.meka.core.domain.MoneyWatch
import os.meka.core.domain.MoneyBusiness
import os.meka.core.domain.MoneyBusinessView
import os.meka.core.domain.MoneyBusinessMail
import os.meka.core.domain.MoneyBusinessMailView
import os.meka.core.domain.MoneyShared
import os.meka.core.domain.MoneySharedView
import os.meka.core.domain.MoneyWorthPoint
import os.meka.core.domain.MoneyWorthRow
import os.meka.core.domain.MoneyWorthView
import os.meka.core.domain.MoneyGoals
import os.meka.core.domain.MoneyWorthOther
import os.meka.core.domain.MoneyWorthOtherRow
import os.meka.core.domain.TradingAlertsView
import os.meka.core.domain.TradingCurveView
import os.meka.core.domain.TradingExportRules
import os.meka.core.domain.TradingExportView
import os.meka.core.domain.TradingReviewView
import os.meka.core.domain.TradingTradesView
import os.meka.core.domain.TradingCurrencyView
import os.meka.core.domain.TradingView
import os.meka.core.facade.MekaCore

/**
 * Ask → Life → Money (Money manager, slice 1): behind his fingerprint (or the phone's PIN when none is set up), the
 * month's spending by category from the statement files he imports — ‹ month ›, "Spent £1,234 · in £2,500", against
 * the 6 months before, each category with its share as a bar (brass when well over its average), and the month's
 * payments; tap one to put that shop in another category from now on (Undo). Import: name the account, pick the CSV file
 * from the bank. Remove an account (tap twice), Delete all money data (asks first). Read-only; nothing is ever paid.
 *
 * Slice 3: this month leads with **WORTH A LOOK**: a possible double charge, a payment much bigger than usual or a first big
 * one, and fees (foreign, late, interest), each with "That's fine" (on both devices, Undo); Navilife never acts on them.
 *
 * Slice 6: **UNTIL PAYDAY** — payday from his pay, what he's spent since, what's still to go out before it (regular
 * payments and the radar's bills) and, with the pay account's balance (a statement's Balance column, or typed here),
 * what's safe to spend a day; lit when it's tight (the low-balance warning).
 *
 * Slice 7: **CARDS** — the accounts he marks as cards (It's a card; a charge card is paid in full), each with when its payment
 * is due (lit three days out; Paid with Undo stops the reminder), the statement and minimum, how much of the limit it
 * uses (lit from 75%), a 0% period ending (lit within 30 days) and interest or late fees charged; Edit details types
 * what the statement says (statement, minimum, due day, limit, 0% end), all synced with Undo. Navilife never pays.
 *
 * Slice 8: **NET WORTH** — one total from the accounts' balances (an account with none has a field to type it), what each
 * card's statement says he owes, and things he types ("Pension £42,000", "Car loan £3,200"; Remove with Undo), with "You
 * have … · you owe …", the change since last month and a brass line over up to 12 months (tap a month for its total).
 * Shown with this month, and before any statement is imported.
 *
 * Slice 5: **REFUNDS ON THE WAY** — each refund on Waiting for, back in his statement (Close it, Undo) or not yet, and after
 * two weeks a chase to copy (Copy the chase marks it chased; he sends it himself, Navilife sends nothing).
 *
 * Slice 4: **FORECAST AT YOUR PACE** — his average whole month (in, out, left over) and where that leaves him in 6 and 12
 * months; what-ifs (halve one of his three biggest everyday categories, an extra amount a month he types) live on this
 * phone only and go when Money locks.
 *
 * Bank sync: **FROM YOUR BANKS** — his banks fill Money by themselves through Lunch Flow (read-only, on Navilife's server):
 * when they were last fetched and how many accounts, **Refresh** (Refreshing… until the server has been), each account
 * whose bank wants it reconnected in Lunch Flow (lit, with Open Lunch Flow), and Sync them again for bank accounts he
 * removed. A statement of the same account counts once.
 *
 * Locking: the core holds no figures for the screen until [MekaCore.openMoney] after the prompt's yes, and closing the
 * pane (or leaving it) calls [MekaCore.closeMoney].
 *
 * Slice 2: this month also shows **tips from his own numbers** (a habit and what halving it saves, a category well over its
 * 6-month average, a regular payment that went up), each with "Hide tips like this" (Undo; Show them again), and his
 * **regular payments** with their monthly total.
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; the month's lines cross-fade, its sections
 * stagger in 40 ms apart, each bar grows to its share (snaps with reduced motion), a refused line blends to the accent
 * with a tick haptic; a save gives a light haptic.
 */
/** Part (4): longer than this after the yes and Money's open went wrong (it says so on the page); not timed. */
private const val MONEY_FEEL_WAIT_MS = 10_000L

@Composable
fun MoneyPane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val context = LocalContext.current
    // Taps feel instant: a payment's category, Shared, Always shared or Business shows in the same frame, drawn over
    // the core's view while the write runs (InstantPaymentRules); a refused or failed one puts the payment back.
    val coreView by core.moneyView.collectAsState()
    var payTaps by remember { mutableStateOf(emptyList<PaymentTap>()) }
    val v = remember(coreView, payTaps) { InstantPaymentRules.shown(coreView, payTaps) }
    var account by rememberSaveable { mutableStateOf("") }
    var said by remember { mutableStateOf<MoneySaved?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Money redesign (B), slice 1: the tab shown; Money opens on Spending each time the pane opens.
    var tab by rememberSaveable { mutableStateOf(MoneyTabs.FIRST) }
    val has: (MoneySection) -> Boolean = { s -> MoneyTabs.shows(tab, s, v) }
    // Money redesign (B2): the category picked on Spending's ring, and whether all categories / all payments show.
    var focus by rememberSaveable { mutableStateOf<String?>(null) }
    // Needs and wants: Spending's view chips (Categories · Needs vs wants) swap the ring and the rows.
    var needsView by rememberSaveable { mutableStateOf(false) }
    var allCats by rememberSaveable { mutableStateOf(false) }
    var allPays by rememberSaveable { mutableStateOf(false) }
    // Money review: Worth a look's open row, whether all rows show, and the ones put away while the write runs.
    var openLook by rememberSaveable { mutableStateOf<String?>(null) }
    var allLooks by rememberSaveable { mutableStateOf(false) }
    var finedLooks by remember { mutableStateOf(emptySet<String>()) }
    // Money review: the quick sorter (one payee at a time), the payees skipped and the ones picked while the write runs.
    var sorting by rememberSaveable { mutableStateOf(false) }
    var sortSkipped by remember { mutableStateOf(emptySet<String>()) }
    var sortDone by remember { mutableStateOf(emptySet<String>()) }

    val unlock: () -> Unit = {
        FoldBiometric.unlock(context, MoneyRules.UNLOCK_REASON, "Your spending, read-only") { ok ->
            // Speed (4c): the screen fills in the same frame (the last view, or "Opening your money…"); a no is said on the page.
            if (ok) {
                haptics.light()
                // Part (4): the yes to the screen's first content (the last view or "Opening your money…"), aim 300 ms.
                val yes = android.os.SystemClock.uptimeMillis()
                scope.launch {
                    withTimeoutOrNull(MONEY_FEEL_WAIT_MS) { core.moneyView.first { !it.locked } } ?: return@launch
                    withFrameNanos { }
                    core.noteFeel(FeelMoment.SCREEN, android.os.SystemClock.uptimeMillis() - yes, MoneyRules.TITLE)
                }
                scope.launch { runCatching { core.openMoney() } }
            } else { core.moneyUnlockRefused(); haptics.tick() }
        }
    }
    LaunchedEffect(Unit) { if (core.moneyView.value.locked) unlock() }
    // The app going to the background locks it too: back on screen, Open Money asks again.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) {
        kotlinx.coroutines.CoroutineScope(Dispatchers.Main.immediate).launch { runCatching { core.closeMoney() } }
    }
    // Leaving the pane locks Money again (on a scope that outlives the pane's, which is cancelled as it goes).
    DisposableEffect(Unit) { onDispose { kotlinx.coroutines.CoroutineScope(Dispatchers.Main.immediate).launch { runCatching { core.closeMoney() } } } }

    val say: (MoneySaved) -> Unit = { r -> if (r.refused) haptics.tick() else haptics.light(); said = r }
    val payTap: (PaymentTap, suspend () -> MoneySaved) -> Unit = { tap, call ->
        payTaps = InstantPaymentRules.add(payTaps, tap)
        scope.launch {
            try {
                val r = runCatching { call() }
                r.exceptionOrNull()?.let { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    haptics.tick(); said = MoneySaved(InstantPaymentRules.failedLine(tap, e.message), refused = true)
                }
                r.getOrNull()?.let(say)
            } finally {
                payTaps = InstantPaymentRules.forget(payTaps, tap)
            }
        }
    }
    // The payment sheet (Meka 2026-10-26 23:18): the payment it's open on, found again in each view so its chips follow.
    var sheetId by rememberSaveable { mutableStateOf<String?>(null) }
    val sheetRow = sheetId?.let { id ->
        v.month?.txns?.firstOrNull { it.id == id } ?: v.month?.spending?.latest?.firstNotNullOfOrNull { d -> d.rows.firstOrNull { it.id == id } }
    }
    val openSheet: (String) -> Unit = { id -> haptics.tick(); said = null; sheetId = id }
    // Slice 3: one of his own categories' sheet (Accounts → YOUR CATEGORIES), found again in each view.
    var ownSheetId by rememberSaveable { mutableStateOf<String?>(null) }
    val ownRow = ownSheetId?.let { id -> v.ownRows.firstOrNull { it.id == id } }
    // Money review, Bills: the card whose sheet is open (found again in each view; Not a card closes it) and Until payday's.
    var cardSheetId by rememberSaveable { mutableStateOf<String?>(null) }
    val cardRow = cardSheetId?.let { id -> v.cards?.rows?.firstOrNull { it.id == id } }
    var paydaySheet by rememberSaveable { mutableStateOf(false) }
    // Money review, Accounts: the account whose sheet is open.
    var accountSheetId by rememberSaveable { mutableStateOf<String?>(null) }
    val accountSheet = accountSheetId?.let { id -> MoneyAccountList.sheet(v, id) }
    // Accounts' rest: which MORE row's sheet is open (side hustle, shared costs, or import, export and delete).
    var moreSheet by rememberSaveable { mutableStateOf<MoneyMoreKind?>(null) }
    // Net worth's typed things (Money review, layout rule (e)): the sheet with them and the field to add one.
    // Plan's goals card: the goal whose sheet is open (found again in each view; it closes when the goal goes).
    var goalSheetId by rememberSaveable { mutableStateOf<String?>(null) }
    val goalOpen = goalSheetId?.let { id -> v.goals?.rows?.firstOrNull { it.id == id } }
    var otherSheet by rememberSaveable { mutableStateOf(false) }
    // Layout rule (c): the ⓘ sheet open, if any (not saved: it's rebuilt from the view on a tap).
    var info by remember { mutableStateOf<MoneyInfo?>(null) }
    val otherOpen = v.worth?.takeIf { otherSheet }
    val paydayOpen = v.payday?.takeIf { paydaySheet }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = account
        scope.launch {
            busy = true
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { s -> s.readBytes().takeIf { it.size <= 8_000_000 }?.decodeToString() }
                }.getOrNull()
            }
            val r = if (text == null) MoneySaved("Couldn't read that file.", refused = true)
            else runCatching { core.importMoneyStatement(name, text) }.getOrNull()
            busy = false
            r?.let(say)
        }
    }

    // Trading tracker, slice 8: EXPORT FOR TAX — the server reads the year, then he picks where the CSV goes.
    var exportCsv by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(TradingExportRules.MIME)) { uri: Uri? ->
        val csv = exportCsv
        exportCsv = null
        if (uri == null || csv == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(csv.encodeToByteArray()) } != null }.getOrDefault(false)
            }
            say(if (ok) MoneySaved("Saved the spreadsheet · open it in Sheets or Excel, or send it to your accountant")
                else MoneySaved("Couldn't save the spreadsheet there · try another place", refused = true))
        }
    }
    val export: (String) -> Unit = { key ->
        if (!exporting) scope.launch {
            exporting = true
            said = MoneySaved(TradingExportRules.WORKING)
            val r = runCatching { core.tradingExport(key) }.getOrNull()
            exporting = false
            if (r == null) return@launch
            say(r.said)
            if (r.csv != null && r.fileName != null) { exportCsv = r.csv; runCatching { saveCsv.launch(r.fileName!!) } }
        }
    }
    // Accounts' rest: Export all payments — made on the phone from his own lines, then the same save sheet.
    val exportAll: () -> Unit = {
        if (!exporting) scope.launch {
            val r = runCatching { core.moneyExport() }.getOrNull() ?: return@launch
            say(r.said)
            if (r.csv != null && r.fileName != null) { exportCsv = r.csv; runCatching { saveCsv.launch(r.fileName!!) } }
        }
    }
    // Money slice 13b: YEAR-END EXPORT — made on the phone from his own lines, then the same save sheet.
    val exportBusiness: (String) -> Unit = { key ->
        if (!exporting) scope.launch {
            val r = runCatching { core.moneyBusinessExport(key) }.getOrNull() ?: return@launch
            say(r.said)
            if (r.csv != null && r.fileName != null) { exportCsv = r.csv; runCatching { saveCsv.launch(r.fileName!!) } }
        }
    }

    val tradingItems: LazyListScope.(TradingView) -> Unit = { t ->
        item(key = "trading-head") {
            Text(t.head, style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.animateItem().padding(top = MekaSpace.m))
        }
        item(key = "trading") {
            TradingBlock(t, Modifier.animateItem().appear(rememberAppearance(2)),
                onAdd = { text -> scope.launch { runCatching { core.tradingWalletAdd(text) }.getOrNull()?.let(say) } },
                onRemove = { id -> scope.launch { runCatching { core.tradingWalletRemove(id) }.getOrNull()?.let(say) } },
                onAlert = { kind, key ->
                    scope.launch { runCatching { core.setTradingAlert(kind, key) }.getOrNull()?.let { say(MoneySaved(it)) } }
                }, exporting = exporting, onExport = export)
        }
    }

    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(MekaSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        item(key = "close") {
            Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
        }
        item(key = "title") {
            Text(MoneyRules.TITLE, style = MekaType.greeting, color = Meka.colors.textPrimary,
                modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.MONEY)).appear(rememberAppearance(0)))
        }
        item(key = "summary") {
            Crossfade(v.summary, animationSpec = MekaMotion.appear(reduced), label = "money-summary") { s ->
                Text(s, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(1)))
            }
        }
        // Speed (4c): while the fresh view is built, the last one shows with "bringing it up to date…" under the title.
        v.opening?.takeIf { !v.skeleton }?.let { line ->
            item(key = "opening") {
                Text(line, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.animateItem())
            }
        }
        if (v.skeleton) return@LazyColumn
        if (v.locked) {
            item(key = "unlock") {
                Text(MoneyRules.UNLOCK, style = MekaType.itemMeta, color = Meka.colors.accent,
                    modifier = Modifier.clickable(role = Role.Button) { unlock() }.minTouch().appear(rememberAppearance(2)))
            }
            return@LazyColumn
        }
        // Money redesign (B), slice 1: Spending · Bills · Plan · Trading · Accounts; only the chosen tab's sections show.
        item(key = "tabs") {
            MoneyTabPills(tab, Modifier.animateItem().padding(top = MekaSpace.xs).appear(rememberAppearance(2))) { t ->
                tab = t
                MoneyTabs.stepOnPick(t, v)?.let { step -> scope.launch { runCatching { core.moneyStep(step) } } }
            }
        }
        MoneyTabs.empty(tab, v)?.let { e ->
            item(key = "tab-empty-${tab.name}") {
                Text(e, style = MekaType.body, color = Meka.colors.textSecondary,
                    modifier = Modifier.animateItem().padding(top = MekaSpace.m).appear(rememberAppearance(3)))
            }
        }
        // Bank sync: FROM YOUR BANKS (Accounts).
        v.bank?.takeIf { has(MoneySection.BANK) }?.let { b ->
            item(key = "bank-head") {
                Text(b.head, style = MekaType.caption, color = Meka.colors.textTertiary,
                    modifier = Modifier.animateItem().padding(top = MekaSpace.m))
            }
            item(key = "bank") {
                BankBlock(b, Modifier.animateItem().appear(rememberAppearance(2)),
                    onRefresh = { scope.launch { runCatching { core.moneyBankRefresh() }.getOrNull()?.let(say) } },
                    onRestore = { scope.launch { runCatching { core.moneyBankRestore() }.getOrNull()?.let(say) } },
                    onOpen = {
                        haptics.light()
                        runCatching {
                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(MoneyBank.LUNCH_FLOW_URL))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    })
            }
        }
        // Trading tracker: TRADING · HYPERLIQUID (whatever month is shown), from his public addresses only; it leads once
        // an address is in, else its set-up block waits below Import (quality gate, MoneyView.tradingFirst).
        v.trading?.takeIf { has(MoneySection.TRADING) }?.let { tradingItems(it) }
        v.month?.let { m ->
            if (has(MoneySection.MONTH)) item(key = "month") {
                Row(Modifier.animateItem().padding(top = MekaSpace.m).appear(rememberAppearance(2)), verticalAlignment = Alignment.CenterVertically) {
                    Arrow("‹", "Month before", enabled = v.earlier) { scope.launch { runCatching { core.moneyStep(-1) } } }
                    Crossfade(m.label, animationSpec = MekaMotion.appear(reduced), label = "money-month", modifier = Modifier.weight(1f)) { l ->
                        Text(l, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
                    }
                    Arrow("›", "Month after", enabled = v.later) { scope.launch { runCatching { core.moneyStep(1) } } }
                }
            }
            val sp = m.spending
            // The ring and rows as the view chip has them; payment chips keep their categories' colours ([sp]).
            val ringSp = sp?.let { MoneyNeeds.shown(it, needsView) }
            if (has(MoneySection.MONTH) && sp != null && ringSp != null) {
                if (sp.needs != null) item(key = "needs-views") {
                    MoneyViewChips(needsView, Modifier.animateItem().appear(rememberAppearance(3))) { on ->
                        if (on != needsView) { haptics.tick(); needsView = on; focus = null }
                    }
                }
                item(key = "ring") {
                    Box(Modifier.fillMaxWidth().animateItem().appear(rememberAppearance(3)), contentAlignment = Alignment.Center) {
                        MoneyRing(ringSp, focus) { focus = null }
                    }
                }
                if (sp.tiles.isNotEmpty()) item(key = "tiles") { MoneyTiles(sp.tiles, Modifier.animateItem().appear(rememberAppearance(4))) }
            }
            if (has(MoneySection.MONTH) && sp == null) item(key = "spent") {
                Column(Modifier.animateItem().appear(rememberAppearance(3)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                    Crossfade(m.spentLine, animationSpec = MekaMotion.appear(reduced), label = "money-spent") { l ->
                        Text(l, style = MekaType.body, color = Meka.colors.textPrimary)
                    }
                    m.vsLine?.let { vs ->
                        val c by animateColorAsState(if (m.vsLit) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "money-vs")
                        Crossfade(vs, animationSpec = MekaMotion.appear(reduced), label = "money-vs-line") { l ->
                            Text(l, style = MekaType.caption, color = c)
                        }
                    }
                }
            }
            // Slice 17: abroad, or a trip near — the card to use leads this month (lit).
            v.travel?.banner?.takeIf { has(MoneySection.TRAVEL_BANNER) }?.let { b ->
                item(key = "travel-banner") {
                    Crossfade(b, animationSpec = MekaMotion.appear(reduced), label = "money-travel-banner",
                        modifier = Modifier.animateItem().padding(top = MekaSpace.xs).appear(rememberAppearance(3))) { l ->
                        Text(l, style = MekaType.body, color = Meka.colors.accent)
                    }
                }
            }
            // Money manager (7): the month in review in plain sentences — the finished month shown, or last month leading
            // this one for its first days.
            // Layout rules (Meka 2026-10-26 22:18): one card of Spent · Came in · Left and two short lines; the sentences
            // and where they came from behind its ⓘ.
            v.review?.takeIf { has(MoneySection.REVIEW) }?.let { rv ->
                item(key = "review-${rv.month}") {
                    MoneyReviewCard(rv, Modifier.animateItem().padding(top = MekaSpace.m).appear(rememberAppearance(3))) {
                        haptics.tick(); info = MoneyInfo(rv.infoTitle, rv.info)
                    }
                }
            }
            // Slice 3: payments worth a second look (this month only), before the tips.
            if (has(MoneySection.ALERTS)) {
                item(key = "alerts-head") {
                    Crossfade(MoneyWatch.head(v.alerts.size), animationSpec = MekaMotion.appear(reduced), label = "money-alerts-head",
                        modifier = Modifier.animateItem().padding(top = MekaSpace.m)) { h ->
                        Text(h, style = MekaType.caption, color = Meka.colors.textTertiary)
                    }
                }
                // Money review (Meka 2026-10-26 22:12): compact one-line rows (what · amount · ›), three then "2 more ›"; a tap
                // opens one in place with That's fine, a swipe either way is That's fine.
                val looks = MoneyLook.rows(v.alerts).filter { it.id !in finedLooks }
                MoneyLook.shown(looks, allLooks).forEachIndexed { i, r ->
                    item(key = "alert-${r.id}") {
                        MoneyLookRowView(
                            r, open = openLook == r.id, modifier = Modifier.animateItem().appear(rememberAppearance(4 + i)),
                            onToggle = { haptics.tick(); openLook = if (openLook == r.id) null else r.id },
                        ) {
                            // Instant: the row leaves in this frame; a refused or failed write brings it back with the reason.
                            finedLooks = finedLooks + r.id
                            scope.launch {
                                val res = runCatching { core.moneyAlertFine(r.id) }.getOrNull()
                                if (res == null || res.refused) finedLooks = finedLooks - r.id
                                res?.let(say)
                            }
                        }
                    }
                }
                MoneyLook.moreLabel(looks.size, allLooks)?.let { more ->
                    item(key = "alerts-more") {
                        Text(more, style = MekaType.itemMeta, color = Meka.colors.accent,
                            modifier = Modifier.animateItem().clip(RoundedCornerShape(MekaRadius.m))
                                .clickable(role = Role.Button) { haptics.tick(); allLooks = !allLooks }.minTouch().testTag("money-looks-more"))
                    }
                }
            }
            // Money redesign (B3): the Bills tab's card, then the month's bills by date (to come first, paid greyed).
            v.bills?.takeIf { has(MoneySection.BILLS) }?.let { b ->
                item(key = "bills-card") {
                    MoneyBillsCard(b, Modifier.animateItem().padding(top = MekaSpace.m).appear(rememberAppearance(3)))
                }
                b.rows.forEachIndexed { i, r ->
                    item(key = "bill-${r.key}") { MoneyBillRowView(r, Modifier.animateItem().appear(rememberAppearance(4 + i))) }
                }
                item(key = "bills-foot") {
                    Text(b.foot, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.animateItem())
                }
            }
            // Slice 6: payday and what's safe to spend (this month only). Money review (Meka 2026-10-26 22:12): one row on
            // Bills, opening its sheet (since payday, the balance and its field), so no field sits open on the page.
            v.payday?.takeIf { has(MoneySection.PAYDAY) }?.let { p ->
                item(key = "payday-head") {
                    Text(p.head, style = MekaType.caption, color = Meka.colors.textTertiary,
                        modifier = Modifier.animateItem().padding(top = MekaSpace.m))
                }
                item(key = "payday") {
                    MoneyPaydayRow(MoneyPayday.brief(p), Modifier.animateItem().appear(rememberAppearance(4))) {
                        haptics.tick(); said = null; paydaySheet = true
                    }
                }
            }
            // Slice 7: his cards (this month only). Money review: one card of one-line rows ("due ? · tap to add"), each
            // opening the card's sheet with its details, Paid and the fields; the accounts that could be a card are on Accounts.
            v.cards?.takeIf { has(MoneySection.CARDS) }?.let { cv ->
                item(key = "cards-head") {
                    Text(cv.head, style = MekaType.caption, color = Meka.colors.textTertiary,
                        modifier = Modifier.animateItem().padding(top = MekaSpace.m))
                }
                item(key = "cards") {
                    MoneyCardsBriefCard(MoneyCards.briefs(cv), Modifier.animateItem().appear(rememberAppearance(5))) { id ->
                        haptics.tick(); said = null; cardSheetId = id
                    }
                }
            }
            // Slice 5: refunds on Waiting for, checked against his statements (this month only).
            v.refunds?.takeIf { has(MoneySection.REFUNDS) }?.let { rf ->
                item(key = "refunds-head") {
                    Crossfade(rf.head, animationSpec = MekaMotion.appear(reduced), label = "money-refunds-head",
                        modifier = Modifier.animateItem().padding(top = MekaSpace.m)) { h ->
                        Text(h, style = MekaType.caption, color = Meka.colors.textTertiary)
                    }
                }
                rf.rows.forEachIndexed { i, r ->
                    item(key = "refund-${r.id}") {
                        RefundCard(r, Modifier.animateItem().appear(rememberAppearance(4 + i)),
                            onClose = { scope.launch { runCatching { core.moneyRefundClose(r.id) }.getOrNull()?.let(say) } },
                            onCopy = { chase ->
                                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                                cm?.setPrimaryClip(android.content.ClipData.newPlainText("Refund chase", chase))
                                scope.launch { runCatching { core.moneyRefundChased(r.id) }.getOrNull()?.let(say) }
                            })
                    }
                }
                rf.foot?.let { f ->
                    item(key = "refunds-foot") {
                        Text(f, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.animateItem())
                    }
                }
            }
            // Slice 2: tips from his own numbers, then his regular payments (this month only).
            if (has(MoneySection.TIPS) && v.tips.isNotEmpty()) {
                item(key = "tips-head") {
                    Text("TIPS FROM YOUR NUMBERS", style = MekaType.caption, color = Meka.colors.textTertiary,
                        modifier = Modifier.animateItem().padding(top = MekaSpace.m))
                }
                v.tips.forEachIndexed { i, t ->
                    item(key = "tip-${t.id}") {
                        TipCard(t, Modifier.animateItem().appear(rememberAppearance(4 + i))) {
                            scope.launch { runCatching { core.hideMoneyTips(t.kind) }.getOrNull()?.let(say) }
                        }
                    }
                }
            }
            v.hiddenTips?.takeIf { has(MoneySection.TIPS) }?.let { h ->
                item(key = "tips-hidden") {
                    Row(Modifier.animateItem(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        Crossfade(h, animationSpec = MekaMotion.appear(reduced), label = "money-tips-hidden", modifier = Modifier.weight(1f)) { l ->
                            Text(l, style = MekaType.caption, color = Meka.colors.textTertiary)
                        }
                        Text(MoneyTips.SHOW_AGAIN, style = MekaType.caption, color = Meka.colors.accent,
                            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) {
                                scope.launch { runCatching { core.showMoneyTips() }.getOrNull()?.let(say) }
                            }.minTouch().padding(MekaSpace.xs))
                    }
                }
            }
            // Money redesign, Plan (mockup): What if I save… — worked out on this phone as he slides, Make it a goal.
            v.save?.takeIf { has(MoneySection.SAVE) }?.let { b ->
                item(key = "save-head") {
                    Text(MoneySave.HEAD, style = MekaType.caption, color = Meka.colors.textTertiary,
                        modifier = Modifier.animateItem().padding(top = MekaSpace.m))
                }
                item(key = "save") {
                    MoneySaveCard(b, Modifier.animateItem().appear(rememberAppearance(4)), onTick = { haptics.tick() }) { amount, months, rate ->
                        haptics.light()
                        scope.launch { runCatching { core.moneySaveGoal(amount, months, rate) }.getOrNull()?.let(say) }
                    }
                }
            }
            // Money redesign, Plan (mockup): Clear my cards — one amount a month across his cards, worked out here as he slides.
            v.clear?.takeIf { has(MoneySection.CLEAR) }?.let { b ->
                item(key = "clear-head") {
                    Text(MoneyClear.HEAD, style = MekaType.caption, color = Meka.colors.textTertiary,
                        modifier = Modifier.animateItem().padding(top = MekaSpace.m))
                }
                item(key = "clear") {
                    MoneyClearCard(b, Modifier.animateItem().appear(rememberAppearance(5)), onTick = { haptics.tick() }) { pay, order ->
                        scope.launch { runCatching { core.moneyClearGoal(pay, order) }.getOrNull()?.let(say) }
                    }
                }
            }
            // Money redesign, Plan's goals card (mockup): GOALS — each £ goal's progress and pace; a tap opens its sheet.
            v.goals?.takeIf { has(MoneySection.GOALS) }?.let { gv ->
                item(key = "goals-head") {
                    Text(gv.head, style = MekaType.caption, color = Meka.colors.textTertiary,
                        modifier = Modifier.animateItem().padding(top = MekaSpace.m))
                }
                item(key = "goals") {
                    MoneyGoalsCard(gv, Modifier.animateItem().appear(rememberAppearance(6))) { id -> haptics.tick(); said = null; goalSheetId = id }
                }
            }
            v.regular?.takeIf { has(MoneySection.REGULAR) }?.let { r ->
                item(key = "regular-head") {
                    Column(Modifier.animateItem().padding(top = MekaSpace.m), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                        Text("REGULAR PAYMENTS", style = MekaType.caption, color = Meka.colors.textTertiary)
                        Crossfade(r.head, animationSpec = MekaMotion.appear(reduced), label = "money-regular-head") { l ->
                            Text(l, style = MekaType.body, color = Meka.colors.textPrimary)
                        }
                    }
                }
                r.rows.forEach { row ->
                    item(key = "regular-${row.key}") { RegularRow(row, Modifier.animateItem()) }
                }
            }
            // Slice 4: the forecast at his pace, with what-ifs on this phone only (this month only).
            v.forecast?.takeIf { has(MoneySection.FORECAST) }?.let { f ->
                item(key = "forecast-head") {
                    Text(f.head, style = MekaType.caption, color = Meka.colors.textTertiary,
                        modifier = Modifier.animateItem().padding(top = MekaSpace.m))
                }
                f.notReady?.let { n ->
                    item(key = "forecast-not-ready") { Text(n, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.animateItem()) }
                }
                if (f.notReady == null) item(key = "forecast") {
                    ForecastBlock(f, Modifier.animateItem().appear(rememberAppearance(4)),
                        onCut = { key -> haptics.tick(); scope.launch { runCatching { core.moneyForecastCut(key) } } },
                        onExtra = { text -> scope.launch { runCatching { core.moneyForecastExtra(text) }.getOrNull()?.let(say) } })
                }
            }
            // Slice 15: challenges he set himself — no-spend days, fun money for the week, category caps (this month only).
            v.challenges?.takeIf { has(MoneySection.CHALLENGES) }?.let { ch ->
                item(key = "challenges-head") {
                    Text(ch.head, style = MekaType.caption, color = Meka.colors.textTertiary,
                        modifier = Modifier.animateItem().padding(top = MekaSpace.m))
                }
                item(key = "challenges") {
                    ChallengesBlock(ch, Modifier.animateItem().appear(rememberAppearance(4)),
                        onNoSpend = { on -> scope.launch { runCatching { core.moneyNoSpend(on) }.getOrNull()?.let(say) } },
                        onFun = { text -> scope.launch { runCatching { core.moneyFunMoney(text) }.getOrNull()?.let(say) } },
                        onCap = { text -> scope.launch { runCatching { core.moneyCap(text) }.getOrNull()?.let(say) } },
                        onRemove = { key -> scope.launch { runCatching { core.moneyChallengeRemove(key) }.getOrNull()?.let(say) } })
                }
            }
            // Slice 17: PAYING ABROAD — which accounts charged foreign fees, and the one to use (Plan).
            v.travel?.takeIf { has(MoneySection.TRAVEL) }?.let { tv ->
                item(key = "travel-head") {
                    Text(tv.head, style = MekaType.caption, color = Meka.colors.textTertiary,
                        modifier = Modifier.animateItem().padding(top = MekaSpace.m))
                }
                item(key = "travel") { TravelBlock(tv, Modifier.animateItem().appear(rememberAppearance(4))) }
            }
            if (has(MoneySection.CATEGORIES) && ringSp != null) {
                item(key = "cats-head") {
                    MoneyHeadWithLink("WHERE IT WENT", MoneySpending.allLabel(ringSp.rows.size, allCats), Modifier.animateItem()) { allCats = !allCats }
                }
                // Layout rule (a): the category rows sit in one card under their label.
                item(key = "spend-card") {
                    MoneyCard(Modifier.animateItem().appear(rememberAppearance(4)), tag = "money-spend-card") {
                        MoneySpending.shownRows(ringSp, allCats).forEach { r ->
                            key(r.key) {
                                MoneySpendRowView(r, picked = focus == r.key) {
                                    haptics.tick(); focus = if (focus == r.key) null else r.key
                                }
                            }
                        }
                    }
                }
            }
            // Money review: "52 payments without a category · Sort them" under the rows opens the quick sorter.
            m.unsorted?.takeIf { has(MoneySection.UNSORTED) }?.let { u ->
                item(key = "unsorted") {
                    MoneyUnsortedRow(u, Modifier.animateItem().appear(rememberAppearance(5))) {
                        haptics.tick(); said = null; sortSkipped = emptySet(); sortDone = emptySet(); sorting = true
                    }
                }
            }
            if (has(MoneySection.CATEGORIES) && sp == null) {
                item(key = "cats-head") {
                    Text("BY CATEGORY", style = MekaType.caption, color = Meka.colors.textTertiary,
                        modifier = Modifier.animateItem().padding(top = MekaSpace.m))
                }
                m.categories.forEachIndexed { i, c ->
                    item(key = "cat-${c.key}") { CategoryRow(c, Modifier.animateItem().appear(rememberAppearance(4 + i))) }
                }
            }
            if (has(MoneySection.PAYMENTS) && sp != null && !allPays) {
                item(key = "latest-head") {
                    MoneyHeadWithLink(MoneySpending.LATEST_HEAD, sp.seeAll, Modifier.animateItem()) { allPays = true }
                }
                // Layout rule (a): the latest payments, by day, in one card.
                item(key = "latest-card") {
                    MoneyCard(Modifier.animateItem().appear(rememberAppearance(5)), tag = "money-latest-card") {
                        sp.latest.forEach { d ->
                            key(d.label) {
                                MoneyDayHead(d)
                                d.rows.forEach { t ->
                                    key(t.id) {
                                        TxnRow(t, Modifier, chip = MoneySpending.txnIcon(sp, t.category) to MoneySpending.txnTone(sp, t.category), showDay = false,
                                            onOpen = { openSheet(t.id) })
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (has(MoneySection.PAYMENTS) && (sp == null || allPays)) {
                if (sp != null) item(key = "latest-only") {
                    MoneyHeadWithLink("ALL PAYMENTS THIS MONTH", MoneySpending.SEE_ALL_CLOSE, Modifier.animateItem()) { allPays = false }
                }
                item(key = "txns-head") {
                    Text("PAYMENTS · TAP ONE TO CHANGE ITS CATEGORY", style = MekaType.caption, color = Meka.colors.textTertiary,
                        modifier = Modifier.animateItem().padding(top = MekaSpace.m))
                }
                m.receiptsLine?.let { l ->
                    item(key = "txns-receipts") {
                        Text(l, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.animateItem())
                    }
                }
                m.txns.forEach { t ->
                    item(key = "txn-${t.id}") {
                        TxnRow(t, Modifier.animateItem(), chip = sp?.let { MoneySpending.txnIcon(it, t.category) to MoneySpending.txnTone(it, t.category) },
                            onOpen = { openSheet(t.id) })
                    }
                }
                if (m.more > 0) {
                    item(key = "txns-more") {
                        Text("and ${m.more} more this month", style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.animateItem())
                    }
                }
            }
            // Layout rule (c): Spending says "read-only · not financial advice" once, here, with where it's from behind ⓘ.
            if (has(MoneySection.SPEND_FOOT)) item(key = "spend-foot") {
                MoneyTabFoot(Modifier.animateItem()) {
                    haptics.tick(); MoneyTabs.info(MoneyTab.SPENDING, v)?.let { info = MoneyInfo(MoneyTabs.INFO_TITLE, it) }
                }
            }
        }
        // Slice 8: net worth (Accounts).
        v.worth?.takeIf { has(MoneySection.WORTH) }?.let { w ->
            item(key = "worth-head") {
                Text(w.head, style = MekaType.caption, color = Meka.colors.textTertiary,
                    modifier = Modifier.animateItem().padding(top = MekaSpace.m))
            }
            item(key = "worth") {
                WorthBlock(w, Modifier.animateItem().appear(rememberAppearance(4)),
                    onOther = { haptics.tick(); said = null; otherSheet = true })
            }
        }
        // Money review (Meka 2026-10-26 22:12), Accounts: one clean list under net worth, each row opening its sheet.
        if (has(MoneySection.ACCOUNTS)) {
            item(key = "accounts-head") {
                Text(MoneyAccountList.HEAD, style = MekaType.caption, color = Meka.colors.textTertiary,
                    modifier = Modifier.animateItem().padding(top = MekaSpace.m))
            }
            item(key = "accounts") {
                MoneyAccountsCard(MoneyAccountList.items(v), Modifier.animateItem().appear(rememberAppearance(4))) { row ->
                    haptics.tick()
                    if (row.opens) { said = null; accountSheetId = row.id }
                    else { tab = MoneyTab.TRADING }
                }
            }
        }
        // Slice 18: the year in review — last year, open, in January; this year so far, folded, after. Money review
        // (Meka 2026-10-26 22:12): it lives on Accounts, under net worth, not on Spending.
        v.year?.takeIf { has(MoneySection.YEAR) }?.let { yv ->
            item(key = "year-head") {
                Text(yv.head, style = MekaType.caption, color = Meka.colors.textTertiary,
                    modifier = Modifier.animateItem().padding(top = MekaSpace.m))
            }
            item(key = "year") { YearBlock(yv, Modifier.animateItem().appear(rememberAppearance(4)), onToggle = { haptics.tick() }) }
        }
        // Slice 3: YOUR CATEGORIES, the ones he made, each opening its sheet (Accounts).
        if (has(MoneySection.OWN_CATEGORIES)) {
            item(key = "own-head") {
                Text(MoneyOwnCategories.HEAD, style = MekaType.caption, color = Meka.colors.textTertiary,
                    modifier = Modifier.animateItem().padding(top = MekaSpace.m))
            }
            item(key = "own") {
                OwnCategoriesCard(v.ownRows, Modifier.animateItem().appear(rememberAppearance(4))) { id -> haptics.tick(); said = null; ownSheetId = id }
            }
        }
        // Accounts' rest (mockup): Side hustle and tax, Shared with his partner, and Import, export and delete as one card
        // of rows, each opening its sheet; what sat open on the page lives in those sheets now.
        v.empty?.takeIf { has(MoneySection.MORE) }?.let { e -> item(key = "empty") { Text(e, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.animateItem().padding(top = MekaSpace.m)) } }
        if (has(MoneySection.MORE)) {
            item(key = "more-head") {
                Text(MoneyMore.HEAD, style = MekaType.caption, color = Meka.colors.textTertiary,
                    modifier = Modifier.animateItem().padding(top = MekaSpace.m))
            }
            item(key = "more") {
                MoneyMoreCard(MoneyMore.rows(v), Modifier.animateItem().appear(rememberAppearance(4))) { kind ->
                    haptics.tick(); said = null
                    if (kind == MoneyMoreKind.DATA) account = ""
                    moreSheet = kind
                }
            }
        }
        item(key = "said") {
            val c by animateColorAsState(if (said?.refused == true) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "money-said")
            Crossfade(said, animationSpec = MekaMotion.appear(reduced), label = "money-said-line") { s ->
                if (s != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    Text(s.line, style = MekaType.caption, color = c, modifier = Modifier.weight(1f))
                    s.undo?.let { u ->
                        Text("Undo", style = MekaType.caption, color = Meka.colors.accent,
                            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) {
                                scope.launch {
                                    runCatching { core.undoMoneyCategory(u) }
                                    haptics.light()
                                    said = null
                                }
                            }.minTouch().padding(MekaSpace.xs))
                    }
                }
            }
        }
        if (has(MoneySection.FOOT)) item(key = "foot") {
            Column(Modifier.animateItem().padding(top = MekaSpace.l), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Text(MoneyRules.FOOT, style = MekaType.caption, color = Meka.colors.textTertiary)
            }
        }
    }
    PaymentSheet(
        row = sheetRow, order = v.categoryOrder, said = said,
        onCategory = { key -> sheetRow?.let { t -> payTap(InstantPaymentRules.categoryTap(t.id, key)) { core.setMoneyCategory(t.id, key) } } },
        onMark = { mark, on ->
            sheetRow?.let { t ->
                when (mark) {
                    MoneySheetMark.PAYMENT_SHARED -> payTap(InstantPaymentRules.sharedTap(t.id, on)) { core.moneySharedTxn(t.id, on) }
                    MoneySheetMark.PAYEE_SHARED -> payTap(InstantPaymentRules.alwaysTap(t.id, on)) { core.moneySharedMerchant(t.id, on) }
                    MoneySheetMark.SIDE_HUSTLE -> payTap(InstantPaymentRules.businessTap(t.id, on)) { core.moneyBusinessMerchant(t.id, on) }
                    MoneySheetMark.NEED -> payTap(InstantPaymentRules.needTap(t.id, on)) { core.moneyNeedMerchant(t.id, on) }
                }
            }
        },
        onUndo = { u -> scope.launch { runCatching { core.undoMoneyCategory(u) }; haptics.light(); said = null } },
        onClose = { sheetId = null },
        own = v.ownCategories,
        onNewCategory = { name, tone, icon ->
            sheetRow?.let { t -> scope.launch { runCatching { core.addMoneyCategory(name, tone, icon.name, t.id) }.getOrNull()?.let(say) } }
        },
    )
    // Money review: the quick sorter — the payment sheet on one payee at a time; a pick moves the payee and the next
    // comes up in the same frame, Skip leaves one for later, and the last one says All sorted.
    val sortV = v.month?.unsorted
    val sortItem = if (sorting) MoneySorter.current(sortV, sortSkipped, sortDone) else null
    val sortLeft = MoneySorter.left(sortV, sortSkipped, sortDone)
    // The last payee sorted (or skipped): the sorter closes and says so.
    LaunchedEffect(sorting, sortItem == null) {
        if (sorting && sortItem == null) { sorting = false; said = MoneySaved(MoneySorter.doneLine(sortSkipped.count { k -> sortV?.items?.any { it.key == k } == true })) }
    }
    PaymentSheet(
        row = sortItem?.row, order = v.categoryOrder, said = said,
        onCategory = { key ->
            sortItem?.let { it ->
                haptics.light()
                sortDone = sortDone + it.key
                scope.launch {
                    val r = runCatching { core.setMoneyCategory(it.row.id, key) }.getOrNull()
                    if (r == null || r.refused) sortDone = sortDone - it.key
                    r?.let(say)
                }
            }
        },
        onMark = { _, _ -> },
        onUndo = { u -> scope.launch { runCatching { core.undoMoneyCategory(u) }; haptics.light(); said = null; sortDone = emptySet() } },
        onClose = { sorting = false },
        own = v.ownCategories,
        onNewCategory = { name, tone, icon ->
            sortItem?.let { it ->
                sortDone = sortDone + it.key
                scope.launch { runCatching { core.addMoneyCategory(name, tone, icon.name, it.row.id) }.getOrNull()?.let(say) }
            }
        },
        sort = sortItem?.let { MoneySortHead(MoneySorter.head(sortLeft), it.line, MoneySorter.spoken(it, sortLeft)) },
        onSkip = { sortItem?.let { haptics.tick(); sortSkipped = sortSkipped + it.key } },
    )
    OwnCategorySheet(
        row = ownRow, own = v.ownCategories, said = said,
        onSave = { name, tone, icon -> ownRow?.let { o -> scope.launch { runCatching { core.editMoneyCategory(o.id, name, tone, icon.name) }.getOrNull()?.let(say) } } },
        onHide = { on -> ownRow?.let { o -> haptics.tick(); scope.launch { runCatching { core.hideMoneyCategory(o.id, on) }.getOrNull()?.let(say) } } },
        onMerge = { key -> ownRow?.let { o -> ownSheetId = null; scope.launch { runCatching { core.mergeMoneyCategory(o.id, key) }.getOrNull()?.let(say) } } },
        onDelete = { ownRow?.let { o -> ownSheetId = null; scope.launch { runCatching { core.deleteMoneyCategory(o.id) }.getOrNull()?.let(say) } } },
        onUndo = { u -> scope.launch { runCatching { core.undoMoneyCategory(u) }; haptics.light(); said = null } },
        onClose = { ownSheetId = null },
        onCap = { text -> ownRow?.let { o -> haptics.tick(); scope.launch { runCatching { core.capMoneyCategory(o.id, text) }.getOrNull()?.let(say) } } },
    )
    MoneyDetailSheet(cardRow, title = { it.name }, tag = "money-card-sheet", done = MoneyCards.DONE, onClose = { cardSheetId = null }) { c ->
        CardDetails(c, said,
            onPaid = { scope.launch { runCatching { core.moneyCardPaid(c.id) }.getOrNull()?.let(say) } },
            onField = { field, text -> scope.launch { runCatching { core.moneyCardField(c.id, field, text) }.getOrNull()?.let(say) } },
            onKind = { card, charge ->
                if (!card) cardSheetId = null
                scope.launch { runCatching { core.moneyCardKind(c.id, card, charge) }.getOrNull()?.let(say) }
            })
    }
    // Money review, Accounts: an account's sheet — its balance, It's a card, Side hustle account, Import and Remove.
    MoneyDetailSheet(accountSheet, title = { it.title }, tag = "money-account-sheet", done = MoneyAccountList.DONE, onClose = { accountSheetId = null }) { a ->
        MoneyAccountDetails(a, busy,
            onBalance = { text -> scope.launch { runCatching { core.moneyBalance(a.id, text) }.getOrNull()?.let(say) } },
            onCard = { on -> haptics.tick(); scope.launch { runCatching { core.moneyCardKind(a.id, on, charge = false) }.getOrNull()?.let(say) } },
            onSideHustle = { on -> haptics.tick(); scope.launch { runCatching { core.moneyBusinessAccount(a.id, on) }.getOrNull()?.let(say) } },
            onImport = {
                account = v.accounts.firstOrNull { it.id == a.id }?.name ?: a.title
                pick.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream"))
            },
            onRemove = {
                accountSheetId = null
                scope.launch { runCatching { core.removeMoneyAccount(a.id) }.getOrNull()?.let(say) }
            },
            onRename = { text -> haptics.tick(); scope.launch { runCatching { core.renameMoneyAccount(a.id, text) }.getOrNull()?.let(say) } })
        SheetSaid(said)
    }
    // Accounts' rest: the MORE card's sheets — the side hustle's tax year, the shared costs, and Import, export and delete
    // (the field and file picker for a statement, Export all payments, and Delete all money data, which asks first).
    val moreOpen = moreSheet?.takeIf { k ->
        when (k) { MoneyMoreKind.SIDE_HUSTLE -> v.business != null; MoneyMoreKind.SHARED_COSTS -> v.shared != null; MoneyMoreKind.DATA -> true }
    }
    MoneyDetailSheet(moreOpen, title = { MoneyMore.title(it, v) }, tag = "money-more-sheet", done = MoneyMore.DONE, onClose = { moreSheet = null }) { kind ->
        when (kind) {
            MoneyMoreKind.SIDE_HUSTLE -> v.business?.let { b ->
                BusinessBlock(b, Modifier,
                    onSetAside = { key -> haptics.tick(); scope.launch { runCatching { core.moneySetAside(key) }.getOrNull()?.let(say) } },
                    onExport = exportBusiness,
                    onBusinessTxn = { txnId -> haptics.tick(); scope.launch { runCatching { core.moneyBusinessMerchant(txnId, true) }.getOrNull()?.let(say) } })
            }
            MoneyMoreKind.SHARED_COSTS -> v.shared?.let { sh ->
                SharedBlock(sh, Modifier,
                    onShare = { key -> haptics.tick(); scope.launch { runCatching { core.moneySharedShare(key) }.getOrNull()?.let(say) } },
                    onAdd = { text -> scope.launch { runCatching { core.moneySharedAdd(text) }.getOrNull()?.let(say) } },
                    onRemove = { id -> scope.launch { runCatching { core.moneySharedRemove(id) }.getOrNull()?.let(say) } },
                    onSettle = { scope.launch { runCatching { core.moneySharedSettle() }.getOrNull()?.let(say) } })
            }
            MoneyMoreKind.DATA -> MoneyDataDetails(
                account = account, busy = busy,
                canDelete = v.accounts.isNotEmpty() || v.worth?.rows?.any { it.removable } == true,
                connectLater = v.bank == null,
                onAccount = { account = it },
                onPick = {
                    if (account.isBlank()) say(MoneySaved(MoneyRules.NO_ACCOUNT, refused = true))
                    else pick.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream"))
                },
                onExport = { haptics.tick(); exportAll() },
                onDeleteAll = {
                    moreSheet = null
                    scope.launch { runCatching { core.deleteAllMoney() }.getOrNull()?.let(say) }
                })
        }
        SheetSaid(said)
    }
    // Plan's goals card: a goal's sheet — where it stands, its pace and which account's balance it follows.
    MoneyDetailSheet(goalOpen, title = { it.title }, tag = "money-goal-sheet", done = MoneyGoals.DONE, onClose = { goalSheetId = null }) { r ->
        v.goals?.let { gv ->
            MoneyGoalDetails(r, gv) { accountId ->
                haptics.tick()
                scope.launch { runCatching { core.moneyGoalFollow(r.id, accountId) }.getOrNull()?.let(say) }
            }
        }
        SheetSaid(said)
    }
    // Layout rule (c): what an ⓘ says (the month in review's sentences, where Spending's numbers came from).
    MoneyDetailSheet(info, title = { it.title }, tag = "money-info-sheet", done = MoneyTabs.INFO_DONE, onClose = { info = null }) { i ->
        MoneyInfoLines(i)
    }
    // Net worth's typed things: each with Remove (Undo in the said line), and the field to add or update one.
    MoneyDetailSheet(otherOpen, title = { MoneyWorthOther.TITLE }, tag = "money-other-sheet", done = MoneyWorthOther.DONE, onClose = { otherSheet = false }) { w ->
        WorthOtherDetails(w,
            onAdd = { text -> scope.launch { runCatching { core.moneyWorthAdd(text) }.getOrNull()?.let(say) } },
            onRemove = { id -> haptics.tick(); scope.launch { runCatching { core.moneyWorthRemove(id) }.getOrNull()?.let(say) } })
        SheetSaid(said)
    }
    MoneyDetailSheet(paydayOpen, title = { it.head.lowercase().replaceFirstChar { ch -> ch.uppercase() } }, tag = "money-payday-sheet",
        done = MoneyPayday.DONE, onClose = { paydaySheet = false }) { p ->
        PaydayBlock(p, Modifier, withRows = MoneyTabs.paydayRows(v)) { text ->
            val id = p.account ?: return@PaydayBlock
            scope.launch { runCatching { core.moneyBalance(id, text) }.getOrNull()?.let(say) }
        }
        SheetSaid(said)
    }
    }
}

/** A sheet's own "said" line (the page's is under the dim): what the last write did, lit when refused. */
@Composable
private fun SheetSaid(said: MoneySaved?) {
    val reduced = Meka.reducedMotion
    Crossfade(said, animationSpec = MekaMotion.appear(reduced), label = "money-sheet-said") { s ->
        if (s != null) Text(s.line, style = MekaType.caption, color = if (s.refused) Meka.colors.accent else Meka.colors.textSecondary,
            modifier = Modifier.testTag("money-sheet-said"))
    }
}

/**
 * Money redesign (B), slice 1 (mockup v4, approved 2026-10-25): the five tabs as pills on one row that scrolls sideways
 * if the screen is narrow. The chosen one is lit (raised fill, gold label) and blends there on the appear spring; a tap
 * gives a tick haptic. Each pill is a tab for a screen reader ("Bills tab, selected") with a 48 dp target.
 */
@Composable
internal fun MoneyTabPills(chosen: MoneyTab, modifier: Modifier = Modifier, onPick: (MoneyTab) -> Unit) {
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MoneyTabs.ALL.forEach { t ->
            val lit = t == chosen
            val fill by animateColorAsState(if (lit) Meka.colors.surfaceRaised else Meka.colors.surface, MekaMotion.appear(reduced), label = "money-tab-${t.name}")
            val ink by animateColorAsState(if (lit) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "money-tab-ink-${t.name}")
            Box(
                Modifier.heightIn(min = MekaSpace.touch)
                    .clip(RoundedCornerShape(MekaRadius.pill))
                    .clickable(role = Role.Tab) { if (!lit) { haptics.tick(); onPick(t) } }
                    .clearAndSetSemantics { contentDescription = MoneyTabs.spoken(t, lit); selected = lit; role = Role.Tab },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    t.label, style = MekaType.itemMeta, color = ink, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(fill)
                        .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
                )
            }
        }
    }
}

/**
 * Money bugs (A5), Meka 2026-10-24: Import a statement's account chips were one Row, so a third account's chip was
 * squeezed to one letter per line. Now they wrap onto the next line (every account, not the first four), each chip one
 * line, a name too long for the width cut with "…" rather than broken.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ImportAccountChips(names: List<String>, chosen: String, onPick: (String) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        names.distinct().forEach { name ->
            val on = name == chosen
            Text(name, style = MekaType.caption, color = if (on) Meka.colors.accent else Meka.colors.textSecondary,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                    .clickable(role = Role.Button) { onPick(name) }.minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs))
        }
    }
}

/**
 * Bank sync: what Lunch Flow last gave (lit when something needs him), each account to reconnect, Refresh (Refreshing…
 * while the server hasn't been yet), Open Lunch Flow when lit, and Sync them again for removed bank accounts.
 */
@Composable
private fun BankBlock(b: MoneyBankView, modifier: Modifier, onRefresh: () -> Unit, onRestore: () -> Unit, onOpen: () -> Unit) {
    val reduced = Meka.reducedMotion
    val c by animateColorAsState(if (b.lit) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "money-bank-lit")
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Crossfade(b.line, animationSpec = MekaMotion.appear(reduced), label = "money-bank-line") { l ->
            Text(l, style = MekaType.body, color = c)
        }
        b.reconnect.forEach { r -> Text(r.line, style = MekaType.caption, color = Meka.colors.accent) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.m)) {
            Crossfade(b.refreshing, animationSpec = MekaMotion.appear(reduced), label = "money-bank-refresh") { busy ->
                Text(if (busy) MoneyBank.REFRESHING else MoneyBank.REFRESH, style = MekaType.itemMeta,
                    color = if (busy) Meka.colors.textTertiary else Meka.colors.accent,
                    modifier = Modifier.clickable(role = Role.Button, enabled = !busy) { onRefresh() }.minTouch())
            }
            if (b.lit) {
                Text(MoneyBank.OPEN, style = MekaType.itemMeta, color = Meka.colors.accent,
                    modifier = Modifier.clickable(role = Role.Button) { onOpen() }.minTouch())
            }
        }
        b.restoreLine?.let { r ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Text(r, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.weight(1f))
                Text(MoneyBank.RESTORE, style = MekaType.caption, color = Meka.colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { onRestore() }.minTouch().padding(MekaSpace.xs))
            }
        }
    }
}

/**
 * Trading tracker: equity and when it was read, P&L for today, the week, the month and all time (up in brass), the open
 * positions nearest liquidation first (lit within 10%), each address with Remove, and a field for a public address.
 */
@Composable
private fun TradingBlock(
    t: TradingView, modifier: Modifier, onAdd: (String) -> Unit, onRemove: (String) -> Unit, onAlert: (String, String) -> Unit,
    exporting: Boolean = false, onExport: (String) -> Unit = {},
) {
    val reduced = Meka.reducedMotion
    var adding by rememberSaveable { mutableStateOf("") }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        t.total?.let { total ->
            Column(Modifier.semantics(mergeDescendants = true) { contentDescription = t.spoken }, verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                Crossfade(total, animationSpec = MekaMotion.appear(reduced), label = "trading-total") { l ->
                    Text(l, style = MekaType.upNextTitle, color = Meka.colors.textPrimary)
                }
                t.totalLine?.let { l -> Text(l, style = MekaType.caption, color = Meka.colors.textSecondary) }
            }
        }
        t.empty?.let { e -> Text(e, style = MekaType.caption, color = Meka.colors.textSecondary) }
        t.currency?.let { c -> CurrencyRow(c, onAlert) }
        t.pnl.forEach { r ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(r.label, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.weight(1f))
                Crossfade(r.amount, animationSpec = MekaMotion.appear(reduced), label = "trading-pnl-${r.label}") { a ->
                    Text(a, style = MekaType.body, color = if (r.up == true) Meka.colors.accent else Meka.colors.textPrimary)
                }
            }
        }
        t.unrealisedLine?.let { l -> Text(l, style = MekaType.caption, color = Meka.colors.textSecondary) }
        t.positions.forEach { p -> key(p.id) {
            val c by animateColorAsState(if (p.lit) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "trading-liq-${p.id}")
            Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = p.spoken },
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.title, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
                    Text(p.amount, style = MekaType.body, color = if (p.up == true) Meka.colors.accent else Meka.colors.textPrimary)
                }
                if (p.line.isNotEmpty()) Text(p.line, style = MekaType.caption, color = Meka.colors.textSecondary)
                p.liquidationLine?.let { l -> Text(l, style = MekaType.caption, color = c) }
            }
        } }
        t.curve?.let { c -> CurveBlock(c) }
        t.trades?.let { tr -> TradesBlock(tr) }
        t.review?.let { r -> ReviewBlock(r) }
        t.wallets.forEach { w ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = w.name + ". " + w.line }) {
                    Text(w.name, style = MekaType.body, color = Meka.colors.textPrimary)
                    Text(w.line, style = MekaType.caption, color = if (w.lit) Meka.colors.accent else Meka.colors.textSecondary)
                }
                Text("Remove", style = MekaType.caption, color = Meka.colors.textTertiary,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { onRemove(w.id) }
                        .semantics { contentDescription = "Stop following ${w.name}" }.minTouch().padding(MekaSpace.xs))
            }
        }
        Field(t.hint, value = adding, onValue = { adding = it }) { adding = ""; onAdd(it) }
        t.alerts?.let { a -> AlertsBlock(a, onAlert) }
        t.export?.let { e -> ExportBlock(e, exporting, onExport) }
        Text(t.foot, style = MekaType.caption, color = Meka.colors.textTertiary)
    }
}

/**
 * Trading tracker, slice 12: SHOW IN · Dollars · Pounds (synced through the alerts setting), with a line saying what it
 * means; the chosen chip in brass, the line cross-fading when it changes.
 */
@Composable
private fun CurrencyRow(c: TradingCurrencyView, onPick: (String, String) -> Unit) {
    val reduced = Meka.reducedMotion
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            Text(c.label, style = MekaType.caption, color = Meka.colors.textSecondary)
            c.chips.forEach { ch -> key(ch.kind + ch.key) {
                val col by animateColorAsState(if (ch.on) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "trading-ccy-${ch.key}")
                Text(ch.label, style = MekaType.caption, color = col,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
                        .clickable(role = Role.RadioButton) { if (!ch.on) onPick(ch.kind, ch.key) }
                        .semantics { contentDescription = ch.spoken }.minTouch()
                        .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs))
            } }
        }
        Crossfade(c.line, animationSpec = MekaMotion.appear(reduced), label = "trading-ccy-line") { l ->
            Text(l, style = MekaType.caption, color = Meka.colors.textTertiary)
        }
    }
}

/**
 * Trading tracker, slice 3: ALERTS — what he's told and when (near liquidation, even in quiet hours; a drop from the
 * month's high; slice 7: a big order and a day's high funding), with a row of choices for each; the chosen one in brass.
 */
@Composable
private fun AlertsBlock(a: TradingAlertsView, onAlert: (String, String) -> Unit) {
    val reduced = Meka.reducedMotion
    Column(Modifier.fillMaxWidth().padding(top = MekaSpace.m), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Text(a.head, style = MekaType.caption, color = Meka.colors.textTertiary)
        Crossfade(a.line, animationSpec = MekaMotion.appear(reduced), label = "trading-alerts-line") { l ->
            Text(l, style = MekaType.caption, color = Meka.colors.textSecondary)
        }
        listOf(a.liqLabel to a.liqChips, a.dropLabel to a.dropChips, a.fillLabel to a.fillChips, a.fundingLabel to a.fundingChips)
            .filter { it.second.isNotEmpty() }.forEach { (label, chips) ->
            Text(label, style = MekaType.body, color = Meka.colors.textPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                chips.forEach { c -> key(c.kind + c.key) {
                    val col by animateColorAsState(if (c.on) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "trading-alert-${c.kind}-${c.key}")
                    Text(c.label, style = MekaType.caption, color = col,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
                            .clickable(role = Role.RadioButton) { if (!c.on) onAlert(c.kind, c.key) }
                            .semantics { contentDescription = c.spoken }.minTouch()
                            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs))
                } }
            }
        }
    }
}

/**
 * Trading tracker, slice 8: EXPORT FOR TAX — a chip per UK tax year; while the server reads the year the chips dim and
 * the line says so, then the system's save sheet asks where the CSV goes.
 */
@Composable
private fun ExportBlock(e: TradingExportView, exporting: Boolean, onExport: (String) -> Unit) {
    val reduced = Meka.reducedMotion
    Column(Modifier.fillMaxWidth().padding(top = MekaSpace.m), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Text(e.head, style = MekaType.caption, color = Meka.colors.textTertiary)
        Crossfade(if (exporting) TradingExportRules.WORKING else e.line, animationSpec = MekaMotion.appear(reduced), label = "trading-export-line") { l ->
            Text(l, style = MekaType.caption, color = Meka.colors.textSecondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            e.chips.forEach { c -> key(c.key) {
                val col by animateColorAsState(if (exporting) Meka.colors.textTertiary else Meka.colors.accent, MekaMotion.appear(reduced), label = "trading-export-${c.key}")
                Text(c.label, style = MekaType.caption, color = col,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
                        .clickable(role = Role.Button, enabled = !exporting) { onExport(c.key) }
                        .semantics { contentDescription = c.spoken }.minTouch()
                        .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs))
            } }
        }
    }
}

/**
 * Trading tracker, slice 4: P&L · LAST 30 DAYS — what the month's P&L comes to (up in brass), the high and low, and the
 * line day by day ending at Now, drawn once left to right; tap a day for its figure.
 */
@Composable
private fun CurveBlock(c: TradingCurveView) {
    val reduced = Meka.reducedMotion
    Column(Modifier.fillMaxWidth().padding(top = MekaSpace.m), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Text(c.head, style = MekaType.caption, color = Meka.colors.textTertiary)
        Crossfade(c.summary, animationSpec = MekaMotion.appear(reduced), label = "trading-curve-summary") { l ->
            Text(l, style = MekaType.body, color = if (c.up == true) Meka.colors.accent else Meka.colors.textPrimary,
                modifier = Modifier.semantics { contentDescription = c.spoken })
        }
        c.rangeLine?.let { l -> Text(l, style = MekaType.caption, color = Meka.colors.textSecondary) }
        WorthLine(c.points, dots = false)
        Text(c.foot, style = MekaType.caption, color = Meka.colors.textTertiary)
    }
}

/**
 * Trading tracker, slice 2: YOUR TRADES · LAST 30 DAYS — trades closed and won, closed P&L, fees, funding and what's left
 * after them (up in brass), the average win and loss, the best and worst trade, and each coin (biggest result first).
 */
@Composable
private fun TradesBlock(tr: TradingTradesView) {
    val reduced = Meka.reducedMotion
    Column(Modifier.fillMaxWidth().padding(top = MekaSpace.m), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Text(tr.head, style = MekaType.caption, color = Meka.colors.textTertiary)
        tr.empty?.let { e -> Text(e, style = MekaType.caption, color = Meka.colors.textSecondary) }
        tr.summary?.let { s ->
            Crossfade(s, animationSpec = MekaMotion.appear(reduced), label = "trades-summary") { l ->
                Text(l, style = MekaType.body, color = Meka.colors.textPrimary,
                    modifier = Modifier.semantics { contentDescription = tr.spoken })
            }
        }
        tr.lines.forEach { r -> key("trades-${r.label}") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(r.label, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.weight(1f))
                Crossfade(r.amount, animationSpec = MekaMotion.appear(reduced), label = "trades-line-${r.label}") { a ->
                    Text(a, style = MekaType.body, color = if (r.up == true) Meka.colors.accent else Meka.colors.textPrimary)
                }
            }
        } }
        tr.averageLine?.let { l -> Text(l, style = MekaType.caption, color = Meka.colors.textSecondary) }
        tr.bestLine?.let { l -> Text(l, style = MekaType.caption, color = Meka.colors.textSecondary) }
        tr.coins.forEach { c -> key(c.id) {
            Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = c.spoken },
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.title, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
                    Crossfade(c.amount, animationSpec = MekaMotion.appear(reduced), label = "trades-coin-${c.id}") { a ->
                        Text(a, style = MekaType.body, color = if (c.up == true) Meka.colors.accent else Meka.colors.textPrimary)
                    }
                }
                Text(c.line, style = MekaType.caption, color = Meka.colors.textSecondary)
            }
        } }
        tr.moreLine?.let { l -> Text(l, style = MekaType.caption, color = Meka.colors.textTertiary) }
        Text(tr.foot, style = MekaType.caption, color = Meka.colors.textTertiary)
    }
}

/** Trading tracker, slice 9: last month in review, plain sentences; a new month's cross-fades in (MekaMotion.appear honours reduced motion). */
@Composable
private fun ReviewBlock(r: TradingReviewView) {
    val reduced = Meka.reducedMotion
    Column(Modifier.fillMaxWidth().padding(top = MekaSpace.m).semantics(mergeDescendants = true) { contentDescription = r.spoken },
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Text(r.head, style = MekaType.caption, color = Meka.colors.textTertiary)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(r.summary.substringBefore(" · "), style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
            r.amount?.let { a ->
                Crossfade(a, animationSpec = MekaMotion.appear(reduced), label = "review-amount") { x ->
                    Text(x, style = MekaType.body, color = if (r.up == true) Meka.colors.accent else Meka.colors.textPrimary)
                }
            }
        }
        if (r.sentences.isEmpty()) Text(r.summary, style = MekaType.caption, color = Meka.colors.textSecondary)
        Crossfade(r.sentences, animationSpec = MekaMotion.appear(reduced), label = "review-sentences") { lines ->
            Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                lines.forEach { line -> Text(line, style = MekaType.body, color = Meka.colors.textSecondary) }
            }
        }
        Text(r.foot, style = MekaType.caption, color = Meka.colors.textTertiary)
    }
}

@Composable
private fun Arrow(glyph: String, spoken: String, enabled: Boolean, onClick: () -> Unit) {
    Text(glyph, style = MekaType.itemTitle, color = if (enabled) Meka.colors.accent else Meka.colors.textTertiary,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, enabled = enabled, onClick = onClick)
            .semantics { contentDescription = spoken }.minTouch().padding(horizontal = MekaSpace.m))
}

@Composable
private fun CategoryRow(c: MoneyCategoryRow, modifier: Modifier) {
    val reduced = Meka.reducedMotion
    var shown by remember { mutableStateOf(if (reduced) c.share else 0f) }
    LaunchedEffect(c.share) { shown = c.share }
    val share by animateFloatAsState(shown, MekaMotion.appear(reduced), label = "money-bar-${c.key}")
    val bar by animateColorAsState(if (c.over) Meka.colors.accent else Meka.colors.textTertiary, MekaMotion.appear(reduced), label = "money-bar-c-${c.key}")
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = c.spoken },
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.label, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
            Text(c.amount, style = MekaType.body, color = Meka.colors.textPrimary)
        }
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)) {
            Box(Modifier.fillMaxWidth(share.coerceIn(0f, 1f)).height(4.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(bar))
        }
        Text(c.line, style = MekaType.caption, color = if (c.over) Meka.colors.accent else Meka.colors.textSecondary)
    }
}

@Composable
private fun TxnRow(
    t: MoneyTxnRow, modifier: Modifier,
    /** Money redesign (B2): the category's icon chip in its colour this month, and whether the sub-line names the day. */
    chip: Pair<os.meka.core.domain.MoneyIcon, Int>? = null, showDay: Boolean = true,
    /** The payment sheet (Meka 2026-10-26 23:18): a tap opens it ([PaymentSheet]), never a dropdown. */
    onOpen: () -> Unit,
) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = t.spoken + ". Open to change its category" }
            .minTouch().padding(vertical = MekaSpace.xs).testTag("money-txn-${t.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (chip != null) Arrangement.spacedBy(MekaSpace.m) else Arrangement.Start,
    ) {
        chip?.let { (icon, tone) -> MoneyIconChip(icon, tone) }
        Column(Modifier.weight(1f)) {
            Text(t.merchant, style = if (chip != null) MekaType.taskTitle else MekaType.body, color = Meka.colors.textPrimary)
            Text((if (showDay) "${t.day} · " else "") + t.categoryLabel + (if (t.taught) " · yours" else "") + (if (t.business) " · Business" else "") + (if (t.shared) " · Shared" else "") + " · ${t.account}",
                style = MekaType.caption, color = Meka.colors.textSecondary)
            // Money at a glance: what the payment was for, from its receipt or order email (the shop's words).
            t.receipt?.let { r -> Text(r, style = MekaType.caption, color = Meka.colors.textSecondary) }
            t.receiptDetail?.let { d -> Text(d, style = MekaType.caption, color = Meka.colors.textTertiary, maxLines = 2) }
        }
        Text(t.amount, style = MekaType.body, color = if (t.moneyIn) Meka.colors.accent else Meka.colors.textPrimary)
    }
}

/**
 * Slice 13: SIDE HUSTLE AND TAX — the tax year so far (in, out, profit in brass), the share to put aside with its chips,
 * the trading allowance, the biggest costs, last tax year, the next Self Assessment dates (lit within 30 days) and each
 * account's Side hustle account switch. Figures cross-fade as they change (MekaMotion.appear honours reduced motion).
 */
@Composable
private fun BusinessBlock(
    b: MoneyBusinessView, modifier: Modifier, onSetAside: (String) -> Unit,
    onExport: (String) -> Unit = {}, onBusinessTxn: (String) -> Unit = {},
) {
    val reduced = Meka.reducedMotion
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        b.intro?.let { Text(it, style = MekaType.caption, color = Meka.colors.textSecondary) }
        b.profitLine?.let { profit ->
            Column(Modifier.semantics(mergeDescendants = true) { contentDescription = b.spoken }, verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                b.yearLine?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
                Crossfade(profit, animationSpec = MekaMotion.appear(reduced), label = "money-biz-profit") { l ->
                    Text(l, style = MekaType.itemTitle, color = if (b.profitUp) Meka.colors.accent else Meka.colors.textPrimary)
                }
                Crossfade(listOfNotNull(b.inLine, b.outLine).joinToString("\n"), animationSpec = MekaMotion.appear(reduced), label = "money-biz-inout") { l ->
                    Text(l, style = MekaType.caption, color = Meka.colors.textSecondary)
                }
            }
            b.setAsideLine?.let { l ->
                Crossfade(l, animationSpec = MekaMotion.appear(reduced), label = "money-biz-aside") { x ->
                    Text(x, style = MekaType.body, color = Meka.colors.textPrimary)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                b.chips.forEach { c ->
                    val col by animateColorAsState(if (c.on) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "money-biz-chip-${c.key}")
                    Text(c.label, style = MekaType.caption, color = col,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                            .clickable(role = Role.Button) { if (!c.on) onSetAside(c.key) }
                            .semantics { contentDescription = "Put aside ${c.label}" + if (c.on) ", chosen" else "" }
                            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs))
                }
            }
            b.allowanceLine?.let { Text(it, style = MekaType.caption, color = Meka.colors.textSecondary) }
            b.costs.forEach { c ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.label, style = MekaType.body, color = Meka.colors.textPrimary)
                        Text(c.line, style = MekaType.caption, color = Meka.colors.textSecondary)
                    }
                    Text(c.amount, style = MekaType.body, color = Meka.colors.textPrimary)
                }
            }
            b.lastYearLine?.let { Text(it, style = MekaType.caption, color = Meka.colors.textSecondary) }
            // Slice 13b: YEAR-END EXPORT — a chip per tax year, then the save sheet.
            b.export?.let { e -> ExportBlock(e, exporting = false, onExport = onExport) }
            b.dates.forEach { d ->
                Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                    Text(d.line, style = MekaType.body, color = if (d.lit) Meka.colors.accent else Meka.colors.textPrimary)
                    Text(d.title, style = MekaType.caption, color = Meka.colors.textSecondary)
                }
            }
        }
        // Slice 13c: FROM YOUR BUSINESS EMAIL — this tax year's money in his Business mail against the statements.
        b.mail?.let { m -> BusinessMailBlock(m, onBusinessTxn) }
        b.merchantsLine?.let { Text(it, style = MekaType.caption, color = Meka.colors.textSecondary) }
        // Money review, Accounts: "Side hustle account" is a switch in each account's sheet now.
        Text(b.foot, style = MekaType.caption, color = Meka.colors.textTertiary)
    }
}

/**
 * Slice 13c: FROM YOUR BUSINESS EMAIL — each email's money with whether the statements show it (lit when they should by
 * now and don't), its subject as a quote, and a Business chip that counts the matched payment's payer as the side
 * hustle's. Lines cross-fade as a match lands (MekaMotion.appear honours reduced motion).
 */
@Composable
private fun BusinessMailBlock(m: MoneyBusinessMailView, onBusinessTxn: (String) -> Unit) {
    val reduced = Meka.reducedMotion
    Column(Modifier.fillMaxWidth().padding(top = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Text(m.head, style = MekaType.caption, color = Meka.colors.textTertiary)
        Crossfade(m.line, animationSpec = MekaMotion.appear(reduced), label = "money-biz-mail-line") { l ->
            Text(l, style = MekaType.caption, color = Meka.colors.textSecondary)
        }
        m.rows.forEach { r ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = r.spoken }) {
                    Text(r.title, style = MekaType.body, color = Meka.colors.textPrimary)
                    Crossfade(r.line, animationSpec = MekaMotion.appear(reduced), label = "money-biz-mail-${r.id}") { l ->
                        Text(l, style = MekaType.caption, color = if (r.lit) Meka.colors.accent else Meka.colors.textSecondary)
                    }
                    r.quote?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                r.businessTxnId?.let { txn ->
                    Text(MoneyBusinessMail.CHIP, style = MekaType.caption, color = Meka.colors.textSecondary,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                            .clickable(role = Role.Button) { onBusinessTxn(txn) }
                            .semantics { contentDescription = MoneyBusinessMail.chipSpoken(r) }
                            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs))
                }
            }
        }
        m.moreLine?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
        Text(m.foot, style = MekaType.caption, color = Meka.colors.textTertiary)
    }
}

/**
 * Slice 15: CHALLENGES — the No-spend days switch and, when on, the month's days as dots (brass = no spending) with the
 * count and the streak; fun money for the week and each category cap as a bar (brass from 90%) with what's left and the
 * run; fields to set them. Bars grow on the appear spring and lines cross-fade (MekaMotion.appear honours reduced motion).
 */
@Composable
private fun ChallengesBlock(
    ch: MoneyChallengesView, modifier: Modifier, onNoSpend: (Boolean) -> Unit, onFun: (String) -> Unit, onCap: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val reduced = Meka.reducedMotion
    var funText by rememberSaveable { mutableStateOf("") }
    var capText by rememberSaveable { mutableStateOf("") }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        ch.intro?.let { Text(it, style = MekaType.caption, color = Meka.colors.textSecondary) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(MoneyChallenges.SWITCH, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
            Text(if (ch.noSpendOn) "On ✓" else "Count them", style = MekaType.caption,
                color = if (ch.noSpendOn) Meka.colors.accent else Meka.colors.textSecondary,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Switch) { onNoSpend(!ch.noSpendOn) }
                    .semantics { contentDescription = MoneyChallenges.SWITCH + if (ch.noSpendOn) ", on" else ", off" }
                    .minTouch().padding(MekaSpace.xs))
        }
        ch.noSpend?.let { ns ->
            Column(Modifier.semantics(mergeDescendants = true) { contentDescription = ns.spoken }, verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                if (ns.days.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { // rhythm: ok (the month's day dots are one mark)
                        ns.days.forEachIndexed { i, free ->
                            val c by animateColorAsState(if (free) Meka.colors.accent else Meka.colors.surfaceRaised, MekaMotion.appear(reduced), label = "money-nospend-$i")
                            Box(Modifier.size(7.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(c))
                        }
                    }
                }
                Crossfade(ns.line, animationSpec = MekaMotion.appear(reduced), label = "money-nospend-line") { l ->
                    Text(l, style = MekaType.body, color = Meka.colors.textPrimary)
                }
                ns.streakLine?.let { st ->
                    Crossfade(st, animationSpec = MekaMotion.appear(reduced), label = "money-nospend-streak") { l ->
                        Text(l, style = MekaType.caption, color = if (ns.lit) Meka.colors.accent else Meka.colors.textSecondary)
                    }
                }
            }
        }
        ch.funMoney?.let { ChallengeBar(it, onRemove) }
        ch.caps.forEach { ChallengeBar(it, onRemove) }
        ch.throughLine?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
        Field(ch.funHint, value = funText, onValue = { funText = it }) { funText = ""; onFun(it) }
        Field(ch.capHint, value = capText, onValue = { capText = it }) { capText = ""; onCap(it) }
        Text(ch.foot, style = MekaType.caption, color = Meka.colors.textTertiary)
    }
}

/**
 * Slice 17: PAYING ABROAD — the tip (brass when there's a card with no foreign fees, or he's going), then each account:
 * its name and a line, a brass dot by one that charged no foreign fees. Lines cross-fade as they change (reduced motion
 * honoured by MekaMotion.appear).
 */
@Composable
private fun TravelBlock(tv: os.meka.core.domain.MoneyTravelView, modifier: Modifier) {
    val reduced = Meka.reducedMotion
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        tv.tip?.let { t ->
            val c by animateColorAsState(if (tv.tipLit) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "money-travel-tip")
            Crossfade(t, animationSpec = MekaMotion.appear(reduced), label = "money-travel-tip-line") { l ->
                Text(l, style = MekaType.body, color = c)
            }
        }
        tv.rows.forEach { r ->
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = r.spoken },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                val dot by animateColorAsState(if (r.good) Meka.colors.accent else Meka.colors.surfaceRaised, MekaMotion.appear(reduced), label = "money-travel-dot-${r.id}")
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(dot))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                    Text(r.name, style = MekaType.body, color = Meka.colors.textPrimary)
                    Crossfade(r.line, animationSpec = MekaMotion.appear(reduced), label = "money-travel-line-${r.id}") { l ->
                        Text(l, style = MekaType.caption, color = Meka.colors.textSecondary)
                    }
                }
            }
        }
        Text(tv.foot, style = MekaType.caption, color = Meka.colors.textTertiary)
    }
}

/**
 * Slice 18: the year in review — the folded line with Open/Fold (open by itself for January's finished year), then what
 * was spent and came in, what was left over (brass when more went out), against the year before, each category with its
 * share as a bar (brass when well up on the year before), the busiest and quietest months and the biggest payment. Unfolds
 * on the expand spring; the bars grow on appear (reduced motion: cross-fades, bars shown at once).
 */
@Composable
private fun YearBlock(yv: os.meka.core.domain.MoneyYearView, modifier: Modifier, onToggle: () -> Unit) {
    val reduced = Meka.reducedMotion
    var open by rememberSaveable(yv.year, yv.unfolded) { mutableStateOf(yv.unfolded) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        // Quality gate slice 13: the head above already says "2026 SO FAR", so the line is the title alone, and the row
        // isn't clipped to a rounded shape (a two-line title on the cover screen lost its first letters to the corners).
        Row(Modifier.fillMaxWidth().minTouch()
            .clickable(role = Role.Button) { open = !open; onToggle() }
            .semantics(mergeDescendants = true) { contentDescription = if (open) "${yv.spoken}. Fold" else "${yv.folded}. Open" },
            verticalAlignment = Alignment.CenterVertically) {
            Text(yv.title, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f).padding(end = MekaSpace.xs))
            Crossfade(if (open) "Fold" else "Open", animationSpec = MekaMotion.appear(reduced), label = "money-year-toggle") { l ->
                Text(l, style = MekaType.body, color = Meka.colors.accent)
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = open,
            enter = if (reduced) androidx.compose.animation.fadeIn(MekaMotion.appear(true))
                else androidx.compose.animation.expandVertically(MekaMotion.expand(false)) + androidx.compose.animation.fadeIn(MekaMotion.appear(false)),
            exit = if (reduced) androidx.compose.animation.fadeOut(MekaMotion.appear(true))
                else androidx.compose.animation.shrinkVertically(MekaMotion.expand(false)) + androidx.compose.animation.fadeOut(MekaMotion.appear(false)),
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                yv.leftLine?.let { Text(it, style = MekaType.body, color = if (yv.leftLit) Meka.colors.accent else Meka.colors.textSecondary) }
                yv.vsLine?.let { Text(it, style = MekaType.caption, color = if (yv.vsLit) Meka.colors.accent else Meka.colors.textSecondary) }
                yv.categories.forEach { c ->
                    CategoryRow(MoneyCategoryRow(c.key, c.label, c.amount, c.share, c.line, c.lit, c.spoken), Modifier)
                }
                yv.monthsLine?.let { Text(it, style = MekaType.caption, color = Meka.colors.textSecondary) }
                yv.biggest?.let { Text(it, style = MekaType.caption, color = Meka.colors.textSecondary) }
                Text(yv.foot, style = MekaType.caption, color = Meka.colors.textTertiary)
            }
        }
    }
}

/** Slice 15: one allowance or cap — its title and amount, a bar that grows to its share (brass from 90%), what's left and Remove. */
@Composable
internal fun ChallengeBar(b: MoneyChallengeBar, onRemove: (String) -> Unit) {
    val reduced = Meka.reducedMotion
    var shown by remember { mutableStateOf(if (reduced) b.share else 0f) }
    LaunchedEffect(b.share) { shown = b.share }
    val share by animateFloatAsState(shown, MekaMotion.appear(reduced), label = "money-chal-bar-${b.key}")
    val bar by animateColorAsState(if (b.lit) Meka.colors.accent else Meka.colors.textTertiary, MekaMotion.appear(reduced), label = "money-chal-c-${b.key}")
    Column(Modifier.fillMaxWidth().padding(top = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Row(Modifier.semantics(mergeDescendants = true) { contentDescription = b.spoken }, verticalAlignment = Alignment.CenterVertically) {
            Text(b.title, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
            Crossfade(b.amount, animationSpec = MekaMotion.appear(reduced), label = "money-chal-amount-${b.key}") { a ->
                Text(a, style = MekaType.body, color = Meka.colors.textPrimary)
            }
        }
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)) {
            Box(Modifier.fillMaxWidth(share.coerceIn(0f, 1f)).height(4.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(bar))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Crossfade(b.line, animationSpec = MekaMotion.appear(reduced), label = "money-chal-line-${b.key}", modifier = Modifier.weight(1f)) { l ->
                Text(l, style = MekaType.caption, color = if (b.lit) Meka.colors.accent else Meka.colors.textSecondary)
            }
            Text("Remove", style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { onRemove(b.key) }
                    .semantics { contentDescription = "Remove ${b.title}" }.minTouch().padding(MekaSpace.xs))
        }
    }
}

/**
 * Slice 14: SHARED WITH <PARTNER> — since when, what each paid, the split with its chips, who owes whom (in brass when
 * someone does), this month's shared total, what the partner paid (Remove), a field to type it and Settled up. Lines
 * cross-fade as they change (MekaMotion.appear honours reduced motion).
 */
@Composable
private fun SharedBlock(
    sh: MoneySharedView, modifier: Modifier, onShare: (String) -> Unit, onAdd: (String) -> Unit, onRemove: (String) -> Unit,
    onSettle: () -> Unit,
) {
    val reduced = Meka.reducedMotion
    var adding by rememberSaveable { mutableStateOf("") }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        sh.intro?.let { Text(it, style = MekaType.caption, color = Meka.colors.textSecondary) }
        sh.balanceLine?.let { bal ->
            Column(Modifier.semantics(mergeDescendants = true) { contentDescription = sh.spoken }, verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                sh.sinceLine?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
                val col by animateColorAsState(if (sh.balanceLit) Meka.colors.accent else Meka.colors.textPrimary, MekaMotion.appear(reduced), label = "money-shared-bal")
                Crossfade(bal, animationSpec = MekaMotion.appear(reduced), label = "money-shared-balance") { l ->
                    Text(l, style = MekaType.itemTitle, color = col)
                }
                Crossfade(listOfNotNull(sh.youLine, sh.partnerLine, sh.splitLine).joinToString("\n"), animationSpec = MekaMotion.appear(reduced), label = "money-shared-lines") { l ->
                    Text(l, style = MekaType.caption, color = Meka.colors.textSecondary)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                sh.chips.forEach { c ->
                    val col by animateColorAsState(if (c.on) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "money-shared-chip-${c.key}")
                    Text(c.label, style = MekaType.caption, color = col,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                            .clickable(role = Role.Button) { if (!c.on) onShare(c.key) }
                            .semantics { contentDescription = "Your share ${c.label}" + if (c.on) ", chosen" else "" }
                            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs))
                }
            }
            sh.settle?.let { label ->
                Text(label, style = MekaType.caption, color = Meka.colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onSettle)
                        .semantics { contentDescription = "$label with ${sh.partner}" }.minTouch().padding(MekaSpace.xs))
            }
            sh.monthLine?.let { l ->
                Crossfade(l, animationSpec = MekaMotion.appear(reduced), label = "money-shared-month") { x ->
                    Text(x, style = MekaType.caption, color = Meka.colors.textSecondary)
                }
            }
        }
        sh.rows.forEach { r ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = r.spoken }) {
                    Text(r.name, style = MekaType.body, color = Meka.colors.textPrimary)
                    Text(r.line, style = MekaType.caption, color = Meka.colors.textSecondary)
                }
                Text(r.amount, style = MekaType.body, color = Meka.colors.textPrimary)
                Text("Remove", style = MekaType.caption, color = Meka.colors.textTertiary,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { onRemove(r.id) }
                        .semantics { contentDescription = "Remove ${r.name} from what was paid" }.minTouch().padding(MekaSpace.xs))
            }
        }
        sh.merchantsLine?.let { Text(it, style = MekaType.caption, color = Meka.colors.textSecondary) }
        Field(sh.hint, value = adding, onValue = { adding = it }) { adding = ""; onAdd(it) }
        Text(sh.foot, style = MekaType.caption, color = Meka.colors.textTertiary)
    }
}

@Composable
private fun TipCard(t: MoneyTip, modifier: Modifier, onHide: () -> Unit) {
    val reduced = Meka.reducedMotion
    val c by animateColorAsState(if (t.lit) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "money-tip-${t.id}")
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised).padding(MekaSpace.m),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Column(Modifier.semantics(mergeDescendants = true) { contentDescription = t.spoken }) {
            Text(t.title, style = MekaType.body, color = Meka.colors.textPrimary)
            Text(t.line, style = MekaType.caption, color = c)
        }
        Text(MoneyTips.HIDE, style = MekaType.caption, color = Meka.colors.textTertiary,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onHide).minTouch())
    }
}

@Composable
private fun RegularRow(r: MoneyRegularRow, modifier: Modifier) {
    Row(modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = r.spoken }.padding(vertical = MekaSpace.xs),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(r.merchant, style = MekaType.body, color = Meka.colors.textPrimary)
            Text(r.line, style = MekaType.caption, color = if (r.lit) Meka.colors.accent else Meka.colors.textSecondary)
        }
        Text(r.amount, style = MekaType.body, color = Meka.colors.textPrimary)
    }
}

/** Slice 5: one refund — Close it when it's back in his statement, the chase to copy when it's late (he sends it). */
@Composable
private fun RefundCard(r: MoneyRefundRow, modifier: Modifier, onClose: () -> Unit, onCopy: (String) -> Unit) {
    val reduced = Meka.reducedMotion
    val c by animateColorAsState(if (r.lit) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "money-refund-${r.id}")
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised).padding(MekaSpace.m),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Column(Modifier.semantics(mergeDescendants = true) { contentDescription = r.spoken }) {
            Text(r.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
            Crossfade(r.line, animationSpec = MekaMotion.appear(reduced), label = "money-refund-line-${r.id}") { l ->
                Text(l, style = MekaType.caption, color = c)
            }
        }
        if (r.state == MoneyRefunds.BACK) {
            Text(MoneyRefunds.CLOSE, style = MekaType.caption, color = Meka.colors.accent,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onClose).minTouch())
        }
        r.chase?.let { chase ->
            Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                Text(chase, style = MekaType.caption, color = Meka.colors.textSecondary)
                Text(MoneyRefunds.COPY, style = MekaType.caption, color = Meka.colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { onCopy(chase) }.minTouch())
                Text(MoneyRefunds.NOTE, style = MekaType.caption, color = Meka.colors.textTertiary)
            }
        }
    }
}

/**
 * Slice 7, in the card's sheet (Money review, Meka 2026-10-26 22:12: "collapse to one card row that opens a sheet"): due
 * (lit three days out) with Paid, the statement and minimum, the limit used, 0% ending and fees; then the fields typed off
 * the statement, Charge card and Not a card, and the foot.
 */
@Composable
private fun CardDetails(
    c: MoneyCardRow, said: MoneySaved?, onPaid: () -> Unit, onField: (String, String) -> Unit, onKind: (Boolean, Boolean) -> Unit,
) {
    val reduced = Meka.reducedMotion
    var texts by remember(c.id) { mutableStateOf(mapOf<String, String>()) }
    val dueColor by animateColorAsState(if (c.dueLit) Meka.colors.accent else Meka.colors.textPrimary, MekaMotion.appear(reduced), label = "money-card-due-${c.id}")
    val useColor by animateColorAsState(if (c.useLit) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "money-card-use-${c.id}")
    val promoColor by animateColorAsState(if (c.promoLit) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "money-card-promo-${c.id}")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Column(Modifier.semantics(mergeDescendants = true) { contentDescription = c.spoken }, verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            Text(c.kindLine, style = MekaType.caption, color = Meka.colors.textTertiary)
            Crossfade(c.dueLine, animationSpec = MekaMotion.appear(reduced), label = "money-card-due-line-${c.id}") { l ->
                Text(l, style = MekaType.body, color = dueColor)
            }
            c.paidLine?.let { p -> Text(p, style = MekaType.caption, color = Meka.colors.textSecondary) }
            c.statementLine?.let { l ->
                Crossfade(l, animationSpec = MekaMotion.appear(reduced), label = "money-card-statement-${c.id}") { t ->
                    Text(t, style = MekaType.caption, color = Meka.colors.textSecondary)
                }
            }
            c.useLine?.let { l ->
                Crossfade(l, animationSpec = MekaMotion.appear(reduced), label = "money-card-use-line-${c.id}") { t ->
                    Text(t, style = MekaType.caption, color = useColor)
                }
            }
            c.promoLine?.let { l -> Text(l, style = MekaType.caption, color = promoColor) }
            c.feesLine?.let { l -> Text(l, style = MekaType.caption, color = Meka.colors.accent) }
        }
        if (c.canPay) {
            Text(MoneyCards.PAID, style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onPaid)
                    .semantics { contentDescription = "Mark ${c.name}'s payment paid" }.minTouch().testTag("money-card-paid"))
        }
        SheetSaid(said)
        c.fields.forEach { f ->
            val hint = if (f.value.isEmpty()) f.hint else "${f.label} · now ${f.value}"
            Field(hint, value = texts[f.key] ?: "", onValue = { t -> texts = texts + (f.key to t) }) { t ->
                texts = texts - f.key
                onField(f.key, t)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.m)) {
            Text(MoneyCards.CHARGE_SWITCH + if (c.charge) " ✓" else "", style = MekaType.caption,
                color = if (c.charge) Meka.colors.accent else Meka.colors.textSecondary,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Switch) { onKind(true, !c.charge) }.minTouch())
            Text(MoneyCards.NOT_CARD, style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { onKind(false, false) }.minTouch())
        }
        Text(MoneyCards.FOOT, style = MekaType.caption, color = Meka.colors.textTertiary)
    }
}

/** Slice 6: payday, since payday, what's still to go out (each payment), the pay account's balance and safe to spend. */
@Composable
private fun PaydayBlock(p: MoneyPaydayView, modifier: Modifier, withRows: Boolean = true, onBalance: (String) -> Unit) {
    val reduced = Meka.reducedMotion
    var balance by rememberSaveable { mutableStateOf("") }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        p.notReady?.let { n -> Text(n, style = MekaType.caption, color = Meka.colors.textSecondary) }
        p.payLine?.let { l ->
            Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                Crossfade(l, animationSpec = MekaMotion.appear(reduced), label = "money-payday") { t ->
                    Text(t, style = MekaType.body, color = Meka.colors.textPrimary)
                }
                p.fromLine?.let { Text(it, style = MekaType.caption, color = Meka.colors.textSecondary) }
                p.sinceLine?.let { s ->
                    Crossfade(s, animationSpec = MekaMotion.appear(reduced), label = "money-payday-since") { t ->
                        Text(t, style = MekaType.caption, color = Meka.colors.textSecondary)
                    }
                }
            }
        }
        // Money redesign (B3): with the Bills card above, its list holds these rows.
        p.goingOut?.takeIf { withRows }?.let { g ->
            Crossfade(g, animationSpec = MekaMotion.appear(reduced), label = "money-payday-out") { t ->
                Text(t, style = MekaType.caption, color = Meka.colors.textTertiary)
            }
        }
        if (withRows) p.rows.forEach { r ->
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = r.spoken }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.title, style = MekaType.body, color = Meka.colors.textPrimary)
                    Text(r.day, style = MekaType.caption, color = Meka.colors.textSecondary)
                }
                Text(r.amount, style = MekaType.body, color = Meka.colors.textPrimary)
            }
        }
        if (withRows && p.more > 0) Text("+ ${p.more} more", style = MekaType.caption, color = Meka.colors.textTertiary)
        p.balanceLine?.let { b ->
            Crossfade(b, animationSpec = MekaMotion.appear(reduced), label = "money-payday-balance") { t ->
                Text(t, style = MekaType.caption, color = Meka.colors.textSecondary)
            }
        }
        if (p.account != null) {
            Field(MoneyPayday.BALANCE_HINT, value = balance, onValue = { balance = it }) { balance = ""; onBalance(it) }
        }
        p.safe?.let { s ->
            val c by animateColorAsState(if (p.safeLit) Meka.colors.accent else Meka.colors.textPrimary, MekaMotion.appear(reduced), label = "money-safe")
            Crossfade(s, animationSpec = MekaMotion.appear(reduced), label = "money-safe-line") { t ->
                Text(t, style = MekaType.body, color = c)
            }
        }
        Text(p.foot, style = MekaType.caption, color = Meka.colors.textTertiary)
    }
}

@Composable
private fun ForecastBlock(f: MoneyForecastView, modifier: Modifier, onCut: (String) -> Unit, onExtra: (String) -> Unit) {
    val reduced = Meka.reducedMotion
    var extra by rememberSaveable { mutableStateOf("") }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        f.pace?.let { p ->
            Crossfade(p, animationSpec = MekaMotion.appear(reduced), label = "money-forecast-pace") { l ->
                Text(l, style = MekaType.body, color = Meka.colors.textPrimary)
            }
        }
        f.left?.let { l ->
            val c by animateColorAsState(if (f.leftLit) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "money-forecast-left")
            Crossfade(l, animationSpec = MekaMotion.appear(reduced), label = "money-forecast-left-line") { t ->
                Text(t, style = MekaType.caption, color = c)
            }
        }
        f.rows.forEach { r -> ForecastRow(r) }
        if (f.cuts.isNotEmpty() || f.rows.isNotEmpty()) {
            Text(MoneyForecast.WHAT_IF, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.padding(top = MekaSpace.xs))
        }
        if (f.cuts.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                f.cuts.forEach { c -> CutChip(c, onCut) }
            }
        }
        // Trading tracker, slice 11: trading at the pace it has gone, on its own row, with where the figure comes from.
        f.trading?.let { t -> CutChip(t, onCut) }
        f.tradingLine?.let { l -> Text(l, style = MekaType.caption, color = Meka.colors.textTertiary) }
        Field(MoneyForecast.EXTRA_HINT, value = extra, onValue = { extra = it }) { extra = it; onExtra(it) }
        f.extraLine?.let { e -> Text(e, style = MekaType.caption, color = Meka.colors.accent) }
        f.basis?.let { b -> Text(b, style = MekaType.caption, color = Meka.colors.textTertiary) }
    }
}

@Composable
private fun CutChip(c: MoneyCutChip, onCut: (String) -> Unit) {
    val reduced = Meka.reducedMotion
    val col by animateColorAsState(if (c.on) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "money-cut-${c.key}")
    Column(
        Modifier.clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .clickable(role = Role.Switch) { onCut(c.key) }.minTouch()
            .semantics(mergeDescendants = true) { contentDescription = c.spoken }
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    ) {
        Text(c.label, style = MekaType.caption, color = col)
        Text(c.line, style = MekaType.caption, color = Meka.colors.textTertiary)
    }
}

@Composable
private fun ForecastRow(r: MoneyForecastRow) {
    val reduced = Meka.reducedMotion
    val c by animateColorAsState(if (r.lit) Meka.colors.accent else Meka.colors.textPrimary, MekaMotion.appear(reduced), label = "money-forecast-${r.label}")
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = r.spoken }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(r.label, style = MekaType.body, color = Meka.colors.textPrimary)
            Crossfade(r.line, animationSpec = MekaMotion.appear(reduced), label = "money-forecast-line-${r.label}") { l ->
                Text(l, style = MekaType.caption, color = Meka.colors.textSecondary)
            }
        }
        Crossfade(r.amount, animationSpec = MekaMotion.appear(reduced), label = "money-forecast-amount-${r.label}") { a ->
            Text(a, style = MekaType.body, color = c)
        }
    }
}

/**
 * Slice 8: NET WORTH — the total (big), have/owe, the change since last month and the month-by-month line. Money review
 * (layout rule (e)): what he typed outside his accounts is one row ("Outside these accounts", the names, the total, or
 * "Add…"), opening its sheet; the accounts, cards and trading are the list under this card.
 */
@Composable
private fun WorthBlock(w: MoneyWorthView, modifier: Modifier, onOther: () -> Unit) {
    val reduced = Meka.reducedMotion
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        w.total?.let { t ->
            Column(Modifier.semantics(mergeDescendants = true) { contentDescription = w.spoken }, verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                Crossfade(t, animationSpec = MekaMotion.appear(reduced), label = "money-worth-total") { l ->
                    Text(l, style = MekaType.upNextTitle, color = Meka.colors.textPrimary)
                }
                w.splitLine?.let { l ->
                    Crossfade(l, animationSpec = MekaMotion.appear(reduced), label = "money-worth-split") { x ->
                        Text(x, style = MekaType.caption, color = Meka.colors.textSecondary)
                    }
                }
                w.changeLine?.let { l ->
                    Crossfade(l, animationSpec = MekaMotion.appear(reduced), label = "money-worth-change") { x ->
                        Text(x, style = MekaType.caption, color = Meka.colors.textSecondary)
                    }
                }
            }
        }
        w.empty?.let { e -> Text(e, style = MekaType.caption, color = Meka.colors.textSecondary) }
        if (w.points.size >= 2) WorthLine(w.points)
        w.chartLine?.let { l -> Text(l, style = MekaType.caption, color = Meka.colors.textTertiary) }
        w.missingLine?.let { l -> Text(l, style = MekaType.caption, color = Meka.colors.textTertiary) }
        MoneyWorthOther.row(w)?.let { r -> WorthOtherRow(r, onOther) }
        Text(w.foot, style = MekaType.caption, color = Meka.colors.textTertiary)
    }
}

/** Net worth's one row for what he typed: the title over the names (or what to add), the total or "Add…" in the accent, ›. */
@Composable
private fun WorthOtherRow(r: MoneyWorthOtherRow, onOpen: () -> Unit) {
    val reduced = Meka.reducedMotion
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(MekaRadius.m))
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = r.spoken }
            .padding(vertical = MekaSpace.xs).testTag("money-worth-other"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.m),
    ) {
        Column(Modifier.weight(1f)) {
            Text(r.title, style = MekaType.body, color = Meka.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Crossfade(r.line, animationSpec = MekaMotion.appear(reduced), label = "money-worth-other-line") { l ->
                Text(l, style = MekaType.caption, color = Meka.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Crossfade(r.amount ?: MoneyWorthOther.ADD, animationSpec = MekaMotion.appear(reduced), label = "money-worth-other-amount") { a ->
            Text(a, style = if (r.add) MekaType.caption else MekaType.metaStrong,
                color = when { r.add -> Meka.colors.accent; r.owes -> Meka.colors.approval; else -> Meka.colors.textPrimary }, maxLines = 1)
        }
        Text("›", style = MekaType.body, color = Meka.colors.textTertiary)
    }
}

/** The typed things' sheet: each with its amount and Remove, then the field (typing a name again updates it). */
@Composable
private fun WorthOtherDetails(w: MoneyWorthView, onAdd: (String) -> Unit, onRemove: (String) -> Unit) {
    var adding by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        MoneyWorthOther.rows(w).forEach { r -> key(r.id) { WorthRow(r, onRemove = { onRemove(r.id) }) } }
        Field(MoneyWorthOther.HINT, modifier = Modifier.testTag("money-worth-add"), value = adding, onValue = { adding = it }) { adding = ""; onAdd(it) }
        Text(MoneyWorthOther.LINE, style = MekaType.caption, color = Meka.colors.textTertiary)
    }
}

@Composable
private fun WorthRow(r: MoneyWorthRow, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = r.spoken }) {
            Text(r.name, style = MekaType.body, color = Meka.colors.textPrimary)
            Text(r.line, style = MekaType.caption, color = Meka.colors.textSecondary)
        }
        if (r.amount.isNotEmpty()) Text(r.amount, style = MekaType.body, color = Meka.colors.textPrimary)
        if (r.removable) {
            Text(MoneyWorthOther.REMOVE, style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onRemove)
                    .semantics { contentDescription = "Remove ${r.name} from your net worth" }.minTouch().padding(MekaSpace.xs)
                    .testTag("money-worth-remove-${r.id}"))
        }
    }
}

/**
 * Slice 8: the month-by-month line — one brass 2 dp line with an 8 dp dot on each month (the latest filled), a hairline
 * baseline, drawn left to right once (snaps with reduced motion); tap near a month for its total under the line.
 */
@Composable
private fun WorthLine(points: List<MoneyWorthPoint>, dots: Boolean = true) {
    val reduced = Meka.reducedMotion
    val accent = Meka.colors.accent
    val hairline = Meka.colors.hairline
    val surface = Meka.colors.background
    var drawn by remember { mutableStateOf(if (reduced) 1f else 0f) }
    LaunchedEffect(points.size) { drawn = 1f }
    val progress by animateFloatAsState(drawn, MekaMotion.appear(reduced), label = "money-worth-line")
    var picked by remember(points) { mutableStateOf(points.size - 1) }
    Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        androidx.compose.foundation.Canvas(
            Modifier.fillMaxWidth().height(96.dp)
                .semantics { contentDescription = points.joinToString(", ") { it.said } }
                .pointerInput(points) {
                    detectTapGestures { o ->
                        val step = if (points.size > 1) size.width / (points.size - 1).toFloat() else 0f
                        picked = if (step > 0f) (o.x / step).roundToInt().coerceIn(0, points.size - 1) else 0
                    }
                },
        ) {
            val pad = 6.dp.toPx()
            val lo = points.minOf { it.pence }
            val hi = points.maxOf { it.pence }
            val span = (hi - lo).takeIf { it > 0 } ?: 1L
            val step = (size.width - 2 * pad) / (points.size - 1)
            fun at(i: Int) = androidx.compose.ui.geometry.Offset(
                pad + i * step, pad + (size.height - 2 * pad) * (1f - (points[i].pence - lo).toFloat() / span),
            )
            drawLine(hairline, androidx.compose.ui.geometry.Offset(0f, size.height - 1f), androidx.compose.ui.geometry.Offset(size.width, size.height - 1f), 1f)
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(at(0).x, at(0).y)
                for (i in 1 until points.size) lineTo(at(i).x, at(i).y)
            }
            clipRect(right = pad + (size.width - 2 * pad) * progress + pad) {
                drawPath(path, accent, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                points.indices.forEach { i ->
                    val c = at(i)
                    // A day-by-day line (the trading curve) shows only the picked day's dot.
                    if (!dots && i != picked) return@forEach
                    drawCircle(surface, 6.dp.toPx(), c)
                    if (i == picked) drawCircle(accent, 4.dp.toPx(), c)
                    else drawCircle(accent, 4.dp.toPx(), c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(points.first().label, style = MekaType.micro, color = Meka.colors.textTertiary, modifier = Modifier.weight(1f))
            Text(points.last().label, style = MekaType.micro, color = Meka.colors.textTertiary)
        }
        Crossfade(points[picked.coerceIn(0, points.size - 1)].said, animationSpec = MekaMotion.appear(reduced), label = "money-worth-picked") { l ->
            Text(l, style = MekaType.caption, color = Meka.colors.textPrimary)
        }
    }
}
