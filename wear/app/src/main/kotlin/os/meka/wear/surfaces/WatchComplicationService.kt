package os.meka.wear.surfaces

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import os.meka.core.domain.WatchComplicationView
import os.meka.core.domain.WatchTileRules
import os.meka.wear.WatchActivity
import os.meka.wear.WatchApplication

/**
 * MEKA's complication (Galaxy Watch, slice 3): MEKA's ring on any watch face. A running fast fills it towards its
 * goal with the fast's clock beside it; otherwise it shows how much of today's list is done ("2/5"). A tap opens MEKA.
 * Read from the watch's own replica; Wear OS asks again every five minutes and after every sync.
 */
class WatchComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val core = (application as WatchApplication).core.value
        if (core != null) runCatching { core.tick() }
        val v = core?.watchComplication() ?: WatchTileRules.complication(null, 0, 0)
        return data(request.complicationType, v)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        data(type, WatchComplicationView("2/5", "Done", 0.4f, false, "2 of 5 done today"))

    private fun data(type: ComplicationType, v: WatchComplicationView): ComplicationData? {
        val spoken = PlainComplicationText.Builder(v.spoken).build()
        val text = PlainComplicationText.Builder(v.text).build()
        val title = PlainComplicationText.Builder(v.title).build()
        return when (type) {
            ComplicationType.RANGED_VALUE ->
                RangedValueComplicationData.Builder(v.value, 0f, 1f, spoken)
                    .setText(text)
                    .setTitle(title)
                    .setTapAction(open())
                    .build()
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(text, spoken)
                    .setTitle(title)
                    .setTapAction(open())
                    .build()
            else -> null
        }
    }

    private fun open(): PendingIntent =
        PendingIntent.getActivity(
            this, 0,
            Intent(this, WatchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
