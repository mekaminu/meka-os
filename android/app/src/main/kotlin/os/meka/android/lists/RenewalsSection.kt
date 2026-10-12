package os.meka.android.lists

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import os.meka.android.designsystem.MekaMotion
import os.meka.core.domain.HomeUpkeepRules
import os.meka.core.domain.HomeUpkeepView
import os.meka.core.domain.UpkeepRow
import os.meka.core.domain.CarRules
import os.meka.core.domain.BillRules
import os.meka.core.domain.SessionRules
import androidx.compose.ui.platform.LocalUriHandler
import os.meka.core.domain.CarView
import os.meka.core.domain.MoneyDateRules
import os.meka.core.domain.MoneyDatesView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.today.SectionLabel
import os.meka.core.domain.CivilDate
import os.meka.core.domain.DueState
import os.meka.core.domain.ObligationKind
import os.meka.core.domain.RenewalItem
import os.meka.core.domain.RenewalRepeat
import os.meka.core.domain.RenewalRules
import os.meka.core.domain.RenewalsView
import os.meka.core.facade.MekaCore

/** A new renewal as typed in the add form. */
data class NewRenewal(
    val title: String,
    val kind: ObligationKind,
    val dueDay: Long,
    val repeat: RenewalRepeat,
    val cost: String?,
    val cancelByDaysBefore: Int?,
)

/**
 * RENEWALS (build plan M1): the renewals and bills radar, entered by hand. Needs doing (overdue, cancel-by close,
 * within lead time) is lit; then Coming up (about three months) and Later. Tapping a row unfolds its actions:
 * "Renewed"/"Paid" rolls a repeating one on (its date line slides up to the next one and the row settles into its
 * new place, light haptic); a one-off leaves like a completion. Nothing is paid or cancelled for you.
 */
internal fun LazyListScope.renewals(
    r: RenewalsView, car: CarView, today: Long, open: String?, toggle: (String) -> Unit, core: MekaCore,
    act: (suspend () -> Unit) -> Unit, leave: (suspend () -> Unit) -> Unit, tap: () -> Unit,
) {
    r.costLine?.let { line ->
        item(key = "r-cost") {
            Text(line, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.animateItem().padding(bottom = MekaSpace.xs))
        }
    }
    if (r.count == 0) {
        empty(
            "renewals",
            "MOT, insurance, the boiler service, subscriptions and bills. Add each with its date and MEKA shows it in good time, with a cancel-by reminder if you set one.",
        )
    }
    homeUpkeep(HomeUpkeepRules.view(r.all, today), open, toggle, core, act, tap)
    car(car, open, toggle, core, act, tap)
    moneyDates(MoneyDateRules.view(r.all, today), open, toggle, core, act, tap)
    section("Needs doing", r.attention, today, open, toggle, core, act, leave, tap)
    section("Coming up", r.upcoming, today, open, toggle, core, act, leave, tap)
    section("Later", r.later, today, open, toggle, core, act, leave, tap)
}

/**
 * Home upkeep (V1): the jobs a house needs on a rhythm, one row that unfolds in place (the list's row motion) into
 * the suggestions. Add (light haptic) puts a job on the radar with its interval and first date, and its line
 * cross-fades to "On the radar · due Sun 1 Nov"; a job whose date only Meka knows (insurance, TV licence) opens its
 * new row so the date can be set straight away.
 */
private fun LazyListScope.homeUpkeep(
    v: HomeUpkeepView, open: String?, toggle: (String) -> Unit, core: MekaCore,
    act: (suspend () -> Unit) -> Unit, tap: () -> Unit,
) {
    item(key = "r-home") {
        ListRow(HomeUpkeepRules.LABEL, v.summary, DueState.NONE, open == HOME_UPKEEP, { toggle(HOME_UPKEEP) }, Modifier.animateItem()) {
            v.rows.forEach { row -> UpkeepRowView(row) { tap(); act { core.addHomeUpkeep(row.presetId)?.let { id -> if (!row.dateKnown) toggle(id) } } } }
        }
    }
}

private const val HOME_UPKEEP = "home-upkeep"
private const val CAR = "car"
private const val MONEY = "money-dates"

