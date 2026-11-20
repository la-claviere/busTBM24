package fr.laclaviere.bus24.data

import java.time.LocalDate

/**
 * Analyse du GTFS statique (CSV) filtré sur la ligne et l'arrêt configurés.
 * Entrées : contenus texte des fichiers calendar.txt, calendar_dates.txt,
 * trips.txt (pré-filtré route 24), stop_times.txt (pré-filtré stop_id cible).
 */
data class DepartureInfo(
    val tripId: String,
    val serviceId: String,
    /** secondes depuis le début du jour de service (peut dépasser 86400 pour les nocturnes). */
    val offsetSeconds: Long,
    val headsign: String,
)

class GtfsData(
    private val calendar: List<ServiceCalendar>,
    private val calendarDates: List<ServiceCalendarDate>,
    val routeName: String,
    val departures: List<DepartureInfo>,
) {

    data class ServiceCalendar(
        val serviceId: String,
        val weekdays: BooleanArray,
        val startDate: LocalDate,
        val endDate: LocalDate,
    )

    data class ServiceCalendarDate(
        val serviceId: String,
        val date: LocalDate,
        /** 1 = ajouté, 2 = supprimé. */
        val exceptionType: Int,
    )

    fun activeServiceIds(date: LocalDate): Set<String> {
        val active = HashSet<String>()
        for (cal in calendar) {
            if (!date.isBefore(cal.startDate) && !date.isAfter(cal.endDate)) {
                val weekday = cal.weekdays[date.dayOfWeek.value - 1]
                if (weekday) active.add(cal.serviceId)
            }
        }
        for (cd in calendarDates) {
            if (cd.date == date) {
                if (cd.exceptionType == 1) active.add(cd.serviceId)
                else active.remove(cd.serviceId)
            }
        }
        return active
    }

    companion object {
        fun parse(
            files: Map<String, String>,
            routeId: String,
            stopId: String,
        ): GtfsData {
            val calendarRows = parseCsv(files["calendar.txt"] ?: "")
            val calendars = calendarRows.drop(1).mapNotNull { row ->
                val m = row.toMapOrNull(calendarRows.firstOrNull() ?: return@mapNotNull null)
                    ?: return@mapNotNull null
                runCatching {
                    ServiceCalendar(
                        serviceId = m["service_id"] ?: "",
                        weekdays = BooleanArray(7) { i ->
                            m[dayColumn(i)] == "1"
                        },
                        startDate = parseYyyymmdd(m["start_date"] ?: "") ?: return@mapNotNull null,
                        endDate = parseYyyymmdd(m["end_date"] ?: "") ?: return@mapNotNull null,
                    )
                }.getOrNull()
            }

            val calDateRows = parseCsv(files["calendar_dates.txt"] ?: "")
            val calDates = if (calDateRows.size < 2) emptyList() else calDateRows.drop(1).mapNotNull { row ->
                val m = row.toMapOrNull(calDateRows.first()) ?: return@mapNotNull null
                runCatching {
                    ServiceCalendarDate(
                        serviceId = m["service_id"] ?: "",
                        date = parseYyyymmdd(m["date"] ?: "") ?: return@mapNotNull null,
                        exceptionType = (m["exception_type"] ?: "0").toInt(),
                    )
                }.getOrNull()
            }

            val tripRows = parseCsv(files["trips.txt"] ?: "")
            val tripsHeader = tripRows.firstOrNull() ?: emptyList()
            val trips = tripRows.drop(1).mapNotNull { row ->
                val m = row.toMapOrNull(tripsHeader) ?: return@mapNotNull null
                m
            }
            val headsignByTrip = HashMap<String, String>()
            val serviceByTrip = HashMap<String, String>()
            for (t in trips) {
                if (t["route_id"] == routeId) {
                    t["trip_id"]?.let { tripId ->
                        headsignByTrip[tripId] = t["trip_headsign"] ?: ""
                        serviceByTrip[tripId] = t["service_id"] ?: ""
                    }
                }
            }

            val stopTimeRows = parseCsv(files["stop_times.txt"] ?: "")
            val stopTimesHeader = stopTimeRows.firstOrNull() ?: emptyList()
            val departures = stopTimeRows.drop(1).mapNotNull { row ->
                val m = row.toMapOrNull(stopTimesHeader) ?: return@mapNotNull null
                val tripId = m["trip_id"] ?: return@mapNotNull null
                if (m["stop_id"] != stopId || tripId !in headsignByTrip) return@mapNotNull null
                val seconds = parseGtfsTime(m["departure_time"] ?: m["arrival_time"] ?: return@mapNotNull null)
                    ?: return@mapNotNull null
                DepartureInfo(
                    tripId = tripId,
                    serviceId = serviceByTrip[tripId] ?: "",
                    offsetSeconds = seconds,
                    headsign = headsignByTrip[tripId] ?: "",
                )
            }

            val routeRows = parseCsv(files["routes.txt"] ?: "")
            val routesHeader = routeRows.firstOrNull() ?: emptyList()
            val routeName = routeRows.drop(1)
                .mapNotNull { it.toMapOrNull(routesHeader) }
                .firstOrNull { it["route_id"] == routeId }
                ?.get("route_short_name") ?: routeId

            return GtfsData(calendars, calDates, routeName, departures)
        }

        val DAY_COLUMNS = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")

        private fun dayColumn(i: Int): String = DAY_COLUMNS[i]

        fun parseGtfsTime(value: String): Long? {
            val parts = value.trim().split(":")
            if (parts.size < 2) return null
            val h = parts[0].toLongOrNull() ?: return null
            val min = parts[1].toLongOrNull() ?: return null
            val sec = if (parts.size > 2) parts[2].toLongOrNull() ?: 0L else 0L
            if (min !in 0..59 || sec !in 0..59) return null
            return h * 3600 + min * 60 + sec
        }

        fun parseYyyymmdd(value: String): LocalDate? {
            if (value.length != 8) return null
            return runCatching {
                LocalDate.of(
                    value.substring(0, 4).toInt(),
                    value.substring(4, 6).toInt(),
                    value.substring(6, 8).toInt(),
                )
            }.getOrNull()
        }

        fun parseCsv(text: String): List<List<String>> {
            val rows = ArrayList<List<String>>()
            var row = ArrayList<String>()
            var field = StringBuilder()
            var inQuotes = false
            var i = 0
            var started = false
            val clean = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
            while (i < clean.length) {
                val c = clean[i]
                when {
                    inQuotes -> {
                        if (c == '"') {
                            if (i + 1 < clean.length && clean[i + 1] == '"') {
                                field.append('"'); i++
                            } else inQuotes = false
                        } else field.append(c)
                        started = true
                    }
                    c == '"' -> {
                        inQuotes = true
                        started = true
                    }
                    c == ',' -> {
                        row.add(field.toString()); field = StringBuilder()
                    }
                    c == '\n' -> {
                        row.add(field.toString())
                        rows.add(row)
                        row = ArrayList(); field = StringBuilder()
                        started = false
                    }
                    else -> field.append(c)
                }
                i++
            }
            if (started || field.isNotEmpty() || row.isNotEmpty()) {
                row.add(field.toString())
                rows.add(row)
            }
            return rows.filter { it.size > 1 || (it.size == 1 && it[0].isNotBlank()) }
        }

        private fun List<String>.toMapOrNull(header: List<String>): Map<String, String>? {
            if (header.isEmpty()) return null
            val n = minOf(header.size, size)
            val map = LinkedHashMap<String, String>(n)
            for (i in 0 until n) map[header[i]] = this[i]
            return map
        }
    }
}
