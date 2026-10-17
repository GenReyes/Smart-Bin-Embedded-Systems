package smartbin

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import smartbin.data.Hub
import smartbin.data.HubEvent
import smartbin.data.Link
import smartbin.notify.Alerts
import smartbin.notify.Feel
import smartbin.notify.Haptics
import smartbin.notify.Sfx
import smartbin.notify.Sounds

/**
 * Starts the reading loop once per process and turns its events into sound,
 * vibration or a notification. On screen an alert plays the app's own sound
 * and pattern; off screen it becomes an Android notification, which carries
 * the same sound through its channel.
 */
class SmartBinApp : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        Hub.init(this)
        Alerts.ensureChannels(this)
        Sounds.init(this)
        Haptics.init(this)
        val store = Hub.store()
        Feel.sounds = store.bool(KEY_SOUNDS, true)
        Feel.haptics = store.bool(KEY_HAPTICS, true)
        Haptics.strength = runCatching { Haptics.Strength.valueOf(store.string(KEY_STRENGTH, "NORMAL")) }.getOrDefault(Haptics.Strength.NORMAL)

        scope.launch {
            Hub.events.collect { e ->
                // Nothing sounds or posts on the start page: only once the user is in the main screens.
                if (!Hub.inMain) return@collect
                val onScreen = Hub.foreground
                when (e) {
                    is HubEvent.NewAlert ->
                        if (onScreen) Feel.event(Alerts.sfxFor(e.type))
                        else if (!e.row.demo) Alerts.post(this@SmartBinApp, e.row, e.type, e.binId)
                    is HubEvent.LinkChanged -> if (onScreen) when (e.to) {
                        Link.LIVE -> Feel.event(Sfx.LINK_UP)
                        Link.CLOUD, Link.THINGSPEAK -> Feel.event(Sfx.CLOUD)
                        Link.OFFLINE -> Feel.event(Sfx.LINK_LOST)
                        Link.DEMO -> {}
                    }
                    is HubEvent.BinLost -> if (onScreen) Feel.event(Sfx.BIN_LOST)
                    is HubEvent.BinBack -> if (onScreen) Feel.event(Sfx.BIN_BACK)
                    is HubEvent.Sound -> if (onScreen) runCatching { Sfx.valueOf(e.name) }.getOrNull()?.let(Feel::event)
                    // The screens tell the user; nothing to post.
                    is HubEvent.CommandResult -> {}
                }
            }
        }
    }

    companion object {
        const val KEY_SOUNDS = "ui_sounds"
        const val KEY_HAPTICS = "ui_haptics"
        const val KEY_STRENGTH = "haptic_strength"
        const val KEY_AWAKE = "keep_awake"
        const val KEY_THEME = "theme"
        const val KEY_ROLE = "role"
        const val KEY_ASKED = "asked_alerts"
    }
}
