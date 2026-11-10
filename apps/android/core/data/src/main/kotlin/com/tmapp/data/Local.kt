package com.tmapp.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.location.LocationListenerCompat
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// ---- Saved stops, routes and recent places (Room) -------------------------------------------------

@Entity(tableName = "saved_stops")
data class SavedStop(@PrimaryKey val stopId: String, val savedAt: Long)

@Entity(tableName = "saved_routes")
data class SavedRoute(@PrimaryKey val routeId: String, val savedAt: Long)

/** A place used as a trip endpoint or opened from search. [stopId] is null for map points. */
@Entity(tableName = "recent_places")
data class RecentPlace(
    @PrimaryKey val key: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val stopId: String?,
    val routeId: String?,
    val usedAt: Long,
)

@Dao
interface SavedDao {
    @Query("SELECT * FROM saved_stops ORDER BY savedAt DESC") fun stops(): Flow<List<SavedStop>>
    @Query("SELECT * FROM saved_routes ORDER BY savedAt DESC") fun routes(): Flow<List<SavedRoute>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(stop: SavedStop)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(route: SavedRoute)
    @Query("DELETE FROM saved_stops WHERE stopId = :id") suspend fun deleteStop(id: String)
    @Query("DELETE FROM saved_routes WHERE routeId = :id") suspend fun deleteRoute(id: String)
    @Query("SELECT * FROM recent_places ORDER BY usedAt DESC LIMIT 8") fun recents(): Flow<List<RecentPlace>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun touch(place: RecentPlace)
    @Query("DELETE FROM recent_places WHERE `key` NOT IN (SELECT `key` FROM recent_places ORDER BY usedAt DESC LIMIT 20)") suspend fun trimRecents()
    @Query("DELETE FROM recent_places") suspend fun clearRecents()
}

@Database(entities = [SavedStop::class, SavedRoute::class, RecentPlace::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun saved(): SavedDao
}

@Singleton
class SavedRepository @Inject constructor(private val dao: SavedDao) {
    val stops: Flow<List<SavedStop>> = dao.stops()
    val routes: Flow<List<SavedRoute>> = dao.routes()
    val recents: Flow<List<RecentPlace>> = dao.recents()

    suspend fun setStopSaved(id: String, saved: Boolean) =
        if (saved) dao.save(SavedStop(id, System.currentTimeMillis())) else dao.deleteStop(id)

    suspend fun setRouteSaved(id: String, saved: Boolean) =
        if (saved) dao.save(SavedRoute(id, System.currentTimeMillis())) else dao.deleteRoute(id)

    suspend fun remember(place: RecentPlace) {
        dao.touch(place.copy(usedAt = System.currentTimeMillis()))
        dao.trimRecents()
    }

    suspend fun clearRecents() = dao.clearRecents()
}

// ---- Settings (DataStore) -------------------------------------------------------------------------

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class Settings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val lastSyncCheck: Long = 0,
    val lastSyncResult: String? = null,
)

private val Context.dataStore by preferencesDataStore("settings")

@Singleton
class SettingsRepository @Inject constructor(private val context: Context) {
    private val theme = intPreferencesKey("theme")
    private val lastCheck = longPreferencesKey("last_sync_check")
    private val lastResult = stringPreferencesKey("last_sync_result")

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            theme = ThemeMode.entries.getOrElse(p[theme] ?: 0) { ThemeMode.SYSTEM },
            lastSyncCheck = p[lastCheck] ?: 0,
            lastSyncResult = p[lastResult],
        )
    }

    suspend fun setTheme(mode: ThemeMode) {
        context.dataStore.edit { it[theme] = mode.ordinal }
    }
    suspend fun recordSync(result: String) {
        context.dataStore.edit {
            it[lastCheck] = System.currentTimeMillis()
            it[lastResult] = result
        }
    }
}

// ---- Location (platform LocationManager, no Play Services) -----------------------------------------

data class Coordinates(val lat: Double, val lon: Double, val accuracy: Float)

@Singleton
class LocationClient @Inject constructor(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun isEnabled(): Boolean = LocationManagerCompat.isLocationEnabled(manager)

    private val _last = kotlinx.coroutines.flow.MutableStateFlow<Coordinates?>(null)
    /** The freshest fix any screen has seen, shared so every screen starts from it. */
    val last: kotlinx.coroutines.flow.StateFlow<Coordinates?> = _last

    /** Last known fix first, then live updates while collected. Empty without permission. */
    @SuppressLint("MissingPermission")
    fun updates(): Flow<Coordinates> = callbackFlow {
        if (!hasPermission()) { close(); return@callbackFlow }
        val providers = manager.getProviders(true).filter { it != LocationManager.PASSIVE_PROVIDER }
        (_last.value ?: lastKnown(providers))?.let { _last.value = it; trySend(it) }
        val listener = LocationListenerCompat { location -> location.toCoordinates().also { _last.value = it; trySend(it) } }
        val request = LocationRequestCompat.Builder(5_000).setMinUpdateDistanceMeters(15f)
            .setQuality(LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY).build()
        for (p in providers) {
            runCatching { LocationManagerCompat.requestLocationUpdates(manager, p, request, listener, Looper.getMainLooper()) }
        }
        awaitClose { LocationManagerCompat.removeUpdates(manager, listener) }
    }

    /**
     * A single fix for one-off needs such as planning from "Mi ubicación": the shared fix if any,
     * else the platform's current location, else null after [timeoutMs].
     */
    @SuppressLint("MissingPermission")
    suspend fun current(timeoutMs: Long = 8_000): Coordinates? {
        _last.value?.let { return it }
        if (!hasPermission()) return null
        val providers = manager.getProviders(true).filter { it != LocationManager.PASSIVE_PROVIDER }
        lastKnown(providers)?.let { _last.value = it; return it }
        val provider = providers.firstOrNull { it == LocationManager.GPS_PROVIDER } ?: providers.firstOrNull() ?: return null
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                val cancel = androidx.core.os.CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                LocationManagerCompat.getCurrentLocation(manager, provider, cancel, ContextCompat.getMainExecutor(context)) { loc ->
                    val c = loc?.toCoordinates()
                    if (c != null) _last.value = c
                    if (cont.isActive) cont.resumeWith(Result.success(c))
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(providers: List<String>): Coordinates? =
        providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }?.toCoordinates()

    private fun Location.toCoordinates() = Coordinates(latitude, longitude, accuracy)
}
