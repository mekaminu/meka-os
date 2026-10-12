package os.meka.android.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.core.domain.RecoveryKeyRules

/**
 * Shown only while this device isn't syncing (silence otherwise). One-time: server address + enrolment code, or
 * (Recovery key, slice 2) the 24 words when both devices were lost.
 * Everything keeps working offline before and after; enrolling just starts pushing local work to the server.
 */
@Composable
fun ConnectCard(
    defaultUrl: String,
    connect: suspend (url: String, code: String) -> String?,
    modifier: Modifier = Modifier,
    recover: (suspend (url: String, words: String) -> String?)? = null,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    var url by rememberSaveable { mutableStateOf(defaultUrl) }
    var code by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    // Recovery key, slice 2: the 24 words instead of the code (kept out of saved state; the window is secure meanwhile).
    var recovering by rememberSaveable { mutableStateOf(false) }
    var words by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    os.meka.android.ask.SecureWhile(open && recovering)

    if (!open) {
        Text(
            "This device isn't syncing yet · Connect",
            style = MekaType.caption, color = Meka.colors.accent,
            modifier = modifier.clickable { open = true }.padding(vertical = MekaSpace.xs),
        )
    } else Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.l)).background(Meka.colors.surfaceRaised).padding(MekaSpace.l),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.m),
    ) {
        Text(if (recovering) "Recover this device" else "Connect this device", style = MekaType.itemTitle, color = Meka.colors.textPrimary)
        Field("Server address", url, { url = it }, KeyboardType.Uri, secret = false)
        val reading = if (recovering) RecoveryKeyRules.read(words) else null
        if (recovering) {
            Text(RecoveryKeyRules.RECOVER_HOW, style = MekaType.caption, color = Meka.colors.textTertiary)
            WordsField(words) { words = it }
            // Live: "12 of 24 words" · "Word 3 isn't one of the key's words" · nothing once they read.
            val line = reading?.let { RecoveryKeyRules.readingLine(it) }
            androidx.compose.animation.Crossfade(line, animationSpec = os.meka.android.designsystem.MekaMotion.appear(Meka.reducedMotion), label = "recover-reading") { l ->
                if (l != null) Text(l, style = MekaType.caption, color = Meka.colors.textSecondary)
            }
        } else {
            Field("Enrolment code", code, { code = it }, KeyboardType.Password, secret = true)
        }
        error?.let { Text(it, style = MekaType.caption, color = Meka.colors.critical) }
        Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.l)) {
            val ready = if (recovering) reading is RecoveryKeyRules.Reading.Ok else code.isNotBlank()
            Text(
                if (busy) "Connecting…" else "Connect", style = MekaType.itemTitle,
                color = if (ready) Meka.colors.accent else Meka.colors.textTertiary,
                modifier = Modifier.clickable(enabled = !busy && url.isNotBlank() && ready) {
                    busy = true; error = null
                    scope.launch {
                        error = if (recovering) recover?.invoke(url, words) else connect(url, code)
                        busy = false
                        if (error == null) { code = ""; words = ""; recovering = false; open = false }
                    }
                },
            )
            Text("Not now", style = MekaType.itemTitle, color = Meka.colors.textSecondary, modifier = Modifier.clickable { words = ""; open = false })
        }
        if (recover != null) {
            Text(
                if (recovering) "Use an enrolment code instead" else RecoveryKeyRules.RECOVER,
                style = MekaType.caption, color = Meka.colors.accent,
                modifier = Modifier.clickable(enabled = !busy) { recovering = !recovering; error = null; words = "" }.padding(vertical = MekaSpace.xs),
            )
        }
    }
}

/** The 24 words, typed or pasted (any case, numbers and new lines fine); no suggestions or autocorrect. */
@Composable
private fun WordsField(value: String, onChange: (String) -> Unit) {
    Column {
        Text("Recovery key", style = MekaType.caption, color = Meka.colors.textTertiary)
        BasicTextField(
            value = value, onValueChange = { onChange(it.take(400)) }, minLines = 3,
            textStyle = MekaType.body.copy(color = Meka.colors.textPrimary),
            cursorBrush = SolidColor(Meka.colors.accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.s)).background(Meka.colors.surface).padding(MekaSpace.m),
        )
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, type: KeyboardType, secret: Boolean) {
    Column {
        Text(label, style = MekaType.caption, color = Meka.colors.textTertiary)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = MekaType.body.copy(color = Meka.colors.textPrimary),
            cursorBrush = SolidColor(Meka.colors.accent),
            keyboardOptions = KeyboardOptions(keyboardType = type),
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.s)).background(Meka.colors.surface).padding(MekaSpace.m),
        )
    }
}
