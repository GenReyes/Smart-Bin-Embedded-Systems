package smartbin.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import smartbin.data.Hub

/** "Start pickup" on a full-bin notification: puts the bin in pickup mode without opening the app. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Alerts.ACTION_COLLECT) return
        val bin = intent.getStringExtra(Alerts.EXTRA_BIN) ?: return
        val note = intent.getIntExtra(Alerts.EXTRA_NOTIFICATION, 0)
        val pending = goAsync()
        Hub.init(context)
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val problem = Hub.command(bin, "COLLECT")
                val name = Hub.bin(bin)?.name ?: "The bin"
                Toast.makeText(context, problem ?: "Pickup started on $name.", Toast.LENGTH_SHORT).show()
                if (problem == null) {
                    NotificationManagerCompat.from(context).cancel(note)
                    Feel.event(Sfx.PICKUP)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
