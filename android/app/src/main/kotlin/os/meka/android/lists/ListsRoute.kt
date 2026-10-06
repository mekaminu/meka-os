package os.meka.android.lists

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.today.SectionLabel
import os.meka.core.domain.DayChoice
import os.meka.core.domain.DecisionItem
import os.meka.core.domain.DecisionStatus
import os.meka.core.domain.DueState
import os.meka.core.domain.ListRules
import os.meka.core.domain.ListsView
import os.meka.core.domain.SomedayKind
import os.meka.core.domain.Task
import os.meka.core.domain.WaitingItem
import os.meka.core.facade.MekaCore

/** The three lists, in the order of the tabs. */
enum class ListTab(val label: String) { WAITING("Waiting for"), SOMEDAY("Someday"), DECISIONS("Decisions") }

/**
 * LISTS (build plan M1): Waiting for (chase dates), Someday (kinds) and Decisions (review dates). One screen with three
 * tabs; the lit tab pill springs across. Tapping a row unfolds its actions in place; "Got it" and "Do it now" make the
 * row leave like a completion (light haptic). Due chases and reviews are lit in the accent colour. Nothing is chased,
 * decided or promoted for you. Reduced motion: cross-fades only.
 */
@Composable
fun ListsRoute(core: MekaCore, initialTab: ListTab? = null) {
    val view by core.listsView.collectAsState()
    var tab by rememberSaveable { mutableStateOf(initialTab ?: ListTab.WAITING) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val act: (suspend () -> Unit) -> Unit = { body -> scope.launch { runCatching { body() } } }
    val toggle: (String) -> Unit = { id -> open = if (open == id) null else id }
    val leave: (suspend () -> Unit) -> Unit = { body -> haptics.light(); open = null; act(body) }

    LazyColumn(
        Modifier.fillMaxSize().background(Meka.colors.background),
        contentPadding = PaddingValues(horizontal = MekaSpace.gutter, vertical = MekaSpace.xl),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        item(key = "title") {
            Text("Lists", style = MekaType.greeting, color = Meka.colors.textPrimary, modifier = Modifier.appear(rememberAppearance(0)))
        }
        item(key = "due") {
            Text(
                view.dueLine ?: "Nothing to chase or review today.",
                style = MekaType.itemMeta,
                color = if (view.dueLine != null) Meka.colors.accent else Meka.colors.textSecondary,
                modifier = Modifier.animateItem().padding(bottom = MekaSpace.m).appear(rememberAppearance(1)),
            )
        }
        item(key = "tabs") {
            Tabs(tab, view, Modifier.padding(bottom = MekaSpace.m).appear(rememberAppearance(2))) { t ->
                if (t != tab) { haptics.tick(); tab = t; open = null }
            }
        }
        when (tab) {
            ListTab.WAITING -> waiting(view, open, toggle, core, act, leave)
            ListTab.SOMEDAY -> someday(view, open, toggle, core, act, leave)
            ListTab.DECISIONS -> decisions(view, open, toggle, core, act, leave)
        }
        item(key = "add-${tab.name}") {
            when (tab) {
                ListTab.WAITING -> AddWaiting { title, who, days -> act { core.addWaiting(title, who, days) } }
                ListTab.SOMEDAY -> AddSomeday { title, kind -> act { core.addSomeday(title, kind) } }
                ListTab.DECISIONS -> AddDecision { s, why, days -> act { core.recordDecision(s, why, days) } }
            }
        }
    }
}

private fun LazyListScope.waiting(
    view: ListsView, open: String?, toggle: (String) -> Unit, core: MekaCore,
    act: (suspend () -> Unit) -> Unit, leave: (suspend () -> Unit) -> Unit,
) {
    if (view.waiting.isEmpty()) empty("waiting", "Nothing you're waiting on. Add a reply, a parcel or a refund below and MEKA tells you when to chase it.")
    items(view.waiting, key = { "w-${it.id}" }) { w ->
        ListRow(w.title, w.meta, w.state, open == w.id, { toggle(w.id) }, Modifier.animateItem()) {
            w.notes?.let { Text(it, style = MekaType.body, color = Meka.colors.textSecondary) }
            Actions {
                Action("Chased") { act { core.chased(w.id, ListRules.DEFAULT_CHASE_DAYS) } }
                Action("Got it") { leave { core.received(w.id) } }
                Action("Delete", critical = true) { leave { core.deleteWaiting(w.id) } }
            }
            Choices("Chase", ListRules.CHASE_CHOICES) { days -> act { core.setChase(w.id, days) } }
        }
    }
}

private fun LazyListScope.someday(
    view: ListsView, open: String?, toggle: (String) -> Unit, core: MekaCore,
    act: (suspend () -> Unit) -> Unit, leave: (suspend () -> Unit) -> Unit,
) {
    if (view.someday.isEmpty()) empty("someday", "Ideas, trips, things to buy or read. They stay out of Today and the planner until you say \"Do it now\".")
    view.someday.forEach { g ->
        item(key = "g-${g.kind.name}") {
            SectionLabel(g.label, Modifier.animateItem().padding(top = MekaSpace.s))
        }
        items(g.items, key = { "s-${it.id}" }) { t: Task ->
            ListRow(t.title, null, DueState.NONE, open == t.id, { toggle(t.id) }, Modifier.animateItem()) {
                t.notes?.let { Text(it, style = MekaType.body, color = Meka.colors.textSecondary) }
                Actions {
                    Action("Do it now") { leave { core.promoteSomeday(t.id) } }
                    Action("Delete", critical = true) { leave { core.delete(t.id) } }
                }
                KindChoices(t.somedayKind ?: SomedayKind.IDEA) { k -> act { core.setSomedayKind(t.id, k) } }
            }
        }
    }
}

private fun LazyListScope.decisions(
    view: ListsView, open: String?, toggle: (String) -> Unit, core: MekaCore,
    act: (suspend () -> Unit) -> Unit, leave: (suspend () -> Unit) -> Unit,
) {
    if (view.decisions.isEmpty()) empty("decisions", "Write down what you decided and why, so it isn't re-made. Add a review date if it should be looked at again.")
    items(view.decisions, key = { "d-${it.id}" }) { d: DecisionItem ->
        ListRow(d.statement, d.meta, d.state, open == d.id, { toggle(d.id) }, Modifier.animateItem()) {
            d.rationale?.let { Text("Why: $it", style = MekaType.body, color = Meka.colors.textSecondary) }
            var replacing by rememberSaveable(d.id) { mutableStateOf(false) }
            Actions {
                if (d.status != DecisionStatus.REVISITING) Action("Revisit") { act { core.revisitDecision(d.id) } }
                Action("Replace…") { replacing = !replacing }
                Action("Delete", critical = true) { leave { core.deleteDecision(d.id) } }
            }
            Choices(if (d.state == DueState.DUE) "Still right · review again" else "Review", ListRules.REVIEW_CHOICES) { days ->
                act { core.keepDecision(d.id, days) }
            }
            AnimatedVisibility(replacing, enter = unfold(), exit = fold()) {
                Field("What did you decide instead?", Modifier.padding(top = MekaSpace.s)) { s ->
                    replacing = false
                    leave { core.replaceDecision(d.id, s, null, null) }
                }
            }
        }
    }
}

private fun LazyListScope.empty(key: String, line: String) = item(key = "empty-$key") {
    Text(line, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.animateItem().padding(vertical = MekaSpace.s))
}

/** Three tabs on a quiet track; the lit pill springs to the chosen one. Counts sit beside the labels. */
@Composable
private fun Tabs(current: ListTab, view: ListsView, modifier: Modifier, choose: (ListTab) -> Unit) {
    val tabs = ListTab.entries
    BoxWithConstraints(modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surface).padding(MekaSpace.xxs)) {
        val slot = maxWidth / tabs.size
        val x by animateDpAsState(slot * tabs.indexOf(current), MekaMotion.replan(Meka.reducedMotion), label = "lists-pill")
        Box(Modifier.offset(x = x).width(slot).height(40.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised))
        Row(Modifier.fillMaxWidth()) {
            tabs.forEach { t ->
                val count = when (t) {
                    ListTab.WAITING -> view.waiting.size
                    ListTab.SOMEDAY -> view.somedayCount
                    ListTab.DECISIONS -> view.decisions.size
                }
                val due = when (t) { ListTab.WAITING -> view.chaseDue; ListTab.DECISIONS -> view.reviewsDue; else -> 0 }
                val color by animateColorAsState(
                    if (t == current) Meka.colors.textPrimary else Meka.colors.textTertiary, MekaMotion.appear(Meka.reducedMotion), label = "tab",
                )
                Box(
                    Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(MekaRadius.pill))
                        .clickable(role = Role.Tab) { choose(t) }
                        .clearAndSetSemantics {
                            contentDescription = t.label + (if (due > 0) ", $due due" else "") + ", $count"
                            selected = t == current
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        if (count > 0) "${t.label} $count" else t.label, maxLines = 1, softWrap = false,
                        style = MekaType.caption.copy(color = if (due > 0) Meka.colors.accent else color),
                    )
                }
            }
        }
    }
}

