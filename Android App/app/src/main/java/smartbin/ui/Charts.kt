package smartbin.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import smartbin.data.Bin
import smartbin.data.Hub
import smartbin.data.HubState
import smartbin.data.Sample
import smartbin.data.v
import smartbin.notify.Sfx
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Series(val color: Color, val pts: List<Pair<Long, Float>>, val dash: Int = 0, val area: Boolean = false)
class Mark(val v: Float, val text: String)

/** One chart: its scale, lines, limit lines, legend and the line of text under it. */
class ChartSpec(
    val min: Float, val max: Float, val ticks: List<Float>, val series: List<Series>, val marks: List<Mark>,
    val legend: List<Triple<Color, Int, String>>, val note: String? = null,
)

private const val POINTS = 60

/** History of one bin over the window, averaged into at most 60 points so the line stays smooth. */
private fun window(h: List<Sample>, from: Long, to: Long, pick: (Sample) -> Float): List<Pair<Long, Float>> {
    val inside = h.filter { it.t in from..to }
    if (inside.size <= POINTS) return inside.map { it.t to pick(it) }
    val span = (to - from).toDouble() / POINTS
    return inside.groupBy { ((it.t - from) / span).toInt().coerceIn(0, POINTS - 1) }
        .toSortedMap()
        .map { (_, g) -> g.map { it.t }.average().toLong() to g.map(pick).average().toFloat() }
}

private val TEMP = Triple(0f, 85f, listOf(0f, 20f, 40f, 60f, 85f))
private val HUM = Triple(0f, 100f, listOf(0f, 25f, 50f, 75f, 100f))
private val PRESS = Triple(300f, 1100f, listOf(300f, 500f, 700f, 900f, 1100f))
private val GAS = Triple(0f, 500f, listOf(0f, 100f, 200f, 300f, 400f, 500f))

@Composable
fun fillSpec(vm: AppViewModel, hub: HubState, history: Map<String, List<Sample>>): ChartSpec {
    val c = Sb.c
    val now = System.currentTimeMillis()
    val from = now - vm.rangeMin * 60_000L
    val bins = hub.bins
    val all = vm.chartBin == null || bins.none { it.id == vm.chartBin }
    val ids = if (all) bins.sortedByDescending { it.reading.fill }.take(5) else bins.filter { it.id == vm.chartBin }
    val th = ids.firstOrNull()?.cal?.v("fill_th")?.toFloat() ?: 80f
    val series = ids.mapIndexed { k, b ->
        Series(binColor(bins.indexOf(b), c.isDark), window(history[b.id].orEmpty(), from, now) { it.fill }, if (all) k % 3 else 0, k == 0)
    }
    val legend = ids.mapIndexed { k, b -> Triple(binColor(bins.indexOf(b), c.isDark), if (all) k % 3 else 0, b.name) } +
        Triple(c.bad, 1, "Threshold")
    val note = if (all && bins.size > 5) "The 5 fullest of ${bins.size}. Pick a bin to see any other." else null
    return ChartSpec(0f, 100f, listOf(0f, 25f, 50f, 75f, 100f), series, listOf(Mark(th, "ALERT LIMIT ${th.toInt()}%")), legend, note)
}

