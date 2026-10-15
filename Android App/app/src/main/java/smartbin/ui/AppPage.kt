package smartbin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import smartbin.BuildConfig
import smartbin.data.AlertType
import smartbin.data.Hub
import smartbin.data.HubState
import smartbin.data.Link
import smartbin.data.Role
import smartbin.data.Source
import smartbin.notify.Alerts
import smartbin.notify.Feel
import smartbin.notify.Haptics
import smartbin.notify.Sfx

/**
 * The gear page: everything about the app itself, kept apart from the bins'
 * own settings on the Settings tab. Sound and vibration come first, then the
 * look, the links, running in the background, and the demo options.
 */
@Composable
fun AppPage(vm: AppViewModel, hub: HubState) {
    val c = Sb.c
    val context = LocalContext.current
    ScrollPage {
        BackBar("App settings", { vm.back() })

        // Sound and vibration, the two keys the gear is mostly opened for.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigSwitch(Ic.speaker, "Sound", vm.sounds, c.accent) { vm.toggleSounds() }
            BigSwitch(Ic.vibrate, "Vibration", vm.haptics, c.accent) { vm.toggleHaptics() }
        }
        Column(
            Modifier.fillMaxWidth().bump(c, 14.dp).padding(horizontal = 13.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            T("Vibration strength", weight = FontWeight.Bold)
            TwoWay(full = true) {
                Haptics.Strength.entries.forEach { s ->
                    Tw(vm.strength == s, { vm.chooseStrength(s) }, s.label, enabled = vm.haptics)
                }
            }
        }
        AlertSounds()
        LRow(Ic.sun, "Keep screen on", "Stops the phone dimming while the app is open", chevron = false) {
            Toggle(vm.keepAwake, { vm.toggleAwake() })
        }

        Row(Modifier.padding(top = 4.dp)) { Sh(Ic.palette, "Look and role") }
        LRow(Ic.palette, "Appearance", chevron = false) {
            TwoWay {
                ThemeMode.entries.forEach { m ->
                    Tw(vm.theme == m, { vm.setThemeMode(m) }, m.name.lowercase().replaceFirstChar { it.uppercase() }, grow = null)
                }
            }
        }
        LRow(Ic.user, "Signed in as", chevron = false) {
            TwoWay {
                Tw(vm.operator, { vm.pickRole(Role.OPERATOR) }, "Operator", grow = null)
                Tw(!vm.operator, { vm.pickRole(Role.COLLECTOR) }, "Collector", grow = null)
            }
        }

        Row(Modifier.padding(top = 4.dp)) { Sh(Ic.layers, "Links to the bins") }
        val looks = routeLooks(hub.routes, c)
        LRow(
            looks.firstOrNull()?.icon ?: Ic.wifi(false), "Connection",
            when {
                looks.isNotEmpty() -> looks.joinToString(" + ") { it.name } + if (hub.link == Link.DEMO) " (demo)" else " answering"
                hub.link == Link.DEMO -> "Demo data. Add an address or channel to a bin to go live."
                else -> "Nothing answers. Check Wi-Fi and the cloud accounts."
            },
            looks.firstOrNull()?.color ?: if (hub.link == Link.OFFLINE) c.bad else c.warn, onClick = { vm.go(Screen.NET) },
        )
        LRow(
            Ic.bluetooth, "Bluetooth setup",
            when { vm.bt in 1..3 -> "Paired, step ${vm.bt + 1} of 4"; vm.bt >= 4 -> "Done, Bluetooth is off"; else -> "Send Wi-Fi to a bin" },
            c.bt, onClick = { vm.go(Screen.BT) },
        )

        Row(Modifier.padding(top = 4.dp)) { Sh(Ic.bell, "Alerts in the background") }
        LRow(
            Ic.bell, "Push alerts",
            when (vm.notifications) {
                Perm.ON -> "On. Alerts arrive with the app closed."
                Perm.BLOCKED -> "Blocked in Android settings."
                Perm.OFF -> "Not turned on yet."
            },
            c.tamper, chevron = false,
        ) {
            when (vm.notifications) {
                Perm.ON -> Tag("ON", c.ok)
                Perm.OFF -> Btn("TURN ON", { vm.overlay = Overlay.NotifPre }, look = Look.TINT, cc = c.tamper, small = true)
                Perm.BLOCKED -> Btn("OPEN", { openAlertSettings(context) }, look = Look.TINT, cc = c.tamper, small = true, sound = Sfx.OPEN)
            }
        }
        LRow(
            Ic.power, "Unrestricted battery",
            if (vm.unrestricted) "On. Android will not pause the app to save battery."
            else "Optimized. Android may pause the app after a while in the background.",
            c.warn, chevron = false,
        ) {
            if (vm.unrestricted) Tag("ON", c.ok)
            else Btn("ALLOW", { vm.askUnrestricted() }, look = Look.TINT, cc = c.warn, small = true, sound = Sfx.OPEN)
        }
        LRow(Ic.bell, "Android alert settings", "Change or silence each kind of alert while the app is closed", onClick = { openAlertSettings(context) })
        Hint(
            "With push alerts on, the app keeps reading the bins after you close it, every 5 s, and a quiet **Watching bins** notice shows while it does. " +
                "Unrestricted battery stops Android from pausing that. Some phones also keep their own list in the app's page of Android settings.",
        )
        if (!vm.unrestricted) Btn("OPEN APP INFO", { vm.openAppInfo() }, Modifier.fillMaxWidth(), Ic.gear, Look.TINT, sound = Sfx.OPEN)

        if (vm.operator) {
            Row(Modifier.padding(top = 4.dp)) { Sh(Ic.download, "Bin list") }
            LRow(Ic.download, "Export bin list", "Names, places and addresses as a file", onClick = { vm.exportBins(hub.bins) }, chevron = false, sound = Sfx.SAVED)
        }

        DemoOptions(vm, hub)
        Hint("Smart-Bin app ${BuildConfig.VERSION_NAME}, matches dashboard v11", Modifier.fillMaxWidth(), align = TextAlign.Center)
    }
}

