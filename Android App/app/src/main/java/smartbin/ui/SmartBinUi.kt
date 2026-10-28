package smartbin.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import smartbin.data.Bin
import smartbin.data.BinStatus
import smartbin.data.Hub
import smartbin.data.HubState
import smartbin.data.Link
import smartbin.data.Mode
import smartbin.data.Role
import smartbin.data.Severity
import smartbin.data.v
import smartbin.notify.Feel
import smartbin.notify.Sfx

/** The whole app: the gate on first launch, then header, page, tabs and sheets, as the mockup's phone frame. */
@Composable
fun SmartBinUi(vm: AppViewModel, setLandscape: (Boolean) -> Unit) {
    val hub by Hub.state.collectAsState()
    val dark = when (vm.theme) {
        ThemeMode.AUTO -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val mode = if (vm.role == null || !vm.started) Mode.NORMAL else hub.mode
    LaunchedEffect(vm.started, vm.role) { vm.syncInMain() }
    val c = animated(sbColors(dark, mode))
    val context = LocalContext.current

    LaunchedEffect(dark) {
        val clear = android.graphics.Color.TRANSPARENT
        val style = if (dark) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear)
        (context as? ComponentActivity)?.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
    LaunchedEffect(vm.fullscreen) { setLandscape(vm.fullscreen != null) }
    val root = LocalView.current
    LaunchedEffect(vm.keepAwake) { root.keepScreenOn = vm.keepAwake }

    val askAlerts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.permissionAnswered(it) }
    val allowAlerts: () -> Unit = {
        vm.overlay = null
        if (Build.VERSION.SDK_INT >= 33) askAlerts.launch(Manifest.permission.POST_NOTIFICATIONS)
        else openAlertSettings(context)
    }

    // The mockup is drawn for a phone 384 dp wide. Other phones scale it, so
    // sizes, spacing and text keep their proportions, and the phone's own font
    // size is held to a small range so no label is cut off.
    val cfg = LocalConfiguration.current
    val base = LocalDensity.current
    val land = vm.fullscreen != null
    val byWidth = cfg.smallestScreenWidthDp / 384f
    val fit = (if (land) byWidth else minOf(byWidth, cfg.screenHeightDp / 700f)).coerceIn(0.8f, 1.6f)
    val scaled = remember(base.density, base.fontScale, fit) { Density(base.density * fit, base.fontScale.coerceIn(0.9f, 1.1f)) }

    CompositionLocalProvider(LocalSb provides c, LocalContentColor provides c.ink, LocalDensity provides scaled) {
        Box(
            Modifier.fillMaxSize().page(c).pointerInput(Unit) {
                // Watches every touch before the keys get it, without taking it:
                // the notice goes away and the key under the finger still works.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    vm.hideToast()
                }
            },
        ) {
            when {
                vm.role == null || !vm.started -> Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) { Gate(vm, hub) }
                land -> FullChart(vm, hub)
                else -> Column(
                    Modifier.align(Alignment.TopCenter).fillMaxHeight().widthIn(max = 520.dp).fillMaxWidth().statusBarsPadding(),
                ) {
                    Header(vm, hub, dark)
                    Box(Modifier.weight(1f)) {
                        if (hub.loading) Loading()
                        else AnimatedContent(
                            targetState = vm.screen,
                            transitionSpec = {
                                val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                                (slideInHorizontally(tween(220)) { dir * it / 8 } + fadeIn(tween(220))) togetherWith
                                    (slideOutHorizontally(tween(160)) { -dir * it / 8 } + fadeOut(tween(110)))
                            },
                            label = "tab",
                        ) { s ->
                            when (s) {
                                Screen.OVERVIEW -> Overview(vm, hub)
                                Screen.CHARTS -> Charts(vm, hub)
                                Screen.SETTINGS -> SettingsPage(vm, hub)
                                Screen.SIM -> if (vm.operator) Simulation(vm, hub) else Overview(vm, hub)
                                Screen.ALERTS -> AlertsPage(vm, hub)
                                Screen.APP -> AppPage(vm, hub)
                                Screen.BT -> Bluetooth(vm, hub)
                                Screen.NET -> Connection(vm, hub)
                            }
                        }
                    }
                    BottomNav(vm, hub)
                }
            }
            // Back from a page under Settings, or from a tab, goes one step toward Overview.
            val view = LocalView.current
            BackHandler(enabled = vm.overlay == null && vm.role != null && vm.started && (land || vm.screen != Screen.OVERVIEW)) {
                Feel.play(view, Sfx.BACK)
                if (land) vm.fullscreen = null else vm.back()
            }
            Overlays(vm, hub, allowAlerts)
            vm.toast?.let { Toast(it, bottom = if (land || vm.role == null || !vm.started) 24.dp else 92.dp) }
        }
    }
}

