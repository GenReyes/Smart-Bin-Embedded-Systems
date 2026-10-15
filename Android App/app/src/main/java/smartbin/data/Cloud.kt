package smartbin.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Firebase Realtime Database over plain REST, the way Garbage-Guard does it:
 * no SDK, so the URL, key and account can be typed in at run time.
 *
 * Data layout (the firmware writes the same paths):
 *   /sb/bins/<cloudId>/status    the /api/status JSON plus "ts" (ms) and "local_ip"
 *   /sb/commands/<cloudId>       the app pushes {"cmd": "LOCK", "ts": ...}; the bin runs and removes it
 *   /sb/usage/bytes_month        optional, the month's download total for the meter
 *   /sb_users/<uid>              the accounts allowed in; Test reads this
 *
 * Only LOCK, UNLOCK and COLLECT go over the cloud. Calibration and
 * simulation need live readings, so they stay on the bins' Wi-Fi.
 */
class Cloud(private var config: FirebaseConfig) {
    private val signIn = Mutex()
    private var token: String? = null
    private var tokenFor: FirebaseConfig? = null
    private var tokenExpires = 0L

    fun use(cfg: FirebaseConfig) {
        config = cfg
    }

    val ready get() = config.filled

    class CloudStatus(val status: LocalApi.Status, val ts: Long, val localIp: String?)

    suspend fun status(cloudId: String): CloudStatus? {
        val raw = rest("GET", "sb/bins/$cloudId/status") ?: return null
        val o = JSONObject(raw)
        return CloudStatus(LocalApi.parse(o), o.optLong("ts", 0L), o.optString("local_ip").takeIf { it.isNotBlank() })
    }

    suspend fun command(cloudId: String, cmd: String) {
        require(cmd in CLOUD_COMMANDS) { "$cmd needs the bins' Wi-Fi" }
        val body = JSONObject().put("cmd", cmd).put("ts", System.currentTimeMillis()).put("from", "app")
        rest("POST", "sb/commands/$cloudId", body.toString())
    }

    suspend fun usageBytes(): Long? = rest("GET", "sb/usage/bytes_month")?.trim()?.toLongOrNull()

    /** Signs in with [cfg] and reads its line in /sb_users, the same check as the dashboard's TEST. */
    suspend fun test(cfg: FirebaseConfig): Result<Int> = try {
        val (idToken, uid) = signInWith(cfg)
        call(cfg.url, "GET", "sb_users/$uid", idToken)
            ?: throw IOException("The account is not on the allowed list (sb_users).")
        val bins = call(cfg.url, "GET", "sb/bins", idToken, query = "&shallow=true")
        Result.success(bins?.let { runCatching { JSONObject(it).length() }.getOrDefault(0) } ?: 0)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(IOException(friendly(e.message.orEmpty())))
    }

    private suspend fun signInWith(cfg: FirebaseConfig): Pair<String, String> {
        val body = JSONObject().put("email", cfg.email.trim()).put("password", cfg.password).put("returnSecureToken", true)
        val o = JSONObject(http("POST", authUrl(cfg), body.toString()))
        tokenExpires = System.currentTimeMillis() + (o.optString("expiresIn", "3600").toLongOrNull() ?: 3600L) * 1000
        return o.getString("idToken") to o.optString("localId")
    }

    /** A token for the saved settings, renewed when under five minutes remain or the settings changed. */
    private suspend fun auth(): String = signIn.withLock {
        val cfg = config
        val current = token
        if (current != null && tokenFor == cfg && System.currentTimeMillis() < tokenExpires - 300_000) return@withLock current
        val (fresh, _) = signInWith(cfg)
        token = fresh
        tokenFor = cfg
        fresh
    }

    private suspend fun rest(method: String, path: String, body: String? = null): String? {
        if (!ready) return null
        return call(config.url, method, path, auth(), body)
    }

    private suspend fun call(db: String, method: String, path: String, idToken: String, body: String? = null, query: String = ""): String? {
        val text = http(method, "${db.trim().trimEnd('/')}/$path.json?auth=${enc(idToken)}$query", body)
        return text.takeIf { it.isNotBlank() && it.trim() != "null" }
    }

    private suspend fun http(method: String, url: String, body: String?): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 8000
        conn.readTimeout = 10000
        try {
            if (body != null) {
                val bytes = body.toByteArray()
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException(errorOf(text, code))
            text
        } catch (e: IOException) {
            conn.disconnect()
            throw e
        }
    }

    /** Firebase reports errors as {"error": "text"} or {"error": {"message": "CODE"}}. */
    private fun errorOf(text: String, code: Int): String {
        val o = runCatching { JSONObject(text) }.getOrNull()
        val inner = o?.optJSONObject("error")?.optString("message").orEmpty()
        val flat = if (o != null && o.optJSONObject("error") == null) o.optString("error") else ""
        return inner.ifEmpty { flat }.ifEmpty { "HTTP $code" }
    }

    /** A plain-http database address can only be the mock server, so sign-in goes there too. */
    private fun authUrl(cfg: FirebaseConfig): String =
        if (cfg.url.startsWith("http://")) "${cfg.url.trim().trimEnd('/')}/__auth/signInWithPassword?key=${enc(cfg.key)}"
        else "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${enc(cfg.key.trim())}"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        val CLOUD_COMMANDS = setOf("LOCK", "UNLOCK", "COLLECT")

        /** Firebase's error codes in plain words. */
        fun friendly(message: String): String = when {
            "INVALID_LOGIN_CREDENTIALS" in message || "INVALID_PASSWORD" in message || "EMAIL_NOT_FOUND" in message ||
                "INVALID_EMAIL" in message -> "Wrong email or password."
            "API key not valid" in message || "API_KEY_INVALID" in message -> "The Web API key is wrong. Copy it again from the Firebase console."
            "PASSWORD_LOGIN_DISABLED" in message || "OPERATION_NOT_ALLOWED" in message ->
                "Email/Password sign-in is off in Firebase Authentication."
            "TOO_MANY_ATTEMPTS_TRY_LATER" in message -> "Too many tries, wait a few minutes."
            "USER_DISABLED" in message -> "This account is disabled."
            "Permission denied" in message || "HTTP 401" in message ->
                "The account is not on the allowed list (sb_users), or the rules were not pasted."
            "HTTP 404" in message -> "The database URL is wrong."
            "sb_users" in message -> message
            "Unable to resolve host" in message || "failed to connect" in message || "timeout" in message.lowercase() ->
                "No internet, or the database URL is wrong."
            else -> "Could not sign in. ${message.take(80)}"
        }

        /** The same checks as the dashboard. Null means every field looks right. */
        fun problemWith(cfg: FirebaseConfig): String? {
            val url = cfg.url.trim()
            val local = url.startsWith("http://")
            return when {
                !url.startsWith("https://") && !local -> "The database URL is wrong. It starts with https:// and ends in .firebasedatabase.app."
                url.endsWith("/") -> "Remove the / at the end of the database URL."
                !local && !(url.endsWith(".firebasedatabase.app") || url.endsWith(".firebaseio.com")) ->
                    "The database URL is wrong. It starts with https:// and ends in .firebasedatabase.app."
                !local && (!cfg.key.trim().startsWith("AIza") || cfg.key.trim().length != 39) ->
                    "The Web API key is wrong. Copy it again from the Firebase console."
                !Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""").matches(cfg.email.trim()) || cfg.password.length < 6 ->
                    "Wrong email or password."
                else -> null
            }
        }
    }
}