/** A large on and off key for the two things people change most. */
@Composable
private fun RowScope.BigSwitch(icon: ImageVector, label: String, on: Boolean, cc: Color, onClick: () -> Unit) {
    val c = Sb.c
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Column(
        Modifier
            .weight(1f)
            .then(
                if (on || pressed) Modifier.ring(cc.soft(0.3f), 16.dp).clip(RoundedCornerShape(16.dp)).background(c.base).sunk(c, 16.dp)
                else Modifier.bump(c, 16.dp)
            )
            .press(true, if (on) Sfx.SWITCH_OFF else Sfx.SWITCH_ON, src, onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Ico(icon, 28.dp, if (on) cc else c.ink2)
        T(label, size = 0.9f, color = if (on) c.ink else c.ink2)
        T(if (on) "ON" else "OFF", size = 0.72f, spacing = 0.08f, color = if (on) cc else c.ink2)
    }
}

/** A preview of each alert's own sound and buzz, from Garbage-Guard. */
@Composable
private fun AlertSounds() {
    val c = Sb.c
    Column(
        Modifier.fillMaxWidth().bump(c, 14.dp).padding(horizontal = 13.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        T("Alert sounds", weight = FontWeight.Bold)
        T("Each kind of alert has its own sound and vibration, so you can tell them apart without looking. Tap one to hear it.", size = 0.8f, weight = FontWeight.SemiBold, color = c.ink2)
        val kinds: List<Triple<AlertType, String, Pair<ImageVector, Color>>> = listOf(
            Triple(AlertType.FULL, "Full", Ic.fill to c.bad),
            Triple(AlertType.TAMPER, "Tamper", Ic.tamper to c.tamper),
            Triple(AlertType.SENSOR, "Sensor", Ic.temp(41.0) to c.temp),
            Triple(AlertType.LOCK, "Lock", Ic.lock(true) to c.sim),
            Triple(AlertType.PICKUP, "Pickup", Ic.truck to c.cal),
            Triple(AlertType.INFO, "Setup", Ic.calib to c.ink2),
        )
        kinds.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (type, label, ic) ->
                    Btn(label, { Feel.event(Alerts.sfxFor(type)) }, Modifier.weight(1f), ic.first, Look.TINT, ic.second, small = true, sound = Sfx.TAP)
                }
            }
        }
    }
}

