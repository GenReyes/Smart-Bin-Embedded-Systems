package smartbin.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import smartbin.R
import smartbin.data.Mode

enum class ThemeMode { AUTO, LIGHT, DARK }

/**
 * The design tokens of the mockup, the .ph[data-th] and .ph[data-mode]
 * blocks of smartbin-app-mockup v2.html. The accent and the page tint follow
 * the app mode: green for normal, violet for simulation, teal for
 * calibration, blue for both, red for an alert.
 */
@Immutable
data class SbColors(
    val isDark: Boolean,
    val base: Color,
    val tint: Color,
    val shL: Color,
    val shD: Color,
    val ink: Color,
    val ink2: Color,
    val well: Color,
    val ok: Color,
    val warn: Color,
    val bad: Color,
    val sim: Color,
    val cal: Color,
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val simSoft: Color,
    val calSoft: Color,
    val temp: Color,
    val humid: Color,
    val weight: Color,
    val gas: Color,
    val press: Color,
    val fill: Color,
    val bt: Color,
    val lid: Color,
    val motion: Color,
    val link: Color,
    val cloud: Color,
    val tamper: Color,
    val lvCool: Color,
    val lvLow: Color,
    val lvMid: Color,
    val lvHigh: Color,
    val lvCrit: Color,
    /** The accent bar and mode chip: one colour, or violet to blue to teal in SIM + CAL. */
    val bar: List<Color>,
) {
    val barBrush: Brush get() = if (bar.size == 1) Brush.linearGradient(listOf(bar[0], bar[0])) else Brush.horizontalGradient(bar)
    /** Text on the brutal coloured banners: white on light, near black on dark. */
    val onBanner: Color get() = if (isDark) Color(0xFF13100D) else Color.White
}

private val Light = SbColors(
    isDark = false,
    base = Color(0xFFE4DCD2), tint = Color(0xFFE4DCD2), shL = Color(0xFFFFFBF5), shD = Color(0xFFBEB2A4),
    ink = Color(0xFF2A241E), ink2 = Color(0xFF7C7265), well = Color(0xFFDED5CA),
    ok = Color(0xFF1E8E3E), warn = Color(0xFFD98A1F), bad = Color(0xFFD93025), sim = Color(0xFF7A45B5), cal = Color(0xFF0D8A8A),
    accent = Color(0xFF1E8E3E), accentSoft = Color(0x381E8E3E), onAccent = Color.White,
    simSoft = Color(0x4D7A45B5), calSoft = Color(0x4D0D8A8A),
    temp = Color(0xFFC2591B), humid = Color(0xFF1F6FA8), weight = Color(0xFF7A5A2E), gas = Color(0xFF6B4FB8), press = Color(0xFFB03A6F),
    fill = Color(0xFF1E7A52), bt = Color(0xFF1A62C4), lid = Color(0xFFB07415), motion = Color(0xFF0E7F9E), link = Color(0xFF1E8E3E),
    cloud = Color(0xFFD9631E), tamper = Color(0xFFD93025),
    lvCool = Color(0xFF1F6FA8), lvLow = Color(0xFF1E8E3E), lvMid = Color(0xFFB88410), lvHigh = Color(0xFFD9641F), lvCrit = Color(0xFFD93025),
    bar = listOf(Color(0xFF1E8E3E)),
)

private val Dark = SbColors(
    isDark = true,
    base = Color(0xFF2B2622), tint = Color(0xFF2B2622), shL = Color(0xFF3B342E), shD = Color(0xFF1A1613),
    ink = Color(0xFFEFE7DC), ink2 = Color(0xFFA2978B), well = Color(0xFF262220),
    ok = Color(0xFF4FBF6E), warn = Color(0xFFE8A94A), bad = Color(0xFFFF6B5E), sim = Color(0xFFB98BE8), cal = Color(0xFF3FC7C2),
    accent = Color(0xFF4FBF6E), accentSoft = Color(0x384FBF6E), onAccent = Color(0xFF13100D),
    simSoft = Color(0x57B98BE8), calSoft = Color(0x523FC7C2),
    temp = Color(0xFFF0A368), humid = Color(0xFF6CB8E8), weight = Color(0xFFD6B98C), gas = Color(0xFFB596F0), press = Color(0xFFE58AB4),
    fill = Color(0xFF4FC48D), bt = Color(0xFF79AEF5), lid = Color(0xFFE8B45C), motion = Color(0xFF5FD0E8), link = Color(0xFF4FBF6E),
    cloud = Color(0xFFF29A5C), tamper = Color(0xFFFF6B5E),
    lvCool = Color(0xFF6CB8E8), lvLow = Color(0xFF4FBF6E), lvMid = Color(0xFFE8C45A), lvHigh = Color(0xFFF0A368), lvCrit = Color(0xFFFF6B5E),
    bar = listOf(Color(0xFF4FBF6E)),
)

