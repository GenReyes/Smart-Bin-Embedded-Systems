package smartbin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import smartbin.data.AlertKind
import smartbin.data.AlertRow
import smartbin.data.Bin
import smartbin.data.DEFAULT_SIM
import smartbin.data.Hub
import smartbin.data.HubState
import smartbin.data.Severity
import smartbin.data.Source
import smartbin.notify.Sfx

// ---------- simulation ----------

private enum class SimKind { RANGE, NUM, PAIR, TOGGLE, LID }

private class SimRowDef(val key: String, val bit: Int, val label: String, val kind: SimKind, val min: Float = 0f, val max: Float = 100f, val step: Float = 1f, val unit: String = "")

private val SENSORS = listOf(
    SimRowDef("fill", 0, "Fill level", SimKind.RANGE, 0f, 100f, 1f, "%"),
    SimRowDef("weight", 1, "Weight", SimKind.NUM),
    SimRowDef("gas", 2, "Air quality", SimKind.RANGE, 0f, 500f, 5f, "IAQ"),
    SimRowDef("temp", 3, "Temperature and humidity", SimKind.PAIR),
    SimRowDef("tamper", 4, "Tamper", SimKind.TOGGLE),
    SimRowDef("motion", 5, "Motion", SimKind.TOGGLE),
    SimRowDef("lid", 6, "Lid", SimKind.LID),
)

@Composable
fun Simulation(vm: AppViewModel, hub: HubState) {
    val c = Sb.c
    val b = vm.binFor(vm.selSim, hub.bins)
    Box(Modifier.fillMaxSize()) {
        ScrollPage(bottomExtra = 80.dp) {
            Sh(Ic.flask, "Simulation", c.sim)
            if (b == null) {
                Card { Empty(Ic.bins, "No bins yet.") }
                return@ScrollPage
            }
            BinSeg(vm, hub, PickFor.SIM, vm.selSim, allowAll = false) { vm.selSim = it }
            Hint("AUTO reads the real hardware and lets the firmware drive the outputs. MANUAL holds the value you set, one row at a time.")
            if (b.source == Source.CLOUD || b.source == Source.THINGSPEAK) Row(
                Modifier.fillMaxWidth().well(c, 16.dp).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(42.dp).bump(c, 21.dp), contentAlignment = Alignment.Center) { Ico(Ic.cloud, 21.dp, c.cloud) }
                Column(Modifier.weight(1f)) {
                    T("Simulation needs the bins’ Wi-Fi", size = 0.98f)
                    T("The app is on cloud now, so APPLY rests until a bin answers on Wi-Fi again.", size = 0.8f, weight = FontWeight.Medium, color = c.ink2)
                }
            }
            SENSORS.forEach { SimRow(vm, b, it) }
            Hint("The whole app turns violet while any sensor is manual.", Modifier.fillMaxWidth(), align = TextAlign.Center)
        }
        if (b != null) Row(
            Modifier.align(Alignment.BottomCenter).padding(horizontal = 14.dp, vertical = 8.dp).widthIn(max = 520.dp).fillMaxWidth()
                .bump(c, 16.dp, Out.O2).padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Btn("APPLY", { vm.applySim(b) }, Modifier.weight(1f), Ic.flask, Look.ACCENT, enabled = b.source != Source.CLOUD && b.source != Source.THINGSPEAK, sound = Sfx.CONFIRM)
            Btn("ALL TO AUTO", { vm.simAllAuto(b) }, Modifier.weight(1f), Ic.reload, Look.TINT, c.ok, enabled = b.sim.mask > 0, sound = Sfx.SWITCH_OFF)
        }
    }
}