/** A list row: title and meta; tapping unfolds [details] in place (reduced motion: cross-fade). */
@Composable
private fun ListRow(
    title: String, meta: String?, state: DueState, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier,
    details: @Composable () -> Unit,
) {
    val bg by animateColorAsState(if (expanded) Meka.colors.surface else Color.Transparent, MekaMotion.appear(Meka.reducedMotion), label = "row-bg")
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(bg)) {
        Column(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = if (expanded) "Hide actions" else "Show actions") { onToggle() }
                .padding(horizontal = MekaSpace.s, vertical = MekaSpace.s),
        ) {
            Text(title, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
            meta?.let { Text(it, style = MekaType.itemMeta, color = if (state == DueState.DUE) Meka.colors.accent else Meka.colors.textSecondary) }
        }
        AnimatedVisibility(expanded, enter = unfold(), exit = fold()) {
            Column(
                Modifier.fillMaxWidth().padding(start = MekaSpace.s, end = MekaSpace.s, bottom = MekaSpace.m),
                verticalArrangement = Arrangement.spacedBy(MekaSpace.s),
            ) { details() }
        }
    }
}

@Composable
private fun unfold() = if (Meka.reducedMotion) fadeIn(MekaMotion.expand(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false))

@Composable
private fun fold() = if (Meka.reducedMotion) fadeOut(MekaMotion.expand(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false))

