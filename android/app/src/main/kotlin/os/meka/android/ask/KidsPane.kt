package os.meka.android.ask

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
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
import os.meka.android.shell.MoreItem
import os.meka.android.shell.SharedMotion
import os.meka.core.domain.KidCard
import os.meka.core.domain.KidChoreDay
import os.meka.core.domain.KidChoreRules
import os.meka.core.domain.KidChoreRow
import os.meka.core.domain.KidMoney
import os.meka.core.domain.KidFeeRow
import os.meka.core.domain.KidSaved
import os.meka.core.domain.KidsPageRules
import os.meka.core.domain.KidsRules
import os.meka.core.domain.KidsWeekRules
import os.meka.core.facade.MekaCore

/** The pane's own list, for the hand tests' bounded scrolls (quality gate (3)). */
const val KIDS_LIST_TAG = "kids-list"
const val KIDS_ROW_SHEET_TAG = "kids-row-sheet"
const val KIDS_ALL_SHEET_TAG = "kids-all-sheet"

/**
 * Ask → Kids in the page shape (every page under Ask in one shape, slice 15; approved 2026-11-11,
 * `docs/design/look/LifePage.dc.html`): the title with its ⓘ, one gold + Add (A club fee · A chore · Pocket money a
 * week, each one typed line, "Alex: …" first when there's more than one child), three numbers (what they need this
 * week, the next club fee, what's to pay this week or the sizes to check), then This week, Children, Club fees and
 * Chores and pocket money (five rows each, See all for the rest). A child's row opens their card in a sheet (sizes with
 * Clear and Undo, All still fit, their club fees with Paid / Remove and the fee field, their chores and pocket money);
 * a fee's row its line, Paid and Remove with Undo; a chores row the child's chores and pocket money (day dots, Paid,
 * Remove, Undo, the chore and pocket money fields); a week's row the day and the thing. No children: a line and About
 * you ›. Navilife never pays a fee or moves money.
 *
 * Motion: the title travels in from Ask's tile (shared title); the header, summary, tiles and cards appear in turn;
 * cards move with the list's item motion; the ⓘ's words unfold and the sheets rise on the expand spring
 * (LifePageParts); inside a sheet the lines cross-fade, the checked line and a refusal blend to the accent (tick
 * haptic; a save a light haptic), a chore dot's fill blends. Reduced motion: cross-fades.
 */
