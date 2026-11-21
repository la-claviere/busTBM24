package fr.laclaviere.bus24.data

/**
 * Décodage minimal du flux GTFS-RT TripUpdates (wire-format Protobuf, sans bibliothèque).
 *
 * Structure observée sur le flux TBM (validée le 20/11/2026 sur /resources/83025/download) :
 *   FeedMessage { header = 1 (FeedHeader), entity = 2 (repeated FeedEntity) }
 *     FeedHeader  { gtfs_realtime_version = 1, incrementality = 2, timestamp = 3 }
 *     FeedEntity  { id = 1 (string), is_deleted = 2 (bool), trip_update = 3 (TripUpdate) }
 *       TripUpdate {
 *         trip = 1 (TripDescriptor),
 *         stop_time_update = répété — champ 2 dans le flux TBM (champ 3 dans le .proto
 *                            canonique : les deux positions sont gérées),
 *         extension propriétaire TBM = champ 3 {1 = "ineo-bus:<réf. véhicule>",
 *                            2 = "<destination>"} (observée sur ~18 % des entités) :
 *                            ignorée silencieusement par le décodeur tolérant,
 *         delay (int32) et timestamp (uint64) ignorés
 *       }
 *         TripDescriptor { trip_id = 1, start_time = 2, start_date = 3 (yyyyMMdd),
 *                          schedule_relationship = 4 (SCHEDULED=0, ADDED=1, UNSCHEDULED=2,
 *                          CANCELED=3), route_id = 5, direction_id = 6 }
 *         StopTimeUpdate { stop_sequence = 1, arrival = 2 (StopTimeEvent),
 *                          departure = 3 (StopTimeEvent), stop_id = 4,
 *                          schedule_relationship = 5 (SCHEDULED=0, SKIPPED=1, NO_DATA=2) }
 *           StopTimeEvent { delay = 1 (int32, secondes), time = 2 (int64, epoch UTC),
 *                           uncertainty = 3 }
 */
data class TripUpdateInfo(
    val tripId: String,
    val routeId: String?,
    val startDate: String?,
    val tripScheduleRelationship: Int,
) {
    val stopTimes = HashMap<Int, StopTimeUpdateInfo>()

    val cancelled: Boolean get() = tripScheduleRelationship == 3

    fun stopUpdate(stopId: String): StopTimeUpdateInfo? =
        stopTimes.values.firstOrNull { it.stopId == stopId }
}

data class StopTimeUpdateInfo(
    val stopSequence: Int,
    val stopId: String?,
    val arrivalEpochUtc: Long?,
    val departureEpochUtc: Long?,
    val arrivalDelaySec: Int?,
    val departureDelaySec: Int?,
    val scheduleRelationship: Int?,
) {
    val skipped: Boolean get() = scheduleRelationship == 1
}

object GtfsRt {

    fun parseFeed(bytes: ByteArray): List<TripUpdateInfo> {
        val updates = ArrayList<TripUpdateInfo>()
        for (f in parseFields(bytes)) {
            if (f.number == 2 && f.isBytes) {
                runCatching { parseEntity(f.bytes!!) }.getOrNull()?.let { updates.add(it) }
            }
        }
        return updates
    }

    fun feedTimestamp(bytes: ByteArray): Long? {
        for (f in parseFields(bytes)) {
            if (f.number == 1 && f.isBytes) {
                for (hf in parseFields(f.bytes!!)) {
                    if (hf.number == 3 && !hf.isBytes) return hf.varint
                }
            }
        }
        return null
    }

    private fun parseEntity(entity: ByteArray): TripUpdateInfo? {
        var tripUpdate: ByteArray? = null
        var deleted = false
        for (f in parseFields(entity)) {
            when {
                f.number == 2 && !f.isBytes -> if (f.varint == 1L) deleted = true
                f.number == 3 && f.isBytes -> tripUpdate = f.bytes
            }
        }
        if (deleted) return null
        return tripUpdate?.let { parseTripUpdate(it) }
    }

    private fun parseTripUpdate(buf: ByteArray): TripUpdateInfo? {
        var tripId = ""
        var routeId: String? = null
        var startDate: String? = null
        var tripScheduleRelationship = 0
        val stopTimes = HashMap<Int, StopTimeUpdateInfo>()

        for (f in parseFields(buf)) {
            when {
                f.number == 1 && f.isBytes -> {
                    for (tf in parseFields(f.bytes!!)) {
                        when {
                            tf.number == 1 && tf.isBytes -> tripId = pbString(tf.bytes!!)
                            tf.number == 3 && tf.isBytes -> startDate = pbString(tf.bytes!!)
                            tf.number == 4 && !tf.isBytes -> tripScheduleRelationship = tf.varint.toInt()
                            tf.number == 5 && tf.isBytes -> routeId = pbString(tf.bytes!!)
                        }
                    }
                }
                (f.number == 2 || f.number == 3) && f.isBytes ->
                    runCatching { parseStopTimeUpdate(f.bytes!!) }.getOrNull()
                        ?.let { stopTimes[it.stopSequence] = it }
            }
        }

        if (tripId.isEmpty() && stopTimes.isEmpty()) return null
        val info = TripUpdateInfo(tripId, routeId, startDate, tripScheduleRelationship)
        info.stopTimes.putAll(stopTimes)
        return info
    }

    private fun parseStopTimeUpdate(buf: ByteArray): StopTimeUpdateInfo? {
        var stopSequence = -1
        var stopId: String? = null
        var arrivalEpoch: Long? = null
        var departureEpoch: Long? = null
        var arrivalDelay: Int? = null
        var departureDelay: Int? = null
        var scheduleRelationship: Int? = null

        for (f in parseFields(buf)) {
            when {
                f.number == 1 && !f.isBytes -> stopSequence = f.varint.toInt()
                f.number == 4 && f.isBytes -> stopId = pbString(f.bytes!!)
                f.number == 5 && !f.isBytes -> scheduleRelationship = f.varint.toInt()
                (f.number == 2 || f.number == 3) && f.isBytes -> {
                    var delay: Int? = null
                    var time: Long? = null
                    for (ef in parseFields(f.bytes!!)) {
                        when {
                            ef.number == 1 && !ef.isBytes -> delay = ef.varint.toInt()
                            ef.number == 2 && !ef.isBytes -> time = ef.varint
                        }
                    }
                    if (f.number == 2) {
                        arrivalEpoch = time
                        arrivalDelay = delay
                    } else {
                        departureEpoch = time
                        departureDelay = delay
                    }
                }
            }
        }
        if (stopSequence < 0 && stopId == null) return null
        return StopTimeUpdateInfo(
            stopSequence, stopId, arrivalEpoch, departureEpoch,
            arrivalDelay, departureDelay, scheduleRelationship,
        )
    }
}
