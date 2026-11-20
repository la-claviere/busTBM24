package fr.laclaviere.bus24.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import fr.laclaviere.bus24.R
import fr.laclaviere.bus24.data.GtfsRt
import fr.laclaviere.bus24.data.GtfsStore
import fr.laclaviere.bus24.data.NetworkSources
import fr.laclaviere.bus24.data.NextBusService
import fr.laclaviere.bus24.data.TripUpdatesClient
import java.time.ZoneId
import kotlin.concurrent.thread

/**
 * Récepteur des alarmes (~60 s / 5 min) : recalcule les passages, met à jour le widget,
 * puis re-programme la prochaine alarme via Bus24Refresher. goAsync() garde le
 * processus actif pendant la récupération réseau.
 */
class Bus24UpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Bus24Refresher.ACTION_REFRESH) return
        val pendingResult = goAsync()
        thread(name = "bus24-widget") {
            try {
                refreshNow(context)
            } catch (_: Exception) {
                // On conserve le dernier état connu ; la prochaine alarme retentera.
            } finally {
                pendingResult.finish()
            }
            try {
                Bus24Refresher.scheduleNext(context)
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private val ZONE: ZoneId = ZoneId.of("Europe/Paris")

        fun refreshNow(context: Context) {
            val store = GtfsStore(context.cacheDir)
            try {
                store.refresh()
            } catch (_: Exception) {
            }
            val gtfs = store.load()
            if (gtfs == null) {
                saveAndRender(
                    context,
                    Bus24State(
                        primaryEpoch = null,
                        message = context.getString(R.string.widget_no_data),
                        subMessage = "",
                        status = context.getString(R.string.widget_no_data_detail),
                        blink = false,
                        realtime = false,
                        cancelled = false,
                        fetchedAtMs = System.currentTimeMillis(),
                    ),
                )
                return
            }

            val nowSec = System.currentTimeMillis() / 1000
            var rtEmpty = false
            val rtMap = try {
                GtfsRt.parseFeed(TripUpdatesClient.fetch()).associateBy { it.tripId }
            } catch (_: Exception) {
                rtEmpty = true
                emptyMap()
            }

            val passages = NextBusService().nextPassages(
                gtfs, NetworkSources.ROUTE_ID, NetworkSources.STOP_ID, nowSec, ZONE, 4, rtMap,
            )
            val state = if (passages.isEmpty()) {
                val resume = NextBusService.nextServiceDay(
                    gtfs, nowSec, ZONE, NetworkSources.ROUTE_ID, NetworkSources.STOP_ID,
                )
                Bus24State(
                    primaryEpoch = null,
                    message = resume?.let { context.getString(R.string.widget_resume, it) }
                        ?: context.getString(R.string.widget_end_of_service),
                    subMessage = "",
                    status = context.getString(R.string.widget_no_rt),
                    blink = false,
                    realtime = false,
                    cancelled = false,
                    fetchedAtMs = System.currentTimeMillis(),
                )
            } else {
                val primary = passages.first()
                val minutes = primary.minutesAway(nowSec)
                val message = buildString {
                    append(NextBusService.formatTime(primary.epochUtc, ZONE))
                    append(" — ")
                    if (primary.cancelled) append(context.getString(R.string.cancelled))
                    else append(NextBusService.formatMinutes(minutes))
                }
                val sub = buildString {
                    passages.drop(1).forEach { p ->
                        append(NextBusService.formatTime(p.epochUtc, ZONE))
                        if (p.cancelled) append(" (annulé)")
                        append("   ")
                    }
                }
                Bus24State(
                    primaryEpoch = primary.epochUtc,
                    message = message,
                    subMessage = sub.trim(),
                    status = context.getString(
                        if (rtEmpty) R.string.widget_no_rt else R.string.widget_rt_ok,
                    ),
                    blink = primary.isAlert(nowSec),
                    realtime = primary.realtime,
                    cancelled = primary.cancelled,
                    fetchedAtMs = System.currentTimeMillis(),
                )
            }
            saveAndRender(context, state)
        }

        private fun saveAndRender(context: Context, state: Bus24State) {
            Bus24State.save(context, state)
            Bus24WidgetProvider.updateAll(
                context,
                Bus24WidgetProvider.render(context, state),
            )
        }
    }
}
