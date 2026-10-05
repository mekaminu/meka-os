package os.meka.android.today

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaPane
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.core.domain.Task
import os.meka.core.facade.MekaCore

/**
 * NEEDS YOU: everything waiting on a decision (conflicts, overdue, due today but unscheduled). Approvals join this
 * list in V1. Same layout rules as Today: two panes when wide, detail springs up over the list when narrow.
 */
@Composable
fun NeedsYouRoute(core: MekaCore) {
    val today by core.today.collectAsState()
    val conflicts by core.conflicts.collectAsState()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val actions = todayActions(core, scope, { selectedId }) { selectedId = it }
    val selected = today.needsYou.map { it.task }.firstOrNull { it.id == selectedId }

    BoxWithConstraints(Modifier.fillMaxSize().background(Meka.colors.background)) {
        val list: @Composable (Modifier) -> Unit = { m ->
            LazyColumn(
                m,
                contentPadding = PaddingValues(horizontal = MekaSpace.gutter, vertical = MekaSpace.xl),
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                item(key = "title") {
                    Text("Needs you", style = MekaType.greeting, color = Meka.colors.textPrimary,
                        modifier = Modifier.padding(bottom = MekaSpace.l).appear(rememberAppearance(0)))
                }
                if (today.needsYou.isEmpty()) {
                    item(key = "clear") {
                        Text("Nothing is waiting on you.", style = MekaType.upNextTitle, color = Meka.colors.textSecondary,
                            modifier = Modifier.animateItem().appear(rememberAppearance(1)))
                    }
                }
                items(today.needsYou, key = { it.task.id }) { n ->
                    TaskRow(n.task, actions, reason = n.reason, modifier = Modifier.animateItem().appear(rememberAppearance(1)))
                }
            }
        }
        if (maxWidth >= 600.dp) {
            Row(Modifier.fillMaxSize()) {
                list(Modifier.weight(0.55f).fillMaxHeight())
                Box(Modifier.width(1.dp).fillMaxHeight().background(Meka.colors.hairline))
                DetailPane(selected, conflicts.filter { it.taskId == selected?.id }, actions, Modifier.weight(0.45f).fillMaxHeight())
            }
        } else {
            list(Modifier.fillMaxSize())
            var shown by remember { mutableStateOf<Task?>(null) }
            if (selected != null) shown = selected
            MekaPane(visible = selected != null) {
                shown?.let { s ->
                    DetailPane(s, conflicts.filter { it.taskId == s.id }, actions, Modifier.fillMaxSize(), onClose = { selectedId = null })
                }
            }
        }
    }
}
