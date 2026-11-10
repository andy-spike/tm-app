package com.tmapp.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tmapp.android.sync.TimetableUpdater
import com.tmapp.data.Coordinates
import com.tmapp.data.LocationClient
import com.tmapp.data.NearbyStop
import com.tmapp.data.RecentPlace
import com.tmapp.data.SavedRepository
import com.tmapp.data.Settings
import com.tmapp.data.SettingsRepository
import com.tmapp.data.SyncResult
import com.tmapp.data.ThemeMode
import com.tmapp.data.TransitData
import com.tmapp.data.TransitRepository
import com.tmapp.data.TransitState
import com.tmapp.transit.BOGOTA
import com.tmapp.transit.Departure
import com.tmapp.transit.Itinerary
import com.tmapp.transit.Place
import com.tmapp.transit.RouteDirection
import com.tmapp.transit.SearchHit
import com.tmapp.transit.TripRequest
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

/** Bogotá's historic centre, used when location is unavailable. */
val DEFAULT_CENTER = Coordinates(4.6018, -74.0721, 0f)

private val started = SharingStarted.WhileSubscribed(5_000)

/** Re-emits on each minute boundary so scheduled countdowns and lists roll forward. */
private fun minuteTicks(): Flow<Instant> = flow {
    while (true) {
        emit(Instant.now())
        delay(60_000 - System.currentTimeMillis() % 60_000 + 50)
    }
}

private fun ready(transit: TransitRepository): Flow<TransitData> =
    transit.state.filterIsInstance<TransitState.Ready>().map { it.data }

@HiltViewModel
class AppViewModel @Inject constructor(transit: TransitRepository, settings: SettingsRepository) : ViewModel() {
    val state: StateFlow<TransitState> = transit.state
    val settings: StateFlow<Settings> = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())
}

// ---- Home ------------------------------------------------------------------------------------------

data class SavedStopCard(val stop: Int, val departures: List<Departure>)

data class HomeState(
    val location: Coordinates? = null,
    val hasPermission: Boolean = false,
    val locationEnabled: Boolean = true,
    val nearby: List<NearbyStop>? = null,
    val saved: List<SavedStopCard> = emptyList(),
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val transit: TransitRepository,
    private val locations: LocationClient,
    saved: SavedRepository,
) : ViewModel() {
    private val permission = MutableStateFlow(locations.hasPermission())

    private val location: StateFlow<Coordinates?> = permission.flatMapLatest { granted ->
        if (granted) locations.updates() else flowOf(null)
    }.distinctUntilChanged { a, b ->
        (a == null && b == null) || (a != null && b != null && transit.distance(a.lat, a.lon, b.lat, b.lon) < 25)
    }.stateIn(viewModelScope, started, null)

    private val nearby: Flow<List<NearbyStop>?> = location.mapLatest { loc ->
        val c = loc ?: DEFAULT_CENTER
        transit.nearby(c.lat, c.lon)
    }.onStart<List<NearbyStop>?> { emit(null) }

    private val savedCards: Flow<List<SavedStopCard>> = combine(saved.stops, ready(transit), minuteTicks()) { list, data, now ->
        Triple(list, data, now)
    }.mapLatest { (list, data, now) ->
        list.mapNotNull { s ->
            val stop = data.timetable.stopById(s.stopId) ?: return@mapNotNull null
            SavedStopCard(stop.index, transit.departures(stop.index, now, 2 * 3600).distinctBy { it.lineKey }.take(3))
        }
    }.onStart { emit(emptyList()) }

    val state: StateFlow<HomeState> = combine(permission, location, nearby, savedCards) { granted, loc, near, cards ->
        HomeState(loc, granted, locations.isEnabled(), near, cards)
    }.stateIn(viewModelScope, started, HomeState(hasPermission = locations.hasPermission()))

    fun onPermissionResult() { permission.value = locations.hasPermission() }

    /** Stops drawn on the map around wherever it is looking. */
    private val _mapStops = MutableStateFlow<List<NearbyStop>>(emptyList())
    val mapStops = _mapStops.asStateFlow()
    private var mapJob: Job? = null

    fun onMapIdle(lat: Double, lon: Double) {
        mapJob?.cancel()
        mapJob = viewModelScope.launch { _mapStops.value = transit.nearby(lat, lon, radius = 1_200.0, limit = 160) }
    }
}

// ---- Stop ------------------------------------------------------------------------------------------

data class LineGroup(val key: String, val route: Int, val destination: String, val departures: List<Departure>)

data class StopState(
    val stop: Int = -1,
    val routes: List<Int> = emptyList(),
    val departures: List<Departure>? = null,
    val saved: Boolean = false,
    val filter: Int? = null,
)

