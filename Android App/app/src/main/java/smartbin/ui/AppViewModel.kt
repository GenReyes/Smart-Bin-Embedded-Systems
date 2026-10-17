package smartbin.ui

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import smartbin.SmartBinApp
import smartbin.data.AlertKind
import smartbin.data.AlertRow
import smartbin.data.Bin
import smartbin.data.BinBle
import smartbin.data.Cal
import smartbin.data.Cloud
import smartbin.data.DEFAULT_SIM
import smartbin.data.FirebaseConfig
import smartbin.data.Demo
import smartbin.data.Hub
import smartbin.data.OperatorLock
import smartbin.data.HubEvent
import smartbin.data.Routes
import smartbin.data.Role
import smartbin.data.Source
import smartbin.data.v
import smartbin.notify.Alerts
import smartbin.notify.Feel
import smartbin.notify.Haptics
import smartbin.notify.Sfx
import java.util.Calendar

/** The five tabs, then the pages opened from the gear key: app settings, Bluetooth setup and Connection. */
enum class Screen { OVERVIEW, CHARTS, SETTINGS, SIM, ALERTS, APP, BT, NET }

enum class BinView { AUTO, GRID, CARDS }
enum class BinFilter { ALL, ACTION, MODES }

/** Which list a bin picker sheet chooses for. */
enum class PickFor(val title: String) { CHART("Fill level chart"), ENV("Environment chart"), SET("Settings"), SIM("Simulation") }

enum class EnvMode { ALL, TEMP, HUMID, GAS, PRESS }

enum class AlertRange(val label: String) { TODAY("Today"), WEEK("7 days"), MONTH("30 days"), ANY("Any") }

enum class Perm { ON, OFF, BLOCKED }

sealed interface Overlay {
    data object Attention : Overlay
    data class BinEdit(val id: String) : Overlay
    data class BinDetail(val id: String) : Overlay
    data class Pick(val forWhat: PickFor) : Overlay
    data object AddBins : Overlay
    data object ClearAlerts : Overlay
    data object Wifi : Overlay
    data object NotifPre : Overlay
    data class PinUnlock(val first: Boolean) : Overlay
    data class PinSet(val first: Boolean, val change: Boolean) : Overlay
    data object PinChange : Overlay
    data class BtCode(val id: String) : Overlay
    /** Bins found over Bluetooth, to pick the one to set up. */
    data object BtDevices : Overlay
}

/**
 * What the screens show besides the bins themselves: the tab, the open sheet,
 * the role, the theme, the choices on each page and the edits not saved yet.
 * The bins, their readings and the alerts live in [Hub].
 */
/** A cloud command's answer: confirmed ([ok]) or timed out, and when. */
data class Settled(val cmd: String, val ok: Boolean, val at: Long)

