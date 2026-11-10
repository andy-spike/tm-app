package com.tmapp.transit

import java.time.Instant

data class Departure(
    val route: Int,
    val pattern: Int,
    val stop: Int,
    val position: Int,
    val time: Instant,
    val destination: String,
    val estimated: Boolean,
) {
    /** Stable grouping key: a route in one direction. */
    val lineKey: String get() = "$route>$destination"
}

data class RouteDirection(
    val pattern: Int,
    val destination: String,
    val stops: IntArray,
    /** Today's first and last departure from the first stop, null when it does not run today. */
    val first: Instant?,
    val last: Instant?,
    val tripsToday: Int,
)

class Schedule(private val tt: Timetable) {
    /**
     * Departures from [stop] (a station includes its platforms) in `[from, from + horizonSeconds]`,
     * excluding arrival-only stops at a pattern's terminal.
     */
    fun departures(day: ServiceDay, stop: Int, from: Instant, horizonSeconds: Int = 3 * 3600, limit: Int = 200): List<Departure> {
        val start = day.secondsOf(from)
        val end = start + horizonSeconds
        val out = ArrayList<Departure>()
        val stops = tt.children[tt.rootOf(stop)].takeIf { tt.stops[tt.rootOf(stop)].isStation } ?: intArrayOf(stop)
        for (s in stops) {
            for (e in tt.stopPatternStart[s] until tt.stopPatternStart[s + 1]) {
                val p = tt.stopPatternPattern[e]
                val i = tt.stopPatternPosition[e]
                if (i == tt.patternSize(p) - 1) continue
                val k = tt.patternStopStart[p] + i
                var r = day.lowerBound(p, start - tt.maxDepartureOffset[k])
                val last = day.patternRunStart[p + 1]
                val destination = tt.patternDestination(p)
                while (r < last && day.runStart[r] + tt.minDepartureOffset[k] <= end) {
                    val t = day.departureAt(r, i)
                    if (t in start..end && day.canBoard(r, i)) {
                        out += Departure(tt.patternRoute[p], p, s, i, day.toInstant(t), destination, day.isEstimated(r))
                    }
                    r++
                }
            }
        }
        out.sortBy { it.time }
        // The same vehicle can be listed twice when two platforms of a station share a pattern.
        val unique = out.distinctBy { "${it.pattern}@${it.time.epochSecond}" }
        return if (unique.size > limit) unique.subList(0, limit) else unique
    }

    /** Distinct directions of a route, the busiest pattern for each terminal. */
    fun directions(day: ServiceDay, route: Int): List<RouteDirection> {
        val byDestination = tt.routePatterns[route].groupBy { tt.patternDestination(it) }
        return byDestination.map { (destination, patterns) ->
            val main = patterns.maxBy { tt.tripCount(it) * 1000 + tt.patternSize(it) }
            var first: Int? = null
            var last: Int? = null
            var count = 0
            for (p in patterns) {
                for (t in tt.patternTripStart[p] until tt.patternTripStart[p + 1]) {
                    if (!tt.services[tt.tripService[t]].activeOn(day.date)) continue
                    val dep = tt.tripDeparture[t]
                    val end = tt.tripFrequencyEnd[t]
                    val lastDep = if (end < 0) dep else end - 1 - (end - 1 - dep) % tt.tripHeadway[t]
                    count += if (end < 0) 1 else (end - dep + tt.tripHeadway[t] - 1) / tt.tripHeadway[t]
                    if (first == null || dep < first) first = dep
                    if (last == null || lastDep > last) last = lastDep
                }
            }
            RouteDirection(
                pattern = main,
                destination = destination,
                stops = IntArray(tt.patternSize(main)) { tt.patternStop(main, it) },
                first = first?.let(day::toInstant),
                last = last?.let(day::toInstant),
                tripsToday = count,
            )
        }.sortedByDescending { it.tripsToday }
    }

    /** Routes serving a stop (station includes platforms), most frequent first. */
    fun routesAt(stop: Int): List<Int> {
        val root = tt.rootOf(stop)
        val stops = if (tt.stops[root].isStation) tt.children[root] else intArrayOf(stop)
        val weight = HashMap<Int, Int>()
        for (s in stops) for (e in tt.stopPatternStart[s] until tt.stopPatternStart[s + 1]) {
            val p = tt.stopPatternPattern[e]
            weight.merge(tt.patternRoute[p], tt.tripCount(p), Int::plus)
        }
        return weight.entries.sortedByDescending { it.value }.map { it.key }.distinctBy { tt.routes[it].code }
    }
}
