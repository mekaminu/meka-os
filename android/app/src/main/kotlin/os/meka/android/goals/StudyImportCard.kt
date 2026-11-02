package os.meka.android.goals

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import os.meka.android.ask.TripPill
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.core.domain.StudyImportOffer
import os.meka.core.domain.StudyImportRules

/**
 * One-tap course import: the card at the top of Goals after a course link was opened or a course shared to Navilife.
 * "ADD A COURSE", the question ("Add Data analytics · 10 modules · 38 lessons?") and Add · Cancel; a link that can't be
 * read shows its plain line and Close. Nothing is written before Add. Motion: it unfolds and folds on the expand spring
 * (the caller's AnimatedVisibility, cross-fade under reduced motion); a light haptic on Add, a tick on Cancel.
 */
@Composable
internal fun StudyImportCard(offer: StudyImportOffer, modifier: Modifier, onAdd: () -> Unit, onCancel: () -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .padding(MekaSpace.m).testTag(TAG),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Text(StudyImportRules.LABEL, style = MekaType.cardLabel, color = Meka.colors.textSecondary)
        Text(
            offer.line, style = MekaType.body, color = if (offer.ok) Meka.colors.textPrimary else Meka.colors.accent,
            modifier = Modifier.semantics { contentDescription = offer.spoken; liveRegion = LiveRegionMode.Polite }.testTag("$TAG-line"),
        )
        if (offer.ok) Text(StudyImportRules.NOTE, style = MekaType.caption, color = Meka.colors.textTertiary)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            if (offer.ok) TripPill(StudyImportRules.ADD, on = true, tag = "$TAG-add", description = "Add ${offer.title}") { onAdd() }
            TripPill(if (offer.ok) StudyImportRules.CANCEL else StudyImportRules.CLOSE, tag = "$TAG-cancel") { onCancel() }
            Spacer(Modifier.weight(1f))
        }
    }
}

internal const val STUDY_IMPORT_TAG = "study-import"
private const val TAG = STUDY_IMPORT_TAG