@Composable
private fun Actions(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.l)) { content() }
}

@Composable
private fun Action(label: String, critical: Boolean = false, onClick: () -> Unit) {
    Text(
        label, style = MekaType.itemTitle, color = if (critical) Meka.colors.critical else Meka.colors.accent,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.s)).clickable(role = Role.Button) { onClick() }.padding(vertical = MekaSpace.xxs),
    )
}

/** A label and a scrolling row of date presets. */
@Composable
private fun Choices(label: String, choices: List<DayChoice>, choose: (Int?) -> Unit) {
    Column {
        Text(label, style = MekaType.caption, color = Meka.colors.textTertiary)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = MekaSpace.xxs), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            choices.forEach { c -> Chip(c.label, false) { choose(c.days) } }
        }
    }
}

@Composable
private fun KindChoices(current: SomedayKind, choose: (SomedayKind) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        ListRules.SOMEDAY_KINDS.forEach { k -> Chip(ListRules.kindLabel(k), k == current) { choose(k) } }
    }
}

@Composable
private fun Chip(label: String, lit: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (lit) Meka.colors.accent else Meka.colors.surfaceRaised, MekaMotion.appear(Meka.reducedMotion), label = "chip")
    Text(
        label, style = MekaType.caption, color = if (lit) Meka.colors.onAccent else Meka.colors.textPrimary, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(bg)
            .semantics { selected = lit }
            .clickable(role = Role.Button) { onClick() }.padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    )
}

