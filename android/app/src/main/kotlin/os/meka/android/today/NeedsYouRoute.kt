package os.meka.android.today

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import os.meka.android.designsystem.MekaSharedLayout
import os.meka.android.shell.SharedMotion
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.work.AfterWorkCard
import os.meka.android.work.AfterWorkHost
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
    var showAfterWork by rememberSaveable { mutableStateOf(false) }

    MekaSharedLayout(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Meka.colors.background)) {
            val twoPane = maxWidth >= 600.dp
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
                    item(key = "after-work") {
                        AfterWorkCard(core, Modifier.animateItem().padding(bottom = MekaSpace.s).appear(rememberAppearance(1))) { showAfterWork = true }
                    }
                    if (today.needsYou.isEmpty()) {
                        item(key = "clear") {
                            Text("Nothing is waiting on you.", style = MekaType.upNextTitle, color = Meka.colors.textSecondary,
                                modifier = Modifier.animateItem().appear(rememberAppearance(1)))
                        }
                    }
                    items(today.needsYou, key = { it.task.id }) { n ->
                        val motion = RowMotion(shareTitle = true, titleVisible = SharedMotion.rowTitleVisible(n.task.id, selectedId, !twoPane, false, emptySet()))
                        TaskRow(n.task, actions, reason = n.reason, motion = motion, modifier = Modifier.animateItem().appear(rememberAppearance(1)))
                    }
                }
            }
            // Opening the Fold grows the detail out beside the list; closed, the detail springs up over it.
            TwoPaneMorph(
                twoPane,
                list = list,
                detail = { m -> DetailPane(selected, conflicts.filter { it.taskId == selected?.id }, actions, m) },
            )
            var shown by remember { mutableStateOf<Task?>(null) }
            if (selected != null) shown = selected
            MekaPane(visible = selected != null && !twoPane) {
                shown?.let { s ->
                    DetailPane(s, conflicts.filter { it.taskId == s.id }, actions, Modifier.fillMaxSize(), onClose = { selectedId = null })
                }
            }
            MekaPane(visible = showAfterWork) { AfterWorkHost(onClose = { showAfterWork = false }) }
        }
    }
}
