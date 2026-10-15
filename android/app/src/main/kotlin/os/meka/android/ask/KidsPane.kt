package os.meka.android.ask

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import os.meka.core.domain.KidFeeRow
import os.meka.core.domain.KidSaved
import os.meka.core.domain.KidWeekDay
import os.meka.core.domain.KidsRules
import os.meka.core.domain.KidsWeekRules
import os.meka.core.facade.MekaCore

/**
 * Ask → Life → Kids (Kids' admin, slice 1): a card per child from About you with their sizes — shoes, football boots,
 * tops, trousers, coat, school jumper — one field each (type and Done keeps it, Clear removes it, Undo puts it
 * back), when they were last checked (lit three months on) and **All still fit**. No children yet: a line and About you.
 * Slice 2: CLUB FEES under the sizes — each fee with its line (brass within 3 days of due), Paid and Remove (Undo), and
 * one field that reads "Football £35 monthly from 1 Nov"; each fee is a bill on the radar, so Bills due reminds once.
 *
 * Slice 3: THIS WEEK over the cards — what they need in the next 7 days, one block per day ("Tomorrow" · "Alex: PE kit"),
 * fees and today's/tomorrow's school things in brass.
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; the summary, the checked line and the line
 * under a card cross-fade (the checked line's colour blends to the accent when due; a refusal blends its line to the
 * accent with a tick haptic, a save gives a light haptic); cards stagger in 40 ms apart. Reduced motion: cross-fades.
 */
@Composable
fun KidsPane(core: MekaCore, onClose: () -> Unit, onAboutYou: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val v by core.kidsView.collectAsState()
    // Per "child|size": what the field holds while it's being edited; per child, what the last save said and which
    // size a clear came from (for Undo).
    val texts = remember { mutableStateMapOf<String, String>() }
    val said = remember { mutableStateMapOf<String, KidSaved>() }
    val undoSize = remember { mutableStateMapOf<String, String>() }
    // Slice 2: per child, what the fee field holds and what the last fee action said (with the fee id Undo takes back).
    val feeTexts = remember { mutableStateMapOf<String, String>() }
    val feeSaid = remember { mutableStateMapOf<String, KidSaved>() }

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
            Text(KidsRules.TITLE, style = MekaType.greeting, color = Meka.colors.textPrimary,
                modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.KIDS)).appear(rememberAppearance(0)))
        }
        item(key = "summary") {
            Crossfade(v.summary, animationSpec = MekaMotion.appear(reduced), label = "kids-summary") { s ->
                Text(s, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(1)))
            }
        }
        if (v.empty != null) {
            item(key = "about-you") {
                Text("About you ›", style = MekaType.itemMeta, color = Meka.colors.accent,
                    modifier = Modifier.clickable(role = Role.Button) { onAboutYou() }.minTouch().appear(rememberAppearance(2)))
            }
        }
        // Slice 3: THIS WEEK — what they need in the next 7 days (School, club sessions, fees), one block per day.
        v.week?.let { w ->
            item(key = "week-head") {
                Column(Modifier.animateItem().padding(top = MekaSpace.m).appear(rememberAppearance(2)),
                    verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                    Text(KidsWeekRules.HEAD, style = MekaType.caption, color = Meka.colors.textTertiary)
                    Crossfade(w.line, animationSpec = MekaMotion.appear(reduced), label = "kids-week-line") { l ->
                        Text(l, style = MekaType.caption, color = Meka.colors.textSecondary)
                    }
                }
            }
            w.days.forEachIndexed { i, d ->
                item(key = "week-${d.day}") {
                    KidWeekDayView(d, Modifier.animateItem().appear(rememberAppearance(3 + i)))
                }
            }
            if (!w.isEmpty) {
                item(key = "week-foot") {
                    Text(KidsWeekRules.FOOT, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.animateItem())
                }
            }
        }
        v.kids.forEachIndexed { i, kid ->
            item(key = "kid-${kid.name}") {
                KidCardView(
                    kid = kid,
                    texts = texts,
                    said = said[kid.name],
                    modifier = Modifier.animateItem().padding(top = MekaSpace.m).appear(rememberAppearance(2 + i)),
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
                )
            }
        }
        item(key = "foot") {
            Text(KidsRules.FOOT, style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.animateItem().padding(top = MekaSpace.l))
        }
    }
}

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
                    "All still fit", style = MekaType.caption, color = Meka.colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                        .clickable(role = Role.Button, onClick = onFit)
                        .semantics { contentDescription = "${kid.name}'s sizes all still fit" }
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
                Field(row.hint, modifier = Modifier.weight(1f), value = texts[key] ?: row.value ?: "", onValue = { texts[key] = it }) { typed ->
                    onSave(row.size, typed)
                }
                if (row.value != null) {
                    Text("Clear", style = MekaType.caption, color = Meka.colors.textTertiary,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { onSave(row.size, "") }
                            .semantics { contentDescription = "Clear ${kid.name}'s ${row.label.lowercase()} size" }
                            .minTouch().padding(MekaSpace.xs))
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

/** Slice 3: one day of THIS WEEK: its label, then each thing (brass when it wants doing: a fee, kit for tomorrow). */
@Composable
private fun KidWeekDayView(d: KidWeekDay, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(top = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Text(d.label, style = MekaType.caption, color = Meka.colors.textTertiary)
        d.items.forEach { thing ->
            Text(thing.text, style = MekaType.body, color = if (thing.lit) Meka.colors.accent else Meka.colors.textPrimary,
                modifier = Modifier.semantics { contentDescription = thing.spoken })
        }
    }
}
