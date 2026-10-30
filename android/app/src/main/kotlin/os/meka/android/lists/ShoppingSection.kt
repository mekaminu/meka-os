package os.meka.android.lists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.TickRing
import os.meka.android.designsystem.TickSize
import os.meka.android.designsystem.bleedStart
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.today.SectionLabel
import os.meka.android.today.ShopModeScreen
import os.meka.core.domain.ShopModeRules
import os.meka.core.domain.ShoppingItem
import os.meka.core.domain.ShoppingRules
import os.meka.core.domain.ShoppingView
import os.meka.core.facade.MekaCore

/**
 * SHOPPING (family sharing, slice 1): the shared shopping list. To buy in the order things were added, then Got for a
 * week. Tapping a row's ring ticks it: the ring sweeps, fills and draws its check (`checkDraw`) with a light haptic and
 * the row glides down under Got (the list's item motion, the same key); tapping a got ring puts it back (tick haptic).
 * Remove takes a row off for good; Clear under Got clears what was bought. Reduced motion: the check shown at once,
 * rows cross-fade.
 */
internal fun LazyListScope.shopping(v: ShoppingView, core: MekaCore, act: (suspend () -> Unit) -> Unit) {
    item(key = "sh-line") {
        val haptics = rememberMekaHaptics()
        // Shop mode (Checklists in tasks, slice 2): the list full screen, big rows, the screen kept awake.
        var shopping by remember { mutableStateOf(false) }
        Row(Modifier.animateItem().fillMaxWidth().padding(bottom = MekaSpace.xs), verticalAlignment = Alignment.CenterVertically) {
            Text(v.line, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.weight(1f))
            if (ShopModeRules.offer(v.toBuy.size + v.got.size)) {
                Text(
                    ShopModeRules.OPEN, style = MekaType.caption, color = Meka.colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { haptics.light(); shopping = true }
                        .minTouch().padding(horizontal = MekaSpace.xs),
                )
            }
        }
        if (shopping) {
            ShopModeScreen(
                view = ShopModeRules.ofShopping(v),
                onTick = { r -> act { if (r.done) core.putBackShopping(r.id) else core.gotShopping(r.id) } },
                onClose = { shopping = false },
            )
        }
    }
    if (v.toBuy.isEmpty() && v.got.isEmpty()) empty("shopping", ShoppingRules.EMPTY_LINE)
    items(v.toBuy, key = { "sh-${it.id}" }) { s -> ShoppingRow(s, core, act, Modifier.animateItem()) }
    if (v.got.isNotEmpty()) {
        item(key = "sh-got") {
            Row(Modifier.animateItem().fillMaxWidth().padding(top = MekaSpace.m), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Got", Modifier.weight(1f))
                Text(
                    "Clear", style = MekaType.caption, color = Meka.colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.s)).clickable(role = Role.Button) { act { core.clearGotShopping() } }
                        .minTouch().padding(horizontal = MekaSpace.xs),
                )
            }
        }
        items(v.got, key = { "sh-${it.id}" }) { s -> ShoppingRow(s, core, act, Modifier.animateItem()) }
    }
}

@Composable
private fun ShoppingRow(s: ShoppingItem, core: MekaCore, act: (suspend () -> Unit) -> Unit, modifier: Modifier) {
    val haptics = rememberMekaHaptics()
    Row(modifier.fillMaxWidth().padding(horizontal = MekaSpace.xs), verticalAlignment = Alignment.CenterVertically) {
        // Works by hand (quality gate): the 22 dp ring answers a 44 dp touch that bleeds into the row's start, as on Today.
        Box(
            Modifier.bleedStart(TickSize.BLEED).size(TickSize.TOUCH).clip(CircleShape)
                .semantics { contentDescription = (if (s.got) "Put back " else "Got ") + s.title }
                .clickable(role = Role.Checkbox) {
                    if (s.got) { haptics.tick(); act { core.putBackShopping(s.id) } } else { haptics.light(); act { core.gotShopping(s.id) } }
                },
            contentAlignment = Alignment.Center,
        ) { TickRing(s.got, Modifier.size(TickSize.DRAWN)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            Text(s.title, style = MekaType.body, color = if (s.got) Meka.colors.textTertiary else Meka.colors.textPrimary)
            s.meta?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textSecondary) }
        }
        Text(
            "Remove", style = MekaType.caption, color = Meka.colors.textTertiary,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { act { core.removeShopping(s.id) } }
                .semantics { contentDescription = "Remove ${s.title}" }
                .minTouch().padding(horizontal = MekaSpace.xs),
        )
    }
}

/** Add to shopping: one line, several things separated by commas ("milk, eggs"). */
@Composable
internal fun AddShopping(add: (String) -> Unit) {
    Column(Modifier.padding(top = MekaSpace.l)) {
        Field(ShoppingRules.ADD_HINT) { add(it) }
    }
}