@Composable
fun envSpec(vm: AppViewModel, hub: HubState, history: Map<String, List<Sample>>): ChartSpec? {
    val c = Sb.c
    val b = vm.binFor(vm.envBin, hub.bins) ?: return null
    val now = System.currentTimeMillis()
    val from = now - vm.rangeMin * 60_000L
    val h = history[b.id].orEmpty()
    val tTh = b.cal.v("temp_th").toFloat()
    val hTh = b.cal.v("hum_th").toFloat()
    val gTh = b.cal.v("gas_th").toFloat()
    val r = b.reading
    fun one(scale: Triple<Float, Float, List<Float>>, col: Color, name: String, unit: String, cur: String, lim: Float?, pick: (Sample) -> Float, note: String): ChartSpec {
        val legend = mutableListOf(Triple(col, 0, "$name, now **$cur $unit**"))
        if (lim != null) legend += Triple(c.bad, 1, "Alert limit ${lim.toInt()} $unit")
        legend += Triple(Color.Transparent, -1, "Scale ${scale.first.toInt()} to ${scale.second.toInt()} $unit")
        return ChartSpec(
            scale.first, scale.second, scale.third, listOf(Series(col, window(h, from, now, pick), area = true)),
            if (lim == null) emptyList() else listOf(Mark(lim, "ALERT LIMIT ${lim.toInt()} $unit")), legend, note,
        )
    }
    return when (vm.envMode) {
        EnvMode.TEMP -> one(TEMP, c.temp, "Temperature", "C", fmt(r.temp, 1), tTh, { it.temp },
            "Temperature of ${b.name} in degrees C, after the offset from Settings. The scale runs to 85 C, the most the BME680 can read.")
        EnvMode.HUMID -> one(HUM, c.humid, "Humidity", "%", fmt(r.hum, 0), hTh, { it.hum },
            "Relative humidity of ${b.name}, 0 to 100 percent, the full BME680 range. The dashed line is its alert limit, set in Settings.")
        EnvMode.GAS -> one(GAS, c.gas, "Air quality", "IAQ", fmt(r.gas, 0), gTh, { it.gas },
            "Air quality index of ${b.name} from the BME680, 0 to 500, lower is cleaner. The dashed line is its alert limit.")
        EnvMode.PRESS -> one(PRESS, c.press, "Pressure", "hPa", fmt(r.press, 1), null, { it.press },
            "Air pressure around ${b.name} on the BME680 range of 300 to 1100 hPa, so normal weather looks flat. Charted here only, with no tile and no alert.")
        EnvMode.ALL -> ChartSpec(
            0f, 200f, listOf(0f, 50f, 100f, 150f, 200f),
            listOf(
                Series(c.temp, window(h, from, now) { it.temp / tTh * 100 }, area = true),
                Series(c.gas, window(h, from, now) { it.gas / gTh * 100 }, dash = 1),
                Series(c.humid, window(h, from, now) { it.hum / hTh * 100 }, dash = 2),
            ),
            listOf(Mark(100f, "ALERT LIMIT (100%)")),
            listOf(
                Triple(c.temp, 0, "Temperature, limit ${tTh.toInt()} C"),
                Triple(c.humid, 2, "Humidity, limit ${hTh.toInt()} %RH"),
                Triple(c.gas, 1, "Air quality, limit ${gTh.toInt()} IAQ"),
                Triple(c.bad, 1, "Alert limit (100%)"),
            ),
            "Temperature, humidity and air quality are each drawn as a share of their own limit, so the dashed line at 100% is the limit for all three. The scale runs to 200%, which keeps that line in the middle. Pick one reading for real units.",
        )
    }
}

/**
 * The mockup's lineChart(): grid lines with their values, the lines with a
 * soft fill under the first, a dot at the newest point, the dashed alert
 * limit with its label, and the start time and "now" along the bottom.
 */
@Composable
fun LineChart(spec: ChartSpec, from: Long, modifier: Modifier = Modifier, height: Dp = 190.dp) {
    val c = Sb.c
    val measurer = rememberTextMeasurer()
    val start = remember(from / 60_000) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(from)) }
    Box(modifier.fillMaxWidth().then(if (height > 0.dp) Modifier.height(height) else Modifier.fillMaxSize()).well(c, 14.dp)) {
        Canvas(Modifier.fillMaxSize()) { chart(spec, from, start, measurer, c) }
        if (spec.series.all { it.pts.size < 2 }) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                T("Collecting readings for this chart", size = 0.8f, weight = FontWeight.Bold, color = c.ink2)
            }
        }
    }
}

