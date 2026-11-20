package fr.laclaviere.bus24

import fr.laclaviere.bus24.data.GtfsData
import fr.laclaviere.bus24.data.GtfsRt
import fr.laclaviere.bus24.data.NextBusService
import fr.laclaviere.bus24.data.StopTimeUpdateInfo
import fr.laclaviere.bus24.data.TripUpdateInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class NextBusServiceTest {

    private val zone: ZoneId = ZoneId.of("Europe/Paris")
    private val routeId = "24"
    private val stopId = "815"
    private val day: LocalDate = LocalDate.of(2026, 11, 20)

    private fun gtfs(vararg departures: Pair<String, Long>): GtfsData {
        val d = day.toString().replace("-", "")
        val calendar = listOf(
            "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date",
            "SEM,1,1,1,1,1,0,0,$d,$d",
        ).joinToString("\n")
        val trips = (listOf("route_id,trip_id,service_id,trip_headsign") +
            departures.map { (tripId, _) -> "24,$tripId,SEM,PESSAC Romainville" })
            .joinToString("\n")
        val stopTimes = (listOf("trip_id,arrival_time,departure_time,stop_id,stop_sequence") +
            departures.map { (tripId, offset) ->
                val t = "%02d:%02d:%02d".format(offset / 3600, offset % 3600 / 60, offset % 60)
                "$tripId,$t,$t,$stopId,1"
            }).joinToString("\n")
        val routes = listOf(
            "route_id,agency_id,route_short_name,route_long_name",
            "24,TBM,24,Principale 24",
        ).joinToString("\n")
        return GtfsData.parse(
            mapOf(
                "routes.txt" to routes,
                "trips.txt" to trips,
                "stop_times.txt" to stopTimes,
                "calendar.txt" to calendar,
                "calendar_dates.txt" to "",
            ),
            routeId,
            stopId,
        )
    }

    private fun epochOf(time: String): Long =
        day.atStartOfDay(zone).toInstant().toEpochMilli() / 1000 +
            LocalTime.parse(time).toSecondOfDay().toLong()

    @Test
    fun `garde le passage planifié en noir si pas de temps réel`() {
        val data = gtfs("t1" to 52_320L)
        val planned = epochOf("14:32:00")
        val passages = NextBusService().nextPassages(
            data, routeId, stopId, nowUtc = planned - 600, zone = zone, maxResults = 3,
        )
        assertEquals(1, passages.size)
        val p = passages.first()
        assertFalse(p.realtime)
        assertFalse(p.cancelled)
        assertEquals(planned, p.epochUtc)
        assertEquals(10, p.minutesAway(planned - 600))
    }

    @Test
    fun `applique le temps réel (bleu) si disponible`() {
        val data = gtfs("t1" to 52_320L)
        val planned = epochOf("14:32:00")
        val rt = mapOf("t1" to tripWithStop("t1", departureEpoch = planned + 180))
        val passages = NextBusService().nextPassages(
            data, routeId, stopId, nowUtc = planned - 600, zone = zone, rtMap = rt,
        )
        val p = passages.first()
        assertTrue(p.realtime)
        assertFalse(p.cancelled)
        assertEquals(planned + 180, p.epochUtc)
    }

    @Test
    fun `annulation passe en alerte rouge même loin de l'arrivée`() {
        val data = gtfs("t1" to 52_320L)
        val now = epochOf("13:32:00")
        val rt = mapOf("t1" to TripUpdateInfo("t1", "24", "20261120", 3))
        val passages = NextBusService().nextPassages(
            data, routeId, stopId, nowUtc = now, zone = zone, rtMap = rt,
        )
        val p = passages.first()
        assertTrue(p.cancelled)
        assertFalse(p.imminent(now))
        assertTrue(p.isAlert(now))
    }

    @Test
    fun `alerte rouge à 5 minutes ou moins seulement`() {
        val data = gtfs("t1" to 52_320L)
        val planned = epochOf("14:32:00")
        val rt = mapOf("t1" to tripWithStop("t1", departureEpoch = planned))
        assertEquals(6, NextBusService().nextPassages(
            data, routeId, stopId, nowUtc = planned - 360, zone = zone, rtMap = rt,
        ).first().minutesAway(planned - 360))
        assertTrue(NextBusService().nextPassages(
            data, routeId, stopId, nowUtc = planned - 300, zone = zone, rtMap = rt,
        ).first().imminent(planned - 300))
        assertFalse(NextBusService().nextPassages(
            data, routeId, stopId, nowUtc = planned - 301, zone = zone, rtMap = rt,
        ).first().imminent(planned - 301))
    }

    @Test
    fun `le bus déjà parti à l'heure estimée n'est pas affiché`() {
        val data = gtfs("t1" to 51_600L, "t2" to 52_920L)
        val planned1 = epochOf("14:20:00")
        val rt = mapOf("t1" to tripWithStop("t1", departureEpoch = planned1 + 360))
        val now = planned1 + 660
        val passages = NextBusService().nextPassages(
            data, routeId, stopId, nowUtc = now, zone = zone, rtMap = rt,
        )
        assertTrue(passages.none { it.plannedEpochUtc == planned1 })
        assertEquals(1, passages.size)
        assertEquals(epochOf("14:42:00"), passages[0].epochUtc)
    }

    @Test
    fun `un bus retardé au-delà de son heure planifiée reste affiché`() {
        val data = gtfs("t1" to 50_700L, "t2" to 52_920L)
        val planned1 = epochOf("14:05:00")
        val rt = mapOf("t1" to tripWithStop("t1", departureEpoch = planned1 + 2_400))
        val now = planned1 + 900
        val passages = NextBusService().nextPassages(
            data, routeId, stopId, nowUtc = now, zone = zone, rtMap = rt,
        )
        assertEquals(2, passages.size)
        val delayed = passages.first { it.plannedEpochUtc == planned1 }
        assertEquals(planned1 + 2_400, delayed.epochUtc)
        assertTrue(delayed.realtime)
    }

    @Test
    fun `le passage annulé est suivi du passage suivant`() {
        val data = gtfs("t1" to 52_320L, "t2" to 52_920L)
        val planned1 = epochOf("14:32:00")
        val rt = mapOf("t1" to TripUpdateInfo("t1", "24", "20261120", 3))
        val passages = NextBusService().nextPassages(
            data, routeId, stopId, nowUtc = planned1 - 1800, zone = zone, maxResults = 3, rtMap = rt,
        )
        assertEquals(2, passages.size)
        assertTrue(passages[0].cancelled)
        assertFalse(passages[1].cancelled)
        assertEquals(planned1 + 600, passages[1].epochUtc)
    }

    @Test
    fun `arrêt sauté au temps réel équivaut à une annulation`() {
        val data = gtfs("t1" to 52_320L)
        val now = epochOf("13:50:00")
        val info = TripUpdateInfo("t1", "24", "20261120", 0)
        info.stopTimes[1] = StopTimeUpdateInfo(
            1, stopId, null, null, null, null, scheduleRelationship = 1,
        )
        val passages = NextBusService().nextPassages(
            data, routeId, stopId, nowUtc = now, zone = zone, rtMap = mapOf("t1" to info),
        )
        assertTrue(passages.first().cancelled)
    }

    private fun tripWithStop(tripId: String, departureEpoch: Long): TripUpdateInfo {
        val info = TripUpdateInfo(tripId, "24", "20261120", 0)
        info.stopTimes[1] = StopTimeUpdateInfo(
            1, stopId, arrivalEpochUtc = null, departureEpochUtc = departureEpoch,
            arrivalDelaySec = null, departureDelaySec = null, scheduleRelationship = 0,
        )
        return info
    }
}