@Composable
fun KidsPane(core: MekaCore, onClose: () -> Unit, onAboutYou: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val v by core.kidsView.collectAsState()
    val page = remember(v) { core.kidsPage(v) }
    // Per "child|size": what the field holds while it's being edited; per child, what the last save said and which
    // size a clear came from (for Undo).
    val texts = remember { mutableStateMapOf<String, String>() }
    val said = remember { mutableStateMapOf<String, KidSaved>() }
    val undoSize = remember { mutableStateMapOf<String, String>() }
    // Per child, what the fee field holds and what the last fee action said (with the fee id Undo takes back).
    val feeTexts = remember { mutableStateMapOf<String, String>() }
    val feeSaid = remember { mutableStateMapOf<String, KidSaved>() }
    // Per child, the chore and pocket money fields and what the last chore/money action said.
    val choreTexts = remember { mutableStateMapOf<String, String>() }
    val pocketTexts = remember { mutableStateMapOf<String, String>() }
    val moneySaid = remember { mutableStateMapOf<String, KidSaved>() }
    var infoOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var addChosen by remember { mutableStateOf<String?>(null) }
    var addText by remember { mutableStateOf("") }
    // The last add's answer: what was added, or why nothing was (lit, the words kept in the field).
    var addSaid by remember { mutableStateOf<KidSaved?>(null) }
    val addSaidColor by animateColorAsState(
        if (addSaid?.refused == true) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "kids-add-said",
    )
    var allCard by remember { mutableStateOf<String?>(null) }
    var rowKey by remember { mutableStateOf<String?>(null) }
    // A fee's sheet: what Paid or Remove said, with the fee id Undo takes back (kept while the fee itself is gone).
    var feeLine by remember { mutableStateOf<Pair<String, KidSaved>?>(null) }
    fun open(key: String) { haptics.tick(); addSaid = null; feeLine = null; rowKey = key }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().testTag(KIDS_LIST_TAG),
            contentPadding = PaddingValues(MekaSpace.gutter),
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            item(key = "close") {
                Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
                    modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
            }
            item(key = "title") {
                LifePageHeader(
                    page.title, page.info, infoOpen,
                    titleModifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.KIDS)).appear(rememberAppearance(0)),
                    onInfo = { haptics.tick(); infoOpen = !infoOpen },
                    onAdd = { haptics.tick(); addSaid = null; addOpen = true },
                )
            }
            item(key = "summary") {
                Column(Modifier.appear(rememberAppearance(1)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                    Crossfade(v.summary, animationSpec = MekaMotion.appear(reduced), label = "kids-summary") { s ->
                        Text(s, style = MekaType.caption, color = Meka.colors.textSecondary)
                    }
                    val line = if (addOpen) null else addSaid
                    Crossfade(line, animationSpec = MekaMotion.appear(reduced), label = "kids-add-line") { l ->
                        l?.let { Text(it.line, style = MekaType.caption, color = addSaidColor) }
                    }
                    if (v.empty != null) {
                        Text("About you ›", style = MekaType.itemMeta, color = Meka.colors.accent,
                            modifier = Modifier.clickable(role = Role.Button) { onAboutYou() }.minTouch())
                    }
                }
            }
            item(key = "tiles") { LifeNumberTiles(page.tiles, Modifier.appear(rememberAppearance(2))) }
            page.cards.forEachIndexed { i, c ->
                item(key = "card-${c.key}") {
                    LifePageCard(
                        c, Modifier.animateItem().appear(rememberAppearance(3 + i)),
                        onMore = { haptics.tick(); allCard = c.key },
                        onRow = { r -> open(r.key) },
                    )
                }
            }
            item(key = "foot") {
                Text(KidsRules.FOOT, style = MekaType.caption, color = Meka.colors.textTertiary,
                    modifier = Modifier.animateItem().padding(top = MekaSpace.l))
            }
        }
        // + Add: which kind, then one typed line.
        LifeSheet(addOpen, page.addTitle, LIFE_ADD_SHEET_TAG, onClose = { addOpen = false }) {
            AddChoiceRows(page.addChoices, addChosen) { c -> haptics.tick(); addSaid = null; addChosen = c.key }
            addChosen?.let { choice ->
                Field(
                    KidsPageRules.hint(choice), value = addText, onValue = { addText = it },
                    description = KidsPageRules.spokenHint(choice), keep = true,
                ) { typed ->
                    scope.launch {
                        val r = runCatching { core.addKids(choice, typed) }.getOrNull() ?: return@launch
                        addSaid = r
                        if (r.refused) haptics.tick() else { haptics.light(); addText = ""; addOpen = false }
                    }
                }
                addSaid?.takeIf { it.refused }?.let { Text(it.line, style = MekaType.caption, color = addSaidColor) }
            }
        }
        // See all: every row of one card; a tap opens its sheet.
        val all = allCard
        LifeSheet(all != null, all?.let(KidsPageRules::cardTitle) ?: KidsPageRules.TITLE, KIDS_ALL_SHEET_TAG, onClose = { allCard = null }) {
            if (all != null) KidsPageRules.allRows(v, all, core.todayEpochDay()).forEach { r ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m))
                        .clickable(role = Role.Button) { allCard = null; open(r.key) }
                        .semantics(mergeDescendants = true) {}
                        .minTouch().padding(vertical = MekaSpace.xs),
                ) {
                    Text(r.name, style = MekaType.body, color = Meka.colors.textPrimary)
                    Text(listOf(r.line, r.whenText).filter { it.isNotEmpty() }.joinToString(" · "), style = MekaType.caption, color = Meka.colors.textSecondary)
                }
            }
        }
        // A row's sheet: a child's card, a fee, a child's chores and pocket money, or a thing this week.
        val openKey = rowKey
        LifeSheet(openKey != null, openKey?.let { KidsPageRules.sheetTitle(v, it) } ?: KidsPageRules.TITLE, KIDS_ROW_SHEET_TAG, onClose = { rowKey = null }) {
            if (openKey == null) return@LifeSheet
            val kid = KidsPageRules.kid(v, openKey)
            val fee = KidsPageRules.fee(v, openKey)
            val week = KidsPageRules.weekItem(v, openKey)
            val moneyOf: @Composable (KidCard) -> Unit = { k ->
                val say: (KidSaved) -> Unit = { r -> if (r.refused) haptics.tick() else haptics.light(); moneySaid[k.name] = r }
                KidMoneyView(
                    name = k.name,
                    money = k.money,
                    pocketText = pocketTexts[k.name] ?: k.money.pocketValue ?: "",
                    onPocketText = { pocketTexts[k.name] = it },
                    onPocket = { typed ->
                        scope.launch {
                            val r = runCatching { core.setKidPocketMoney(k.name, typed) }.getOrNull() ?: return@launch
                            say(r)
                            if (r.refused) pocketTexts[k.name] = typed else pocketTexts.remove(k.name)
                        }
                    },
                    choreText = choreTexts[k.name] ?: "",
                    onChoreText = { choreTexts[k.name] = it },
                    onAddChore = { typed ->
                        scope.launch {
                            val r = runCatching { core.addKidChore(k.name, typed) }.getOrNull() ?: return@launch
                            say(r)
                            if (r.refused) choreTexts[k.name] = typed else choreTexts.remove(k.name)
                        }
                    },
                    onTick = { id, day -> scope.launch { runCatching { core.tickKidChore(id, day) }.getOrNull()?.let(say) } },
                    onRemove = { id -> scope.launch { runCatching { core.removeKidChore(id) }.getOrNull()?.let(say) } },
                    onPaid = { scope.launch { runCatching { core.kidPocketMoneyPaid(k.name) }.getOrNull()?.let(say) } },
                    said = moneySaid[k.name],
                    onUndo = { token ->
                        scope.launch {
                            val ok = runCatching { core.undoKidMoney(token) }.getOrDefault(false)
                            haptics.light()
                            if (ok) moneySaid.remove(k.name) else moneySaid[k.name] = KidSaved(KidChoreRules.NOTHING_TO_UNDO, refused = true)
                        }
                    },
                )
            }
            when {
                kid != null && KidsPageRules.isMoney(openKey) -> moneyOf(kid)
                kid != null -> KidCardView(
                    kid = kid,
                    texts = texts,
                    said = said[kid.name],
                    modifier = Modifier,
                    onSave = { size, typed ->
                        scope.launch {
                            val r = runCatching { core.setKidSize(kid.name, size, typed) }.getOrNull() ?: return@launch
                            said[kid.name] = r
                            if (r.undo != null) undoSize[kid.name] = size
                            // The field empties itself on Done: a refusal puts the words back to fix, a save shows the kept size.
                            if (r.refused) { haptics.tick(); texts["${kid.name}|$size"] = typed } else { haptics.light(); texts.remove("${kid.name}|$size") }
                        }
                    },
                    onUndo = { undo ->
                        val size = undoSize[kid.name] ?: return@KidCardView
                        scope.launch {
                            runCatching { core.setKidSize(kid.name, size, undo) }
                            haptics.light()
                            said.remove(kid.name)
                        }
                    },
                    onFit = {
                        scope.launch {
                            val line = runCatching { core.kidSizesFit(kid.name) }.getOrNull() ?: return@launch
                            haptics.light()
                            said[kid.name] = KidSaved(line)
                        }
                    },
                    feeText = feeTexts[kid.name] ?: "",
                    onFeeText = { feeTexts[kid.name] = it },
                    feeSaid = feeSaid[kid.name],
                    onAddFee = { typed ->
                        scope.launch {
                            val r = runCatching { core.addKidFee(kid.name, typed) }.getOrNull() ?: return@launch
                            feeSaid[kid.name] = r
                            if (r.refused) { haptics.tick(); feeTexts[kid.name] = typed } else { haptics.light(); feeTexts.remove(kid.name) }
                        }
                    },
                    onFeePaid = { id ->
                        scope.launch {
                            val r = runCatching { core.kidFeePaid(id) }.getOrNull() ?: return@launch
                            if (r.refused) haptics.tick() else haptics.light()
                            feeSaid[kid.name] = r
                        }
                    },
                    onFeeRemove = { id ->
                        scope.launch {
                            val r = runCatching { core.removeKidFee(id) }.getOrNull() ?: return@launch
                            haptics.light()
                            feeSaid[kid.name] = r
                        }
                    },
                    onFeeUndo = { id ->
                        scope.launch {
                            val ok = runCatching { core.undoKidFee(id) }.getOrDefault(false)
                            haptics.light()
                            if (ok) feeSaid.remove(kid.name) else feeSaid[kid.name] = KidSaved(KidsRules.FEE_NOTHING_TO_UNDO, refused = true)
                        }
                    },
                ) { moneyOf(kid) }
                fee != null || feeLine?.first == openKey -> {
                    fee?.let { (k, f) ->
                        Text(k.name, style = MekaType.caption, color = Meka.colors.textTertiary)
                        KidFeeRowView(
                            f,
                            onPaid = {
                                scope.launch {
                                    val r = runCatching { core.kidFeePaid(f.id) }.getOrNull() ?: return@launch
                                    if (r.refused) haptics.tick() else haptics.light()
                                    feeLine = openKey to r
                                }
                            },
                            onRemove = {
                                scope.launch {
                                    val r = runCatching { core.removeKidFee(f.id) }.getOrNull() ?: return@launch
                                    haptics.light()
                                    feeLine = openKey to r
                                }
                            },
                        )
                        Text(KidsRules.FEE_FOOT, style = MekaType.caption, color = Meka.colors.textTertiary)
                    }
                    val line = feeLine?.takeIf { it.first == openKey }?.second
                    Crossfade(line, animationSpec = MekaMotion.appear(reduced), label = "kids-fee-sheet-line") { s ->
                        if (s != null) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                                Text(s.line, style = MekaType.caption, color = if (s.refused) Meka.colors.accent else Meka.colors.textSecondary,
                                    modifier = Modifier.weight(1f))
                                s.feeUndo?.let { id ->
                                    Text("Undo", style = MekaType.caption, color = Meka.colors.accent,
                                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) {
                                            scope.launch {
                                                val ok = runCatching { core.undoKidFee(id) }.getOrDefault(false)
                                                haptics.light()
                                                feeLine = if (ok) null else openKey to KidSaved(KidsRules.FEE_NOTHING_TO_UNDO, refused = true)
                                            }
                                        }.minTouch().padding(MekaSpace.xs))
                                }
                            }
                        }
                    }
                }
                week != null -> {
                    val (d, thing) = week
                    Text(thing.text, style = MekaType.body, color = if (thing.lit) Meka.colors.accent else Meka.colors.textPrimary,
                        modifier = Modifier.semantics { contentDescription = thing.spoken })
                    Text(KidsWeekRules.FOOT, style = MekaType.caption, color = Meka.colors.textTertiary)
                    if (d.items.size > 1) d.items.filter { it != thing }.forEach { other ->
                        Text(other.text, style = MekaType.caption, color = Meka.colors.textSecondary)
                    }
                }
                else -> Text(KidsPageRules.GONE, style = MekaType.caption, color = Meka.colors.textSecondary)
            }
        }
    }
}

