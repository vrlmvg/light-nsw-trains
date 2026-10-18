package io.github.vrlmvg.nswtrains

/** One train's reported position from a GTFS-realtime VehiclePositions feed. */
data class VehiclePosition(
    val tripId: String,
    val routeId: String,
    val lat: Double,
    val lon: Double,
    /** e.g. "20:24 Central Station to Lithgow Station" */
    val label: String,
    /** Seconds since epoch of the position fix; 0 when missing. */
    val timestamp: Long,
)

/**
 * Minimal protobuf reader for the parts of GTFS-realtime we need (no protobuf library is allowed in
 * tools). Unknown fields, including TfNSW extensions, are skipped.
 *
 * FeedMessage.entity = 2 → FeedEntity.vehicle = 4 → VehiclePosition:
 *   trip = 1 (TripDescriptor: trip_id = 1, route_id = 5), position = 2 (latitude = 1, longitude = 2,
 *   both float32), timestamp = 5, vehicle = 8 (VehicleDescriptor: label = 2).
 */
object GtfsRealtime {

    fun parseVehiclePositions(bytes: ByteArray): List<VehiclePosition> {
        val result = mutableListOf<VehiclePosition>()
        Reader(bytes, 0, bytes.size).forEachField { field, wire, r ->
            if (field == 2 && wire == LEN) {
                r.message().forEachField { f, w, entity ->
                    if (f == 4 && w == LEN) parseVehicle(entity.message())?.let(result::add) else entity.skip(w)
                }
            } else {
                r.skip(wire)
            }
        }
        return result
    }

    private fun parseVehicle(r: Reader): VehiclePosition? {
        var tripId = ""; var routeId = ""; var label = ""
        var lat = Double.NaN; var lon = Double.NaN; var timestamp = 0L
        r.forEachField { field, wire, v ->
            when {
                field == 1 && wire == LEN -> v.message().forEachField { f, w, t ->
                    when {
                        f == 1 && w == LEN -> tripId = t.string()
                        f == 5 && w == LEN -> routeId = t.string()
                        else -> t.skip(w)
                    }
                }
                field == 2 && wire == LEN -> v.message().forEachField { f, w, p ->
                    when {
                        f == 1 && w == I32 -> lat = p.float().toDouble()
                        f == 2 && w == I32 -> lon = p.float().toDouble()
                        else -> p.skip(w)
                    }
                }
                field == 5 && wire == VARINT -> timestamp = v.varint()
                field == 8 && wire == LEN -> v.message().forEachField { f, w, d ->
                    if (f == 2 && w == LEN) label = d.string().trim() else d.skip(w)
                }
                else -> v.skip(wire)
            }
        }
        if (lat.isNaN() || lon.isNaN()) return null
        return VehiclePosition(tripId, routeId, lat, lon, label, timestamp)
    }

    private const val VARINT = 0
    private const val I64 = 1
    private const val LEN = 2
    private const val I32 = 5

    private class Reader(private val buf: ByteArray, private var pos: Int, private val end: Int) {

        inline fun forEachField(block: (field: Int, wire: Int, reader: Reader) -> Unit) {
            while (pos < end) {
                val key = varint()
                block((key ushr 3).toInt(), (key and 7).toInt(), this)
            }
        }

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (pos >= end) throw IllegalArgumentException("Truncated varint")
                val b = buf[pos++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw IllegalArgumentException("Varint too long")
            }
        }

        fun message(): Reader {
            val length = varint().toInt()
            require(length >= 0 && pos + length <= end) { "Bad length" }
            return Reader(buf, pos, pos + length).also { pos += length }
        }

        fun string(): String {
            val length = varint().toInt()
            require(length >= 0 && pos + length <= end) { "Bad length" }
            return String(buf, pos, length, Charsets.UTF_8).also { pos += length }
        }

        fun float(): Float {
            require(pos + 4 <= end) { "Truncated float" }
            val bits = (buf[pos].toInt() and 0xFF) or
                ((buf[pos + 1].toInt() and 0xFF) shl 8) or
                ((buf[pos + 2].toInt() and 0xFF) shl 16) or
                ((buf[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return java.lang.Float.intBitsToFloat(bits)
        }

        fun skip(wire: Int) {
            when (wire) {
                VARINT -> varint()
                I64 -> pos += 8
                LEN -> {
                    // Read the length first: `pos += varint()` would add to the position from before the varint.
                    val length = varint().toInt()
                    pos += length
                }
                I32 -> pos += 4
                else -> throw IllegalArgumentException("Unsupported wire type $wire")
            }
            require(pos <= end) { "Skipped past end" }
        }
    }
}