fun openAlertSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
private fun Loading() {
    ScrollPage {
        Skeleton(40.dp, Modifier.fillMaxWidth(0.5f), radius = 12.dp)
        Skeleton(430.dp)
        Skeleton(180.dp)
    }
}

// ---------- header ----------

private val MODES = mapOf(
    Mode.NORMAL to "NORMAL", Mode.SIM to "SIMULATION", Mode.CALIB to "CALIBRATING", Mode.DUAL to "SIM + CAL", Mode.ALERT to "ALERT",
)

private fun modeIcon(m: Mode): ImageVector = when (m) {
    Mode.NORMAL -> Ic.check; Mode.SIM -> Ic.flask; Mode.CALIB, Mode.DUAL -> Ic.calib; Mode.ALERT -> Ic.bell
}

@Composable
private fun Header(vm: AppViewModel, hub: HubState, dark: Boolean) {
    val c = Sb.c
    val view = LocalView.current
    // Brand, mode chip, theme key
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // A tap on the logo plays its chime and its build-up again.
        var logoPlay by remember { androidx.compose.runtime.mutableIntStateOf(1) }
        Row(
            Modifier.press(true, Sfx.LOGO) { logoPlay++ },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SmartBinLogo(30.dp, logoPlay)
            T("SMART-BIN", size = 1.12f, spacing = 0.04f, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        // DEMO shows only while demo mode is on. It goes back to the start page, where demo is turned off.
        if (hub.demoOn) {
            Box(
                Modifier.defaultMinSize(minHeight = 36.dp).bump(c, 11.dp).press(true, Sfx.BACK) { vm.toStart() }.padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) { T("DEMO", size = 0.74f, spacing = 0.04f, color = c.warn, maxLines = 1) }
        }
        val m = hub.mode
        Row(
            Modifier
                .raised(c, 999.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(c.barBrush)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Ico(modeIcon(m), 15.dp, c.onAccent)
            T(MODES.getValue(m), size = 0.74f, spacing = 0.04f, color = c.onAccent, maxLines = 1)
        }
        IconKey(Ic.sunmoon(dark), {
            vm.toggleTheme(dark)
        }, size = 40.dp, iconSize = 19.dp, sound = if (dark) Sfx.THEME_LIGHT else Sfx.THEME_DARK)
        // The gear opens the app's own settings: sound, vibration, battery, links and demo options.
        val onApp = vm.screen == Screen.APP || vm.screen == Screen.NET || vm.screen == Screen.BT
        IconKey(Ic.gear, { if (onApp) vm.back() else vm.go(Screen.APP) }, size = 40.dp, iconSize = 19.dp, tint = if (onApp) c.accent else c.ink2, sound = if (onApp) Sfx.BACK else Sfx.OPEN)
    }
    // The live strip: what needs attention, the link and the role
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(vm, hub)
            LinkChip(hub, Modifier.weight(1f, fill = false)) { vm.go(Screen.NET) }
        }
        val src = remember { MutableInteractionSource() }
        val pressed by src.collectIsPressedAsState()
        Box(
            Modifier
                .defaultMinSize(minHeight = 36.dp)
                .then(if (pressed) Modifier.clip(RoundedCornerShape(11.dp)).background(c.base).sunk(c, 11.dp) else Modifier.bump(c, 11.dp))
                .press(true, Sfx.CHOICE, src) { vm.pickRole(if (vm.operator) Role.COLLECTOR else Role.OPERATOR) }
                .padding(horizontal = 13.dp),
            contentAlignment = Alignment.Center,
        ) { T(if (vm.operator) "OPERATOR" else "COLLECTOR", size = 0.76f, spacing = 0.04f, maxLines = 1) }
    }
    Box(Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(3.dp)).background(c.barBrush))
}