/** Kids: a child's size field's pill, then ":" and the size's key (walk-through A15 measures them). */
internal const val KIDS_SIZE_FIELD_TAG = "kids-size-field"

@Composable
private fun KidCardView(
    kid: KidCard,
    texts: MutableMap<String, String>,
    said: KidSaved?,
    modifier: Modifier,
    onSave: (size: String, typed: String) -> Unit,
    onUndo: (String) -> Unit,
    onFit: () -> Unit,
    feeText: String,
    onFeeText: (String) -> Unit,
    feeSaid: KidSaved?,
    onAddFee: (String) -> Unit,
    onFeePaid: (String) -> Unit,
    onFeeRemove: (String) -> Unit,
    onFeeUndo: (String) -> Unit,
    extra: @Composable () -> Unit = {},
) {
    val reduced = Meka.reducedMotion
    val checkedColor by animateColorAsState(
        if (kid.due) Meka.colors.accent else Meka.colors.textTertiary, MekaMotion.appear(reduced), label = "kid-due-${kid.name}",
    )
    val saidColor by animateColorAsState(
        if (said?.refused == true) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "kid-said-${kid.name}",
    )
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.m),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = kid.spoken },
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                Text(kid.name, style = MekaType.body, color = Meka.colors.textPrimary)
                Text(kid.line, style = MekaType.caption, color = Meka.colors.textSecondary)
            }
            if (kid.sizesLine != null) {
                Text(
                    kid.fitButton, style = MekaType.caption, color = Meka.colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                        .clickable(role = Role.Button, onClick = onFit)
                        .semantics { contentDescription = "${kid.name}'s sizes: ${kid.fitButton}" }
                        .minTouch().padding(MekaSpace.xs),
                )
            }
        }
        Crossfade(kid.checkedLine, animationSpec = MekaMotion.appear(reduced), label = "kid-checked-${kid.name}") { l ->
            Text(l, style = MekaType.caption, color = checkedColor)
        }
        kid.sizes.forEach { row ->
            val key = "${kid.name}|${row.size}"
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Text(row.label, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.width(112.dp))
                // Walk-through 2026-11-08 A15: Clear sits inside a filled field's end, so a filled field is as wide as
                // an empty one.
                Field(
                    row.placeholder, modifier = Modifier.weight(1f), value = texts[key] ?: row.value ?: "", onValue = { texts[key] = it },
                    description = "${kid.name}'s ${row.label.lowercase()} size, for example ${row.hint}",
                    tag = "$KIDS_SIZE_FIELD_TAG:${row.size}",
                    trailing = if (row.value == null) null else @Composable {
                            Text("Clear", style = MekaType.caption, color = Meka.colors.textTertiary,
                                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { onSave(row.size, "") }
                                    .semantics { contentDescription = "Clear ${kid.name}'s ${row.label.lowercase()} size" }
                                    .minTouch().padding(MekaSpace.xs))
                    },
                ) { typed ->
                    onSave(row.size, typed)
                }
            }
        }
        Crossfade(said, animationSpec = MekaMotion.appear(reduced), label = "kid-line-${kid.name}") { s ->
            if (s != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    Text(s.line, style = MekaType.caption, color = saidColor, modifier = Modifier.weight(1f))
                    s.undo?.let { u ->
                        Text("Undo", style = MekaType.caption, color = Meka.colors.accent,
                            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { onUndo(u) }
                                .minTouch().padding(MekaSpace.xs))
                    }
                }
            }
        }
        // Slice 2: club fees — each on the radar as a bill (one reminder 3 days before), Paid / Remove with Undo.
        Text(KidsRules.FEE_HEAD, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.padding(top = MekaSpace.xs))
        kid.fees.forEach { fee ->
            KidFeeRowView(fee, onPaid = { onFeePaid(fee.id) }, onRemove = { onFeeRemove(fee.id) })
        }
        Field(KidsRules.FEE_HINT, modifier = Modifier.fillMaxWidth(), value = feeText, onValue = onFeeText) { typed -> onAddFee(typed) }
        val feeSaidColor by animateColorAsState(
            if (feeSaid?.refused == true) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "kid-fee-said-${kid.name}",
        )
        Crossfade(feeSaid, animationSpec = MekaMotion.appear(reduced), label = "kid-fee-line-${kid.name}") { s ->
            if (s != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    Text(s.line, style = MekaType.caption, color = feeSaidColor, modifier = Modifier.weight(1f))
                    s.feeUndo?.let { id ->
                        Text("Undo", style = MekaType.caption, color = Meka.colors.accent,
                            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { onFeeUndo(id) }
                                .minTouch().padding(MekaSpace.xs))
                    }
                }
            }
        }
        extra()
    }
}

