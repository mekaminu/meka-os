package os.meka.android.ask

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.SlideSwitch
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.lists.Field
import os.meka.core.domain.MoneySaved
import os.meka.core.domain.SpendPlanDetail
import os.meka.core.domain.SpendPlanRules
import os.meka.core.domain.SpendRow
import os.meka.core.domain.SpendSaid
import os.meka.core.domain.SpendTone
import os.meka.core.facade.MekaCore

/** The page's own list, for the hand tests' bounded scrolls. */
const val SPEND_PLANS_LIST_TAG = "spend-plans-list"

/** Which sheet is open on the page: a new plan, adding to a plan, changing one row, or today's rate. */
private sealed interface SpendSheet {
    data object NewPlan : SpendSheet
    data class Add(val planId: String) : SpendSheet
    data class Edit(val row: SpendRow) : SpendSheet
    data class Rate(val planId: String, val start: String) : SpendSheet
}

/**
 * Money → Plan → Spending plans (slice 1; the approved design `docs/design/money/SpendPlan.dc.html`), drawn over Money
 * so it stays behind the same fingerprint: plans as chips, THIS PLAN with its total, needed-by day and bought/sent bar,
 * Can I afford it? (This month · Over 2 months · Over 3 months) from Money's average month, three month tiles, Same for
 * everyone with − / + for a plan in another currency, and the people or things five a page (tick = bought or sent, tap
 * = change it). A calculator only: nothing here sends or moves money.
 *
 * Motion: the page slides in from the right over Money (the pane's push); its cards stagger in 40 ms apart; the bar
 * grows on the replan spring and the verdict cross-fades between Fits and short (its colour blends); a tick fills its
 * circle (appear); sheets rise as Money's sheets do. Ticks and adds give a light haptic, a refusal a tick. Reduced
 * motion: cross-fades, the bar shown at its value.
 */
