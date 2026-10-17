package smartbin.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import smartbin.data.Bin
import smartbin.data.Cal
import smartbin.data.HubState
import smartbin.data.Link
import smartbin.data.Source
import smartbin.data.v
import smartbin.notify.Sfx

@Composable
fun SettingsPage(vm: AppViewModel, hub: HubState) {
    val c = Sb.c
    val b = vm.binFor(vm.selSet, hub.bins)
    val scroll = rememberScrollState()
    val calHere = b != null && hub.calibBin == b.id
    val on = vm.operator && calHere && vm.edit != null
    val showSave = on && vm.dirty
    Box(Modifier.fillMaxSize()) {
        ScrollPage(state = scroll, bottomExtra = if (showSave) 72.dp else 0.dp) {
            Sh(Ic.sliders, "Settings", c.cal)
            if (b == null) {
                Card { Empty(Ic.bins, "No bins yet.") }
            } else {
                // Calibration runs on one bin at a time, so the picker is held while it is on.
                BinSeg(vm, hub, PickFor.SET, vm.selSet, allowAll = false, locked = hub.calibBin != null) {
                    vm.selSet = it
                    vm.pinShown = false
                }
                // The Collect PIN sits at the top for both roles, as on the dashboard.
                PinCard(vm, b)
                if (vm.operator) CalBar(vm, b, on, hub) else ReadOnlyNote()
                val v: Cal = if (on) vm.edit!! else b.cal
                Sections(vm, b, v, on)
            }
            if (b != null) {
                Row(Modifier.padding(top = 4.dp)) { Sh(Ic.reload, "Bin tools") }
                LRow(Ic.reload, "Reload from bin", "Read the values ${b.name} is running on", onClick = { vm.reload(b) }, chevron = false, sound = Sfx.TAP)
            }
            // The app's own settings live behind the gear key in the header.
            LRow(Ic.gear, "App settings", "Sound, vibration, battery, links and demo options. Also the gear at the top right.", onClick = { vm.go(Screen.APP) })
        }
        if (showSave) {
            SaveBar(
                Modifier.align(Alignment.BottomCenter).padding(horizontal = 14.dp, vertical = 8.dp),
                onCancel = { vm.cancel(b) }, onSave = { vm.save(b) },
            )
        }
    }
}

@Composable
private fun SaveBar(modifier: Modifier, onCancel: () -> Unit, onSave: () -> Unit) {
    val c = Sb.c
    Row(
        modifier.widthIn(max = 520.dp).fillMaxWidth().bump(c, 16.dp, Out.O2, ring = c.accentSoft).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Ico(Ic.save, 18.dp, c.accent)
            T("Unsaved changes", size = 0.8f, color = c.accent, maxLines = 1)
        }
        Btn("CANCEL", onCancel, small = true, sound = Sfx.BACK)
        Btn("SAVE", onSave, icon = Ic.save, look = Look.ACCENT, small = true, sound = Sfx.CONFIRM)
    }
}

@Composable
private fun CalBar(vm: AppViewModel, b: Bin, on: Boolean, hub: HubState) {
    val c = Sb.c
    val cloud = b.source == Source.CLOUD
    val elsewhere = hub.calibBin != null && hub.calibBin != b.id
    Column(
        Modifier.fillMaxWidth().then(if (on) Modifier.ring(c.accentSoft, 16.dp) else Modifier).well(c, 16.dp).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).bump(c, 21.dp), contentAlignment = Alignment.Center) {
                Ico(if (on) Ic.calib else if (cloud) Ic.cloud else Ic.lock(true), 21.dp, if (on) c.accent else c.ink2, Modifier.spin(on))
            }
            Column(Modifier.weight(1f)) {
                T(if (on) "Calibration mode is on for ${b.name}" else "Settings are locked", size = 0.98f)
                T(
                    when {
                        on -> "Change what you need, then SAVE. Switching off without saving leaves the bin as it was."
                        cloud -> "Calibration needs live readings, so it only runs on the bins’ Wi-Fi. The app is on cloud now. Every value can still be read."
                        elsewhere -> "Calibration is on for another bin. Switch it off there first."
                        else -> "Switch on calibration mode to change anything. The bin shows its teal calibration screen while it is on."
                    },
                    Modifier.padding(top = 2.dp), size = 0.8f, weight = FontWeight.Medium, color = c.ink2,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().bump(c, 14.dp).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            T("Calibration mode", Modifier.weight(1f), size = 0.9f, weight = FontWeight.Bold, color = if (on) c.accent else c.ink)
            Toggle(on, { vm.setCalibration(it, b) }, enabled = on || (!cloud && !elsewhere && b.source != Source.STALE))
        }
    }
}

