package smartbin.ui

import smartbin.notify.Feel
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import smartbin.data.HubState
import smartbin.data.OperatorLock
import smartbin.notify.Sfx

/** Every sheet, drawn over the page. Only one is open at a time. */
@Composable
fun Overlays(vm: AppViewModel, hub: HubState, allowAlerts: () -> Unit) {
    val o = vm.overlay ?: return
    val close = { vm.overlay = null }
    when (o) {
        Overlay.Attention -> Sheet(close) { Attention(vm, hub) }
        is Overlay.BinEdit -> {
            val b = hub.bins.firstOrNull { it.id == o.id } ?: return Gone(vm)
            Sheet(close) {
                SheetTitle(Ic.gear, "${b.name} settings")
                Field("Name") { Inp(vm.formName, { vm.formName = it }) }
                Field("Location") { Inp(vm.formPlace, { vm.formPlace = it }) }
                Field("IP address") { Inp(vm.formIp, { vm.formIp = it.trim() }, placeholder = "192.168.1.101", keyboard = KeyboardType.Uri) }
                Field("Cloud name (Firebase)") { Inp(vm.formCloud, { vm.formCloud = it.trim() }, placeholder = "bin01") }
                Field("ThingSpeak channel ID", Ic.chart, Sb.c.ts) { Inp(vm.formTsCh, { vm.formTsCh = it.trim() }, placeholder = "2345678", keyboard = KeyboardType.Number) }
                Field("ThingSpeak read API key", Ic.key, Sb.c.ts) { Inp(vm.formTsKey, { vm.formTsKey = it.trim() }, placeholder = "Only for a private channel") }
                Field("TalkBack ID and API key", Ic.key, Sb.c.ts) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Inp(vm.formTbId, { vm.formTbId = it.trim() }, Modifier.weight(0.42f), placeholder = "ID", keyboard = KeyboardType.Number)
                        Inp(vm.formTbKey, { vm.formTbKey = it.trim() }, Modifier.weight(0.58f), placeholder = "API key")
                    }
                }
                Hint("With an IP address the app reads this bin live over Wi-Fi. With none of the three it shows demo data. Bluetooth setup can fill the address in. The cloud name is the bin's node in Firebase, tried when Wi-Fi does not answer. The ThingSpeak channel is tried after that, and TalkBack carries LOCK, UNLOCK and COLLECT to the bin.")
                Acts {
                    Btn("Remove", { vm.removeBin(b.id) }, Modifier.weight(1f), Ic.trash, Look.DANGER, sound = Sfx.DANGER)
                    Btn("Save", { vm.saveBinEdit(b.id) }, Modifier.weight(1f), Ic.save, Look.ACCENT, sound = Sfx.SAVED)
                }
            }
        }
        is Overlay.BinDetail -> {
            val bins = hub.bins
            val i = bins.indexOfFirst { it.id == o.id }
            if (i < 0) return Gone(vm)
            val b = bins[i]
            Sheet(close) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconKey(Ic.back, { vm.overlay = Overlay.BinDetail(bins[i - 1].id) }, tint = muted(), enabled = i > 0, sound = Sfx.STEP_DOWN)
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        T(b.name, size = 1.02f, maxLines = 1)
                        T("${i + 1} of ${bins.size} · swipe or use the arrows", size = 0.74f, weight = FontWeight.Bold, color = Sb.c.ink2, maxLines = 1)
                    }
                    IconKey(
                        Ic.back, { vm.overlay = Overlay.BinDetail(bins[i + 1].id) }, tint = muted(), enabled = i < bins.size - 1, sound = Sfx.STEP_UP,
                        iconMod = Modifier.rotate180(),
                    )
                    IconKey(Ic.cross, close, tint = muted(), sound = Sfx.BACK)
                }
                // A swipe across the card moves to the next or previous bin, like the arrows.
                var drag by remember { mutableStateOf(0f) }
                Box(
                    Modifier.pointerInput(i, bins.size) {
                        detectHorizontalDragGestures(
                            onDragStart = { drag = 0f },
                            onDragEnd = {
                                val far = 80.dp.toPx()
                                when {
                                    drag < -far && i < bins.size - 1 -> { Feel.event(Sfx.STEP_UP); vm.overlay = Overlay.BinDetail(bins[i + 1].id) }
                                    drag > far && i > 0 -> { Feel.event(Sfx.STEP_DOWN); vm.overlay = Overlay.BinDetail(bins[i - 1].id) }
                                }
                                drag = 0f
                            },
                        ) { _, dx -> drag += dx }
                    },
                ) { BinCard(vm, b, hub, inSheet = true) }
            }
        }
        is Overlay.Pick -> Sheet(close) { Picker(vm, hub, o.forWhat) }
        Overlay.AddBins -> Sheet(close) {
            SheetTitle(Ic.plus, "Add bins")
            SheetText("How many bins are you adding? Each one gets a card with demo data until you give it an address.")
            val c = Sb.c
            Row(
                Modifier.align(Alignment.CenterHorizontally).well(c, 999.dp).padding(5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RoundKey("−", vm.addN > 1, Sfx.STEP_DOWN) { vm.addN-- }
                T("${vm.addN}", Modifier.size(40.dp, 30.dp), size = 1.4f, tnum = true, align = androidx.compose.ui.text.style.TextAlign.Center)
                RoundKey("+", vm.addN < 3, Sfx.STEP_UP) { vm.addN++ }
            }
            Acts {
                Btn("Cancel", close, Modifier.weight(1f), sound = Sfx.BACK)
                Btn("Add ${vm.addN} bin${if (vm.addN > 1) "s" else ""}", { vm.addBins() }, Modifier.weight(1f), Ic.plus, Look.ACCENT, sound = Sfx.CONFIRM)
            }
        }
        Overlay.ClearAlerts -> Sheet(close) {
            val n = vm.shown(hub.alerts).size
            SheetTitle(Ic.trash, "Delete alerts")
            SheetText("Delete ${vm.scope(n)}? This cannot be undone.")
            Acts {
                Btn("Cancel", close, Modifier.weight(1f), sound = Sfx.BACK)
                Btn("Delete $n", { vm.deleteShown(hub.alerts) }, Modifier.weight(1f), Ic.trash, Look.DANGER, sound = Sfx.DANGER)
            }
        }
        Overlay.Wifi -> Sheet(close) {
            SheetTitle(Ic.radar, "Networks the bin can see")
            Hint("Only 2.4 GHz networks are listed. The bin cannot join 5 GHz.")
            val nets = vm.btNets.ifEmpty { if (vm.btDemo) AppViewModel.DEMO_NETS else emptyList() }
            if (nets.isEmpty()) Hint("The bin has not listed any networks yet. Close this and press SCAN again, or type the name.")
            nets.forEach { n ->
                Opt(vm.ssid == n.ssid, { vm.ssid = n.ssid; vm.overlay = null }) {
                    T(n.ssid, Modifier.weight(1f), weight = FontWeight.Bold, maxLines = 1)
                    T(
                        n.rssi?.let { r -> (if (r >= -55) "Strong" else if (r >= -67) "Good" else if (r >= -75) "Fair" else "Weak") + " · $r dBm" } ?: "",
                        size = 0.8f, weight = FontWeight.Bold, color = Sb.c.ink2,
                    )
                }
            }
        }
        Overlay.BtDevices -> Sheet(close) {
            SheetTitle(Ic.bluetooth, "Bins nearby")
            Hint(if (vm.btScanning) "Looking for bins with WI-FI SETUP open..." else vm.btNote ?: "Pick the bin to pair with.")
            vm.btFound.forEach { f ->
                Opt(false, { vm.btConnect(f) }) {
                    Ico(Ic.bluetooth, 19.dp, Sb.c.bt)
                    T(f.name, Modifier.weight(1f), weight = FontWeight.Bold, maxLines = 1)
                    T("${f.rssi} dBm", size = 0.8f, weight = FontWeight.Bold, color = Sb.c.ink2)
                }
            }
            Acts {
                Btn("Cancel", close, Modifier.weight(1f), sound = Sfx.BACK)
                Btn(
                    if (vm.btScanning) "SEARCHING" else "SEARCH AGAIN", { vm.btSearch() }, Modifier.weight(1f), Ic.radar, Look.TINT, Sb.c.bt,
                    enabled = !vm.btScanning, iconMod = Modifier.spin(vm.btScanning),
                )
            }
        }
        is Overlay.PinUnlock -> Sheet(close) {
            OpPad(vm, Ic.lock(true), "Operator PIN", "Collector is the default. Enter the operator PIN to calibrate, simulate and edit bins.", "UNLOCK") { vm.pinUnlock(o.first) }
        }
        is Overlay.PinSet -> Sheet(close) {
            val again = vm.pinNew.isNotEmpty()
            OpPad(
                vm, Ic.key, if (o.change) "New operator PIN" else "Create the operator PIN",
                if (again) "Type it once more to be sure."
                else "${OperatorLock.MIN} to ${OperatorLock.MAX} digits. Anyone without it stays a collector. Only an operator can change it later.",
                if (again) "SAVE" else "NEXT",
            ) { vm.pinSave(o.first, o.change) }
        }
        Overlay.PinChange -> Sheet(close) {
            OpPad(vm, Ic.lock(true), "Change operator PIN", "Enter the current PIN first.", "NEXT") { vm.pinChangeCheck() }
        }
        is Overlay.BtCode -> {
            val b = hub.bins.firstOrNull { it.id == o.id } ?: return Gone(vm)
            Sheet({ vm.btCancel() }) {
                BinScreenCode(vm, b.name)
                PinPad(
                    Ic.bluetooth, "Type the pairing code", "On ${b.name}, open WI-FI SETUP, then type the 6 digits it shows. The code changes every time.",
                    "PAIR", vm.btEntry, AppViewModel.BT_CODE_LEN, AppViewModel.BT_CODE_LEN, vm.btMsg,
                    { vm.btKey(it) }, { vm.btBack() }, { vm.btCheck(b) },
                )
            }
        }
        Overlay.NotifPre -> Sheet({ vm.notNow() }) {
            val c = Sb.c
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Ico(Ic.bell, 40.dp, c.tamper) }
            SheetTitle(null, "Get alerts when the app is closed", center = true)
            SheetText("Smart-Bin tells you when a bin is full, tilted, too hot, too humid or its air turns bad, with a Start pickup button on the alert.", center = true)
            Acts {
                Btn("Not now", { vm.notNow() }, Modifier.weight(1f), sound = Sfx.BACK)
                Btn("Allow alerts", allowAlerts, Modifier.weight(1f), look = Look.ACCENT, sound = Sfx.CONFIRM)
            }
        }
    }
}

