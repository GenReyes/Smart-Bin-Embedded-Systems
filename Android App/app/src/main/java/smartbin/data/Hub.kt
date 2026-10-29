package smartbin.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/** The kinds of alert, one notification channel and one sound each. */
enum class AlertType { FULL, TAMPER, SENSOR, LOCK, PICKUP, INFO }

sealed interface HubEvent {
    data class NewAlert(val row: AlertRow, val type: AlertType, val binId: String) : HubEvent
    data class LinkChanged(val from: Link, val to: Link) : HubEvent
    data class BinLost(val name: String) : HubEvent
    data class BinBack(val name: String) : HubEvent
    /** A cloud command was confirmed by the bin, refused ([why]), or ran out of time. */
    data class CommandResult(val binId: String, val name: String, val cmd: String, val ok: Boolean, val why: String? = null) : HubEvent
    /** A sound for something the demo tour did, named as in the Sfx list (LOCK, PICKUP, ...). */
    data class Sound(val name: String) : HubEvent
}

/**
 * The one place that talks to the bins. It reads every bin once a second
 * (every five while the app is closed), local Wi-Fi first and Firebase second,
 * keeps a day of history for the charts, turns changes into alerts, and runs
 * the commands. Screens only read [state] and call these functions.
 *
 * Everything runs on the main thread; only the network calls leave it.
 */
object Hub {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var store: Store
    private var loop: Job? = null
    private var started = false

    private val _state = MutableStateFlow(HubState())
    val state: StateFlow<HubState> = _state

    private val _history = MutableStateFlow<Map<String, List<Sample>>>(emptyMap())
    val history: StateFlow<Map<String, List<Sample>>> = _history

    private val _events = MutableSharedFlow<HubEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<HubEvent> = _events

    lateinit var cloud: Cloud
        private set
    lateinit var ts: ThingSpeak
        private set

    /** True while the app is on screen. Off screen the bins are read less often. */
    @Volatile var foreground = true

    /**
     * True once the user is past the start page and the role picker in this
     * run of the app. Alerts reach the notification shade only then, and only
     * while the app is in the background: never from the start page, and
     * never after the app is swiped away or closed.
     */
    @Volatile var inMain = false

    private val nextLocal = HashMap<String, Long>()
    private val localFails = HashMap<String, Int>()
    private val nextCloud = HashMap<String, Long>()
    private val nextTs = HashMap<String, Long>()
    private var fbOk = 0L
    private var tsOk = 0L
    private var lastCheck = 0L
    /** When this app last switched calibration on or off, so the bin gets a moment to answer before its report counts. */
    private var calibAt = 0L
    /** Per channel: the first and the latest (time, entry number) seen, for the message estimate. */
    private val tsSeen = HashMap<String, Pair<Pair<Long, Long>, Pair<Long, Long>>>()
    /** Demo options: bins pretending to have gone quiet. */
    private val demoQuiet = HashSet<String>()
    /** Demo options: when each pretend cloud command is carried out, by bin. */
    private val demoAnswer = HashMap<String, Pair<Long, String>>()
    private val simDirty = HashSet<String>()
    private val prev = HashMap<String, Flags>()
    private var lastSample = 0L
    private var lastUsage = 0L
    private var alertSeq = 0L

    private data class Flags(
        val full: Boolean, val hot: Boolean, val humid: Boolean, val gassy: Boolean, val status: BinStatus, val source: Source,
        val faults: String, val acc: Int, val locked: Boolean = false, val collecting: Boolean = false,
    )

    /** Demo tour: readings held on a demo bin while the tour acts something out. */
    private val holds = HashMap<String, (Reading) -> Reading>()
    private var tourStep = 0
    private var tourNext = 0L
    private var tourPauseUntil = 0L

    /** Firebase watchers: this phone's id, and when it last told the bins it is watching. */
    private var watcherId = ""
    private var watchedAt = 0L
    private var watching = false
    /** Firebase alerts written by the bins while the app was away, read once per bin per run. */
    private val alertsRead = HashSet<String>()
    private var alertsCheck = 0L
    private var startedAt = 0L

