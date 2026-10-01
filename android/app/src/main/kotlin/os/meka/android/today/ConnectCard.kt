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

/**
 * Shown only while this device isn't syncing (silence otherwise). One-time: server address + enrolment code.
 * Everything keeps working offline before and after; enrolling just starts pushing local work to the server.
 */
@Composable
fun ConnectCard(defaultUrl: String, connect: suspend (url: String, code: String) -> String?, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    var url by rememberSaveable { mutableStateOf(defaultUrl) }
    var code by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    if (!open) {
        Text(
            "This device isn't syncing yet · Connect",
            style = MekaType.caption, color = Meka.colors.accent,
            modifier = modifier.clickable { open = true }.padding(vertical = MekaSpace.xs),
        )
    } else Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.l)).background(Meka.colors.surfaceRaised).padding(MekaSpace.l),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.s),
    ) {
        Text("Connect this device", style = MekaType.itemTitle, color = Meka.colors.textPrimary)
        Field("Server address", url, { url = it }, KeyboardType.Uri, secret = false)
        Field("Enrolment code", code, { code = it }, KeyboardType.Password, secret = true)
        error?.let { Text(it, style = MekaType.caption, color = Meka.colors.critical) }
        Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.l)) {
            Text(
                if (busy) "Connecting…" else "Connect", style = MekaType.itemTitle, color = Meka.colors.accent,
                modifier = Modifier.clickable(enabled = !busy && url.isNotBlank() && code.isNotBlank()) {
                    busy = true; error = null
                    scope.launch {
                        error = connect(url, code)
                        busy = false
                        if (error == null) { code = ""; open = false }
                    }
                },
            )
            Text("Not now", style = MekaType.itemTitle, color = Meka.colors.textSecondary, modifier = Modifier.clickable { open = false })
        }
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
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.s)).background(Meka.colors.surface).padding(MekaSpace.s),
        )
    }
}
