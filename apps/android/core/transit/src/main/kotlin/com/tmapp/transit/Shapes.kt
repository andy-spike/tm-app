package com.tmapp.transit

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Flat lat/lon pairs: [lat0, lon0, lat1, lon1, …]. */
class Polyline(val coords: DoubleArray) {
    val size: Int get() = coords.size / 2
    fun lat(i: Int) = coords[2 * i]
    fun lon(i: Int) = coords[2 * i + 1]
}

/**
 * Street-following pattern geometry from shapes.json. Decoded lazily per pattern; falls back to
 * straight lines between stops when a pattern has no shape.
 */
class Shapes(private val encoded: List<String>) {
    private val cache = arrayOfNulls<Polyline>(encoded.size)

    fun pattern(p: Int): Polyline? {
        if (p !in encoded.indices || encoded[p].isEmpty()) return null
        cache[p]?.let { return it }
        return decode(encoded[p]).also { cache[p] = it }
    }

    /** Geometry for a ride from pattern position [a] to [b]. */
    fun leg(tt: Timetable, p: Int, a: Int, b: Int): Polyline {
        val line = pattern(p)
        val fallback = { Polyline(DoubleArray((b - a + 1) * 2) { k -> val s = tt.stops[tt.patternStop(p, a + k / 2)]; if (k % 2 == 0) s.lat else s.lon }) }
        if (line == null || line.size < 2) return fallback()
        // Project stops onto the line in order so loops and returns map to the right pass.
        var from = 0
        var start = -1
        var end = -1
        for (i in 0..b) {
            val s = tt.stops[tt.patternStop(p, i)]
            val idx = nearest(line, s.lat, s.lon, from)
            if (i == a) start = idx
            if (i == b) end = idx
            from = idx
        }
        if (start < 0 || end <= start) return fallback()
        val first = tt.stops[tt.patternStop(p, a)]
        val last = tt.stops[tt.patternStop(p, b)]
        val out = DoubleArray((end - start + 3) * 2)
        out[0] = first.lat; out[1] = first.lon
        System.arraycopy(line.coords, start * 2, out, 2, (end - start + 1) * 2)
        out[out.size - 2] = last.lat; out[out.size - 1] = last.lon
        return Polyline(out)
    }

    private fun nearest(line: Polyline, lat: Double, lon: Double, from: Int): Int {
        var best = from
        var bestD = Double.MAX_VALUE
        // Search a forward window; stops are visited in order along the shape.
        val limit = minOf(line.size, from + 400)
        for (i in from until limit) {
            val d = (line.lat(i) - lat).let { it * it } + (line.lon(i) - lon).let { it * it }
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    companion object {
        @Serializable
        private class File(val version: String = "", val patterns: List<String>)

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): Shapes = Shapes(json.decodeFromString<File>(text).patterns)

        fun decode(s: String): Polyline {
            val out = DoubleArray(s.length * 2)
            var n = 0
            var i = 0
            var lat = 0
            var lon = 0
            while (i < s.length) {
                var result = 0
                var shift = 0
                var b: Int
                do { b = s[i++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20)
                lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
                result = 0; shift = 0
                do { b = s[i++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20)
                lon += if (result and 1 != 0) (result shr 1).inv() else result shr 1
                out[n++] = lat / 1e5
                out[n++] = lon / 1e5
            }
            return Polyline(out.copyOf(n))
        }
    }
}
