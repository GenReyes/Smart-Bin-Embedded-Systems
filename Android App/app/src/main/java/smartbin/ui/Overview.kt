package smartbin.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import smartbin.data.Bin
import smartbin.data.BinStatus
import smartbin.data.Hub
import smartbin.data.HubState
import smartbin.data.Source
import smartbin.data.v
import smartbin.notify.Sfx

@Composable
fun Overview(vm: AppViewModel, hub: HubState) {
    val c = Sb.c
    val bins = hub.bins
    val n = bins.size
    val grid = vm.binView == BinView.GRID || (vm.binView == BinView.AUTO && n > 2)
    val list = bins.filter {
        when (vm.binFilter) {
            BinFilter.ALL -> true
            BinFilter.ACTION -> it.needsAction
            BinFilter.MODES -> it.simulating || it.calibrating
        }
    }
    ScrollPage {
        liveBanners(hub).forEach { Banner(it.tone, it.icon, it.title, it.small, iconMod = Modifier.shake(it.icon === Ic.tamper)) }
        val head: @Composable () -> Unit = {
            Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Sh(Ic.bins, "Bins · $n", c.fill)
                Spacer(Modifier.weight(1f))
                if (n > 0) TwoWay {
                    Tw(grid, { vm.binView = BinView.GRID }, icon = Ic.grid, grow = null, minHeight = 36.dp)
                    Tw(!grid, { vm.binView = BinView.CARDS }, icon = Ic.layers, grow = null, minHeight = 36.dp)
                }
                if (vm.operator) AddMini { vm.addN = 1; vm.overlay = Overlay.AddBins }
            }
        }
        if (n == 0) {
            head()
            Card { Empty(Ic.bins, "No bins yet. Tap Add.") }
            return@ScrollPage
        }
        if (grid) {
            CollectNext(vm, bins)
            head()
            if (n > 2) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                FilterKey(Ic.bins, "All $n", vm.binFilter == BinFilter.ALL) { vm.binFilter = BinFilter.ALL }
                FilterKey(Ic.bell, "Needs action ${bins.count { it.needsAction }}", vm.binFilter == BinFilter.ACTION) { vm.binFilter = BinFilter.ACTION }
                FilterKey(Ic.flask, "Sim / Cal ${bins.count { it.simulating || it.calibrating }}", vm.binFilter == BinFilter.MODES) { vm.binFilter = BinFilter.MODES }
            }
            if (list.isEmpty()) Card { Empty(Ic.check, "No bins match this filter.") }
            else TileGrid(list) { b -> Tile(b) { vm.overlay = Overlay.BinDetail(b.id) } }
            TileLegend()
        } else {
            head()
            bins.forEach { BinCard(vm, it, hub) }
            CollectNext(vm, bins)
        }
    }
}