/**
 * UK money deadlines (V1): one row like Home upkeep that unfolds in place into Self Assessment, the ISA allowance, the
 * energy and broadband contracts and a pension check-in. Add (light haptic) puts one on the radar; a date only Meka
 * knows (a contract's end, the check-in) opens its new row so it can be set. Dates only: nothing touches an account.
 */
private fun LazyListScope.moneyDates(
    v: MoneyDatesView, open: String?, toggle: (String) -> Unit, core: MekaCore,
    act: (suspend () -> Unit) -> Unit, tap: () -> Unit,
) {
    item(key = "r-money") {
        ListRow(MoneyDateRules.LABEL, v.summary, DueState.NONE, open == MONEY, { toggle(MONEY) }, Modifier.animateItem()) {
            v.rows.forEach { row ->
                UpkeepLine(row.title, row.line, row.note, row.tracked) {
                    tap(); act { core.addMoneyDate(row.presetId)?.let { id -> if (!row.dateKnown) toggle(id) } }
                }
            }
        }
    }
}

/**
 * Car on autopilot (V1, slice 1): one row like Home upkeep that unfolds in place into the registration (typed once,
 * Done saves it) and the car's four dates. Add (light haptic) puts one on the radar with a placeholder date and opens
 * its new row so the real date can be set; reminders come four weeks and a week before. Its line cross-fades to
 * "On the radar · due …" like the home jobs'. Slice 2: the DVLA's line and the MOT history under the registration, the
 * DVLA's day on the MOT and tax lines, and "Use the DVLA's date" when the radar's date differs.
 */
private fun LazyListScope.car(
    v: CarView, open: String?, toggle: (String) -> Unit, core: MekaCore,
    act: (suspend () -> Unit) -> Unit, tap: () -> Unit,
) {
    item(key = "r-car") {
        ListRow(CarRules.LABEL, v.summary, DueState.NONE, open == CAR, { toggle(CAR) }, Modifier.animateItem()) {
            var regError by rememberSaveable { mutableStateOf<String?>(null) }
            Field(v.registration?.let { "Registration (now $it), blank to clear" } ?: "Registration, e.g. AB12 CDE") { text ->
                regError = CarRules.registrationError(text)
                if (regError == null) { tap(); act { core.setCarRegistration(text) } }
            }
            regError?.let { Text(it, style = MekaType.caption, color = Meka.colors.critical) }
            // Slice 2: what the DVLA says (MEKA's server asks once a day), cross-fading as a new answer arrives.
            AnimatedContent(v.dvlaLine to v.historyLine, transitionSpec = { fadeIn(MekaMotion.appear(true)) togetherWith fadeOut(MekaMotion.appear(true)) }, label = "car-dvla") { (dvla, history) ->
                Column {
                    dvla?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textSecondary) }
                    history?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
                }
            }
            v.rows.forEach { row ->
                UpkeepLine(
                    row.title, row.line, row.note, row.tracked,
                    use = row.dvlaOffer?.let { { tap(); act { core.useDvlaDate(row.presetId) } } },
                ) { tap(); act { core.addCarDate(row.presetId)?.let { id -> toggle(id) } } }
            }
        }
    }
}

@Composable
private fun UpkeepRowView(row: UpkeepRow, add: () -> Unit) = UpkeepLine(row.title, row.line, row.note, row.tracked, add = add)

/** One suggested job or car date: title, its line (cross-fades once added) and what it covers, with Add until tracked. */
@Composable
private fun UpkeepLine(title: String, line: String, note: String, tracked: Boolean, use: (() -> Unit)? = null, add: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = MekaSpace.xxs), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MekaType.body, color = Meka.colors.textPrimary)
            AnimatedContent(line, transitionSpec = { fadeIn(MekaMotion.appear(true)) togetherWith fadeOut(MekaMotion.appear(true)) }, label = "upkeep-line") { l ->
                Text(l, style = MekaType.itemMeta, color = if (tracked) Meka.colors.textSecondary else Meka.colors.textTertiary)
            }
            Text(note, style = MekaType.caption, color = Meka.colors.textTertiary)
        }
        if (!tracked) Action("Add") { add() }
        // Car slice 2: the radar's date differs from the DVLA's; never changed without this tap.
        else if (use != null) Action("Use the DVLA's date") { use() }
    }
}