/** One active condition: the banner kind, its icon, title and line under it. */
class Live(val tone: Tone, val icon: ImageVector, val title: String, val small: String? = null, val pill: Pair<ImageVector, Color>)

/** The conditions the mockup shows as banners, in its order. */
@Composable
fun liveBanners(hub: HubState): List<Live> {
    val c = Sb.c
    val b = hub.bins
    val out = ArrayList<Live>()
    val cal = b.filter { it.calibrating }
    val tam = b.filter { it.reading.mode.tamper }
    val full = b.filter { it.full }
    val sim = b.filter { it.simulating }
    val lost = b.filter { it.stale }
    if (cal.isNotEmpty()) out += Live(Tone.TEAL, Ic.calib, "CALIBRATING: " + cal.joinToString { it.name }, "Keep the bin empty and do not touch it.", Ic.calib to c.cal)
    if (tam.isNotEmpty()) out += Live(Tone.RED, Ic.tamper, "TAMPER: " + tam.joinToString { it.name }, "Check the bin before resetting the alert.", Ic.tamper to c.tamper)
    if (full.isNotEmpty()) out += Live(Tone.RED, Ic.truck, "COLLECT NOW: " + full.joinToString { "${it.name} at ${Math.round(it.reading.fill)}%" }, null, Ic.truck to c.tamper)
    if (sim.isNotEmpty()) out += Live(Tone.PURPLE, Ic.flask, "SIMULATION MODE", "Some readings are set by hand, not by the sensors.", Ic.flask to c.sim)
    // Pretend links from the demo options say so, so nobody mistakes them for real ones.
    hub.tourMsg?.let { out += Live(Tone.PURPLE, Ic.flask, "Demo tour: $it", "The tour runs by itself. Press a key on a demo bin and it waits a minute for you.", Ic.flask to c.sim) }
    val broken = b.filter { it.reading.faults.isNotEmpty() }
    if (broken.isNotEmpty()) out += Live(
        Tone.AMBER, Ic.gear, "SENSOR NOT ANSWERING: " + broken.joinToString { "${it.name} (${it.reading.faults.joinToString()})" },
        "Its readings show -- until the sensor answers again. Check the wiring.", Ic.gear to c.warn,
    )
    if (b.isEmpty()) {
        out += Live(Tone.AMBER, Ic.wifi(false), "No bins linked yet", "Pair a bin over Bluetooth in App settings, or add one with its IP address as operator. Turn DEMO on at the start page to look around.", Ic.wifi(false) to c.warn)
        return out
    }
    if (hub.demoOn) out += Live(Tone.AMBER, Ic.flask, "Demo mode", "Bins named Demo show made-up readings. Press DEMO at the top to go back to the start page and turn it off.", Ic.flask to c.warn)
    val pretend = if (hub.demo) "Pretend link from the demo options. " else ""
    when (hub.link) {
        Link.DEMO -> {}
        Link.OFFLINE -> out += Live(Tone.RED, Ic.wifi(false), "Bins are not answering", "No bin answers on Wi-Fi and the cloud cannot be reached. Check the phone has internet.", Ic.wifi(false) to c.tamper)
        Link.CLOUD -> out += Live(
            Tone.ORANGE, Ic.cloud, "Cloud mode",
            pretend + "No bin answers on this Wi-Fi, so readings come through Firebase every 5 s. LOCK, UNLOCK and COLLECT still work. Calibration and simulation need the bins’ Wi-Fi.",
            Ic.cloud to c.cloud,
        )
        Link.THINGSPEAK -> out += Live(
            Tone.BLUE, Ic.chart, "ThingSpeak mode",
            pretend + "Wi-Fi and Firebase do not answer, so readings come from ThingSpeak every 15 s. LOCK, UNLOCK and COLLECT go through TalkBack.",
            Ic.chart to c.ts,
        )
        Link.LIVE -> {}
    }
    if (lost.isNotEmpty() && hub.link != Link.OFFLINE) {
        out += Live(Tone.RED, Ic.wifi(false), "Not answering: " + lost.joinToString { it.name }, "No reading for more than 90 s. Check its power and Wi-Fi.", Ic.wifi(false) to c.tamper)
    }
    return out
}