@Composable
private fun muted() = Sb.c.ink2

private fun Modifier.rotate180(): Modifier = this.graphicsLayer { rotationZ = 180f }

/** A sheet whose bin has gone, removed elsewhere: it closes itself. */
@Composable
private fun Gone(vm: AppViewModel) {
    LaunchedEffect(Unit) { vm.overlay = null }
}

@Composable
private fun RoundKey(label: String, enabled: Boolean, sound: Sfx, onClick: () -> Unit) {
    val c = Sb.c
    Box(
        Modifier.size(44.dp).then(if (enabled) Modifier else Modifier.padding(0.dp)).bump(c, 22.dp)
            .press(enabled, sound, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { T(label, size = 1.3f, color = if (enabled) c.ink2 else c.ink2.soft(0.35f), lineHeight = 1f) }
}

/** The operator PIN sheet. */
@Composable
private fun OpPad(vm: AppViewModel, icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, go: String, onGo: () -> Unit) =
    PinPad(icon, title, text, go, vm.opPin, OperatorLock.MIN, OperatorLock.MAX, vm.pinMsg, { vm.pinKey(it) }, { vm.pinBack() }, onGo)

/** A code sheet: dots for the digits, a line for what went wrong, and a keypad. */
@Composable
private fun PinPad(
    icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, go: String,
    value: String, min: Int, max: Int, msg: Pair<Boolean, String>?,
    onKey: (Char) -> Unit, onBack: () -> Unit, onGo: () -> Unit,
) {
    val c = Sb.c
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Ico(icon, 38.dp, c.accent) }
    SheetTitle(null, title, center = true)
    SheetText(text, center = true)
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
        repeat(maxOf(min, value.length)) { i ->
            Box(Modifier.size(14.dp).clip(RoundedCornerShape(7.dp)).background(if (i < value.length) c.accent else c.ink2.soft(0.25f)))
        }
    }
    msg?.let { (ok, m) -> T(m, Modifier.fillMaxWidth(), size = 0.82f, weight = FontWeight.Bold, color = if (ok) c.ok else c.bad, align = androidx.compose.ui.text.style.TextAlign.Center) }
    val rows = listOf("123", "456", "789")
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                r.forEach { d -> PinKey(d.toString(), Modifier.weight(1f), value.length < max) { onKey(d) } }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PinKey("⌫", Modifier.weight(1f), value.isNotEmpty(), Sfx.STEP_DOWN, onClick = onBack)
            PinKey("0", Modifier.weight(1f), value.length < max) { onKey('0') }
            PinKey(go, Modifier.weight(1f), value.length >= min, Sfx.CONFIRM, accent = true, onClick = onGo)
        }
    }
}