@Composable
internal fun SpendPlansPage(core: MekaCore, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val v by core.spendPlansView.collectAsState()
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    var months by rememberSaveable { mutableIntStateOf(1) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var sheet by remember { mutableStateOf<SpendSheet?>(null) }
    var said by remember { mutableStateOf<SpendSaid?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    val plan = v.plan(chosen)
    val say: (SpendSaid?) -> Unit = { r -> if (r == null || r.refused) haptics.tick() else haptics.light(); said = r }
    // Slice 4: a thing's shop link opens in the browser; only links the core checked (plain https) ever reach here.
    val uri = LocalUriHandler.current
    val open: (String) -> Unit = { link -> if (link.isNotEmpty()) { haptics.light(); runCatching { uri.openUri(link) } } }
    BackHandler(enabled = sheet == null, onBack = onBack)

    Box(Modifier.fillMaxSize().background(Meka.colors.background)) {
        LazyColumn(
            Modifier.fillMaxSize().testTag(SPEND_PLANS_LIST_TAG),
            contentPadding = PaddingValues(MekaSpace.gutter),
            verticalArrangement = Arrangement.spacedBy(MekaSpace.m),
        ) {
            item(key = "back") {
                Text("‹ Money", style = MekaType.itemMeta, color = Meka.colors.accent,
                    modifier = Modifier.clickable(role = Role.Button) { onBack() }.minTouch())
            }
            item(key = "title") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(SpendPlanRules.TITLE, style = MekaType.greeting, color = Meka.colors.textPrimary,
                        modifier = Modifier.weight(1f).appear(rememberAppearance(0)))
                    if (v.canAdd) GoldPill(SpendPlanRules.NEW_PLAN, "spend-new-plan") { haptics.light(); said = null; sheet = SpendSheet.NewPlan }
                }
            }
            if (v.chips.isNotEmpty()) item(key = "chips") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).appear(rememberAppearance(1)),
                    horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    v.chips.forEach { c ->
                        MoneyChip(c.name, on = c.id == plan?.id, tag = "spend-chip-${c.id}") { haptics.tick(); chosen = c.id; page = 0; months = 1 }
                    }
                }
            }
            if (plan == null) {
                item(key = "empty") {
                    Text("Plan for Christmas gifts, a holiday or money sent home: add the people or things, and see whether it fits your month.",
                        style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(2)))
                }
            } else {
                item(key = "total-${plan.id}") { TotalCard(plan, Modifier.animateItem().appear(rememberAppearance(2))) }
                item(key = "afford-${plan.id}") {
                    AffordCard(plan, months, Modifier.animateItem().appear(rememberAppearance(3))) { m -> haptics.tick(); months = m }
                }
                item(key = "count-${plan.id}") {
                    // Count in my forecast: off until he turns it on for this plan; Money's forecast then takes off
                    // what's still to buy in the month he'll buy it.
                    MoneyCard(Modifier.animateItem().appear(rememberAppearance(4)), tag = "spend-count") {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m))
                                .clickable(role = Role.Switch) {
                                    haptics.tick(); scope.launch { runCatching { core.setSpendPlanCounted(plan.id, !plan.counted) } }
                                }
                                .semantics(mergeDescendants = true) { contentDescription = "${SpendPlanRules.COUNT_LABEL}, ${plan.countLine}"; selected = plan.counted }
                                .minTouch().testTag("spend-count-switch"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(SpendPlanRules.COUNT_LABEL, style = MekaType.body, color = Meka.colors.textPrimary)
                                Crossfade(plan.countLine, animationSpec = MekaMotion.appear(reduced), label = "spend-count-line") { l ->
                                    Text(l, style = MekaType.caption, color = if (plan.counted) Meka.colors.accent else Meka.colors.textSecondary)
                                }
                            }
                            Spacer(Modifier.size(MekaSpace.m))
                            SlideSwitch(plan.counted)
                        }
                    }
                }
                plan.foreign?.let { f ->
                    item(key = "same-${plan.id}") {
                        MoneyCard(Modifier.animateItem().appear(rememberAppearance(4)), tag = "spend-same") {
                            Text(SpendPlanRules.SAME_HEAD, style = MekaType.body, color = Meka.colors.textPrimary)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.m)) {
                                StepButton("−", "Less each") { haptics.tick(); scope.launch { runCatching { core.stepSpendPlanEach(plan.id, up = false) } } }
                                Crossfade(f.each, animationSpec = MekaMotion.appear(reduced), label = "spend-each") { e ->
                                    Text(e, style = MekaType.nowTitle, color = Meka.colors.textPrimary)
                                }
                                StepButton("+", "More each") { haptics.tick(); scope.launch { runCatching { core.stepSpendPlanEach(plan.id, up = true) } } }
                            }
                            Text(f.rateLine, style = MekaType.caption, color = Meka.colors.textSecondary)
                            Text(SpendPlanRules.CHANGE_RATE, style = MekaType.itemMeta, color = Meka.colors.accent,
                                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) {
                                    haptics.tick(); said = null; sheet = SpendSheet.Rate(plan.id, f.rateText)
                                }.minTouch().testTag("spend-rate"))
                        }
                    }
                }
                item(key = "list-${plan.id}") {
                    val pages = ((plan.rows.size + SpendPlanRules.PAGE - 1) / SpendPlanRules.PAGE).coerceAtLeast(1)
                    val p = page.coerceIn(0, pages - 1)
                    MoneyCard(Modifier.animateItem().appear(rememberAppearance(5)), tag = "spend-list") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${plan.listTitle} · ${plan.rows.size}", style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
                            if (plan.canAdd) Text(SpendPlanRules.ADD, style = MekaType.itemMeta, color = Meka.colors.accent,
                                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) {
                                    haptics.light(); said = null; sheet = SpendSheet.Add(plan.id)
                                }.minTouch().testTag("spend-add"))
                        }
                        if (plan.rows.isEmpty()) {
                            Text(if (plan.foreign != null) "Add the people you're sending to." else "Add what you'll buy, with how much and the month.",
                                style = MekaType.caption, color = Meka.colors.textSecondary)
                        }
                        plan.rows.drop(p * SpendPlanRules.PAGE).take(SpendPlanRules.PAGE).forEach { r ->
                            ThingRow(r,
                                onTick = { haptics.light(); scope.launch { runCatching { core.setSpendThingDone(r.id, !r.done) } } },
                                onOpen = { haptics.tick(); said = null; sheet = SpendSheet.Edit(r) },
                                onLink = { open(r.link) })
                        }
                        if (pages > 1) Row(verticalAlignment = Alignment.CenterVertically) {
                            val from = p * SpendPlanRules.PAGE + 1
                            val to = minOf(plan.rows.size, from + SpendPlanRules.PAGE - 1)
                            Text("$from–$to of ${plan.rows.size}", style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.weight(1f))
                            StepButton("‹", "Previous five") { if (p > 0) { haptics.tick(); page = p - 1 } }
                            Spacer(Modifier.size(MekaSpace.xs))
                            StepButton("›", "Next five") { if (p < pages - 1) { haptics.tick(); page = p + 1 } }
                        }
                    }
                }
                item(key = "remove-${plan.id}") {
                    // Two taps: the first asks, the second removes the plan and everything on it.
                    val asking = removing == plan.id
                    Text(if (asking) "Tap again to remove ${plan.name} and everything on it" else SpendPlanRules.REMOVE_PLAN,
                        style = MekaType.itemMeta, color = if (asking) Meka.colors.accent else Meka.colors.textSecondary,
                        modifier = Modifier.animateItem().clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) {
                            haptics.tick()
                            if (!asking) removing = plan.id else {
                                removing = null; chosen = null
                                scope.launch { runCatching { core.removeSpendPlan(plan.id) } }
                            }
                        }.minTouch().testTag("spend-remove-plan"))
                }
            }
            item(key = "foot") {
                Text(SpendPlanRules.FOOT, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.animateItem())
            }
        }

        // The sheets, as Money's: a new plan, add, change and today's rate.
        val asMoney: (SpendSaid?) -> MoneySaved? = { s -> s?.let { MoneySaved(it.line, refused = it.refused) } }
        MoneyDetailSheet(sheet as? SpendSheet.NewPlan, title = { SpendPlanRules.NEW_SHEET }, tag = "spend-new-sheet", done = SpendPlanRules.DONE,
            onClose = { sheet = null }) { _ ->
            var text by rememberSaveable { mutableStateOf("") }
            var currency by rememberSaveable { mutableStateOf(SpendPlanRules.GBP) }
            Field(SpendPlanRules.NEW_HINT, value = text, onValue = { text = it }, description = SpendPlanRules.NEW_DESCRIPTION, keep = true) { }
            Text("In", style = MekaType.caption, color = Meka.colors.textSecondary)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                SpendPlanRules.CURRENCIES.forEach { c ->
                    MoneyChip("${c.symbol} ${c.name}", on = c.code == currency, tag = "spend-cur-${c.code}") { haptics.tick(); currency = c.code }
                }
            }
            GoldPill("Add plan", "spend-new-save") {
                scope.launch {
                    val r = runCatching { core.addSpendPlan(text, currency) }.getOrNull()
                    say(r)
                    if (r != null && !r.refused) { chosen = r.id; page = 0; months = 1; text = ""; sheet = null }
                }
            }
            SpendSaidLine(asMoney(said))
        }
        val adding = sheet as? SpendSheet.Add
        val editing = sheet as? SpendSheet.Edit
        MoneyDetailSheet(adding ?: editing, title = { if (it is SpendSheet.Edit) SpendPlanRules.EDIT_SHEET else SpendPlanRules.ADD_SHEET },
            tag = "spend-thing-sheet", done = SpendPlanRules.DONE, onClose = { sheet = null }) { s ->
            val row = (s as? SpendSheet.Edit)?.row
            var name by rememberSaveable(s) { mutableStateOf(row?.name ?: "") }
            var note by rememberSaveable(s) { mutableStateOf(row?.noteText ?: "") }
            var amount by rememberSaveable(s) { mutableStateOf(row?.amountText ?: "") }
            var month by rememberSaveable(s) { mutableIntStateOf(row?.month ?: 0) }
            var link by rememberSaveable(s) { mutableStateOf(row?.link ?: "") }
            val foreign = plan?.foreign != null
            Field(if (foreign) "Mum" else "Football boots", value = name, onValue = { name = it }, description = "Name", keep = true) { }
            Field(if (foreign) "Note (optional)" else "For Sam (optional)", value = note, onValue = { note = it }, description = "Note", keep = true) { }
            Field(if (foreign) "Amount (blank = same for everyone)" else "How much, like 45", value = amount, onValue = { amount = it },
                description = "Amount", keep = true) { }
            Field(SpendPlanRules.LINK_HINT, value = link, onValue = { link = it }, description = "Link", keep = true) { }
            if (!foreign) {
                Text("Buy in", style = MekaType.caption, color = Meka.colors.textSecondary)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    v.monthChoices.forEach { m ->
                        MoneyChip(m.label, on = m.key == month, tag = "spend-month-${m.key}") { haptics.tick(); month = m.key }
                    }
                }
            }
            GoldPill(if (row == null) "Add" else "Save", "spend-thing-save") {
                scope.launch {
                    // Slice 4: a link that isn't one Navilife opens is refused before anything is saved.
                    if (SpendPlanRules.link(link) == null) { say(SpendSaid(SpendPlanRules.BAD_LINK, refused = true)); return@launch }
                    val r = runCatching {
                        val saved = if (row == null) core.addSpendThing((s as SpendSheet.Add).planId, name, note, amount, month)
                            else core.editSpendThing(row.id, name, note, amount, month)
                        val id = row?.id ?: saved.id
                        if (!saved.refused && id != null && (row == null && link.isNotBlank() || row != null && link.trim() != row.link)) {
                            core.setSpendThingLink(id, link)
                        }
                        saved
                    }.getOrNull()
                    say(r)
                    if (r != null && !r.refused) {
                        if (row == null) { name = ""; note = ""; amount = ""; link = "" } else sheet = null
                    }
                }
            }
            if (row != null && row.link.isNotEmpty()) Text("${SpendPlanRules.OPEN_LINK} · ${row.linkLabel}", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { open(row.link) }
                    .minTouch().testTag("spend-thing-open-link"))
            if (row != null) Text("Remove", style = MekaType.itemMeta, color = Meka.colors.textSecondary,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) {
                    haptics.tick(); sheet = null; scope.launch { runCatching { core.removeSpendThing(row.id) } }
                }.minTouch().testTag("spend-thing-remove"))
            SpendSaidLine(asMoney(said))
        }
        MoneyDetailSheet(sheet as? SpendSheet.Rate, title = { SpendPlanRules.RATE_SHEET }, tag = "spend-rate-sheet", done = SpendPlanRules.DONE,
            onClose = { sheet = null }) { s ->
            var text by rememberSaveable(s) { mutableStateOf(s.start) }
            // Slice 3: today's looked-up rate first, his own below it (his wins while it's typed).
            val f = v.plan(s.planId)?.foreign
            Text(f?.todayLine ?: SpendPlanRules.TODAY_NONE, style = MekaType.body, color = Meka.colors.textPrimary,
                modifier = Modifier.testTag("spend-rate-today"))
            Text(SpendPlanRules.RATE_HELP, style = MekaType.caption, color = Meka.colors.textSecondary)
            Field("2,000", value = text, onValue = { text = it }, description = "Your own rate, how many to one pound", keep = true) { }
            Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.m), verticalAlignment = Alignment.CenterVertically) {
                GoldPill("Save rate", "spend-rate-save") {
                    scope.launch {
                        val r = runCatching { core.setSpendPlanRate(s.planId, text) }.getOrNull()
                        say(r)
                        if (r != null && !r.refused) sheet = null
                    }
                }
                if (f?.canUseToday == true) Text(SpendPlanRules.USE_TODAY, style = MekaType.itemMeta, color = Meka.colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) {
                        haptics.tick()
                        scope.launch {
                            val r = runCatching { core.useSpendPlanTodaysRate(s.planId) }.getOrNull()
                            say(r)
                            if (r != null && !r.refused) sheet = null
                        }
                    }.minTouch().testTag("spend-rate-today-use"))
            }
            Text(SpendPlanRules.RATE_SOURCE, style = MekaType.caption, color = Meka.colors.textTertiary)
            SpendSaidLine(asMoney(said))
        }
    }
}

