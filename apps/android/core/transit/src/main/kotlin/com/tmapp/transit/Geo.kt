package com.tmapp.transit

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot

object Geo {
    /** Equirectangular distance in meters; accurate to well under 1% at city scale. */
    fun meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val rad = PI / 180
        val x = (lon2 - lon1) * rad * cos((lat1 + lat2) * rad / 2)
        val y = (lat2 - lat1) * rad
        return hypot(x, y) * 6_371_000.0
    }

    /** Straight line × 1.3 detour at 1.25 m/s (≈4.5 km/h), matching the TypeScript router. */
    const val DETOUR = 1.3
    const val WALK_SPEED = 1.25
    fun walkSeconds(straightMeters: Double): Int = ceil(straightMeters * DETOUR / WALK_SPEED).toInt()
}

/** Simple uniform grid for nearest-stop lookups. */
class StopGrid(private val timetable: Timetable, private val cellDegrees: Double = 0.005) {
    private val cells = HashMap<Long, IntArray>()

    init {
        val temp = HashMap<Long, MutableList<Int>>()
        for (s in timetable.stops) temp.getOrPut(key(cellOf(s.lat), cellOf(s.lon))) { mutableListOf() }.add(s.index)
        temp.forEach { (k, v) -> cells[k] = v.toIntArray() }
    }

    private fun cellOf(v: Double) = kotlin.math.floor(v / cellDegrees).toInt()
    private fun key(a: Int, b: Int) = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)

    /** Calls [visit] with every stop index within [radius] meters, plus its distance. */
    inline fun forEachWithin(lat: Double, lon: Double, radius: Double, visit: (stop: Int, meters: Double) -> Unit) {
        val span = ceil(radius / (cellDegreesMeters())).toInt() + 1
        val cy = cellIndex(lat)
        val cx = cellIndex(lon)
        for (dy in -span..span) for (dx in -span..span) {
            val list = cell(cy + dy, cx + dx) ?: continue
            for (s in list) {
                val stop = stops()[s]
                val d = Geo.meters(lat, lon, stop.lat, stop.lon)
                if (d <= radius) visit(s, d)
            }
        }
    }

    @PublishedApi internal fun cellDegreesMeters(): Double = cellDegrees * 110_000.0
    @PublishedApi internal fun cellIndex(v: Double): Int = cellOf(v)
    @PublishedApi internal fun cell(a: Int, b: Int): IntArray? = cells[key(a, b)]
    @PublishedApi internal fun stops(): List<Stop> = timetable.stops
}