/**
 * The stand-in for the bin's touchscreen while it pairs: the dark ILI9341
 * panel with the code in big digits and how long it lasts. A real bin shows
 * this on its own screen, never on the phone.
 */
@Composable
private fun BinScreenCode(vm: AppViewModel, name: String) {
    var left by remember { mutableStateOf(60) }
    LaunchedEffect(vm.btCodeAt) {
        while (true) {
            left = ((AppViewModel.BT_CODE_LIFE - (System.currentTimeMillis() - vm.btCodeAt)) / 1000).toInt().coerceAtLeast(0)
            if (left == 0) {
                vm.btNewCode()
                break
            }
            kotlinx.coroutines.delay(250)
        }
    }
    val dim = androidx.compose.ui.graphics.Color(0xFF8A96A3)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(androidx.compose.ui.graphics.Color(0xFF14181C)).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        T("STAND-IN FOR ${name.uppercase()}'S SCREEN", size = 0.6f, spacing = 0.08f, color = dim)
        T("PAIRING CODE", size = 0.72f, spacing = 0.12f, color = androidx.compose.ui.graphics.Color(0xFF5FD0E8))
        T(vm.btCode.chunked(3).joinToString(" "), size = 2.1f, spacing = 0.08f, color = androidx.compose.ui.graphics.Color.White, tnum = true, maxLines = 1)
        T("Expires in $left s", size = 0.72f, weight = FontWeight.Bold, color = if (left <= 10) androidx.compose.ui.graphics.Color(0xFFFF8A7A) else dim, tnum = true)
    }
}