@Composable
private fun SpendSaidLine(said: MoneySaved?) {
    val reduced = Meka.reducedMotion
    Crossfade(said, animationSpec = MekaMotion.appear(reduced), label = "spend-said") { s ->
        if (s != null) Text(s.line, style = MekaType.caption, color = if (s.refused) Meka.colors.accent else Meka.colors.textSecondary)
    }
}

@Composable
private fun GoldPill(label: String, tag: String, onClick: () -> Unit) {
    Text(
        label, style = MekaType.metaStrong, color = Meka.colors.onAccent,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent)
            .clickable(role = Role.Button, onClick = onClick).minTouch()
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs).testTag(tag),
    )
}

@Composable
private fun StepButton(label: String, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(Meka.colors.surfaceRaised)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description }.minTouch(),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MekaType.body, color = Meka.colors.accent) }
}

@Composable
private fun TotalCard(plan: SpendPlanDetail, modifier: Modifier) {
    val reduced = Meka.reducedMotion
    val fill by animateFloatAsState(plan.bar, MekaMotion.replan(reduced), label = "spend-bar")
    MoneyCard(modifier, tag = "spend-total") {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                MoneyLabel("THIS PLAN")
                Crossfade(plan.total, animationSpec = MekaMotion.appear(reduced), label = "spend-total") { t ->
                    Text(t, style = MekaType.greeting, color = Meka.colors.textPrimary)
                }
                Text(plan.totalSub, style = MekaType.caption, color = Meka.colors.textSecondary)
            }
            plan.by?.let { Text(it, style = MekaType.caption, color = Meka.colors.textSecondary) }
        }
        Box(Modifier.fillMaxWidth().padding(top = MekaSpace.xs).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Meka.colors.hairline)) {
            Box(Modifier.fillMaxWidth(if (reduced) plan.bar else fill).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(Meka.colors.accent))
        }
        Text(plan.spentLine, style = MekaType.caption, color = Meka.colors.textSecondary)
    }
}