@Composable
private fun SimRow(vm: AppViewModel, b: Bin, s: SimRowDef) {
    val c = Sb.c
    val cc = when (s.key) {
        "fill" -> c.fill; "weight" -> c.weight; "gas" -> c.gas; "temp" -> c.temp; "tamper" -> c.tamper; "motion" -> c.motion; else -> c.lid
    }
    val values = b.sim.values
    fun value(k: String) = values[k] ?: DEFAULT_SIM[k] ?: 0.0
    val manual = b.sim.on(s.bit)
    val icon: ImageVector = when (s.key) {
        "fill" -> Ic.fill; "weight" -> Ic.weight(value("weight") / 4500); "gas" -> Ic.gas; "temp" -> Ic.temp(value("temp"))
        "tamper" -> Ic.tamper; "motion" -> Ic.motion(value("motion") > 0); else -> Ic.lid(value("lid") > 0)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .bump(c, 16.dp)
            .drawBehind { if (manual) drawRect(cc, size = Size(4.dp.toPx(), size.height)) }
            .padding(horizontal = 13.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Ico(icon, 21.dp, cc)
            T(s.label, Modifier.weight(1f), weight = FontWeight.Bold)
            Btn(
                if (manual) "MANUAL" else "AUTO", { vm.simToggle(b, s.bit) }, Modifier.widthIn(min = 104.dp),
                if (manual) Ic.flask else Ic.check, if (manual) Look.SOLID else Look.TINT, if (manual) c.sim else c.ok,
                small = true, sound = if (manual) Sfx.SWITCH_OFF else Sfx.SWITCH_ON,
            )
        }
        val on = manual
        Box(Modifier.alpha(if (on) 1f else 0.45f)) {
            when (s.kind) {
                SimKind.RANGE -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Slider(
                        value = value(s.key).toFloat().coerceIn(s.min, s.max),
                        onValueChange = { vm.simValue(b, s.key, (Math.round(it / s.step) * s.step).toDouble()) },
                        valueRange = s.min..s.max,
                        enabled = on,
                        modifier = Modifier.weight(1f).height(28.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = c.sim, activeTrackColor = c.sim, inactiveTrackColor = c.well,
                            disabledThumbColor = c.ink2, disabledActiveTrackColor = c.ink2, disabledInactiveTrackColor = c.well,
                        ),
                    )
                    Row(Modifier.defaultMinSize(minWidth = 66.dp).well(c, 11.dp).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
                        T(Math.round(value(s.key)).toString(), tnum = true)
                        T(" ${s.unit}", size = 0.7f, color = c.ink2)
                    }
                }
                SimKind.NUM -> SimStepper(vm, b, "weight", value("weight"), on)
                SimKind.PAIR -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SimStepper(vm, b, "temp", value("temp"), on)
                    SimStepper(vm, b, "humidity", value("humidity"), on)
                }
                SimKind.TOGGLE -> TwoWay(full = true) {
                    Tw(value(s.key) <= 0, { vm.simValue(b, s.key, 0.0) }, "OFF", Ic.cross, cc, enabled = on)
                    Tw(value(s.key) > 0, { vm.simValue(b, s.key, 1.0) }, "TRIGGERED", if (s.key == "motion") Ic.motion(true) else Ic.tamper, cc, enabled = on)
                }
                SimKind.LID -> TwoWay(full = true) {
                    Tw(value("lid") <= 0, { vm.simValue(b, "lid", 0.0) }, "CLOSED", Ic.lock(true), c.lid, enabled = on)
                    Tw(value("lid") > 0, { vm.simValue(b, "lid", 1.0) }, "OPEN", Ic.lid(true), c.lid, enabled = on)
                }
            }
        }
    }
}

@Composable
private fun SimStepper(vm: AppViewModel, b: Bin, key: String, v: Double, on: Boolean) {
    val f = SIM_STEP.getValue(key)
    Stepper(fmt(v, if (key == "temp") 1 else 0), f.unit, { vm.simStep(b, key, it) }, enabled = on, cc = Sb.c.sim)
}

// ---------- alerts ----------

private val KIND_ICONS: List<Pair<AlertKind, ImageVector>>
    get() = listOf(
        AlertKind.ALL to Ic.layers, AlertKind.FULL to Ic.fill, AlertKind.SENSOR to Ic.temp(30.0), AlertKind.TAMPER to Ic.tamper,
        AlertKind.LOCK to Ic.lock(true), AlertKind.PICKUP to Ic.truck, AlertKind.SETUP to Ic.calib,
    )

