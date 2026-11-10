package com.tmapp.transit

import java.time.LocalDate

enum class SystemKind { TRONCAL, ALIMENTADOR, URBANO, COMPLEMENTARIO, ESPECIAL, DUAL, CABLE, UNKNOWN;

    companion object {
        fun parse(value: String): SystemKind = when (value) {
            "troncal" -> TRONCAL
            "alimentador" -> ALIMENTADOR
            "urbano" -> URBANO
            "complementario" -> COMPLEMENTARIO
            "especial" -> ESPECIAL
            "dual" -> DUAL
            "cable" -> CABLE
            else -> UNKNOWN
        }
    }
}

/** A GTFS stop or station. [parent] is the station index for platforms, else -1. */
class Stop(
    val index: Int,
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val isStation: Boolean,
    val parent: Int,
    val code: String?,
)

/**
 * A route as riders read it. Paired SITP routes such as `HA617` carry [aliases] `A617`, `H617`.
 * [code] is the label shown on badges.
 */
class Route(
    val index: Int,
    val id: String,
    val feedCode: String,
    val code: String,
    val aliases: List<String>,
    val longName: String,
    val system: SystemKind,
    val agencyId: String,
)

class Service(
    val start: LocalDate,
    val end: LocalDate,
    /** Monday-first. */
    val weekdays: BooleanArray,
    val exceptions: Map<LocalDate, Boolean>,
) {
    fun activeOn(date: LocalDate): Boolean {
        exceptions[date]?.let { return it }
        return date >= start && date <= end && weekdays[date.dayOfWeek.value - 1]
    }
}

/**
 * Immutable in-memory timetable using flat primitive arrays (CSR layouts) so that routing
 * touches few objects. All times are seconds; profile times are relative to a trip's first departure.
 */
class Timetable(
    val version: String,
    val builtAt: String?,
    val feedStart: LocalDate,
    val feedEnd: LocalDate,
    val stops: List<Stop>,
    val routes: List<Route>,
    val services: List<Service>,
    // Patterns
    val patternRoute: IntArray,
    /** Size P+1. Stops of pattern p are patternStops[patternStopStart[p] until patternStopStart[p+1]]. */
    val patternStopStart: IntArray,
    val patternStops: IntArray,
    /** Size P+1, global profile index ranges. */
    val patternProfileStart: IntArray,
    /** Per profile: offset into [profileTimes] (2 values per stop: arrival, departure). */
    val profileTimeStart: IntArray,
    /** Offsets in seconds, stored unsigned 16-bit with [TIME_BIAS]; read with [time]. Halves the largest array. */
    val profileTimes: CharArray,
    /** Per profile: offset into [flags] (1 value per stop). */
    val profileFlagStart: IntArray,
    /** GTFS pickup type in bits 0–1, drop-off type in bits 2–3. */
    val flags: ByteArray,
    /** Size P+1, trips of pattern p. */
    val patternTripStart: IntArray,
    val tripDeparture: IntArray,
    /** Global profile index. */
    val tripProfile: IntArray,
    val tripService: IntArray,
    /** -1 when the trip is not frequency based. */
    val tripFrequencyEnd: IntArray,
    val tripHeadway: IntArray,
    val tripExact: BooleanArray,
    /** Footpaths in CSR layout, size N+1. */
    val footStart: IntArray,
    val footTo: IntArray,
    val footSeconds: IntArray,
) {
    val patternCount: Int get() = patternRoute.size

    fun time(i: Int): Int = profileTimes[i].code - TIME_BIAS
    fun pickupType(i: Int): Int = flags[i].toInt() and 3
    fun dropoffType(i: Int): Int = (flags[i].toInt() shr 2) and 3

    companion object {
        /** Allows first arrivals up to ~17 min before the first departure and trips up to ~17.9 h. */
        const val TIME_BIAS = 1024
    }
    val stopCount: Int get() = stops.size

    fun patternSize(p: Int): Int = patternStopStart[p + 1] - patternStopStart[p]
    fun patternStop(p: Int, i: Int): Int = patternStops[patternStopStart[p] + i]

    /** Stop -> (pattern, position) incidence, CSR layout. */
    val stopPatternStart: IntArray
    val stopPatternPattern: IntArray
    val stopPatternPosition: IntArray

    /** Per pattern position: min/max departure and arrival offsets across profiles. */
    val minDepartureOffset: IntArray
    val maxDepartureOffset: IntArray
    val minArrivalOffset: IntArray
    val maxArrivalOffset: IntArray

    /** Station index -> child stops (including the station itself). */
    val children: Array<IntArray>

    /** Route -> patterns. */
    val routePatterns: Array<IntArray>

    init {
        val n = stops.size
        val counts = IntArray(n + 1)
        for (s in patternStops) counts[s + 1]++
        for (i in 0 until n) counts[i + 1] += counts[i]
        stopPatternStart = counts.copyOf()
        stopPatternPattern = IntArray(patternStops.size)
        stopPatternPosition = IntArray(patternStops.size)
        val cursor = counts.copyOf()
        for (p in 0 until patternCount) {
            for (i in 0 until patternSize(p)) {
                val s = patternStop(p, i)
                val slot = cursor[s]++
                stopPatternPattern[slot] = p
                stopPatternPosition[slot] = i
            }
        }
        val total = patternStops.size
        minDepartureOffset = IntArray(total) { Int.MAX_VALUE }
        maxDepartureOffset = IntArray(total) { Int.MIN_VALUE }
        minArrivalOffset = IntArray(total) { Int.MAX_VALUE }
        maxArrivalOffset = IntArray(total) { Int.MIN_VALUE }
        for (p in 0 until patternCount) {
            val base = patternStopStart[p]
            for (prof in patternProfileStart[p] until patternProfileStart[p + 1]) {
                val t = profileTimeStart[prof]
                for (i in 0 until patternSize(p)) {
                    val arr = time(t + 2 * i)
                    val dep = time(t + 2 * i + 1)
                    val k = base + i
                    if (dep < minDepartureOffset[k]) minDepartureOffset[k] = dep
                    if (dep > maxDepartureOffset[k]) maxDepartureOffset[k] = dep
                    if (arr < minArrivalOffset[k]) minArrivalOffset[k] = arr
                    if (arr > maxArrivalOffset[k]) maxArrivalOffset[k] = arr
                }
            }
        }
        val kids = Array(n) { mutableListOf<Int>() }
        for (s in stops) {
            kids[s.index].add(s.index)
            if (s.parent >= 0) kids[s.parent].add(s.index)
        }
        children = Array(n) { kids[it].toIntArray() }
        val byRoute = Array(routes.size) { mutableListOf<Int>() }
        for (p in 0 until patternCount) byRoute[patternRoute[p]].add(p)
        routePatterns = Array(routes.size) { byRoute[it].toIntArray() }
    }

    /** Station for a platform, else the stop itself. */
    fun rootOf(stop: Int): Int = stops[stop].parent.takeIf { it >= 0 } ?: stop

    fun stopById(id: String): Stop? = stopIndex[id]?.let { stops[it] }
    fun routeById(id: String): Route? = routeIndex[id]?.let { routes[it] }

    private val stopIndex: Map<String, Int> by lazy { stops.associate { it.id to it.index } }
    private val routeIndex: Map<String, Int> by lazy { routes.associate { it.id to it.index } }

    /** The last stop's name: the feed has no headsigns, so riders read direction from the terminal. */
    fun patternDestination(p: Int): String = stops[rootOf(patternStop(p, patternSize(p) - 1))].name

    fun tripCount(p: Int): Int = patternTripStart[p + 1] - patternTripStart[p]
}