private fun DrawScope.chart(spec: ChartSpec, from: Long, start: String, tm: TextMeasurer, c: SbColors) {
    val to = System.currentTimeMillis()
    val l = 32.dp.toPx()
    val r = 12.dp.toPx()
    val t = 12.dp.toPx()
    val b = 20.dp.toPx()
    val pw = size.width - l - r
    val ph = size.height - t - b
    fun y(v: Float) = t + ph - ((v.coerceIn(spec.min, spec.max) - spec.min) / (spec.max - spec.min)) * ph
    fun x(time: Long) = l + ((time - from).toFloat() / (to - from).toFloat()).coerceIn(0f, 1f) * pw
    val label = TextStyle(fontFamily = Inter, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = c.ink2)
    spec.ticks.forEach { v ->
        val yy = y(v)
        drawLine(c.ink2.soft(0.22f), Offset(l, yy), Offset(size.width - r, yy), 1.dp.toPx())
        val m = tm.measure(Math.round(v).toString(), label)
        drawText(m, topLeft = Offset(l - 7.dp.toPx() - m.size.width, yy - m.size.height / 2f))
    }
    val dashes = listOf(null, floatArrayOf(7f, 5f), floatArrayOf(2.5f, 4.5f))
    spec.series.forEach { s ->
        if (s.pts.size < 2) return@forEach
        val line = Path()
        s.pts.forEachIndexed { k, (time, v) -> if (k == 0) line.moveTo(x(time), y(v)) else line.lineTo(x(time), y(v)) }
        if (s.area) {
            val area = Path().apply {
                addPath(line)
                lineTo(x(s.pts.last().first), t + ph)
                lineTo(x(s.pts.first().first), t + ph)
                close()
            }
            drawPath(area, Brush.verticalGradient(listOf(s.color.soft(0.24f), s.color.copy(alpha = 0f)), startY = t, endY = t + ph))
        }
        val d = dashes[s.dash.coerceIn(0, 2)]
        drawPath(
            line, s.color,
            style = Stroke(
                2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round,
                pathEffect = d?.let { PathEffect.dashPathEffect(floatArrayOf(it[0].dp.toPx(), it[1].dp.toPx())) },
            ),
        )
        val end = Offset(x(s.pts.last().first), y(s.pts.last().second))
        drawCircle(s.color.soft(0.22f), 7.5.dp.toPx(), end)
        drawCircle(s.color, 3.8.dp.toPx(), end)
    }
    val markStyle = TextStyle(fontFamily = Inter, fontSize = 8.6.sp, fontWeight = FontWeight.ExtraBold, color = c.bad)
    spec.marks.forEach { m ->
        val my = y(m.v)
        drawLine(c.bad, Offset(l, my), Offset(size.width - r, my), 1.6.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 6.dp.toPx())))
        val txt = tm.measure(m.text, markStyle)
        val pad = 7.dp.toPx()
        val box = Size(txt.size.width + pad * 2, 14.dp.toPx())
        val tl = Offset(l + 8.dp.toPx(), my - 17.dp.toPx())
        drawRoundRect(c.well, tl, box, CornerRadius(4.dp.toPx()))
        drawRoundRect(c.bad, tl, box, CornerRadius(4.dp.toPx()), style = Stroke(1.2.dp.toPx()))
        drawText(txt, topLeft = Offset(tl.x + pad, tl.y + (box.height - txt.size.height) / 2f))
    }
    val bottom = TextStyle(fontFamily = Inter, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = c.ink2)
    val st = tm.measure(start, bottom)
    drawText(st, topLeft = Offset(l, size.height - st.size.height - 2.dp.toPx()))
    val nw = tm.measure("now", bottom.copy(color = c.ok))
    drawText(nw, topLeft = Offset(size.width - r - nw.size.width, size.height - nw.size.height - 2.dp.toPx()))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Legend(items: List<Triple<Color, Int, String>>, modifier: Modifier = Modifier) {
    val c = Sb.c
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (col, dash, text) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (dash >= 0) Swatch(col, dash)
                T(text, size = 0.76f, weight = FontWeight.Bold, color = c.ink2, bold = c.ink)
            }
        }
    }
}

@Composable
fun RangeSeg(vm: AppViewModel) {
    val c = Sb.c
    TwoWay(full = true) {
        listOf(15 to "15 m", 60 to "1 h", 360 to "6 h", 1440 to "24 h").forEach { (m, label) ->
            Tw(vm.rangeMin == m, { vm.rangeMin = m }, label, cc = c.fill)
        }
    }
}

@Composable
fun EnvSeg(vm: AppViewModel) {
    val c = Sb.c
    TwoWay(full = true) {
        listOf(
            Triple(EnvMode.ALL, "ALL", Ic.layers to c.ink),
            Triple(EnvMode.TEMP, "TEMPERATURE", Ic.temp(30.0) to c.temp),
            Triple(EnvMode.HUMID, "HUMIDITY", Ic.humid to c.humid),
            Triple(EnvMode.GAS, "AIR QUALITY", Ic.gas to c.gas),
            Triple(EnvMode.PRESS, "PRESSURE", Ic.press to c.press),
        ).forEach { (m, label, ic) ->
            Tw(
                vm.envMode == m, { vm.envMode = m }, label, ic.first, ic.second,
                grow = if (m == EnvMode.ALL) 0.75f else 1f, stack = true, minHeight = 52.dp, textSize = 0.62f,
            )
        }
    }
}

/**
 * The bin selector: one row of keys while the bins fit, otherwise a picker
 * that opens a sheet. [allowAll] adds ALL BINS for the fill chart.
 */
