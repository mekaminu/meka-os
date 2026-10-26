package os.meka.android.ask

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.minTouch
import os.meka.android.lists.Field
import os.meka.core.domain.TripGroupRules
import os.meka.core.domain.TripList
import os.meka.core.domain.TripListGroup
import os.meka.core.domain.TripListRow
import os.meka.core.domain.TripListRules
import os.meka.core.domain.TripWho

/**
 * Trip lists editing, slice 4a (Meka 2026-10-26: move a thing to another group, rename a group "to my liking"): what
 * this screen has changed and is still writing, drawn in the same frame as the tap with [TripGroupRules.preview] (the
 * rule for every tap). Each write runs after; once the view has moved on since the write began, the overlays go.
 */
@Stable
internal class TripGroupEdits {
    val moves = mutableStateMapOf<String, String>()
    val renames = mutableStateMapOf<String, String>()
    val deleted = mutableStateMapOf<String, Boolean>()
    var order by mutableStateOf<List<String>?>(null)
    val added = mutableStateListOf<String>()
    /** The item sheet's changes, drawn on the row until the view has them. */
    val rows = mutableStateMapOf<String, TripListRow>()
    private var inFlight = 0
    private var seen by mutableIntStateOf(0)

    fun viewChanged() { seen++; if (inFlight == 0) clear() }

    fun clear() { moves.clear(); renames.clear(); deleted.clear(); order = null; added.clear(); rows.clear() }

    fun apply(groups: List<TripListGroup>, list: TripList): List<TripListGroup> =
        TripGroupRules.preview(groups, moves, renames, deleted.filterValues { it }.keys, order, added, list.wire)
            .map { g -> if (rows.isEmpty()) g else g.copy(rows = g.rows.map { rows[it.id] ?: it }) }

    /** Shows [local] now, then runs [write]; a failed write takes it away again. */
    fun run(scope: CoroutineScope, local: () -> Unit, write: suspend () -> Boolean) {
        local()
        inFlight++
        val at = seen
        scope.launch {
            val ok = runCatching { write() }.getOrDefault(false)
            inFlight--
            if (!ok || (inFlight == 0 && seen != at)) clear()
        }
    }
}

@Composable
internal fun rememberTripGroupEdits(): TripGroupEdits = remember { TripGroupEdits() }

/** A small pill button on the sheet and the group menu (48 dp target, on the 8/16 rhythm). */
@Composable
internal fun TripPill(label: String, on: Boolean = false, tag: String? = null, description: String = label, onClick: () -> Unit) {
    val reduced = Meka.reducedMotion
    val bg by animateColorAsState(if (on) Meka.colors.accent else Meka.colors.surfaceRaised, MekaMotion.appear(reduced), label = "trip-pill")
    Text(
        label, style = MekaType.caption, color = if (on) Meka.colors.onAccent else Meka.colors.textPrimary,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(bg)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description; if (on) stateDescription = "Selected" }
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs)
            .let { if (tag != null) it.testTag(tag) else it },
    )
}

/**
 * A group's menu under its head (⋯): Rename (a field starting from the name), Move up, Move down, Delete group (its
 * things go to Other, with Undo).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TripGroupMenu(
    g: TripListGroup, first: Boolean, last: Boolean,
    onRename: (String) -> Unit, onMove: (Int) -> Unit, onDelete: () -> Unit,
) {
    var renaming by rememberSaveable(g.key) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .padding(MekaSpace.xs).testTag("trip-group-menu"),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            TripPill(TripGroupRules.RENAME, on = renaming, tag = "trip-group-rename") { renaming = !renaming }
            if (!first) TripPill(TripGroupRules.MOVE_UP, tag = "trip-group-up") { onMove(-1) }
            if (!last) TripPill(TripGroupRules.MOVE_DOWN, tag = "trip-group-down") { onMove(1) }
            TripPill(TripGroupRules.DELETE, tag = "trip-group-delete", description = "${TripGroupRules.DELETE} ${g.name}") { onDelete() }
        }
        if (renaming) {
            Field(g.name, description = TripGroupRules.RENAME_HINT, modifier = Modifier.testTag("trip-group-rename-field")) { onRename(it); renaming = false }
        }
    }
}

/**
 * The item sheet (⋯ on a row, or a long press): its name (type a new one), how many (− / +), whose (To pack), the
 * group (every group, lit where it is, and New group…), and Remove.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TripItemSheet(
    row: TripListRow, list: TripList, group: String, groups: List<String>, qty: Int,
    onName: (String) -> Unit, onQty: (Int) -> Unit, onWho: (TripWho) -> Unit, onGroup: (String) -> Unit, onRemove: () -> Unit, onDone: () -> Unit,
) {
    var newGroup by rememberSaveable(row.id) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .padding(MekaSpace.m).testTag("trip-item-sheet"),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Text(TripGroupRules.NAME_LABEL, style = MekaType.sectionLabel, color = Meka.colors.textTertiary)
        Field(row.text, description = "Rename ${row.text}", modifier = Modifier.testTag("trip-item-name")) { onName(it) }
        Text(TripGroupRules.QTY_LABEL, style = MekaType.sectionLabel, color = Meka.colors.textTertiary)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            TripPill("−", tag = "trip-item-less", description = "One fewer") { if (qty > 1) onQty(qty - 1) }
            Text("$qty", style = MekaType.taskTitle, color = Meka.colors.textPrimary, modifier = Modifier.testTag("trip-item-qty"))
            TripPill("+", tag = "trip-item-more-qty", description = "One more") { if (qty < TripListRules.MAX_QTY) onQty(qty + 1) }
        }
        if (list == TripList.PACK) {
            Text(TripGroupRules.WHO_LABEL, style = MekaType.sectionLabel, color = Meka.colors.textTertiary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                for (w in TripWho.entries) TripPill(w.label, on = row.sub == w.label, tag = "trip-item-who-${w.wire}") { onWho(w) }
            }
        }
        Text(TripGroupRules.GROUP_LABEL, style = MekaType.sectionLabel, color = Meka.colors.textTertiary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            for (name in groups) TripPill(name, on = name == group, tag = "trip-item-group-$name", description = "Move to $name") { if (name != group) onGroup(name) }
            TripPill(TripGroupRules.NEW_GROUP, on = newGroup, tag = "trip-item-new-group") { newGroup = !newGroup }
        }
        if (newGroup) Field(TripGroupRules.ADD_GROUP_HINT, modifier = Modifier.testTag("trip-item-new-group-field")) { onGroup(it); newGroup = false }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalAlignment = Alignment.CenterVertically) {
            Text(TripGroupRules.REMOVE, style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onRemove)
                    .semantics { contentDescription = "Remove ${row.text}" }.minTouch().padding(MekaSpace.xs).testTag("trip-item-remove"))
            Spacer(Modifier.weight(1f))
            Text(TripListRules.DONE, style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onDone)
                    .minTouch().padding(MekaSpace.xs).testTag("trip-item-done"))
        }
    }
}

/** "+ Group" at the foot of a tab's groups: a field to name a new one. */
@Composable
internal fun TripAddGroup(onAdd: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Text(TripGroupRules.ADD_GROUP, style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { open = !open }
                .semantics { contentDescription = "Add a group" }.minTouch().padding(MekaSpace.xs).testTag("trip-add-group"))
        if (open) Field(TripGroupRules.ADD_GROUP_HINT, modifier = Modifier.testTag("trip-add-group-field")) { onAdd(it); open = false }
    }
}
