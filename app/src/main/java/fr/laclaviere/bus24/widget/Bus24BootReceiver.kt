package fr.laclaviere.bus24.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Après un redémarrage du téléphone, les alarmes AlarmManager sont perdues : on
 * re-rend le dernier état connu et on re-programme le rafraîchissement. Les données
 * sont rechargées dès le premier tick d'alarme (< 60 s).
 */
class Bus24BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Bus24WidgetProvider.updateAll(context, Bus24WidgetProvider.renderFromState(context))
        Bus24Refresher.scheduleNext(context)
    }
}