@Composable
private fun PinCard(vm: AppViewModel, b: Bin) {
    val c = Sb.c
    Column(
        Modifier.fillMaxWidth().well(c, 16.dp).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(44.dp).bump(c, 22.dp), contentAlignment = Alignment.Center) { Ico(Ic.pin, 21.dp, c.cal) }
            Column(Modifier.weight(1f)) {
                T("Collect PIN for ${b.name}", size = 0.98f)
                T(
                    if (vm.operator) "Collectors type this on the bin for COLLECT and WI-FI SETUP. Change it under Lid and lock."
                    else "Type this on the bin touchscreen when you tap COLLECT.",
                    size = 0.8f, weight = FontWeight.Medium, color = c.ink2,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            T(
                if (vm.pinShown) "%04d".format(b.cal.v("collect_pin").toInt()) else "••••", Modifier.weight(1f).padding(start = 4.dp),
                size = 2f, color = c.cal, tnum = true, spacing = if (vm.pinShown) 0.21f else 0.14f, lineHeight = 1.1f,
            )
            Btn(if (vm.pinShown) "HIDE" else "SHOW", { vm.pinShown = !vm.pinShown }, icon = if (vm.pinShown) Ic.eyeoff else Ic.eye, look = Look.TINT, cc = c.cal, small = true, sound = Sfx.CHOICE)
        }
    }
}

@Composable
private fun ReadOnlyNote() {
    val c = Sb.c
    Row(
        Modifier.fillMaxWidth().well(c, 16.dp).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(42.dp).bump(c, 21.dp), contentAlignment = Alignment.Center) { Ico(Ic.eye, 21.dp, c.ink2) }
        Column(Modifier.weight(1f)) {
            T("These settings are read only", size = 0.98f)
            T("You can see every value, but only the operator can change them or run a calibration.", size = 0.8f, weight = FontWeight.Medium, color = c.ink2)
        }
    }
}

/** .sect: a fold-out group of settings with its summary on the right. */
@Composable
private fun Sect(vm: AppViewModel, key: String, title: String, summary: String, cc: Color, on: Boolean, body: @Composable () -> Unit) {
    val c = Sb.c
    val open = key in vm.open
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "chev")
    Column(Modifier.fillMaxWidth().bump(c, 16.dp)) {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 54.dp).press(true, if (open) Sfx.BACK else Sfx.OPEN) { vm.toggleSection(key) }.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(4.dp, 15.dp).clip(RoundedCornerShape(2.dp)).background(cc))
            T(title.uppercase(), size = 0.76f, spacing = 0.09f, color = cc, maxLines = 1)
            T(summary, Modifier.weight(1f), size = 0.76f, weight = FontWeight.Bold, color = c.ink2, maxLines = 1, align = TextAlign.End)
            Ico(Ic.chev, 18.dp, c.ink2, Modifier.graphicsLayer { rotationZ = turn })
        }
        AnimatedVisibility(open) {
            Column(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 16.dp).alpha(if (on) 1f else 0.5f),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) { body() }
        }
    }
}

@Composable
private fun Fld(vm: AppViewModel, key: String, value: Double, on: Boolean, cc: Color, extra: (@Composable () -> Unit)? = null) {
    val f = FIELDS.getValue(key)
    Field("${f.label} (${f.unit})", f.icon(), cc) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stepper(fmt(value, f.dec), f.unit, { vm.step(key, it) }, Modifier.weight(1f), enabled = on, cc = cc)
            extra?.invoke()
        }
    }
}

