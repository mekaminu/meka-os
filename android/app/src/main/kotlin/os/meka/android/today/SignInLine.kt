package os.meka.android.today

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.SignInLine
import os.meka.core.facade.ConnectStart
import os.meka.core.facade.MekaCore

/**
 * Today's sign-in line (Reliability first, item 2): "Google sign-in ends tomorrow at 14:05 · Reconnect" in the accent,
 * or "Google sign-in expired · calendars aren't updating · Reconnect" in the critical colour. Tapping the line unfolds
 * why (expand spring, tick haptic) with **Reconnect**, which opens the provider's sign-in page (light haptic) asking
 * for editing again when the account had it. The line cross-fades as it changes; its colour blends.
 */
@Composable
fun SignInLineView(line: SignInLine, core: MekaCore, modifier: Modifier = Modifier) {
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var open by rememberSaveable { mutableStateOf(false) }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    val color by animateColorAsState(if (line.critical) Meka.colors.critical else Meka.colors.accent, MekaMotion.appear(reduced), label = "signin-line")

    fun reconnect() = scope.launch {
        haptics.light()
        problem = null
        when (val r = core.startConnect(line.provider, line.editing)) {
            is ConnectStart.OpenBrowser -> runCatching { uri.openUri(r.url) }.onFailure { problem = "Couldn't open the browser." }
            ConnectStart.NotSetUp -> problem = "This calendar isn't set up on your server any more."
            is ConnectStart.Failed -> problem = r.reason
        }
    }

    Column(modifier.fillMaxWidth()) {
        Crossfade(line.text, Modifier.fillMaxWidth(), MekaMotion.appear(reduced), label = "signin-text") { text ->
            Text(
                text, style = MekaType.caption, color = color,
                modifier = Modifier.clickable(role = Role.Button) { haptics.tick(); open = !open }
                    .padding(vertical = MekaSpace.xs).semantics { contentDescription = line.spoken },
            )
        }
        AnimatedVisibility(
            open,
            enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
            exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        ) {
            Column(Modifier.fillMaxWidth().padding(start = MekaSpace.m), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                Text(line.detail, style = MekaType.caption, color = Meka.colors.textSecondary)
                problem?.let { Text(it, style = MekaType.caption, color = Meka.colors.critical) }
                Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.l), modifier = Modifier.padding(top = MekaSpace.xxs)) {
                    Text(
                        "Reconnect", style = MekaType.caption, color = Meka.colors.accent,
                        modifier = Modifier.clickable(role = Role.Button) { reconnect() }.padding(vertical = MekaSpace.xxs),
                    )
                }
            }
        }
    }
}