@Composable
private fun PinKey(label: String, modifier: Modifier, enabled: Boolean = true, sound: Sfx = Sfx.PIN_KEY, accent: Boolean = false, onClick: () -> Unit) {
    val c = Sb.c
    Box(
        modifier.height(54.dp).bump(c, 14.dp).press(enabled, sound, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val ink = when { !enabled -> c.ink2.soft(0.35f); accent -> c.accent; else -> c.ink }
        T(label, size = if (label.length > 1 && label != "⌫") 0.86f else 1.35f, spacing = if (label.length > 1) 0.05f else 0f, color = ink, tnum = true, maxLines = 1)
    }
}

/** The Needs attention sheet: unread alerts first, then what is going on right now. */
@Composable
private fun Attention(vm: AppViewModel, hub: HubState) {
    val c = Sb.c
    SheetTitle(Ic.bell, "Needs attention")
    val pending = hub.alerts.filter { !it.read }
    val live = liveBanners(hub)
    if (pending.isNotEmpty()) {
        PopSec("Unread alerts")
        pending.take(4).forEach { a -> Banner(sevTone(a.sev), Ic.named(a.icon), a.title, "${a.meta} · ${ago(a.at)}") }
        if (pending.size > 4) PopSec("and ${pending.size - 4} more in the Alerts tab")
        Row(Modifier.padding(horizontal = 4.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Btn("MARK ALL READ", { vm.markAll() }, Modifier.weight(1f), Ic.check, Look.TINT, c.ok, small = true, sound = Sfx.CONFIRM)
            Btn("OPEN ALERTS", { vm.go(Screen.ALERTS) }, Modifier.weight(1f), Ic.bell, Look.TINT, c.tamper, small = true, sound = Sfx.OPEN)
        }
    }
    if (live.isNotEmpty()) {
        PopSec("Right now")
        live.forEach { Banner(it.tone, it.icon, it.title, it.small) }
    }
    if (pending.isEmpty() && live.isEmpty()) Empty(Ic.check, "Nothing needs attention.")
}

@Composable
private fun PopSec(text: String) {
    T(text.uppercase(), Modifier.padding(start = 2.dp, top = 4.dp), size = 0.7f, spacing = 0.08f, color = Sb.c.ink2)
}

/** Pick a bin, with a search box when there are many. */
@Composable
private fun Picker(vm: AppViewModel, hub: HubState, forWhat: PickFor) {
    val c = Sb.c
    val bins = hub.bins
    var q by remember { mutableStateOf("") }
    val sel: String? = when (forWhat) {
        PickFor.CHART -> vm.chartBin
        PickFor.ENV -> vm.binFor(vm.envBin, bins)?.id
        PickFor.SET -> vm.binFor(vm.selSet, bins)?.id
        PickFor.SIM -> vm.binFor(vm.selSim, bins)?.id
    }
    val choose = { id: String? ->
        when (forWhat) {
            PickFor.CHART -> vm.chartBin = id
            PickFor.ENV -> vm.envBin = id
            PickFor.SET -> { vm.selSet = id; vm.pinShown = false }
            PickFor.SIM -> vm.selSim = id
        }
        vm.overlay = null
    }
    SheetTitle(Ic.bins, "Pick a bin") { T(forWhat.title, size = 0.68f, weight = FontWeight.Bold, color = c.ink2) }
    if (bins.size > 8) Inp(q, { q = it }, placeholder = "Search by name or place")
    val shown = bins.withIndex().filter { (_, b) -> q.isBlank() || "${b.name} ${b.place}".contains(q.trim(), ignoreCase = true) }
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        if (forWhat == PickFor.CHART && q.isBlank()) Opt(sel == null || bins.none { it.id == sel }, { choose(null) }) {
            Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) { Ico(Ic.layers, 21.dp, c.ink2) }
            Column(Modifier.weight(1f)) {
                T("All bins", size = 0.95f)
                T(if (bins.size > 5) "The 5 fullest are drawn" else "Every bin on one chart", size = 0.76f, weight = FontWeight.SemiBold, color = c.ink2)
            }
        }
        shown.forEach { (i, b) ->
            Opt(sel == b.id, { choose(b.id) }) {
                BinIcon(b.reading.fill, binColor(i, c.isDark), 34.dp)
                Column(Modifier.weight(1f)) {
                    T(b.name, size = 0.95f, maxLines = 1)
                    T(b.place, size = 0.76f, weight = FontWeight.SemiBold, color = c.ink2, maxLines = 1)
                }
                if (b.simulating) MTag("SIM", c.sim)
                if (b.calibrating) MTag("CAL", c.cal)
                T(if (b.reading.noFill) "--" else "${Math.round(b.reading.fill)}%", size = 0.9f, color = levelColor(b, c))
            }
        }
        if (shown.isEmpty()) Empty(Ic.bins, "No bin matches.")
    }
}

@Composable
private fun MTag(text: String, bg: androidx.compose.ui.graphics.Color) {
    Box(Modifier.clip(RoundedCornerShape(5.dp)).background(bg).padding(horizontal = 6.dp, vertical = 3.dp)) {
        T(text, size = 0.6f, spacing = 0.04f, color = androidx.compose.ui.graphics.Color.White)
    }
}