@Composable
private fun StatusPill(vm: AppViewModel, hub: HubState) {
    val c = Sb.c
    val live = liveBanners(hub)
    val unread = hub.alerts.filter { !it.read }
    if (unread.isEmpty() && live.isEmpty()) return
    val (icon, cc, text) = if (unread.isNotEmpty()) {
        val worst = Severity.entries.first { s -> unread.any { it.sev == s } }
        Triple(Ic.bell, sevColor(worst, c), "${unread.size} unread")
    } else {
        val p = live.first().pill
        Triple(p.first, p.second, "${live.size} active")
    }
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Row(
        Modifier
            .defaultMinSize(minHeight = 36.dp)
            .then(if (pressed) Modifier.ring(cc.soft(0.34f), 18.dp).clip(RoundedCornerShape(18.dp)).background(c.base).sunk(c, 18.dp) else Modifier.bump(c, 18.dp, ring = cc.soft(0.34f)))
            .press(true, Sfx.OPEN, src) { vm.overlay = Overlay.Attention }
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Ico(icon, 18.dp, cc)
        T(text, size = 0.8f, spacing = 0.03f, color = cc, maxLines = 1)
    }
}

fun sevColor(s: Severity, c: SbColors) = when (s) {
    Severity.CRITICAL -> c.bad; Severity.WARNING -> c.warn; Severity.INFO -> c.sim; Severity.SUCCESS -> c.ok
}

fun sevTone(s: Severity) = when (s) {
    Severity.CRITICAL -> Tone.RED; Severity.WARNING -> Tone.AMBER; Severity.INFO -> Tone.PURPLE; Severity.SUCCESS -> Tone.GREEN
}

/** One way to the bins, as the link chip and Connection draw it. */
class RouteLook(val name: String, val icon: ImageVector, val color: Color)

/** The routes that answer, in their order: Wi-Fi green, Firebase orange, ThingSpeak blue. */
fun routeLooks(r: smartbin.data.Routes, c: SbColors): List<RouteLook> = buildList {
    if (r.local) add(RouteLook("Wi-Fi", Ic.wifi(true), c.link))
    if (r.firebase) add(RouteLook("Firebase", Ic.cloud, c.cloud))
    if (r.thingspeak) add(RouteLook("ThingSpeak", Ic.chart, c.ts))
}

/**
 * Which links answer. Each route has its own colour and icon, so every mix
 * looks different: one icon per route that is up, a ring in the colour of the
 * one the readings come from, and a strip of all their colours underneath.
 */
@Composable
private fun LinkChip(hub: HubState, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Sb.c
    val link = hub.link
    val parts = routeLooks(hub.routes, c)
    val main = when (link) {
        Link.LIVE -> c.link; Link.CLOUD -> c.cloud; Link.THINGSPEAK -> c.ts
        Link.OFFLINE -> c.bad; Link.DEMO -> parts.firstOrNull()?.color ?: c.warn
    }
    val text = when {
        parts.size == 3 -> "All 3 links"
        parts.isNotEmpty() -> parts.joinToString(" + ") { it.name }
        link == Link.OFFLINE -> "Not connected"
        link == Link.DEMO -> "Demo data"
        else -> "Connecting"
    }
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Row(
        modifier
            .defaultMinSize(minHeight = 36.dp)
            .then(if (pressed) Modifier.clip(RoundedCornerShape(18.dp)).background(c.base).sunk(c, 18.dp) else Modifier.bump(c, 18.dp, ring = if (parts.isNotEmpty()) main.soft(0.34f) else null))
            .press(true, Sfx.OPEN, src, onClick)
            .drawBehind {
                // The colour strip: one segment per route that answers.
                if (parts.size > 1) {
                    val h = 3.dp.toPx()
                    val w = (size.width - 26.dp.toPx()) / parts.size
                    parts.forEachIndexed { k, p ->
                        drawRoundRect(
                            p.color, topLeft = Offset(13.dp.toPx() + k * w, size.height - h - 3.dp.toPx()),
                            size = androidx.compose.ui.geometry.Size(w - 2.dp.toPx(), h),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2),
                        )
                    }
                }
            }
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (parts.isEmpty()) Ico(Ic.wifi(false), 17.dp, if (link == Link.OFFLINE) c.bad else c.warn)
        parts.forEach { p -> Ico(p.icon, 16.dp, p.color, Modifier.breathe(p.color == main, 2200)) }
        T(text, Modifier.padding(start = 2.dp), size = 0.8f, weight = FontWeight.Bold, color = if (parts.size == 1) main else c.ink, fit = true)
    }
}

