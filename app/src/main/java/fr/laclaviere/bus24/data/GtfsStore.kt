package fr.laclaviere.bus24.data

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.zip.ZipFile

/**
 * Télécharge, filtre et met en cache le GTFS statique du TBM.
 * Ne sont conservés en cache que : la ligne 24 (routes/trips), les passages à l'arrêt
 * cible (stop_times, filtrés en flux pour limiter la mémoire), calendar et calendar_dates.
 */
class GtfsStore(private val cacheDir: File) {

    private val routesFile get() = File(cacheDir, "route_${NetworkSources.ROUTE_ID}_routes.csv")
    private val tripsFile get() = File(cacheDir, "route_${NetworkSources.ROUTE_ID}_trips.csv")
    private val stopTimesFile get() = File(cacheDir, "stop_${NetworkSources.STOP_ID}_stop_times.csv")
    private val calendarFile get() = File(cacheDir, "calendar.csv")
    private val calendarDatesFile get() = File(cacheDir, "calendar_dates.csv")
    private val stampFile get() = File(cacheDir, "gtfs_downloaded_at")

    val isCachePresent: Boolean get() = tripsFile.isFile && stopTimesFile.isFile

    fun gtfsAgeMs(): Long? {
        if (!stampFile.isFile) return null
        val stamp = stampFile.readText().trim().toLongOrNull() ?: return null
        return System.currentTimeMillis() - stamp
    }

    fun load(): GtfsData? {
        if (!isCachePresent) return null
        val files = mapOf(
            "routes.txt" to (if (routesFile.isFile) routesFile.bufferedReader().readText() else ""),
            "trips.txt" to tripsFile.bufferedReader().readText(),
            "stop_times.txt" to stopTimesFile.bufferedReader().readText(),
            "calendar.txt" to (if (calendarFile.isFile) calendarFile.bufferedReader().readText() else ""),
            "calendar_dates.txt" to (if (calendarDatesFile.isFile) calendarDatesFile.bufferedReader().readText() else ""),
        )
        return GtfsData.parse(files, NetworkSources.ROUTE_ID, NetworkSources.STOP_ID)
    }

    @Throws(Exception::class)
    fun refresh(force: Boolean = false) {
        val age = gtfsAgeMs()
        if (!force && age != null && age < NetworkSources.MAX_GTFS_AGE_MS) return
        val tmp = File.createTempFile("tbm_gtfs", ".zip", cacheDir)
        try {
            downloadTo(NetworkSources.GTFS_URL, tmp)
            extractFiltered(tmp)
        } finally {
            tmp.delete()
        }
    }

    private fun extractFiltered(zip: File) {
        cacheDir.mkdirs()
        ZipFile(zip).use { zf ->
            val routesBuf = StringBuilder()
            val tripsBuf = StringBuilder(1 shl 20)
            val calendarBuf = StringBuilder()
            val calDatesBuf = StringBuilder()
            val route24Trips = HashSet<String>()
            var stopTimesSeen = false

            for (entry in zf.entries()) {
                when (val name = entry.name.substringAfterLast('/').trimStart('/')) {
                    "routes.txt", "trips.txt", "calendar.txt", "calendar_dates.txt" -> {
                        val lines = zf.getInputStream(entry).bufferedReader().readLines()
                        val rows = GtfsData.parseCsv(lines.joinToString("\n"))
                        if (rows.isEmpty()) continue
                        val header = rows.first()
                        val idxRoute = header.indexOf("route_id")
                        val idxTrip = header.indexOf("trip_id")
                        val out = when (name) {
                            "routes.txt" -> routesBuf
                            "trips.txt" -> tripsBuf
                            "calendar.txt" -> calendarBuf
                            else -> calDatesBuf
                        }
                        out.append(joinCsv(header)).append('\n')
                        for (row in rows.drop(1)) {
                            if (name == "trips.txt") {
                                if (row.getOrNull(idxRoute) != NetworkSources.ROUTE_ID) continue
                                route24Trips.add(row.getOrNull(idxTrip) ?: "")
                            }
                            out.append(joinCsv(row)).append('\n')
                        }
                    }
                    "stop_times.txt" -> {
                        stopTimesSeen = true
                        writeFilteredStopTimes(zf, entry, route24Trips)
                    }
                }
            }

            if (!stopTimesSeen) throw IllegalStateException("stop_times.txt introuvable dans le GTFS")
            routesFile.writeText(routesBuf.toString())
            tripsFile.writeText(tripsBuf.toString())
            calendarFile.writeText(calendarBuf.toString())
            calendarDatesFile.writeText(calDatesBuf.toString())
            stampFile.writeText(Instant.now().toEpochMilli().toString())
        }
    }

    private fun joinCsv(row: List<String>): String =
        row.joinToString(",") { f ->
            if (f.contains(',') || f.contains('"') || f.contains('\n')) {
                "\"" + f.replace("\"", "\"\"") + "\""
            } else f
        }

    private fun writeFilteredStopTimes(zf: ZipFile, entry: java.util.zip.ZipEntry, route24Trips: HashSet<String>) {
        val stopId = NetworkSources.STOP_ID
        stopTimesFile.bufferedWriter().use { out ->
            zf.getInputStream(entry).bufferedReader().use { reader ->
                var header = reader.readLine()
                if (header == null) return
                header = header.removePrefix("﻿").replace("\r", "")
                val headerRow = GtfsData.parseCsv(header).firstOrNull() ?: emptyList()
                val idxStop = headerRow.indexOf("stop_id")
                val idxTrip = headerRow.indexOf("trip_id")
                out.write(header)
                out.write("\n")
                var line = reader.readLine()
                while (line != null) {
                    val clean = line.replace("\r", "")
                    // Pré-filtre rapide : stop_id en 4e colonne, entouré de virgules.
                    if (clean.contains(",$stopId,")) {
                        val row = GtfsData.parseCsv(clean).firstOrNull()
                        if (row != null &&
                            row.getOrNull(idxStop) == stopId &&
                            row.getOrNull(idxTrip) in route24Trips
                        ) {
                            out.write(clean)
                            out.write("\n")
                        }
                    }
                    line = reader.readLine()
                }
            }
        }
    }

    companion object {
        @Throws(Exception::class)
        fun downloadTo(url: String, target: File) {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = NetworkSources.HTTP_TIMEOUT_MS
            connection.readTimeout = NetworkSources.HTTP_TIMEOUT_MS * 3
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", NetworkSources.USER_AGENT)
            try {
                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException("HTTP ${connection.responseCode} pour $url")
                }
                connection.inputStream.use { input ->
                    target.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
                }
            } finally {
                connection.disconnect()
            }
        }
    }
}
