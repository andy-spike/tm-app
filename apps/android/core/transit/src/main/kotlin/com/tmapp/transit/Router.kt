package com.tmapp.transit

import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** A trip endpoint: a timetable stop ([stop] ≥ 0) or an arbitrary point such as the rider's location. */
data class Place(val name: String, val lat: Double, val lon: Double, val stop: Int = -1)

data class TripRequest(val from: Place, val to: Place, val time: Instant, val arriveBy: Boolean = false)

data class StopTime(val stop: Int, val arrival: Instant, val departure: Instant)

sealed interface Leg {
    val departure: Instant
    val arrival: Instant
    val duration: Duration get() = Duration.between(departure, arrival)
}

data class WalkLeg(
    val from: Place,
    val to: Place,
    override val departure: Instant,
    override val arrival: Instant,
    val meters: Int,
) : Leg

data class TransitLeg(
    val route: Int,
    val pattern: Int,
    val boardPosition: Int,
    val alightPosition: Int,
    /** Board, intermediate and alight stops with times. */
    val stops: List<StopTime>,
    val destination: String,
    /** Frequency-based service without exact times: departures are a headway estimate. */
    val estimated: Boolean,
) : Leg {
    override val departure: Instant get() = stops.first().departure
    override val arrival: Instant get() = stops.last().arrival
}

data class Itinerary(val legs: List<Leg>) {
    val departure: Instant get() = legs.first().departure
    val arrival: Instant get() = legs.last().arrival
    val duration: Duration get() = Duration.between(departure, arrival)
    val rides: List<TransitLeg> get() = legs.filterIsInstance<TransitLeg>()
    val transfers: Int get() = (rides.size - 1).coerceAtLeast(0)
    val walkMeters: Int get() = legs.sumOf { (it as? WalkLeg)?.meters ?: 0 }
    val signature: String get() = rides.joinToString("|") { "${it.pattern}:${it.boardPosition}@${it.departure.epochSecond}" }
}

data class RouterOptions(
    val maxRides: Int = 4,
    val maxAccessMeters: Double = 1_000.0,
    /** Walk-only option offered when origin and destination are this close. */
    val maxDirectWalkMeters: Double = 2_000.0,
    val transferSlackSeconds: Int = 60,
    val maxOptions: Int = 5,
    /** Later options are searched within this window after the requested time. */
    val windowSeconds: Int = 2 * 3600,
)

/**
 * Schedule-based RAPTOR (Delling et al.) on the flat [Timetable]. Arrive-by queries run the same scan
 * backwards on negated times, so both directions share one implementation.
 */
class Router(private val tt: Timetable, private val options: RouterOptions = RouterOptions(), private val dayProvider: (LocalDate) -> ServiceDay) {
    private val grid = StopGrid(tt)

    fun plan(request: TripRequest): List<Itinerary> {
        val date = request.time.atZone(BOGOTA).toLocalDate()
        val day = dayProvider(date)
        val reverse = request.arriveBy
        val fromOrigin = accessTimes(request.from)
        val toDestination = accessTimes(request.to)
        val results = LinkedHashMap<String, Itinerary>()
        val direct = Geo.meters(request.from.lat, request.from.lon, request.to.lat, request.to.lon)
        if (direct <= options.maxDirectWalkMeters) {
            val seconds = Geo.walkSeconds(direct).toLong()
            val start = if (reverse) request.time.minusSeconds(seconds) else request.time
            results["walk"] = Itinerary(listOf(WalkLeg(request.from, request.to, start, start.plusSeconds(seconds), (direct * Geo.DETOUR).toInt())))
        }
        if (fromOrigin.isEmpty || toDestination.isEmpty) return results.values.toList()
        val requested = day.secondsOf(request.time)
        var cursor = requested
        repeat(options.maxOptions) {
            val found = Scan(day, reverse, if (reverse) toDestination else fromOrigin, if (reverse) fromOrigin else toDestination, request)
                .run(if (reverse) -cursor else cursor)
            val transit = found.filter { it.rides.isNotEmpty() }
            if (transit.isEmpty()) return@repeat
            var added = false
            for (it in transit) if (results.putIfAbsent(it.signature, it) == null) added = true
            // Step past the best option to find the next departure (or earlier arrival for arrive-by).
            val next = if (reverse) transit.maxOf { day.secondsOf(it.arrival) } - 60 else transit.minOf { day.secondsOf(it.departure) } + 60
            if (!added && next == cursor) return@repeat
            cursor = next
            if (kotlin.math.abs(cursor - requested) > options.windowSeconds) return@repeat
        }
        return prune(results.values.toList(), reverse)
    }

