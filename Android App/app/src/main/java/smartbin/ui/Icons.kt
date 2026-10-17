package smartbin.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * The icon set of the mockup (copied there from dashboard v9), kept as the
 * same SVG markup and turned into vectors by a small reader. Only what the
 * mockup uses is understood: path, circle and rect, fill="currentColor" for
 * a solid shape, opacity, stroke-width, and groups that turn or move.
 */

private val TAG = Regex("""<(/?)(\w+)([^>]*?)/?>""")
private val ATTR = Regex("""([\w-]+)="([^"]*)"""")

private fun circleD(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

private fun rectD(x: Float, y: Float, w: Float, h: Float, rx: Float): String {
    if (rx <= 0f) return "M$x ${y}h${w}v${h}h${-w}z"
    val r = minOf(rx, w / 2, h / 2)
    val iw = w - 2 * r
    val ih = h - 2 * r
    return "M${x + r} ${y}h${iw}a$r $r 0 0 1 $r ${r}v${ih}a$r $r 0 0 1 ${-r} ${r}h${-iw}a$r $r 0 0 1 ${-r} ${-r}v${-ih}a$r $r 0 0 1 $r ${-r}z"
}

fun svg(name: String, body: String, sw: Float = 2f): ImageVector {
    val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    val ink = SolidColor(Color.Black)
    val alphas = ArrayList<Float>().apply { add(1f) }
    for (m in TAG.findAll(body)) {
        val closing = m.groupValues[1] == "/"
        val tag = m.groupValues[2]
        val a = ATTR.findAll(m.groupValues[3]).associate { it.groupValues[1] to it.groupValues[2] }
        fun f(k: String, d: Float = 0f) = a[k]?.toFloatOrNull() ?: d
        if (tag == "g") {
            if (closing) {
                b.clearGroup()
                if (alphas.size > 1) alphas.removeAt(alphas.lastIndex)
            } else {
                b.addGroup(
                    rotate = f("rot"), pivotX = f("px"), pivotY = f("py"),
                    translationX = f("tx"), translationY = f("ty"),
                )
                alphas.add(alphas.last() * f("opacity", 1f))
            }
            continue
        }
        if (closing) continue
        val d = when (tag) {
            "path" -> a["d"] ?: continue
            "circle" -> circleD(f("cx"), f("cy"), f("r"))
            "rect" -> rectD(f("x"), f("y"), f("width"), f("height"), f("rx"))
            else -> continue
        }
        val alpha = alphas.last() * f("opacity", 1f)
        if (a["fill"] == "currentColor") {
            b.addPath(addPathNodes(d), fill = ink, fillAlpha = alpha)
        } else {
            b.addPath(
                addPathNodes(d), fill = null, stroke = ink, strokeAlpha = alpha, strokeLineWidth = f("stroke-width", sw),
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }
    }
    return b.build()
}

/** The calibration ring is dashed 7 on, 6 off; vectors have no dashes, so the dashes are arcs. */
private fun dashedRing(cx: Float, cy: Float, r: Float, on: Float, off: Float): String {
    val circ = 2 * PI.toFloat() * r
    val n = (circ / (on + off)).toInt().coerceAtLeast(1)
    val step = 360f / n
    val sweep = step * on / (on + off)
    val sb = StringBuilder()
    for (k in 0 until n) {
        val a0 = Math.toRadians((k * step - 90).toDouble())
        val a1 = Math.toRadians((k * step + sweep - 90).toDouble())
        val x0 = cx + r * cos(a0).toFloat()
        val y0 = cy + r * sin(a0).toFloat()
        val x1 = cx + r * cos(a1).toFloat()
        val y1 = cy + r * sin(a1).toFloat()
        sb.append("M$x0 ${y0}A$r $r 0 0 1 $x1 $y1")
    }
    return sb.toString()
}

object Ic {
    private val cache = HashMap<String, ImageVector>()
    private fun k(key: String, body: () -> String): ImageVector = cache.getOrPut(key) { svg(key, body()) }

    val grid get() = k("grid") { """<rect x="3" y="3" width="7.5" height="7.5" rx="1.6"/><rect x="13.5" y="3" width="7.5" height="7.5" rx="1.6"/><rect x="3" y="13.5" width="7.5" height="7.5" rx="1.6"/><rect x="13.5" y="13.5" width="7.5" height="7.5" rx="1.6"/>""" }
    val chart get() = k("chart") { """<path d="M3 20h18"/><path d="M5 15l4.5-5 3.5 3L19 6"/>""" }
    val sliders get() = k("sliders") { """<path d="M4 7h10M18 7h2M4 17h4M12 17h8"/><circle cx="16" cy="7" r="2.2"/><circle cx="10" cy="17" r="2.2"/>""" }
    val flask get() = k("flask") { """<path d="M9.5 3v6.2L4.6 18a2 2 0 0 0 1.7 3h11.4a2 2 0 0 0 1.7-3L14.5 9.2V3"/><path d="M8 3h8M7.4 15h9.2"/>""" }
    val bell get() = k("bell") { """<path d="M18 9a6 6 0 1 0-12 0c0 6-2 7-2 7h16s-2-1-2-7"/><path d="M10.4 20.5a2 2 0 0 0 3.2 0"/>""" }
    val plus get() = k("plus") { """<path d="M12 5v14M5 12h14"/>""" }
    val gear get() = k("gear") { """<circle cx="12" cy="12" r="3"/><path d="M12 2.8v2.6M12 18.6v2.6M2.8 12h2.6M18.6 12h2.6M5.5 5.5l1.8 1.8M16.7 16.7l1.8 1.8M18.5 5.5l-1.8 1.8M7.3 16.7l-1.8 1.8"/>""" }
    val truck get() = k("truck") { """<path d="M2 7h11v9H2zM13 10h4.5l3.5 3.5V16h-8z"/><circle cx="6.5" cy="18.5" r="1.8"/><circle cx="16.5" cy="18.5" r="1.8"/>""" }
    val check get() = k("check") { """<path d="M4 12.5l5 5L20 6.5"/>""" }
    val humid get() = k("humid") { """<path d="M12 3.2s6.2 6.6 6.2 10.3a6.2 6.2 0 1 1-12.4 0C5.8 9.8 12 3.2 12 3.2z"/><circle cx="12" cy="14.4" r="3" fill="currentColor" opacity=".45"/>""" }
    val gas get() = k("gas") { """<path d="M6.5 17c0-2.6 2.6-2.6 2.6-5.2S6.5 9.2 6.5 6.6"/><path d="M12 18c0-2.6 2.6-2.6 2.6-5.2S12 10.2 12 7.6"/><path d="M17.5 17c0-2.6 2.6-2.6 2.6-5.2s-2.6-2.6-2.6-5.2"/>""" }
    val press get() = k("press") { """<path d="M4.6 17.6a8.6 8.6 0 1 1 14.8 0"/><path d="M12 14l3.6-4.4"/><circle cx="12" cy="14.2" r="1.5" fill="currentColor"/><path d="M7.2 17.6h9.6"/>""" }
    val tamper get() = k("tamper") { """<path d="M12 3.4L22 20H2z"/><path d="M12 9.6v4.2M12 17h.01"/>""" }
    val calib get() = k("calib") { """<path d="${dashedRing(12f, 12f, 8.4f, 7f, 6f)}"/><circle cx="12" cy="12" r="2.4" fill="currentColor"/><path d="M12 1.6v2.4M12 20v2.4M1.6 12h2.4M20 12h2.4"/>""" }
    val fill get() = k("fill") { """<path d="M5.4 4.4h13.2l-1.3 15.2a2 2 0 0 1-2 1.8H8.7a2 2 0 0 1-2-1.8z"/><path d="M6.9 12.6c1.6-1.1 3.3-1.1 4.9 0s3.3 1.1 4.9 0"/>""" }
    val bins get() = k("bins") { """<path d="M3.2 7.6h7l-.8 11.8H4zM13.8 7.6h7l-.8 11.8h-5.4z"/><path d="M2.4 7.6h8.6M13 7.6h8.6M5.4 5h2.6M16 5h2.6"/>""" }
    val clock get() = k("clock") { """<circle cx="12" cy="12" r="8.4"/><path d="M12 7.2V12l3.2 2"/>""" }
    val trash get() = k("trash") { """<path d="M4.4 6.8h15.2M9.6 6.8V4.6h4.8v2.2M6.8 6.8l1 12.6h8.4l1-12.6M10.2 10.4v5.4M13.8 10.4v5.4"/>""" }
    val stopwatch get() = k("stopwatch") { """<circle cx="12" cy="13.6" r="7.4"/><path d="M12 9.8v3.8l2.6 1.6M9.6 2.8h4.8M19.4 7.2l1.4-1.4"/>""" }
    val layers get() = k("layers") { """<path d="M12 3.4l8.6 4.6L12 12.6 3.4 8z"/><path d="M3.4 12.4L12 17l8.6-4.6M3.4 16.6L12 21.2l8.6-4.6"/>""" }
    val rank get() = k("rank") { """<path d="M9.6 6.6h10.8M9.6 12h10.8M9.6 17.4h10.8"/><path d="M4.8 5v3.6M3.6 5h1.2M3.4 10.4h2.8M3.4 15.4h2.6v1.6h-2.6v1.8h2.6"/>""" }
    val eye get() = k("eye") { """<path d="M2.4 12s3.6-6.4 9.6-6.4S21.6 12 21.6 12s-3.6 6.4-9.6 6.4S2.4 12 2.4 12z"/><circle cx="12" cy="12" r="2.8"/>""" }
    val eyeoff get() = k("eyeoff") { """<path d="M4 4l16 16"/><path d="M9.6 9.7a2.8 2.8 0 0 0 3.9 3.9"/><path d="M6.4 6.8C3.9 8.5 2.4 12 2.4 12s3.6 6.4 9.6 6.4c1.6 0 3-.4 4.2-1M18.3 15.1c1.9-1.6 3.3-3.1 3.3-3.1S18 5.6 12 5.6c-.8 0-1.5.1-2.2.3"/>""" }
    val bluetooth get() = k("bluetooth") { """<path d="M7.4 7.4l9.2 9.2L12 20.6V3.4l4.6 4L7.4 16.6"/>""" }
    val radar get() = k("radar") { """<path d="M12 12l5.6-5.6"/><circle cx="12" cy="12" r="1.6" fill="currentColor"/><path d="M8.4 15.6a5.1 5.1 0 0 1 0-7.2M5.6 18.4a9 9 0 0 1 0-12.8"/><path d="M15.6 8.4a5.1 5.1 0 0 1 0 7.2"/>""" }
    val key get() = k("key") { """<circle cx="8.2" cy="12" r="3.6"/><path d="M11.8 12h9v3M17.6 12v2.6"/>""" }
    val ruler get() = k("ruler") { """<rect x="2.6" y="8" width="18.8" height="8" rx="1.8"/><path d="M7 8v3M11 8v4M15 8v3M19 8v4"/>""" }
    val percent get() = k("percent") { """<path d="M18.5 5.5l-13 13"/><circle cx="7.6" cy="7.6" r="2.3"/><circle cx="16.4" cy="16.4" r="2.3"/>""" }
    val save get() = k("save") { """<path d="M4.5 4.5h11l4 4v11h-15z"/><path d="M8 4.5v5h6v-5M8 19.5v-5.5h8v5.5"/>""" }
    val reload get() = k("reload") { """<path d="M20.5 12a8.5 8.5 0 1 1-2.6-6.1"/><path d="M20.8 4.4v4.8h-4.8"/>""" }
    val download get() = k("download") { """<path d="M12 3.6v11.2M7.6 10.6l4.4 4.4 4.4-4.4"/><path d="M4.4 19.4h15.2"/>""" }
    val undo get() = k("undo") { """<path d="M3.6 12a8.4 8.4 0 1 0 2.6-6.1"/><path d="M3.3 4.4v4.8h4.8"/>""" }
    val capture get() = k("capture") { """<circle cx="12" cy="12" r="7.6"/><circle cx="12" cy="12" r="1.8" fill="currentColor"/><path d="M12 1.8v2.8M12 19.4v2.8M1.8 12h2.8M19.4 12h2.8"/>""" }
    val pin get() = k("pin") { """<rect x="3.4" y="4.6" width="17.2" height="14.8" rx="2.2"/><path d="M7.4 9.4h.01M12 9.4h.01M16.6 9.4h.01M7.4 14.6h9.2"/>""" }
    val tilt get() = k("tilt") { """<path d="M3.4 20.2h17.2"/><path d="M6.6 20.2L15 6.4"/><path d="M11.4 20.2a7.5 7.5 0 0 0-1.6-4.4"/>""" }
    val servo get() = k("servo") { """<circle cx="12" cy="13.6" r="3.2"/><path d="M12 10.4V4.6"/><path d="M4.6 13.6a7.4 7.4 0 0 1 3.2-6.1M19.4 13.6a7.4 7.4 0 0 0-3.2-6.1"/>""" }
    val cross get() = k("cross") { """<path d="M6.4 6.4l11.2 11.2M17.6 6.4L6.4 17.6"/>""" }
    val power get() = k("power") { """<path d="M12 3.4v8.2"/><path d="M6.8 6.6a7.6 7.6 0 1 0 10.4 0"/>""" }
    val chev get() = k("chev") { """<path d="M6 9l6 6 6-6"/>""" }
    val cloud get() = k("cloud") { """<path d="M7 18.6h10.4a4.2 4.2 0 0 0 .5-8.4 6 6 0 0 0-11.5-1A4.8 4.8 0 0 0 7 18.6z"/>""" }
    val back get() = k("back") { """<path d="M15 5l-7 7 7 7"/>""" }
    val expand get() = k("expand") { """<path d="M9.5 3.5H3.5v6M14.5 3.5h6v6M20.5 14.5v6h-6M3.5 14.5v6h6"/>""" }
    val shrink get() = k("shrink") { """<path d="M3.5 9.5h6v-6M20.5 9.5h-6v-6M14.5 20.5v-6h6M9.5 20.5v-6h-6"/>""" }
    val user get() = k("user") { """<circle cx="12" cy="8.2" r="3.8"/><path d="M4.6 20.4a7.4 7.4 0 0 1 14.8 0"/>""" }
    val palette get() = k("palette") { """<path d="M12 3.4a8.6 8.6 0 1 0 0 17.2c1.2 0 1.9-.8 1.9-1.8 0-1-.9-1.5-.9-2.4s.7-1.6 1.7-1.6h1.3a4.6 4.6 0 0 0 4.6-4.6c0-3.8-3.9-6.8-8.6-6.8z"/><circle cx="7.8" cy="11.6" r="1.1" fill="currentColor"/><circle cx="10.6" cy="7.8" r="1.1" fill="currentColor"/><circle cx="15" cy="8.4" r="1.1" fill="currentColor"/>""" }
    val speaker get() = k("speaker") { """<path d="M4 9.5h3.6L12.4 5v14l-4.8-4.5H4z"/><path d="M15.6 9a4.2 4.2 0 0 1 0 6M18.4 6.4a8 8 0 0 1 0 11.2"/>""" }
    val vibrate get() = k("vibrate") { """<rect x="7.4" y="3.6" width="9.2" height="16.8" rx="2"/><path d="M3.6 8.4v7.2M20.4 8.4v7.2M11 17.2h2"/>""" }
    val sun get() = k("sun") { """<circle cx="12" cy="12" r="3.8"/><path d="M12 2.6v2.4M12 19v2.4M2.6 12h2.4M19 12h2.4M5.4 5.4l1.7 1.7M16.9 16.9l1.7 1.7M18.6 5.4l-1.7 1.7M7.1 16.9l-1.7 1.7"/>""" }

    /** The thermometer fills with the temperature, 10 to 50 C. */
    fun temp(t: Double?): ImageVector {
        val f = (((t ?: 28.0) - 10) / 40).coerceIn(0.05, 1.0)
        val q = (f * 20).toInt() / 20.0
        return k("temp$q") {
            val h = 6 * q + 1.4
            val y = 15.4 - h
            """<path d="M14.2 14.4V5.2a2.2 2.2 0 1 0-4.4 0v9.2a4.6 4.6 0 1 0 4.4 0z"/><rect x="10.6" y="$y" width="2.8" height="$h" rx="1.4" fill="currentColor"/><circle cx="12" cy="17.2" r="2.6" fill="currentColor"/>"""
        }
    }

    /** The scale needle swings with the load, a share of capacity. */
    fun weight(f: Double?): ImageVector {
        val deg = (((f ?: 0.3).coerceIn(0.0, 1.1) * 150 - 75) / 5).toInt() * 5
        return k("weight$deg") {
            """<path d="M4.6 20.5h14.8l-1.9-9.6H6.5z"/><path d="M9 10.9a3 3 0 1 1 6 0"/><g rot="$deg" px="12" py="17.6"><path d="M12 17.6v-3.1" stroke-width="2.2"/></g>"""
        }
    }

    fun lid(open: Boolean) = k("lid$open") {
        """<path d="M6.6 9.5l1.1 11h8.6l1.1-11"/><g rot="${if (open) -38 else 0}" px="6" py="9"><path d="M4.5 9.5h15"/><path d="M9.6 7.1h4.8"/></g>"""
    }

    fun motion(on: Boolean) = k("motion$on") {
        """<circle cx="5.5" cy="12" r="1.8" fill="currentColor"/><g opacity="${if (on) 1 else 0.4}"><path d="M10 7.5a7 7 0 0 1 0 9"/><path d="M13.6 5a11 11 0 0 1 0 14"/><path d="M17.2 2.6a15 15 0 0 1 0 18.8"/></g>"""
    }

    fun lock(locked: Boolean) = k("lock$locked") {
        """<rect x="4.5" y="10.5" width="15" height="10.5" rx="2.4"/><g ty="${if (locked) 0 else -3}"><path d="M8 10.5V7.4a4 4 0 0 1 8 0v3.1"/></g><circle cx="12" cy="15.7" r="1.5" fill="currentColor"/>"""
    }

    fun sunmoon(dark: Boolean) = k("sunmoon$dark") {
        val sun = if (dark) 1.0 else 0.3
        val moon = if (dark) 0.3 else 1.0
        """<g opacity="$sun"><circle cx="8.2" cy="12" r="3"/><path d="M8.2 5.4v1.6M8.2 17v1.6M3.4 7.2l1.2 1.2M11.8 15.6l1.2 1.2M3.4 16.8l1.2-1.2M1.2 12h1.6"/></g><g opacity="$moon"><path d="M19.4 7.6a5.4 5.4 0 1 0 0 8.8 6.2 6.2 0 0 1 0-8.8z"/></g>"""
    }

    fun wifi(live: Boolean) = k("wifi$live") {
        """<path d="M8.5 15.2a5 5 0 0 1 7 0" stroke-width="2.2"/><path d="M5.5 11.8a9.4 9.4 0 0 1 13 0" stroke-width="2.2"/><path d="M2.4 8.4a14 14 0 0 1 19.2 0" stroke-width="2.2"/><circle cx="12" cy="18.6" r="1.4" fill="currentColor"/>"""
    }

    /** The icon names alert rows carry. */
    fun named(n: String): ImageVector = when (n) {
        "fill" -> fill; "gas" -> gas; "tamper" -> tamper; "humid" -> humid; "lock" -> lock(true)
        "check" -> check; "truck" -> truck; "calib" -> calib; "temp" -> temp(41.0); "save" -> save; "gear" -> gear
        else -> bell
    }
}

/** An icon from [Ic] in the colour of the text around it, or [tint]. */
@Composable
fun Ico(icon: ImageVector, size: Dp = 21.dp, tint: Color = LocalContentColor.current, modifier: Modifier = Modifier) {
    Icon(icon, contentDescription = null, modifier = modifier.size(size), tint = tint)
}

/** @keyframes shake: the tamper triangle rocks while it is shown. */
@Composable
fun Modifier.shake(on: Boolean = true): Modifier {
    if (!on) return this
    val t = rememberInfiniteTransition(label = "shake")
    val r by t.animateFloat(-6f, 6f, infiniteRepeatable(tween(225), RepeatMode.Reverse), label = "shake")
    return graphicsLayer { rotationZ = r }
}

@Composable
fun Modifier.spin(on: Boolean = true): Modifier {
    if (!on) return this
    val t = rememberInfiniteTransition(label = "spin")
    val r by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "spin")
    return graphicsLayer { rotationZ = r }
}

/** @keyframes breathe, 0.82 to 1.12 in scale and 0.45 to 1 in opacity. */
@Composable
fun Modifier.breathe(on: Boolean = true, millis: Int = 2800): Modifier {
    if (!on) return this
    val t = rememberInfiniteTransition(label = "breathe")
    val f by t.animateFloat(0f, 1f, infiniteRepeatable(tween(millis / 2), RepeatMode.Reverse), label = "breathe")
    return graphicsLayer {
        val s = 0.82f + 0.30f * f
        scaleX = s
        scaleY = s
        alpha = 0.45f + 0.55f * f
    }
}

private val BIN_BODY = PathParser().parsePathString("M11.5 14h25l-2.6 26.2a2.4 2.4 0 0 1-2.4 2.1H16.5a2.4 2.4 0 0 1-2.4-2.1z").toPath()
private val BIN_LID = PathParser().parsePathString("M8 13h32M19 9.4h10").toPath()
private val WAVE_A = PathParser().parsePathString("M-24 4 q6 -5 12 0 t12 0 t12 0 t12 0 t12 0 t12 0 v36 h-72z").toPath()
private val WAVE_B = PathParser().parsePathString("M-24 7 q6 -5 12 0 t12 0 t12 0 t12 0 t12 0 t12 0 v36 h-72z").toPath()

/**
 * The bin with its fill level as a moving wave, the hero of every bin card
 * and tile. The waves drift sideways the whole time and rise or fall to a
 * new level over 0.6 s.
 */
@Composable
fun BinIcon(pct: Double, color: Color, size: Dp = 46.dp, modifier: Modifier = Modifier) {
    val level by animateFloatAsState(pct.coerceIn(0.0, 100.0).toFloat(), tween(600), label = "level")
    val t = rememberInfiniteTransition(label = "wave")
    val a by t.animateFloat(0f, 24f, infiniteRepeatable(tween(3400, easing = LinearEasing)), label = "a")
    val b by t.animateFloat(0f, 24f, infiniteRepeatable(tween(5000, easing = LinearEasing)), label = "b")
    Canvas(modifier.size(size)) {
        scale(this.size.width / 48f, pivot = Offset.Zero) {
            val y = 42f - level / 100f * 30f
            clipPath(BIN_BODY) {
                translate(a, y) { drawPath(WAVE_A, color, alpha = 0.85f) }
                translate(b, y) { drawPath(WAVE_B, color, alpha = 0.35f) }
            }
            drawPath(BIN_BODY, color, style = Stroke(2.6f, join = StrokeJoin.Round))
            drawPath(BIN_LID, color, style = Stroke(2.6f, cap = StrokeCap.Round))
        }
    }
}
