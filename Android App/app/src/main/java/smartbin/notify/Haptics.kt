package smartbin.notify

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.roundToInt

/**
 * The app's own vibration patterns, for moments that deserve more than a
 * plain click: a full bin, tamper, a lock, a pickup, the link dropping. Each
 * has its own rhythm, so the phone can be told apart in a pocket. Plain keys
 * keep Android's touch clicks in [Feel]; these patterns follow the Vibration
 * switch and the strength chosen in Settings.
 */
object Haptics {
    enum class Strength(val label: String, val scale: Float) {
        LIGHT("Light", 0.45f), NORMAL("Normal", 0.75f), STRONG("Strong", 1f),
    }

    @Volatile var strength = Strength.NORMAL

    /** One pattern, as (milliseconds, amplitude) steps. Amplitude runs 1 to 255; 0 is a pause. */
    enum class Buzz(vararg val steps: Pair<Int, Int>) {
        // Alerts: long and hard. Routine events: short and soft.
        FULL(300 to 255, 120 to 0, 300 to 255, 120 to 0, 300 to 255),
        TAMPER(90 to 255, 60 to 0, 90 to 255, 60 to 0, 90 to 255, 60 to 0, 400 to 255),
        SENSOR(150 to 200, 100 to 0, 300 to 200),
        LOCK(60 to 200, 50 to 0, 160 to 150),
        UNLOCK(160 to 150, 50 to 0, 60 to 200),
        PICKUP(40 to 120, 60 to 0, 40 to 160, 60 to 0, 120 to 220),
        DONE(30 to 120, 40 to 0, 30 to 160, 40 to 0, 30 to 200, 40 to 0, 140 to 255),
        INFO(40 to 120, 80 to 0, 60 to 170),

        // In the app only.
        DANGER(60 to 255, 70 to 0, 90 to 255),
        LOGO(40 to 80, 40 to 0, 40 to 150, 40 to 0, 70 to 255),
        BOOT(30 to 40, 30 to 80, 30 to 120, 30 to 160, 40 to 200),
        SAVED(20 to 170, 60 to 0, 20 to 170),
        DENIED(50 to 200, 50 to 0, 50 to 200),
        CALIB(20 to 140, 80 to 0, 20 to 140, 80 to 0, 20 to 140, 80 to 0, 80 to 220),
        SIM(60 to 90, 40 to 130, 40 to 170, 60 to 210),
        LINK_LOST(120 to 220, 100 to 150, 120 to 80, 140 to 40),
        LINK_UP(40 to 120, 60 to 0, 70 to 230),
        CLOUD(50 to 140, 60 to 0, 50 to 140, 60 to 0, 120 to 90),
        BIN_LOST(80 to 150, 80 to 60),
        BIN_BACK(40 to 70, 50 to 0, 50 to 160),
        ;

        /** The same rhythm as a notification channel wants it: a pause first, then buzz and pause in turn. */
        fun channelPattern(): LongArray {
            val out = mutableListOf(0L)
            var on = false
            steps.forEach { (ms, amp) ->
                if ((amp > 0) == on) out[out.lastIndex] = out.last() + ms
                else { out.add(ms.toLong()); on = amp > 0 }
            }
            return out.toLongArray()
        }
    }

    private var vibrator: Vibrator? = null

    fun init(context: Context) {
        if (vibrator != null) return
        val app = context.applicationContext
        vibrator = runCatching {
            if (Build.VERSION.SDK_INT >= 31) app.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION") (app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
        }.getOrNull()
    }

    /** Plays one pattern, unless vibration is switched off in Settings. */
    fun play(buzz: Buzz) {
        if (!Feel.haptics) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val scale = strength.scale
        val timings = LongArray(buzz.steps.size) { buzz.steps[it].first.toLong() }
        val amps = IntArray(buzz.steps.size) {
            val a = buzz.steps[it].second
            if (a == 0) 0 else (a * scale).roundToInt().coerceIn(1, 255)
        }
        try {
            v.vibrate(VibrationEffect.createWaveform(timings, amps, -1))
        } catch (_: Exception) {
            // No motor: the sound still plays.
        }
    }

    /** The pattern that goes with a sound, for the sounds that have one. */
    fun forSfx(sfx: Sfx): Buzz? = when (sfx) {
        Sfx.DANGER -> Buzz.DANGER
        Sfx.LOGO -> Buzz.LOGO
        Sfx.DENIED -> Buzz.DENIED
        Sfx.SAVED -> Buzz.SAVED
        Sfx.BOOT -> Buzz.BOOT
        Sfx.LINK_LOST -> Buzz.LINK_LOST
        Sfx.LINK_UP -> Buzz.LINK_UP
        Sfx.CLOUD -> Buzz.CLOUD
        Sfx.LOCK -> Buzz.LOCK
        Sfx.UNLOCK -> Buzz.UNLOCK
        Sfx.PICKUP -> Buzz.PICKUP
        Sfx.DONE -> Buzz.DONE
        Sfx.CALIB -> Buzz.CALIB
        Sfx.SIM -> Buzz.SIM
        Sfx.BIN_LOST -> Buzz.BIN_LOST
        Sfx.BIN_BACK -> Buzz.BIN_BACK
        Sfx.ALERT_FULL -> Buzz.FULL
        Sfx.ALERT_TAMPER -> Buzz.TAMPER
        Sfx.ALERT_SENSOR -> Buzz.SENSOR
        Sfx.ALERT_LOCK -> Buzz.LOCK
        Sfx.ALERT_PICKUP -> Buzz.DONE
        Sfx.ALERT_INFO -> Buzz.INFO
        else -> null
    }
}
