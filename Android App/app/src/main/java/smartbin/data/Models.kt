package smartbin.data

/** How the app is reaching the bins right now. Local Wi-Fi first, Firebase second, ThingSpeak third. */
enum class Link { DEMO, LIVE, CLOUD, THINGSPEAK, OFFLINE }

/** Where one bin's latest reading came from. */
enum class Source { DEMO, LIVE, CLOUD, THINGSPEAK, STALE }

/** The three ways to the bins, and which of them answer right now. */
data class Routes(val local: Boolean = false, val firebase: Boolean = false, val thingspeak: Boolean = false) {
    val any get() = local || firebase || thingspeak
    val count get() = listOf(local, firebase, thingspeak).count { it }
}

enum class BinStatus { OK, LOCKED, TAMPER, COLLECTING, CALIBRATING }

enum class Role { OPERATOR, COLLECTOR }

/** The app-wide colour mode, the same names as the dashboard. */
enum class Mode { NORMAL, SIM, CALIB, DUAL, ALERT }

enum class Severity { CRITICAL, WARNING, INFO, SUCCESS }

/** The kinds the alert filter bar knows. */
enum class AlertKind(val label: String, val one: String, val many: String) {
    ALL("All", "alert", "alerts"),
    FULL("Full bins", "full-bin alert", "full-bin alerts"),
    SENSOR("Sensor readings", "sensor alert", "sensor alerts"),
    TAMPER("Tamper", "tamper alert", "tamper alerts"),
    LOCK("Lock and unlock", "lock event", "lock events"),
    PICKUP("Pickups", "pickup", "pickups"),
    SETUP("Calibration and settings", "setup event", "setup events"),
}

/** One reading from a bin, the fields of GET /api/status. */
data class Reading(
    val fill: Double = 0.0,
    val distance: Double = 30.0,
    val weight: Double = 0.0,
    val temp: Double = 28.0,
    val tempRaw: Double = 30.0,
    val hum: Double = 60.0,
    val press: Double = 1008.4,
    val gas: Double = 60.0,
    val iaqAcc: Int = 3,
    val tilt: Double = 0.0,
    val motion: Boolean = false,
    val lidOpen: Boolean = false,
    val status: BinStatus = BinStatus.OK,
    /** When the phone received it. */
    val at: Long = 0L,
)

/**
 * The calibration values of one bin, kept as named numbers so they map one to
 * one onto the JSON of POST /api/calibrate and onto the Settings steppers.
 */
typealias Cal = Map<String, Double>

val DEFAULT_CAL: Cal = mapOf(
    "d_empty" to 30.0, "d_full" to 5.0, "fill_th" to 80.0, "temp_th" to 40.0, "hum_th" to 85.0,
    "gas_th" to 200.0, "tilt_th" to 30.0, "weight_cap" to 4500.0, "auto_lock" to 0.0,
    "servo_closed" to 0.0, "servo_open" to 90.0, "collect_pin" to 1234.0, "pir_hold_s" to 5.0,
    "buzz_s" to 2.0, "temp_off" to -2.0, "hum_off" to 0.0, "press_off" to 0.0,
)

fun Cal.v(key: String): Double = this[key] ?: DEFAULT_CAL[key] ?: 0.0

/** Simulation: one bit per sensor that is held by hand, and the values it is held at. */
data class Sim(
    val mask: Int = 0,
    val values: Map<String, Double> = DEFAULT_SIM,
) {
    fun on(bit: Int) = (mask shr bit) and 1 == 1
}

val DEFAULT_SIM: Map<String, Double> = mapOf(
    "fill" to 60.0, "weight" to 2000.0, "gas" to 120.0, "temp" to 28.0, "humidity" to 60.0,
    "tamper" to 0.0, "motion" to 0.0, "lid" to 0.0,
)