    /** Removes options that are worse in every way than another, then orders them for display. */
    private fun prune(list: List<Itinerary>, reverse: Boolean): List<Itinerary> {
        val kept = list.filter { a ->
            list.none { b ->
                b !== a && b.rides.isNotEmpty() == a.rides.isNotEmpty() &&
                    b.departure >= a.departure && b.arrival <= a.arrival && b.transfers <= a.transfers &&
                    b.walkMeters <= a.walkMeters &&
                    (b.departure > a.departure || b.arrival < a.arrival || b.transfers < a.transfers || b.walkMeters < a.walkMeters)
            }
        }
        return if (reverse) kept.sortedByDescending { it.departure } else kept.sortedWith(compareBy({ it.departure }, { it.arrival }))
    }

    /** Walking seconds from a place to each stop within reach. Stations include their platforms at zero cost. */
    private fun accessTimes(place: Place): Access {
        val seconds = IntArray(tt.stopCount) { NONE }
        var meters = DoubleArray(0)
        val dist = DoubleArray(tt.stopCount) { -1.0 }
        var any = false
        fun reach(radius: Double) {
            grid.forEachWithin(place.lat, place.lon, radius) { s, d ->
                val w = Geo.walkSeconds(d)
                if (w < seconds[s]) { seconds[s] = w; dist[s] = d; any = true }
            }
        }
        if (place.stop >= 0) {
            val root = tt.rootOf(place.stop)
            for (s in tt.children[root]) { seconds[s] = 0; dist[s] = 0.0; any = true }
        }
        reach(options.maxAccessMeters)
        // Outer neighbourhoods can be farther from any stop; widen once instead of failing.
        if (!any) reach(options.maxAccessMeters * 2)
        meters = dist
        return Access(seconds, meters, !any)
    }

    private class Access(val seconds: IntArray, val meters: DoubleArray, val isEmpty: Boolean)

