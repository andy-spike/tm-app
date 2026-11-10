package com.tmapp.transit

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.LocalDate
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

class BundleFormatException(message: String) : Exception(message)

/**
 * Decoder for the TMBG timetable bundle (revisions 1–3), see packages/core/BUNDLE_FORMAT.md.
 *
 * Memory matters on phones (app heaps can be 192 MB), so the payload is decompressed as a stream,
 * twice: a counting pass sizes every array exactly, then a filling pass writes into them. Nothing
 * is ever held twice and the 28 MB inflated payload is never materialised.
 */
object BundleDecoder {
    @Serializable
    private class MetaStop(
        val id: String,
        val name: String,
        val lat: Double,
        val lon: Double,
        val kind: String,
        val parentStationId: String? = null,
        val code: String? = null,
    )

    @Serializable
    private class MetaRoute(
        val id: String,
        val shortName: String,
        val longName: String? = null,
        val color: String = "",
        val agencyId: String = "",
        val system: String = "unknown",
    )

    @Serializable
    private class MetaService(
        val start: String,
        val end: String,
        val weekdays: List<Int>,
        val exceptions: Map<String, Boolean> = emptyMap(),
    )

    @Serializable
    private class Meta(
        val version: String,
        val builtAt: String? = null,
        val feedStart: String,
        val feedEnd: String,
        val stops: List<MetaStop>,
        val routes: List<MetaRoute>,
        val services: List<MetaService>,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun decode(bytes: ByteArray): Timetable {
        if (bytes.size < 16) throw BundleFormatException("Truncated bundle")
        if (bytes[0] != 'T'.code.toByte() || bytes[1] != 'M'.code.toByte() ||
            bytes[2] != 'B'.code.toByte() || bytes[3] != 'G'.code.toByte()
        ) throw BundleFormatException("Not a timetable bundle")
        val revision = u32(bytes, 4)
        if (revision !in 1..3) throw BundleFormatException("Unsupported revision $revision")
        val metaLength = u32(bytes, 8)
        val payloadLength = u32(bytes, 12)
        val metaEnd = 16 + metaLength
        if (metaLength < 0 || payloadLength < 0 || metaEnd.toLong() + payloadLength != bytes.size.toLong()) {
            throw BundleFormatException("Invalid bundle length")
        }
        val meta = json.decodeFromString<Meta>(String(bytes, 16, metaLength, Charsets.UTF_8))
        val open = {
            val raw = ByteArrayInputStream(bytes, metaEnd, payloadLength)
            Reader(if (revision >= 2) InflaterInputStream(raw, Inflater(true), 1 shl 16) else raw)
        }
        val sizes = open().use { Pass(it, meta, null).run() }.sizes ?: throw BundleFormatException("Counting pass failed")
        return open().use { Pass(it, meta, Arrays(sizes)).run() }.result ?: throw BundleFormatException("Decoding pass failed")
    }

    private fun u32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or
            ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)

    /** Unbuffered streams are slow; this keeps its own buffer and decodes LEB128 directly. */
    private class Reader(private val input: InputStream) : AutoCloseable {
        private val buf = ByteArray(1 shl 16)
        private var pos = 0
        private var len = 0

        private fun fill(): Boolean {
            len = input.read(buf)
            pos = 0
            return len > 0
        }

        fun byte(): Int {
            if (pos >= len && !fill()) throw BundleFormatException("Truncated payload")
            return buf[pos++].toInt() and 0xff
        }

        fun varint(): Int {
            var result = 0L
            var shift = 0
            while (true) {
                val b = byte()
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
                if (shift > 35) throw BundleFormatException("Varint overflow")
            }
            if (result > Int.MAX_VALUE) throw BundleFormatException("Varint overflow")
            return result.toInt()
        }

        fun skip(n: Int) { repeat(n) { byte() } }

        fun atEnd(): Boolean = pos >= len && !fill()

        override fun close() = input.close()
    }

    private class Sizes(
        val patterns: Int,
        val patternStops: Int,
        val profiles: Int,
        val times: Int,
        val trips: Int,
        val foot: Int,
    )

    private class Arrays(s: Sizes) {
        val patternRoute = IntArray(s.patterns)
        val patternStopStart = IntArray(s.patterns + 1)
        val patternProfileStart = IntArray(s.patterns + 1)
        val patternTripStart = IntArray(s.patterns + 1)
        val patternStops = IntArray(s.patternStops)
        val profileTimeStart = IntArray(s.profiles)
        val profileFlagStart = IntArray(s.profiles)
        val times = CharArray(s.times)
        val flags = ByteArray(s.times / 2)
        val tripDeparture = IntArray(s.trips)
        val tripProfile = IntArray(s.trips)
        val tripService = IntArray(s.trips)
        val tripFreqEnd = IntArray(s.trips)
        val tripHeadway = IntArray(s.trips)
        val tripExact = BooleanArray(s.trips)
        val footTo = IntArray(s.foot)
        val footSeconds = IntArray(s.foot)
    }

    /** One walk over the payload. Without [out] it only counts; with it, it fills and builds. */
    private class Pass(private val r: Reader, private val meta: Meta, private val out: Arrays?) {
        var result: Timetable? = null

