package smartbin.notify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import smartbin.data.Hub

/**
 * Keeps the app's process alive while it is in the background, so [Hub] goes
 * on reading the bins every few seconds and alerts still arrive. It runs only
 * while push alerts are on, the app is off screen, and the user had reached
 * the main screens. Swiping the app away ends it, and with it the alerts.
 */
class WatchService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android restarting a killed app's service is not the user opening it.
        if (!Hub.inMain) {
            stopSelf()
            return START_NOT_STICKY
        }
        Hub.init(this)
        Alerts.ensureChannels(this)
        val note = Alerts.watching(this)
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(ID, note)
        } catch (_: Exception) {
            // Android refused a background start; alerts then arrive only while the app is open.
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Swiped away from recent apps: the app counts as closed, so no more alerts. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Hub.inMain = false
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        private const val ID = 7001

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, WatchService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, WatchService::class.java)) }
        }
    }
}
