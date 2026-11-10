package com.tmapp.android.ui.map

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PointF
import android.view.Gravity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.gson.JsonObject
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.io.File

data class MapStop(val stop: Int, val lat: Double, val lon: Double, val station: Boolean, val selected: Boolean = false)

/** [coords] are flat lat/lon pairs. */
class MapLine(val coords: DoubleArray, val color: Color, val dashed: Boolean = false, val width: Float = 5f)

data class MapPin(val lat: Double, val lon: Double, val color: Color)

sealed interface CameraTarget {
    data class Center(val lat: Double, val lon: Double, val zoom: Double) : CameraTarget
    /** Fits flat lat/lon pairs inside the map with padding in dp. */
    class Fit(val coords: DoubleArray, val paddingDp: Int = 48, val maxZoom: Double = 16.5) : CameraTarget
}

private object StyleCache {
    private val cache = HashMap<String, String>()

    fun get(context: Context, dark: Boolean, archive: File): String = synchronized(cache) {
        val key = "$dark:${archive.absolutePath}"
        cache.getOrPut(key) {
            val raw = context.assets.open(if (dark) "map/style-dark.json" else "map/style-light.json").use { it.readBytes().decodeToString() }
            raw.replace("pmtiles://{archive}", "pmtiles://file://${archive.absolutePath}")
        }
    }
}

