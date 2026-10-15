package smartbin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
                        T("${i + 1} of ${bins.size} · arrows move between bins", size = 0.74f, weight = FontWeight.Bold, color = Sb.c.ink2, maxLines = 1)
                    }
                    IconKey(
                        Ic.back, { vm.overlay = Overlay.BinDetail(bins[i + 1].id) }, tint = muted(), enabled = i < bins.size - 1, sound = Sfx.STEP_UP,
                        iconMod = Modifier.rotate180(),
                    )
                    IconKey(Ic.cross, close, tint = muted(), sound = Sfx.BACK)
                }
                BinCard(vm, b, hub, inSheet = true)
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
            listOf("HomeWiFi-2G" to "Strong", "TUP-Lab-2.4" to "Good", "PLDTHOMEFIBR-2G" to "Fair", "Guest_2G" to "Weak").forEach { (n, s) ->
                Opt(vm.ssid == n, { vm.ssid = n; vm.overlay = null }) {
                    T(n, Modifier.weight(1f), weight = FontWeight.Bold)
                    T(s, size = 0.8f, weight = FontWeight.Bold, color = Sb.c.ink2)
                }
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
                T("${Math.round(b.reading.fill)}%", size = 0.9f, color = levelColor(b, c))
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