@Composable
private fun AddMini(onClick: () -> Unit) {
    val c = Sb.c
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Row(
        Modifier
            .height(40.dp)
            .then(if (pressed) Modifier.clip(RoundedCornerShape(20.dp)).background(c.base).sunk(c, 20.dp) else Modifier.bump(c, 20.dp))
            .press(true, Sfx.OPEN, src, onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        val t = if (pressed) c.accent else c.ink2
        Ico(Ic.plus, 17.dp, t)
        T("Add", size = 0.86f, weight = FontWeight.Bold, color = t)
    }
}

/** .btn.sm.binbtn: a key that stays pushed in while it is the chosen one. */
@Composable
fun FilterKey(icon: ImageVector?, text: String, on: Boolean, cc: Color = Sb.c.accent, simRing: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Sb.c
    Box(if (simRing && !on) Modifier.ring(c.simSoft, 11.dp) else Modifier) {
        Btn(
            text, onClick, icon = icon, small = true, pressedIn = on, cc = if (on) cc else c.ink2,
            sound = Sfx.CHOICE, enabled = enabled,
        )
    }
}

@Composable
fun Empty(icon: ImageVector, text: String) {
    val c = Sb.c
    Column(Modifier.fillMaxWidth().padding(vertical = 30.dp, horizontal = 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Ico(icon, 34.dp, c.ink2.soft(0.55f))
        T(text, size = 0.92f, weight = FontWeight.Bold, color = c.ink2, align = TextAlign.Center)
    }
}

// ---------- the bin card ----------

private fun band(f: Double) = when { f >= 1 -> 4; f >= .8 -> 3; f >= .5 -> 2; else -> 1 }

private fun lvColor(lv: Int, c: SbColors) = when (lv) { 0 -> c.lvCool; 1 -> c.lvLow; 2 -> c.lvMid; 3 -> c.lvHigh; else -> c.lvCrit }

@Composable
fun BinCard(vm: AppViewModel, b: Bin, hub: HubState, inSheet: Boolean = false) {
    val c = Sb.c
    val r = b.reading
    val cal = b.cal
    val op = vm.operator
    val fillTh = cal.v("fill_th")
    val calOn = b.calibrating
    val simOn = b.simulating
    val hot = r.status != BinStatus.OK || r.fill >= fillTh
    val urgent = b.urgent
    val stale = b.stale
    val haze = if (urgent && !inSheet) {
        val t = rememberInfiniteTransition(label = "haze")
        t.animateFloat(2f, 7f, infiniteRepeatable(tween(1300), RepeatMode.Reverse), label = "haze").value
    } else 2f
    val outline = when {
        calOn && simOn -> listOf(c.simSoft to 4.dp, c.calSoft to 9.dp)
        calOn -> listOf(c.calSoft to 4.dp)
        simOn -> listOf(c.simSoft to 4.dp)
        else -> emptyList()
    }
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                outline.forEach { (col, out) ->
                    val o = out.toPx()
                    drawRoundRect(
                        col, topLeft = Offset(-o + 1.5.dp.toPx(), -o + 1.5.dp.toPx()),
                        size = Size(size.width + 2 * o - 3.dp.toPx(), size.height + 2 * o - 3.dp.toPx()),
                        cornerRadius = CornerRadius(18.dp.toPx() + o), style = Stroke(3.dp.toPx()),
                    )
                }
            }
            .nm(c, out = if (inSheet) Out.O1 else Out.O2, ring = if (hot && !inSheet) c.accentSoft else null, ringWidth = haze.dp)
            .padding(14.dp),
    ) {
        // Top: hero, name, fill
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(62.dp).well(c, 31.dp), contentAlignment = Alignment.Center) {
                if (calOn) Ico(Ic.calib, 46.dp, c.cal, Modifier.spin())
                else BinIcon(r.fill, statusColor(b, c), 46.dp)
            }
            Column(Modifier.weight(1f).padding(top = 4.dp)) {
                T(b.name, size = 1.2f, spacing = 0.02f, maxLines = 1)
                T(b.place, size = 0.86f, weight = FontWeight.Medium, color = c.ink2, maxLines = 1)
                if (stale) Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Ico(Ic.clock, 13.dp, c.bad)
                    T(lastSeen(b), size = 0.74f, color = c.bad, maxLines = 1)
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.alpha(if (stale) 0.42f else 1f), verticalAlignment = Alignment.Bottom) {
                    T(Math.round(r.fill).toString(), size = 2.45f, color = c.accent, tnum = true, lineHeight = 1f)
                    T("%", Modifier.padding(bottom = 3.dp), size = 2.45f * 0.42f, color = c.accent, lineHeight = 1f)
                }
                if (op) IconKey(Ic.gear, { vm.openBinEdit(b) }, tint = c.ink2, iconSize = 16.dp, label = "SET", sound = Sfx.OPEN)
            }
        }
        // Six chips
        val live = b.source == Source.LIVE
        val cloud = b.source == Source.CLOUD
        val tsp = b.source == Source.THINGSPEAK
        val stKey = if (calOn) BinStatus.OK else r.status
        val stIcon = when (stKey) {
            BinStatus.TAMPER -> Ic.tamper; BinStatus.LOCKED -> Ic.lock(true); BinStatus.COLLECTING -> Ic.truck; else -> Ic.check
        }
        val stColor = when (stKey) { BinStatus.LOCKED -> c.sim; BinStatus.TAMPER -> c.tamper; BinStatus.COLLECTING -> c.cal; else -> c.ok }
        val stText = when (stKey) { BinStatus.LOCKED -> "LOCKED"; BinStatus.TAMPER -> "TAMPER"; BinStatus.COLLECTING -> "PICKUP"; else -> "OK" }
        Column(Modifier.padding(top = 13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Chip(Ic.lid(r.lidOpen), if (r.lidOpen) "OPEN" else "CLOSED", r.lidOpen, c.lid, Modifier.weight(1f))
                Chip(stIcon, stText, true, stColor, Modifier.weight(1f), Modifier.shake(stKey == BinStatus.TAMPER))
                Chip(
                    if (cloud) Ic.cloud else if (tsp) Ic.chart else Ic.wifi(live),
                    when { live -> "WI-FI"; cloud -> "CLOUD"; tsp -> "TSPEAK"; stale -> "OFFLINE"; else -> "DEMO" }, true,
                    when { live -> c.link; cloud -> c.cloud; tsp -> c.ts; stale -> c.tamper; else -> c.warn }, Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Chip(Ic.flask, "SIM", simOn, c.sim, Modifier.weight(1f))
                Chip(Ic.calib, "CAL", calOn, c.cal, Modifier.weight(1f))
                Chip(Ic.motion(r.motion), "MOTION", r.motion, c.motion, Modifier.weight(1f))
            }
        }
        // The fill bar with ticks at 50 and the limit
        val shown by animateFloatAsState(r.fill.coerceIn(0.0, 100.0).toFloat(), tween(600), label = "bar")
        val barColor = levelColor(b, c)
        Column(Modifier.alpha(if (stale) 0.42f else 1f)) {
            Box(
                Modifier
                    .padding(top = 16.dp, bottom = 6.dp)
                    .fillMaxWidth()
                    .height(24.dp)
                    .well(c, 12.dp, In.I2)
                    .drawBehind {
                        val p = 3.dp.toPx()
                        val w = (size.width * shown / 100f - 2 * p).coerceAtLeast(0f)
                        if (w > 0) drawRoundRect(barColor, topLeft = Offset(p, p), size = Size(w, size.height - 2 * p), cornerRadius = CornerRadius(10.dp.toPx()))
                        listOf(50.0, fillTh).forEach { at ->
                            val x = (size.width * at / 100).toFloat()
                            drawRoundRect(
                                c.ink.soft(0.35f), topLeft = Offset(x - 1.5.dp.toPx(), 4.dp.toPx()),
                                size = Size(3.dp.toPx(), size.height - 8.dp.toPx()), cornerRadius = CornerRadius(2.dp.toPx()),
                            )
                        }
                    },
            )
            Ticks(listOf(0.0, 50.0, fillTh, 100.0))
        }
        // Four sensor tiles
        Row(Modifier.padding(top = 12.dp).alpha(if (stale) 0.42f else 1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val tTh = cal.v("temp_th")
            val hTh = cal.v("hum_th")
            val wCap = cal.v("weight_cap")
            val gTh = cal.v("gas_th")
            SensorTile(Ic.temp(r.temp), c.temp, "${fmt(r.temp, 1)}°", "Temperature", r.temp >= tTh, if (r.temp < 22) 0 else band(r.temp / tTh), Modifier.weight(1f))
            SensorTile(Ic.humid, c.humid, "${Math.round(r.hum)}%", "Humidity", r.hum >= hTh,
                when { r.hum >= hTh - 10 -> 3; r.hum >= 60 -> 2; r.hum <= 35 -> 0; else -> 1 }, Modifier.weight(1f))
            SensorTile(Ic.weight(r.weight / wCap), c.weight, "%.2fkg".format(r.weight / 1000), "Weight", r.weight >= wCap, band(r.weight / wCap), Modifier.weight(1f))
            SensorTile(Ic.gas, c.gas, Math.round(r.gas).toString(), "Air quality", r.gas >= gTh, band(r.gas / gTh), Modifier.weight(1f))
        }
        // LOCK or UNLOCK, RESET TAMPER (operator), COLLECT or DONE
        // A command sent through Firebase or ThingSpeak holds every key until the bin confirms it.
        val wait = hub.pending[b.id]
        val busy = calOn || stale || wait != null
        val locked = r.status == BinStatus.LOCKED
        val tamper = r.status == BinStatus.TAMPER
        val coll = r.status == BinStatus.COLLECTING
        // Once the bin answers, the key that sent the command flashes green (confirmed) or red (timed out).
        val done = vm.settled[b.id]
        val fade = settleFade(done)
        fun flashFor(vararg cmds: String) = if (done != null && done.cmd in cmds) Modifier.settleFlash(done.ok, fade, c) else Modifier
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            val m = Modifier.weight(1f)
            val lm = m.then(flashFor("LOCK", "UNLOCK"))
            val cm = m.then(flashFor("COLLECT", "COLLECT_DONE"))
            if (wait != null && (wait.cmd == "LOCK" || wait.cmd == "UNLOCK")) WaitKey(wait, if (wait.cmd == "LOCK") "LOCKING" else "UNLOCKING", c.sim, m)
            else if (locked) Btn("UNLOCK", { vm.command(b.id, "UNLOCK") }, lm, Ic.lock(false), Look.SOLID, c.sim, enabled = !busy, stack = true, sound = Sfx.UNLOCK)
            else Btn("LOCK", { vm.command(b.id, "LOCK") }, lm, Ic.lock(true), Look.TINT, c.sim, enabled = !busy && !tamper, stack = true, sound = Sfx.LOCK)
            if (op) Btn(
                "RESET TAMPER", { vm.command(b.id, "CLEAR_TAMPER") }, m.then(flashFor("CLEAR_TAMPER")), Ic.tamper, if (tamper) Look.DANGER else Look.TINT, c.tamper,
                enabled = tamper && !stale && wait == null, stack = true, sound = Sfx.CONFIRM,
            )
            if (wait != null && wait.cmd == "COLLECT") WaitKey(wait, "STARTING", c.link, m)
            else if (coll) Btn("DONE", { vm.command(b.id, "COLLECT_DONE") }, cm, Ic.check, Look.SOLID, c.cal, enabled = !stale && wait == null, stack = true, sound = Sfx.DONE)
            else Btn("COLLECT", { vm.command(b.id, "COLLECT") }, cm, Ic.truck, Look.TINT, c.link, enabled = !busy, stack = true, sound = Sfx.PICKUP)
        }
        if (wait != null) {
            val via = if (wait.via == Source.THINGSPEAK) "ThingSpeak" else "Firebase"
            T(
                "Sent through $via. The keys stay locked until ${b.name} confirms.", Modifier.padding(top = 8.dp).fillMaxWidth(),
                size = 0.74f, weight = FontWeight.Bold, color = if (wait.via == Source.THINGSPEAK) c.ts else c.cloud, align = TextAlign.Center,
            )
        }
    }
}