@HiltViewModel(assistedFactory = StopViewModel.Factory::class)
class StopViewModel @AssistedInject constructor(
    @Assisted private val stopId: String,
    private val transit: TransitRepository,
    private val savedRepo: SavedRepository,
) : ViewModel() {
    @AssistedFactory interface Factory { fun create(stopId: String): StopViewModel }

    private val filter = MutableStateFlow<Int?>(null)

    private val base: Flow<StopState> = combine(ready(transit), minuteTicks()) { data, now -> data to now }
        .mapLatest { (data, now) ->
            val stop = data.timetable.stopById(stopId)?.index ?: -1
            if (stop < 0) StopState(departures = emptyList()) else StopState(stop, data.schedule.routesAt(stop), transit.departures(stop, now))
        }

    val state: StateFlow<StopState> = combine(base, savedRepo.stops, filter) { s, saved, f ->
        s.copy(saved = saved.any { it.stopId == stopId }, filter = f)
    }.stateIn(viewModelScope, started, StopState())

    fun setFilter(route: Int?) { filter.value = route }
    fun toggleSaved() { viewModelScope.launch { savedRepo.setStopSaved(stopId, !state.value.saved) } }

    companion object {
        fun group(departures: List<Departure>): List<LineGroup> =
            departures.groupBy { it.lineKey }.map { (k, v) -> LineGroup(k, v.first().route, v.first().destination, v) }
                .sortedBy { it.departures.first().time }
    }
}

// ---- Route -----------------------------------------------------------------------------------------

data class RouteState(val route: Int = -1, val directions: List<RouteDirection>? = null, val selected: Int = 0, val saved: Boolean = false)

@HiltViewModel(assistedFactory = RouteViewModel.Factory::class)
class RouteViewModel @AssistedInject constructor(
    @Assisted private val routeId: String,
    private val transit: TransitRepository,
    private val savedRepo: SavedRepository,
) : ViewModel() {
    @AssistedFactory interface Factory { fun create(routeId: String): RouteViewModel }

    private val selected = MutableStateFlow(0)

    private val base: Flow<RouteState> = ready(transit).map { data ->
        val route = data.timetable.routeById(routeId)?.index ?: -1
        if (route < 0) RouteState(directions = emptyList()) else RouteState(route, transit.directions(route, LocalDate.now(BOGOTA)))
    }

    val state: StateFlow<RouteState> = combine(base, savedRepo.routes, selected) { s, saved, sel ->
        s.copy(selected = sel.coerceIn(0, ((s.directions?.size ?: 1) - 1).coerceAtLeast(0)), saved = saved.any { it.routeId == routeId })
    }.stateIn(viewModelScope, started, RouteState())

    fun select(i: Int) { selected.value = i }
    fun toggleSaved() { viewModelScope.launch { savedRepo.setRouteSaved(routeId, !state.value.saved) } }
}

// ---- Search ----------------------------------------------------------------------------------------

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val transit: TransitRepository,
    private val savedRepo: SavedRepository,
) : ViewModel() {
    val query = MutableStateFlow("")

    val results: StateFlow<List<SearchHit>?> = query.debounce { if (it.length <= 1) 0L else 120L }
        .mapLatest { q -> if (q.isBlank()) null else transit.search(q) }
        .stateIn(viewModelScope, started, null)

    val recents: StateFlow<List<RecentPlace>> = savedRepo.recents.stateIn(viewModelScope, started, emptyList())

    fun remember(place: RecentPlace) { viewModelScope.launch { savedRepo.remember(place) } }
    fun clearRecents() { viewModelScope.launch { savedRepo.clearRecents() } }
}

// ---- Trip planner (activity scoped: shared by planner, search picker and itinerary) ----------------

sealed interface Endpoint {
    data object MyLocation : Endpoint
    data class At(val place: Place) : Endpoint
}

enum class TimeMode { NOW, DEPART, ARRIVE }

sealed interface PlanState {
    data object Idle : PlanState
    data object Loading : PlanState
    data object NeedLocation : PlanState
    data class Done(val options: List<Itinerary>, val request: TripRequest) : PlanState
}

data class TripState(
    val from: Endpoint? = null,
    val to: Endpoint? = null,
    val mode: TimeMode = TimeMode.NOW,
    val date: LocalDate = LocalDate.now(BOGOTA),
    val time: LocalTime = LocalTime.now(BOGOTA).withSecond(0).withNano(0),
    val plan: PlanState = PlanState.Idle,
)