/**
 * Demo options act out what simulation cannot: a faster or paused demo, links
 * coming and going, a bin going quiet, and one-tap events. They change only
 * bins with no address, are forgotten when the app closes, and while none is
 * used the demo runs exactly as before.
 */
@Composable
private fun DemoOptions(vm: AppViewModel, hub: HubState) {
    val c = Sb.c
    @Suppress("UNUSED_VARIABLE") val tick = vm.demoTick
    Row(Modifier.padding(top = 4.dp)) { Sh(Ic.flask, "Demo options", c.sim) }
    val demoBins = hub.bins.filter { it.demo }
    if (demoBins.isEmpty()) {
        Card { Hint("Demo options act on bins with no address. Every bin is set up for real now, so there is nothing to act out.") }
        return
    }
    val inUse = Hub.demoInUse
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Ico(Ic.flask, 20.dp, if (inUse) c.sim else c.ink2)
            T(if (inUse) "Demo options are in use" else "Not in use. The demo runs as normal.", Modifier.weight(1f), size = 0.9f, weight = FontWeight.Bold)
            if (inUse) Btn("RESET", { vm.resetDemo() }, icon = Ic.undo, look = Look.TINT, cc = c.sim, small = true, sound = Sfx.CONFIRM)
        }

        Field("Fill speed", Ic.stopwatch, c.sim) {
            TwoWay(full = true) {
                listOf(0.0 to "Pause", 1.0 to "Real", 60.0 to "Normal", 600.0 to "Fast").forEach { (x, label) ->
                    Tw(vm.demoSpeed == x, { vm.changeDemoSpeed(x) }, label, cc = c.sim)
                }
            }
        }
        Hint("Normal fills a bin in one demo minute as much as in one real hour. Fast is ten times that, for showing a bin fill up in class.")

        Field("Pretend these links answer", Ic.layers, c.sim) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val r = vm.demoLinks
                Btn("WI-FI", { vm.toggleDemoLink(0) }, Modifier.weight(1f), Ic.wifi(true), Look.TINT, c.link, small = true, pressedIn = r.local, sound = Sfx.CHOICE)
                Btn("FIREBASE", { vm.toggleDemoLink(1) }, Modifier.weight(1f), Ic.cloud, Look.TINT, c.cloud, small = true, pressedIn = r.firebase, sound = Sfx.CHOICE, textSize = 0.7f)
                Btn("TSPEAK", { vm.toggleDemoLink(2) }, Modifier.weight(1f), Ic.chart, Look.TINT, c.ts, small = true, pressedIn = r.thingspeak, sound = Sfx.CHOICE, textSize = 0.7f)
            }
        }
        Hint("The link chip in the header shows each mix in its own colours: Wi-Fi green, Firebase orange, ThingSpeak blue.")

        Field("Act out on a bin", Ic.bins, c.sim) {
            BinSeg(vm, hub, PickFor.SET, vm.selSet, allowAll = false) { vm.selSet = it }
        }
        val b = vm.binFor(vm.selSet, hub.bins)
        if (b != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("FULL", { vm.demoEvent(b, Hub.DemoEvent.FULL) }, Modifier.weight(1f), Ic.fill, Look.TINT, c.bad, small = true, sound = Sfx.CONFIRM)
                Btn("TAMPER", { vm.demoEvent(b, Hub.DemoEvent.TAMPER) }, Modifier.weight(1f), Ic.tamper, Look.TINT, c.tamper, small = true, sound = Sfx.CONFIRM)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("BAD AIR", { vm.demoEvent(b, Hub.DemoEvent.AIR) }, Modifier.weight(1f), Ic.gas, Look.TINT, c.gas, small = true, sound = Sfx.CONFIRM)
                Btn("EMPTY", { vm.demoEvent(b, Hub.DemoEvent.EMPTY) }, Modifier.weight(1f), Ic.truck, Look.TINT, c.cal, small = true, sound = Sfx.CONFIRM)
            }
            val quiet = Hub.demoQuiet(b.id)
            Btn(
                if (quiet) "ANSWER AGAIN" else "GO QUIET", { vm.toggleDemoQuiet(b) }, Modifier.fillMaxWidth(),
                Ic.wifi(quiet), Look.TINT, if (quiet) c.ok else c.warn, small = true, sound = if (quiet) Sfx.LINK_UP else Sfx.LINK_LOST,
            )
        }
        Hint("Simulation holds sensor values by hand. These act out events instead: a bin filling past its limit, being tipped, a burst of bad air, a pickup, or a bin that stops answering. They change only bins with no address and are forgotten when the app closes.")
    }
}

