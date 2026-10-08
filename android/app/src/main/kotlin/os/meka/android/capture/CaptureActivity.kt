package os.meka.android.capture

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import os.meka.android.designsystem.mekaRecomposer
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import os.meka.android.MekaApplication
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.QuickCapture

/**
 * The capture sheet over whatever Meka was doing (build plan M1, "Capture from anywhere"). Opened by the share
 * sheet, "Capture in MEKA" on selected text, the home-screen widget and the quick-settings tile. Springs up from the
 * bottom with the field ready; Add saves one task (title + notes, [QuickCapture]) and the sheet drops away.
 * Nothing is saved unless Meka taps Add. Voice uses the phone's speech recogniser, asked to stay on the device.
 */
class CaptureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = CaptureRequests.from(
            intent?.action, intent?.type,
            intent?.getCharSequenceExtra(CaptureRequests.EXTRA_TEXT) ?: intent?.getCharSequenceExtra(CaptureRequests.EXTRA_PROCESS_TEXT),
            intent?.getCharSequenceExtra(CaptureRequests.EXTRA_SUBJECT),
            intent?.getBooleanExtra(CaptureRequests.EXTRA_VOICE, false) == true,
        )
        if (request == null) { finish(); return }
        enableEdgeToEdge()
        val core = (application as MekaApplication).core
        // MEKA follows its own Motion setting, not the phone's animator scale (MotionClock.kt).
        setContent(parent = mekaRecomposer()) {
            MekaTheme {
                CaptureSheet(
                    request = request,
                    // First launch only: rotation or unfolding must not re-open the recogniser.
                    listenOnOpen = request.startVoice && savedInstanceState == null,
                    save = { text -> core.capture(text, null) != null },
                    done = { saved -> setResult(if (saved) Activity.RESULT_OK else Activity.RESULT_CANCELED); finish() },
                )
            }
        }
    }

    override fun finish() {
        super.finish()
        // The sheet already animated itself away.
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}

@Composable
private fun CaptureSheet(request: CaptureRequest, listenOnOpen: Boolean, save: suspend (String) -> Boolean, done: (Boolean) -> Unit) {
    val initial = remember(request) { QuickCapture.draft(request.text, request.subject) }
    var title by rememberSaveable { mutableStateOf(initial?.title.orEmpty()) }
    val notes = initial?.notes
    var captured by rememberSaveable { mutableStateOf(false) }
    var voiceMissing by remember { mutableStateOf(false) }
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val rise = with(density) { 160.dp.toPx() }
    // 0 = below the screen, 1 = in place. Reduced motion: fades instead of moving.
    val shown = remember { Animatable(0f) }

    suspend fun leave(saved: Boolean) {
        shown.animateTo(0f, MekaMotion.expand(reduced))
        done(saved)
    }

    val listen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val heard = CaptureRequests.spoken(r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS))
        if (heard != null) title = if (title.isBlank()) heard else "$title $heard"
        runCatching { focus.requestFocus() }
    }
    fun startListening() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true) // on-device speech; nothing leaves the phone
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say it and MEKA will keep it")
        try { listen.launch(i) } catch (_: ActivityNotFoundException) { voiceMissing = true }
    }

    fun add() {
        if (captured || title.isBlank()) return
        scope.launch {
            if (save(CaptureRequests.compose(title, notes))) {
                keyboard?.hide()
                captured = true
                haptics.light()
                delay(if (reduced) 250 else 520)
                leave(true)
            }
        }
    }

    LaunchedEffect(Unit) {
        shown.animateTo(1f, MekaMotion.expand(reduced))
        if (captured) { leave(true); return@LaunchedEffect } // rotated or unfolded mid-goodbye
        if (listenOnOpen) startListening() else { runCatching { focus.requestFocus() }; keyboard?.show() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = shown.value }
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(remember { MutableInteractionSource() }, indication = null) { scope.launch { leave(false) } },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .graphicsLayer {
                    if (!reduced) translationY = (1f - shown.value) * rise
                    alpha = shown.value
                }
                .clip(RoundedCornerShape(topStart = MekaRadius.l, topEnd = MekaRadius.l))
                .background(Meka.colors.surface)
                .clickable(remember { MutableInteractionSource() }, indication = null) {} // taps inside don't dismiss
                .navigationBarsPadding()
                .padding(MekaSpace.gutter),
            verticalArrangement = Arrangement.spacedBy(MekaSpace.s),
        ) {
            AnimatedContent(
                targetState = captured,
                transitionSpec = {
                    (fadeIn(MekaMotion.appear(reduced)) + (if (reduced) fadeIn() else scaleIn(MekaMotion.approve(false), 0.6f)))
                        .togetherWith(fadeOut(MekaMotion.appear(reduced)))
                },
                label = "captured",
            ) { isCaptured ->
                if (isCaptured) {
                    Row(Modifier.fillMaxWidth().padding(vertical = MekaSpace.m), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center) {
                        Box(Modifier.size(28.dp).clip(CircleShape).background(Meka.colors.success), contentAlignment = Alignment.Center) {
                            Text("✓", color = Meka.colors.background, style = MekaType.itemMeta)
                        }
                        Spacer(Modifier.width(MekaSpace.s))
                        Text("Captured", style = MekaType.itemTitle, color = Meka.colors.textPrimary)
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.s)) {
                        Text("CAPTURE", style = MekaType.sectionLabel, color = Meka.colors.textTertiary)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(MekaRadius.pill))
                                    .background(Meka.colors.surfaceRaised)
                                    .padding(horizontal = MekaSpace.l, vertical = MekaSpace.m),
                            ) {
                                if (title.isEmpty()) Text("Capture anything…", style = MekaType.body, color = Meka.colors.textTertiary)
                                BasicTextField(
                                    value = title,
                                    onValueChange = { title = it.replace('\n', ' ') },
                                    singleLine = true,
                                    textStyle = MekaType.body.copy(color = Meka.colors.textPrimary),
                                    cursorBrush = SolidColor(Meka.colors.accent),
                                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { add() }),
                                    modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Capture" },
                                )
                            }
                            Spacer(Modifier.width(MekaSpace.xs))
                            RoundButton("🎙", "Speak", Meka.colors.surfaceRaised, Meka.colors.textPrimary) { startListening() }
                            Spacer(Modifier.width(MekaSpace.xs))
                            RoundButton("↑", "Add", if (title.isBlank()) Meka.colors.hairline else Meka.colors.accent, Meka.colors.onAccent) { add() }
                        }
                        if (notes != null) {
                            Text("Kept in notes: $notes", style = MekaType.caption, color = Meka.colors.textSecondary,
                                maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        if (voiceMissing) {
                            Text("Voice isn't available on this phone. Type it instead.", style = MekaType.caption, color = Meka.colors.textSecondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundButton(glyph: String, label: String, fill: Color, ink: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(fill).clickable(onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Text(glyph, style = MekaType.itemTitle, color = ink) }
}