    private inner class Scan(
        private val day: ServiceDay,
        private val reverse: Boolean,
        private val access: Access,
        private val egress: Access,
        private val request: TripRequest,
    ) {
        private val n = tt.stopCount
        private val rounds = options.maxRides + 1
        private val arr = Array(rounds) { IntArray(n) { NONE } }
        private val kind = Array(rounds) { ByteArray(n) }
        private val footFrom = Array(rounds) { IntArray(n) }
        private val tArr = Array(rounds) { IntArray(n) { NONE } }
        private val tPattern = Array(rounds) { IntArray(n) }
        private val tRun = Array(rounds) { IntArray(n) }
        private val tBoard = Array(rounds) { IntArray(n) }
        private val tAlight = Array(rounds) { IntArray(n) }
        private val best = IntArray(n) { NONE }
        private var targetBest = NONE

        private fun boardTime(run: Int, i: Int) = if (reverse) -day.arrivalAt(run, i) else day.departureAt(run, i)
        private fun alightTime(run: Int, i: Int) = if (reverse) -day.departureAt(run, i) else day.arrivalAt(run, i)
        private fun canBoard(run: Int, i: Int) = if (reverse) day.canAlight(run, i) else day.canBoard(run, i)
        private fun canAlight(run: Int, i: Int) = if (reverse) day.canBoard(run, i) else day.canAlight(run, i)

        /** Earliest run (in search time) boardable at position [i] no earlier than [ready]. */
        private fun findRun(p: Int, i: Int, ready: Int): Int {
            val k = tt.patternStopStart[p] + i
            var bestRun = -1
            var bestTime = NONE
            if (!reverse) {
                var r = day.lowerBound(p, ready - tt.maxDepartureOffset[k])
                val end = day.patternRunStart[p + 1]
                while (r < end) {
                    if (day.runStart[r] + tt.minDepartureOffset[k] >= bestTime) break
                    val t = day.departureAt(r, i)
                    if (t >= ready && t < bestTime && day.canBoard(r, i)) { bestTime = t; bestRun = r }
                    r++
                }
            } else {
                val limit = -ready // latest real arrival allowed at i
                var r = day.lowerBound(p, limit - tt.minArrivalOffset[k] + 1) - 1
                val start = day.patternRunStart[p]
                while (r >= start) {
                    if (-(day.runStart[r] + tt.maxArrivalOffset[k]) >= bestTime) break
                    val t = -day.arrivalAt(r, i)
                    if (t >= ready && t < bestTime && day.canAlight(r, i)) { bestTime = t; bestRun = r }
                    r--
                }
            }
            return bestRun
        }

        fun run(start: Int): List<Itinerary> {
            var marked = BooleanArray(n)
            for (s in 0 until n) {
                val w = access.seconds[s]
                if (w == NONE) continue
                arr[0][s] = start + w
                best[s] = start + w
                kind[0][s] = ACCESS
                marked[s] = true
            }
            val roundTarget = IntArray(rounds) { NONE }
            val roundTargetStop = IntArray(rounds) { -1 }
            val patternFrom = IntArray(tt.patternCount) { -1 }
            for (k in 1 until rounds) {
                System.arraycopy(arr[k - 1], 0, arr[k], 0, n)
                // Queue each pattern from its first marked position in travel order.
                java.util.Arrays.fill(patternFrom, -1)
                var anyPattern = false
                for (s in 0 until n) {
                    if (!marked[s]) continue
                    for (e in tt.stopPatternStart[s] until tt.stopPatternStart[s + 1]) {
                        val p = tt.stopPatternPattern[e]
                        val pos = tt.stopPatternPosition[e]
                        val cur = patternFrom[p]
                        if (cur < 0 || (if (reverse) pos > cur else pos < cur)) patternFrom[p] = pos
                        anyPattern = true
                    }
                }
                if (!anyPattern) break
                val transitMarked = BooleanArray(n)
                for (p in 0 until tt.patternCount) {
                    val from = patternFrom[p]
                    if (from < 0) continue
                    val size = tt.patternSize(p)
                    var run = -1
                    var boardPos = -1
                    var step = if (reverse) size - 1 - from else from
                    while (step < size) {
                        val i = if (reverse) size - 1 - step else step
                        val s = tt.patternStop(p, i)
                        if (run >= 0 && canAlight(run, i)) {
                            val t = alightTime(run, i)
                            if (t < best[s] && t < targetBest) {
                                arr[k][s] = t; best[s] = t; kind[k][s] = TRANSIT
                                tArr[k][s] = t; tPattern[k][s] = p; tRun[k][s] = run; tBoard[k][s] = boardPos; tAlight[k][s] = i
                                transitMarked[s] = true
                            }
                        }
                        val prev = arr[k - 1][s]
                        if (prev != NONE) {
                            val ready = prev + if (k - 1 > 0 && kindAt(k - 1, s) != ACCESS) options.transferSlackSeconds else 0
                            if (run < 0 || ready < boardTime(run, i)) {
                                val candidate = findRun(p, i, ready)
                                if (candidate >= 0 && (run < 0 || boardTime(candidate, i) < boardTime(run, i))) {
                                    run = candidate; boardPos = i
                                }
                            }
                        }
                        step++
                    }
                }
                // One bounded walk between rides, never a chain of footpaths.
                val nextMarked = transitMarked.copyOf()
                for (s in 0 until n) {
                    if (!transitMarked[s]) continue
                    val base = tArr[k][s]
                    for (e in tt.footStart[s] until tt.footStart[s + 1]) {
                        val to = tt.footTo[e]
                        val t = base + tt.footSeconds[e]
                        if (t < best[to] && t < targetBest) {
                            arr[k][to] = t; best[to] = t; kind[k][to] = FOOT; footFrom[k][to] = s
                            nextMarked[to] = true
                        }
                    }
                }
                var any = false
                for (s in 0 until n) {
                    if (!nextMarked[s]) continue
                    any = true
                    val w = egress.seconds[s]
                    if (w == NONE) continue
                    val t = arr[k][s] + w
                    if (t < targetBest) { targetBest = t; roundTarget[k] = t; roundTargetStop[k] = s }
                }
                marked = nextMarked
                if (!any) break
            }
            val out = ArrayList<Itinerary>()
            for (k in 1 until rounds) {
                val s = roundTargetStop[k]
                if (s >= 0) build(k, s)?.let(out::add)
            }
            return out
        }

        /** Resolves labels inherited from earlier rounds. */
        private fun kindAt(round: Int, s: Int): Byte {
            var r = round
            while (r > 0 && kind[r][s] == INHERIT) r--
            return kind[r][s]
        }

        private fun build(round: Int, egressStop: Int): Itinerary? {
            // Segments in search order, from the egress side back to the access side.
            class Ride(val pattern: Int, val run: Int, val a: Int, val b: Int)
            class Walk(val from: Int, val to: Int)
            val segments = ArrayList<Any>()
            var r = round
            var s = egressStop
            var forceTransit = false
            var accessStop = -1
            var guard = 0
            while (guard++ < 64) {
                if (!forceTransit) while (r > 0 && kind[r][s] == INHERIT) r--
                val k = if (forceTransit) TRANSIT else kind[r][s]
                forceTransit = false
                when (k) {
                    ACCESS -> { accessStop = s; break }
                    TRANSIT -> {
                        val p = tPattern[r][s]
                        segments += Ride(p, tRun[r][s], tBoard[r][s], tAlight[r][s])
                        s = tt.patternStop(p, tBoard[r][s])
                        r -= 1
                    }
                    FOOT -> {
                        val from = footFrom[r][s]
                        segments += Walk(from, s)
                        s = from
                        forceTransit = true
                    }
                    else -> return null
                }
            }
            if (accessStop < 0) return null
            // Real chronological order: forward search reconstructs backwards; reverse search already runs origin-first.
            val ordered = if (reverse) segments else segments.asReversed()
            val legs = ArrayList<Leg>()
            val rides = ArrayList<TransitLeg>()
            for (seg in ordered) {
                if (seg is Ride) {
                    val a = minOf(seg.a, seg.b)
                    val b = maxOf(seg.a, seg.b)
                    val stops = (a..b).map { i ->
                        StopTime(tt.patternStop(seg.pattern, i), day.toInstant(day.arrivalAt(seg.run, i)), day.toInstant(day.departureAt(seg.run, i)))
                    }
                    val leg = TransitLeg(tt.patternRoute[seg.pattern], seg.pattern, a, b, stops, tt.patternDestination(seg.pattern), day.isEstimated(seg.run))
                    legs += leg; rides += leg
                } else if (seg is Walk) {
                    val (from, to) = if (reverse) seg.to to seg.from else seg.from to seg.to
                    val prev = legs.last()
                    val d = Geo.meters(tt.stops[from].lat, tt.stops[from].lon, tt.stops[to].lat, tt.stops[to].lon)
                    val start = prev.arrival
                    legs += WalkLeg(placeOf(from), placeOf(to), start, start.plusSeconds(Geo.walkSeconds(d).toLong()), (d * Geo.DETOUR).toInt())
                }
            }
            if (rides.isEmpty()) return null
            val firstStop = if (reverse) egressStop else accessStop
            val lastStop = if (reverse) accessStop else egressStop
            val origin = if (reverse) egress else access
            val destination = if (reverse) access else egress
            val result = ArrayList<Leg>()
            val firstBoard = rides.first()
            val accessMeters = origin.meters[firstStop]
            if (accessMeters > 0 || request.from.stop < 0) {
                val w = origin.seconds[firstStop].toLong()
                if (w > 0) result += WalkLeg(request.from, placeOf(firstStop), firstBoard.departure.minusSeconds(w), firstBoard.departure, (accessMeters * Geo.DETOUR).toInt())
            }
            result += legs
            val lastRide = rides.last()
            val egressSeconds = destination.seconds[lastStop].toLong()
            val endAnchor = legs.last().arrival
            if (egressSeconds > 0) {
                val m = destination.meters[lastStop]
                result += WalkLeg(placeOf(lastStop), request.to, endAnchor, endAnchor.plusSeconds(egressSeconds), (m * Geo.DETOUR).toInt())
            }
            // A walk between rides must not end after the next ride leaves (can happen with overtaking profiles).
            for (i in 1 until result.size - 1) {
                val w = result[i] as? WalkLeg ?: continue
                val next = result[i + 1] as? TransitLeg ?: continue
                if (w.arrival > next.departure) return null
            }
            if (lastRide.arrival.isBefore(firstBoard.departure)) return null
            return Itinerary(result)
        }

        private fun placeOf(stop: Int): Place {
            val s = tt.stops[stop]
            return Place(s.name, s.lat, s.lon, stop)
        }
    }

    private companion object {
        const val NONE = Int.MAX_VALUE / 2
        const val INHERIT: Byte = 0
        const val ACCESS: Byte = 1
        const val TRANSIT: Byte = 2
        const val FOOT: Byte = 3
    }
}
