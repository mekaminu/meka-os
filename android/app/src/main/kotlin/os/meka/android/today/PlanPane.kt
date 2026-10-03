package os.meka.android.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.core.domain.DayPlanner
import os.meka.core.facade.MekaCore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val hm = DateTimeFormatter.ofPattern("HH:mm")
private fun t(ms: Long) = hm.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/**
 * "Plan my day": a suggested timeline of tasks fitted around calendar events and fixtures. Nothing changes until
 * the owner taps Apply (autonomy level 1: suggest).
 */
@Composable
fun PlanPane(core: MekaCore, onClose: () -> Unit) {
    var plan by remember { mutableStateOf<DayPlanner.Plan?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { plan = core.planDay() }

    Column(Modifier.fillMaxSize().padding(MekaSpace.gutter).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(MekaSpace.s)) {
        Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { onClose() }.padding(vertical = MekaSpace.s))
        Text("Your day", style = MekaType.greeting, color = Meka.colors.textPrimary)
        val p = plan
        if (p == null) {
            Text("Planning…", style = MekaType.itemMeta, color = Meka.colors.textTertiary)
            return@Column
        }
        if (p.isEmpty && p.unplaced.isEmpty()) {
            Text("Nothing to plan: every open task is already scheduled.", style = MekaType.itemMeta, color = Meka.colors.textSecondary)
            return@Column
        }
        Text("A suggestion. Nothing changes until you apply it.", style = MekaType.itemMeta, color = Meka.colors.textSecondary)
        Spacer(Modifier.height(MekaSpace.s))

        // One timeline: fixed events and suggested tasks, in time order.
        val rows = p.busy.map { Triple(it.startAtMs, "${t(it.startAtMs)}–${t(it.endAtMs)}", it.title to false) } +
            p.placements.map { Triple(it.startMs, "${t(it.startMs)}–${t(it.endMs)}", it.task.title to true) }
        rows.sortedBy { it.first }.forEach { (_, time, item) ->
            val (title, suggested) = item
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m))
                    .background(if (suggested) Meka.colors.surfaceRaised else Meka.colors.background)
                    .padding(horizontal = MekaSpace.m, vertical = MekaSpace.s),
            ) {
                Text(time, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.width(104.dp))
                Text(title, style = MekaType.itemTitle, color = if (suggested) Meka.colors.textPrimary else Meka.colors.textTertiary)
            }
        }
        if (p.unplaced.isNotEmpty()) {
            Spacer(Modifier.height(MekaSpace.s))
            Text("Won't fit today: " + p.unplaced.joinToString(", ") { it.title }, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
        }
        Text("${p.freeMinutesLeft / 60} h ${p.freeMinutesLeft % 60} min still free.", style = MekaType.caption, color = Meka.colors.textTertiary)
        Spacer(Modifier.height(MekaSpace.m))
        if (!p.isEmpty) {
            Text(
                "Apply plan", style = MekaType.itemTitle, color = Meka.colors.onAccent,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent)
                    .clickable(role = Role.Button) { scope.launch { core.applyPlan(p); onClose() } }
                    .padding(horizontal = MekaSpace.l, vertical = MekaSpace.m),
            )
        }
    }
}