/** How long a key flashes after its cloud command is answered. */
const val SETTLE_MILLIS = 1100L

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Hub.store()

    /** Cloud commands just confirmed or timed out, by bin, while their key flashes. */
    val settled = mutableStateMapOf<String, Settled>()

    init {
        // A cloud command's key unlocks when the bin confirms it, or when it waited too long.
        viewModelScope.launch {
            Hub.events.collect { e ->
                if (e is HubEvent.CommandResult) {
                    // The key flashes green or red for a moment, then lets go.
                    val s = Settled(e.cmd, e.ok, System.currentTimeMillis())
                    settled[e.binId] = s
                    launch {
                        delay(SETTLE_MILLIS)
                        if (settled[e.binId] == s) settled.remove(e.binId)
                    }
                    if (e.ok) {
                        Feel.event(Sfx.CMD_OK)
                        say("${e.name} confirmed ${e.cmd}.")
                    } else {
                        Feel.event(Sfx.CMD_FAIL)
                        say(e.why?.let { "${e.name} refused ${e.cmd}: $it." } ?: "${e.name} did not confirm ${e.cmd}. Check the bin, then try again.")
                    }
                }
            }
        }
    }

    // ---------- whole app ----------

    // Every start opens on the start page with no role picked. Operator is never
    // remembered, and a return after a while away drops it to collector.
    var role by mutableStateOf<Role?>(null)
        private set
    val lock = OperatorLock(store)
    val operator get() = role != Role.COLLECTOR
    var screen by mutableStateOf(Screen.OVERVIEW)
    /** The tab the gear key was pressed on, so back returns there. */
    private var beforeApp = Screen.OVERVIEW
    /**
     * False on the start page. A new view model means the app was closed or
     * Android ended it, so the start page shows again. Coming back from the
     * background keeps this one, and the page the user left.
     */
    var started by mutableStateOf(false)

    /** Back to the start page, where demo mode is turned on or off. Operator needs the PIN again after. */
    fun toStart() {
        if (role == Role.OPERATOR) Feel.event(Sfx.OP_OUT)
        if (editing) viewModelScope.launch { Hub.setCalibration(null) }
        edit = null
        dirty = false
        overlay = null
        fullscreen = null
        screen = Screen.OVERVIEW
        role = null
        started = false
        syncInMain()
    }

    /** The DEMO key on the start page. */
    fun toggleDemo() {
        val on = !Hub.state.value.demoOn
        Hub.setDemo(on)
        demoSpeed = Demo.speed
        demoTick++
        Feel.event(if (on) Sfx.DEMO else Sfx.SWITCH_OFF)
    }

    /** Tells [Hub] whether the user is inside the main screens, which is when alerts may be posted. */
    fun syncInMain() {
        Hub.inMain = started && role != null
    }

    override fun onCleared() {
        // The app was closed for good (back out of it, or the activity finished).
        Hub.inMain = false
        ble.release()
        super.onCleared()
    }
    var overlay by mutableStateOf<Overlay?>(null)
    var theme by mutableStateOf(runCatching { ThemeMode.valueOf(store.string(SmartBinApp.KEY_THEME, "AUTO")) }.getOrDefault(ThemeMode.AUTO))
        private set
    var toast by mutableStateOf<String?>(null)
        private set
    var toastSeq by mutableIntStateOf(0)
        private set
    private var toastJob: Job? = null

    fun say(message: String) {
        toast = message
        toastSeq++
        toastJob?.cancel()
        toastJob = viewModelScope.launch {
            delay(2200)
            toast = null
        }
    }

    /** Any touch on the screen clears the notice, so it never sits over a key. */
    fun hideToast() {
        if (toast == null) return
        toastJob?.cancel()
        toast = null
    }

    fun go(s: Screen) {
        overlay = null
        fullscreen = null
        if (s == Screen.APP && screen.ordinal < Screen.APP.ordinal) beforeApp = screen
        screen = if (s == Screen.SIM && !operator) Screen.OVERVIEW else s
    }

    /** Back from a page: Bluetooth and Connection go to the gear page, the gear page to its tab, a tab to Overview. */
    fun back() = when (screen) {
        Screen.BT, Screen.NET -> go(Screen.APP)
        Screen.APP -> go(beforeApp)
        else -> go(Screen.OVERVIEW)
    }

    fun toggleTheme(nowDark: Boolean) = setThemeMode(if (nowDark) ThemeMode.LIGHT else ThemeMode.DARK)

    fun setThemeMode(m: ThemeMode) {
        theme = m
        store.putString(SmartBinApp.KEY_THEME, m.name)
    }

    /** Asking for operator opens the PIN sheet. Collector needs nothing. */
    fun pickRole(r: Role, first: Boolean = false) {
        if (r == role && !first) return
        if (r == Role.OPERATOR) {
            opPin = ""; pinNew = ""; pinMsg = null
            overlay = if (lock.hasPin) Overlay.PinUnlock(first) else Overlay.PinSet(first, change = false)
            return
        }
        setRole(r, first)
    }

    private fun setRole(r: Role, first: Boolean) {
        val had = role != null
        if (role == Role.OPERATOR && r == Role.COLLECTOR) Feel.event(Sfx.OP_OUT)
        role = r
        started = true
        syncInMain()
        // Only "a role was picked" is kept; the next start is always collector.
        store.putString(SmartBinApp.KEY_ROLE, Role.COLLECTOR.name)
        if (r == Role.COLLECTOR) {
            if (editing) viewModelScope.launch { Hub.setCalibration(null) }
            edit = null
            dirty = false
            if (screen == Screen.SIM) screen = Screen.OVERVIEW
        }
        if (first || !had) {
            screen = Screen.OVERVIEW
            if (notifications != Perm.ON && !store.bool(SmartBinApp.KEY_ASKED, false)) overlay = Overlay.NotifPre
        } else {
            say("Signed in as ${if (r == Role.OPERATOR) "operator" else "collector"}.")
        }
    }

    // ---------- operator PIN ----------

    /** The digits typed so far, the first entry of a new PIN, and the line under the dots. */
    var opPin by mutableStateOf("")
    var pinNew by mutableStateOf("")
    var pinMsg by mutableStateOf<Pair<Boolean, String>?>(null)
    /** Set when the app went to the background, to drop operator after AUTO_LOCK. */
    private var awaySince = 0L

    fun pinKey(d: Char) {
        if (opPin.length < OperatorLock.MAX) opPin += d
    }

    fun pinBack() {
        opPin = opPin.dropLast(1)
    }

    fun pinUnlock(first: Boolean) {
        val wait = lock.waitLeft()
        if (wait > 0) {
            pinMsg = false to "Too many wrong PINs. Try again in ${(wait + 999) / 1000} s."
            return
        }
        if (lock.check(opPin)) {
            overlay = null
            opPin = ""
            Feel.event(Sfx.OP_IN)
            setRole(Role.OPERATOR, first)
        } else {
            opPin = ""
            val left = lock.waitLeft()
            Feel.event(if (left > 0) Sfx.PIN_LOCKOUT else Sfx.PIN_WRONG)
            pinMsg = false to if (left > 0) "Wrong PIN. Locked for ${(left + 999) / 1000} s." else "Wrong PIN. ${lock.triesLeft()} tries before a wait."
        }
    }

    /** Making or changing the PIN: type it, then type it again. */
    fun pinSave(first: Boolean, change: Boolean) {
        if (!OperatorLock.valid(opPin)) {
            pinMsg = false to "Use ${OperatorLock.MIN} to ${OperatorLock.MAX} digits."
            return
        }
        if (pinNew.isEmpty()) {
            pinNew = opPin
            opPin = ""
            pinMsg = true to "Type the same PIN again."
            return
        }
        if (opPin != pinNew) {
            opPin = ""; pinNew = ""
            Feel.event(Sfx.PIN_WRONG)
            pinMsg = false to "The two PINs differ. Start again."
            return
        }
        lock.set(opPin)
        opPin = ""; pinNew = ""; pinMsg = null
        overlay = null
        Feel.event(if (change) Sfx.PIN_SET else Sfx.OP_IN)
        if (change) say("Operator PIN changed.") else setRole(Role.OPERATOR, first)
    }

    /** Changing the PIN asks for the old one first. */
    fun changePin() {
        opPin = ""; pinNew = ""; pinMsg = null
        overlay = Overlay.PinChange
    }

    fun pinChangeCheck() {
        val wait = lock.waitLeft()
        if (wait > 0) {
            pinMsg = false to "Too many wrong PINs. Try again in ${(wait + 999) / 1000} s."
            return
        }
        if (!lock.check(opPin)) {
            opPin = ""
            val left = lock.waitLeft()
            Feel.event(if (left > 0) Sfx.PIN_LOCKOUT else Sfx.PIN_WRONG)
            pinMsg = false to if (left > 0) "Wrong PIN. Locked for ${(left + 999) / 1000} s." else "Wrong PIN. ${lock.triesLeft()} tries before a wait."
            return
        }
        opPin = ""; pinMsg = null
        overlay = Overlay.PinSet(first = false, change = true)
    }

    fun backgrounded() {
        awaySince = System.currentTimeMillis()
    }

    /** Back after more than AUTO_LOCK away, operator drops to collector. */
    fun foregrounded() {
        val away = awaySince
        awaySince = 0L
        if (away > 0 && role == Role.OPERATOR && System.currentTimeMillis() - away > AUTO_LOCK) {
            setRole(Role.COLLECTOR, first = false)
            say("Operator locked after 2 min away. Collector mode.")
        }
    }

    fun openFromNotification() {
        if (role == null) return
        started = true
        overlay = null
        fullscreen = null
        screen = Screen.OVERVIEW
    }

    // ---------- sound and feel, from Garbage-Guard ----------

    var sounds by mutableStateOf(Feel.sounds)
        private set
    var haptics by mutableStateOf(Feel.haptics)
        private set
    var strength by mutableStateOf(Haptics.strength)
        private set
    var keepAwake by mutableStateOf(store.bool(SmartBinApp.KEY_AWAKE, false))
        private set

    fun toggleSounds() {
        sounds = !sounds
        Feel.sounds = sounds
        store.putBool(SmartBinApp.KEY_SOUNDS, sounds)
    }

    fun toggleHaptics() {
        haptics = !haptics
        Feel.haptics = haptics
        store.putBool(SmartBinApp.KEY_HAPTICS, haptics)
        if (haptics) Haptics.play(Haptics.Buzz.SAVED)
    }

    fun chooseStrength(s: Haptics.Strength) {
        strength = s
        Haptics.strength = s
        store.putString(SmartBinApp.KEY_STRENGTH, s.name)
        Haptics.play(Haptics.Buzz.SAVED)
    }

    fun toggleAwake() {
        keepAwake = !keepAwake
        store.putBool(SmartBinApp.KEY_AWAKE, keepAwake)
    }

    // ---------- battery ----------

    /** True when Android lets the app run in the background with no battery limits. */
    var unrestricted by mutableStateOf(batteryFree())
        private set

    private fun batteryFree(): Boolean {
        val ctx = getApplication<Application>()
        return ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true
    }

    /** Asks Android for unrestricted battery, or opens the list when the phone has no such prompt. */
    fun askUnrestricted() {
        val ctx = getApplication<Application>()
        val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        val ok = runCatching { ctx.startActivity(ask.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess ||
            runCatching { ctx.startActivity(list.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (!ok) say("This phone has no battery setting for apps.")
    }

    /** Opens the app's own page in Android settings, where some phones keep the battery choice. */
    fun openAppInfo() {
        val ctx = getApplication<Application>()
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // ---------- push alerts ----------

    var notifications by mutableStateOf(if (Alerts.allowed(app)) Perm.ON else Perm.OFF)
        private set

    fun refreshSystemState() {
        unrestricted = batteryFree()
        val on = Alerts.allowed(getApplication())
        notifications = when {
            on -> Perm.ON
            store.bool(SmartBinApp.KEY_ASKED, false) -> Perm.BLOCKED
            else -> Perm.OFF
        }
    }

    fun permissionAnswered(granted: Boolean) {
        store.putBool(SmartBinApp.KEY_ASKED, true)
        notifications = if (granted) Perm.ON else Perm.BLOCKED
        say(if (granted) "Push alerts are on." else "Push alerts are off. You can change this in Settings.")
    }

    fun notNow() {
        store.putBool(SmartBinApp.KEY_ASKED, true)
        overlay = null
    }

    // ---------- overview ----------

    var binView by mutableStateOf(BinView.AUTO)
    var binFilter by mutableStateOf(BinFilter.ALL)
    var prioAll by mutableStateOf(false)
    var addN by mutableIntStateOf(1)

    fun command(id: String, cmd: String) {
        viewModelScope.launch {
            val b = Hub.bin(id) ?: return@launch
            if (b.demo) Hub.pauseTour()
            val problem = Hub.command(id, cmd)
            if (problem != null) {
                Feel.event(Sfx.DENIED)
                say(problem)
                return@launch
            }
            val n = b.name
            val msg = when (cmd) {
                "LOCK" -> "$n is locked."
                "UNLOCK" -> "$n is unlocked."
                "CLEAR_TAMPER" -> "Tamper reset on $n."
                "COLLECT" -> "Pickup started on $n. The lid is open."
                "COLLECT_DONE" -> "$n is empty and back to normal."
                else -> "Sent to $n."
            }
            val waiting = Hub.state.value.pending[id]
            if (waiting != null) {
                val via = if (waiting.via == Source.THINGSPEAK) "ThingSpeak" else "Firebase"
                Feel.event(Sfx.CLOUD)
                say("${waiting.cmd} sent to $n via $via. Waiting for the bin.")
            } else say(msg)
        }
    }

    // Bin edit sheet
    var formName by mutableStateOf("")
    var formPlace by mutableStateOf("")
    var formIp by mutableStateOf("")
    var formCloud by mutableStateOf("")
    var formTsCh by mutableStateOf("")
    var formTsKey by mutableStateOf("")
    var formTbId by mutableStateOf("")
    var formTbKey by mutableStateOf("")

    fun openBinEdit(b: Bin) {
        formName = b.name
        formPlace = b.place
        formIp = b.ip
        formCloud = b.cloudId
        formTsCh = b.tsChannel
        formTsKey = b.tsKey
        formTbId = b.tbId
        formTbKey = b.tbKey
        overlay = Overlay.BinEdit(b.id)
    }

    fun saveBinEdit(id: String) {
        Hub.editBin(id, formName, formPlace, formIp, formCloud, formTsCh, formTsKey, formTbId, formTbKey)
        overlay = null
        say(
            "Saved ${Hub.bin(id)?.name ?: "the bin"}." + when {
                formIp.isNotBlank() -> " Reading it over Wi-Fi now."
                formTsCh.isNotBlank() -> " Reading it from ThingSpeak now."
                else -> ""
            }
        )
    }

    fun removeBin(id: String) {
        val name = Hub.bin(id)?.name ?: "The bin"
        if (calibBin() == id) {
            edit = null
            dirty = false
        }
        Hub.removeBin(id)
        overlay = null
        say("$name removed.")
    }

    fun addBins() {
        val n = addN
        Hub.addBins(n)
        overlay = null
        say("Added $n bin${if (n > 1) "s" else ""}. Tap SET to give each one an address.")
    }

    // ---------- charts ----------

    /** null means all bins. */
    var chartBin by mutableStateOf<String?>(null)
    var envBin by mutableStateOf<String?>(null)
    var envMode by mutableStateOf(EnvMode.ALL)
    var rangeMin by mutableIntStateOf(60)
    /** "fill" or "env" while the sideways chart is open. */
    var fullscreen by mutableStateOf<String?>(null)

    // ---------- settings ----------

    var selSet by mutableStateOf<String?>(null)
    var selSim by mutableStateOf<String?>(null)
    var edit by mutableStateOf<Cal?>(null)
        private set
    var dirty by mutableStateOf(false)
        private set
    var open by mutableStateOf(setOf("limits"))
    var pinShown by mutableStateOf(false)
    var tref by mutableStateOf(27.5)
    var known by mutableStateOf(1000.0)

    private fun calibBin() = Hub.state.value.calibBin
    val editing get() = edit != null && calibBin() != null

    fun binFor(id: String?, bins: List<Bin>): Bin? = bins.firstOrNull { it.id == id } ?: bins.firstOrNull()

    fun toggleSection(k: String) {
        open = if (k in open) open - k else open + k
    }

    fun setCalibration(on: Boolean, b: Bin) {
        viewModelScope.launch {
            if (on) {
                Hub.setCalibration(b.id)
                edit = b.cal
                dirty = false
                Feel.event(Sfx.CALIB)
                say("Calibration mode on for ${b.name}.")
            } else {
                val lost = dirty
                Hub.setCalibration(null)
                edit = null
                dirty = false
                say(if (lost) "Calibration off. Unsaved changes were dropped." else "Calibration off. ${b.name} is back to normal.")
            }
        }
    }

    fun step(key: String, dir: Int) {
        val f = FIELDS.getValue(key)
        when (key) {
            "tref" -> tref = round2((tref + f.step * dir).coerceIn(f.min, f.max))
            "known" -> known = round2((known + f.step * dir).coerceIn(f.min, f.max))
            else -> {
                val e = edit ?: return
                edit = e + (key to round2((e.v(key) + f.step * dir).coerceIn(f.min, f.max)))
                dirty = true
            }
        }
    }

    fun setPin(text: String) {
        val e = edit ?: return
        val digits = text.filter { it.isDigit() }.take(4)
        edit = e + ("collect_pin" to (digits.toIntOrNull() ?: 0).toDouble())
        dirty = true
    }

    fun toggleAutoLock() {
        val e = edit ?: return
        edit = e + ("auto_lock" to if (e.v("auto_lock") > 0) 0.0 else 1.0)
        dirty = true
    }

    fun useNow(key: String, b: Bin) {
        val e = edit ?: return
        var v = Math.round(b.reading.distance * 10) / 10.0
        if (key == "d_full") v = minOf(v, e.v("d_empty") - 1)
        edit = e + (key to v)
        dirty = true
        say("Took ${fmt(v, 1)} cm from the sensor.")
    }

    fun setOffset(b: Bin) {
        val e = edit ?: return
        val off = Math.round((tref - b.reading.tempRaw).coerceIn(-10.0, 10.0) * 10) / 10.0
        edit = e + ("temp_off" to off)
        dirty = true
        say("Offset set to ${fmt(off, 1)} C. Press SAVE to keep it.")
    }

    fun binCommand(b: Bin, cmd: String, extra: Map<String, Any>, ok: String) {
        viewModelScope.launch {
            if (b.demo) Hub.pauseTour()
            val problem = Hub.command(b.id, cmd, extra)
            if (problem != null) Feel.event(Sfx.DENIED)
            say(problem ?: ok)
        }
    }

    /** The bin's touchscreen: four crosses to press on the bin, or back to the firmware's values. Wi-Fi only. */
    fun touchCal(b: Bin, reset: Boolean) {
        if (!b.demo && b.source != Source.LIVE) {
            Feel.event(Sfx.DENIED)
            say("Touch calibration needs ${b.name} on the same Wi-Fi as the phone.")
            return
        }
        Feel.event(if (reset) Sfx.CONFIRM else Sfx.CALIB)
        binCommand(
            b, if (reset) "TOUCH_CAL_RESET" else "TOUCH_CAL", emptyMap(),
            if (reset) "${b.name}'s touchscreen is back to the default values."
            else "${b.name} shows 4 crosses. Press the centre of each one on the bin.",
        )
    }

    /** Gives every bin on Wi-Fi the saved Firebase account, so they write to the same database. */
    fun sendCloudToBins() {
        if (!operator) {
            Feel.event(Sfx.DENIED)
            say("Only an operator can send the cloud account to the bins.")
            return
        }
        viewModelScope.launch {
            val (ok, tried) = Hub.sendCloudToBins()
            when {
                tried == 0 -> { Feel.event(Sfx.DENIED); say("No bin is on Wi-Fi with an address, so nothing was sent.") }
                ok == tried -> { Feel.event(Sfx.SAVED); say("Sent the cloud account to $ok bin${if (ok == 1) "" else "s"}.") }
                else -> { Feel.event(Sfx.CMD_FAIL); say("$ok of $tried bins took the cloud account. Check the others are on.") }
            }
        }
    }

    fun save(b: Bin) {
        val e = edit ?: return
        if (e.v("d_full") >= e.v("d_empty")) {
            Feel.event(Sfx.DENIED)
            say("Distance full must be smaller than distance empty.")
            return
        }
        viewModelScope.launch {
            val problem = Hub.saveCal(b.id, e)
            if (problem != null) {
                Feel.event(Sfx.DENIED)
                say(problem)
            } else {
                dirty = false
                Feel.event(Sfx.SAVED)
                say("Saved to ${b.name}.")
            }
        }
    }

    fun cancel(b: Bin) {
        edit = b.cal
        dirty = false
        say("Changes dropped.")
    }

    fun reload(b: Bin) {
        viewModelScope.launch {
            if (b.ip.isBlank()) {
                say(if (b.demo) "${b.name} is a demo bin." else "Give ${b.name} an address first.")
                return@launch
            }
            val problem = Hub.reload(b.id)
            if (problem == null && editing && calibBin() == b.id) {
                edit = Hub.bin(b.id)?.cal
                dirty = false
            }
            say(problem ?: "Read the latest values from ${b.name}.")
        }
    }

    /** Writes the bins' names, places and addresses to Downloads, or offers to share them on older phones. */
    fun exportBins(bins: List<Bin>) {
        val a = JSONArray()
        bins.forEach { b -> a.put(JSONObject().put("name", b.name).put("place", b.place).put("ip", b.ip).put("cloud_id", b.cloudId)) }
        val text = a.toString(2)
        val ctx = getApplication<Application>()
        if (Build.VERSION.SDK_INT >= 29) {
            val ok = runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "smartbin-bins.json")
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("no file")
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            }.isSuccess
            say(if (ok) "Bin list saved to Downloads." else "The bin list could not be saved.")
        } else {
            val send = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT, text)
            runCatching { ctx.startActivity(Intent.createChooser(send, "Export bin list").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    // ---------- Bluetooth Wi-Fi setup ----------

    /** 0 not paired, 1 paired, 2 joining, 3 joined, 4 Bluetooth off on the bin. */
    var bt by mutableIntStateOf(0)
    var ssid by mutableStateOf("")
    var pass by mutableStateOf("")
    var showPass by mutableStateOf(false)
    /** What is happening now, under the keys. */
    var btNote by mutableStateOf<String?>(null)
    var btBusy by mutableStateOf(false)
    /** The bins found nearby, and whether the search still runs. */
    val btFound = androidx.compose.runtime.mutableStateListOf<BinBle.Found>()
    var btScanning by mutableStateOf(false)
    /** The networks the bin can see. */
    var btNets by mutableStateOf<List<BinBle.Net>>(emptyList())
    /** The address and network the bin joined. */
    var btIp by mutableStateOf("")
    var btJoined by mutableStateOf("")
    /** The bin being set up: its Bluetooth name, and whether it is the demo stand-in. */
    var btName by mutableStateOf("")
    var btDemo by mutableStateOf(false)
    /** Set when the phone and the bin disagree about an old pairing. */
    var btStale by mutableStateOf(false)

    private val ble by lazy { BinBle(getApplication()) }
    private var btJob: Job? = null
    private var btWait: Job? = null

    /**
     * Pairing a demo bin uses a stand-in for its screen: a fresh random code
     * each time, gone after 60 s, and 3 wrong codes stop pairing for 60 s, the
     * same rules the bin's firmware keeps.
     */
    var btCode by mutableStateOf("")
        private set
    var btCodeAt by mutableLongStateOf(0L)
        private set
    var btEntry by mutableStateOf("")
    var btMsg by mutableStateOf<Pair<Boolean, String>?>(null)
    private var btFails = 0
    private var btWaitUntil = 0L

    fun btPermissions() = ble.permissions()
    fun btAllowed() = ble.allowed()

    private fun btReset() {
        btWait?.cancel()
        bt = 0
        btIp = ""
        btJoined = ""
        btNets = emptyList()
        btStale = false
    }

    /** PAIR A BIN. A demo bin walks through the stand-in; anything else looks for real bins nearby. */
    fun btPair(b: Bin?) {
        btReset()
        if (b != null && b.demo) {
            val wait = btWaitUntil - System.currentTimeMillis()
            if (wait > 0) {
                Feel.event(Sfx.DENIED)
                say("${b.name} stopped pairing after 3 wrong codes. Try again in ${(wait + 999) / 1000} s.")
                return
            }
            btDemo = true
            btName = b.name
            btFails = 0
            btEntry = ""
            btMsg = null
            btNewCode()
            overlay = Overlay.BtCode(b.id)
            return
        }
        btDemo = false
        when {
            !ble.present -> { Feel.event(Sfx.DENIED); btNote = "This phone has no Bluetooth." }
            !ble.allowed() -> { Feel.event(Sfx.DENIED); btNote = "Allow Nearby devices (Bluetooth) for Smart-Bin, then press PAIR A BIN again." }
            !ble.on -> { Feel.event(Sfx.DENIED); btNote = "Turn Bluetooth on, then press PAIR A BIN again." }
            else -> btSearch()
        }
    }

    /** Looks for bins for 8 s and lists them in a sheet. */
    fun btSearch() {
        btFound.clear()
        btScanning = true
        overlay = Overlay.BtDevices
        btNote = "Looking for bins nearby. On the bin, tap i, then WI-FI SETUP, and type the Collect PIN."
        btJob?.cancel()
        btJob = viewModelScope.launch {
            try {
                ble.scan(8_000) { f -> if (btFound.none { it.address == f.address }) btFound.add(f) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                btNote = e.message ?: "Could not look for bins."
            } finally {
                btScanning = false
            }
            if (btFound.isEmpty()) btNote = "No bin found. Check that the bin shows WI-FI SETUP and stands close by" +
                (if (Build.VERSION.SDK_INT < 31) ", and that Location is on." else ".")
        }
    }

    /** A bin picked in the sheet: connect, pair with the code Android asks for, then read its networks. */
    fun btConnect(f: BinBle.Found) {
        overlay = null
        btJob?.cancel()
        btName = f.name
        btBusy = true
        btNote = "Connecting to ${f.name}..."
        btJob = viewModelScope.launch {
            try {
                ble.onDrop = { viewModelScope.launch { btDropped() } }
                ble.onStat = { j -> viewModelScope.launch { btStatus(j) } }
                ble.connect(f.device)
                btNote = "${f.name} shows a 6-digit code now. Type it in the box Android opens. The code lasts 60 s."
                ble.secure()
                ble.listen()
                Feel.event(Sfx.BT_PAIRED)
                bt = 1
                btNote = "Paired with ${f.name}. The link is encrypted. Pick the network next."
                btLoadNets()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: BinBle.StaleBond) {
                ble.close()
                btStale = true
                Feel.event(Sfx.BT_WRONG)
                btNote = "${f.name} keeps dropping the link. The phone most likely remembers it from an earlier pairing. Press FORGET AND PAIR AGAIN, then on the bin open WI-FI SETUP again."
            } catch (e: Exception) {
                ble.close()
                Feel.event(Sfx.BT_WRONG)
                btNote = "Pairing did not finish (${e.message}). If the code was wrong, the bin shows a new one; after 3 wrong codes it waits 60 s."
            } finally {
                btBusy = false
            }
        }
    }

    /** Makes the phone forget the bin, then searches again. Opens Android's Bluetooth settings if that is not allowed. */
    fun btForget() {
        val d = ble.device ?: btFound.firstOrNull { it.name == btName }?.device
        btStale = false
        if (d != null && ble.forget(d)) {
            Feel.event(Sfx.CONFIRM)
            say("The phone forgot $btName. Pair it again.")
            btSearch()
        } else {
            say("Remove $btName in Android's Bluetooth settings, then press PAIR A BIN.")
            runCatching {
                getApplication<Application>().startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    /** The bin scans when WI-FI SETUP opens; an empty list means it is not done, so it asks again after 5 s. */
    private suspend fun btLoadNets() {
        repeat(3) { k ->
            val nets = runCatching { ble.networks() }.getOrDefault(emptyList())
            if (nets.isNotEmpty()) {
                btNets = nets
                if (ssid.isBlank()) ssid = nets.first().ssid
                return
            }
            if (k < 2) {
                btNote = "The bin is still looking for networks. Asking again in 5 s. You can also type the name."
                delay(5_000)
                if (bt != 1) return
            }
        }
        btNote = "The bin found no networks. Check the router is on 2.4 GHz and close by, press SCAN, or type the name."
    }

    fun btScanNets() {
        if (btDemo) {
            overlay = Overlay.Wifi
            return
        }
        viewModelScope.launch {
            btBusy = true
            btLoadNets()
            btBusy = false
            if (btNets.isNotEmpty()) overlay = Overlay.Wifi
        }
    }

    fun btNewCode() {
        btCode = "%06d".format(java.security.SecureRandom().nextInt(1_000_000))
        btCodeAt = System.currentTimeMillis()
    }

    fun btKey(d: Char) {
        if (btEntry.length < BT_CODE_LEN) btEntry += d
    }

    fun btBack() {
        btEntry = btEntry.dropLast(1)
    }

    fun btCheck(b: Bin) {
        if (System.currentTimeMillis() - btCodeAt > BT_CODE_LIFE) {
            btNewCode()
            btEntry = ""
            Feel.event(Sfx.BT_WRONG)
            btMsg = false to "That code expired. The bin shows a new one."
            return
        }
        if (btEntry == btCode) {
            overlay = null
            btEntry = ""
            btCode = ""
            bt = 1
            btNets = DEMO_NETS
            Feel.event(Sfx.BT_PAIRED)
            btNote = "Paired with ${b.name} (demo). Pick a network. A name with \"fail\" in it shows what a failed join looks like."
            return
        }
        btEntry = ""
        btFails++
        if (btFails >= 3) {
            overlay = null
            btCode = ""
            btWaitUntil = System.currentTimeMillis() + 60_000L
            Feel.event(Sfx.BT_BLOCKED)
            say("3 wrong codes. ${b.name} stops pairing for 60 s.")
        } else {
            val left = 3 - btFails
            Feel.event(Sfx.BT_WRONG)
            btMsg = false to "Wrong code. $left ${if (left == 1) "try" else "tries"} left."
        }
    }

    fun btCancel() {
        overlay = null
        btEntry = ""
        btCode = ""
    }

    /** SEND WI-FI: the bin tries the network for up to 15 s and keeps its old one if it fails. */
    fun btSend() {
        val name = ssid.trim()
        when {
            name.isBlank() -> { Feel.event(Sfx.DENIED); say("Pick or type the Wi-Fi name first."); return }
            name.length > 32 || pass.length > 63 -> { Feel.event(Sfx.DENIED); say("The name can be 32 characters at most and the password 63."); return }
            pass.isNotEmpty() && pass.length < 8 -> { Feel.event(Sfx.DENIED); say("A Wi-Fi password has at least 8 characters. Leave it empty only for an open network."); return }
        }
        bt = 2
        btNote = "Sending the network to $btName and waiting for it to join..."
        btWait?.cancel()
        if (btDemo) {
            btWait = viewModelScope.launch {
                delay(1800)
                if (name.contains("fail", true)) btStatus(JSONObject().put("state", "FAILED").put("why", "Network not found"))
                else btStatus(JSONObject().put("state", "CONNECTED").put("ip", "192.168.1.${101 + (Hub.state.value.bins.indexOfFirst { it.id == selSet }.coerceAtLeast(0))}").put("ssid", name))
            }
            return
        }
        btWait = viewModelScope.launch {
            try {
                ble.join(name, pass)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                bt = 1
                Feel.event(Sfx.CMD_FAIL)
                btNote = "Could not send (${e.message}). Press SEND WI-FI again."
                return@launch
            }
            // Notifications can be missed or cut short, so the status is read every 2 s too.
            val end = System.currentTimeMillis() + 25_000
            while (bt == 2 && System.currentTimeMillis() < end) {
                delay(2_000)
                if (bt == 2 && ble.connected) runCatching { ble.readStat() }.getOrNull()?.let { btStatus(it) }
            }
            if (bt == 2) {
                bt = 1
                Feel.event(Sfx.WIFI_FAILED)
                btNote = "No answer from the bin after 25 s. Its touchscreen shows whether it joined. Press SEND WI-FI again, or PAIR A BIN."
            }
        }
    }

    /** One STAT message from the bin. */
    private fun btStatus(j: JSONObject) {
        if (bt != 2) return
        when (j.optString("state").uppercase()) {
            "CONNECTING" -> btNote = "$btName is joining ${ssid.trim()}. This takes up to 15 s."
            "CONNECTED" -> {
                val ip = j.optString("ip")
                if (ip.isBlank()) return
                btWait?.cancel()
                btIp = ip
                btJoined = j.optString("ssid").ifBlank { ssid.trim() }
                bt = 3
                Feel.event(Sfx.WIFI_JOINED)
                btNote = "Press ${btUseLabel()} to keep this address, then TURN BT OFF."
            }
            "FAILED" -> {
                btWait?.cancel()
                bt = 1
                Feel.event(Sfx.WIFI_FAILED)
                btNote = "$btName could not join: ${j.optString("why").ifBlank { "no reason given" }}. It went back to its old Wi-Fi. Check the name and password, then SEND WI-FI again."
            }
        }
    }

    /** The link dropped. While joining, the answer is read again after reconnecting. */
    private suspend fun btDropped() {
        when (bt) {
            1 -> {
                bt = 0
                Feel.event(Sfx.BIN_LOST)
                btNote = "$btName closed the Bluetooth link. On the bin, open WI-FI SETUP again, then PAIR A BIN."
            }
            2 -> {
                btNote = "The link dropped while $btName joins. Reconnecting to read the result..."
                val d = btFound.firstOrNull { it.name == btName }?.device ?: return
                runCatching {
                    ble.connect(d)
                    ble.listen()
                    btStatus(ble.readStat())
                }
            }
        }
    }

    /** The bin being set up is the picked one if it is real; otherwise it becomes a new bin. */
    private fun btTarget(): Bin? = Hub.bin(selSet ?: "")?.takeIf { !it.demo }

    fun btUseLabel(): String = when {
        btDemo -> "USE"
        btTarget() != null -> "USE FOR ${btTarget()!!.name.uppercase()}"
        else -> "ADD AS BIN ${btNumber()}".trim()
    }

    private fun btNumber() = Regex("(\\d+)\\s*$").find(btName)?.groupValues?.get(1) ?: ""

    fun btUse() {
        if (btIp.isBlank()) return
        if (btDemo) {
            Feel.event(Sfx.SAVED)
            say("$btName is a demo bin, so nothing was stored.")
            return
        }
        val t = btTarget()
        if (t != null) {
            Hub.editBin(t.id, t.name, t.place, btIp, t.cloudId)
            say("${t.name} will now be read at $btIp.")
        } else {
            val b = Hub.addRealBin(btNumber().padStart(2, '0').takeIf { btNumber().isNotEmpty() } ?: "", btIp)
            selSet = b.id
            say("Added ${b.name} at $btIp.")
        }
        Feel.event(Sfx.SAVED)
    }

    fun btOff() {
        btWait?.cancel()
        viewModelScope.launch {
            if (!btDemo) ble.off()
            bt = 4
            Feel.event(Sfx.BT_OFF)
            btNote = "Bluetooth is off on $btName. It stays on its Wi-Fi until someone opens WI-FI SETUP again."
        }
    }

    // ---------- simulation ----------

    fun simToggle(b: Bin, bit: Int) {
        val s = b.sim
        Hub.editSim(b.id, s.copy(mask = s.mask xor (1 shl bit)))
    }

    fun simValue(b: Bin, key: String, v: Double) {
        val s = b.sim
        Hub.editSim(b.id, s.copy(values = s.values + (key to v)))
    }

    fun simStep(b: Bin, key: String, dir: Int) {
        val f = SIM_STEP.getValue(key)
        val v = (b.sim.values[key] ?: DEFAULT_SIM[key] ?: 0.0) + f.step * dir
        simValue(b, key, Math.round(v.coerceIn(f.min, f.max) * 10) / 10.0)
    }

    fun applySim(b: Bin) {
        viewModelScope.launch {
            val problem = Hub.applySim(b.id)
            if (problem != null) {
                Feel.event(Sfx.DENIED)
                say(problem)
            } else {
                if (b.sim.mask > 0) Feel.event(Sfx.SIM)
                say(if (b.sim.mask > 0) "Simulation applied to ${b.name}." else "${b.name} is reading real sensors again.")
            }
        }
    }

    fun simAllAuto(b: Bin) {
        Hub.editSim(b.id, b.sim.copy(mask = 0))
        viewModelScope.launch {
            Hub.applySim(b.id)
            say("All rows back to AUTO.")
        }
    }

    // ---------- alerts ----------

    var akind by mutableStateOf(AlertKind.ALL)
    var arange by mutableStateOf(AlertRange.ANY)
    var alertShown by mutableIntStateOf(6)

    fun shown(all: List<AlertRow>): List<AlertRow> {
        val now = System.currentTimeMillis()
        val since = when (arange) {
            AlertRange.TODAY -> Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            AlertRange.WEEK -> now - 7 * DAY
            AlertRange.MONTH -> now - 30 * DAY
            AlertRange.ANY -> Long.MIN_VALUE
        }
        return all.filter { (akind == AlertKind.ALL || it.kind == akind) && it.at >= since }
    }

    fun scope(n: Int): String {
        val w = if (n == 1) akind.one else akind.many
        val r = when (arange) {
            AlertRange.TODAY -> " from today"; AlertRange.WEEK -> " from the last 7 days"
            AlertRange.MONTH -> " from the last 30 days"; AlertRange.ANY -> ""
        }
        return (if (akind == AlertKind.ALL && arange == AlertRange.ANY) "all $n $w" else "$n $w") + r
    }

    fun markShown(all: List<AlertRow>) {
        val s = shown(all)
        Hub.markRead(s.map { it.id }.toSet())
        say("Marked ${scope(s.size)} as read.")
    }

    fun markAll() {
        Hub.markRead(Hub.state.value.alerts.map { it.id }.toSet())
        overlay = null
        say("All alerts marked read.")
    }

    fun deleteShown(all: List<AlertRow>) {
        val s = shown(all)
        val t = scope(s.size)
        Hub.deleteAlerts(s.map { it.id }.toSet())
        overlay = null
        Feel.event(Sfx.DANGER)
        say("Deleted $t.")
    }

    // ---------- demo options ----------

    /** Demo fill speed as a multiple of real life. 60 is the normal demo. */
    var demoSpeed by mutableStateOf(Demo.speed)
        private set
    /** The pretend links live in [Hub], because the demo tour changes them too. */
    val demoLinks: Routes get() = Hub.state.value.demoLinks

    fun changeDemoSpeed(x: Double) {
        Hub.pauseTour()
        demoSpeed = x
        Hub.demoSpeed(x)
    }

    fun toggleDemoLink(which: Int) {
        Hub.pauseTour()
        val r = demoLinks
        val next = when (which) {
            0 -> r.copy(local = !r.local)
            1 -> r.copy(firebase = !r.firebase)
            else -> r.copy(thingspeak = !r.thingspeak)
        }
        Hub.demoLinks(next)
        val main = when {
            next.local -> "Demo bins now answer on Wi-Fi."
            next.firebase -> "Demo bins now answer through Firebase. LOCK, UNLOCK and COLLECT wait for the bin."
            next.thingspeak -> "Demo bins now answer through ThingSpeak. LOCK, UNLOCK and COLLECT wait for the bin."
            else -> "Demo bins show plain demo data again."
        }
        say(main)
    }

    /** Pretend cloud commands: false confirms them after 4 s, true lets them time out after 8 s. */
    fun setDemoNoConfirm(no: Boolean) {
        Hub.pauseTour()
        Hub.setDemoNoConfirm(no)
        say(if (no) "Cloud commands on demo bins will not be confirmed. Their keys give up after 8 s." else "Cloud commands on demo bins are confirmed after 4 s.")
    }

    fun demoEvent(b: Bin, e: Hub.DemoEvent) {
        if (!b.demo) {
            Feel.event(Sfx.DENIED)
            say("${b.name} is a real bin. Demo options only change demo bins.")
            return
        }
        Hub.pauseTour()
        Hub.demoEvent(b.id, e)
        say(
            when (e) {
                Hub.DemoEvent.FULL -> "${b.name} is now over its fill limit."
                Hub.DemoEvent.TAMPER -> "${b.name} was tipped. Reset it from its card."
                Hub.DemoEvent.AIR -> "Air quality spiked in ${b.name}. It settles by itself."
                Hub.DemoEvent.EMPTY -> "${b.name} is empty."
            }
        )
    }

    fun toggleDemoQuiet(b: Bin) {
        if (!b.demo) {
            Feel.event(Sfx.DENIED)
            say("${b.name} is a real bin. Demo options only change demo bins.")
            return
        }
        Hub.pauseTour()
        val quiet = !Hub.demoQuiet(b.id)
        Hub.setDemoQuiet(b.id, quiet)
        demoTick++
        say(if (quiet) "${b.name} stops answering. It shows as lost after 90 s." else "${b.name} answers again.")
    }

    /** Bumped when a demo choice kept in [Hub] changes, so the page redraws. */
    var demoTick by mutableIntStateOf(0)
        private set

    fun resetDemo() {
        Hub.resetDemo()
        demoSpeed = Demo.speed
        demoTick++
        Feel.event(Sfx.SAVED)
        say("Demo options are back to normal.")
    }

    // ---------- ThingSpeak ----------

    var tsUrl by mutableStateOf(Hub.thingSpeakUrl())
    var tsMsg by mutableStateOf<Pair<Boolean, String>?>(null)
    var tsBusy by mutableStateOf(false)

    fun tsTest() {
        val url = tsUrl.trim()
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            Feel.event(Sfx.DENIED)
            tsMsg = false to "The server address starts with https://."
            return
        }
        Hub.saveThingSpeak(url)
        tsBusy = true
        viewModelScope.launch {
            val (ok, problem) = Hub.testThingSpeak()
            tsBusy = false
            if (ok > 0 && problem == null) {
                Feel.event(Sfx.CLOUD)
                tsMsg = true to "$ok channel${if (ok == 1) "" else "s"} answered. Saved."
            } else {
                Feel.event(Sfx.DENIED)
                tsMsg = false to (if (ok > 0) "$ok answered. " else "") + (problem ?: "No channel answered.")
            }
        }
    }

    // ---------- Firebase ----------

    var fb by mutableStateOf(Hub.firebase())
    var fbShow by mutableStateOf(false)
    /** (passed, message) */
    var fbMsg by mutableStateOf<Pair<Boolean, String>?>(null)
    var fbBusy by mutableStateOf(false)

    fun fbTest(save: Boolean) {
        val cfg = fb
        Cloud.problemWith(cfg)?.let {
            Feel.event(Sfx.DENIED)
            fbMsg = false to (it + if (save) " Nothing was saved." else "")
            return
        }
        fbBusy = true
        viewModelScope.launch {
            val r = Hub.cloud.test(cfg)
            fbBusy = false
            r.onSuccess { n ->
                if (save) Hub.saveFirebase(cfg)
                Feel.event(if (save) Sfx.SAVED else Sfx.CLOUD)
                fbMsg = true to "Signed in as ${cfg.email.trim()}. $n bin${if (n == 1) " is" else "s are"} in the database." + if (save) " Saved as the working set." else ""
            }.onFailure {
                Feel.event(Sfx.DENIED)
                fbMsg = false to (it.message.orEmpty() + if (save) " Nothing was saved." else "")
            }
        }
    }

    fun fbRestore() {
        val w = Hub.firebaseWorking()
        if (w == null) {
            fbMsg = false to "No details have passed a test on this phone yet."
            return
        }
        fb = w
        Hub.saveFirebase(w)
        fbMsg = true to "Put back the last details that passed a test."
    }

    companion object {
        private const val DAY = 24 * 3600_000L
        private const val AUTO_LOCK = 2 * 60_000L
        const val BT_CODE_LEN = 6
        const val BT_CODE_LIFE = 60_000L
        val DEMO_NETS = listOf(BinBle.Net("HomeWiFi-2G", -48), BinBle.Net("TUP-Manila", -61), BinBle.Net("Smart_Bin_Lab", -70), BinBle.Net("PLDTHOMEFIBR24", -79))
        private fun round2(v: Double) = Math.round(v * 100) / 100.0
    }
}

/** One setting stepper: the mockup's FIELDS table. */
class FieldSpec(val label: String, val icon: () -> androidx.compose.ui.graphics.vector.ImageVector, val unit: String, val step: Double, val min: Double, val max: Double, val dec: Int)

val FIELDS: Map<String, FieldSpec> = mapOf(
    "d_empty" to FieldSpec("Distance empty", { Ic.ruler }, "cm", 0.5, 1.0, 400.0, 1),
    "d_full" to FieldSpec("Distance full", { Ic.ruler }, "cm", 0.5, 1.0, 400.0, 1),
    "fill_th" to FieldSpec("Fill threshold", { Ic.percent }, "%", 1.0, 10.0, 100.0, 0),
    "temp_th" to FieldSpec("Temperature threshold", { Ic.temp(40.0) }, "°C", 1.0, 10.0, 85.0, 0),
    "hum_th" to FieldSpec("Humidity threshold", { Ic.humid }, "%RH", 1.0, 30.0, 100.0, 0),
    "gas_th" to FieldSpec("Air quality limit", { Ic.gas }, "IAQ", 5.0, 50.0, 500.0, 0),
    "tilt_th" to FieldSpec("Tilt threshold", { Ic.tilt }, "deg", 1.0, 5.0, 90.0, 0),
    "weight_cap" to FieldSpec("Weight capacity", { Ic.weight(0.6) }, "g", 50.0, 100.0, 5000.0, 0),
    "temp_off" to FieldSpec("Temperature offset", { Ic.temp(28.0) }, "°C", 0.1, -10.0, 10.0, 1),
    "hum_off" to FieldSpec("Humidity offset", { Ic.humid }, "%RH", 0.5, -20.0, 20.0, 1),
    "press_off" to FieldSpec("Pressure offset", { Ic.press }, "hPa", 0.1, -50.0, 50.0, 1),
    "servo_closed" to FieldSpec("Servo closed", { Ic.servo }, "deg", 1.0, 0.0, 180.0, 0),
    "servo_open" to FieldSpec("Servo open", { Ic.servo }, "deg", 1.0, 0.0, 180.0, 0),
    "pir_hold_s" to FieldSpec("Lid stays open", { Ic.clock }, "s", 1.0, 1.0, 60.0, 0),
    "buzz_s" to FieldSpec("Buzzer minimum", { Ic.bell }, "s", 1.0, 1.0, 30.0, 0),
    "tref" to FieldSpec("Reference thermometer", { Ic.temp(28.0) }, "°C", 0.1, -40.0, 85.0, 1),
    "known" to FieldSpec("Known weight", { Ic.weight(0.3) }, "g", 50.0, 10.0, 5000.0, 0),
)

class SimStep(val unit: String, val step: Double, val min: Double, val max: Double)

val SIM_STEP = mapOf(
    "weight" to SimStep("g", 100.0, 0.0, 5000.0),
    "temp" to SimStep("°C", 0.5, -40.0, 85.0),
    "humidity" to SimStep("%", 1.0, 0.0, 100.0),
)

fun fmt(v: Double, dec: Int): String = if (dec == 0) Math.round(v).toString() else "%.${dec}f".format(v)

/** "2 min ago", as the alert rows say it. */
fun ago(at: Long, now: Long = System.currentTimeMillis()): String {
    val m = ((now - at) / 60_000L).coerceAtLeast(0)
    return when {
        m < 1 -> "now"
        m < 60 -> "$m min ago"
        m < 24 * 60 -> "${m / 60} h ago"
        m < 48 * 60 -> "1 day ago"
        else -> "${m / (24 * 60)} days ago"
    }
}