@Composable
private fun Sections(vm: AppViewModel, b: Bin, v: Cal, on: Boolean) {
    val c = Sb.c
    val r = b.reading
    Sect(vm, "range", "Fill range", "${fmt(v.v("d_empty"), 1)} to ${fmt(v.v("d_full"), 1)} cm", c.cal, on) {
        Fld(vm, "d_empty", v.v("d_empty"), on, c.cal) { Btn("USE NOW", { vm.useNow("d_empty", b) }, look = Look.TINT, cc = c.cal, small = true, enabled = on, sound = Sfx.CONFIRM) }
        Fld(vm, "d_full", v.v("d_full"), on, c.cal) { Btn("USE NOW", { vm.useNow("d_full", b) }, look = Look.TINT, cc = c.cal, small = true, enabled = on, sound = Sfx.CONFIRM) }
        Hint("Empty the bin and press USE NOW for the first one. Hold a board at your full line and press it for the second. Sensor reads **${fmt(r.distance, 1)} cm** right now.")
    }
    Sect(
        vm, "limits", "Alert limits",
        "${fmt(v.v("fill_th"), 0)}% · ${fmt(v.v("temp_th"), 0)}°C · ${fmt(v.v("hum_th"), 0)}%RH · ${fmt(v.v("gas_th"), 0)} IAQ", c.bad, on,
    ) {
        listOf("fill_th", "temp_th", "hum_th", "gas_th", "tilt_th", "weight_cap").forEach { Fld(vm, it, v.v(it), on, c.bad) }
        Hint("A reading at or above its threshold turns its tile red, raises an alert and sounds the bin buzzer. Humidity uses 85 %RH by default, the same limit as the bin touchscreen.")
    }
    Sect(vm, "air", "Air sensor (BME680)", "offset ${fmt(v.v("temp_off"), 1)}°C", c.gas, on) {
        Field("Temperature raw / corrected", Ic.temp(r.temp), c.gas) { Readout("${fmt(r.tempRaw, 1)} / ${fmt(r.tempRaw + v.v("temp_off"), 1)} °C") }
        Fld(vm, "tref", vm.tref, on, c.gas) { Btn("SET OFFSET", { vm.setOffset(b) }, look = Look.TINT, cc = c.gas, small = true, enabled = on, sound = Sfx.CONFIRM) }
        Fld(vm, "temp_off", v.v("temp_off"), on, c.gas)
        val acc = listOf("Stabilizing", "Low accuracy", "Medium accuracy", "Calibrated")[r.iaqAcc.coerceIn(0, 3)]
        Field("Gas sensor state", Ic.gas, c.gas) {
            Readout(
                when {
                    r.fault("BME680") -> "Not answering"
                    r.warming -> "Warming up" + (r.iaqWarmS?.let { " (${it / 60}:%02d)".format(it % 60) } ?: "") + (r.gasK?.let { ", gas ${Math.round(it)}kΩ" } ?: "")
                    else -> "$acc, IAQ ${Math.round(r.gas)}"
                },
            )
        }
        Fld(vm, "hum_off", v.v("hum_off"), on, c.gas)
        Fld(vm, "press_off", v.v("press_off"), on, c.gas)
        Btn(
            "RESET GAS BASELINE", { vm.binCommand(b, "RESET_GAS", emptyMap(), "Gas baseline cleared. It needs time in normal air to be accurate again.") },
            Modifier.fillMaxWidth(), Ic.reload, Look.TINT, c.gas, enabled = on, sound = Sfx.CONFIRM,
        )
        Hint("The BME680 warms itself, so it reads a little hot and its humidity a little low. Put a reference thermometer next to the bin for a few minutes, enter its reading, press SET OFFSET, then SAVE. The gas reading learns its own baseline in normal air, and RESET GAS BASELINE restarts that.")
    }
    Sect(vm, "lid", "Lid and lock", "PIN •••• · ${fmt(v.v("pir_hold_s"), 0)} s", c.sim, on) {
        Fld(vm, "servo_closed", v.v("servo_closed"), on, c.sim)
        Fld(vm, "servo_open", v.v("servo_open"), on, c.sim)
        Field("Collect PIN", Ic.pin, c.sim) {
            Inp(
                if (vm.operator) v.v("collect_pin").toInt().takeIf { it > 0 }?.toString().orEmpty() else "••••", vm::setPin,
                keyboard = KeyboardType.NumberPassword, enabled = on,
            )
        }
        Fld(vm, "pir_hold_s", v.v("pir_hold_s"), on, c.sim)
        Fld(vm, "buzz_s", v.v("buzz_s"), on, c.sim)
        Row(
            Modifier.fillMaxWidth().bump(c, 14.dp).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            T("Lock the lid when the bin is full", Modifier.weight(1f), size = 0.9f, weight = FontWeight.Bold)
            Toggle(v.v("auto_lock") > 0, { vm.toggleAutoLock() }, enabled = on)
        }
    }
    Sect(vm, "weight", "Weight", "%.2f kg now".format(r.weight / 1000), c.weight, on) {
        Field("Reading now", Ic.weight(r.weight / v.v("weight_cap")), c.weight) { Readout("%.2f kg".format(r.weight / 1000)) }
        Fld(vm, "known", vm.known, on, c.weight)
        Acts {
            Btn("TARE / ZERO", { vm.binCommand(b, "TARE", emptyMap(), "Scale zeroed on ${b.name}.") }, Modifier.weight(1f), Ic.undo, Look.TINT, c.weight, enabled = on, sound = Sfx.CONFIRM)
            Btn(
                "CALIBRATE", { vm.binCommand(b, "CAL_WEIGHT", mapOf("grams" to vm.known), "Calibrated with ${fmt(vm.known, 0)} g.") },
                Modifier.weight(1f), Ic.capture, Look.TINT, c.weight, enabled = on, sound = Sfx.CONFIRM,
            )
        }
        Hint("Tare with the bin empty. Then put the known weight in and press CALIBRATE.")
    }
    if (vm.operator) Sect(
        vm, "touch", "Touchscreen",
        when (b.touchCustom) { true -> "calibrated"; false -> "default values"; null -> "not reported" }, c.accent, true,
    ) {
        Acts {
            Btn("CALIBRATE TOUCH", { vm.touchCal(b, false) }, Modifier.weight(1f), Ic.capture, Look.TINT, c.accent, sound = Sfx.TAP)
            Btn("RESET", { vm.touchCal(b, true) }, Modifier.weight(1f), Ic.undo, Look.TINT, c.accent, sound = Sfx.TAP)
        }
        Hint("If taps on the bin land in the wrong place, press CALIBRATE TOUCH and press the centre of each of the 4 crosses the bin shows. RESET goes back to the firmware's own values. Needs the bin on the same Wi-Fi as the phone.")
    }
}