@Composable
private fun AffordCard(plan: SpendPlanDetail, months: Int, modifier: Modifier, onMonths: (Int) -> Unit) {
    val reduced = Meka.reducedMotion
    MoneyCard(modifier, tag = "spend-afford") {
        Text(SpendPlanRules.AFFORD_HEAD, style = MekaType.body, color = Meka.colors.textPrimary)
        val a = plan.afford.firstOrNull { it.months == months } ?: plan.afford.firstOrNull()
        if (a == null) {
            Text(plan.affordNote ?: SpendPlanRules.NO_RATE + ".", style = MekaType.caption, color = Meka.colors.textSecondary)
        } else {
            a.rows.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(top = if (r.ruled) MekaSpace.xxs else 0.dp)) {
                    Text(r.label, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.weight(1f))
                    Text(r.value, style = if (r.strong) MekaType.captionStrong else MekaType.caption, color = Meka.colors.textPrimary)
                }
            }
            val tone by animateColorAsState(if (a.fits) Meka.colors.success else Meka.colors.accent, MekaMotion.appear(reduced), label = "spend-verdict")
            Crossfade(a.verdict, animationSpec = MekaMotion.appear(reduced), label = "spend-verdict-text") { line ->
                Text(line, style = MekaType.metaStrong, color = tone,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(tone.copy(alpha = 0.10f))
                        .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs).testTag("spend-verdict"))
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                plan.afford.forEach { s -> MoneyChip(s.label, on = s.months == a.months, tag = "spend-spread-${s.months}") { onMonths(s.months) } }
            }
        }
        if (plan.foreign == null) Row(Modifier.fillMaxWidth().padding(top = MekaSpace.xs), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            plan.months.forEach { m ->
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
                        .semantics(mergeDescendants = true) { contentDescription = "${m.label}, ${m.total}" + if (m.verdict.isNotEmpty()) ", ${m.verdict}" else "" }
                        .padding(MekaSpace.xs),
                ) {
                    Text(m.label, style = MekaType.caption, color = Meka.colors.textSecondary)
                    Text(m.total, style = MekaType.captionStrong, color = Meka.colors.textPrimary)
                    if (m.verdict.isNotEmpty()) Text(m.verdict, style = MekaType.caption, color = when (m.tone) {
                        SpendTone.FITS -> Meka.colors.success
                        SpendTone.SHORT -> Meka.colors.accent
                        SpendTone.NONE -> Meka.colors.textTertiary
                    })
                }
            }
        }
    }
}