@Composable
fun AlertsPage(vm: AppViewModel, hub: HubState) {
    val c = Sb.c
    val every = hub.alerts
    val unread = hub.unread
    val filtered = vm.shown(every)
    val unShown = filtered.count { !it.read }
    val list = filtered.take(vm.alertShown)
    ScrollPage {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Sh(Ic.clock, "Alert history", c.tamper, Modifier.weight(1f))
            if (unread > 0) Box(Modifier.clip(RoundedCornerShape(5.dp)).background(c.tamper).padding(horizontal = 8.dp, vertical = 4.dp)) {
                T("$unread UNREAD", size = 0.68f, spacing = 0.05f, color = Color.White)
            }
        }
        TwoWay(full = true) {
            KIND_ICONS.forEach { (k, ic) ->
                Tw(vm.akind == k, { vm.akind = k; vm.alertShown = 6 }, icon = ic, cc = if (k == AlertKind.ALL) c.ink else c.tamper, minHeight = 40.dp)
            }
        }
        TwoWay(full = true) {
            AlertRange.entries.forEach { r -> Tw(vm.arange == r, { vm.arange = r; vm.alertShown = 6 }, r.label, cc = c.tamper, minHeight = 36.dp) }
        }
        Column {
            T(if (filtered.isNotEmpty()) "Showing ${vm.scope(filtered.size)}" else "No alerts match.", size = 0.82f)
            T(
                if (filtered.isNotEmpty()) vm.akind.label + if (unShown > 0) ", $unShown unread" else ", all read" else "Change the kind or the dates.",
                Modifier.padding(top = 2.dp), size = 0.82f, weight = FontWeight.SemiBold, color = c.ink2,
            )
        }
        Acts {
            Btn("MARK READ", { vm.markShown(every) }, Modifier.weight(1f), Ic.check, Look.TINT, c.ok, small = true, enabled = unShown > 0, sound = Sfx.CONFIRM)
            Btn("DELETE", { vm.overlay = Overlay.ClearAlerts }, Modifier.weight(1f), Ic.trash, Look.TINT, c.tamper, small = true, enabled = filtered.isNotEmpty(), sound = Sfx.OPEN)
        }
        if (list.isEmpty()) {
            Card { Empty(Ic.bell, if (every.isNotEmpty()) "No alerts match. Change the kind or the dates." else "No alerts yet. This fills up as bins reach their limits.") }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(11.dp)) { list.forEach { AlertLine(it) } }
        }
        if (filtered.size > list.size) Btn("Show older (${filtered.size - list.size} more)", { vm.alertShown += 6 }, Modifier.fillMaxWidth(), Ic.clock)
        if (list.isNotEmpty()) Hint("MARK READ and DELETE act on exactly the alerts shown. Tap the dot to mark one read or unread. The cross removes one alert.", Modifier.fillMaxWidth(), align = TextAlign.Center)
    }
}

@Composable
private fun AlertLine(a: AlertRow) {
    val c = Sb.c
    val cc = sevColor(a.sev, c)
    val critical = a.sev == Severity.CRITICAL
    val surface = if (critical) Modifier.brutal(c, 8.dp, c.bad)
    else Modifier.bump(c, 14.dp).drawBehind { drawRect(cc, size = Size(5.dp.toPx(), size.height)) }
    val fg = if (critical) Color.White else c.ink
    val sub = if (critical) Color.White.soft(0.88f) else c.ink2
    Row(
        Modifier.fillMaxWidth().alpha(if (a.read) 0.66f else 1f).then(surface).padding(start = 8.dp, end = 10.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // The dot: full while unread, a ring once read. A tap flips it.
        Box(
            Modifier.size(30.dp, 44.dp).press(true, Sfx.CHOICE) { Hub.markRead(setOf(a.id), !a.read) },
            contentAlignment = Alignment.Center,
        ) {
            val dot = if (critical) Color.White else cc
            if (a.read) Box(Modifier.size(11.dp).alpha(0.5f).ring(dot, 5.5.dp, 2.dp).padding(2.dp))
            else Box(Modifier.size(11.dp).ring(dot.soft(0.24f), 5.5.dp, 3.dp).clip(CircleShape).background(dot))
        }
        Box(
            Modifier.size(38.dp).then(if (critical) Modifier.clip(RoundedCornerShape(11.dp)).background(Color.White.soft(0.17f)) else Modifier.well(c, 11.dp)),
            contentAlignment = Alignment.Center,
        ) { Ico(Ic.named(a.icon), 20.dp, if (critical) Color.White else cc) }
        Column(Modifier.weight(1f)) {
            T(a.title, size = 0.95f, weight = if (a.read) FontWeight.Bold else FontWeight.ExtraBold, color = fg)
            T("${a.meta} · ${ago(a.at)}", Modifier.padding(top = 2.dp), size = 0.78f, weight = FontWeight.Medium, color = sub)
        }
        Box(
            Modifier.size(34.dp).then(if (critical) Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White.soft(0.18f)) else Modifier.bump(c, 10.dp))
                .press(true, Sfx.DANGER) { Hub.deleteAlerts(setOf(a.id)) },
            contentAlignment = Alignment.Center,
        ) { Ico(Ic.cross, 15.dp, if (critical) Color.White else c.ink2) }
    }
}