/** The tokens for one theme and one app mode. */
fun sbColors(dark: Boolean, mode: Mode): SbColors {
    val c = if (dark) Dark else Light
    return when (mode) {
        Mode.NORMAL -> c
        Mode.SIM -> c.copy(
            accent = c.sim, bar = listOf(c.sim),
            accentSoft = if (dark) Color(0x38B98BE8) else Color(0x387A45B5),
            tint = if (dark) Color(0xFF2C2530) else Color(0xFFE2DAE1),
        )
        Mode.CALIB -> c.copy(
            accent = c.cal, bar = listOf(c.cal),
            accentSoft = if (dark) Color(0x333FC7C2) else Color(0x330D8A8A),
            tint = if (dark) Color(0xFF222B2A) else Color(0xFFDBE3E0),
        )
        Mode.DUAL -> {
            val mid = if (dark) Color(0xFF7CA9D5) else Color(0xFF43679F)
            c.copy(
                accent = mid, bar = listOf(c.sim, mid, c.cal),
                accentSoft = if (dark) Color(0x3D7CA9D5) else Color(0x3D43679F),
                tint = if (dark) Color(0xFF262A33) else Color(0xFFDEDDE6),
            )
        }
        Mode.ALERT -> c.copy(
            accent = c.bad, bar = listOf(c.bad),
            accentSoft = if (dark) Color(0x33FF6B5E) else Color(0x33D93025),
            tint = if (dark) Color(0xFF302422) else Color(0xFFEADCD5),
        )
    }
}

/** Colours that change with the mode ease across, as the mockup's background does in 0.5 s. */
@Composable
fun animated(c: SbColors): SbColors {
    val spec = tween<Color>(500)
    val accent by animateColorAsState(c.accent, spec, label = "accent")
    val soft by animateColorAsState(c.accentSoft, spec, label = "soft")
    val tint by animateColorAsState(c.tint, spec, label = "tint")
    return c.copy(accent = accent, accentSoft = soft, tint = tint)
}

val LocalSb = staticCompositionLocalOf { Light }

object Sb {
    val c: SbColors
        @Composable get() = LocalSb.current
}

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_regular, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold),
    Font(R.font.inter_extrabold, FontWeight.Black),
)

/**
 * Each bin has its own colour. Twelve hues spread round the wheel, red left
 * out because red means a limit, ordered so neighbours differ. Bins 13 to 24
 * reuse them one step darker and 25 to 36 one step lighter, the same formula
 * as the mockup.
 */
private val HUES = intArrayOf(153, 287, 47, 207, 100, 260, 20, 180, 313, 73, 233, 127)
private val ODD = intArrayOf(0, 1, 0, 1, 0, 1, 1, 0, 0, 1, 1, 1)

fun binColor(i: Int, dark: Boolean): Color {
    val t = (i / 12) % 3
    val r = i / 36
    val h = (HUES[i % 12] + r * 11) % 360
    val odd = ODD[i % 12] == 1
    val l = (if (dark) intArrayOf(64, 78, 50)[t] else intArrayOf(40, 27, 54)[t]) + if (odd) (if (dark) -9 else 8) else 0
    val s = if (dark) intArrayOf(70, 60, 72)[t] else intArrayOf(66, 62, 72)[t]
    return Color.hsl(h.toFloat(), s / 100f, l / 100f)
}

/** color-mix(in srgb, x p%, transparent) */
fun Color.soft(p: Float) = copy(alpha = alpha * p)

/** color-mix(in srgb, a p%, b) */
fun mix(a: Color, p: Float, b: Color) = Color(
    red = a.red * p + b.red * (1 - p),
    green = a.green * p + b.green * (1 - p),
    blue = a.blue * p + b.blue * (1 - p),
    alpha = 1f,
)
