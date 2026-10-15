package smartbin.notify

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import smartbin.R

/**
 * The app's own sound for each kind of action, built by tools/make_sounds.py.
 * Rising pitch means forward, on, open or OK; falling means back, off, close
 * or no. Alerts that must sound while the app is closed go through the
 * notification channels in [Alerts], because only Android can play those.
 */
enum class Sfx(val res: Int, val volume: Float = 0.6f) {
    TAP(R.raw.sb_tap),
    CHOICE(R.raw.sb_choice),
    SWITCH_ON(R.raw.sb_switch_on),
    SWITCH_OFF(R.raw.sb_switch_off),
    STEP_UP(R.raw.sb_step_up),
    STEP_DOWN(R.raw.sb_step_down),
    TAB(R.raw.sb_tab),
    OPEN(R.raw.sb_open),
    BACK(R.raw.sb_back),
    CONFIRM(R.raw.sb_confirm),
    DANGER(R.raw.sb_danger),
    DENIED(R.raw.sb_denied),
    LOGO(R.raw.sb_logo),
    THEME_LIGHT(R.raw.sb_theme_light),
    THEME_DARK(R.raw.sb_theme_dark),

    BOOT(R.raw.sb_boot, 0.8f),
    SAVED(R.raw.sb_saved, 0.7f),
    LINK_UP(R.raw.sb_link_up, 0.7f),
    LINK_LOST(R.raw.sb_link_lost, 0.7f),
    CLOUD(R.raw.sb_cloud, 0.7f),
    LOCK(R.raw.sb_lock, 0.7f),
    UNLOCK(R.raw.sb_unlock, 0.7f),
    PICKUP(R.raw.sb_pickup, 0.7f),
    DONE(R.raw.sb_done, 0.75f),
    CALIB(R.raw.sb_calib, 0.7f),
    SIM(R.raw.sb_sim, 0.7f),
    BIN_LOST(R.raw.sb_bin_lost, 0.7f),
    BIN_BACK(R.raw.sb_bin_back, 0.7f),

    ALERT_FULL(R.raw.sb_alert_full, 0.8f),
    ALERT_TAMPER(R.raw.sb_alert_tamper, 0.8f),
    ALERT_SENSOR(R.raw.sb_alert_sensor, 0.8f),
    ALERT_LOCK(R.raw.sb_alert_lock, 0.8f),
    ALERT_PICKUP(R.raw.sb_alert_pickup, 0.8f),
    ALERT_INFO(R.raw.sb_alert_info, 0.8f),
}

object Sounds {
    private var pool: SoundPool? = null
    private val loaded = HashMap<Sfx, Int>()
    private val ready = HashSet<Int>()
    private var bootWanted = false
    @Volatile private var last = 0L

    fun init(context: Context) {
        if (pool != null) return
        val app = context.applicationContext
        val made = SoundPool.Builder()
            .setMaxStreams(6)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        pool = made
        made.setOnLoadCompleteListener { p, sample, status ->
            if (status != 0) return@setOnLoadCompleteListener
            ready.add(sample)
            // The opening chime is asked for before the sounds have loaded; it plays as soon as it can.
            if (bootWanted && sample == loaded[Sfx.BOOT]) {
                bootWanted = false
                if (Feel.sounds) p.play(sample, Sfx.BOOT.volume, Sfx.BOOT.volume, 1, 0, 1f)
            }
        }
        Sfx.entries.forEach { loaded[it] = made.load(app, it.res, 1) }
    }

    fun play(sfx: Sfx) {
        if (!Feel.sounds) return
        val id = loaded[sfx] ?: return
        if (id !in ready) return
        last = SystemClock.uptimeMillis()
        pool?.play(id, sfx.volume, sfx.volume, 1, 0, 1f)
    }

    /** For sounds that follow from another action: skipped when a key has just made its own sound. */
    fun playIfQuiet(sfx: Sfx) {
        if (SystemClock.uptimeMillis() - last > 250) play(sfx)
    }

    /** The opening chime. Waits for the sound to load if it has not yet. */
    fun boot() {
        val id = loaded[Sfx.BOOT]
        if (id != null && id in ready) play(Sfx.BOOT) else bootWanted = true
    }
}
