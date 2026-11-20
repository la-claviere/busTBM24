package fr.laclaviere.bus24.data

import java.net.HttpURLConnection
import java.net.URL

/** Récupération du flux GTFS-RT TripUpdates. */
object TripUpdatesClient {

    fun fetch(): ByteArray {
        val connection = URL(NetworkSources.TRIP_UPDATES_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = NetworkSources.HTTP_TIMEOUT_MS
        connection.readTimeout = NetworkSources.HTTP_TIMEOUT_MS * 2
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", NetworkSources.USER_AGENT)
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode} pour TripUpdates")
            }
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}