/**
 * Slice 5: CHORES AND POCKET MONEY on a child's card: the weekly amount, each chore with its line and seven day dots
 * (brass when ticked; today ringed; days still to come dimmed and not tappable), Remove, the field that adds a chore,
 * the week's sum, what was marked paid and **Paid £8**, and the last action's line with Undo.
 * Motion: a dot's fill blends (MekaMotion.appear) with a light haptic; the lines cross-fade; reduced motion: cross-fades.
 */
@Composable
private fun KidMoneyView(
    name: String,
    money: KidMoney,
    pocketText: String,
    onPocketText: (String) -> Unit,
    onPocket: (String) -> Unit,
    choreText: String,
    onChoreText: (String) -> Unit,
    onAddChore: (String) -> Unit,
    onTick: (String, Long) -> Unit,
    onRemove: (String) -> Unit,
    onPaid: () -> Unit,
    said: KidSaved?,
    onUndo: (String) -> Unit,
) {
    val reduced = Meka.reducedMotion
    Text(KidChoreRules.HEAD, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.padding(top = MekaSpace.xs))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Text(KidChoreRules.POCKET_LABEL, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.width(112.dp))
        Field(KidChoreRules.POCKET_HINT, modifier = Modifier.weight(1f), value = pocketText, onValue = onPocketText) { typed -> onPocket(typed) }
    }
    money.chores.forEach { chore -> KidChoreRowView(chore, onTick = { day -> onTick(chore.id, day) }, onRemove = { onRemove(chore.id) }) }
    Field(KidChoreRules.CHORE_HINT, modifier = Modifier.fillMaxWidth(), value = choreText, onValue = onChoreText) { typed -> onAddChore(typed) }
    money.weekLine?.let { w ->
        Crossfade(w, animationSpec = MekaMotion.appear(reduced), label = "kid-money-week-$name") { l ->
            Text(l, style = MekaType.caption, color = Meka.colors.textSecondary)
        }
    }
    if (money.paidLine != null || money.payLabel != null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            Crossfade(money.paidLine.orEmpty(), animationSpec = MekaMotion.appear(reduced), label = "kid-money-paid-$name", modifier = Modifier.weight(1f)) { l ->
                Text(l, style = MekaType.caption, color = if (money.lit) Meka.colors.accent else Meka.colors.textTertiary)
            }
            money.payLabel?.let { label ->
                Text(label, style = MekaType.caption, color = Meka.colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onPaid)
                        .semantics { contentDescription = "$label to $name for this week" }.minTouch().padding(MekaSpace.xs))
            }
        }
    }
    val saidColor by animateColorAsState(
        if (said?.refused == true) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "kid-money-said-$name",
    )
    Crossfade(said, animationSpec = MekaMotion.appear(reduced), label = "kid-money-line-$name") { s ->
        if (s != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Text(s.line, style = MekaType.caption, color = saidColor, modifier = Modifier.weight(1f))
                s.moneyUndo?.let { token ->
                    Text("Undo", style = MekaType.caption, color = Meka.colors.accent,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { onUndo(token) }
                            .minTouch().padding(MekaSpace.xs))
                }
            }
        }
    }
    if (money.shown) Text(KidChoreRules.FOOT, style = MekaType.caption, color = Meka.colors.textTertiary)
}