// ---------- bottom nav ----------

private class TabDef(val screen: Screen, val label: String, val icon: ImageVector, val cc: (SbColors) -> Color)

private val TABS = listOf(
    TabDef(Screen.OVERVIEW, "Overview", Ic.grid) { it.fill },
    TabDef(Screen.CHARTS, "Charts", Ic.chart) { it.humid },
    TabDef(Screen.SETTINGS, "Settings", Ic.sliders) { it.cal },
    TabDef(Screen.SIM, "Simulation", Ic.flask) { it.sim },
    TabDef(Screen.ALERTS, "Alerts", Ic.bell) { it.tamper },
)

@Composable
private fun BottomNav(vm: AppViewModel, hub: HubState) {
    val c = Sb.c
    // The gear pages belong to no tab.
    val current = vm.screen.takeIf { it.ordinal < Screen.APP.ordinal }
    val unread = hub.unread
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(
                    androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, c.shD.soft(0.6f))),
                    topLeft = Offset(0f, -14.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width, 14.dp.toPx()),
                )
            }
            .background(c.base)
            .navigationBarsPadding()
            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        TABS.filter { it.screen != Screen.SIM || vm.operator }.forEach { t ->
            NavItem(t.icon, t.label, current == t.screen, t.cc(c), if (t.screen == Screen.ALERTS) unread else 0) {
                vm.go(t.screen)
            }
        }
    }
}

@Composable
private fun RowScope.NavItem(icon: ImageVector, label: String, selected: Boolean, cc: Color, badge: Int, onClick: () -> Unit) {
    val c = Sb.c
    Box(Modifier.weight(1f)) {
        Column(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 56.dp)
                .then(if (selected) Modifier.ring(cc.soft(0.26f), 14.dp).clip(RoundedCornerShape(14.dp)).background(c.base).sunk(c, 14.dp) else Modifier.clip(RoundedCornerShape(14.dp)))
                .press(!selected, Sfx.TAB, onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
        ) {
            val t = if (selected) cc else c.ink2
            Ico(icon, 22.dp, t)
            T(label, size = 9.8f / 14f, weight = FontWeight.Bold, color = t, maxLines = 1)
        }
        if (badge > 0) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(15.dp.roundToPx(), 3.dp.roundToPx()) }
                    .defaultMinSize(minWidth = 20.dp)
                    .height(20.dp)
                    .brutal(c, 6.dp, c.tamper, off = 2.dp)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) { T(if (badge > 99) "99+" else badge.toString(), size = 11f / 14f, color = Color.White, maxLines = 1, lineHeight = 1f) }
        }
    }
}

// ---------- start page ----------

/**
 * The start page, on every start of the app and from App settings: the logo,
 * the two roles, demo mode, and how the bins answer. Nothing sounds or posts
 * from here; alerts start once a role is picked.
 */