/** Corner radius of a stacked key, for the lines drawn round it. */
private val KEY_RADIUS = 13.dp

/**
 * A key held down while a cloud command waits for the bin: pushed in under
 * a light veil, its icon turning, and a line round its edge running down to
 * the deadline, moved every frame.
 */
@Composable
private fun WaitKey(p: smartbin.data.Pending, label: String, cc: Color, modifier: Modifier) {
    val c = Sb.c
    val clock = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(p) {
        while (true) withFrameMillis { clock.longValue = System.currentTimeMillis() }
    }
    Btn(
        label, {}, modifier.drawWithContent {
            drawContent()
            val left = ((p.deadline - clock.longValue).toFloat() / (p.deadline - p.at).coerceAtLeast(1)).coerceIn(0f, 1f)
            drawRoundRect(c.base.soft(0.35f), cornerRadius = CornerRadius(KEY_RADIUS.toPx()))
            edgeLine(1f, cc.soft(0.22f))
            edgeLine(left, cc)
        },
        Ic.reload, Look.TINT, cc, stack = true, pressedIn = true, iconMod = Modifier.spin(true), sound = Sfx.DENIED,
    )
}

/** The part [part] (0 to 1) of a line round a key's edge, from its top-left corner. */
private fun DrawScope.edgeLine(part: Float, color: Color) {
    if (part <= 0f) return
    val w = 3.dp.toPx()
    val r = (KEY_RADIUS.toPx() - w / 2f).coerceAtLeast(0f)
    val whole = Path().apply { addRoundRect(RoundRect(w / 2f, w / 2f, size.width - w / 2f, size.height - w / 2f, CornerRadius(r, r))) }
    val measure = PathMeasure().apply { setPath(whole, false) }
    val piece = Path()
    measure.getSegment(0f, measure.length * part, piece, true)
    drawPath(piece, color, style = Stroke(width = w, cap = StrokeCap.Round))
}