// ---------- connection ----------

@Composable
fun Connection(vm: AppViewModel, hub: HubState) {
    val c = Sb.c
    val link = hub.link
    val (stateText, stateCc) = when (link) {
        Link.LIVE -> "Local Wi-Fi" to c.link; Link.CLOUD -> "Firebase" to c.cloud; Link.THINGSPEAK -> "ThingSpeak" to c.ts
        Link.OFFLINE -> "Not connected" to c.bad; Link.DEMO -> "Demo data" to c.warn
    }
    val r = hub.routes
    val demo = hub.demo
    ScrollPage {
        BackBar("Connection", { vm.back() }) {
            StatePill(when (link) { Link.CLOUD -> Ic.cloud; Link.THINGSPEAK -> Ic.chart; else -> Ic.wifi(link == Link.LIVE) }, stateText, stateCc, true)
        }
        Card {
            Sh(Ic.layers, "How the app reaches the bins", c.link)
            Route(1, Ic.wifi(true), c.link, link == Link.LIVE, if (demo) r.local else r.local.takeIf { hub.bins.any { it.ip.isNotBlank() } }, "Local Wi-Fi", "Always tried first. Readings every second and every control.")
            Route(2, Ic.cloud, c.cloud, link == Link.CLOUD, if (demo) r.firebase else hub.fbUp, "Firebase", "When no bin answers on Wi-Fi. Readings every 5 s. LOCK, UNLOCK and COLLECT only.")
            Route(3, Ic.chart, c.ts, link == Link.THINGSPEAK, if (demo) r.thingspeak else hub.tsUp, "ThingSpeak", "When Wi-Fi and Firebase do not answer. Readings every 15 s. LOCK, UNLOCK and COLLECT through TalkBack.")
            if (demo && r.any) Hint("These are pretend links from the demo options, to show the colours.")
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                hub.bins.take(4).forEach { b ->
                    val (cc, txt) = when (b.source) {
                        Source.DEMO -> c.warn to "demo data"
                        Source.STALE -> c.bad to "not answering"
                        Source.LIVE -> c.link to "answers on Wi-Fi"
                        Source.CLOUD -> c.cloud to "via Firebase"
                        Source.THINGSPEAK -> c.ts to "via ThingSpeak"
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Box(Modifier.size(10.dp).ring(cc.soft(0.22f), 5.dp, 3.dp).clip(RoundedCornerShape(5.dp)).background(cc))
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            T(b.name, size = 0.84f, weight = FontWeight.Bold, maxLines = 1)
                            T(b.ip.ifBlank { "no address" }, size = 0.84f, weight = FontWeight.SemiBold, color = c.ink2, maxLines = 1)
                        }
                        T(txt, size = 0.72f, weight = FontWeight.Bold, color = cc, maxLines = 1)
                    }
                    if (!b.isDemo && (b.fw.isNotBlank() || b.rssi != null)) {
                        val up = b.uptimeS?.let { u -> if (u >= 86_400) "up ${u / 86_400} d" else if (u >= 3600) "up ${u / 3600} h" else "up ${u / 60} min" }
                        T(
                            listOfNotNull(b.fw.takeIf { it.isNotBlank() }?.let { "firmware $it" }, b.rssi?.let { "signal $it dBm" }, up).joinToString(" · "),
                            Modifier.padding(start = 19.dp), size = 0.72f, weight = FontWeight.SemiBold, color = c.ink2, maxLines = 1,
                        )
                    }
                }
                if (hub.bins.size > 4) T("and ${hub.bins.size - 4} more", size = 0.84f, weight = FontWeight.SemiBold, color = c.ink2)
            }
            Hint("The app calls every bin address with a 1.5 s timeout when it opens, when the phone changes network, and every 30 s while it is on cloud, and goes back to Wi-Fi by itself. Each bin also writes its own address to the cloud, so a new address is picked up without typing it.")
        }
        Card {
            Sh(Ic.cloud, "Firebase account", c.cloud)
            Field("Database URL", Ic.cloud) { Inp(vm.fb.url, { vm.fb = vm.fb.copy(url = it.trim()) }, placeholder = "https://...firebasedatabase.app", keyboard = KeyboardType.Uri) }
            Field("Web API key", Ic.key) { Inp(vm.fb.key, { vm.fb = vm.fb.copy(key = it.trim()) }, placeholder = "AIza...") }
            Field("Email", Ic.user) { Inp(vm.fb.email, { vm.fb = vm.fb.copy(email = it.trim()) }, placeholder = "name@example.com", keyboard = KeyboardType.Email) }
            Field("Password", Ic.key) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Inp(vm.fb.password, { vm.fb = vm.fb.copy(password = it) }, Modifier.weight(1f), password = !vm.fbShow)
                    Btn(if (vm.fbShow) "HIDE" else "SHOW", { vm.fbShow = !vm.fbShow }, icon = if (vm.fbShow) Ic.eyeoff else Ic.eye, look = Look.TINT, small = true, sound = Sfx.CHOICE)
                }
            }
            vm.fbMsg?.let { (ok, msg) ->
                Row(
                    Modifier.fillMaxWidth().brutal(c, 12.dp, if (ok) c.ok else c.bad, off = 3.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Ico(if (ok) Ic.check else Ic.tamper, 18.dp, c.onBanner)
                    T(msg, size = 0.82f, weight = FontWeight.Bold, color = c.onBanner)
                }
            }
            Acts {
                Btn(if (vm.fbBusy) "TESTING" else "TEST", { vm.fbTest(false) }, Modifier.weight(1f), Ic.radar, Look.TINT, c.cloud, enabled = !vm.fbBusy)
                Btn("TEST AND SAVE", { vm.fbTest(true) }, Modifier.weight(1f), Ic.save, Look.SOLID, c.cloud, enabled = !vm.fbBusy)
            }
            Btn("RESTORE WORKING", { vm.fbRestore() }, Modifier.fillMaxWidth(), Ic.undo, Look.TINT)
            if (vm.operator) {
                Btn("SEND TO BINS", { vm.sendCloudToBins() }, Modifier.fillMaxWidth(), Ic.bins, Look.TINT, c.cloud, sound = Sfx.TAP)
                Hint("SEND TO BINS gives every bin on Wi-Fi this account, so they write to the same database. Save it with TEST AND SAVE first.")
            }
            Hint("TEST signs in and reads the list of bins. TEST AND SAVE keeps the details only when the test passes. RESTORE WORKING puts back the last details that passed. The password is stored encrypted on this phone.")
        }
        Card {
            Sh(Ic.chart, "ThingSpeak", c.ts)
            Field("Server", Ic.chart, c.ts) { Inp(vm.tsUrl, { vm.tsUrl = it.trim() }, placeholder = "https://api.thingspeak.com", keyboard = KeyboardType.Uri) }
            vm.tsMsg?.let { (ok, msg) ->
                Row(
                    Modifier.fillMaxWidth().brutal(c, 12.dp, if (ok) c.ok else c.bad, off = 3.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Ico(if (ok) Ic.check else Ic.tamper, 18.dp, c.onBanner)
                    T(msg, size = 0.82f, weight = FontWeight.Bold, color = c.onBanner)
                }
            }
            Btn(if (vm.tsBusy) "TESTING" else "TEST AND SAVE", { vm.tsTest() }, Modifier.fillMaxWidth(), Ic.radar, Look.SOLID, c.ts, enabled = !vm.tsBusy)
            // ThingSpeak has no call that reports the message count, so the app estimates it.
            val perDay = hub.tsPerDay
            val year = (perDay ?: 0.0) * 365
            val yearShare = (year / 3_000_000.0).coerceIn(0.0, 1.0)
            Box(
                Modifier.fillMaxWidth().height(14.dp).well(c, 7.dp).drawBehind {
                    if (yearShare > 0) drawRoundRect(
                        if (year > 3_000_000) c.bad else c.ts,
                        size = Size((size.width * yearShare).toFloat().coerceAtLeast(size.height), size.height), cornerRadius = CornerRadius(7.dp.toPx()),
                    )
                },
            )
            Hint(
                if (perDay == null) "No estimate yet. Once a channel has been read for two minutes, the app counts how fast its entries rise and works out the year. The free licence allows 3 million messages a year. The exact count is on your ThingSpeak account page."
                else "About **${"%,d".format(Math.round(perDay))}** messages a day, so about **${"%,d".format(Math.round(year))}** a year of the 3,000,000 the free licence allows" +
                    (if (year > 3_000_000) ". That is over the limit, so make the bins write less often." else ".") + " This is an estimate. The exact count is on your ThingSpeak account page.",
            )
            Hint(
                "Each bin needs its own channel. Put the channel ID and read API key in the bin's SET sheet, and its TalkBack ID and key for LOCK, UNLOCK and COLLECT. " +
                    "The write API key stays in the bin's firmware. Fields: **1** fill %, **2** weight g, **3** temperature, **4** humidity, **5** air quality, **6** pressure, **7** tilt, " +
                    "**8** motion + 2 × lid + 4 × status, and the status text holds the bin's IP address. The free licence allows a write every 15 s per channel and 3 million a year in all.",
            )
        }
        Card {
            Sh(Ic.download, "Data use this month", c.cloud)
            val bytes = hub.cloudBytes
            val limit = 10L * 1024 * 1024 * 1024
            val share = ((bytes ?: 0L).toDouble() / limit).coerceIn(0.0, 1.0)
            Box(
                Modifier.fillMaxWidth().height(14.dp).well(c, 7.dp).drawBehind {
                    if (share > 0) drawRoundRect(c.cloud, size = Size((size.width * share).toFloat().coerceAtLeast(size.height), size.height), cornerRadius = CornerRadius(7.dp.toPx()))
                },
            )
            Hint(
                if (bytes == null) "No figure yet. The bins report how much they sent once the app has used the cloud. The free Firebase plan allows 10 GB each month."
                else "**${bytes / (1024 * 1024)} MB** of the 10 GB the free Firebase plan allows each month. The bins write every 5 s while a phone watches over cloud and every minute otherwise, and at once when an alert starts."
            )
        }
    }
}

