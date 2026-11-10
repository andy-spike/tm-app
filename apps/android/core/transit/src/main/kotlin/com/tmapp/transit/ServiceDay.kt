package com.tmapp.transit

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

val BOGOTA: ZoneId = ZoneId.of("America/Bogota")

/**
 * Every vehicle run that can matter for queries on [date]: trips from the previous, current and next
 * service day, with frequency windows expanded to a headway grid. Times are seconds from [date]'s
 * local midnight, so after-midnight GTFS times (25:10) keep their service day.
 */
class ServiceDay(val timetable: Timetable, val date: LocalDate) {
    val midnight: Long = date.atStartOfDay(BOGOTA).toEpochSecond()

    /** Size P+1. Runs of pattern p are sorted by start time. */
    val patternRunStart: IntArray
    val runStart: IntArray
    val runTrip: IntArray

    init {
        val tt = timetable
        val days = intArrayOf(-1, 0, 1)
        val active = days.map { d -> BooleanArray(tt.services.size) { tt.services[it].activeOn(date.plusDays(d.toLong())) } }
        val starts = IntArray(tt.patternCount + 1)
        val keys = ArrayList<LongArray>(tt.patternCount)
        var total = 0
        for (p in 0 until tt.patternCount) {
            var buffer = LongArray(16)
            var n = 0
            for (t in tt.patternTripStart[p] until tt.patternTripStart[p + 1]) {
                val service = tt.tripService[t]
                for ((k, d) in days.withIndex()) {
                    if (!active[k][service]) continue
                    val offset = d * 86_400
                    val end = tt.tripFrequencyEnd[t]
                    if (end < 0) {
                        if (n == buffer.size) buffer = buffer.copyOf(n * 2)
                        buffer[n++] = key(offset + tt.tripDeparture[t], t)
                    } else {
                        var s = tt.tripDeparture[t]
                        while (s < end) {
                            if (n == buffer.size) buffer = buffer.copyOf(n * 2)
                            buffer[n++] = key(offset + s, t)
                            s += tt.tripHeadway[t]
                        }
                    }
                }
            }
            val sorted = buffer.copyOf(n).also { it.sort() }
            keys += sorted
            starts[p] = total
            total += n
        }
        starts[tt.patternCount] = total
        patternRunStart = starts
        runStart = IntArray(total)
        runTrip = IntArray(total)
        var i = 0
        for (list in keys) for (k in list) {
            runStart[i] = ((k ushr 20) - START_BIAS).toInt()
            runTrip[i] = (k and TRIP_MASK).toInt()
            i++
        }
    }

    fun profileOf(run: Int): Int = timetable.tripProfile[runTrip[run]]

    /** Frequency-based runs without exact times are estimates on a headway grid. */
    fun isEstimated(run: Int): Boolean {
        val trip = runTrip[run]
        return timetable.tripFrequencyEnd[trip] >= 0 && !timetable.tripExact[trip]
    }

    fun departureAt(run: Int, position: Int): Int =
        runStart[run] + timetable.time(timetable.profileTimeStart[profileOf(run)] + 2 * position + 1)

    fun arrivalAt(run: Int, position: Int): Int =
        runStart[run] + timetable.time(timetable.profileTimeStart[profileOf(run)] + 2 * position)

    fun canBoard(run: Int, position: Int): Boolean =
        timetable.pickupType(timetable.profileFlagStart[profileOf(run)] + position) != 1

    fun canAlight(run: Int, position: Int): Boolean =
        timetable.dropoffType(timetable.profileFlagStart[profileOf(run)] + position) != 1

    fun toInstant(seconds: Int): Instant = Instant.ofEpochSecond(midnight + seconds)
    fun secondsOf(instant: Instant): Int = (instant.epochSecond - midnight).toInt()

    /** First run index of pattern [p] whose start is at least [start]. */
    fun lowerBound(p: Int, start: Int): Int {
        var lo = patternRunStart[p]
        var hi = patternRunStart[p + 1]
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (runStart[mid] < start) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private companion object {
        const val START_BIAS = 1L shl 20
        const val TRIP_MASK = (1L shl 20) - 1
        fun key(start: Int, trip: Int): Long = ((start + START_BIAS) shl 20) or trip.toLong()
    }
}