/** Runs from 1 to 0 over [SETTLE_MILLIS] each time a new answer arrives. */
@Composable
private fun settleFade(s: Settled?): State<Float> {
    val fade = remember(s) { Animatable(if (s == null) 0f else 1f) }
    LaunchedEffect(s) {
        if (s != null) fade.animateTo(0f, tween(SETTLE_MILLIS.toInt(), easing = LinearEasing))
    }
    return fade.asState()
}

/** A green or red wash and edge over a key, fading out as [fade] runs down. */
private fun Modifier.settleFlash(ok: Boolean, fade: State<Float>, c: SbColors): Modifier = drawWithContent {
    drawContent()
    val f = fade.value
    if (f <= 0f) return@drawWithContent
    val tone = if (ok) c.ok else c.bad
    drawRoundRect(tone.soft(0.28f * f), cornerRadius = CornerRadius(KEY_RADIUS.toPx()))
    edgeLine(1f, tone.soft(f))
}

/** .ticks: 0, 50, the limit and 100 under the bar, each centred on its mark. */
@Composable
private fun Ticks(at: List<Double>) {
    val c = Sb.c
    BoxWithConstraints(Modifier.fillMaxWidth().height(18.dp)) {
        val w = maxWidth
        at.forEachIndexed { i, v ->
            val label = Math.round(v).toString()
            val x = w * (v / 100).toFloat()
            val align = when (i) { 0 -> 0f; at.lastIndex -> 1f; else -> 0.5f }
            Box(Modifier.offset { IntOffset((x.toPx() - 30.dp.toPx() * align * 2).toInt(), 0) }.width(60.dp)) {
                T(
                    label, Modifier.fillMaxWidth(), size = 0.76f, weight = FontWeight.Bold, color = c.ink2, maxLines = 1,
                    align = when (i) { 0 -> TextAlign.Start; at.lastIndex -> TextAlign.End; else -> TextAlign.Center },
                )
            }
        }
    }
}

