package fr.laclaviere.bus24.data

/**
 * Lecteur minimaliste du wire-format Protobuf (aucune bibliothèque externe).
 * Suffisant pour GTFS-RT : varint (wire type 0), length-delimited (2), en ignorant
 * proprement fixed32/fixed64.
 */
data class PbField(val number: Int, val wireType: Int, val varint: Long, val bytes: ByteArray?) {
    val isBytes: Boolean get() = bytes != null
}

fun pbString(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)

/** Découpe un message en liste plate de champs (les sous-messages restent en `bytes`). */
fun parseFields(buf: ByteArray): List<PbField> {
    val out = ArrayList<PbField>()
    var pos = 0
    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            if (pos >= buf.size) throw IllegalStateException("truncated varint")
            val b = buf[pos++]
            result = result or ((b.toLong() and 0x7F) shl shift)
            if (b >= 0) return result
            shift += 7
        }
        throw IllegalStateException("varint too long")
    }
    while (pos < buf.size) {
        val tag = varint()
        val fieldNumber = (tag shr 3).toInt()
        val wireType = (tag and 0x7).toInt()
        when (wireType) {
            0 -> out.add(PbField(fieldNumber, 0, varint(), null))
            2 -> {
                val len = varint().toInt()
                if (len < 0 || pos + len > buf.size) throw IllegalStateException("bad length $len")
                out.add(PbField(fieldNumber, 2, 0L, buf.copyOfRange(pos, pos + len)))
                pos += len
            }
            1 -> pos += 8
            5 -> pos += 4
            else -> throw IllegalStateException("unsupported wire type $wireType (field $fieldNumber)")
        }
    }
    return out
}