    fun init(context: Context) {
        if (started) return
        started = true
        store = Store(context)
        cloud = Cloud(store.firebase())
        ts = ThingSpeak().apply { use(store.string(KEY_TS_URL, THINGSPEAK_URL)) }
        val now = System.currentTimeMillis()
        startedAt = now
        watcherId = store.string(KEY_WATCHER, "").ifBlank { "app-" + java.util.UUID.randomUUID().toString().take(10) }.also { store.putString(KEY_WATCHER, it) }
        var bins = store.bins() ?: emptyList()
        var alerts = store.alerts() ?: emptyList()
        // Version 1 kept its demo bins in the saved list, as bins with no address. Version 2 makes
        // demo bins only when DEMO is on at the start page, so those old ones go, once.
        if (!store.bool(KEY_V2, false)) {
            // b1 and b2 were the demo bins; a bin with no address, channel or cloud account was demo data too.
            val real = bins.filter { it.ip.isNotBlank() || it.onThingSpeak || (cloud.ready && it.id !in setOf("b1", "b2")) }
            if (real.isEmpty()) alerts = emptyList()
            bins = real
            store.saveBins(bins)
            store.saveAlerts(alerts)
            store.putBool(KEY_V2, true)
        }
        bins = bins.map { it.copy(source = Source.STALE) }
        alertSeq = alerts.maxOfOrNull { it.id } ?: 0L
        _state.value = HubState(bins = bins, alerts = alerts, link = linkOf(bins), loading = true)
        _history.value = emptyMap()

        // A new network (joining the bins' Wi-Fi, or leaving it) means try Wi-Fi again at once.
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch { nextLocal.clear(); localFails.clear(); nextCloud.clear(); nextTs.clear(); lastCheck = 0L }
                }
            })
        }
        loop = scope.launch {
            while (isActive) {
                val began = System.currentTimeMillis()
                try {
                    tick()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // One bad round never stops the loop.
                }
                val period = if (foreground) 1000L else 5000L
                delay(max(200L, period - (System.currentTimeMillis() - began)))
            }
        }
    }

    // ---------- the loop ----------

    private suspend fun tick() {
        val now = System.currentTimeMillis()
        val before = _state.value
        val polled = coroutineScope { before.bins.map { b -> async { poll(b, now) } }.awaitAll() }
        // Bins may have been added, edited or removed while the calls were out: keep those edits.
        val byId = polled.associateBy { it.id }
        val current = _state.value
        val merged = current.bins.map { b ->
            val p = byId[b.id] ?: return@map b
            val was = before.bins.firstOrNull { it.id == b.id } ?: return@map b
            // An edit made while the calls were out wins over the bin's answer.
            if (b.ip != was.ip) return@map b
            b.copy(
                reading = p.reading, source = p.source, lastSeen = p.lastSeen,
                cal = if (p.cal !== was.cal && b.cal === was.cal) p.cal else b.cal,
                ip = p.ip,
                sim = if (b.id in simDirty) b.sim else p.sim,
            )
        }
        val link = linkOf(merged)
        if (link != current.link && !current.loading) _events.tryEmit(HubEvent.LinkChanged(current.link, link))
        checkRoutes(merged, now)
        val pending = settle(_state.value.pending, merged, now)
        _state.value = current.copy(
            calibBin = syncCalib(current.calibBin, merged, now),
            pending = pending,
            bins = merged, link = link, loading = false,
            fbUp = if (cloud.ready) now - fbOk < 60_000 else null,
            tsUp = if (merged.any { it.onThingSpeak }) now - tsOk < 60_000 else null,
            tsPerDay = tsPerDay(merged),
        )
        if (merged.any { it.ip != (current.bins.firstOrNull { c -> c.id == it.id }?.ip) }) persist(merged)
        watch(merged, now)
        tour(now)
        watchers(merged, now)
        cloudAlerts(merged, now)
        if (now - lastSample >= 10_000) {
            lastSample = now
            sample(merged, now)
        }
        if (current.fbUp == true && now - lastUsage > 60_000) {
            lastUsage = now
            runCatching { cloud.usageBytes() }.getOrNull()?.let { _state.value = _state.value.copy(cloudBytes = it) }
        }
    }

    private suspend fun poll(b: Bin, now: Long): Bin {
        if (b.demo) {
            // Demo options can make a bin go quiet; it keeps its last reading until it comes back.
            if (b.id in demoQuiet) {
                demoAnswer.remove(b.id)
                return b.copy(source = Source.STALE)
            }
            // A pretend cloud command is carried out a few seconds after it was sent, as a real bin would.
            var from = b
            demoAnswer[b.id]?.let { (at, cmd) ->
                if (now >= at) {
                    demoAnswer.remove(b.id)
                    from = b.copy(reading = effect(b.reading, cmd))
                }
            }
            var r = Demo.step(from, _state.value.calibBin == b.id, now)
            holds[b.id]?.let { r = it(r) }
            return from.copy(reading = r, source = demoSource(_state.value.demoLinks), lastSeen = now)
        }
        // Local Wi-Fi first: every second while it answers. After a miss it is tried
        // again in 2 s, and after three misses in a row every 30 s, as on the dashboard.
        if (b.ip.isNotBlank() && now >= (nextLocal[b.id] ?: 0L)) {
            try {
                val s = LocalApi.status(b.ip)
                nextLocal.remove(b.id)
                localFails.remove(b.id)
                return b.copy(
                    reading = s.reading, source = Source.LIVE, lastSeen = now,
                    cal = s.cal ?: b.cal,
                    sim = if (s.simMask != null && b.id !in simDirty) b.sim.copy(mask = s.simMask, values = b.sim.values + (s.simValues ?: emptyMap())) else b.sim,
                    fw = s.fw.ifBlank { b.fw }, rssi = s.rssi ?: b.rssi, uptimeS = s.uptimeS ?: b.uptimeS, touchCustom = s.touchCustom ?: b.touchCustom,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                val fails = (localFails[b.id] ?: 0) + 1
                localFails[b.id] = fails
                nextLocal[b.id] = now + if (fails < 3) 2_000 else 30_000
            }
        }
        // Then Firebase, every 5 s, when an account is set up.
        if (cloud.ready) {
            if (now >= (nextCloud[b.id] ?: 0L)) {
                nextCloud[b.id] = now + 5_000
                try {
                    val c = cloud.status(b.cloudId)
                    fbOk = now
                    val age = if (c != null && c.ts > 0) now - c.ts else 0L
                    if (c != null && age < 90_000) {
                        return b.copy(
                            reading = c.status.reading, source = Source.CLOUD, lastSeen = now,
                            cal = c.status.cal ?: b.cal,
                            // Each bin writes its own address, so a new one is picked up without typing it.
                            ip = c.localIp ?: b.ip,
                            fw = c.status.fw.ifBlank { b.fw }, rssi = c.status.rssi ?: b.rssi, uptimeS = c.status.uptimeS ?: b.uptimeS,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            } else if (b.source == Source.CLOUD && now - b.lastSeen < 90_000) {
                return b
            }
        }
        // Then ThingSpeak, every 15 s, the fastest its free licence lets a bin write.
        if (b.onThingSpeak) {
            if (now >= (nextTs[b.id] ?: 0L)) {
                nextTs[b.id] = now + 15_000
                try {
                    val e = ts.last(b.tsChannel, b.tsKey)
                    tsOk = now
                    e?.let { noteEntry(b.tsChannel, it.entryId, now) }
                    val age = if (e != null && e.ts > 0) now - e.ts else 0L
                    if (e != null && age < 90_000) {
                        return b.copy(reading = e.reading, source = Source.THINGSPEAK, lastSeen = now, ip = e.localIp ?: b.ip)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            } else if (b.source == Source.THINGSPEAK && now - b.lastSeen < 90_000) {
                return b
            }
        }
        // A bin is called lost only after 90 s with no reading at all; until then its last one stays.
        if ((b.source == Source.LIVE || b.source == Source.CLOUD || b.source == Source.THINGSPEAK) && now - b.lastSeen < 90_000) return b
        return b.copy(source = Source.STALE)
    }

    private fun linkOf(bins: List<Bin>, pretend: Routes = _state.value.demoLinks): Link = when {
        bins.isEmpty() -> Link.OFFLINE
        bins.all { it.demo } -> when (demoSource(pretend)) {
            Source.LIVE -> Link.LIVE
            Source.CLOUD -> Link.CLOUD
            Source.THINGSPEAK -> Link.THINGSPEAK
            else -> Link.DEMO
        }
        bins.any { it.source == Source.LIVE } -> Link.LIVE
        bins.any { it.source == Source.CLOUD } -> Link.CLOUD
        bins.any { it.source == Source.THINGSPEAK } -> Link.THINGSPEAK
        else -> Link.OFFLINE
    }

    /**
     * Firebase and ThingSpeak are only read for data when Wi-Fi fails, so every
     * 30 s the one not in use gets a small call, and the link chip can show each
     * route that answers.
     */
    private suspend fun checkRoutes(bins: List<Bin>, now: Long) {
        if (now - lastCheck < 30_000) return
        lastCheck = now
        if (cloud.ready && now - fbOk > 30_000 && cloud.ping()) fbOk = now
        // Every channel is read here too, so the message estimate covers all of them.
        bins.filter { it.onThingSpeak && it.source != Source.THINGSPEAK }.distinctBy { it.tsChannel }.forEach { t ->
            runCatching { ts.last(t.tsChannel, t.tsKey) }.onSuccess { e ->
                tsOk = now
                e?.let { noteEntry(t.tsChannel, it.entryId, now) }
            }
        }
    }

    /**
     * Calibration mode belongs to the bin. Whoever switches it on (this app, the dashboard or
     * the touchscreen), the bin reports it, and the switch here follows that report, so the
     * app and the dashboard always agree. A switch made here gets 4 s to reach the bin first.
     */
    private fun syncCalib(cur: String?, bins: List<Bin>, now: Long): String? {
        if (now - calibAt < 4_000) return cur
        val live = bins.filter { !it.demo && (it.source == Source.LIVE || it.source == Source.CLOUD) }
        if (cur != null) {
            val b = live.firstOrNull { it.id == cur } ?: return cur
            return if (b.calibrating) cur else null
        }
        return live.firstOrNull { it.calibrating }?.id
    }

    /** Drops each cloud command the bin has now carried out, or that waited too long. */
    private fun settle(pending: Map<String, Pending>, bins: List<Bin>, now: Long): Map<String, Pending> {
        if (pending.isEmpty()) return pending
        val out = HashMap<String, Pending>()
        pending.forEach { (id, p) ->
            val b = bins.firstOrNull { it.id == id } ?: return@forEach
            val fresh = b.lastSeen > p.at
            val done = fresh && when (p.cmd) {
                "LOCK" -> b.reading.mode.locked
                "UNLOCK" -> !b.reading.mode.locked
                "COLLECT" -> b.reading.mode.collecting
                else -> true
            }
            when {
                done -> _events.tryEmit(HubEvent.CommandResult(id, b.name, p.cmd, true))
                now > p.deadline -> _events.tryEmit(HubEvent.CommandResult(id, b.name, p.cmd, false))
                else -> out[id] = p
            }
        }
        return out
    }

    private fun noteEntry(channel: String, entry: Long, now: Long) {
        if (entry <= 0) return
        val seen = tsSeen[channel]
        tsSeen[channel] = if (seen == null || entry < seen.second.second) (now to entry) to (now to entry) else seen.first to (now to entry)
    }

    /** Sums each channel's writes per day, once it has been watched for two minutes. */
    private fun tsPerDay(bins: List<Bin>): Double? {
        val channels = bins.filter { it.onThingSpeak }.map { it.tsChannel }.toSet()
        var total = 0.0
        var any = false
        channels.forEach { ch ->
            val (a, b) = tsSeen[ch] ?: return@forEach
            val ms = b.first - a.first
            if (ms < 120_000) return@forEach
            any = true
            total += (b.second - a.second).toDouble() / ms * 86_400_000.0
        }
        return if (any) total else null
    }

    private fun sample(bins: List<Bin>, now: Long) {
        val cut = now - 24 * 3600_000L
        val h = _history.value.toMutableMap()
        bins.forEach { b ->
            if (b.source == Source.STALE) return@forEach
            val r = b.reading
            val list = (h[b.id] ?: emptyList()).dropWhile { it.t < cut } +
                Sample(now, r.fill.toFloat(), r.temp.toFloat(), r.hum.toFloat(), r.press.toFloat(), r.gas.toFloat())
            h[b.id] = list
        }
        h.keys.retainAll(bins.map { it.id }.toSet())
        _history.value = h
    }

    /** Turns changes into alerts. The first reading of a bin only sets the starting point. */
    private fun watch(bins: List<Bin>, now: Long) {
        bins.forEach { b ->
            val r = b.reading
            val c = b.cal
            val f = Flags(
                full = r.fill >= c.v("fill_th") && !r.noFill, hot = r.temp >= c.v("temp_th") && !r.fault("BME680"),
                humid = r.hum >= c.v("hum_th") && !r.fault("BME680"), gassy = r.gas >= c.v("gas_th") && !r.warming && !r.fault("BME680"),
                status = r.status, source = b.source, faults = r.faults.joinToString(", "), acc = r.iaqAcc,
                locked = r.mode.locked, collecting = r.mode.collecting,
            )
            val p = prev[b.id]
            prev[b.id] = f
            if (p == null) return@forEach
            val n = b.name
            if (f.full && !p.full) add(b, Severity.CRITICAL, "fill", "Fill threshold passed", "$n is at ${r.fill.toInt()}%", AlertType.FULL)
            if (f.hot && !p.hot) add(b, Severity.CRITICAL, "temp", "Temperature high", "$n at ${"%.1f".format(r.temp)} °C", AlertType.SENSOR)
            if (f.humid && !p.humid) add(b, Severity.WARNING, "humid", "Humidity high", "$n at ${r.hum.toInt()}%, limit ${c.v("hum_th").toInt()}%", AlertType.SENSOR)
            if (f.gassy && !p.gassy) add(b, Severity.WARNING, "gas", "Air quality warning", "$n index ${r.gas.toInt()}, limit ${c.v("gas_th").toInt()}", AlertType.SENSOR)
            if (f.status != p.status) when (f.status) {
                BinStatus.TAMPER -> add(b, Severity.CRITICAL, "tamper", "Tamper detected", "$n was moved or tilted", AlertType.TAMPER)
                BinStatus.CALIBRATING -> add(b, Severity.INFO, "calib", "Calibration started", "$n is being calibrated", AlertType.INFO, kind = AlertKind.SETUP)
                else -> {}
            }
            // Lock and pickup follow the bin's own flags, so a lock under a tamper or a calibration
            // is neither lost nor announced twice, the same as the dashboard.
            if (f.locked && !p.locked) add(b, Severity.INFO, "lock", "Bin locked", "$n locked", AlertType.LOCK)
            if (!f.locked && p.locked) add(b, Severity.SUCCESS, "check", "Bin unlocked", "$n is open for use again", AlertType.LOCK, kind = AlertKind.LOCK)
            if (!f.collecting && p.collecting) add(b, Severity.SUCCESS, "truck", "Collection complete", "$n is back to normal", AlertType.PICKUP)
            if (f.faults.isNotEmpty() && f.faults != p.faults) add(b, Severity.WARNING, "gear", "Sensor not answering", "$n: ${f.faults}", AlertType.SENSOR)
            if (f.faults.isEmpty() && p.faults.isNotEmpty()) add(b, Severity.SUCCESS, "check", "Sensor back", "$n reads every sensor again", AlertType.INFO, kind = AlertKind.SENSOR)
            if (p.acc == 0 && f.acc > 0) add(b, Severity.SUCCESS, "gas", "Air sensor ready", "$n air quality readings are settled", AlertType.INFO, kind = AlertKind.SENSOR)
            run {
                if (f.source == Source.STALE && p.source != Source.STALE) _events.tryEmit(HubEvent.BinLost(n))
                if (f.source != Source.STALE && p.source == Source.STALE) _events.tryEmit(HubEvent.BinBack(n))
            }
        }
        prev.keys.retainAll(bins.map { it.id }.toSet())
    }

    private fun add(b: Bin, sev: Severity, icon: String, title: String, meta: String, type: AlertType, kind: AlertKind = Demo.kindFor(icon)) {
        val row = AlertRow(++alertSeq, sev, icon, kind, title, meta, System.currentTimeMillis(), demo = b.demo)
        val list = (listOf(row) + _state.value.alerts).take(200)
        _state.value = _state.value.copy(alerts = list)
        saveAlerts(list)
        _events.tryEmit(HubEvent.NewAlert(row, type, b.id))
    }

    // ---------- commands ----------

    fun bin(id: String) = _state.value.bins.firstOrNull { it.id == id }

    private fun update(id: String, persist: Boolean = false, f: (Bin) -> Bin) {
        val s = _state.value
        val bins = s.bins.map { if (it.id == id) f(it) else it }
        _state.value = s.copy(bins = bins, link = linkOf(bins))
        if (persist) persist(bins)
    }

    /** Only real bins and their alerts are saved. Demo ones live while demo mode is on. */
    private fun persist(bins: List<Bin>) = store.saveBins(bins.filterNot { it.demo })
    private fun saveAlerts(list: List<AlertRow>) = store.saveAlerts(list.filterNot { it.demo })

    /**
     * Sends one command. Over Wi-Fi every command works; over the cloud only
     * LOCK, UNLOCK and COLLECT. Returns null when it went through, or what to
     * tell the user when it did not. The card changes at once either way, the
     * same as the dashboard, and the next reading shows what the bin really did.
     */
    suspend fun command(id: String, cmd: String, extra: Map<String, Any> = emptyMap()): String? {
        val b = bin(id) ?: return null
        _state.value.pending[id]?.let { return "Waiting for ${b.name} to confirm ${it.cmd}." }
        var problem: String? = null
        // Set when the command went through Firebase or ThingSpeak, so its key waits for the bin.
        var via: Source? = null
        // Demo bins act out the route the demo options pretend to use.
        if (b.demo) when (b.source) {
            Source.STALE -> return "${b.name} is not answering."
            Source.CLOUD, Source.THINGSPEAK -> {
                if (cmd !in Cloud.CLOUD_COMMANDS) return "That needs the bins' Wi-Fi."
                val now = System.currentTimeMillis()
                _state.value = _state.value.copy(pending = _state.value.pending + (id to Pending(cmd, b.source, now, now + DEMO_TIMEOUT)))
                if (!_state.value.demoNoConfirm) demoAnswer[id] = (now + DEMO_CONFIRM) to cmd
                return null
            }
            else -> {}
        } else when (b.source) {
            Source.DEMO -> {}
            Source.LIVE -> try {
                LocalApi.command(b.ip, cmd, extra)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LocalApi.Refused) {
                // The bin answered and said no: say why, and do not try the cloud.
                return "${b.name}: ${e.why}."
            } catch (_: Exception) {
                problem = if (cmd in Cloud.CLOUD_COMMANDS && (cloud.ready || b.tbId.isNotBlank())) {
                    val viaFb = cloud.ready && runCatching { cloud.command(b.cloudId, cmd).also { k -> k?.let { confirm(id, b.name, cmd, it) } } }.isSuccess
                    val viaTs = !viaFb && b.tbId.isNotBlank() && runCatching { ts.command(b.tbId, b.tbKey, cmd) }.isSuccess
                    if (viaFb) via = Source.CLOUD
                    if (viaTs) via = Source.THINGSPEAK
                    if (viaFb || viaTs) null else "${b.name} did not answer on Wi-Fi or the cloud."
                } else "${b.name} did not answer. Check its power and Wi-Fi."
            }
            Source.CLOUD -> problem = if (cmd !in Cloud.CLOUD_COMMANDS) "That needs the bins' Wi-Fi."
            else runCatching { cloud.command(b.cloudId, cmd).also { k -> k?.let { confirm(id, b.name, cmd, it) } } }
                .exceptionOrNull()?.let { "The cloud did not take it. ${it.message.orEmpty()}" }
                .also { if (it == null) via = Source.CLOUD }
            Source.THINGSPEAK -> problem = when {
                cmd !in Cloud.CLOUD_COMMANDS -> "That needs the bins' Wi-Fi."
                b.tbId.isBlank() -> "Add ${b.name}'s TalkBack ID and key in SET to send commands through ThingSpeak."
                else -> runCatching { ts.command(b.tbId, b.tbKey, cmd) }.exceptionOrNull()?.let { "ThingSpeak did not take it. ${it.message.orEmpty()}" }
                    .also { if (it == null) via = Source.THINGSPEAK }
            }
            Source.STALE -> problem = "${b.name} is not answering."
        }
        if (problem != null && b.source != Source.LIVE) return problem
        // Over the cloud the card does not change until the bin confirms; the key stays locked meanwhile.
        via?.let { v ->
            if (problem != null) return problem
            val now = System.currentTimeMillis()
            val wait = if (v == Source.THINGSPEAK) 120_000L else 60_000L
            _state.value = _state.value.copy(pending = _state.value.pending + (id to Pending(cmd, v, now, now + wait)))
            return null
        }
        update(id) { x ->
            x.copy(
                reading = effect(x.reading, cmd),
                sim = if (cmd == "CLEAR_TAMPER") x.sim.copy(values = x.sim.values + ("tamper" to 0.0)) else x.sim,
            )
        }
        return problem
    }

    /**
     * The bin writes its answer to a Firebase command under the push key. It is
     * read every second for 8 s; with no answer the key keeps waiting for the
     * bin's status instead, as before.
     */
    private fun confirm(id: String, name: String, cmd: String, key: String) {
        scope.launch {
            repeat(8) {
                delay(1000)
                val r = runCatching { cloud.result(key) }.getOrNull()
                if (r != null && (r.optString("cmd").isBlank() || r.optString("cmd") == cmd)) {
                    cloud.dropResult(key)
                    if (_state.value.pending[id]?.cmd != cmd) return@launch
                    _state.value = _state.value.copy(pending = _state.value.pending - id)
                    val ok = r.optBoolean("ok", false)
                    _events.tryEmit(HubEvent.CommandResult(id, name, cmd, ok, r.optString("why").takeIf { !ok && it.isNotBlank() }))
                    if (ok) update(id) { it.copy(reading = effect(it.reading, cmd)) }
                    return@launch
                }
            }
        }
    }

    /** What a command does to a bin's reading, as the card shows it. */
    private fun effect(r: Reading, cmd: String): Reading = when (cmd) {
        "LOCK" -> r.copy(status = BinStatus.LOCKED, lidOpen = false, modes = r.modes?.copy(locked = true))
        "UNLOCK" -> r.copy(status = BinStatus.OK, modes = r.modes?.copy(locked = false))
        "CLEAR_TAMPER" -> r.copy(status = BinStatus.OK, tilt = 0.0, modes = r.modes?.copy(tamper = false))
        "COLLECT" -> r.copy(status = BinStatus.COLLECTING, lidOpen = true, modes = r.modes?.copy(collecting = true))
        "COLLECT_DONE" -> r.copy(status = BinStatus.OK, lidOpen = false, fill = 0.0, weight = 0.0, gas = minOf(r.gas, 48.0), modes = r.modes?.copy(collecting = false))
        "TARE" -> r.copy(weight = 0.0)
        "RESET_GAS" -> r.copy(iaqAcc = 0, iaqWarmS = 20)
        else -> r
    }

    /** Calibration mode, on one bin at a time. The bin shows its teal calibration screen while it is on. */
    suspend fun setCalibration(id: String?) {
        val old = _state.value.calibBin
        if (old == id) return
        calibAt = System.currentTimeMillis()
        _state.value = _state.value.copy(calibBin = id)
        old?.let { o -> bin(o)?.takeIf { it.source == Source.LIVE && !it.demo }?.let { runCatching { LocalApi.command(it.ip, "CALIBRATE_END") } } }
        old?.let { o -> update(o) { b -> if (b.reading.status == BinStatus.CALIBRATING) b.copy(reading = b.reading.copy(status = BinStatus.OK, modes = b.reading.modes?.copy(calibrating = false))) else b } }
        id?.let { n -> bin(n)?.takeIf { it.source == Source.LIVE && !it.demo }?.let { runCatching { LocalApi.command(it.ip, "CALIBRATE_START") } } }
        id?.let { n -> update(n) { b -> b.copy(reading = b.reading.copy(status = BinStatus.CALIBRATING, modes = b.reading.modes?.copy(calibrating = true))) } }
    }

    /** Stores new calibration values on the bin (EEPROM over Wi-Fi) and in the app. */
    suspend fun saveCal(id: String, cal: Cal): String? {
        val b = bin(id) ?: return null
        if (b.source == Source.LIVE && !b.demo) {
            try {
                LocalApi.calibrate(b.ip, cal)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return "${b.name} did not answer, so nothing was saved."
            }
        }
        update(id, persist = true) { it.copy(cal = cal) }
        bin(id)?.let {
            add(it, Severity.SUCCESS, "check", "Settings saved",
                "${it.name}: empty ${fmt(cal.v("d_empty"))} cm, full ${fmt(cal.v("d_full"))} cm, alert at ${cal.v("fill_th").toInt()}%",
                AlertType.INFO, kind = AlertKind.SETUP)
        }
        return null
    }

    /** Re-reads the values a bin is running on. */
    suspend fun reload(id: String): String? {
        val b = bin(id) ?: return null
        if (b.ip.isBlank()) return null
        return try {
            val s = LocalApi.status(b.ip)
            update(id, persist = true) { it.copy(cal = s.cal ?: it.cal, reading = s.reading, source = Source.LIVE, lastSeen = System.currentTimeMillis()) }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            "${b.name} did not answer on Wi-Fi."
        }
    }

    /** Changes the simulation rows on screen. [apply] sends them to the bin. */
    fun editSim(id: String, sim: Sim) {
        simDirty.add(id)
        update(id) { it.copy(sim = sim) }
    }

    suspend fun applySim(id: String): String? {
        val b = bin(id) ?: return null
        if (b.source == Source.LIVE && !b.demo) {
            try {
                LocalApi.simulate(b.ip, b.sim)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return "${b.name} did not answer. Simulation needs the bins' Wi-Fi."
            }
        }
        simDirty.remove(id)
        return null
    }

    // ---------- bins ----------

    fun addBins(n: Int) {
        val s = _state.value
        val now = System.currentTimeMillis()
        val added = (0 until n).map { k ->
            val index = s.bins.count { !it.demo } + k
            Demo.newBin(index, "b$now-$k").copy(cloudId = "bin%02d".format(index + 1))
        }
        val bins = s.bins + added
        _state.value = s.copy(bins = bins, link = linkOf(bins))
        _history.value = _history.value + added.associate { it.id to emptyList() }
        persist(bins)
    }

    /**
     * A bin that just joined Wi-Fi over Bluetooth and is not in the list yet.
     * Named after its Bluetooth name (SmartBin-01 is Bin 01, cloud name bin01).
     */
    fun addRealBin(number: String, ip: String): Bin {
        val s = _state.value
        s.bins.firstOrNull { !it.demo && it.ip == ip }?.let { return it }
        val index = s.bins.count { !it.demo }
        val num = number.ifBlank { "%02d".format(index + 1) }
        val b = Demo.newBin(index, "b${System.currentTimeMillis()}").copy(name = "Bin $num", place = "", cloudId = "bin$num", ip = ip)
        val bins = listOf(b) + s.bins
        _state.value = s.copy(bins = bins, link = linkOf(bins))
        _history.value = _history.value + (b.id to emptyList())
        persist(bins)
        return b
    }

    /** The bins SEND TO BINS reaches: real ones with an address. */
    fun binsOnWifi() = _state.value.bins.filter { !it.demo && it.ip.isNotBlank() }

    /**
     * Gives every real bin on Wi-Fi the saved database URL and key with the bins' own
     * account ([cfg] carries that email and password). Returns how many took it, how many
     * were tried, the names that did not answer, and the reason a bin gave for refusing.
     */
    suspend fun sendCloudToBins(cfg: FirebaseConfig): SendResult {
        val bins = binsOnWifi()
        var ok = 0
        val missed = mutableListOf<String>()
        var why: String? = null
        bins.forEach { b ->
            runCatching { LocalApi.sendCloud(b.ip, cfg) }
                .onSuccess { ok++ }
                .onFailure { e -> missed += b.name; if (e is LocalApi.Refused) why = e.why }
        }
        return SendResult(ok, bins.size, missed, why)
    }

    class SendResult(val ok: Int, val tried: Int, val missed: List<String>, val why: String?)

    fun editBin(
        id: String, name: String, place: String, ip: String, cloudId: String,
        tsChannel: String? = null, tsKey: String? = null, tbId: String? = null, tbKey: String? = null,
    ) {
        val clean = ip.trim()
        update(id, persist = true) {
            val next = it.copy(
                name = name.trim().ifBlank { it.name }, place = place.trim(), ip = clean,
                cloudId = cloudId.trim().ifBlank { it.cloudId },
                tsChannel = tsChannel?.trim() ?: it.tsChannel, tsKey = tsKey?.trim() ?: it.tsKey,
                tbId = tbId?.trim() ?: it.tbId, tbKey = tbKey?.trim() ?: it.tbKey,
            )
            val moved = next.ip != it.ip || next.tsChannel != it.tsChannel
            next.copy(source = if (moved) (if (next.demo) Source.DEMO else Source.STALE) else it.source)
        }
        nextLocal.remove(id)
        nextCloud.remove(id)
        nextTs.remove(id)
        demoQuiet.remove(id)
    }

    fun removeBin(id: String) {
        val s = _state.value
        val bins = s.bins.filterNot { it.id == id }
        _state.value = s.copy(bins = bins, link = linkOf(bins), calibBin = s.calibBin.takeIf { it != id })
        persist(bins)
    }

    // ---------- alerts ----------

    fun markRead(ids: Set<Long>, read: Boolean = true) = editAlerts { list -> list.map { if (it.id in ids) it.copy(read = read) else it } }

    fun deleteAlerts(ids: Set<Long>) = editAlerts { list -> list.filterNot { it.id in ids } }

    private fun editAlerts(f: (List<AlertRow>) -> List<AlertRow>) {
        val list = f(_state.value.alerts)
        _state.value = _state.value.copy(alerts = list)
        saveAlerts(list)
    }

    // ---------- demo options ----------

    /** Demo options change only bins with no address. Left alone, the demo runs as normal. */
    val demoInUse get() = Demo.speed != Demo.NORMAL_SPEED || _state.value.demoLinks.any || _state.value.demoNoConfirm || demoQuiet.isNotEmpty()

    fun demoSpeed(x: Double) {
        Demo.speed = x
        _state.value = _state.value.copy()
    }

    /**
     * Pretend links. The first one on, in the app's own order (Wi-Fi, Firebase,
     * ThingSpeak), is the one the demo bins answer on, so the cards, banners,
     * link sounds and command waits behave as they would on that route.
     */
    fun demoLinks(r: Routes) {
        val s = _state.value
        val via = demoSource(r)
        val bins = s.bins.map { if (it.demo && it.source != Source.STALE) it.copy(source = via) else it }
        val link = linkOf(bins, r)
        if (link != s.link) _events.tryEmit(HubEvent.LinkChanged(s.link, link))
        _state.value = s.copy(bins = bins, demoLinks = r, link = link)
    }

    /** Pretend cloud commands time out instead of being confirmed. */
    fun setDemoNoConfirm(no: Boolean) {
        if (no) demoAnswer.clear()
        _state.value = _state.value.copy(demoNoConfirm = no)
    }

    private fun demoSource(r: Routes) = when {
        r.local -> Source.LIVE
        r.firebase -> Source.CLOUD
        r.thingspeak -> Source.THINGSPEAK
        else -> Source.DEMO
    }

    fun demoQuiet(id: String) = id in demoQuiet

    fun setDemoQuiet(id: String, quiet: Boolean) {
        if (quiet) demoQuiet.add(id) else demoQuiet.remove(id)
        if (quiet) update(id) { it.copy(lastSeen = System.currentTimeMillis()) }
        if (!quiet) update(id) { it.copy(source = demoSource(_state.value.demoLinks)) }
    }

    enum class DemoEvent { FULL, TAMPER, AIR, EMPTY }

    fun demoEvent(id: String, e: DemoEvent) {
        update(id) { b ->
            val r = b.reading
            val c = b.cal
            b.copy(
                reading = when (e) {
                    DemoEvent.FULL -> r.copy(fill = minOf(100.0, c.v("fill_th") + 3), distance = Demo.distanceFor(c.v("fill_th") + 3, c))
                    DemoEvent.TAMPER -> r.copy(status = BinStatus.TAMPER, tilt = 38.0, lidOpen = false)
                    DemoEvent.AIR -> r.copy(gas = c.v("gas_th") + 60)
                    DemoEvent.EMPTY -> r.copy(fill = 0.0, weight = 0.0, gas = minOf(r.gas, 48.0), status = BinStatus.OK, distance = c.v("d_empty"))
                },
            )
        }
    }

    /** Puts every demo option back, so the demo runs as it does on a fresh start. */
    fun resetDemo() {
        Demo.speed = Demo.NORMAL_SPEED
        demoQuiet.clear()
        demoAnswer.clear()
        val now = System.currentTimeMillis()
        val start = Demo.bins().associateBy { it.id }
        val s = _state.value
        val bins = s.bins.map { b -> if (!b.demo) b else start[b.id]?.let { o -> b.copy(reading = o.reading, rate = o.rate, source = Source.DEMO) } ?: b.copy(source = Source.DEMO) }
        val link = linkOf(bins, Routes())
        if (link != s.link) _events.tryEmit(HubEvent.LinkChanged(s.link, link))
        _state.value = s.copy(bins = bins, link = link, demoLinks = Routes(), demoNoConfirm = false, pending = s.pending.filterKeys { k -> bins.none { it.id == k && it.demo } })
        _history.value = _history.value + bins.filter { it.demo }.associate { it.id to Demo.history(it, now) }
    }

    // ---------- demo mode and the demo tour ----------

    /**
     * Demo mode, from the start page only. On adds the three demo bins with a
     * day of made-up history and alerts; off takes them away with everything
     * the demo options changed.
     */
    fun setDemo(on: Boolean) {
        val s = _state.value
        if (on == s.demoOn) return
        val now = System.currentTimeMillis()
        holds.clear()
        tourStep = 0
        tourNext = now + 6_000
        tourPauseUntil = 0L
        if (on) {
            val demo = Demo.bins()
            val alerts = (Demo.alerts(demo, now).map { it.copy(id = ++alertSeq) } + s.alerts).sortedByDescending { it.at }
            val bins = s.bins.filterNot { it.demo } + demo
            _state.value = s.copy(bins = bins, alerts = alerts, demoOn = true, link = linkOf(bins), tourMsg = null)
            _history.value = _history.value + demo.associate { it.id to Demo.history(it, now) }
        } else {
            Demo.speed = Demo.NORMAL_SPEED
            demoQuiet.clear()
            demoAnswer.clear()
            val gone = s.bins.filter { it.demo }.map { it.id }.toSet()
            val bins = s.bins.filterNot { it.demo }
            _state.value = s.copy(
                bins = bins, alerts = s.alerts.filterNot { it.demo }, demoOn = false, link = linkOf(bins, Routes()),
                demoLinks = Routes(), demoNoConfirm = false, tourMsg = null,
                pending = s.pending - gone, calibBin = s.calibBin.takeIf { it !in gone },
            )
            _history.value = _history.value - gone
            gone.forEach { prev.remove(it) }
        }
    }

    /** A key pressed on a demo bin, or a demo option changed: the tour waits a minute. */
    fun pauseTour() {
        tourPauseUntil = System.currentTimeMillis() + 60_000
        if (_state.value.tourMsg != null) _state.value = _state.value.copy(tourMsg = null)
    }

    private fun hold(id: String, f: ((Reading) -> Reading)?) {
        if (f == null) holds.remove(id) else holds[id] = f
    }

    /** A command acted out by the tour: the card changes and its sound plays, as if a key was pressed. */
    private fun tourCmd(b: Bin, cmd: String) {
        update(b.id) { it.copy(reading = effect(it.reading, cmd)) }
        TOUR_SFX[cmd]?.let { _events.tryEmit(HubEvent.Sound(it)) }
    }

    /**
     * With demo mode on and nobody pressing anything on a demo bin, the demo
     * bins act out every outcome in turn, about every 10 s: full, pickup,
     * tamper, lock, bad air, heat, humidity, a sensor fault, warm-up, the
     * links changing, a bin going quiet, and calibration.
     */
    private fun tour(now: Long) {
        val s = _state.value
        val demo = s.bins.filter { it.demo }
        if (!s.demoOn || demo.isEmpty() || !inMain || !foreground) {
            tourNext = maxOf(tourNext, now + 6_000)
            return
        }
        if (now < tourPauseUntil || now < tourNext) return
        tourNext = now + 10_000
        val a = demo[0]
        val b = demo[1 % demo.size]
        val c = demo[2 % demo.size]
        val step = tourStep
        tourStep = (tourStep + 1) % TOUR_STEPS
        val msg: String = when (step) {
            0 -> { demoEvent(a.id, DemoEvent.FULL); "${a.name} fills past its ${a.cal.v("fill_th").toInt()}% limit." }
            1 -> { if (bin(a.id)?.reading?.status == BinStatus.LOCKED) tourCmd(a, "UNLOCK"); tourCmd(a, "COLLECT"); "A collector starts the pickup at ${a.name}. The lid opens." }
            2 -> { tourCmd(a, "COLLECT_DONE"); "${a.name} is emptied and back to normal." }
            3 -> { hold(b.id) { r -> r.copy(tilt = 40.0, status = BinStatus.TAMPER, lidOpen = false, tamperPhase = "LATCHED") }; "${b.name} is tipped over. Tamper alert." }
            4 -> { hold(b.id, null); tourCmd(b, "CLEAR_TAMPER"); "The tamper alert on ${b.name} is reset." }
            5 -> { tourCmd(c, "LOCK"); "${c.name} is locked from the app." }
            6 -> { tourCmd(c, "UNLOCK"); "${c.name} is unlocked again." }
            7 -> { val g = c.cal.v("gas_th") + 45; hold(c.id) { r -> r.copy(gas = g) }; "The air in ${c.name} goes bad." }
            8 -> {
                hold(c.id, null); update(c.id) { it.copy(reading = it.reading.copy(gas = 60.0)) }
                val t = b.cal.v("temp_th") + 3.5
                hold(b.id) { r -> r.copy(temp = t, tempRaw = t - b.cal.v("temp_off")) }
                "${b.name} gets too hot."
            }
            9 -> {
                val h = b.cal.v("hum_th") + 5
                hold(b.id) { r -> r.copy(hum = h) }
                update(b.id) { it.copy(reading = it.reading.copy(temp = 29.0, tempRaw = 29.0 - it.cal.v("temp_off"))) }
                "${b.name} gets too humid."
            }
            10 -> {
                hold(b.id, null); update(b.id) { it.copy(reading = it.reading.copy(hum = 62.0)) }
                hold(a.id) { r -> r.copy(faults = listOf("HX711"), weight = 0.0) }
                "The weight sensor of ${a.name} stops answering."
            }
            11 -> { hold(a.id, null); "The weight sensor of ${a.name} answers again." }
            12 -> { tourCmd(c, "RESET_GAS"); "The air sensor of ${c.name} warms up. Its score is held until it settles." }
            13 -> { demoLinks(Routes(firebase = true)); "${b.name} and the others lose Wi-Fi, so they are read through Firebase." }
            14 -> { demoLinks(Routes(thingspeak = true)); "Firebase is down too, so the bins are read through ThingSpeak." }
            15 -> { setDemoQuiet(b.id, true); "${b.name} stops answering on every link." }
            16 -> { setDemoQuiet(b.id, false); demoLinks(Routes(local = true)); "${b.name} is back, and the bins answer on Wi-Fi again." }
            17 -> { demoLinks(Routes()); scope.launch { setCalibration(b.id) }; TOUR_SFX["CALIBRATE_START"]?.let { _events.tryEmit(HubEvent.Sound(it)) }; "${b.name} is being calibrated." }
            else -> { scope.launch { setCalibration(null) }; "Calibration of ${b.name} is done. The tour starts again." }
        }
        _state.value = _state.value.copy(tourMsg = msg)
    }

    // ---------- Firebase watchers and alerts ----------

    /**
     * While the app is open and reads a bin through Firebase, it tells the bins
     * every 10 s, so they write every 5 s instead of every minute. Going to
     * the background takes the note away.
     */
    private suspend fun watchers(bins: List<Bin>, now: Long) {
        if (!cloud.ready) return
        val want = foreground && bins.any { !it.demo && it.source == Source.CLOUD }
        if (want && now - watchedAt >= 10_000) {
            watchedAt = now
            if (runCatching { cloud.watch(watcherId, android.os.Build.MODEL ?: "phone") }.isSuccess) watching = true
        } else if (!want && watching) {
            watching = false
            watchedAt = 0L
            cloud.unwatch(watcherId)
        }
    }

    /**
     * The alerts each bin wrote to Firebase while the app was closed, read once
     * per bin after the app starts. Alerts while the app is open come from the
     * readings themselves, so they are not read twice.
     */
    private suspend fun cloudAlerts(bins: List<Bin>, now: Long) {
        if (!cloud.ready || !foreground || now - alertsCheck < 30_000) return
        alertsCheck = now
        bins.filter { !it.demo && it.cloudId.isNotBlank() && it.cloudId !in alertsRead }.forEach { b ->
            val list = runCatching { cloud.alerts(b.cloudId) }.getOrNull() ?: return@forEach
            alertsRead.add(b.cloudId)
            val seenKey = "fb_alerts_" + b.cloudId
            val seen = store.string(seenKey, "0").toLongOrNull() ?: 0L
            val fresh = list.filter { it.optLong("ts") in (seen + 1) until startedAt }
            if (fresh.isNotEmpty()) {
                val rows = fresh.map { o ->
                    val kind = when (o.optString("kind")) {
                        "full" -> AlertKind.FULL; "sensor" -> AlertKind.SENSOR; "tamper" -> AlertKind.TAMPER
                        "lock" -> AlertKind.LOCK; "pickup" -> AlertKind.PICKUP; else -> AlertKind.SETUP
                    }
                    val sev = when (o.optString("sev")) {
                        "critical" -> Severity.CRITICAL; "warning" -> Severity.WARNING; "success" -> Severity.SUCCESS; else -> Severity.INFO
                    }
                    val icon = when (kind) {
                        AlertKind.FULL -> "fill"; AlertKind.SENSOR -> "gas"; AlertKind.TAMPER -> "tamper"
                        AlertKind.LOCK -> "lock"; AlertKind.PICKUP -> "truck"; else -> "calib"
                    }
                    AlertRow(++alertSeq, sev, icon, kind, o.optString("title", "Bin alert"), o.optString("meta", b.name), o.optLong("ts"))
                }
                val all = (rows + _state.value.alerts).sortedByDescending { it.at }.take(200)
                _state.value = _state.value.copy(alerts = all)
                saveAlerts(all)
            }
            list.maxOfOrNull { it.optLong("ts") }?.let { store.putString(seenKey, it.toString()) }
        }
    }

    // ---------- ThingSpeak ----------

    fun thingSpeakUrl() = ts.url

    fun saveThingSpeak(url: String) {
        ts.use(url)
        store.putString(KEY_TS_URL, ts.url)
        nextTs.clear()
        lastCheck = 0L
    }

    /** Reads each bin's channel once. Returns how many answered and the first problem. */
    suspend fun testThingSpeak(): Pair<Int, String?> {
        val bins = _state.value.bins.filter { it.onThingSpeak }
        if (bins.isEmpty()) return 0 to "No bin has a ThingSpeak channel yet. Add one in its SET sheet."
        var ok = 0
        var problem: String? = null
        bins.forEach { b ->
            try {
                ts.last(b.tsChannel, b.tsKey)
                ok++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (problem == null) problem = "${b.name}: ${e.message ?: "no answer"}"
            }
        }
        if (ok > 0) tsOk = System.currentTimeMillis()
        return ok to problem
    }

    // ---------- Firebase ----------

    fun firebase() = store.firebase()

    /** The bins' own account email last sent, or bin01@ on the operator's domain as a first guess. */
    fun binEmail(): String = store.string("bin_email", "").ifBlank {
        store.firebase().email.substringAfter('@', "").takeIf { it.isNotBlank() }?.let { "bin01@$it" } ?: ""
    }

    fun saveBinEmail(email: String) = store.putString("bin_email", email.trim())
    fun firebaseWorking() = store.firebaseWorking()

    fun saveFirebase(cfg: FirebaseConfig) {
        store.saveFirebase(cfg, passedTest = true)
        cloud.use(cfg)
        nextCloud.clear()
    }

    /** Estimated fill speed in percent per hour, from the last hour of history; demo bins use their own rate. */
    fun ratePerHour(b: Bin): Double {
        if (b.demo) return b.rate
        val h = _history.value[b.id] ?: return 0.0
        val now = System.currentTimeMillis()
        val recent = h.filter { it.t >= now - 3600_000L }
        if (recent.size < 6) return 0.0
        val hours = (recent.last().t - recent.first().t) / 3600_000.0
        if (hours <= 0.05) return 0.0
        return ((recent.last().fill - recent.first().fill) / hours).coerceAtLeast(0.0)
    }

    private const val KEY_TS_URL = "ts_url"
    private const val KEY_V2 = "v2_bins"
    private const val KEY_WATCHER = "watcher_id"
    private const val TOUR_STEPS = 19
    /** The sound each tour command plays, the same as its key in the app. */
    private val TOUR_SFX = mapOf(
        "LOCK" to "LOCK", "UNLOCK" to "UNLOCK", "COLLECT" to "PICKUP", "COLLECT_DONE" to "DONE",
        "CLEAR_TAMPER" to "CONFIRM", "RESET_GAS" to "CONFIRM", "CALIBRATE_START" to "CALIB",
    )
    /** Pretend cloud commands: confirmed after 4 s, or given up after 8 s, as in Garbage-Guard's demo. */
    private const val DEMO_CONFIRM = 4_000L
    private const val DEMO_TIMEOUT = 8_000L

    private fun fmt(v: Double) = if (v == Math.floor(v)) v.toInt().toString() else "%.1f".format(v)

    fun store() = store
}
