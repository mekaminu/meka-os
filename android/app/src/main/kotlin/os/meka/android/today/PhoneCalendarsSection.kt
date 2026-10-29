package os.meka.android.today

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import os.meka.android.MekaApplication
import os.meka.android.calendar.PhoneCalendars
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.LoneWordGlue
import os.meka.core.domain.PhoneCalendarRules
import os.meka.core.facade.MekaCore

/**
 * The Week board (j): Calendars → THIS PHONE'S CALENDARS. Before he allows it, one line and Allow (Android's own
 * Calendar prompt; refused for good → the line says where to turn it on and a tap opens Navilife's app settings). Once
 * allowed, each calendar kept on the phone with its events in the next five weeks, and one line that they show on this
 * phone only. The list cross-fades as it changes. Read on the phone only ([PhoneCalendars]).
 */
@Composable
internal fun PhoneCalendarsSection(core: MekaCore, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val haptics = rememberMekaHaptics()
    var permitted by remember { mutableStateOf(PhoneCalendars.granted(context)) }
    var refused by remember { mutableStateOf(false) }
    val found by core.phoneCalendars.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val now = PhoneCalendars.granted(context)
        if (now != permitted) { permitted = now; readAgain(context) }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        permitted = ok
        refused = !ok
        readAgain(context)
    }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .padding(MekaSpace.m).testTag("phone-calendars"),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Text(PhoneCalendarRules.SECTION, style = MekaType.sectionLabel, color = Meka.colors.textTertiary)
        val state = when {
            !permitted -> null
            else -> found.orEmpty()
        }
        Crossfade(state, animationSpec = MekaMotion.appear(Meka.reducedMotion), label = "phone-calendars") { list ->
            if (list == null) {
                Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    Text(LoneWordGlue.kept(if (refused) PhoneCalendarRules.DENIED_LINE else PhoneCalendarRules.ASK_LINE),
                        style = MekaType.caption, color = Meka.colors.textSecondary,
                        modifier = if (refused) Modifier.clickable(role = Role.Button) { openAppSettings(context) } else Modifier)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(PhoneCalendarRules.ALLOW, style = MekaType.itemMeta, color = Meka.colors.accent,
                            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).clickable(role = Role.Button) {
                                haptics.tick()
                                if (refused) openAppSettings(context) else runCatching { ask.launch(Manifest.permission.READ_CALENDAR) }
                            }.minTouch().testTag("phone-calendars-allow"))
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    if (list.isEmpty()) Text(PhoneCalendarRules.NONE_LINE, style = MekaType.caption, color = Meka.colors.textTertiary)
                    list.forEach { c ->
                        Text(LoneWordGlue.kept(c.line), style = MekaType.itemMeta, color = Meka.colors.textPrimary)
                    }
                    if (list.isNotEmpty()) {
                        Text(LoneWordGlue.kept(PhoneCalendarRules.ONLY_HERE), style = MekaType.caption, color = Meka.colors.textTertiary)
                    }
                }
            }
        }
    }
}

private fun readAgain(context: Context) {
    (context.applicationContext as? MekaApplication)?.readPhoneCalendars()
}

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