@Composable
private fun SensorTile(icon: ImageVector, cc: Color, value: String, label: String, alarm: Boolean, level: Int, modifier: Modifier) {
    val c = Sb.c
    val surface = if (alarm) Modifier.brutal(c, 10.dp, c.bad, off = 3.dp) else Modifier.well(c, 13.dp)
    Column(
        modifier.then(surface).padding(top = 10.dp, bottom = 8.dp, start = 2.dp, end = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (alarm) Box(Modifier.size(21.dp).breathe(true, 2600).clip(RoundedCornerShape(50)).background(Color.White.soft(0.38f)))
            Ico(icon, 21.dp, if (alarm) Color.White else cc)
        }
        T(value, size = 1.04f, color = if (alarm) Color.White else lvColor(level, c), tnum = true, maxLines = 1, lineHeight = 1.05f, fit = true)
        T(label, size = 0.66f, weight = FontWeight.Bold, color = if (alarm) Color.White else c.ink2, fit = true)
    }
}

// ---------- collect next ----------

/** Time to full from how fast the bin has been filling. Returns the text and how near it is: 0 near, 1 mid, 2 far, 3 none. */
fun etaOf(b: Bin): Pair<String, Int> {
    val left = b.cal.v("fill_th") - b.reading.fill
    val rate = if (b.reading.status == BinStatus.COLLECTING) 0.0 else Hub.ratePerHour(b)
    if (left <= 0) return "Full now" to 0
    if (rate <= 0.05) return "Not filling" to 3
    val h = left / rate
    val t = if (h < 1) "~${Math.round(h * 60)} min" else "~${"%.1f".format(h)} h"
    return "$t to full" to when { h < 2 -> 0; h < 8 -> 1; else -> 2 }
}

