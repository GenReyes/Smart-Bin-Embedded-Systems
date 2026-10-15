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

    /** True while the app is on screen. Off screen the bins are read less often. */
    @Volatile var foreground = true

    private val nextLocal = HashMap<String, Long>()
    private val localFails = HashMap<String, Int>()
    private val nextCloud = HashMap<String, Long>()
    private val simDirty = HashSet<String>()
    private val prev = HashMap<String, Flags>()
    private var lastSample = 0L
    private var lastUsage = 0L
    private var alertSeq = 0L

    private data class Flags(val full: Boolean, val hot: Boolean, val humid: Boolean, val gassy: Boolean, val status: BinStatus, val source: Source)

    fun init(context: Context) {
        if (started) return
        started = true
        store = Store(context)
        cloud = Cloud(store.firebase())
        val now = System.currentTimeMillis()
        val bins = store.bins() ?: Demo.bins().also(store::saveBins)
        val alerts = store.alerts() ?: (if (bins.none { it.ip.isNotBlank() }) Demo.alerts(bins, now) else emptyList()).also(store::saveAlerts)
        alertSeq = alerts.maxOfOrNull { it.id } ?: 0L
        _state.value = HubState(bins = bins, alerts = alerts, link = linkOf(bins), loading = true)
        _history.value = bins.filter { it.ip.isBlank() }.associate { it.id to Demo.history(it, now) }

        // A new network (joining the bins' Wi-Fi, or leaving it) means try Wi-Fi again at once.
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch { nextLocal.clear(); localFails.clear(); nextCloud.clear() }
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
        _state.value = current.copy(bins = merged, link = link, loading = false)
        if (merged.any { it.ip != (current.bins.firstOrNull { c -> c.id == it.id }?.ip) }) store.saveBins(merged)
        watch(merged, now)
        if (now - lastSample >= 10_000) {
            lastSample = now
            sample(merged, now)
        }
        if (link == Link.CLOUD && now - lastUsage > 60_000) {
            lastUsage = now
            runCatching { cloud.usageBytes() }.getOrNull()?.let { _state.value = _state.value.copy(cloudBytes = it) }
        }
    }

    private suspend fun poll(b: Bin, now: Long): Bin {
        if (b.ip.isBlank()) {
            val r = Demo.step(b, _state.value.calibBin == b.id, now)
            return b.copy(reading = r, source = Source.DEMO)
        }
        // Local Wi-Fi first: every second while it answers. After a miss it is tried
        // again in 2 s, and after three misses in a row every 30 s, as on the dashboard.
        if (now >= (nextLocal[b.id] ?: 0L)) {
            try {
                val s = LocalApi.status(b.ip)
                nextLocal.remove(b.id)
                localFails.remove(b.id)
                return b.copy(
                    reading = s.reading, source = Source.LIVE, lastSeen = now,
                    cal = s.cal ?: b.cal,
                    sim = if (s.simMask != null && b.id !in simDirty) b.sim.copy(mask = s.simMask) else b.sim,
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
                    val age = if (c != null && c.ts > 0) now - c.ts else 0L
                    if (c != null && age < 90_000) {
                        return b.copy(
                            reading = c.status.reading, source = Source.CLOUD, lastSeen = now,
                            cal = c.status.cal ?: b.cal,
                            // Each bin writes its own address, so a new one is picked up without typing it.
                            ip = c.localIp ?: b.ip,
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
        // A bin is called lost only after 90 s with no reading at all; until then its last one stays.
        if ((b.source == Source.LIVE || b.source == Source.CLOUD) && now - b.lastSeen < 90_000) return b
        return b.copy(source = Source.STALE)
    }

    private fun linkOf(bins: List<Bin>): Link = when {
        bins.none { it.ip.isNotBlank() } -> Link.DEMO
        bins.any { it.source == Source.LIVE } -> Link.LIVE
        bins.any { it.source == Source.CLOUD } -> Link.CLOUD
        else -> Link.OFFLINE
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
                full = r.fill >= c.v("fill_th"), hot = r.temp >= c.v("temp_th"), humid = r.hum >= c.v("hum_th"),
                gassy = r.gas >= c.v("gas_th"), status = r.status, source = b.source,
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
                BinStatus.LOCKED -> add(b, Severity.INFO, "lock", "Bin locked", "$n locked from the app", AlertType.LOCK)
                BinStatus.CALIBRATING -> add(b, Severity.INFO, "calib", "Calibration started", "$n is being calibrated", AlertType.INFO, kind = AlertKind.SETUP)
                BinStatus.COLLECTING -> {}
                BinStatus.OK -> when (p.status) {
                    BinStatus.LOCKED -> add(b, Severity.SUCCESS, "check", "Bin unlocked", "$n is open for use again", AlertType.LOCK, kind = AlertKind.LOCK)
                    BinStatus.COLLECTING -> add(b, Severity.SUCCESS, "truck", "Collection complete", "$n is back to normal", AlertType.PICKUP)
                    else -> {}
                }
            }
            if (b.ip.isNotBlank()) {
                if (f.source == Source.STALE && p.source != Source.STALE) _events.tryEmit(HubEvent.BinLost(n))
                if (f.source != Source.STALE && p.source == Source.STALE) _events.tryEmit(HubEvent.BinBack(n))
            }
        }
        prev.keys.retainAll(bins.map { it.id }.toSet())
    }

    private fun add(b: Bin, sev: Severity, icon: String, title: String, meta: String, type: AlertType, kind: AlertKind = Demo.kindFor(icon)) {
        val row = AlertRow(++alertSeq, sev, icon, kind, title, meta, System.currentTimeMillis())
        val list = (listOf(row) + _state.value.alerts).take(200)
        _state.value = _state.value.copy(alerts = list)
        store.saveAlerts(list)
        _events.tryEmit(HubEvent.NewAlert(row, type, b.id))
    }

    // ---------- commands ----------

    fun bin(id: String) = _state.value.bins.firstOrNull { it.id == id }

    private fun update(id: String, persist: Boolean = false, f: (Bin) -> Bin) {
        val s = _state.value
        val bins = s.bins.map { if (it.id == id) f(it) else it }
        _state.value = s.copy(bins = bins, link = linkOf(bins))
        if (persist) store.saveBins(bins)
    }

    /**
     * Sends one command. Over Wi-Fi every command works; over the cloud only
     * LOCK, UNLOCK and COLLECT. Returns null when it went through, or what to
     * tell the user when it did not. The card changes at once either way, the
     * same as the dashboard, and the next reading shows what the bin really did.
     */
    suspend fun command(id: String, cmd: String, extra: Map<String, Any> = emptyMap()): String? {
        val b = bin(id) ?: return null
        var problem: String? = null
        when (b.source) {
            Source.DEMO -> {}
            Source.LIVE -> try {
                LocalApi.command(b.ip, cmd, extra)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                problem = if (cloud.ready && cmd in Cloud.CLOUD_COMMANDS) {
                    runCatching { cloud.command(b.cloudId, cmd) }.exceptionOrNull()?.let { "${b.name} did not answer on Wi-Fi or the cloud." }
                } else "${b.name} did not answer. Check its power and Wi-Fi."
            }
            Source.CLOUD -> problem = if (cmd !in Cloud.CLOUD_COMMANDS) "That needs the bins' Wi-Fi."
            else runCatching { cloud.command(b.cloudId, cmd) }.exceptionOrNull()?.let { "The cloud did not take it. ${it.message.orEmpty()}" }
            Source.STALE -> problem = "${b.name} is not answering."
        }
        if (problem != null && b.source != Source.LIVE) return problem
        update(id) { x ->
            val r = x.reading
            x.copy(
                reading = when (cmd) {
                    "LOCK" -> r.copy(status = BinStatus.LOCKED, lidOpen = false)
                    "UNLOCK" -> r.copy(status = BinStatus.OK)
                    "CLEAR_TAMPER" -> r.copy(status = BinStatus.OK, tilt = 0.0)
                    "COLLECT" -> r.copy(status = BinStatus.COLLECTING, lidOpen = true)
                    "COLLECT_DONE" -> r.copy(status = BinStatus.OK, lidOpen = false, fill = 0.0, weight = 0.0, gas = minOf(r.gas, 48.0))
                    "TARE" -> r.copy(weight = 0.0)
                    "RESET_GAS" -> r.copy(iaqAcc = 0)
                    else -> r
                },
                sim = if (cmd == "CLEAR_TAMPER") x.sim.copy(values = x.sim.values + ("tamper" to 0.0)) else x.sim,
            )
        }
        return problem
    }

    /** Calibration mode, on one bin at a time. The bin shows its teal calibration screen while it is on. */
    suspend fun setCalibration(id: String?) {
        val old = _state.value.calibBin
        if (old == id) return
        _state.value = _state.value.copy(calibBin = id)
        old?.let { o -> bin(o)?.takeIf { it.source == Source.LIVE }?.let { runCatching { LocalApi.command(it.ip, "CALIBRATE_END") } } }
        old?.let { o -> update(o) { b -> if (b.reading.status == BinStatus.CALIBRATING) b.copy(reading = b.reading.copy(status = BinStatus.OK)) else b } }
        id?.let { n -> bin(n)?.takeIf { it.source == Source.LIVE }?.let { runCatching { LocalApi.command(it.ip, "CALIBRATE_START") } } }
        id?.let { n -> update(n) { b -> b.copy(reading = b.reading.copy(status = BinStatus.CALIBRATING)) } }
    }

    /** Stores new calibration values on the bin (EEPROM over Wi-Fi) and in the app. */
    suspend fun saveCal(id: String, cal: Cal): String? {
        val b = bin(id) ?: return null
        if (b.source == Source.LIVE) {
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
        if (b.source == Source.LIVE) {
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
            val index = s.bins.size + k
            Demo.newBin(index, "b$now-$k").copy(cloudId = "bin%02d".format(index + 1))
        }
        val bins = s.bins + added
        _state.value = s.copy(bins = bins, link = linkOf(bins))
        _history.value = _history.value + added.associate { it.id to emptyList() }
        store.saveBins(bins)
    }

    fun editBin(id: String, name: String, place: String, ip: String, cloudId: String) {
        val clean = ip.trim()
        update(id, persist = true) {
            val moved = clean != it.ip
            it.copy(
                name = name.trim().ifBlank { it.name }, place = place.trim(), ip = clean,
                cloudId = cloudId.trim().ifBlank { it.cloudId },
                source = if (moved) (if (clean.isBlank()) Source.DEMO else Source.STALE) else it.source,
            )
        }
        nextLocal.remove(id)
        nextCloud.remove(id)
    }

    fun removeBin(id: String) {
        val s = _state.value
        val bins = s.bins.filterNot { it.id == id }
        _state.value = s.copy(bins = bins, link = linkOf(bins), calibBin = s.calibBin.takeIf { it != id })
        store.saveBins(bins)
    }

    // ---------- alerts ----------

    fun markRead(ids: Set<Long>, read: Boolean = true) = editAlerts { list -> list.map { if (it.id in ids) it.copy(read = read) else it } }

    fun deleteAlerts(ids: Set<Long>) = editAlerts { list -> list.filterNot { it.id in ids } }

    private fun editAlerts(f: (List<AlertRow>) -> List<AlertRow>) {
        val list = f(_state.value.alerts)
        _state.value = _state.value.copy(alerts = list)
        store.saveAlerts(list)
    }

    // ---------- Firebase ----------

    fun firebase() = store.firebase()
    fun firebaseWorking() = store.firebaseWorking()

    fun saveFirebase(cfg: FirebaseConfig) {
        store.saveFirebase(cfg, passedTest = true)
        cloud.use(cfg)
        nextCloud.clear()
    }

    /** Estimated fill speed in percent per hour, from the last hour of history; demo bins use their own rate. */
    fun ratePerHour(b: Bin): Double {
        if (b.source == Source.DEMO) return b.rate
        val h = _history.value[b.id] ?: return 0.0
        val now = System.currentTimeMillis()
        val recent = h.filter { it.t >= now - 3600_000L }
        if (recent.size < 6) return 0.0
        val hours = (recent.last().t - recent.first().t) / 3600_000.0
        if (hours <= 0.05) return 0.0
        return ((recent.last().fill - recent.first().fill) / hours).coerceAtLeast(0.0)
    }

    private fun fmt(v: Double) = if (v == Math.floor(v)) v.toInt().toString() else "%.1f".format(v)

    fun store() = store
}
