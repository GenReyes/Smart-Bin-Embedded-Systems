package smartbin.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/*
 * The v2 Smart-Bin logo, the same drawing as the dashboard's: a bin with a fill
 * wave, a controller chip on its front, a status light on the lid and a Wi-Fi
 * signal above it. It plays a short build-up (the lid lifts, the bin fills, the
 * chip lights, the signal goes out) and then holds its still frame, which is
 * also the home-screen icon. Drawn on a 64 by 64 grid.
 */
private fun p(d: String) = PathParser().parsePathString(d).toPath()

private val L_BODY = p("M17.5 26h29l-2.7 27.6a3.2 3.2 0 0 1-3.2 2.9H23.4a3.2 3.2 0 0 1-3.2-2.9z")
private val L_WAVE_BACK = p("M0 37q5-3.6 10 0t10 0t10 0t10 0t10 0t10 0t10 0v30H0z")
private val L_WAVE_FRONT = p("M-5 39q5-3.6 10 0t10 0t10 0t10 0t10 0t10 0t10 0v30H-5z")
private val L_SIG = listOf(
    p("M27.8 12.6a6 6 0 0 1 8.4 0"),
    p("M24.2 8.9a11 11 0 0 1 15.6 0"),
    p("M20.6 5.2a16 16 0 0 1 22.8 0"),
)
private val L_TICKS = p("M22 30.5h3.4M22 34h2.2")
private val L_PINS = p("M32 42v-3.2M32 51v2.5M27.5 46.5h-4M36.5 46.5h4")
private val L_LID = p("M13 22.5h38")
private val L_HANDLE = p("M27 22.5v-3.4h10v3.4")
private val SPRING = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.4f)
private val SOFT = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/**
 * The logo at [size]. Each new value of [play] plays the build-up again, as a
 * tap on it does; 0 shows the still frame at once.
 */
@Composable
fun SmartBinLogo(size: Dp, play: Int = 0, modifier: Modifier = Modifier) {
    val c = Sb.c
    val ok = c.ok
    val teal = c.cal
    val ink = c.ink
    val onBrand = if (c.isDark) Color(0xFF13100D) else Color.White

    // 0 to 1 for each part of the build-up. The still frame is every part at 1.
    val lid = remember { Animatable(1f) }
    val fill = remember { Animatable(1f) }
    val chip = remember { Animatable(1f) }
    val sig = remember { Animatable(1f) }
    LaunchedEffect(play) {
        if (play == 0) return@LaunchedEffect
        lid.snapTo(0f); fill.snapTo(0f); chip.snapTo(0f); sig.snapTo(0f)
        coroutineScope {
            launch { lid.animateTo(1f, tween(1100)) }
            launch { kotlinx.coroutines.delay(250); fill.animateTo(1f, tween(1500, easing = SOFT)) }
            launch { kotlinx.coroutines.delay(1000); chip.animateTo(1f, tween(600, easing = SPRING)) }
            launch { kotlinx.coroutines.delay(1300); sig.animateTo(1f, tween(800)) }
        }
    }
    val t = rememberInfiniteTransition(label = "logo")
    val wave by t.animateFloat(0f, -10f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "wave")
    val wave2 by t.animateFloat(-10f, 0f, infiniteRepeatable(tween(4600, easing = LinearEasing)), label = "wave2")
    val ping by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3600, easing = LinearEasing)), label = "ping")
    val led by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "led")

    Canvas(modifier.size(size)) {
        scale(this.size.width / 64f, pivot = Offset.Zero) {
            drawLogo(
                ink, ok, teal, onBrand,
                lid = lid.value, fill = fill.value, chip = chip.value, sig = sig.value,
                wave = wave, wave2 = wave2, ping = ping, led = led,
            )
        }
    }
}

private fun DrawScope.drawLogo(
    ink: Color, ok: Color, teal: Color, onBrand: Color,
    lid: Float, fill: Float, chip: Float, sig: Float,
    wave: Float, wave2: Float, ping: Float, led: Float,
) {
    // The glow behind the signal grows in with it.
    if (sig > 0f) {
        drawCircle(
            Brush.radialGradient(listOf(ok.copy(alpha = 0.45f * sig), ok.copy(alpha = 0f)), center = Offset(32f, 12f), radius = 12f * (0.3f + 0.7f * sig)),
            radius = 12f, center = Offset(32f, 12f),
        )
    }
    // Three arcs go out one after another, then pulse gently.
    val base = floatArrayOf(1f, 0.7f, 0.4f)
    L_SIG.forEachIndexed { i, path ->
        val appear = ((sig * 3f) - i).coerceIn(0f, 1f)
        val phase = (ping - i * 0.07f + 1f) % 1f
        val pulse = 1f - 0.65f * kotlin.math.sin(phase * Math.PI.toFloat()).let { it * it } * 0.6f
        if (appear > 0f) {
            translate(0f, 3f * (1f - appear)) {
                drawPath(path, ok, alpha = base[i] * appear * pulse, style = Stroke(2.6f, cap = StrokeCap.Round))
            }
        }
    }
    // A soft glass tint inside the bin.
    drawPath(
        L_BODY,
        Brush.horizontalGradient(listOf(ok.copy(alpha = 0.05f), ok.copy(alpha = 0.16f), ok.copy(alpha = 0.05f)), startX = 17.5f, endX = 46.5f),
    )
    // The fill rises from the bottom, its two waves drifting the whole time.
    val gradient = Brush.verticalGradient(listOf(ok, teal), startY = 34f, endY = 57f)
    clipPath(L_BODY) {
        translate(wave2, 17f * (1f - fill)) { drawPath(L_WAVE_BACK, gradient, alpha = 0.35f) }
        translate(wave, 17f * (1f - fill)) { drawPath(L_WAVE_FRONT, gradient) }
    }
    drawPath(L_TICKS, ok, alpha = 0.8f * chip, style = Stroke(1.8f, cap = StrokeCap.Round))
    // The controller chip pops in.
    if (chip > 0f) {
        scale(chip, pivot = Offset(32f, 46.5f)) {
            val a = chip.coerceIn(0f, 1f)
            drawRoundRect(onBrand, topLeft = Offset(27.5f, 42f), size = androidx.compose.ui.geometry.Size(9f, 9f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.8f), alpha = a, style = Stroke(2f))
            drawPath(L_PINS, onBrand, alpha = a, style = Stroke(1.8f, cap = StrokeCap.Round))
            listOf(Offset(32f, 37.6f), Offset(22.6f, 46.5f), Offset(41.4f, 46.5f)).forEach { drawCircle(onBrand, 1.5f, it, alpha = a) }
        }
    }
    drawPath(L_BODY, ink, style = Stroke(3f, join = StrokeJoin.Round))
    // The lid lifts on its hinge at the left and settles back.
    val swing = when {
        lid < 0.35f -> lid / 0.35f
        lid < 0.7f -> 1f - (lid - 0.35f) / 0.35f * 0.62f
        else -> 0.38f * (1f - (lid - 0.7f) / 0.3f)
    }
    translate(0f, -3f * swing) {
        rotate(-16f * swing, pivot = Offset(13f, 22.5f)) {
            drawPath(L_LID, ink, style = Stroke(3.4f, cap = StrokeCap.Round))
            drawPath(L_HANDLE, ink, style = Stroke(3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(ok, 1.3f, Offset(45.5f, 22.5f), alpha = led)
        }
    }
}