/** Slice 5: one chore — title and line ("£1 each · 3 this week"), Remove, and a dot per day of this week to tick. */
@Composable
private fun KidChoreRowView(chore: KidChoreRow, onTick: (Long) -> Unit, onRemove: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = chore.spoken },
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                Text(chore.title, style = MekaType.body, color = Meka.colors.textPrimary)
                Crossfade(chore.line, animationSpec = MekaMotion.appear(Meka.reducedMotion), label = "kid-chore-${chore.id}") { l ->
                    Text(l, style = MekaType.caption, color = Meka.colors.textSecondary)
                }
            }
            Text("Remove", style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onRemove)
                    .semantics { contentDescription = "Remove ${chore.title}" }.minTouch().padding(MekaSpace.xs))
        }
        Row(Modifier.fillMaxWidth()) {
            chore.days.forEach { d -> KidChoreDot(chore.title, d, Modifier.weight(1f)) { onTick(d.day) } }
        }
    }
}

/** One day's dot: brass when ticked, ringed today, dimmed (and not tappable) for days still to come. */
@Composable
private fun KidChoreDot(title: String, d: KidChoreDay, modifier: Modifier, onTick: () -> Unit) {
    val reduced = Meka.reducedMotion
    val fill by animateColorAsState(if (d.done) Meka.colors.accent else Meka.colors.surfaceRaised, MekaMotion.appear(reduced), label = "kid-dot-${d.day}")
    val ink by animateColorAsState(
        when { d.done -> Meka.colors.onAccent; d.canTick -> Meka.colors.textSecondary; else -> Meka.colors.textTertiary },
        MekaMotion.appear(reduced), label = "kid-dot-ink-${d.day}",
    )
    Box(
        modifier.minTouch()
            .then(if (d.canTick) Modifier.clickable(role = Role.Checkbox, onClick = onTick) else Modifier)
            .semantics { contentDescription = "$title, ${d.spoken}" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            // Walk-through 2026-11-08 A13: drawn up to 36 dp (was 32), as big as its seventh of the row allows.
            Modifier.sizeIn(maxWidth = 36.dp, maxHeight = 36.dp).fillMaxWidth().aspectRatio(1f).testTag("kid-chore-dot")
                .clip(CircleShape).background(fill)
                .then(if (d.today) Modifier.border(1.dp, Meka.colors.accent, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(d.letter, style = MekaType.itemMeta, color = ink)
        }
    }
}

/** One club fee: its title and line ("£35 · every month · next Sun 1 Nov", brass when due within 3 days), Paid, Remove. */
@Composable
private fun KidFeeRowView(fee: KidFeeRow, onPaid: () -> Unit, onRemove: () -> Unit) {
    val reduced = Meka.reducedMotion
    val lineColor by animateColorAsState(
        if (fee.due) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "kid-fee-${fee.id}",
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = fee.spoken },
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            Text(fee.title, style = MekaType.body, color = Meka.colors.textPrimary)
            Crossfade(fee.line, animationSpec = MekaMotion.appear(reduced), label = "kid-fee-line-${fee.id}") { l ->
                Text(l, style = MekaType.caption, color = lineColor)
            }
        }
        Text("Paid", style = MekaType.caption, color = Meka.colors.accent,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onPaid)
                .semantics { contentDescription = "${fee.title} paid" }.minTouch().padding(MekaSpace.xs))
        Text("Remove", style = MekaType.caption, color = Meka.colors.textTertiary,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onRemove)
                .semantics { contentDescription = "Remove ${fee.title}" }.minTouch().padding(MekaSpace.xs))
    }
}
