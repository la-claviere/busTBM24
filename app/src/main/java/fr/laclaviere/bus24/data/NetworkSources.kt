package fr.laclaviere.bus24.data

object NetworkSources {
    const val ROUTE_ID = "24"
    const val ROUTE_NAME = "24"
    const val STOP_ID = "815"
    const val STOP_CODE = "FAU47A"
    const val STOP_NAME = "Fauré"
    const val DIRECTION = "Pessac Romainville"

    const val GTFS_URL = "https://transport.data.gouv.fr/resources/83024/download"
    const val TRIP_UPDATES_URL = "https://transport.data.gouv.fr/resources/83025/download"

    const val MAX_GTFS_AGE_MS = 12L * 60 * 60 * 1000
    const val HTTP_TIMEOUT_MS = 20_000
    const val USER_AGENT = "prochain-bus24-android/1.0"
}