data class Bin(
    /** Stable id inside the app. */
    val id: String,
    /** The bin's node in Firebase, /sb/bins/<cloudId>. */
    val cloudId: String,
    val name: String,
    val place: String,
    val ip: String = "",
    val cal: Cal = DEFAULT_CAL,
    val sim: Sim = Sim(),
    val reading: Reading = Reading(),
    val source: Source = Source.DEMO,
    /** Last time a real reading arrived, over Wi-Fi or the cloud. */
    val lastSeen: Long = 0L,
    /** Demo only: how fast it fills, in percent per hour. */
    val rate: Double = 0.5,
    /** ThingSpeak: the bin's channel and its read key, and the TalkBack queue it takes commands from. */
    val tsChannel: String = "",
    val tsKey: String = "",
    val tbId: String = "",
    val tbKey: String = "",
) {
    val onThingSpeak get() = tsChannel.isNotBlank()
    /** No address and no ThingSpeak channel: the app makes up its readings. */
    val demo get() = ip.isBlank() && !onThingSpeak
    val stale get() = source == Source.STALE
    val calibrating get() = reading.status == BinStatus.CALIBRATING
    val simulating get() = sim.mask > 0
    val full get() = reading.fill >= cal.v("fill_th")
    val sensorOver get() = reading.temp >= cal.v("temp_th") || reading.hum >= cal.v("hum_th") ||
        reading.gas >= cal.v("gas_th") || reading.weight >= cal.v("weight_cap")
    val urgent get() = reading.status == BinStatus.TAMPER || full
    val needsAction get() = urgent || sensorOver
}

/** One point of history for the charts. */
data class Sample(val t: Long, val fill: Float, val temp: Float, val hum: Float, val press: Float, val gas: Float)

data class AlertRow(
    val id: Long,
    val sev: Severity,
    /** Name of the icon, as in the mockup: fill, gas, tamper, humid, lock, check, truck, calib, temp. */
    val icon: String,
    val kind: AlertKind,
    val title: String,
    val meta: String,
    val at: Long,
    val read: Boolean = false,
)

/** The ThingSpeak server. The real one unless the mock server is used. */
const val THINGSPEAK_URL = "https://api.thingspeak.com"

data class FirebaseConfig(val url: String = "", val key: String = "", val email: String = "", val password: String = "") {
    val filled get() = url.isNotBlank() && key.isNotBlank() && email.isNotBlank() && password.isNotBlank()
}

/**
 * A command sent through Firebase or ThingSpeak that the bin has not confirmed
 * yet. Its key stays locked until the bin's next reading shows it was done,
 * or until [deadline] passes.
 */
data class Pending(val cmd: String, val via: Source, val at: Long, val deadline: Long)

/** Everything the screens draw, in one value. */
data class HubState(
    val bins: List<Bin> = emptyList(),
    val link: Link = Link.DEMO,
    val alerts: List<AlertRow> = emptyList(),
    /** Calibration mode, on one bin at a time. */
    val calibBin: String? = null,
    /** Bytes the cloud reported for this month, when it reports them. */
    val cloudBytes: Long? = null,
    val loading: Boolean = true,
    /** Firebase and ThingSpeak answered within the last minute. Null when they are not set up. */
    val fbUp: Boolean? = null,
    val tsUp: Boolean? = null,
    /** ThingSpeak messages a day for all channels, estimated from how fast their entry numbers rise. */
    val tsPerDay: Double? = null,
    /** Demo options: links to pretend are up, so the link colours can be shown with no hardware. */
    val demoLinks: Routes = Routes(),
    /** Cloud commands waiting for the bin to confirm them, by bin id. */
    val pending: Map<String, Pending> = emptyMap(),
) {
    val routes: Routes
        get() = if (link == Link.DEMO) demoLinks
        else Routes(local = bins.any { it.source == Source.LIVE }, firebase = fbUp == true, thingspeak = tsUp == true)
    val mode: Mode
        get() {
            val cal = calibBin != null
            val sim = bins.any { it.simulating }
            return when {
                cal && sim -> Mode.DUAL
                cal -> Mode.CALIB
                sim -> Mode.SIM
                bins.any { it.urgent } -> Mode.ALERT
                else -> Mode.NORMAL
            }
        }
    val unread get() = alerts.count { !it.read }
}