/** A one-line field in a raised pill; Done submits and clears. */
@Composable
private fun Field(hint: String, modifier: Modifier = Modifier, value: String? = null, onValue: ((String) -> Unit)? = null, onDone: (String) -> Unit) {
    var own by rememberSaveable(hint) { mutableStateOf("") }
    val text = value ?: own
    val set: (String) -> Unit = onValue ?: { own = it }
    Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
            .padding(horizontal = MekaSpace.l, vertical = MekaSpace.m),
    ) {
        if (text.isEmpty()) Text(hint, style = MekaType.body, color = Meka.colors.textTertiary)
        BasicTextField(
            value = text,
            onValueChange = set,
            singleLine = true,
            textStyle = MekaType.body.copy(color = Meka.colors.textPrimary),
            cursorBrush = SolidColor(Meka.colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) { onDone(text); set("") } }),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint },
        )
    }
}

/** Add to Waiting for: what, then (once typing) from whom and when to chase. */
@Composable
private fun AddWaiting(add: (String, String?, Int?) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var who by rememberSaveable { mutableStateOf("") }
    var chase by rememberSaveable { mutableStateOf<Int?>(ListRules.DEFAULT_CHASE_DAYS) }
    val submit = { if (title.isNotBlank()) { add(title, who.ifBlank { null }, chase); title = ""; who = ""; chase = ListRules.DEFAULT_CHASE_DAYS } }
    Column(Modifier.padding(top = MekaSpace.l), verticalArrangement = Arrangement.spacedBy(MekaSpace.s)) {
        Field("Waiting for…", value = title, onValue = { title = it }) { submit() }
        AnimatedVisibility(title.isNotBlank(), enter = unfold(), exit = fold()) {
            Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.s)) {
                Field("From whom (optional)", value = who, onValue = { who = it }) { submit() }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    ListRules.CHASE_CHOICES.forEach { c -> Chip(if (c.days == null) c.label else "Chase ${c.label.lowercase()}", chase == c.days) { chase = c.days } }
                }
                Action("Add") { submit() }
            }
        }
    }
}

@Composable
private fun AddSomeday(add: (String, SomedayKind) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(SomedayKind.IDEA) }
    val submit = { if (title.isNotBlank()) { add(title, kind); title = "" } }
    Column(Modifier.padding(top = MekaSpace.l), verticalArrangement = Arrangement.spacedBy(MekaSpace.s)) {
        Field("Someday…", value = title, onValue = { title = it }) { submit() }
        AnimatedVisibility(title.isNotBlank(), enter = unfold(), exit = fold()) {
            Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.s)) {
                KindChoices(kind) { kind = it }
                Action("Add") { submit() }
            }
        }
    }
}

@Composable
private fun AddDecision(add: (String, String?, Int?) -> Unit) {
    var statement by rememberSaveable { mutableStateOf("") }
    var why by rememberSaveable { mutableStateOf("") }
    var review by rememberSaveable { mutableStateOf<Int?>(null) }
    val submit = { if (statement.isNotBlank()) { add(statement, why.ifBlank { null }, review); statement = ""; why = ""; review = null } }
    Column(Modifier.padding(top = MekaSpace.l).widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(MekaSpace.s)) {
        Field("I decided…", value = statement, onValue = { statement = it }) { submit() }
        AnimatedVisibility(statement.isNotBlank(), enter = unfold(), exit = fold()) {
            Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.s)) {
                Field("Why (optional)", value = why, onValue = { why = it }) { submit() }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    ListRules.REVIEW_CHOICES.forEach { c -> Chip(if (c.days == null) c.label else "Review ${c.label.lowercase()}", review == c.days) { review = c.days } }
                }
                Action("Add") { submit() }
            }
        }
    }
}
