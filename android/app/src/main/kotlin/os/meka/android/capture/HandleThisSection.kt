package os.meka.android.capture

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.SkeletonRows
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.today.RequestCardView
import os.meka.android.today.RequestChoice
import os.meka.core.domain.FormAnswer
import os.meka.core.domain.FormAnswersView
import os.meka.core.domain.FormFillRules
import os.meka.core.domain.HandleThisRules
import os.meka.core.domain.RequestCard

/**
 * "Handle this" in the capture sheet (build plan V1, slice 1): while MEKA reads what was shared, a thinking shimmer;
 * then the line ("2 things to add", "Nothing in that for you to do · …") and up to three cards rising one after another
 * (the stagger), each with Add and Not needed (no Change here: the sheet has no detail to open; Change is in Needs
 * you). An answered card folds away on the expand spring. Reduced motion: cross-fades.
 *
 * What MEKA knows, slice 4: when the share is a form, "FROM WHAT MEKA KNOWS" lists each field beside the fact that
 * answers it ([FormFillRules], worked out on the phone; nothing sent), each with Copy (light haptic) and Copy all; the
 * line under them cross-fades to what was copied. The rows rise with the cards' stagger.
 */
@Composable
internal fun HandleThisSection(
    reading: Boolean,
    line: String?,
    cards: List<RequestCard>,
    answered: Set<String>,
    onChoice: (RequestCard, RequestChoice) -> Unit,
    answers: FormAnswersView = FormFillRules.EMPTY,
    copiedLine: String? = null,
    onCopy: (FormAnswer) -> Unit = {},
    onCopyAll: () -> Unit = {},
) {
    val reduced = Meka.reducedMotion
    Column(Modifier.fillMaxWidth().testTag("handle-this"), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Text("HANDLE THIS", style = MekaType.sectionLabel, color = Meka.colors.textTertiary)
        Crossfade(targetState = reading, animationSpec = MekaMotion.appear(reduced), label = "handle-reading") { isReading ->
            if (isReading) {
                Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    Text(HandleThisRules.READING_LINE, style = MekaType.body, color = Meka.colors.textSecondary)
                    SkeletonRows(count = 2, rowHeight = 18.dp)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    line?.let {
                        Text(it, style = MekaType.body, color = Meka.colors.textSecondary,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    Column(
                        Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
                    ) {
                        cards.forEachIndexed { i, card ->
                            key(card.id) {
                                val a = rememberAppearance(i)
                                AnimatedVisibility(
                                    visible = card.id !in answered,
                                    exit = fadeOut(MekaMotion.appear(reduced)) + (if (reduced) fadeOut() else shrinkVertically(MekaMotion.expand(false))),
                                ) {
                                    RequestCardView(card.copy(changeLabel = null), onChoice = { onChoice(card, it) }, modifier = Modifier.appear(a))
                                }
                            }
                        }
                    }
                    if (cards.isNotEmpty()) {
                        Text(HandleThisRules.LEFT_LINE, style = MekaType.caption, color = Meka.colors.textTertiary)
                    }
                    if (answers.answers.isNotEmpty()) {
                        FormAnswersBlock(answers, copiedLine, firstStep = cards.size, onCopy = onCopy, onCopyAll = onCopyAll)
                    }
                }
            }
        }
    }
}

/** The form's fields with the facts that answer them, each with Copy; Copy all when there are two or more. */
@Composable
private fun FormAnswersBlock(
    view: FormAnswersView,
    copiedLine: String?,
    firstStep: Int,
    onCopy: (FormAnswer) -> Unit,
    onCopyAll: () -> Unit,
) {
    val reduced = Meka.reducedMotion
    Column(Modifier.fillMaxWidth().testTag("form-answers"), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(FormFillRules.TITLE.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary,
                modifier = Modifier.weight(1f))
            if (view.answers.size > 1) {
                Text(
                    FormFillRules.COPY_ALL, style = MekaType.caption, color = Meka.colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                        .clickable(role = Role.Button, onClick = onCopyAll)
                        .semantics { contentDescription = "Copy all ${view.answers.size} answers" }
                        .padding(MekaSpace.xs),
                )
            }
        }
        view.answers.forEachIndexed { i, a ->
            key(a.field) {
                val appearance = rememberAppearance(firstStep + i)
                Row(
                    Modifier.fillMaxWidth().appear(appearance).clip(RoundedCornerShape(MekaRadius.m))
                        .background(Meka.colors.surfaceRaised).minTouch()
                        .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
                ) {
                    Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = a.spoken },
                        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                        Text(a.field, style = MekaType.caption, color = Meka.colors.textSecondary)
                        Text(a.copyText, style = MekaType.body, color = Meka.colors.textPrimary)
                    }
                    Text(
                        FormFillRules.COPY, style = MekaType.caption, color = Meka.colors.accent,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                            .clickable(role = Role.Button) { onCopy(a) }
                            .semantics { contentDescription = a.copySpoken }
                            .padding(MekaSpace.xs),
                    )
                }
            }
        }
        Crossfade(copiedLine ?: view.line, animationSpec = MekaMotion.appear(reduced), label = "form-answers-line") { l ->
            Text(l, style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}
