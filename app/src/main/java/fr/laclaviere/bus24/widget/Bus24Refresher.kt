package fr.laclaviere.bus24.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Planification du rafraîchissement du widget : toutes les ~60 s quand un passage est
 * proche (< 45 min), sinon toutes les 5 min. AlarmManager en mode non exact (aucune
 * permission spéciale requise), re-programmé à chaque tick par Bus24UpdateReceiver.
 */
object Bus24Refresher {

    const val ACTION_REFRESH = "fr.laclaviere.bus24.ACTION_WIDGET_REFRESH"
    private const val REQUEST_CODE = 4242
    private const val NORMAL_INTERVAL_MS = 300_000L

    fun scheduleNext(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, Bus24UpdateReceiver::class.java).setAction(ACTION_REFRESH)
        val pending = PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val intervalMs =
            if (Bus24State.serviceActiveSoon(context)) 60_000L else NORMAL_INTERVAL_MS
        val triggerAt = SystemClock.elapsedRealtime() + intervalMs
        alarm.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, intervalMs / 2, pending)
    }

    fun cancel(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, Bus24UpdateReceiver::class.java).setAction(ACTION_REFRESH)
        val pending = PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        pending?.cancel()
    }
}