/**
 * Offline MapLibre map. The map is decorative for accessibility: every screen lists the same stops
 * and routes as text, so TalkBack skips it.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun TransitMap(
    archive: File,
    dark: Boolean,
    camera: CameraTarget,
    modifier: Modifier = Modifier,
    stops: List<MapStop> = emptyList(),
    lines: List<MapLine> = emptyList(),
    pins: List<MapPin> = emptyList(),
    user: Pair<Double, Double>? = null,
    accent: Color = Color(0xFF0E7C5B),
    bottomPaddingDp: Int = 0,
    onStopClick: ((Int) -> Unit)? = null,
    onCameraIdle: ((lat: Double, lon: Double) -> Unit)? = null,
    interactive: Boolean = true,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    val stopClick by rememberUpdatedState(onStopClick)
    val idle by rememberUpdatedState(onCameraIdle)
    val mapView = remember {
        val options = MapLibreMapOptions.createFromAttributes(context)
            .camera(CameraPosition.Builder().target(LatLng(4.6097, -74.0817)).zoom(12.0).build())
            .attributionGravity(Gravity.BOTTOM or Gravity.END)
            .logoEnabled(false)
            .compassEnabled(false)
            .rotateGesturesEnabled(false)
            .tiltGesturesEnabled(false)
            .scrollGesturesEnabled(interactive)
            .zoomGesturesEnabled(interactive)
            .doubleTapGesturesEnabled(interactive)
        MapView(context, options).apply {
            onCreate(null)
            getMapAsync { m ->
                m.setMinZoomPreference(10.0)
                m.setMaxZoomPreference(17.5)
                m.setLatLngBoundsForCameraTarget(LatLngBounds.from(4.90, -73.95, 4.30, -74.30))
                m.addOnMapClickListener { latLng ->
                    val handler = stopClick ?: return@addOnMapClickListener false
                    val p: PointF = m.projection.toScreenLocation(latLng)
                    val r = 14 * density
                    val hit = m.queryRenderedFeatures(android.graphics.RectF(p.x - r, p.y - r, p.x + r, p.y + r), STOPS_LAYER, STATIONS_LAYER)
                        .firstOrNull()?.getNumberProperty("stop")?.toInt()
                    if (hit != null) handler(hit)
                    hit != null
                }
                m.addOnCameraIdleListener {
                    val target = m.cameraPosition.target ?: return@addOnCameraIdleListener
                    idle?.invoke(target.latitude, target.longitude)
                }
                map = m
            }
        }
    }
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onStop()
            mapView.onDestroy()
        }
    }
    // Style: reload only when the theme or archive changes.
    LaunchedEffect(map, dark, archive) {
        val m = map ?: return@LaunchedEffect
        style = null
        m.setStyle(Style.Builder().fromJson(StyleCache.get(context, dark, archive))) { s ->
            s.addSource(GeoJsonSource(LINES_SOURCE))
            s.addSource(GeoJsonSource(STOPS_SOURCE))
            s.addSource(GeoJsonSource(PINS_SOURCE))
            s.addSource(GeoJsonSource(USER_SOURCE))
            val casing = if (dark) "#0B0B0D" else "#FFFFFF"
            s.addLayer(
                LineLayer(LINES_CASING, LINES_SOURCE).withProperties(
                    PropertyFactory.lineColor(casing),
                    PropertyFactory.lineWidth(Expression.sum(Expression.get("width"), Expression.literal(3f))),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ).withFilter(Expression.eq(Expression.get("dashed"), false)),
            )
            s.addLayer(
                LineLayer(LINES_LAYER, LINES_SOURCE).withProperties(
                    PropertyFactory.lineColor(Expression.get("color")),
                    PropertyFactory.lineWidth(Expression.get("width")),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ).withFilter(Expression.eq(Expression.get("dashed"), false)),
            )
            s.addLayer(
                LineLayer(WALK_LAYER, LINES_SOURCE).withProperties(
                    PropertyFactory.lineColor(Expression.get("color")),
                    PropertyFactory.lineWidth(Expression.get("width")),
                    PropertyFactory.lineDasharray(arrayOf(0.1f, 1.8f)),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                ).withFilter(Expression.eq(Expression.get("dashed"), true)),
            )
            val ink = if (dark) "#F3F2EF" else "#121214"
            val paper = if (dark) "#0B0B0D" else "#FFFFFF"
            s.addLayer(
                CircleLayer(STOPS_LAYER, STOPS_SOURCE).withProperties(
                    PropertyFactory.circleRadius(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(12, 2.5f), Expression.stop(16, 6f))),
                    PropertyFactory.circleColor(paper),
                    PropertyFactory.circleStrokeColor(ink),
                    PropertyFactory.circleStrokeWidth(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(12, 1f), Expression.stop(16, 2f))),
                ).withFilter(Expression.all(Expression.eq(Expression.get("station"), false), Expression.eq(Expression.get("selected"), false))),
            )
            s.addLayer(
                CircleLayer(STATIONS_LAYER, STOPS_SOURCE).withProperties(
                    PropertyFactory.circleRadius(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(11, 4f), Expression.stop(16, 9f))),
                    PropertyFactory.circleColor(ink),
                    PropertyFactory.circleStrokeColor(paper),
                    PropertyFactory.circleStrokeWidth(2f),
                ).withFilter(Expression.all(Expression.eq(Expression.get("station"), true), Expression.eq(Expression.get("selected"), false))),
            )
            s.addLayer(
                CircleLayer(SELECTED_LAYER, STOPS_SOURCE).withProperties(
                    PropertyFactory.circleRadius(10f),
                    PropertyFactory.circleColor(accent.hex()),
                    PropertyFactory.circleStrokeColor(paper),
                    PropertyFactory.circleStrokeWidth(3f),
                ).withFilter(Expression.eq(Expression.get("selected"), true)),
            )
            s.addLayer(
                CircleLayer(PINS_LAYER, PINS_SOURCE).withProperties(
                    PropertyFactory.circleRadius(8f),
                    PropertyFactory.circleColor(Expression.get("color")),
                    PropertyFactory.circleStrokeColor(paper),
                    PropertyFactory.circleStrokeWidth(3f),
                ),
            )
            s.addLayer(
                CircleLayer(USER_HALO, USER_SOURCE).withProperties(
                    PropertyFactory.circleRadius(18f),
                    PropertyFactory.circleColor("#2F7CF6"),
                    PropertyFactory.circleOpacity(0.18f),
                ),
            )
            s.addLayer(
                CircleLayer(USER_LAYER, USER_SOURCE).withProperties(
                    PropertyFactory.circleRadius(7f),
                    PropertyFactory.circleColor("#2F7CF6"),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                    PropertyFactory.circleStrokeWidth(2.5f),
                ),
            )
            style = s
        }
    }
    LaunchedEffect(style, stops) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(STOPS_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(stops.map { st ->
            Feature.fromGeometry(Point.fromLngLat(st.lon, st.lat), JsonObject().apply {
                addProperty("stop", st.stop)
                addProperty("station", st.station)
                addProperty("selected", st.selected)
            })
        }))
    }
    LaunchedEffect(style, lines) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(LINES_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(lines.filter { it.coords.size >= 4 }.map { l ->
            val pts = (0 until l.coords.size / 2).map { Point.fromLngLat(l.coords[2 * it + 1], l.coords[2 * it]) }
            Feature.fromGeometry(LineString.fromLngLats(pts), JsonObject().apply {
                addProperty("color", l.color.hex())
                addProperty("dashed", l.dashed)
                addProperty("width", l.width)
            })
        }))
    }
    LaunchedEffect(style, pins) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(PINS_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(pins.map { p ->
            Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat), JsonObject().apply { addProperty("color", p.color.hex()) })
        }))
    }
    LaunchedEffect(style, user) {
        val s = style ?: return@LaunchedEffect
        val u = user
        s.getSourceAs<GeoJsonSource>(USER_SOURCE)?.setGeoJson(
            FeatureCollection.fromFeatures(if (u == null) emptyList() else listOf(Feature.fromGeometry(Point.fromLngLat(u.second, u.first)))),
        )
    }
    LaunchedEffect(map, camera, bottomPaddingDp) {
        val m = map ?: return@LaunchedEffect
        val bottom = (bottomPaddingDp * density).toInt()
        when (camera) {
            is CameraTarget.Center -> {
                m.moveCamera(CameraUpdateFactory.paddingTo(0.0, 0.0, 0.0, bottom.toDouble()))
                m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(camera.lat, camera.lon), camera.zoom))
            }
            is CameraTarget.Fit -> {
                if (camera.coords.size < 2) return@LaunchedEffect
                val pad = (camera.paddingDp * density).toInt()
                if (camera.coords.size == 2) {
                    m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(camera.coords[0], camera.coords[1]), camera.maxZoom))
                } else {
                    val b = LatLngBounds.Builder()
                    for (i in 0 until camera.coords.size / 2) b.include(LatLng(camera.coords[2 * i], camera.coords[2 * i + 1]))
                    val bounds = runCatching { b.build() }.getOrNull() ?: return@LaunchedEffect
                    m.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, pad, pad, pad, pad + bottom))
                    if (m.cameraPosition.zoom > camera.maxZoom) m.moveCamera(CameraUpdateFactory.zoomTo(camera.maxZoom))
                }
            }
        }
    }
    AndroidView(factory = { mapView }, modifier = modifier.semantics { invisibleToUser() })
}

private fun Color.hex(): String = String.format("#%06X", 0xFFFFFF and toArgb())

private const val LINES_SOURCE = "tm-lines"
private const val STOPS_SOURCE = "tm-stops"
private const val PINS_SOURCE = "tm-pins"
private const val USER_SOURCE = "tm-user"
private const val LINES_CASING = "tm-lines-casing"
private const val LINES_LAYER = "tm-lines"
private const val WALK_LAYER = "tm-walk"
private const val STOPS_LAYER = "tm-stops"
private const val STATIONS_LAYER = "tm-stations"
private const val SELECTED_LAYER = "tm-selected"
private const val PINS_LAYER = "tm-pins"
private const val USER_HALO = "tm-user-halo"
private const val USER_LAYER = "tm-user"
