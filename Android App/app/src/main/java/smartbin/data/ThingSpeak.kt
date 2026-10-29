package smartbin.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * ThingSpeak, the third way to the bins. Each bin has its own channel and
 * writes to it at most every 15 s (the free licence's limit). The app reads
 * the newest entry with the channel's read key, and sends LOCK, UNLOCK and
 * COLLECT through the bin's TalkBack queue, which the bin polls.
 *
 * Channel fields (the firmware writes the same ones):
 *   field1 fill %         field5 air quality (IAQ)
 *   field2 weight g       field6 pressure hPa
 *   field3 temperature C  field7 tilt deg
 *   field4 humidity %     field8 flags, below
 *   status (text)         the bin's local IP address
 * Field 8: motion + 2 * lid open + 4 * status (status 0 OK, 1 LOCKED, 2 TAMPER,
 * 3 COLLECTING, 4 CALIBRATING). Firmware from 29 Oct 2026 also sets 512 and
 * adds the modes (32 locked, 64 collecting, 128 calibrating, 256 tamper) and
 * the missing sensors (1024 HC-SR04, 2048 HX711, 4096 BME680, 8192 BMI160).
 * A missing sensor's field is left empty.
 */
class ThingSpeak(private var server: String = THINGSPEAK_URL) {

    fun use(url: String) {
        server = url.trim().trimEnd('/').ifBlank { THINGSPEAK_URL }
    }

    val url get() = server

    /** [entryId] goes up by one with each write, which is how the app estimates the message count. */
    class Entry(val reading: Reading, val ts: Long, val localIp: String?, val entryId: Long)

    /** The newest entry of a bin's channel, or null when the channel has none yet. */
    suspend fun last(channel: String, key: String): Entry? {
        val q = "api_key=${enc(key.trim())}&status=true"
        val text = http("GET", "$server/channels/${enc(channel.trim())}/feeds/last.json?$q", null)
        if (text.isBlank() || text.trim() == "-1" || text.trim() == "null") return null
        val o = JSONObject(text)
        fun f(n: Int, def: Double) = o.optString("field$n").toDoubleOrNull() ?: def
        val flags = f(8, 0.0).toInt()
        val status = BinStatus.entries.getOrElse((flags shr 2) and 7) { BinStatus.OK }
        val full = flags and 512 != 0
        val modes = if (full) Modes(flags and 32 != 0, flags and 64 != 0, flags and 128 != 0, flags and 256 != 0) else null
        val faults = if (full) FAULT_BITS.filter { (bit, _) -> flags and bit != 0 }.map { it.second } else emptyList()
        val r = Reading(
            fill = f(1, 0.0), weight = f(2, 0.0), temp = f(3, 28.0), tempRaw = f(3, 28.0), hum = f(4, 60.0),
            gas = f(5, 60.0), press = f(6, 1008.4), tilt = f(7, 0.0),
            motion = flags and 1 == 1, lidOpen = flags and 2 == 2, status = status,
            at = System.currentTimeMillis(), faults = faults, modes = modes,
            hasFill = !full || o.optString("field1").toDoubleOrNull() != null,
        )
        return Entry(r, parseTime(o.optString("created_at")), o.optString("status").takeIf { it.isNotBlank() && it != "null" }, o.optLong("entry_id", 0L))
    }

    /** Puts a command on the bin's TalkBack queue. Only LOCK, UNLOCK and COLLECT. */
    suspend fun command(tbId: String, tbKey: String, cmd: String) {
        require(cmd in Cloud.CLOUD_COMMANDS) { "$cmd needs the bins' Wi-Fi" }
        val body = "api_key=${enc(tbKey.trim())}&command_string=${enc(cmd)}"
        http("POST", "$server/talkbacks/${enc(tbId.trim())}/commands.json", body)
    }

    private suspend fun http(method: String, url: String, form: String?): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 8000
        conn.readTimeout = 10000
        try {
            if (form != null) {
                val bytes = form.toByteArray()
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException(if (code == 400 || code == 401) "The channel ID or key is wrong." else "HTTP $code")
            text
        } catch (e: IOException) {
            conn.disconnect()
            throw e
        }
    }

    private fun parseTime(s: String): Long = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)!!.time
    }.getOrDefault(0L)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private companion object {
        val FAULT_BITS = listOf(1024 to "HC-SR04", 2048 to "HX711", 4096 to "BME680", 8192 to "BMI160")
    }
}
