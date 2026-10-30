package os.meka.android.ask

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.sharedTitleInPane
import os.meka.android.shell.MoreItem
import os.meka.android.shell.SharedMotion
import os.meka.android.shell.ShellNav
import os.meka.core.domain.AiStatusView
import os.meka.core.facade.MekaCore

/** The pane's own list, for the hand tests' bounded scrolls (quality gate (3)). */
const val SETTINGS_LIST_TAG = "settings-list"

/**
 * Ask → Settings (Fold review 2026-10-12, item 7): every setting, behind one row on Ask. Work mode, Protected time,
 * Notifications, Appearance (unfolds in place), MEKA's voice, Talk, Calendars, Family and Watch (once connected), What
 * MEKA may do, MEKA's AI (whether it's on and the month's spend, moved here from under Ask's field), Activity, Setup,
 * Health, Vault and Your data. Each row opens its pane over this one, and back drops to here.
 *
 * Motion: the pane springs up (MekaPane) with the Settings row's title travelling in; rows stagger in 40 ms apart;
 * each row's label travels into the pane it opens; lines cross-fade and lit ones blend to the accent. Reduced motion:
 * cross-fades.
 */
@Composable
fun SettingsPane(
    core: MekaCore, connected: Boolean, pane: MoreItem?, onClose: () -> Unit, open: (MoreItem) -> Unit,
    appearanceOpen: Boolean, toggleAppearance: () -> Unit, openTopics: () -> Unit, playOpening: () -> Unit,
) {
    val lists by core.listsView.collectAsState()
    val work by core.workMode.collectAsState()
    val health by core.healthView.collectAsState()
    val setup by core.setupView.collectAsState()
    var ai by remember { mutableStateOf<AiStatusView?>(null) }
    LaunchedEffect(Unit) { ai = runCatching { core.aiStatus() }.getOrNull() }
    LazyColumn(
        Modifier.fillMaxSize().testTag(SETTINGS_LIST_TAG),
        contentPadding = PaddingValues(MekaSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        item(key = "close") {
            Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
        }
        item(key = "title") {
            Text(MoreItem.SETTINGS.label, style = MekaType.greeting, color = Meka.colors.textPrimary,
                modifier = Modifier.padding(bottom = MekaSpace.xs).sharedTitleInPane(SharedMotion.paneKey(MoreItem.SETTINGS))
                    .appear(rememberAppearance(0)))
        }
        itemsIndexed(ShellNav.settings(connected), key = { _, it -> it.name }) { i, item ->
            val step = Modifier.appear(rememberAppearance(i + 1))
            when {
                ShellNav.unfoldsInPlace(item) -> AppearanceRow(appearanceOpen, step, openTopics = openTopics, playOpening = playOpening, toggle = toggleAppearance, core = core)
                item == MoreItem.AI -> AiSpendRow(ai, step)
                else -> MoreRow(
                    item, ShellNav.moreLine(item, lists.dueCount, work.atWork, health?.summary, setup?.summary),
                    ShellNav.moreLit(item, lists.dueCount, health?.attention ?: 0, setup?.toDo ?: 0), pane == item, step,
                ) { open(item) }
            }
        }
    }
}