// ---------- start page ----------

/**
 * Shown when the app opens after it was closed or ended, while the bins are
 * reached. It lets go by itself once a route answers, or at once for demo
 * data. Coming back from the background skips it.
 */
@Composable
fun StartPage(vm: AppViewModel, hub: HubState) {
    val c = Sb.c
    var waited by remember { mutableStateOf(false) }
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(1300)
        waited = true
        delay(9000)
        slow = true
    }
    val live = hub.link == Link.LIVE || hub.link == Link.CLOUD || hub.link == Link.THINGSPEAK
    val ready = !hub.loading && (hub.link == Link.DEMO || live)
    LaunchedEffect(waited, ready) {
        if (waited && ready) vm.started = true
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(112.dp).well(c, 56.dp).press(true, Sfx.LOGO) {}, contentAlignment = Alignment.Center) {
                BinIcon(46.0, c.accent, 80.dp)
            }
            T("SMART-BIN", size = 1.6f, spacing = 0.04f)
            T(
                when {
                    hub.link == Link.DEMO -> "Opening with demo data"
                    live -> "Connected"
                    slow -> "No bin has answered yet"
                    else -> "Reaching the bins"
                },
                size = 0.9f, weight = FontWeight.Bold, color = c.ink2,
            )
            if (hub.link != Link.DEMO) {
                Column(Modifier.fillMaxWidth().well(c, 16.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val anyIp = hub.bins.any { it.ip.isNotBlank() }
                    StartLine(
                        Ic.wifi(true), "Local Wi-Fi", c.link,
                        when { hub.bins.any { it.source == Source.LIVE } -> true; !anyIp -> null; else -> false },
                        if (anyIp) "Trying" else "No address",
                    )
                    StartLine(Ic.cloud, "Firebase", c.cloud, hub.fbUp, if (hub.fbUp == null) "Not set up" else "Trying")
                    StartLine(Ic.chart, "ThingSpeak", c.ts, hub.tsUp, if (hub.tsUp == null) "Not set up" else "Trying")
                }
            }
            if (slow && !live) {
                Hint("Check the phone is on the bins' Wi-Fi or has internet. You can go in now and the app keeps trying.", align = TextAlign.Center)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Btn("CONNECTION", { vm.started = true; vm.go(Screen.NET) }, Modifier.weight(1f), Ic.layers, Look.TINT, sound = Sfx.OPEN)
                    Btn("GO IN", { vm.started = true }, Modifier.weight(1f), Ic.check, Look.ACCENT, sound = Sfx.CONFIRM)
                }
            } else if (!ready) {
                Btn("SKIP", { vm.started = true }, icon = Ic.chev, look = Look.TINT, small = true, sound = Sfx.TAP, iconMod = Modifier.rotate270())
            }
        }
    }
}

@Composable
private fun StartLine(icon: ImageVector, name: String, cc: Color, up: Boolean?, waiting: String) {
    val c = Sb.c
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Ico(icon, 19.dp, if (up == true) cc else c.ink2, Modifier.breathe(up == false, 1200))
        T(name, Modifier.weight(1f), size = 0.9f, weight = FontWeight.Bold)
        when (up) {
            true -> Tag("CONNECTED", cc)
            false -> T(waiting, size = 0.78f, weight = FontWeight.Bold, color = c.ink2)
            null -> T(waiting, size = 0.78f, weight = FontWeight.SemiBold, color = c.ink2)
        }
    }
}

private fun Modifier.rotate270() = this.graphicsLayer { rotationZ = -90f }
