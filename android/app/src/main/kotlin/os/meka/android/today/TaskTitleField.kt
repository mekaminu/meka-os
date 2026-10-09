package os.meka.android.today

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.delay
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaType
import os.meka.core.domain.TextAutosave

/**
 * A task's title in its detail. It saves itself ([TextAutosave]; Meka, 2026-10-08: a rename was lost on Close
 * because only the keyboard's Done saved it): [TextAutosave.DELAY_MS] after typing stops, when the field loses
 * focus, on Done, and when the detail closes or shows another task. A blank title is never saved (the old one stays).
 * A save landing mid-typing doesn't trim the field ("Buy " stays "Buy " so the next word doesn't run on); a change from
 * the other device shows once nothing typed here is waiting.
 */
@Composable
internal fun TaskTitleField(taskId: String, savedTitle: String, onSave: (String, String) -> Unit, modifier: Modifier = Modifier) {
    var title by remember(taskId) { mutableStateOf(savedTitle) }
    var dirty by remember(taskId) { mutableStateOf(false) }
    LaunchedEffect(taskId, savedTitle) { if (TextAutosave.adoptSaved(title, savedTitle, dirty)) title = savedTitle }
    val latestSaved by rememberUpdatedState(savedTitle)
    val save by rememberUpdatedState(onSave)
    // `title` and `dirty` are read live from their state (a save and the focus loss that follows it on Done, or on
    // close, must not both save).
    val saveTitle = {
        if (dirty) {
            dirty = false
            TextAutosave.titleToSave(title, latestSaved)?.let { save(taskId, it) }
        }
    }
    LaunchedEffect(taskId, title, dirty) {
        if (dirty) { delay(TextAutosave.DELAY_MS); saveTitle() }
    }
    DisposableEffect(taskId) { onDispose { saveTitle() } }
    val focus = LocalFocusManager.current
    BasicTextField(
        value = title,
        onValueChange = { title = it.replace('\n', ' '); dirty = true },
        singleLine = false,
        textStyle = MekaType.upNextTitle.copy(color = Meka.colors.textPrimary),
        cursorBrush = SolidColor(Meka.colors.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { saveTitle(); focus.clearFocus() }),
        modifier = modifier.semantics { contentDescription = "Title" }.onFocusChanged { if (!it.isFocused) saveTitle() },
    )
}