@Composable
private fun CollectNext(vm: AppViewModel, bins: List<Bin>) {
    val c = Sb.c
    val rows = bins.sortedByDescending { it.reading.fill }
    val show = if (vm.prioAll) rows else rows.take(3)
    Card {
        Sh(Ic.rank, "Collect next", c.fill)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            show.forEachIndexed { k, b ->
                val p = Math.round(b.reading.fill)
                val th = b.cal.v("fill_th")
                val (tag, tagColor) = when {
                    p >= th -> "URGENT" to c.bad
                    p >= 50 -> "SOON" to c.warn
                    else -> "NORMAL" to c.ok
                }
                val (eta, near) = etaOf(b)
                val edge = when (near) { 0 -> c.bad; 1 -> c.warn; 2 -> c.ok; else -> c.ink2 }
                val src = remember { MutableInteractionSource() }
                val pressed by src.collectIsPressedAsState()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (pressed) Modifier.clip(RoundedCornerShape(14.dp)).background(c.base).sunk(c, 14.dp) else Modifier.bump(c, 14.dp))
                        .drawBehind { drawRect(edge, size = Size(5.dp.toPx(), size.height)) }
                        .press(true, Sfx.OPEN, src) { vm.overlay = Overlay.BinDetail(b.id) }
                        .padding(start = 16.dp, end = 13.dp, top = 11.dp, bottom = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    Box(Modifier.size(28.dp).well(c, 14.dp), contentAlignment = Alignment.Center) { T("${k + 1}", size = 0.9f) }
                    Column(Modifier.weight(1f)) {
                        T(b.name, weight = FontWeight.Bold, maxLines = 1)
                        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Ico(Ic.stopwatch, 12.dp, c.ink2)
                            T(eta, size = 0.76f, weight = FontWeight.SemiBold, color = c.ink2, maxLines = 1)
                        }
                    }
                    T("$p%", tnum = true)
                    Tag(tag, tagColor)
                }
            }
        }
        if (rows.size > 3) Btn(
            if (vm.prioAll) "Show top 3" else "Show all ${rows.size} bins", { vm.prioAll = !vm.prioAll },
            Modifier.fillMaxWidth(), if (vm.prioAll) Ic.chev else Ic.rank, small = true,
        )
        Hint("Fullest bin first. Time to full comes from how fast each bin filled over the last hour. Tap a row to open that bin.")
    }
}

// ---------- many bins: the grid ----------

