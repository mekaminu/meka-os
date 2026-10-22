package os.meka.android.goals

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.FastingRules
import os.meka.core.domain.FastingView

/*
 * FASTING's shared pieces. The Fasting card that sat at the top of Goals was replaced on 2026-10-22 by Goals' small
 * [FastingTile] and the Fasting screen ([FastingPane], the approved design); what both still use lives here.
 */

/** The "you did it" burst plays once per fast on this phone. */
object FastBurst {
    private const val PREFS = "meka.hints"
    private const val KEY = "fastBurst"

    /** True the first time for [fastId]; marked at once so a recomposition or restart doesn't replay it. */
    @Synchronized
    fun claim(context: Context, fastId: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY, null) == fastId) return false
        prefs.edit().putString(KEY, fastId).apply()
        return true
    }
}

/**
 * A custom "until" end: a day ([FastingView.untilDays]) and a time in half-hour steps, checked by the core as you
 * choose ("Goal 4 d 18 h · starts now" or "Pick an end at least 12 hours away"). Start is offered only when it's valid.
 */
@Composable
internal fun UntilPicker(v: FastingView, start: (Long) -> Unit) {
    val haptics = rememberMekaHaptics()
    val days = v.untilDays
    if (days.isEmpty()) return
    var day by rememberSaveable { mutableLongStateOf(days.getOrElse(1) { days.first() }.epochDay) }
    var minute by rememberSaveable { mutableIntStateOf(FastingRules.UNTIL_HOUR * 60) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // The check moves on with the clock while the picker is open.
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val chosen = days.firstOrNull { it.epochDay == day } ?: days.first()
    val untilMs = v.untilAt(chosen, minute)
    val pick = FastingRules.untilPick(now, untilMs)
    val reduced = Meka.reducedMotion
    Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs), modifier = Modifier.padding(top = MekaSpace.xxs)) {
        Chips("Ends on", days.map { it.label to (it.epochDay == chosen.epochDay) }) { i -> haptics.tick(); day = days[i].epochDay }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.m)) {
            Text("At", style = MekaType.caption, color = Meka.colors.textTertiary)
            Action("‹") { haptics.tick(); minute = FastingRules.stepUntilTime(minute, -1) }
            AnimatedContent(
                targetState = FastingRules.hm(minute),
                transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
                label = "until-time",
            ) { t -> Text(t, style = MekaType.itemTitle, color = Meka.colors.textPrimary, modifier = Modifier.semantics { contentDescription = "Ends at $t" }) }
            Action("›") { haptics.tick(); minute = FastingRules.stepUntilTime(minute, 1) }
        }
        AnimatedContent(
            targetState = pick,
            transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
            label = "until-line",
        ) { p -> Text(p.line, style = MekaType.caption, color = if (p.ok) Meka.colors.textSecondary else Meka.colors.critical) }
        AnimatedVisibility(pick.ok, enter = unfold(), exit = fold()) {
            Action("Start fast until ${chosen.label} ${FastingRules.hm(minute)}") { start(untilMs) }
        }
    }
}