        fun run(): Pass {
            val stopCount = meta.stops.size
            val patternCount = r.varint()
            if (out != null && patternCount != out.patternRoute.size) throw BundleFormatException("Payload changed between passes")
            var nStops = 0
            var nProfiles = 0
            var nTimes = 0
            var nTrips = 0
            for (p in 0 until patternCount) {
                val route = r.varint()
                if (route >= meta.routes.size) throw BundleFormatException("Invalid pattern route")
                r.skip(r.varint()) // headsign: empty in this feed; riders read direction from the terminal.
                val size = r.varint()
                if (out != null) {
                    out.patternRoute[p] = route
                    out.patternStopStart[p] = nStops
                }
                repeat(size) {
                    val s = r.varint()
                    if (s >= stopCount) throw BundleFormatException("Invalid pattern stop")
                    if (out != null) out.patternStops[nStops] = s
                    nStops++
                }
                val profileCount = r.varint()
                val firstProfile = nProfiles
                if (out != null) out.patternProfileStart[p] = firstProfile
                repeat(profileCount) {
                    if (out != null) {
                        out.profileTimeStart[nProfiles] = nTimes
                        out.profileFlagStart[nProfiles] = nTimes / 2
                    }
                    var prev = 0
                    for (k in 0 until size * 2) {
                        val d = r.varint()
                        prev += if (d and 1 == 1) -((d + 1) ushr 1) else d ushr 1
                        if (out != null) {
                            val stored = prev + Timetable.TIME_BIAS
                            if (stored !in 0..0xffff) throw BundleFormatException("Trip offset $prev s out of range")
                            out.times[nTimes + k] = stored.toChar()
                        }
                    }
                    val flagBase = nTimes / 2
                    for (k in 0 until size) {
                        val pickup = r.varint()
                        if (out != null) out.flags[flagBase + k] = (pickup and 3).toByte()
                    }
                    for (k in 0 until size) {
                        val dropoff = r.varint()
                        if (out != null) out.flags[flagBase + k] = (out.flags[flagBase + k].toInt() or ((dropoff and 3) shl 2)).toByte()
                    }
                    nTimes += size * 2
                    nProfiles++
                }
                if (out != null) out.patternTripStart[p] = nTrips
                var departure = 0
                repeat(r.varint()) {
                    departure += r.varint()
                    val profile = r.varint()
                    val service = r.varint()
                    val hasFrequency = r.varint()
                    if (profile >= profileCount || service >= meta.services.size) throw BundleFormatException("Invalid trip reference")
                    var end = -1
                    var headway = 0
                    var exact = true
                    if (hasFrequency != 0) {
                        end = r.varint()
                        headway = r.varint()
                        exact = r.varint() != 0
                        if (headway <= 0) throw BundleFormatException("Invalid headway")
                    }
                    if (out != null) {
                        out.tripDeparture[nTrips] = departure
                        out.tripProfile[nTrips] = firstProfile + profile
                        out.tripService[nTrips] = service
                        out.tripFreqEnd[nTrips] = end
                        out.tripHeadway[nTrips] = headway
                        out.tripExact[nTrips] = exact
                    }
                    nTrips++
                }
            }
            if (r.varint() != stopCount) throw BundleFormatException("Invalid footpath count")
            val footStart = IntArray(stopCount + 1)
            var nFoot = 0
            for (s in 0 until stopCount) {
                footStart[s] = nFoot
                repeat(r.varint()) {
                    val to = r.varint()
                    val seconds = r.varint()
                    if (to >= stopCount) throw BundleFormatException("Invalid footpath")
                    if (out != null) {
                        out.footTo[nFoot] = to
                        out.footSeconds[nFoot] = seconds
                    }
                    nFoot++
                }
            }
            footStart[stopCount] = nFoot
            if (!r.atEnd()) throw BundleFormatException("Invalid bundle tail")
            if (out == null) {
                sizes = Sizes(patternCount, nStops, nProfiles, nTimes, nTrips, nFoot)
                return this
            }
            out.patternStopStart[patternCount] = nStops
            out.patternProfileStart[patternCount] = nProfiles
            out.patternTripStart[patternCount] = nTrips
            result = build(out, footStart)
            return this
        }

        var sizes: Sizes? = null

        private fun build(a: Arrays, footStart: IntArray): Timetable {
            val stopIds = HashMap<String, Int>(meta.stops.size * 2)
            meta.stops.forEachIndexed { i, s -> stopIds[s.id] = i }
            val spaces = Regex("\\s{2,}")
            val stops = meta.stops.mapIndexed { i, s ->
                Stop(i, s.id, s.name.replace(spaces, " ").trim(), s.lat, s.lon, s.kind == "station", s.parentStationId?.let { stopIds[it] } ?: -1, s.code)
            }
            val routes = meta.routes.mapIndexed { i, r ->
                RouteCodes.describe(i, r.id, r.shortName, r.longName, SystemKind.parse(r.system), r.agencyId)
            }
            val services = meta.services.map { s ->
                if (s.weekdays.size != 7) throw BundleFormatException("Invalid service weekdays")
                Service(LocalDate.parse(s.start), LocalDate.parse(s.end), BooleanArray(7) { s.weekdays[it] == 1 }, s.exceptions.mapKeys { LocalDate.parse(it.key) })
            }
            return Timetable(
                version = meta.version,
                builtAt = meta.builtAt,
                feedStart = LocalDate.parse(meta.feedStart),
                feedEnd = LocalDate.parse(meta.feedEnd),
                stops = stops,
                routes = routes,
                services = services,
                patternRoute = a.patternRoute,
                patternStopStart = a.patternStopStart,
                patternStops = a.patternStops,
                patternProfileStart = a.patternProfileStart,
                profileTimeStart = a.profileTimeStart,
                profileTimes = a.times,
                profileFlagStart = a.profileFlagStart,
                flags = a.flags,
                patternTripStart = a.patternTripStart,
                tripDeparture = a.tripDeparture,
                tripProfile = a.tripProfile,
                tripService = a.tripService,
                tripFrequencyEnd = a.tripFreqEnd,
                tripHeadway = a.tripHeadway,
                tripExact = a.tripExact,
                footStart = footStart,
                footTo = a.footTo,
                footSeconds = a.footSeconds,
            )
        }
    }
}