@Composable
private fun TileGrid(bins: List<Bin>, tile: @Composable (Bin) -> Unit) {
    val cols = 3
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        bins.chunked(cols).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { b -> Box(Modifier.weight(1f)) { tile(b) } }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Tile(b: Bin, onClick: () -> Unit) {
    val c = Sb.c
    val r = b.reading
    val calOn = b.calibrating
    val simOn = b.simulating
    val lc = levelColor(b, c)
    val stale = b.stale
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val ringW = if (b.urgent) {
        val t = rememberInfiniteTransition(label = "thaze")
        t.animateFloat(2.5f, 5f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "thaze").value
    } else 0f
    val fill: Brush = when {
        calOn && simOn -> Brush.linearGradient(
            0f to mix(c.sim, 0.22f, c.base), 0.5f to mix(c.sim, 0.22f, c.base), 0.5f to mix(c.cal, 0.22f, c.base), 1f to mix(c.cal, 0.22f, c.base),
        )
        calOn -> Brush.linearGradient(listOf(mix(c.cal, 0.18f, c.base), mix(c.cal, 0.18f, c.base)))
        simOn -> Brush.linearGradient(listOf(mix(c.sim, 0.18f, c.base), mix(c.sim, 0.18f, c.base)))
        else -> Brush.linearGradient(listOf(c.base, c.base))
    }
    Box(Modifier.fillMaxWidth().alpha(if (stale) 0.5f else 1f)) {
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (pressed) Modifier else Modifier.raised(c, 16.dp, ring = if (b.urgent) c.bad.soft(1f - (ringW - 2.5f) / 2.5f * 0.55f) else null, ringWidth = ringW.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(fill)
                .then(if (pressed) Modifier.sunk(c, 16.dp) else Modifier)
                .press(true, Sfx.OPEN, src, onClick)
                .padding(start = 6.dp, end = 6.dp, top = 10.dp, bottom = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            BinIcon(r.fill, lc, 42.dp)
            T(b.name, size = 0.84f, maxLines = 1)
            T("${Math.round(r.fill)}%", size = 1.22f, color = lc, tnum = true, lineHeight = 1.05f)
            Row(Modifier.height(15.dp), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
                if (r.status == BinStatus.TAMPER) Ico(Ic.tamper, 15.dp, c.tamper, Modifier.shake())
                if (r.status == BinStatus.LOCKED) Ico(Ic.lock(true), 15.dp, c.sim)
                if (r.status == BinStatus.COLLECTING) Ico(Ic.truck, 15.dp, c.cal)
                if (simOn) Ico(Ic.flask, 15.dp, c.sim)
                if (calOn) Ico(Ic.calib, 15.dp, c.cal)
                if (r.lidOpen && r.status != BinStatus.COLLECTING) Ico(Ic.lid(true), 15.dp, c.lid)
                if (stale) Ico(Ic.wifi(false), 15.dp, c.tamper)
            }
        }
        if (b.sensorOver) Adot(Modifier.align(Alignment.TopEnd).padding(6.dp))
    }
}

/** .adot: the brutal "!" for a sensor over its limit. */
@Composable
fun Adot(modifier: Modifier = Modifier, small: Boolean = false) {
    val c = Sb.c
    Box(modifier.size(if (small) 16.dp else 19.dp).brutal(c, 5.dp, c.bad, off = 2.dp), contentAlignment = Alignment.Center) {
        T("!", size = (if (small) 10f else 11f) / 14f, weight = FontWeight.Black, color = Color.White, lineHeight = 1f)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TileLegend() {
    val c = Sb.c
    @Composable
    fun key(fill: Brush, text: String, ring: Color? = null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.size(14.dp).then(if (ring != null) Modifier.ring(ring, 4.dp) else Modifier).clip(RoundedCornerShape(4.dp)).background(fill))
            T(text, size = 0.72f, weight = FontWeight.Bold, color = c.ink2)
        }
    }
    val solid = { col: Color -> Brush.linearGradient(listOf(col, col)) }
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(13.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        key(solid(mix(c.sim, 0.3f, c.base)), "Simulation")
        key(solid(mix(c.cal, 0.3f, c.base)), "Calibration")
        key(
            Brush.linearGradient(0f to mix(c.sim, 0.34f, c.base), 0.5f to mix(c.sim, 0.34f, c.base), 0.5f to mix(c.cal, 0.34f, c.base), 1f to mix(c.cal, 0.34f, c.base)),
            "Both",
        )
        key(solid(c.base), "Full or tamper", ring = c.bad)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Adot(small = true)
            T("Sensor over limit", size = 0.72f, weight = FontWeight.Bold, color = c.ink2)
        }
    }
}
