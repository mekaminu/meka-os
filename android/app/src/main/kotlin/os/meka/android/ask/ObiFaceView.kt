package os.meka.android.ask

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import os.meka.android.designsystem.Meka
import os.meka.core.domain.FaceGeometry
import os.meka.core.domain.FaceGold
import os.meka.core.domain.FaceLook
import os.meka.core.domain.LipSync
import os.meka.core.domain.MouthFollower
import os.meka.core.domain.MouthShape
import os.meka.core.domain.OrbState
import os.meka.core.wire.FaceCodec
import java.io.File
import kotlin.math.roundToInt

/**
 * Obi's face, slice 3 (the approved mockup): Meka's face film, cropped to hide the vendor's HUD
 * ([FaceGeometry.CROP]), turned gold ([FaceGold]), its edge faded to black by the ellipse, and one of the six mouth
 * patches cross-fading over the film's mouth as Obi speaks. This slice drives the mouth from the voice's loudness
 * ([FaceLook.mouthFromLevel], held and cross-faded by [MouthFollower]); idle, no patch: the film's own blinking face.
 * Listening it is a touch brighter; thinking, a slow gold glint passes across. Reduced motion ([still] too): the film
 * held on its first frame, the mouth shapes only. Drawn only when [ObiFaceStore] holds all seven files; drawing only,
 * the caller places it and gives it its label.
 */
@Composable
fun ObiFaceView(state: OrbState, level: Float, height: Dp, modifier: Modifier = Modifier, still: Boolean = false) {
    val context = LocalContext.current
    val reduced = Meka.reducedMotion || still
    val loop = remember { ObiFaceStore.file(context, FaceCodec.LOOP) }
    var mouths by remember { mutableStateOf<Map<MouthShape, ImageBitmap>>(emptyMap()) }
    LaunchedEffect(Unit) {
        mouths = withContext(Dispatchers.IO) {
            MouthShape.entries.zip(FaceCodec.MOUTHS).mapNotNull { (shape, name) ->
                val f = ObiFaceStore.file(context, name) ?: return@mapNotNull null
                runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull()?.let { shape to it }
            }.toMap()
        }
    }
    val brightness = FaceLook.brightness(state)
    val gold = remember(brightness) { goldMatrix(brightness) }
    val filmPaint = remember(gold) { Paint().apply { colorFilter = ColorMatrixColorFilter(gold) } }
    val patchFilter = remember(gold) { ColorFilter.colorMatrix(ColorMatrix(gold)) }

    val currentLevel by rememberUpdatedState(level)
    val currentState by rememberUpdatedState(state)
    val follower = remember { MouthFollower() }
    var frame by remember { mutableStateOf(LipSync.Frame(null, null, 1.0)) }
    var glintAt by remember { mutableStateOf<Float?>(null) }
    val moving = state == OrbState.SPEAKING || state == OrbState.THINKING || frame.from != null || frame.to != null
    LaunchedEffect(moving, reduced) {
        if (!moving) return@LaunchedEffect
        while (true) withFrameMillis { t ->
            frame = follower.step(FaceLook.mouthFromLevel(currentState, currentLevel, t), t)
            glintAt = FaceLook.glint(currentState, t, reduced)
        }
    }
    val glint = if (state == OrbState.THINKING) glintAt else null

    val width = height * (FaceGeometry.CROP.w / FaceGeometry.CROP.h).toFloat()
    Box(
        modifier.size(width, height).clearAndSetSemantics { }
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val w = size.width
                val h = size.height
                glint?.let { g ->
                    // A soft gold band sweeping left to right.
                    val x = -0.3f * w + g * 1.6f * w
                    drawRect(
                        Brush.horizontalGradient(
                            0f to Color.Transparent, 0.5f to Color(0xFFECD79E).copy(alpha = 0.22f), 1f to Color.Transparent,
                            startX = x - 0.15f * w, endX = x + 0.15f * w,
                        ),
                        blendMode = BlendMode.Plus,
                    )
                }
                // The ellipse: full face inside FADE_INNER of its radius, fading to nothing at its edge.
                val c = Offset(w * FaceGeometry.FADE_CX.toFloat(), h * FaceGeometry.FADE_CY.toFloat())
                val r = w * 0.5f
                scale(1f, FaceGeometry.FADE_TALL.toFloat(), pivot = c) {
                    drawRect(
                        Brush.radialGradient(
                            0f to Color.Black, FaceGeometry.FADE_INNER.toFloat() to Color.Black, FaceGeometry.FADE_OUTER.toFloat() to Color.Transparent,
                            center = c, radius = r,
                        ),
                        topLeft = Offset(-w, -h), size = Size(3 * w, 3 * h), blendMode = BlendMode.DstIn,
                    )
                }
            },
    ) {
        if (loop != null) AndroidView(
            factory = { ctx -> FaceFilmView(ctx, loop) },
            update = { v -> v.setLayerPaint(filmPaint); v.playing = !reduced },
            onRelease = { it.release() },
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(Modifier.fillMaxSize()) {
            val box = FaceGeometry.mouthIn(size.width.toDouble(), size.height.toDouble())
            fun patch(shape: MouthShape?, alpha: Float) {
                val img = shape?.let { mouths[it] } ?: return
                if (alpha <= 0f) return
                drawImage(
                    img, IntOffset.Zero, IntSize(img.width, img.height),
                    IntOffset(box.x.roundToInt(), box.y.roundToInt()), IntSize(box.w.roundToInt(), box.h.roundToInt()),
                    alpha = alpha.coerceAtMost(1f), colorFilter = patchFilter,
                )
            }
            val mix = frame.mix.toFloat()
            if (frame.to == null) patch(frame.from, 1f - mix)
            else {
                if (frame.from != frame.to) patch(frame.from, 1f)
                patch(frame.to, mix)
            }
        }
    }
}

/** [FaceGold.matrix] with its colour rows scaled by [brightness] (listening is a touch brighter). */
private fun goldMatrix(brightness: Float): FloatArray = FaceGold.matrix.copyOf().also { m ->
    if (brightness != 1f) for (r in 0..2) for (c in 0..4) m[r * 5 + c] *= brightness
}

/** The face film: a silent looping video on a [TextureView] (so the gold colour filter and the fade apply to it). */
private class FaceFilmView(context: Context, private val file: File) : TextureView(context), TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var prepared = false

    var playing: Boolean = true
        set(v) {
            field = v
            val p = player ?: return
            if (!prepared) return
            runCatching { if (v && !p.isPlaying) p.start() else if (!v && p.isPlaying) p.pause() }
        }

    init {
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        crop(w, h)
        val s = Surface(st)
        surface = s
        player = runCatching {
            MediaPlayer().apply {
                setDataSource(file.path)
                setSurface(s)
                isLooping = true
                setVolume(0f, 0f)
                setOnPreparedListener { mp ->
                    prepared = true
                    if (playing) mp.start() else mp.seekTo(0)
                }
                prepareAsync()
            }
        }.getOrNull()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = crop(w, h)

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        release()
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    /** Stretches the film so only its crop fills the view (the view has the crop's shape). */
    private fun crop(w: Int, h: Int) {
        val c = FaceGeometry.CROP
        val m = Matrix()
        m.setScale((FaceGeometry.FILM_W / c.w).toFloat(), (FaceGeometry.FILM_H / c.h).toFloat())
        m.postTranslate((-c.x / c.w * w).toFloat(), (-c.y / c.h * h).toFloat())
        setTransform(m)
    }

    fun release() {
        player?.let { runCatching { it.release() } }
        player = null
        prepared = false
        surface?.release()
        surface = null
    }
}
