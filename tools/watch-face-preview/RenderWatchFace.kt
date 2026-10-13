package os.meka.tools

import os.meka.core.domain.DayArcKind
import os.meka.core.domain.WatchFaceLook
import os.meka.core.domain.WatchFaceRules
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.awt.image.ConvolveOp
import java.awt.image.Kernel
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The watch face golden preview (watch face item, Meka 2026-10-13 12:13): draws the face from [WatchFaceLook] and
 * [WatchFaceRules] the way the Fold's WatchFaceDial does — the arcs' track, hairline, bezel and its glow, markers, a
 * work band and an event arc (the one on now glowing), the hour and minute hands, the second hand with its blurred glow
 * and the cap — at the Fold cover's density, Dark, at 10:08:37, into PNGs a person can look at. Not a pixel-exact copy
 * of Compose or SwiftUI, but every size, alpha and blur comes from the core, so a face whose numbers bottom out shows it.
 *
 * Usage: RenderWatchFaceKt <out dir> [sizeDp …]
 */
private const val DENSITY = 2.625f // the Fold's cover screen (420 dpi)
private val BACKGROUND = Color(0, 0, 0) // the Dark theme's background
private val ACCENT = Color(0xC9, 0xB3, 0x7E)

fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "build/watch-face" }).apply { mkdirs() }
    val sizes = args.drop(1).map { it.toInt() }.ifEmpty { listOf(WatchFaceRules.COVER_DP, 150) }
    sizes.forEach { size ->
        val img = render(size)
        val file = File(out, "watch-face-${size}dp.png")
        ImageIO.write(img, "png", file)
        // A 3× enlargement as well, so the 78 dp face can be judged by eye on a laptop.
        val big = BufferedImage(img.width * 3, img.height * 3, BufferedImage.TYPE_INT_RGB)
        big.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            drawImage(img, 0, 0, big.width, big.height, null); dispose()
        }
        ImageIO.write(big, "png", File(out, "watch-face-${size}dp-x3.png"))
        println("wrote ${file.path} (${img.width}×${img.height} px)")
    }
}

private fun Color.a(alpha: Float) = Color(red, green, blue, (alpha.coerceIn(0f, 1f) * 255).roundToInt())