@Composable
private fun Route(n: Int, icon: ImageVector, cc: Color, on: Boolean, up: Boolean?, title: String, sub: String) {
    val c = Sb.c
    Row(
        Modifier.fillMaxWidth().then(if (on) Modifier.bump(c, 14.dp, ring = cc.soft(0.34f)) else Modifier.well(c, 14.dp)).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(Modifier.size(26.dp).bump(c, 13.dp), contentAlignment = Alignment.Center) { T("$n", size = 0.8f, color = cc) }
        Ico(icon, 21.dp, cc)
        Column(Modifier.weight(1f)) {
            T(title, size = 0.92f)
            T(sub, Modifier.padding(top = 2.dp), size = 0.77f, weight = FontWeight.SemiBold, color = c.ink2)
        }
        when {
            on -> Tag("IN USE", cc)
            up == true -> Tag("ANSWERS", cc.soft(0.85f))
            up == false -> T("NO ANSWER", size = 0.66f, spacing = 0.05f, color = c.bad, maxLines = 1)
            else -> T("NOT SET UP", size = 0.66f, spacing = 0.05f, color = c.ink2, maxLines = 1)
        }
    }
}

// ---------- Bluetooth setup ----------

@Composable
fun Bluetooth(vm: AppViewModel, hub: HubState) {
    val c = Sb.c
    val b = vm.binFor(vm.selSet, hub.bins)
    val paired = vm.bt in 1..3
    val name = vm.btName.ifBlank { b?.takeIf { it.isDemo }?.name ?: "the bin" }
    // Android asks for Nearby devices (or Location before Android 12) the first time.
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.btPair(b) }
    val pair = { if (b?.isDemo != true && !vm.btAllowed()) ask.launch(vm.btPermissions()) else vm.btPair(b) }
    ScrollPage {
        BackBar("Bluetooth setup", { vm.back() }) {
            StatePill(Ic.bluetooth, if (vm.bt >= 4) "Off" else if (paired) "Paired" else "Not paired", c.bt, paired)
        }
        Card {
            Hint("Hand the Wi-Fi name and password to a bin over Bluetooth, read back the address it gets, then switch its Bluetooth off so only Wi-Fi stays on.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Ic.bluetooth to "PAIR + CODE", Ic.radar to "PICK NETWORK", Ic.key to "SEND", Ic.power to "BT OFF").forEachIndexed { k, (ic, label) ->
                    val done = vm.bt > k
                    val now = vm.bt == k
                    val cc = when { done -> c.ok; now -> c.bt; else -> c.ink2 }
                    Column(
                        Modifier.weight(1f).then(if (now) Modifier.bump(c, 13.dp, ring = c.bt.soft(0.3f)) else Modifier.well(c, 13.dp)).padding(vertical = 11.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Ico(if (done) Ic.check else ic, 19.dp, cc)
                        T(label, size = 0.64f, color = cc, align = TextAlign.Center, lineHeight = 1.2f)
                    }
                }
            }
            vm.btNote?.let { note ->
                Row(
                    Modifier.fillMaxWidth().well(c, 12.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Ico(if (vm.btBusy || vm.bt == 2) Ic.reload else Ic.bluetooth, 18.dp, c.bt, Modifier.spin(vm.btBusy || vm.bt == 2))
                    T(note, Modifier.weight(1f), size = 0.82f, weight = FontWeight.SemiBold)
                }
            }
            val canEdit = vm.bt == 1
            Field("Wi-Fi name (2.4 GHz)", Ic.wifi(false)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Inp(vm.ssid, { vm.ssid = it }, Modifier.weight(1f), placeholder = "HomeWiFi-2G", enabled = canEdit)
                    Btn("SCAN", { vm.btScanNets() }, icon = Ic.radar, look = Look.TINT, cc = c.bt, small = true, enabled = canEdit && !vm.btBusy, sound = Sfx.OPEN)
                }
            }
            Field("Password", Ic.key) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Inp(vm.pass, { vm.pass = it }, Modifier.weight(1f), password = !vm.showPass, enabled = canEdit)
                    Btn(if (vm.showPass) "HIDE" else "SHOW", { vm.showPass = !vm.showPass }, icon = if (vm.showPass) Ic.eyeoff else Ic.eye, look = Look.TINT, small = true, sound = Sfx.CHOICE)
                }
            }
            if (vm.bt >= 3 && vm.btIp.isNotBlank()) {
                Column(Modifier.fillMaxWidth().well(c, 14.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Ico(Ic.wifi(true), 21.dp, c.ok)
                        Column(Modifier.weight(1f)) {
                            T("$name joined ${vm.btJoined}", size = 0.86f, weight = FontWeight.Bold)
                            T(vm.btIp, size = 1.3f, color = c.ok, tnum = true)
                        }
                    }
                    Btn(vm.btUseLabel(), { vm.btUse() }, Modifier.fillMaxWidth(), Ic.check, Look.SOLID, c.ok, small = true, sound = Sfx.TAP)
                }
            }
            if (vm.btStale) {
                Btn("FORGET AND PAIR AGAIN", { vm.btForget() }, Modifier.fillMaxWidth(), Ic.undo, Look.TINT, c.warn, sound = Sfx.TAP)
            }
            Btn(
                if (vm.btBusy && vm.bt == 0) "PAIRING" else "PAIR A BIN", { pair() }, Modifier.fillMaxWidth(), Ic.bluetooth, Look.TINT, c.bt,
                enabled = (vm.bt == 0 || vm.bt >= 4) && !vm.btBusy, sound = Sfx.OPEN,
            )
            Btn(
                "SEND WI-FI", { vm.btSend() }, Modifier.fillMaxWidth(), if (vm.bt == 2) Ic.reload else Ic.key, Look.SOLID, c.bt,
                enabled = vm.bt == 1, iconMod = Modifier.spin(vm.bt == 2), sound = Sfx.CONFIRM,
            )
            Btn("TURN BT OFF", { vm.btOff() }, Modifier.fillMaxWidth(), Ic.power, Look.TINT, c.warn, enabled = vm.bt in 1..3, sound = Sfx.TAP)
            Hint(
                when (vm.bt) {
                    0 -> "On the bin, tap i, then WI-FI SETUP, and type the Collect PIN. Then press PAIR A BIN, pick the bin, and type the 6-digit code its screen shows."
                    1 -> "Pick the 2.4 GHz network the bin should join, then SEND WI-FI."
                    2 -> "The bin tries the network for up to 15 s. If it fails it goes back to its old Wi-Fi."
                    3 -> "Press ${vm.btUseLabel()} to keep this address, then TURN BT OFF."
                    else -> "Bluetooth is off on the bin. It stays on Wi-Fi until someone opens WI-FI SETUP on it again."
                }
            )
            if (b?.isDemo == true) Hint("${b.name} is a demo bin, so pairing uses a stand-in code here. Pick a real bin, or none, to set up a real one.")
            Hint("The code keeps strangers from pairing and encrypts the link, so the Wi-Fi password cannot be overheard. It is new each time, lasts 60 s, and 3 wrong codes stop pairing for 60 s.")
        }
    }
}
