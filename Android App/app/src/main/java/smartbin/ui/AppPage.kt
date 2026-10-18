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
import smartbin.notify.Sounds

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
        SoundTest()
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
        if (vm.operator) LRow(Ic.key, "Change operator PIN", "Asks for the current PIN first", onClick = { vm.changePin() })
        Hint("The app opens on the start page each time, where you pick a role. Operator needs the PIN, and drops back to collector after 2 min in the background. After 5 wrong PINs it waits 30 s, longer each time.")

        Row(Modifier.padding(top = 4.dp)) { Sh(Ic.layers, "Links to the bins") }
        val looks = routeLooks(hub.routes, c)
        LRow(
            looks.firstOrNull()?.icon ?: Ic.wifi(false), "Connection",
            when {
                looks.isNotEmpty() -> looks.joinToString(" + ") { it.name } + if (hub.demo) " (demo)" else " answering"
                hub.link == Link.DEMO -> "Demo bins only. Pair a bin over Bluetooth to go live."
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

        Row(Modifier.padding(top = 4.dp)) { Sh(Ic.flask, "Demo", c.sim) }
        LRow(
            Ic.undo, "Start page",
            if (hub.demoOn) "Demo is on. Turn it off or on from the start page." else "Pick a role again, or turn on the demo",
            c.sim, onClick = { vm.toStart() },
        )

        if (vm.operator) {
            Row(Modifier.padding(top = 4.dp)) { Sh(Ic.download, "Bin list") }
            LRow(Ic.download, "Export bin list", "Names, places and addresses as a file", onClick = { vm.exportBins(hub.bins) }, chevron = false, sound = Sfx.SAVED)
        }

        if (hub.demoOn) DemoOptions(vm, hub)
        Hint("Smart-Bin app ${BuildConfig.VERSION_NAME}, matches dashboard v2 and firmware ${hub.bins.firstOrNull { !it.isDemo && it.fw.isNotBlank() }?.fw ?: "v2"}", Modifier.fillMaxWidth(), align = TextAlign.Center)
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

/** One key of the sound test: the sound, its name, and an icon and colour of its own. */
private class Snd(val sfx: Sfx, val label: String, val icon: ImageVector, val color: (SbColors) -> Color)

/**
 * The sound test, the same groups, names, icons and colours as the dashboard's.
 * Each key plays its sound and vibration even with sound off, so people can learn them.
 */
private val SOUND_GROUPS: List<Pair<String, List<Snd>>> = listOf(
    "Alerts" to listOf(
        Snd(Sfx.ALERT_FULL, "Bin full", Ic.fill) { it.bad },
        Snd(Sfx.ALERT_TAMPER, "Tamper", Ic.tamper) { it.tamper },
        Snd(Sfx.ALERT_SENSOR, "Sensor limit", Ic.temp(41.0)) { it.temp },
        Snd(Sfx.ALERT_LOCK, "Bin locked", Ic.lock(true)) { it.sim },
        Snd(Sfx.ALERT_PICKUP, "Pickup alert", Ic.truck) { it.cal },
        Snd(Sfx.ALERT_INFO, "Info", Ic.bell) { it.bt },
    ),
    "Bins and links" to listOf(
        Snd(Sfx.LINK_UP, "Wi-Fi link", Ic.wifi(true)) { it.link },
        Snd(Sfx.CLOUD, "Cloud link", Ic.cloud) { it.cloud },
        Snd(Sfx.LINK_LOST, "Link lost", Ic.wifi(false)) { it.bad },
        Snd(Sfx.BIN_LOST, "Bin quiet", Ic.radar) { it.warn },
        Snd(Sfx.BIN_BACK, "Bin back", Ic.bins) { it.ok },
        Snd(Sfx.CMD_OK, "Command OK", Ic.check) { it.ok },
        Snd(Sfx.CMD_FAIL, "Command failed", Ic.cross) { it.bad },
    ),
    "Lid and pickup" to listOf(
        Snd(Sfx.LOCK, "Lock", Ic.lock(true)) { it.sim },
        Snd(Sfx.UNLOCK, "Unlock", Ic.lock(false)) { it.lid },
        Snd(Sfx.PICKUP, "Pickup start", Ic.truck) { it.cal },
        Snd(Sfx.DONE, "Pickup finished", Ic.check) { it.fill },
    ),
    "Setup" to listOf(
        Snd(Sfx.CALIB, "Calibrate", Ic.calib) { it.cal },
        Snd(Sfx.SIM, "Simulation", Ic.flask) { it.sim },
        Snd(Sfx.SAVED, "Saved", Ic.save) { it.ok },
        Snd(Sfx.DENIED, "Not allowed", Ic.cross) { it.warn },
    ),
    "Bluetooth Wi-Fi setup" to listOf(
        Snd(Sfx.BT_PAIRED, "Paired", Ic.bluetooth) { it.bt },
        Snd(Sfx.BT_WRONG, "Wrong code", Ic.key) { it.warn },
        Snd(Sfx.BT_BLOCKED, "Pairing stopped", Ic.lock(true)) { it.bad },
        Snd(Sfx.WIFI_JOINED, "Wi-Fi joined", Ic.wifi(true)) { it.ok },
        Snd(Sfx.WIFI_FAILED, "Wi-Fi failed", Ic.wifi(false)) { it.tamper },
        Snd(Sfx.BT_OFF, "Bluetooth off", Ic.power) { it.motion },
    ),
    "Operator PIN" to listOf(
        Snd(Sfx.OP_IN, "Operator in", Ic.sliders) { it.accent },
        Snd(Sfx.OP_OUT, "Operator out", Ic.undo) { it.weight },
        Snd(Sfx.PIN_KEY, "PIN key", Ic.pin) { it.humid },
        Snd(Sfx.PIN_WRONG, "Wrong PIN", Ic.key) { it.bad },
        Snd(Sfx.PIN_LOCKOUT, "PIN lockout", Ic.stopwatch) { it.tamper },
        Snd(Sfx.PIN_SET, "PIN saved", Ic.save) { it.gas },
    ),
    "App" to listOf(
        Snd(Sfx.BOOT, "Start", Ic.power) { it.ok },
        Snd(Sfx.LOGO, "Logo", Ic.bins) { it.gas },
        Snd(Sfx.DEMO, "Demo on", Ic.flask) { it.warn },
        Snd(Sfx.THEME_LIGHT, "Light theme", Ic.sunmoon(false)) { it.lid },
        Snd(Sfx.THEME_DARK, "Dark theme", Ic.sunmoon(true)) { it.humid },
    ),
    "Keys" to listOf(
        Snd(Sfx.TAP, "Tap", Ic.capture) { it.humid },
        Snd(Sfx.CHOICE, "Choice", Ic.sliders) { it.gas },
        Snd(Sfx.TAB, "Tab", Ic.layers) { it.motion },
        Snd(Sfx.OPEN, "Open", Ic.eye) { it.fill },
        Snd(Sfx.BACK, "Back", Ic.undo) { it.weight },
        Snd(Sfx.CONFIRM, "Confirm", Ic.check) { it.ok },
        Snd(Sfx.DANGER, "Danger", Ic.trash) { it.bad },
        Snd(Sfx.SWITCH_ON, "Switch on", Ic.power) { it.link },
        Snd(Sfx.SWITCH_OFF, "Switch off", Ic.power) { it.ink2 },
        Snd(Sfx.STEP_UP, "Step up", Ic.plus) { it.temp },
        Snd(Sfx.STEP_DOWN, "Step down", Ic.percent) { it.press },
    ),
)

@Composable
private fun SoundTest() {
    val c = Sb.c
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().bump(c, 14.dp).padding(horizontal = 13.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Sound test", Modifier.weight(1f), weight = FontWeight.Bold)
            Btn(if (open) "HIDE" else "SHOW", { open = !open }, icon = Ic.speaker, look = Look.TINT, small = true, sound = Sfx.OPEN)
        }
        T("Every sound the app and dashboard make, in the same groups. Each one plays with its vibration, even with sound off, so you can learn them.", size = 0.8f, weight = FontWeight.SemiBold, color = c.ink2)
        if (open) SOUND_GROUPS.forEach { (group, sounds) ->
            T(group, size = 0.78f, spacing = 0.06f, weight = FontWeight.Bold, color = c.ink2)
            sounds.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { k ->
                        // The key itself plays the sound and its buzz; with sound off, play it anyway.
                        Btn(k.label, { if (!Feel.sounds) Sounds.play(k.sfx, force = true) }, Modifier.weight(1f), k.icon, Look.TINT, k.color(c), small = true, sound = k.sfx, textSize = 0.7f)
                    }
                    repeat(3 - row.size) { Box(Modifier.weight(1f)) }
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
                val r = hub.demoLinks
                Btn("WI-FI", { vm.toggleDemoLink(0) }, Modifier.weight(1f), Ic.wifi(true), Look.TINT, c.link, small = true, pressedIn = r.local, sound = Sfx.CHOICE)
                Btn("FIREBASE", { vm.toggleDemoLink(1) }, Modifier.weight(1f), Ic.cloud, Look.TINT, c.cloud, small = true, pressedIn = r.firebase, sound = Sfx.CHOICE, textSize = 0.7f)
                Btn("TSPEAK", { vm.toggleDemoLink(2) }, Modifier.weight(1f), Ic.chart, Look.TINT, c.ts, small = true, pressedIn = r.thingspeak, sound = Sfx.CHOICE, textSize = 0.7f)
            }
        }
        Hint("The first one on, in the app's order (Wi-Fi, then Firebase, then ThingSpeak), is the one the demo bins answer on. The cards, banners and link chip change to match, in each link's colour: Wi-Fi green, Firebase orange, ThingSpeak blue.")

        Field("Cloud commands", Ic.cloud, c.sim) {
            TwoWay(full = true) {
                Tw(!hub.demoNoConfirm, { vm.setDemoNoConfirm(false) }, "Confirm in 4 s", cc = c.ok)
                Tw(hub.demoNoConfirm, { vm.setDemoNoConfirm(true) }, "Time out at 8 s", cc = c.bad)
            }
        }
        Hint("With Firebase or ThingSpeak on, LOCK, UNLOCK and COLLECT stay locked while the bin is asked. The bin either confirms after 4 s and the key flashes green, or never answers and the key flashes red at 8 s.")

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
        Hint("Simulation holds sensor values by hand. These act out events instead: a bin filling past its limit, being tipped, a burst of bad air, a pickup, or a bin that stops answering. They change only the demo bins and are forgotten when the app closes.")
    }
}
