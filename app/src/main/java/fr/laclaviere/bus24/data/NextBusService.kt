package fr.laclaviere.bus24.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class Passage(
    /** Heure estimée du passage à l'arrêt (temps réel si dispo, sinon planifié). */
    val epochUtc: Long,
    /** Heure planifiée d'origine (pour affichage du symbole). */
    val plannedEpochUtc: Long,
    val headsign: String,
    val realtime: Boolean,
    val cancelled: Boolean,
) {
    fun minutesAway(nowUtc: Long): Long {
        val diff = epochUtc - nowUtc
        if (diff <= 0) return 0
        return (diff + 59) / 60
    }

    fun imminent(nowUtc: Long): Boolean {
        val m = minutesAway(nowUtc)
        return !cancelled && m in 0..5
    }

    /** Rouge clignotant : annulé ou arrivée à ≤ 5 min (spécification validée). */
    fun isAlert(nowUtc: Long): Boolean = cancelled || imminent(nowUtc)
}

class NextBusService {

    /**
     * @param nowUtc epoch UTC courant
     * @param zone fuseau de l'arrêt (Europe/Paris)
     * @param maxResults nombre de passages suivants souhaités
     * @param rtMap tripId → TripUpdateInfo, ou null / vide si flux indisponible
     */
    fun nextPassages(
        data: GtfsData,
        routeId: String,
        stopId: String,
        nowUtc: Long,
        zone: ZoneId,
        maxResults: Int = 6,
        rtMap: Map<String, TripUpdateInfo> = emptyMap(),
    ): List<Passage> {
        val today = Instant.ofEpochSecond(nowUtc).atZone(zone).toLocalDate()
        val candidates = ArrayList<Passage>()
        for (serviceDayOffset in -1L..0L) {
            val serviceDate = today.plusDays(serviceDayOffset)
            val active = data.activeServiceIds(serviceDate)
            if (active.isEmpty()) continue
            val dayStart = serviceDate.atStartOfDay(zone).toInstant().toEpochMilli() / 1000
            for (dep in data.departures) {
                if (dep.serviceId !in active) continue
                val plannedEpoch = dayStart + dep.offsetSeconds
                if (plannedEpoch < nowUtc - GRACE_PAST_SEC) continue
                candidates.add(combine(dep, plannedEpoch, rtMap))
            }
        }
        candidates.sortBy { it.epochUtc }
        return candidates.take(maxResults)
    }

    private fun combine(
        dep: DepartureInfo,
        plannedEpoch: Long,
        rtMap: Map<String, TripUpdateInfo>,
    ): Passage {
        val info = rtMap[dep.tripId]
        if (info == null) {
            return Passage(plannedEpoch, plannedEpoch, dep.headsign, realtime = false, cancelled = false)
        }
        if (info.cancelled) {
            return Passage(plannedEpoch, plannedEpoch, dep.headsign, realtime = true, cancelled = true)
        }
        val stopUpdate = info.stopUpdate(stopIdFor(info, dep))
        if (stopUpdate != null && stopUpdate.skipped) {
            return Passage(plannedEpoch, plannedEpoch, dep.headsign, realtime = true, cancelled = true)
        }
        val estimated = estimatedEpoch(stopUpdate, plannedEpoch)
            ?: return Passage(plannedEpoch, plannedEpoch, dep.headsign, realtime = false, cancelled = false)
        return Passage(estimated, plannedEpoch, dep.headsign, realtime = true, cancelled = false)
    }

    private fun stopIdFor(info: TripUpdateInfo, dep: DepartureInfo): String =
        NetworkSources.STOP_ID

    /**
     * Heure estimée à l'arrêt : epoch absolu du StopTimeEvent si fourni,
     * sinon horaire planifié + délai (trip-level, puis stop-level).
     */
    private fun estimatedEpoch(update: StopTimeUpdateInfo?, plannedEpoch: Long): Long? {
        if (update == null) return null
        update.departureEpochUtc?.let { return it }
        update.arrivalEpochUtc?.let { return it }
        update.departureDelaySec?.let { return plannedEpoch + it }
        update.arrivalDelaySec?.let { return plannedEpoch + it }
        return null
    }

    companion object {
        /** Tolérance : un passage très récemment passé reste affichable quelques secondes. */
        const val GRACE_PAST_SEC = 30L

        private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

        fun formatTime(epochUtc: Long, zone: ZoneId): String =
            Instant.ofEpochSecond(epochUtc).atZone(zone).toLocalTime().format(HHMM)

        fun formatMinutes(minutes: Long): String = when {
            minutes <= 0L -> "à l'arrêt"
            minutes == 1L -> "dans 1 min"
            else -> "dans $minutes min"
        }

        fun nextServiceDay(data: GtfsData, nowUtc: Long, zone: ZoneId, routeId: String, stopId: String): String? {
            val today = Instant.ofEpochSecond(nowUtc).atZone(zone).toLocalDate()
            for (offset in 0..7) {
                val date = today.plusDays(offset.toLong())
                val active = data.activeServiceIds(date)
                if (active.isEmpty()) continue
                val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli() / 1000
                val best = data.departures
                    .filter { it.serviceId in active }
                    .map { dayStart + it.offsetSeconds }
                    .filter { it >= nowUtc }
                    .minOrNull() ?: continue
                if (offset == 0) continue
                return formatTime(best, zone)
            }
            return null
        }
    }
}