fun render(sizeDp: Int): BufferedImage {
    val pad = WatchFaceLook.bleedDp(sizeDp) + 4f
    val px = ((sizeDp + pad * 2) * DENSITY).roundToInt()
    val img = BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    g.color = BACKGROUND
    g.fillRect(0, 0, px, px)
    val c = px / 2f
    fun d(dp: Float) = dp * DENSITY
    fun circle(g: Graphics2D, rDp: Float, color: Color, widthDp: Float, from: Float = 0f, sweep: Float = 360f, cap: Int = BasicStroke.CAP_BUTT) {
        val r = d(rDp)
        g.color = color
        g.stroke = BasicStroke(d(widthDp), cap, BasicStroke.JOIN_ROUND)
        g.draw(Arc2D.Float(c - r, c - r, r * 2, r * 2, 90f - from, -sweep, Arc2D.OPEN))
    }
    fun at(deg: Float, rDp: Float): Pair<Float, Float> {
        val a = Math.toRadians(deg - 90.0)
        return Pair(c + (cos(a) * d(rDp)).toFloat(), c + (sin(a) * d(rDp)).toFloat())
    }
    val glow = 0.8f
    val radius = WatchFaceRules.rimRadiusDp(sizeDp)
    val rim = WatchFaceRules.rimStrokeDp(sizeDp)
    // The arcs' faint track and the hairline.
    circle(g, radius, ACCENT.a(WatchFaceLook.ARC_TRACK_ALPHA), WatchFaceLook.ARC_TRACK_DP)
    circle(g, WatchFaceLook.hairlineRadiusDp(sizeDp), ACCENT.a(WatchFaceLook.HAIRLINE_ALPHA), WatchFaceLook.hairlineDp(sizeDp))
    // The bezel's glow and the bezel.
    val edge = WatchFaceLook.bezelRadiusDp(sizeDp)
    for (i in 0 until WatchFaceLook.BLUR_LAYERS) {
        circle(g, edge + WatchFaceLook.blurOffsetDp(i, sizeDp), ACCENT.a(WatchFaceLook.blurAlpha(i, glow)), WatchFaceLook.blurStrokeDp(sizeDp))
    }
    circle(g, edge, ACCENT.a(WatchFaceLook.bezelAlpha(glow, 1f)), WatchFaceLook.bezelDp(sizeDp))
    // Markers.
    WatchFaceRules.markers().forEach { m ->
        val outer = radius - rim / 2 - 2f
        val inner = outer - radius * (if (m.major) WatchFaceRules.MAJOR_MARKER_LENGTH else WatchFaceRules.MINOR_MARKER_LENGTH)
        val (x1, y1) = at(m.degrees, inner)
        val (x2, y2) = at(m.degrees, outer)
        g.color = ACCENT.a(WatchFaceRules.MARKER_ALPHA)
        g.stroke = BasicStroke(d(if (m.major) WatchFaceRules.MAJOR_MARKER_DP else WatchFaceRules.MINOR_MARKER_DP), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(Line2D.Float(x1, y1, x2, y2))
    }
    // Work 10:00–17:30 and a meeting on now (10:00–11:00) and a planned task at 13:00.
    val arc = WatchFaceLook.arcStrokeDp(sizeDp)
    circle(g, radius, ACCENT.a(WatchFaceLook.WORK_ALPHA), arc, WatchFaceRules.degrees(10 * 60 + 8), WatchFaceRules.degrees(17 * 60 + 30) - WatchFaceRules.degrees(10 * 60 + 8))
    circle(g, radius, ACCENT.a(WatchFaceLook.NOW_GLOW_ALPHA * glow), arc + WatchFaceLook.NOW_GLOW_EXTRA_DP, WatchFaceRules.degrees(10 * 60 + 8), 26f, BasicStroke.CAP_ROUND)
    circle(g, radius, ACCENT.a(WatchFaceRules.arcAlpha(DayArcKind.EVENT)), arc, WatchFaceRules.degrees(10 * 60 + 8), 26f)
    circle(g, radius, ACCENT.a(WatchFaceRules.arcAlpha(DayArcKind.TASK)), arc, WatchFaceRules.degrees(13 * 60), 15f)
    // Hour and minute hands at 10:08:37.
    val hands = WatchFaceRules.hands(10 * 60 + 8, 37_000, 37_000)
    val scale = (sizeDp / 150f).coerceIn(0.7f, 2f)
    fun hand(deg: Float, length: Float, base: Float, tip: Float) {
        val a = Math.toRadians(deg - 90.0)
        val dx = cos(a).toFloat(); val dy = sin(a).toFloat(); val nx = -dy; val ny = dx
        val back = d(radius * WatchFaceRules.TAIL_LENGTH)
        val L = d(length); val B = d(base); val T = d(tip)
        val p = Path2D.Float().apply {
            moveTo(c - dx * back + nx * B / 2.6f, c - dy * back + ny * B / 2.6f)
            lineTo(c + nx * B / 2, c + ny * B / 2)
            lineTo(c + dx * L + nx * T / 2, c + dy * L + ny * T / 2)
            lineTo(c + dx * (L + T / 2), c + dy * (L + T / 2))
            lineTo(c + dx * L - nx * T / 2, c + dy * L - ny * T / 2)
            lineTo(c - nx * B / 2, c - ny * B / 2)
            lineTo(c - dx * back - nx * B / 2.6f, c - dy * back - ny * B / 2.6f)
            closePath()
        }
        g.color = ACCENT
        g.fill(p)
    }
    hand(hands.minuteDegrees, radius * WatchFaceRules.MINUTE_HAND_LENGTH, WatchFaceRules.MINUTE_HAND_BASE_DP * scale, WatchFaceRules.MINUTE_HAND_TIP_DP * scale)
    hand(hands.hourDegrees, radius * WatchFaceRules.HOUR_HAND_LENGTH, WatchFaceRules.HOUR_HAND_BASE_DP * scale, WatchFaceRules.HOUR_HAND_TIP_DP * scale)
    // The second hand: blurred glow layers, then the bright brass needle.
    val sec = hands.secondDegrees
    val (fx, fy) = at(sec, -WatchFaceLook.needleTailDp(sizeDp))
    val (tx, ty) = at(sec, WatchFaceLook.needleReachDp(sizeDp))
    val needle = Color(WatchFaceLook.NEEDLE_DARK_ARGB.toInt(), true)
    WatchFaceLook.halo(sizeDp).forEach { layer ->
        val glowImg = BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB)
        glowImg.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            color = needle.a(layer.alpha)
            stroke = BasicStroke(d(layer.widthDp), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            draw(Line2D.Float(fx, fy, tx, ty)); dispose()
        }
        g.drawImage(blur(glowImg, d(layer.blurDp)), 0, 0, null)
    }
    g.color = needle
    g.stroke = BasicStroke(d(WatchFaceLook.needleDp(sizeDp)), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
    g.draw(Line2D.Float(fx, fy, tx, ty))
    // The cap.
    val cap = d(WatchFaceLook.capDp(sizeDp))
    g.color = ACCENT
    g.fill(Ellipse2D.Float(c - cap, c - cap, cap * 2, cap * 2))
    g.color = BACKGROUND
    g.fill(Ellipse2D.Float(c - cap * 0.4f, c - cap * 0.4f, cap * 0.8f, cap * 0.8f))
    g.dispose()
    return img
}

/** A Gaussian blur of [radius] px (sigma = radius / 2, like BlurMaskFilter), horizontal then vertical. */
private fun blur(src: BufferedImage, radius: Float): BufferedImage {
    if (radius < 0.5f) return src
    val sigma = radius / 2f
    val r = (radius * 1.5f).roundToInt().coerceAtLeast(1)
    val w = FloatArray(r * 2 + 1) { i -> exp(-((i - r) * (i - r)) / (2 * sigma * sigma)) }
    val sum = w.sum()
    for (i in w.indices) w[i] /= sum
    val h = ConvolveOp(Kernel(w.size, 1, w), ConvolveOp.EDGE_NO_OP, null).filter(src, null)
    return ConvolveOp(Kernel(1, w.size, w), ConvolveOp.EDGE_NO_OP, null).filter(h, null)
}