@Composable
fun BinSeg(vm: AppViewModel, hub: HubState, forWhat: PickFor, selected: String?, allowAll: Boolean, locked: Boolean = false, onPick: (String?) -> Unit) {
    val c = Sb.c
    val bins = hub.bins
    val sel = if (allowAll) selected?.takeIf { id -> bins.any { it.id == id } } else (vm.binFor(selected, bins)?.id)
    if (bins.size <= (if (allowAll) 3 else 4)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp, horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            if (allowAll) FilterKey(Ic.layers, "ALL BINS", sel == null) { onPick(null) }
            bins.forEachIndexed { i, b ->
                FilterKey(Ic.fill, b.name, sel == b.id, binColor(i, c.isDark), simRing = b.simulating, enabled = !locked || sel == b.id) { onPick(b.id) }
            }
        }
        return
    }
    val b = bins.firstOrNull { it.id == sel }
    val i = bins.indexOf(b)
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 54.dp)
            .graphicsLayer { alpha = if (locked) 0.6f else 1f }
            .then(if (pressed) Modifier.clip(RoundedCornerShape(14.dp)).background(c.base).sunk(c, 14.dp) else Modifier.bump(c, 14.dp, ring = c.accentSoft))
            .press(!locked, Sfx.OPEN, src) { vm.overlay = Overlay.Pick(forWhat) }
            .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (b != null) BinIcon(b.reading.fill, binColor(i, c.isDark), 34.dp)
        else Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) { Ico(Ic.layers, 21.dp, c.ink2) }
        Column(Modifier.weight(1f)) {
            T(b?.name ?: "All bins", size = 0.95f, maxLines = 1)
            T(
                if (b != null) "${b.place} · ${Math.round(b.reading.fill)}%" + modeTxt(b) else if (bins.size > 5) "The 5 fullest of ${bins.size} are drawn" else "${bins.size} bins",
                size = 0.76f, weight = FontWeight.SemiBold, color = c.ink2, maxLines = 1,
            )
        }
        T((if (b != null) "${i + 1} of ${bins.size}" else "") + if (locked) "\nlocked" else "", size = 0.72f, color = c.ink2, align = androidx.compose.ui.text.style.TextAlign.End)
        Ico(Ic.chev, 18.dp, c.ink2)
    }
}

fun modeTxt(b: Bin): String {
    val cal = b.calibrating
    val sim = b.simulating
    return when { cal && sim -> " · sim + cal"; cal -> " · calibrating"; sim -> " · simulation"; else -> "" }
}

@Composable
fun Charts(vm: AppViewModel, hub: HubState) {
    val c = Sb.c
    val history by Hub.history.collectAsState()
    val from = System.currentTimeMillis() - vm.rangeMin * 60_000L
    ScrollPage {
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Sh(Ic.fill, "Fill level", c.fill, Modifier.weight(1f))
                IconKey(Ic.expand, { vm.fullscreen = "fill" }, tint = c.ink2, size = 40.dp, iconSize = 18.dp, radius = 12.dp, sound = Sfx.OPEN)
            }
            RangeSeg(vm)
            BinSeg(vm, hub, PickFor.CHART, vm.chartBin, allowAll = true) { vm.chartBin = it }
            val f = fillSpec(vm, hub, history)
            LineChart(f, from)
            Legend(f.legend)
            f.note?.let { Hint(it) }
        }
        val e = envSpec(vm, hub, history)
        if (e != null) Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Sh(Ic.temp(30.0), "Environment", c.temp, Modifier.weight(1f))
                IconKey(Ic.expand, { vm.fullscreen = "env" }, tint = c.ink2, size = 40.dp, iconSize = 18.dp, radius = 12.dp, sound = Sfx.OPEN)
            }
            EnvSeg(vm)
            BinSeg(vm, hub, PickFor.ENV, vm.envBin, allowAll = false) { vm.envBin = it }
            LineChart(e, from)
            Legend(e.legend)
            e.note?.let { Hint(it) }
        }
    }
}

/** The sideways chart: a bar with the title, its picker and controls, then the chart over the whole width. */
@Composable
fun FullChart(vm: AppViewModel, hub: HubState) {
    val c = Sb.c
    val history by Hub.history.collectAsState()
    val env = vm.fullscreen == "env"
    val from = System.currentTimeMillis() - vm.rangeMin * 60_000L
    val spec = if (env) envSpec(vm, hub, history) else fillSpec(vm, hub, history)
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().bump(c, 14.dp).padding(start = 6.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val name = if (env) vm.binFor(vm.envBin, hub.bins)?.name.orEmpty() else hub.bins.firstOrNull { it.id == vm.chartBin }?.name ?: "All bins"
            Row(
                Modifier.defaultMinSize(minHeight = 44.dp).clip(RoundedCornerShape(12.dp))
                    .press(true, Sfx.OPEN) { vm.overlay = Overlay.Pick(if (env) PickFor.ENV else PickFor.CHART) }.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Sh(if (env) Ic.temp(30.0) else Ic.fill, if (env) "Environment" else "Fill level", if (env) c.temp else c.fill)
                T(name, size = 0.86f, maxLines = 1)
                Ico(Ic.chev, 16.dp, c.ink2)
            }
            Box(Modifier.weight(1f)) { if (env) EnvSeg(vm) else RangeSeg(vm) }
            IconKey(Ic.shrink, { vm.fullscreen = null }, tint = c.ink2, size = 40.dp, iconSize = 18.dp, radius = 12.dp, sound = Sfx.BACK)
        }
        if (spec != null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                LineChart(spec, from, Modifier.widthIn(max = 1400.dp), height = 0.dp)
            }
            Legend(spec.legend, Modifier.padding(horizontal = 6.dp))
        }
    }
}