@Composable
private fun ThingRow(r: SpendRow, onTick: () -> Unit, onOpen: () -> Unit, onLink: () -> Unit) {
    val reduced = Meka.reducedMotion
    val fill by animateColorAsState(if (r.done) Meka.colors.success else androidx.compose.ui.graphics.Color.Transparent, MekaMotion.appear(reduced), label = "spend-tick")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = "${r.name}, ${r.gbp}" + (if (r.local.isNotEmpty()) ", ${r.local}" else "") + ", ${r.note}" }
            .padding(vertical = MekaSpace.xxs).testTag("spend-row-${r.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.m),
    ) {
        Box(
            Modifier.minTouch().clip(CircleShape).clickable(role = Role.Checkbox, onClick = onTick)
                .semantics { contentDescription = if (r.done) "Done, tap to untick" else "Tick when done" }.testTag("spend-tick-${r.id}"),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(24.dp).clip(CircleShape).border(1.5.dp, if (r.done) Meka.colors.success else Meka.colors.textTertiary, CircleShape).background(fill),
                contentAlignment = Alignment.Center) {
                if (r.done) Text("✓", style = MekaType.caption, color = Meka.colors.onAccent)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(r.name, style = MekaType.body, color = Meka.colors.textPrimary, maxLines = 1)
            if (r.note.isNotEmpty()) Text(r.note, style = MekaType.caption, color = Meka.colors.textSecondary, maxLines = 1)
            // Slice 4: the shop it's from, a tap opens the page.
            if (r.linkLabel.isNotEmpty()) Text("↗ ${r.linkLabel}", style = MekaType.caption, color = Meka.colors.accent, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.s)).clickable(role = Role.Button, onClick = onLink)
                    .semantics { contentDescription = "${SpendPlanRules.OPEN_LINK}, ${r.linkLabel}" }.minTouch().testTag("spend-link-${r.id}"))
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(r.gbp, style = MekaType.body, color = Meka.colors.textPrimary)
            if (r.local.isNotEmpty()) Text(r.local, style = MekaType.caption, color = Meka.colors.textTertiary)
        }
    }
}
