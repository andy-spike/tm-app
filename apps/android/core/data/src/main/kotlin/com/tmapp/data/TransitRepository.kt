package com.tmapp.data

import android.os.SystemClock
import android.util.Log
import com.tmapp.transit.BOGOTA
import com.tmapp.transit.BundleDecoder
import com.tmapp.transit.Departure
import com.tmapp.transit.Geo
import com.tmapp.transit.Itinerary
import com.tmapp.transit.Router
import com.tmapp.transit.RouteDirection
import com.tmapp.transit.Schedule
import com.tmapp.transit.SearchHit
import com.tmapp.transit.ServiceDay
import com.tmapp.transit.Shapes
import com.tmapp.transit.StopGrid
import com.tmapp.transit.Timetable
import com.tmapp.transit.TransitSearch
import com.tmapp.transit.TripRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Everything decoded from one bundle. Immutable; replaced as a whole when a new bundle activates. */
class TransitData(
    val bundle: InstalledBundle,
    val timetable: Timetable,
    val shapes: Shapes,
    val mapArchive: File,
) {
    val schedule = Schedule(timetable)
    val search by lazy { TransitSearch(timetable) }
    val grid by lazy { StopGrid(timetable) }
    private val days = object : LinkedHashMap<LocalDate, ServiceDay>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LocalDate, ServiceDay>?) = size > 3
    }
    val router = Router(timetable) { day(it) }

    @Synchronized
    fun day(date: LocalDate): ServiceDay = days.getOrPut(date) { ServiceDay(timetable, date) }
    fun dayAt(instant: Instant): ServiceDay = day(instant.atZone(BOGOTA).toLocalDate())
}

sealed interface TransitState {
    data object Loading : TransitState
    data class Ready(val data: TransitData) : TransitState
    data class Failed(val message: String) : TransitState
}

data class NearbyStop(val stop: Int, val meters: Double, val routes: List<Int>)

@Singleton
class TransitRepository @Inject constructor(private val store: BundleStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<TransitState>(TransitState.Loading)
    val state: StateFlow<TransitState> = _state.asStateFlow()

    /** Starts decoding immediately; safe to call more than once. */
    fun start() {
        if (started) return
        started = true
        scope.launch { load { store.active() } }
    }

    @Volatile private var started = false

    suspend fun activate(bundle: InstalledBundle) = load { bundle }

    private suspend fun load(source: () -> InstalledBundle) {
        try {
            val t0 = SystemClock.elapsedRealtime()
            val bundle = withContext(Dispatchers.IO) { source() }
            val shapesText = scope.async(Dispatchers.IO) { bundle.file(BundleStore.SHAPES).readText() }
            val bytes = withContext(Dispatchers.IO) { bundle.file(BundleStore.TIMETABLE).readBytes() }
            val timetable = BundleDecoder.decode(bytes)
            val data = TransitData(bundle, timetable, Shapes.parse(shapesText.await()), bundle.file(BundleStore.MAP))
            // Warm today's service day and search index before the first screen asks.
            data.dayAt(Instant.now())
            _state.value = TransitState.Ready(data)
            Log.i(TAG, "Timetable ${timetable.version} ready in ${SystemClock.elapsedRealtime() - t0} ms")
            scope.launch { data.search; data.grid }
        } catch (e: Exception) {
            Log.e(TAG, "Could not load timetable", e)
            if (_state.value !is TransitState.Ready) _state.value = TransitState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun data(): TransitData = state.filterIsInstance<TransitState.Ready>().first().data

    suspend fun nearby(lat: Double, lon: Double, radius: Double = 700.0, limit: Int = 25): List<NearbyStop> = withContext(Dispatchers.Default) {
        val d = data()
        val tt = d.timetable
        val best = HashMap<Int, Double>()
        d.grid.forEachWithin(lat, lon, radius) { s, m ->
            val root = tt.rootOf(s)
            // Platforms collapse into their station at the nearest platform's distance.
            if (m < (best[root] ?: Double.MAX_VALUE)) best[root] = m
        }
        best.entries.sortedBy { it.value }.take(limit).map { (s, m) -> NearbyStop(s, m, d.schedule.routesAt(s)) }
            .filter { it.routes.isNotEmpty() }
    }

    suspend fun departures(stop: Int, from: Instant = Instant.now(), horizonSeconds: Int = 3 * 3600): List<Departure> = withContext(Dispatchers.Default) {
        val d = data()
        d.schedule.departures(d.dayAt(from), stop, from, horizonSeconds)
    }

    suspend fun directions(route: Int, date: LocalDate = LocalDate.now(BOGOTA)): List<RouteDirection> = withContext(Dispatchers.Default) {
        val d = data()
        d.schedule.directions(d.day(date), route)
    }

    suspend fun plan(request: TripRequest): List<Itinerary> = withContext(Dispatchers.Default) {
        val d = data()
        val t0 = SystemClock.elapsedRealtime()
        d.router.plan(request).also { Log.i(TAG, "Planned ${it.size} options in ${SystemClock.elapsedRealtime() - t0} ms") }
    }

    suspend fun search(query: String): List<SearchHit> = withContext(Dispatchers.Default) { data().search.search(query) }

    fun distance(aLat: Double, aLon: Double, bLat: Double, bLon: Double) = Geo.meters(aLat, aLon, bLat, bLon)

    private companion object { const val TAG = "TransitRepository" }
}
