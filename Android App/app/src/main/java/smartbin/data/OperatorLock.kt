package smartbin.data

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The operator PIN. Only a salted PBKDF2 hash is kept, never the PIN. Wrong
 * tries are counted on disk, so closing the app does not reset them: after 5
 * the lock waits 30 s, and the wait doubles with every miss after that.
 */
class OperatorLock(private val store: Store) {
    val hasPin get() = store.string(KEY_HASH, "").isNotEmpty()

    /** Milliseconds until another try is allowed, 0 when one is. */
    fun waitLeft(now: Long = System.currentTimeMillis()) = (store.string(KEY_UNTIL, "0").toLongOrNull() ?: 0L).minus(now).coerceAtLeast(0L)

    fun set(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        store.putString(KEY_SALT, b64(salt))
        store.putString(KEY_HASH, b64(hash(pin, salt)))
        store.putString(KEY_MISSES, "0")
        store.putString(KEY_UNTIL, "0")
    }

    /** True when the PIN matches. A miss counts toward the wait. */
    fun check(pin: String): Boolean {
        if (!hasPin || waitLeft() > 0) return false
        val salt = Base64.decode(store.string(KEY_SALT, ""), Base64.NO_WRAP)
        val want = Base64.decode(store.string(KEY_HASH, ""), Base64.NO_WRAP)
        if (MessageDigest.isEqual(hash(pin, salt), want)) {
            store.putString(KEY_MISSES, "0")
            return true
        }
        val misses = (store.string(KEY_MISSES, "0").toIntOrNull() ?: 0) + 1
        store.putString(KEY_MISSES, misses.toString())
        if (misses >= FREE_TRIES) {
            val wait = 30_000L shl (misses - FREE_TRIES).coerceAtMost(6)
            store.putString(KEY_UNTIL, (System.currentTimeMillis() + wait).toString())
        }
        return false
    }

    /** Tries left before the lock starts waiting. */
    fun triesLeft() = (FREE_TRIES - (store.string(KEY_MISSES, "0").toIntOrNull() ?: 0)).coerceAtLeast(0)

    private fun hash(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, 20_000, 256)).encoded

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    companion object {
        const val MIN = 4
        const val MAX = 8
        private const val FREE_TRIES = 5
        private const val KEY_HASH = "op_pin_hash"
        private const val KEY_SALT = "op_pin_salt"
        private const val KEY_MISSES = "op_pin_misses"
        private const val KEY_UNTIL = "op_pin_until"

        fun valid(pin: String) = pin.length in MIN..MAX && pin.all { it.isDigit() }
    }
}
