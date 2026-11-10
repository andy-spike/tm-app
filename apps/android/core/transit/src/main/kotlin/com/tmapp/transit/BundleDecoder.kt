package com.tmapp.transit

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.util.zip.Inflater

class BundleFormatException(message: String) : Exception(message)

/**
 * Decoder for the TMBG timetable bundle (revisions 1–3), see packages/core/BUNDLE_FORMAT.md.
 * Pure JVM; runs in a few hundred milliseconds on a phone and should be called off the main thread.
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
        val body = if (revision >= 2) inflate(bytes, metaEnd, payloadLength) else bytes.copyOfRange(metaEnd, bytes.size)
        return Parser(body, meta).parse()
    }

    private fun u32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or
            ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)

    private fun inflate(bytes: ByteArray, offset: Int, length: Int): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(bytes, offset, length)
            var out = ByteArray(maxOf(length * 4, 1 shl 16))
            var size = 0
            while (!inflater.finished()) {
                if (size == out.size) out = out.copyOf(out.size * 2)
                val n = inflater.inflate(out, size, out.size - size)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw BundleFormatException("Truncated payload")
                }
                size += n
            }
            return if (size == out.size) out else out.copyOf(size)
        } finally {
            inflater.end()
        }
    }

    private class Parser(private val body: ByteArray, private val meta: Meta) {
        private var pos = 0

        private fun get(): Int {
            var result = 0L
            var shift = 0
            while (true) {
                if (pos >= body.size) throw BundleFormatException("Truncated varint")
                val b = body[pos++].toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
                if (shift > 35) throw BundleFormatException("Varint overflow")
            }
            if (result > Int.MAX_VALUE) throw BundleFormatException("Varint overflow")
            return result.toInt()
        }

        private fun count(): Int {
            val n = get()
            if (n > body.size - pos) throw BundleFormatException("Invalid count")
            return n
        }

        private fun string(): String {
            val n = count()
            val s = String(body, pos, n, Charsets.UTF_8)
            pos += n
            return s
        }

        fun parse(): Timetable {
            val stopIds = HashMap<String, Int>(meta.stops.size * 2)
            meta.stops.forEachIndexed { i, s -> stopIds[s.id] = i }
            val stops = meta.stops.mapIndexed { i, s ->
                Stop(
                    index = i,
                    id = s.id,
                    name = s.name.replace(Regex("\\s{2,}"), " ").trim(),
                    lat = s.lat,
                    lon = s.lon,
                    isStation = s.kind == "station",
                    parent = s.parentStationId?.let { stopIds[it] } ?: -1,
                    code = s.code,
                )
            }
            val routes = meta.routes.mapIndexed { i, r ->
                RouteCodes.describe(i, r.id, r.shortName, r.longName, SystemKind.parse(r.system), r.agencyId)
            }
            val services = meta.services.map { s ->
                if (s.weekdays.size != 7) throw BundleFormatException("Invalid service weekdays")
                Service(
                    start = LocalDate.parse(s.start),
                    end = LocalDate.parse(s.end),
                    weekdays = BooleanArray(7) { s.weekdays[it] == 1 },
                    exceptions = s.exceptions.mapKeys { LocalDate.parse(it.key) },
                )
            }
            val stopCount = stops.size
            val patternCount = count()
            val patternRoute = IntArray(patternCount)
            val patternStopStart = IntArray(patternCount + 1)
            val patternProfileStart = IntArray(patternCount + 1)
            val patternTripStart = IntArray(patternCount + 1)
            val patternStops = IntList(1 shl 16)
            val profileTimeStart = IntList(1 shl 16)
            val profileFlagStart = IntList(1 shl 16)
            val times = IntList(1 shl 22)
            val pickup = ByteList(1 shl 21)
            val dropoff = ByteList(1 shl 21)
            val tripDeparture = IntList(1 shl 18)
            val tripProfile = IntList(1 shl 18)
            val tripService = IntList(1 shl 18)
            val tripFreqEnd = IntList(1 shl 18)
            val tripHeadway = IntList(1 shl 18)
            val tripExact = ByteList(1 shl 18)
            for (p in 0 until patternCount) {
                val route = get()
                if (route >= routes.size) throw BundleFormatException("Invalid pattern route")
                patternRoute[p] = route
                string() // headsign: empty in the current feed; direction comes from the terminal stop.
                val size = count()
                patternStopStart[p] = patternStops.size
                repeat(size) {
                    val s = get()
                    if (s >= stopCount) throw BundleFormatException("Invalid pattern stop")
                    patternStops.add(s)
                }
                val profileCount = count()
                val firstProfile = profileTimeStart.size
                patternProfileStart[p] = firstProfile
                repeat(profileCount) {
                    profileTimeStart.add(times.size)
                    profileFlagStart.add(pickup.size)
                    var prev = 0
                    repeat(size * 2) {
                        val d = get()
                        prev += if (d and 1 == 1) -((d + 1) ushr 1) else d ushr 1
                        times.add(prev)
                    }
                    repeat(size) { pickup.add(get().toByte()) }
                    repeat(size) { dropoff.add(get().toByte()) }
                }
                patternTripStart[p] = tripDeparture.size
                var departure = 0
                repeat(count()) {
                    departure += get()
                    val profile = get()
                    val service = get()
                    val hasFrequency = get()
                    if (profile >= profileCount || service >= services.size) {
                        throw BundleFormatException("Invalid trip reference")
                    }
                    tripDeparture.add(departure)
                    tripProfile.add(firstProfile + profile)
                    tripService.add(service)
                    if (hasFrequency != 0) {
                        val end = get()
                        val headway = get()
                        val exact = get()
                        if (headway <= 0) throw BundleFormatException("Invalid headway")
                        tripFreqEnd.add(end)
                        tripHeadway.add(headway)
                        tripExact.add(if (exact != 0) 1 else 0)
                    } else {
                        tripFreqEnd.add(-1)
                        tripHeadway.add(0)
                        tripExact.add(1)
                    }
                }
            }
            patternStopStart[patternCount] = patternStops.size
            patternProfileStart[patternCount] = profileTimeStart.size
            patternTripStart[patternCount] = tripDeparture.size
            val footLists = count()
            if (footLists != stopCount) throw BundleFormatException("Invalid footpath count")
            val footStart = IntArray(stopCount + 1)
            val footTo = IntList(1 shl 17)
            val footSeconds = IntList(1 shl 17)
            for (s in 0 until stopCount) {
                footStart[s] = footTo.size
                repeat(count()) {
                    val to = get()
                    if (to >= stopCount) throw BundleFormatException("Invalid footpath")
                    footTo.add(to)
                    footSeconds.add(get())
                }
            }
            footStart[stopCount] = footTo.size
            if (pos != body.size) throw BundleFormatException("Invalid bundle tail")
            return Timetable(
                version = meta.version,
                builtAt = meta.builtAt,
                feedStart = LocalDate.parse(meta.feedStart),
                feedEnd = LocalDate.parse(meta.feedEnd),
                stops = stops,
                routes = routes,
                services = services,
                patternRoute = patternRoute,
                patternStopStart = patternStopStart,
                patternStops = patternStops.toArray(),
                patternProfileStart = patternProfileStart,
                profileTimeStart = profileTimeStart.toArray(),
                profileTimes = times.toArray(),
                profileFlagStart = profileFlagStart.toArray(),
                pickup = pickup.toArray(),
                dropoff = dropoff.toArray(),
                patternTripStart = patternTripStart,
                tripDeparture = tripDeparture.toArray(),
                tripProfile = tripProfile.toArray(),
                tripService = tripService.toArray(),
                tripFrequencyEnd = tripFreqEnd.toArray(),
                tripHeadway = tripHeadway.toArray(),
                tripExact = BooleanArray(tripExact.size) { tripExact[it] != 0.toByte() },
                footStart = footStart,
                footTo = footTo.toArray(),
                footSeconds = footSeconds.toArray(),
            )
        }
    }
}

internal class IntList(capacity: Int) {
    private var data = IntArray(capacity)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    operator fun get(i: Int): Int = data[i]
    fun toArray(): IntArray = data.copyOf(size)
}

internal class ByteList(capacity: Int) {
    private var data = ByteArray(capacity)
    var size = 0
        private set

    fun add(v: Byte) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    operator fun get(i: Int): Byte = data[i]
    fun toArray(): ByteArray = data.copyOf(size)
}
