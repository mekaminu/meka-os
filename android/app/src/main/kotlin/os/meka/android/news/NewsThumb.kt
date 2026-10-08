package os.meka.android.news

import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaType
import os.meka.core.domain.NewsItem
import os.meka.core.facade.MekaCore

/**
 * A story's picture (news, images slice): the small JPEG the household's server made from the feed's picture, fetched
 * from the server by key (never from the publisher). Until it arrives, or when there is none, an accent-tinted tile
 * with the source's short name ("MD", "BBC", "TC"; [NewsItem.tileMark]), tinted in Barça's colour on Barça stories. The picture cross-fades in over the tile. Decorative: the row
 * already says the title and source, so screen readers skip it.
 */
/** How strongly the picture-less tile is tinted with the accent (or Barça's colour). */
private const val TILE_TINT = 0.16f

@Composable
fun NewsThumb(core: MekaCore, item: NewsItem, modifier: Modifier, corner: Dp = MekaRadius.s, initialStyle: TextStyle = MekaType.itemTitle) {
    val key = item.imageKey
    val picture by produceState<ImageBitmap?>(initialValue = null, key) {
        value = key?.let { k ->
            withContext(Dispatchers.IO) {
                runCatching { core.newsImage(k)?.let { b -> BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() } }.getOrNull()
            }
        }
    }
    val barca = item.topic == "barca"
    Box(
        modifier.clip(RoundedCornerShape(corner)).background(Meka.colors.surfaceRaised).clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(picture, animationSpec = MekaMotion.appear(Meka.reducedMotion), label = "news-picture") { p ->
            if (p != null) {
                Image(p, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                val tint = if (barca) Meka.colors.barca else Meka.colors.accent
                Box(Modifier.fillMaxSize().background(tint.copy(alpha = TILE_TINT)), contentAlignment = Alignment.Center) {
                    Text(item.tileMark, style = initialStyle, color = tint, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}
