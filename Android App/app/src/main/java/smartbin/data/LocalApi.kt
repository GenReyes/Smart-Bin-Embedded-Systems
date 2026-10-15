package smartbin.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The bin's own REST API over the same Wi-Fi (implementation plan, section
 * "Local API"). Every call has a 1.5 s timeout, the same as the dashboard, so
 * a bin that is off is noticed quickly and the app can try the cloud.
 *
 *   GET  /api/status     every reading, the bin status, its calibration and sim mask
 *   POST /api/command    {"cmd": "LOCK"} and the other commands
 *   POST /api/calibrate  the calibration values, stored in EEPROM
 *   POST /api/simulate   {"mask": 5, "values": {...}}
 */
object LocalApi {
    private const val TIMEOUT_MS = 1500

    /** The parts of /api/status the app uses. */
    class Status(val reading: Reading, val cal: Cal?, val simMask: Int?)

    suspend fun status(ip: String): Status = parse(JSONObject(call(ip, "/api/status", null)))

    suspend fun command(ip: String, cmd: String, extra: Map<String, Any> = emptyMap()) {
        val o = JSONObject().put("cmd", cmd)
        extra.forEach { (k, v) -> o.put(k, v) }
        call(ip, "/api/command", o.toString())
    }

    suspend fun calibrate(ip: String, cal: Cal) {
        val o = JSONObject()
        cal.forEach { (k, v) -> o.put(k, if (v == Math.floor(v)) v.toLong() else v) }
        call(ip, "/api/calibrate", o.toString())
    }

    suspend fun simulate(ip: String, sim: Sim) {
        val values = JSONObject()
        sim.values.forEach { (k, v) -> values.put(k, v) }
        call(ip, "/api/simulate", JSONObject().put("mask", sim.mask).put("values", values).toString())
    }

    /** An address the user typed, with or without http:// and a port. */
    fun base(ip: String): String {
        val t = ip.trim().trimEnd('/')
        return if (t.startsWith("http://") || t.startsWith("https://")) t else "http://$t"
    }

    private suspend fun call(ip: String, path: String, body: String?): String = withContext(Dispatchers.IO) {
        if (ip.isBlank()) throw IOException("no address")
        val conn = URL(base(ip) + path).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.useCaches = false
        try {
            if (body != null) {
                val bytes = body.toByteArray()
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            conn.inputStream.bufferedReader().use { it.readText() }.ifBlank { "{}" }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Reads the status JSON. Field names follow the plan; the older names the
     * dashboard also accepts (fill_percentage, weight_grams and so on) work too.
     */
    fun parse(j: JSONObject): Status {
        fun num(vararg keys: String, def: Double): Double {
            for (k in keys) if (j.has(k) && !j.isNull(k)) {
                val d = j.optDouble(k)
                if (!d.isNaN()) return d
            }
            return def
        }
        val temp = num("temp", "temperature", def = 28.0)
        val lidRaw = j.opt("lid") ?: j.opt("lid_state")
        val lidOpen = lidRaw == 1 || lidRaw.toString().equals("OPEN", true) || lidRaw == true
        val statusRaw = j.opt("status") ?: j.opt("bin_status")
        val names = BinStatus.entries
        val status = when (statusRaw) {
            is Number -> names.getOrElse(statusRaw.toInt()) { BinStatus.OK }
            null -> BinStatus.OK
            else -> names.firstOrNull { it.name.equals(statusRaw.toString(), true) } ?: BinStatus.OK
        }
        val reading = Reading(
            fill = num("fill", "fill_percentage", def = 0.0).coerceIn(0.0, 100.0),
            distance = num("distance_cm", "distance", def = 30.0),
            weight = num("weight_g", "weight_grams", "weight", def = 0.0),
            temp = temp,
            tempRaw = num("temp_raw", def = temp),
            hum = num("humidity", def = 60.0),
            press = num("pressure", "pressure_hpa", def = 1008.0),
            gas = num("iaq", "gas", "gas_level", def = 60.0),
            iaqAcc = num("iaq_acc", def = 3.0).toInt(),
            tilt = num("tilt", "tilt_deg", def = 0.0),
            motion = j.optBoolean("motion", j.optInt("pir", 0) == 1),
            lidOpen = lidOpen,
            status = status,
            at = System.currentTimeMillis(),
        )
        val cal = j.optJSONObject("cal")?.let { c ->
            val m = DEFAULT_CAL.toMutableMap()
            c.keys().forEach { k -> c.optDouble(k).takeUnless { it.isNaN() }?.let { m[k] = it } }
            m.toMap()
        }
        val mask = if (j.has("sim_mask")) j.optInt("sim_mask") else null
        return Status(reading, cal, mask)
    }
}
