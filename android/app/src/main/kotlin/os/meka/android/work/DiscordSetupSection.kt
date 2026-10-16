package os.meka.android.work

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.DiscordRules
import os.meka.core.facade.MekaCore

/**
 * Work mode → Discord (Discord channel watch, slice 1): every channel Discord's notifications have named, each with
 * Follow / Stop (synced, so the Mac's list matches); how to set Discord up so the channel's posts reach the phone; and
 * the limits. Posts from channels he doesn't follow are dropped where they arrive.
 */
@Composable
internal fun DiscordSetupSection(core: MekaCore, index: Int) {
    val context = LocalContext.current
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    val reduced = Meka.reducedMotion
    val view by core.discordWatch.collectAsState()
    var said by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().appear(rememberAppearance(index)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Text(DiscordRules.TITLE.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary)
        Crossfade(said ?: view.statusLine, animationSpec = MekaMotion.appear(reduced), label = "discord-line") { line ->
            Text(line, style = MekaType.itemMeta, color = if (view.rows.any { it.watched }) Meka.colors.accent else Meka.colors.textSecondary)
        }
        Text(DiscordRules.SETUP_LINE, style = MekaType.caption, color = Meka.colors.textTertiary)
        Text("Open Discord's notification settings", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) {
                haptics.tick()
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, DiscordRules.PACKAGES.first())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }.padding(vertical = MekaSpace.xs))
        view.emptyLine?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
        view.rows.forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = MekaSpace.xxs), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(row.label, style = MekaType.body, color = Meka.colors.textPrimary)
                    Text(row.line, style = MekaType.caption, color = Meka.colors.textTertiary)
                }
                SettingChip(if (row.watched) "Following" else "Follow", row.watched,
                    spoken = if (row.watched) "Stop following ${row.label}" else "Follow ${row.label}") {
                    haptics.tick()
                    scope.launch { said = runCatching { core.setDiscordWatched(row.key, !row.watched) }.getOrNull() }
                }
            }
        }
        Text("What Navilife can't see", style = MekaType.itemMeta, color = Meka.colors.textPrimary, modifier = Modifier.padding(top = MekaSpace.m))
        DiscordRules.LIMITS.forEach { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
    }
}
