package os.meka.android.lists

import androidx.compose.animation.AnimatedVisibility
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
    r: RenewalsView, today: Long, open: String?, toggle: (String) -> Unit, core: MekaCore,
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
    section("Needs doing", r.attention, today, open, toggle, core, act, leave, tap)
    section("Coming up", r.upcoming, today, open, toggle, core, act, leave, tap)
    section("Later", r.later, today, open, toggle, core, act, leave, tap)
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
    item.subject?.let { Text(it, style = MekaType.body, color = Meka.colors.textSecondary) }
    item.notes?.let { Text(it, style = MekaType.body, color = Meka.colors.textSecondary) }
    Actions {
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
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}