private fun LazyListScope.section(
    label: String, rows: List<RenewalItem>, today: Long, open: String?, toggle: (String) -> Unit, core: MekaCore,
    act: (suspend () -> Unit) -> Unit, leave: (suspend () -> Unit) -> Unit, tap: () -> Unit,
) {
    if (rows.isEmpty()) return
    item(key = "r-label-$label") { SectionLabel(label, Modifier.animateItem().padding(top = MekaSpace.m)) }
    items(rows, key = { "r-${it.id}" }) { item ->
        ListRow(item.title, item.meta, if (item.needsAttention) DueState.DUE else DueState.NONE, open == item.id, { toggle(item.id) }, Modifier.animateItem()) {
            RenewalDetails(item, today, core, act, leave, tap)
        }
    }
}

@Composable
private fun RenewalDetails(
    item: RenewalItem, today: Long, core: MekaCore,
    act: (suspend () -> Unit) -> Unit, leave: (suspend () -> Unit) -> Unit, tap: () -> Unit,
) {
    var picking by rememberSaveable(item.id) { mutableStateOf(false) }
    var costError by rememberSaveable(item.id) { mutableStateOf<String?>(null) }
    var badLink by rememberSaveable(item.id) { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    val reduced = Meka.reducedMotion
    item.subject?.let { Text(it, style = MekaType.body, color = Meka.colors.textSecondary) }
    item.notes?.let { Text(it, style = MekaType.body, color = Meka.colors.textSecondary) }
    Actions {
        // Bills due: opens the page Meka saved as where he pays it; MEKA never pays.
        item.payLink?.let { link -> Action(BillRules.payLabel(link)) { tap(); runCatching { uri.openUri(link) } } }
        Action(item.doneLabel) {
            if (item.repeats == RenewalRepeat.NONE) leave { core.renewalDone(item.id) } else { tap(); act { core.renewalDone(item.id) } }
        }
        Action(item.stopLabel) { leave { core.stopRenewal(item.id) } }
        Action("Delete", critical = true) { leave { core.deleteRenewal(item.id) } }
    }
    ChipRow(null) { Chip(RenewalRules.dueButton(item.dueDay, today), false) { picking = true } }
    ChipRow("Repeats") {
        RenewalRules.REPEATS.forEach { rp -> Chip(RenewalRules.repeatLabel(rp), item.repeats == rp) { act { core.setRenewalRepeat(item.id, rp) } } }
    }
    ChipRow("Show it") {
        RenewalRules.LEAD_CHOICES.forEach { c -> Chip(c.label, c.days == item.leadDays) { c.days?.let { d -> act { core.setRenewalLead(item.id, d) } } } }
    }
    ChipRow("Cancel by") {
        val current = item.cancelByDay?.let { (item.dueDay - it).toInt() }
        RenewalRules.CANCEL_CHOICES.forEach { c -> Chip(c.label, c.days == current) { act { core.setRenewalCancelBy(item.id, c.days) } } }
    }
    KindChips(item.kind) { k -> act { core.setRenewalKind(item.id, k) } }
    Field(item.costPence?.let { "Cost (now ${RenewalRules.formatPence(it)}), blank to clear" } ?: "Cost, e.g. 9.99") { text ->
        costError = checkCost(text)
        if (costError == null) act { core.setRenewalCost(item.id, text) }
    }
    costError?.let { Text(it, style = MekaType.caption, color = Meka.colors.critical) }
    // Bills due: where he pays it (optional); the reminder three days before offers Pay when it's set.
    Field(item.payLink?.let { "Pay link (now ${BillRules.site(it)})" } ?: "Pay link, e.g. octopus.energy") { text ->
        val typed = text.take(SessionRules.MAX_LINK)
        act { val ok = core.setRenewalPayLink(item.id, typed); badLink = !ok; if (ok) tap() }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        AnimatedContent(BillRules.linkLine(item, badLink), transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) }, label = "pay-link-line", modifier = Modifier.weight(1f)) { l ->
            Text(l, style = MekaType.caption, color = if (badLink) Meka.colors.critical else Meka.colors.textTertiary)
        }
        if (item.payLink != null) Action("Remove") { badLink = false; act { core.setRenewalPayLink(item.id, "") } }
    }
    if (picking) DayPickerDialog(item.dueDay, onPick = { d -> act { core.setRenewalDue(item.id, d) } }, onDismiss = { picking = false })
}

