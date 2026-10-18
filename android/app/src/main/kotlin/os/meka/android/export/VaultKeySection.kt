package os.meka.android.export

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import os.meka.core.domain.VaultKeyRules
import os.meka.core.domain.VaultRequest
import os.meka.core.facade.MekaCore

/**
 * The Vault key (Vault slice 2a), under the Vault's documents: the household key's id and where it is ("Vault key
 * 4F2A 91D0 · on this Fold and the Mac"), whether the recovery key opens it, and each device asking for it with its
 * six-digit code, Allow and Not this one; or, on a device without it, what it waits for (lit) and Ask again.
 *
 * Motion: the block staggers in after the documents; its line and sub cross-fade, the line blending to the accent
 * while it waits; a request card grows in and out on the expand spring; Allow gives a light haptic, Not this one a
 * tick. Reduced motion: cross-fades.
 */
@Composable
fun VaultKeySection(core: MekaCore, firstIndex: Int) {
    val v by core.vaultKeyView.collectAsState()
    if (!v.shown) return
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    var busy by remember { mutableStateOf(false) }
    val lineColor by animateColorAsState(
        if (v.lit) Meka.colors.accent else Meka.colors.textPrimary, MekaMotion.appear(reduced), label = "vault-key-line",
    )
    Column(
        Modifier.fillMaxWidth().animateContentSize(MekaMotion.expand(reduced)),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Text("VAULT KEY", style = MekaType.sectionLabel, color = Meka.colors.textTertiary,
            modifier = Modifier.padding(top = MekaSpace.m).appear(rememberAppearance(firstIndex)))
        Crossfade(v.line, animationSpec = MekaMotion.appear(reduced), label = "vault-key") { line ->
            Text(line, style = MekaType.body, color = lineColor, modifier = Modifier.appear(rememberAppearance(firstIndex + 1)))
        }
        Crossfade(v.sub, animationSpec = MekaMotion.appear(reduced), label = "vault-key-sub") { sub ->
            sub?.let { Text(it, style = MekaType.caption, color = Meka.colors.textSecondary) }
        }
        if (v.canAskAgain) {
            KeyAction(VaultKeyRules.ASK_AGAIN, accent = true, spoken = "Ask again for the Vault key") {
                haptics.light(); scope.launch { runCatching { core.askVaultAgain() } }
            }
        }
        v.requests.forEach { r ->
            key(r.deviceId) {
                RequestCard(
                    r, busy,
                    onAllow = {
                        busy = true; haptics.light()
                        scope.launch { runCatching { core.allowVaultDevice(r.deviceId) }; busy = false }
                    },
                    onRefuse = { haptics.tick(); scope.launch { runCatching { core.refuseVaultDevice(r.deviceId) } } },
                )
            }
        }
    }
}

@Composable
private fun RequestCard(r: VaultRequest, busy: Boolean, onAllow: () -> Unit, onRefuse: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs)
            .semantics(mergeDescendants = false) { contentDescription = r.spoken },
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Text(r.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
        Text(r.line, style = MekaType.caption, color = Meka.colors.accent)
        Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            KeyAction(VaultKeyRules.ALLOW, accent = !busy, spoken = "${VaultKeyRules.ALLOW}: ${r.title}", onClick = { if (!busy) onAllow() })
            KeyAction(VaultKeyRules.REFUSE, accent = false, spoken = "${VaultKeyRules.REFUSE}: ${r.title}", onClick = onRefuse)
        }
    }
}

@Composable
private fun KeyAction(label: String, accent: Boolean, spoken: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = spoken }
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    ) {
        Text(label, style = MekaType.itemMeta, color = if (accent) Meka.colors.accent else Meka.colors.textSecondary)
    }
}
