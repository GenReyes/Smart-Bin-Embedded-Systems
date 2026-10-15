package smartbin.notify

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import smartbin.MainActivity
import smartbin.R
import smartbin.data.AlertRow
import smartbin.data.AlertType

/**
 * Android notifications for alerts, each kind with its own sound and its own
 * vibration. On Android a notification's sound belongs to its channel, so
 * every kind has a channel. That is also what lets the right sound play while
 * the app is closed, and lets each kind be changed or silenced in Android's
 * settings.
 */
object Alerts {
    const val CHANNEL_WATCH = "sb_watch"
    const val ACTION_COLLECT = "smartbin.START_PICKUP"
    const val EXTRA_BIN = "bin"
    const val EXTRA_NOTIFICATION = "notification"

    private class Kind(val channel: String, val name: String, val about: String, val sfx: Sfx, val importance: Int, val buzz: Haptics.Buzz)

    private val kinds = mapOf(
        AlertType.FULL to Kind("sb_full_v1", "Bin full", "A bin passed its fill limit. Has a Start pickup button.", Sfx.ALERT_FULL, NotificationManager.IMPORTANCE_HIGH, Haptics.Buzz.FULL),
        AlertType.TAMPER to Kind("sb_tamper_v1", "Tamper", "A bin was moved or tilted.", Sfx.ALERT_TAMPER, NotificationManager.IMPORTANCE_HIGH, Haptics.Buzz.TAMPER),
        AlertType.SENSOR to Kind("sb_sensor_v1", "Sensor over its limit", "Temperature, humidity or air quality passed its limit.", Sfx.ALERT_SENSOR, NotificationManager.IMPORTANCE_HIGH, Haptics.Buzz.SENSOR),
        AlertType.LOCK to Kind("sb_lock_v1", "Lock and unlock", "A bin was locked or unlocked.", Sfx.ALERT_LOCK, NotificationManager.IMPORTANCE_DEFAULT, Haptics.Buzz.LOCK),
        AlertType.PICKUP to Kind("sb_pickup_v1", "Pickups", "A bin was emptied.", Sfx.ALERT_PICKUP, NotificationManager.IMPORTANCE_DEFAULT, Haptics.Buzz.DONE),
        AlertType.INFO to Kind("sb_info_v1", "Calibration and settings", "A bin was calibrated or its settings were saved.", Sfx.ALERT_INFO, NotificationManager.IMPORTANCE_LOW, Haptics.Buzz.INFO),
    )

    /** The sound the app plays for a kind of alert while it is on screen. */
    fun sfxFor(type: AlertType): Sfx = kinds.getValue(type).sfx

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val audio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        kinds.values.forEach { k ->
            manager.createNotificationChannel(
                NotificationChannel(k.channel, k.name, k.importance).apply {
                    description = k.about
                    setSound(Uri.parse("android.resource://${context.packageName}/${k.sfx.res}"), audio)
                    enableVibration(true)
                    vibrationPattern = k.buzz.channelPattern()
                }
            )
        }
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_WATCH, "Watching the bins", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps reading the bins so alerts arrive while the app is closed."
                setShowBadge(false)
            }
        )
    }

    fun allowed(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled()

    @SuppressLint("MissingPermission")
    fun post(context: Context, row: AlertRow, type: AlertType, binId: String) {
        if (!allowed(context)) return
        val k = kinds.getValue(type)
        val id = (row.id % Int.MAX_VALUE).toInt()
        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_OPEN, "overview"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, k.channel)
            .setSmallIcon(R.drawable.ic_stat_bin)
            .setContentTitle(row.title)
            .setContentText(row.meta)
            .setStyle(NotificationCompat.BigTextStyle().bigText(row.meta))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setWhen(row.at)
            .setShowWhen(true)
            .setPriority(if (k.importance >= NotificationManager.IMPORTANCE_HIGH) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_ALARM.takeIf { type == AlertType.TAMPER } ?: NotificationCompat.CATEGORY_STATUS)
            // Phones older than Android 8 have no channels, so the sound goes on the notification itself.
            .setSound(Uri.parse("android.resource://${context.packageName}/${k.sfx.res}"))
            .setVibrate(k.buzz.channelPattern())
        if (type == AlertType.FULL) {
            val pickup = PendingIntent.getBroadcast(
                context, id,
                Intent(context, ActionReceiver::class.java).setAction(ACTION_COLLECT).putExtra(EXTRA_BIN, binId).putExtra(EXTRA_NOTIFICATION, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            n.addAction(0, "Start pickup", pickup)
        }
        n.addAction(0, "Open", open)
        runCatching { NotificationManagerCompat.from(context).notify(id, n.build()) }
    }

    /** The quiet notification a foreground service must show. */
    fun watching(context: Context) = NotificationCompat.Builder(context, CHANNEL_WATCH)
        .setSmallIcon(R.drawable.ic_stat_bin)
        .setContentTitle("Watching your bins")
        .setContentText("Alerts arrive while the app is closed.")
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setContentIntent(
            PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .build()
}
