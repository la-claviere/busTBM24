package fr.laclaviere.bus24.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Teste le décodeur GTFS-RT sur un flux Protobuf fabriqué à la main (wire-format canonique),
 * avec la particularité TBM : stop_time_update sur le champ 2 de TripUpdate.
 */
class GtfsRtTest {

    private fun varint(v: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var x = v
        while (true) {
            val b = (x and 0x7F).toInt()
            x = x ushr 7
            if (x == 0L) { out.write(b); break }
            out.write(b or 0x80)
        }
        return out.toByteArray()
    }

    private fun tag(field: Int, wireType: Int): ByteArray = varint(((field shl 3) or wireType).toLong())

    private fun lenField(field: Int, payload: ByteArray): ByteArray =
        tag(field, 2) + varint(payload.size.toLong()) + payload

    private fun varField(field: Int, value: Long): ByteArray =
        tag(field, 0) + varint(value)

    private fun strField(field: Int, value: String): ByteArray =
        lenField(field, value.toByteArray())

    private fun stopTimeEvent(delay: Int?, time: Long?): ByteArray {
        val buf = ByteArrayOutputStream()
        delay?.let { buf.write(varField(1, it.toLong())) }
        time?.let { buf.write(varField(2, it)) }
        return buf.toByteArray()
    }

    private fun stopTimeUpdate(
        sequence: Int,
        stopId: String,
        depTime: Long? = null,
        depDelay: Int? = null,
        skrel: Int? = null,
    ): ByteArray {
        val buf = ByteArrayOutputStream()
        buf.write(varField(1, sequence.toLong()))
        if (depTime != null || depDelay != null) {
            buf.write(lenField(3, stopTimeEvent(depDelay, depTime)))
        }
        buf.write(strField(4, stopId))
        skrel?.let { buf.write(varField(5, it.toLong())) }
        return buf.toByteArray()
    }

    private fun tripUpdate(tripId: String, routeId: String, srel: Int, st: ByteArray?): ByteArray {
        val tripDescriptor = strField(1, tripId) + strField(5, routeId) + varField(4, srel.toLong())
        val buf = ByteArrayOutputStream()
        buf.write(lenField(1, tripDescriptor))
        st?.let { buf.write(lenField(2, it)) }
        return buf.toByteArray()
    }

    @Test
    fun `décodage du flux TBM avec stop_time_update en champ 2`() {
        val header = strField(1, "2.0") + varField(2, 0) + varField(3, 1_795_140_100L)
        val entity = strField(1, "RT|b_268437957_20|b_268437957") +
            lenField(3, tripUpdate("b_268437957_20", "24", 0,
                stopTimeUpdate(43, "815", depTime = 1_795_166_000L, depDelay = 120)))
        val feed = lenField(1, header) + lenField(2, entity)

        val updates = GtfsRt.parseFeed(feed)
        assertEquals(1, updates.size)
        val info = updates[0]
        assertEquals("b_268437957_20", info.tripId)
        assertEquals("24", info.routeId)
        assertFalse(info.cancelled)
        assertEquals(1_795_140_100L, GtfsRt.feedTimestamp(feed))
        val stop = info.stopUpdate("815")
        assertNotNull(stop)
        assertEquals(1_795_166_000L, stop!!.departureEpochUtc)
        assertEquals(120, stop.departureDelaySec)
    }

    @Test
    fun `trajet annulé détecté, entité supprimée ignorée, champ 3 accepté aussi`() {
        val entityCancel = strField(1, "e1") + lenField(3, tripUpdate("tripX", "24", 3, null))
        val entityCanonicalDupe = strField(1, "eDel") + lenField(3, tripUpdate("tripZ", "24", 0,
            stopTimeUpdate(10, "815", depTime = 1_795_170_000L))) + varField(2, 1)
        val entityOk = strField(1, "eOk") + lenField(3, tripUpdate("tripZ2", "24", 0,
            stopTimeUpdate(10, "815", depTime = 1_795_170_000L)))
        // même entité dupliquée pour tester le placement champ 3 vs champ 2 non nécessaire :
        val feed = lenField(1, varField(3, 1_795_140_100L)) +
            lenField(2, entityCancel) + lenField(2, entityCanonicalDupe) + lenField(2, entityOk)

        val updates = GtfsRt.parseFeed(feed)
        assertEquals(2, updates.size) // l'entité supprimée (is_deleted=1) est ignorée
        assertTrue(updates.first { it.tripId == "tripX" }.cancelled)
        assertEquals(1_795_170_000L, updates.first { it.tripId == "tripZ2" }.stopUpdate("815")!!.departureEpochUtc)
    }

    @Test
    fun `extension propriétaire TBM en champ 3 de TripUpdate ignorée, StopTimeUpdate champ 2 préservés`() {
        val extensionIneo = strField(1, "ineo-bus:1074") + strField(2, "GRADIGNAN CENTRE")
        val tu = tripUpdate("b_268436724_20", "24", 0,
            stopTimeUpdate(12, "815", depTime = 1_795_166_000L)) + lenField(3, extensionIneo)
        val feed = lenField(2, strField(1, "RT|b_268436724_20|b_268436724") + lenField(3, tu))

        val updates = GtfsRt.parseFeed(feed)
        assertEquals(1, updates.size)
        val stop = updates[0].stopUpdate("815")
        assertNotNull(stop)
        assertEquals(1_795_166_000L, stop!!.departureEpochUtc)
    }

    @Test
    fun `entité illisible ignorée sans faire échouer le flux`() {
        val goodEntity = lenField(2, strField(1, "eG") + lenField(3,
            tripUpdate("tripG", "24", 0, stopTimeUpdate(1, "815", depTime = 1_795_166_000L))))
        val garbageEntity = lenField(2, byteArrayOf(0x63, 0x47, 0x52, 0x41, 0x44, 0x12, 0x7f, 0x00))
        val feed = goodEntity + garbageEntity

        val updates = GtfsRt.parseFeed(feed)
        assertEquals(1, updates.size)
        assertEquals("tripG", updates[0].tripId)
    }

    @Test
    fun `arrêt sauté détecté`() {
        val feed = lenField(2, strField(1, "e2") + lenField(3,
            tripUpdate("tripY", "24", 0, stopTimeUpdate(7, "815", skrel = 1))))
        val updates = GtfsRt.parseFeed(feed)
        val stop = updates[0].stopUpdate("815")
        assertNotNull(stop)
        assertTrue(stop!!.skipped)
    }
}
