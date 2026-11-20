package fr.laclaviere.bus24.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import fr.laclaviere.bus24.MainActivity
import fr.laclaviere.bus24.R

/**
 * Widget RemoteViews : badge « 24 », heure + compte à rebours, clignotement rouge
 * (ViewFlipper) si annulé ou arrivée imminente.
 */
class Bus24WidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetManager.updateAppWidget(appWidgetIds, renderFromState(context))
        Bus24Refresher.scheduleNext(context)
    }

    override fun onEnabled(context: Context) {
        Bus24UpdateReceiver.refreshNow(context)
    }

    override fun onDisabled(context: Context) {
        Bus24Refresher.cancel(context)
    }

    companion object {

        val RED: Int get() = Color.rgb(0xD3, 0x2F, 0x2F)
        val BLUE: Int get() = Color.rgb(0x21, 0x65, 0xC0)
        val BLACK: Int get() = Color.rgb(0x11, 0x11, 0x11)
        val GRAY: Int get() = Color.rgb(0x66, 0x66, 0x66)

        fun updateAll(context: Context, views: RemoteViews) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, Bus24WidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                manager.updateAppWidget(ids, views)
            }
        }

        fun renderFromState(context: Context): RemoteViews =
            render(context, Bus24State.load(context))

        fun render(context: Context, state: Bus24State?): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_bus24)
            views.setTextViewText(R.id.widget_line_badge, "24")
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )

            if (state == null) {
                views.setTextViewText(R.id.widget_primary, "")
                views.setTextColor(R.id.widget_primary, GRAY)
                views.setViewVisibility(R.id.widget_primary, View.VISIBLE)
                views.setViewVisibility(R.id.widget_flipper, View.GONE)
                views.setTextViewText(R.id.widget_secondary, "")
                views.setTextViewText(
                    R.id.widget_status,
                    context.getString(R.string.widget_waiting),
                )
                views.setTextColor(R.id.widget_status, GRAY)
                return views
            }

            if (state.blink) {
                views.setTextViewText(R.id.widget_blink_a, state.message)
                views.setTextViewText(R.id.widget_blink_b, state.message)
                views.setTextColor(R.id.widget_blink_a, RED)
                views.setTextColor(R.id.widget_blink_b, RED)
                views.setViewVisibility(R.id.widget_flipper, View.VISIBLE)
                views.setViewVisibility(R.id.widget_primary, View.GONE)
            } else {
                views.setTextViewText(R.id.widget_primary, state.message)
                views.setTextColor(R.id.widget_primary, if (state.realtime) BLUE else BLACK)
                views.setViewVisibility(R.id.widget_flipper, View.GONE)
                views.setViewVisibility(R.id.widget_primary, View.VISIBLE)
            }
            views.setTextViewText(R.id.widget_secondary, state.subMessage)
            views.setTextColor(R.id.widget_secondary, GRAY)
            views.setTextViewText(R.id.widget_status, state.status)
            views.setTextColor(R.id.widget_status, GRAY)
            return views
        }
    }
}