/** Add a renewal: what, then (once typing) its kind, due date, repeat, cost and cancel-by. */
@Composable
internal fun AddRenewal(today: Long, add: (NewRenewal) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(ObligationKind.OTHER) }
    var due by rememberSaveable { mutableStateOf(today + RenewalRules.DEFAULT_DUE_IN_DAYS) }
    var repeat by rememberSaveable { mutableStateOf(RenewalRules.defaultRepeat(ObligationKind.OTHER)) }
    var cost by rememberSaveable { mutableStateOf("") }
    var cancelBy by rememberSaveable { mutableStateOf<Int?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val submit = {
        error = checkCost(cost)
        if (title.isNotBlank() && error == null) {
            add(NewRenewal(title, kind, due, repeat, cost.ifBlank { null }, cancelBy))
            title = ""; cost = ""; cancelBy = null; kind = ObligationKind.OTHER
            repeat = RenewalRules.defaultRepeat(kind); due = today + RenewalRules.DEFAULT_DUE_IN_DAYS
        }
    }
    Column(Modifier.padding(top = MekaSpace.l), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Field("Renewal or bill…", value = title, onValue = { title = it }) { submit() }
        AnimatedVisibility(title.isNotBlank(), enter = unfold(), exit = fold()) {
            Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                KindChips(kind) { kind = it; repeat = RenewalRules.defaultRepeat(it) }
                ChipRow(null) { Chip(RenewalRules.dueButton(due, today), true) { picking = true } }
                ChipRow("Repeats") { RenewalRules.REPEATS.forEach { rp -> Chip(RenewalRules.repeatLabel(rp), repeat == rp) { repeat = rp } } }
                Field("Cost (optional), e.g. 9.99", value = cost, onValue = { cost = it; error = null }) { submit() }
                error?.let { Text(it, style = MekaType.caption, color = Meka.colors.critical) }
                ChipRow("Cancel by") { RenewalRules.CANCEL_CHOICES.forEach { c -> Chip(c.label, cancelBy == c.days) { cancelBy = c.days } } }
                Action("Add") { submit() }
            }
        }
    }
    if (picking) DayPickerDialog(due, onPick = { due = it }, onDismiss = { picking = false })
}

private fun checkCost(text: String): String? = RenewalRules.costError(text)

@Composable
private fun KindChips(current: ObligationKind, choose: (ObligationKind) -> Unit) {
    ChipRow("Kind") { RenewalRules.KINDS.forEach { k -> Chip(RenewalRules.kindLabel(k), k == current) { choose(k) } } }
}

/** An optional caption over a scrolling row of chips. */
@Composable
private fun ChipRow(label: String?, chips: @Composable () -> Unit) {
    Column {
        label?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(top = MekaSpace.xxs),
            horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) { chips() }
    }
}

/**
 * The system date picker, dressed in MEKA's colours (the app doesn't otherwise use Material theming). Days are local
 * epoch days; the picker works in UTC midnights, so the conversion is exact.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DayPickerDialog(initialDay: Long, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val c = Meka.colors
    val base = if (c.background.luminance() < 0.5f) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = c.accent, onPrimary = c.onAccent, surface = c.surfaceRaised, onSurface = c.textPrimary,
        onSurfaceVariant = c.textSecondary, surfaceContainerHigh = c.surfaceRaised, outline = c.hairline,
        secondaryContainer = c.surface, onSecondaryContainer = c.textPrimary,
    )
    MaterialTheme(colorScheme = scheme) {
        val state = rememberDatePickerState(initialSelectedDateMillis = initialDay * CivilDate.DAY_MS)
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onPick(Math.floorDiv(it, CivilDate.DAY_MS)) }
                    onDismiss()
                }) { Text("Set", style = MekaType.body) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = MekaType.body) } },
        ) { DatePicker(state) }
    }
}