@Composable
private fun Gate(vm: AppViewModel, hub: HubState) {
    val c = Sb.c
    var play by remember { androidx.compose.runtime.mutableIntStateOf(1) }
    ScrollPage {
        Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(124.dp).well(c, 62.dp).press(true, Sfx.LOGO) { play++ }, contentAlignment = Alignment.Center) {
                SmartBinLogo(96.dp, play)
            }
            T("SMART-BIN", size = 1.65f, spacing = 0.04f)
            Hint("Pick how you are using the system today. Operator asks for a PIN. Collector is the default.", Modifier.widthIn(max = 290.dp), align = androidx.compose.ui.text.style.TextAlign.Center)
        }
        RoleCard(Ic.sliders, "Admin / Operator", listOf("Charts and history", "Lock, unlock, reset tamper", "Calibration", "Simulation controls", "Add and edit bins")) {
            vm.pickRole(Role.OPERATOR, first = true)
        }
        RoleCard(Ic.truck, "Collector", listOf("Which bin to empty first", "Charts, alerts and the collect PIN", "Start and finish a pickup", "Lock and unlock a bin", "Pair a bin to Wi-Fi over Bluetooth", "Settings are read only")) {
            vm.pickRole(Role.COLLECTOR, first = true)
        }
        // Demo mode is turned on or off only here.
        Row(
            Modifier.fillMaxWidth().well(c, 18.dp).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Btn(
                if (hub.demoOn) "DEMO ON" else "DEMO OFF", { vm.toggleDemo() }, icon = Ic.flask, look = Look.TINT,
                cc = if (hub.demoOn) c.warn else c.ink2, small = true, pressedIn = hub.demoOn, sound = Sfx.TAP,
            )
            T(
                if (hub.demoOn) "Three made-up bins sit next to your real ones and act out every alert by themselves."
                else "Turn DEMO on to add three made-up bins that show every alert by themselves.",
                Modifier.weight(1f), size = 0.8f, weight = FontWeight.SemiBold, color = c.ink2,
            )
        }
        val real = hub.bins.filter { !it.demo }
        if (real.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().well(c, 16.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val anyIp = real.any { it.ip.isNotBlank() }
                StartLine(
                    Ic.wifi(true), "Local Wi-Fi", c.link,
                    when { real.any { it.source == smartbin.data.Source.LIVE } -> true; !anyIp -> null; else -> false },
                    if (anyIp) "Trying" else "No address",
                )
                StartLine(Ic.cloud, "Firebase", c.cloud, hub.fbUp, if (hub.fbUp == null) "Not set up" else "Trying")
                StartLine(Ic.chart, "ThingSpeak", c.ts, hub.tsUp, if (hub.tsUp == null) "Not set up" else "Trying")
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

@Composable
private fun RoleCard(icon: ImageVector, title: String, points: List<String>, onClick: () -> Unit) {
    val c = Sb.c
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (pressed) Modifier.ring(c.accentSoft, 18.dp).clip(RoundedCornerShape(18.dp)).background(c.base).sunk(c, 18.dp) else Modifier.bump(c, 18.dp))
            .press(true, Sfx.CONFIRM, src, onClick)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Ico(icon, 34.dp, c.accent)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            T(title, size = 1.05f)
            Column {
                points.forEach { p -> T("•  $p", size = 0.84f, weight = FontWeight.Medium, color = c.ink2, lineHeight = 1.6f) }
            }
        }
    }
}

/** The bin's colour on the card and in the tiles: the status colour, or how full it is. */
fun statusColor(b: Bin, c: SbColors): Color = when (b.reading.status) {
    BinStatus.CALIBRATING, BinStatus.COLLECTING -> c.cal
    BinStatus.TAMPER -> c.bad
    BinStatus.LOCKED -> c.sim
    BinStatus.OK -> levelColor(b, c)
}

fun levelColor(b: Bin, c: SbColors): Color {
    val p = b.reading.fill
    return when {
        p >= b.cal.v("fill_th") -> c.bad
        p >= 50 -> c.warn
        else -> c.ok
    }
}

/** A short line for a stale bin: when its last reading came. */
fun lastSeen(b: Bin): String = if (b.lastSeen <= 0) "No reading yet" else
    "Last reading " + java.text.SimpleDateFormat("H:mm", java.util.Locale.getDefault()).format(java.util.Date(b.lastSeen))
