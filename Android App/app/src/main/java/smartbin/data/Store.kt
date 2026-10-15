package smartbin.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import smartbin.BuildConfig
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * What the app remembers between runs: the bin list, alert history, the
 * user's choices, and the Firebase account. The Firebase password is sealed
 * with a key in the Android Keystore, so it is never on disk in the clear.
 */
class Store(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("smartbin", Context.MODE_PRIVATE)

    // ---------- small values ----------

    fun bool(key: String, def: Boolean) = prefs.getBoolean(key, def)
    fun putBool(key: String, v: Boolean) = prefs.edit().putBoolean(key, v).apply()
    fun string(key: String, def: String) = prefs.getString(key, def) ?: def
    fun putString(key: String, v: String) = prefs.edit().putString(key, v).apply()

    // ---------- bins ----------

    fun bins(): List<Bin>? {
        val raw = prefs.getString("bins", null) ?: return null
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val cal = DEFAULT_CAL.toMutableMap()
                o.optJSONObject("cal")?.let { c -> c.keys().forEach { k -> cal[k] = c.optDouble(k, cal[k] ?: 0.0) } }
                Bin(
                    id = o.getString("id"),
                    cloudId = o.optString("cloudId", "bin%02d".format(i + 1)),
                    name = o.optString("name", "Bin %02d".format(i + 1)),
                    place = o.optString("place", ""),
                    ip = o.optString("ip", ""),
                    cal = cal,
                    rate = o.optDouble("rate", 0.5),
                    tsChannel = o.optString("tsChannel", ""),
                    tsKey = o.optString("tsKey", ""),
                    tbId = o.optString("tbId", ""),
                    tbKey = o.optString("tbKey", ""),
                    reading = Reading(fill = o.optDouble("fill", 0.0), weight = o.optDouble("weight", 0.0), gas = o.optDouble("gas", 60.0)),
                )
            }
        }.getOrNull()
    }

    fun saveBins(bins: List<Bin>) {
        val a = JSONArray()
        bins.forEach { b ->
            val cal = JSONObject()
            b.cal.forEach { (k, v) -> cal.put(k, v) }
            a.put(
                JSONObject().put("id", b.id).put("cloudId", b.cloudId).put("name", b.name).put("place", b.place)
                    .put("ip", b.ip).put("cal", cal).put("rate", b.rate)
                    .put("tsChannel", b.tsChannel).put("tsKey", b.tsKey).put("tbId", b.tbId).put("tbKey", b.tbKey)
                    .put("fill", b.reading.fill).put("weight", b.reading.weight).put("gas", b.reading.gas)
            )
        }
        prefs.edit().putString("bins", a.toString()).apply()
    }

    // ---------- alerts ----------

    fun alerts(): List<AlertRow>? {
        val raw = prefs.getString("alerts", null) ?: return null
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                AlertRow(
                    id = o.getLong("id"),
                    sev = Severity.valueOf(o.getString("sev")),
                    icon = o.getString("icon"),
                    kind = AlertKind.valueOf(o.getString("kind")),
                    title = o.getString("title"),
                    meta = o.getString("meta"),
                    at = o.getLong("at"),
                    read = o.optBoolean("read"),
                )
            }
        }.getOrNull()
    }

    fun saveAlerts(rows: List<AlertRow>) {
        val a = JSONArray()
        rows.forEach { r ->
            a.put(
                JSONObject().put("id", r.id).put("sev", r.sev.name).put("icon", r.icon).put("kind", r.kind.name)
                    .put("title", r.title).put("meta", r.meta).put("at", r.at).put("read", r.read)
            )
        }
        prefs.edit().putString("alerts", a.toString()).apply()
    }

    // ---------- Firebase account ----------

    /** The team's account from local.properties at build time. Empty when none was given. */
    fun firebaseDefaults() = FirebaseConfig(BuildConfig.FB_URL, BuildConfig.FB_KEY, BuildConfig.FB_EMAIL, BuildConfig.FB_PASSWORD)

    /** The saved account, or the built-in one when nothing was saved. */
    fun firebase(): FirebaseConfig = readFb("saved") ?: firebaseDefaults()

    /** The last account that passed a test. */
    fun firebaseWorking(): FirebaseConfig? = readFb("good") ?: firebaseDefaults().takeIf { it.filled }

    fun saveFirebase(cfg: FirebaseConfig, passedTest: Boolean) {
        writeFb("saved", cfg)
        if (passedTest) writeFb("good", cfg)
    }

    private fun readFb(slot: String): FirebaseConfig? {
        val url = prefs.getString("${slot}_url", null) ?: return null
        return FirebaseConfig(
            url = url,
            key = prefs.getString("${slot}_key", "").orEmpty(),
            email = prefs.getString("${slot}_email", "").orEmpty(),
            password = open(prefs.getString("${slot}_pw", "").orEmpty()),
        )
    }

    private fun writeFb(slot: String, cfg: FirebaseConfig) {
        prefs.edit()
            .putString("${slot}_url", cfg.url.trim())
            .putString("${slot}_key", cfg.key.trim())
            .putString("${slot}_email", cfg.email.trim())
            .putString("${slot}_pw", seal(cfg.password))
            .apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val maker = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        maker.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return maker.generateKey()
    }

    private fun seal(plain: String): String = runCatching {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = cipher.doFinal(plain.toByteArray())
        Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(out, Base64.NO_WRAP)
    }.getOrDefault("")

    private fun open(sealed: String): String = runCatching {
        val parts = sealed.split(':')
        if (parts.size != 2) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)))
    }.getOrDefault("")

    private companion object {
        const val KEY_ALIAS = "smartbin_cloud_key"
    }
}
