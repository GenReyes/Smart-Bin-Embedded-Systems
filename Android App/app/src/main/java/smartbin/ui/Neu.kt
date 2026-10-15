package smartbin.ui

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The mockup's two shadow systems. The soft pair (light above left, dark
 * below right) is the Neumorphism depth: --o1 and --o2 raise a surface,
 * --i1 and --i2 sink it. The hard offset block with a 2 dp ink edge is the
 * Neo-Brutalism part, used for banners, alarms and danger keys.
 */

/** --o1 and --o2: offset and blur in px. */
enum class Out(val off: Dp, val blur: Dp) { O1(4.dp, 9.dp), O2(7.dp, 15.dp), POP(3.dp, 8.dp) }

/** --i1 and --i2 */
enum class In(val off: Dp, val blur: Dp) { I1(4.dp, 8.dp), I2(6.dp, 12.dp) }

// A CSS blur of b px is a Gaussian with sigma b / 2. BlurMaskFilter takes a
// radius where sigma is about 0.577 * radius, so the factor is close to 0.85.
private const val BLUR_K = 0.85f

private fun blurPaint(color: Color, blurPx: Float, stroke: Float = 0f) =
    Paint().asFrameworkPaint().apply {
        isAntiAlias = true
        this.color = color.toArgb()
        if (stroke > 0f) {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = stroke
        }
        if (blurPx > 0f) maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
    }

/**
 * Raised: the soft pair, plus an optional ring (box-shadow 0 0 0 Npx) drawn
 * outside the edge. Put it before the clip and the background.
 */
fun Modifier.raised(c: SbColors, radius: Dp, out: Out = Out.O1, ring: Color? = null, ringWidth: Dp = 2.dp): Modifier = drawWithCache {
    val r = radius.toPx().coerceAtMost(size.minDimension / 2f)
    val off = out.off.toPx()
    val light = blurPaint(c.shL, out.blur.toPx() * BLUR_K)
    val dark = blurPaint(c.shD, out.blur.toPx() * BLUR_K)
    val rw = ringWidth.toPx()
    onDrawBehind {
        drawIntoCanvas { canvas ->
            val n = canvas.nativeCanvas
            n.drawRoundRect(-off, -off, size.width - off, size.height - off, r, r, light)
            n.drawRoundRect(off, off, size.width + off, size.height + off, r, r, dark)
        }
        if (ring != null && ring.alpha > 0f) {
            drawRoundRect(
                ring, topLeft = Offset(-rw / 2, -rw / 2), size = Size(size.width + rw, size.height + rw),
                cornerRadius = CornerRadius(r + rw / 2, r + rw / 2), style = Stroke(rw),
            )
        }
    }
}

/** Sunk: the inner soft pair. Put it after the background. */
fun Modifier.sunk(c: SbColors, radius: Dp, inset: In = In.I1): Modifier = drawWithCache {
    val r = radius.toPx().coerceAtMost(size.minDimension / 2f)
    val off = inset.off.toPx()
    val clip = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r, r))) }
    val dark = blurPaint(c.shD, inset.blur.toPx() * BLUR_K, stroke = off * 2)
    val light = blurPaint(c.shL, inset.blur.toPx() * BLUR_K, stroke = off * 2)
    onDrawBehind {
        clipPath(clip) {
            drawIntoCanvas { canvas ->
                val n = canvas.nativeCanvas
                n.drawRoundRect(off, off, size.width + off, size.height + off, r, r, dark)
                n.drawRoundRect(-off, -off, size.width - off, size.height - off, r, r, light)
            }
        }
    }
}

/** A ring outside the edge with nothing else, for a surface that is already sunk. */
fun Modifier.ring(color: Color, radius: Dp, width: Dp = 2.dp): Modifier = drawBehind {
    val rw = width.toPx()
    val r = radius.toPx().coerceAtMost(size.minDimension / 2f)
    drawRoundRect(
        color, topLeft = Offset(-rw / 2, -rw / 2), size = Size(size.width + rw, size.height + rw),
        cornerRadius = CornerRadius(r + rw / 2, r + rw / 2), style = Stroke(rw),
    )
}

/** The brutal block: a hard offset shadow and a 2 dp ink edge. */
fun Modifier.brutal(c: SbColors, radius: Dp, fill: Color, off: Dp = 4.dp, edge: Color = c.ink): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .drawBehind {
            val r = radius.toPx()
            drawRoundRect(edge, topLeft = Offset(off.toPx(), off.toPx()), size = size, cornerRadius = CornerRadius(r, r))
        }
        .clip(shape)
        .background(fill)
        .border(2.dp, edge, shape)
}

/** .nm: a raised card, a shade lighter toward the light. */
fun Modifier.nm(c: SbColors, radius: Dp = 18.dp, out: Out = Out.O2, ring: Color? = null, ringWidth: Dp = 2.dp): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .raised(c, radius, out, ring, ringWidth)
        .clip(shape)
        .background(c.base)
        .background(
            Brush.linearGradient(
                0f to c.shL.copy(alpha = 0.42f), 0.58f to Color.Transparent,
                start = Offset(0f, 0f), end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
            )
        )
}

/** A flat raised surface: --o1 on the page colour. */
fun Modifier.bump(c: SbColors, radius: Dp, out: Out = Out.O1, ring: Color? = null, fill: Color = c.base): Modifier =
    this.raised(c, radius, out, ring).clip(RoundedCornerShape(radius)).background(fill)

/** A well: the page sunk in, for inputs, steppers and readouts. */
fun Modifier.well(c: SbColors, radius: Dp, inset: In = In.I1, fill: Color = c.well): Modifier =
    this.clip(RoundedCornerShape(radius)).background(fill).sunk(c, radius, inset)

/** The page: two soft blooms of the accent over the mode tint. */
fun Modifier.page(c: SbColors): Modifier = drawBehind {
    drawRect(c.tint)
    drawRect(
        Brush.radialGradient(
            0f to c.accent.copy(alpha = 0.09f), 0.7f to Color.Transparent,
            center = Offset(size.width * 0.10f, -size.height * 0.06f), radius = size.width * 1.3f,
        )
    )
    drawRect(
        Brush.radialGradient(
            0f to c.accent.copy(alpha = 0.06f), 0.72f to Color.Transparent,
            center = Offset(size.width * 1.08f, size.height * 0.06f), radius = size.width * 1.1f,
        )
    )
}