@HiltViewModel
class TripViewModel @Inject constructor(
    private val transit: TransitRepository,
    private val locations: LocationClient,
    private val savedRepo: SavedRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(TripState(from = if (locations.hasPermission()) Endpoint.MyLocation else null))
    val state = _state.asStateFlow()
    private var lastFix: Coordinates? = null
    private var locationJob: Job? = null
    private var planJob: Job? = null

    init { watchLocation() }

    fun onPermissionResult() {
        if (!locations.hasPermission()) return
        if (_state.value.from == null && _state.value.to != Endpoint.MyLocation) _state.value = _state.value.copy(from = Endpoint.MyLocation)
        watchLocation()
        replan()
    }

    private fun watchLocation() {
        if (locationJob != null || !locations.hasPermission()) return
        locationJob = viewModelScope.launch {
            locations.updates().collect { fix ->
                val first = lastFix == null
                lastFix = fix
                if (first && usesLocation()) replan()
            }
        }
    }

    private fun usesLocation() = _state.value.from == Endpoint.MyLocation || _state.value.to == Endpoint.MyLocation

    fun setFrom(e: Endpoint?) { _state.value = _state.value.copy(from = e); replan() }
    fun setTo(e: Endpoint?) { _state.value = _state.value.copy(to = e); replan() }
    fun swap() { _state.value = _state.value.copy(from = _state.value.to, to = _state.value.from); replan() }
    fun setTime(mode: TimeMode, date: LocalDate = _state.value.date, time: LocalTime = _state.value.time) {
        _state.value = _state.value.copy(mode = mode, date = date, time = time)
        replan()
    }

    /** From a stop screen: use the stop as origin or destination, keeping a sensible other end. */
    fun planWithStop(place: Place, asOrigin: Boolean) {
        val s = _state.value
        val here = Endpoint.At(place)
        _state.value = if (asOrigin) {
            s.copy(from = here, to = s.to.takeIf { it != here && it != Endpoint.MyLocation })
        } else {
            s.copy(to = here, from = (s.from.takeIf { it != here }) ?: if (locations.hasPermission()) Endpoint.MyLocation else null)
        }
        replan()
    }

    fun replan() {
        planJob?.cancel()
        val s = _state.value
        val from = s.from
        val to = s.to
        if (from == null || to == null) { _state.value = s.copy(plan = PlanState.Idle); return }
        val fromPlace = resolve(from)
        val toPlace = resolve(to)
        if (fromPlace == null || toPlace == null) {
            _state.value = s.copy(plan = if (locations.hasPermission()) PlanState.Loading else PlanState.NeedLocation)
            return
        }
        val time = if (s.mode == TimeMode.NOW) Instant.now() else s.date.atTime(s.time).atZone(BOGOTA).toInstant()
        val request = TripRequest(fromPlace, toPlace, time, arriveBy = s.mode == TimeMode.ARRIVE)
        _state.value = s.copy(plan = PlanState.Loading)
        planJob = viewModelScope.launch {
            val options = transit.plan(request)
            _state.value = _state.value.copy(plan = PlanState.Done(options, request))
        }
    }

    private fun resolve(e: Endpoint): Place? = when (e) {
        is Endpoint.At -> e.place
        Endpoint.MyLocation -> lastFix?.let { Place("", it.lat, it.lon) }
    }

    fun rememberStop(stopId: String, name: String, lat: Double, lon: Double) {
        viewModelScope.launch { savedRepo.remember(RecentPlace("stop:$stopId", name, lat, lon, stopId, null, 0)) }
    }

    fun itinerary(index: Int): Itinerary? = (_state.value.plan as? PlanState.Done)?.options?.getOrNull(index)
}

// ---- Saved -----------------------------------------------------------------------------------------

data class SavedState(val stops: List<SavedStopCard> = emptyList(), val routes: List<Int> = emptyList(), val loaded: Boolean = false)

@HiltViewModel
class SavedViewModel @Inject constructor(
    private val transit: TransitRepository,
    savedRepo: SavedRepository,
) : ViewModel() {
    val state: StateFlow<SavedState> = combine(savedRepo.stops, savedRepo.routes, ready(transit), minuteTicks()) { s, r, data, now ->
        Inputs(s.map { it.stopId }, r.map { it.routeId }, data, now)
    }.mapLatest { (stops, routes, data, now) ->
        SavedState(
            stops = stops.mapNotNull { id ->
                val stop = data.timetable.stopById(id) ?: return@mapNotNull null
                SavedStopCard(stop.index, transit.departures(stop.index, now, 2 * 3600).distinctBy { it.lineKey }.take(4))
            },
            routes = routes.mapNotNull { data.timetable.routeById(it)?.index },
            loaded = true,
        )
    }.stateIn(viewModelScope, started, SavedState())

    private data class Inputs(val stops: List<String>, val routes: List<String>, val data: TransitData, val now: Instant)
}

// ---- Settings --------------------------------------------------------------------------------------

sealed interface CheckState {
    data object Idle : CheckState
    data object Checking : CheckState
    data class Done(val result: SyncResult) : CheckState
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val updater: TimetableUpdater,
    private val settingsRepo: SettingsRepository,
    transit: TransitRepository,
) : ViewModel() {
    val data: StateFlow<TransitData?> = transit.state.map { (it as? TransitState.Ready)?.data }.stateIn(viewModelScope, started, null)
    private val _check = MutableStateFlow<CheckState>(CheckState.Idle)
    val check = _check.asStateFlow()

    fun checkNow() {
        if (_check.value == CheckState.Checking) return
        _check.value = CheckState.Checking
        viewModelScope.launch { _check.value = CheckState.Done(updater.check()) }
    }

    fun setTheme(mode: ThemeMode) { viewModelScope.launch { settingsRepo.setTheme(mode) } }
}
