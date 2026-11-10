package com.tmapp.android.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmapp.android.R
import com.tmapp.android.ui.DEFAULT_CENTER
import com.tmapp.android.ui.HomeViewModel
import com.tmapp.android.ui.LocalTransit
import com.tmapp.android.ui.Navigator
import com.tmapp.android.ui.SavedStopCard
import com.tmapp.android.ui.SearchKey
import com.tmapp.android.ui.StopKey
import com.tmapp.android.ui.components.CapFontScale
import com.tmapp.android.ui.components.Eyebrow
import com.tmapp.android.ui.components.Hairline
import com.tmapp.android.ui.components.RouteBadge
import com.tmapp.android.ui.components.RouteBadges
import com.tmapp.android.ui.components.ScheduledTag
import com.tmapp.android.ui.components.SectionTitle
import com.tmapp.android.ui.components.SkeletonRows
import com.tmapp.android.ui.components.StopTile
import com.tmapp.android.ui.components.rememberNow
import com.tmapp.android.ui.components.rememberTimeFormat
import com.tmapp.android.ui.components.stopKind
import com.tmapp.android.ui.map.CameraTarget
import com.tmapp.android.ui.map.MapStop
import com.tmapp.android.ui.map.TransitMap
import com.tmapp.android.ui.theme.LocalExtra
import com.tmapp.android.ui.theme.Spacing
import com.tmapp.data.NearbyStop
import com.tmapp.transit.BOGOTA
import com.tmapp.transit.Geo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: Navigator) {
    val vm: HomeViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val mapStops by vm.mapStops.collectAsStateWithLifecycle()
    val data = LocalTransit.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.onPermissionResult() }
    val requestLocation = {
        permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    var camera by remember {
        mutableStateOf<CameraTarget>(state.location?.let { CameraTarget.Center(it.lat, it.lon, 15.6) } ?: CameraTarget.Center(DEFAULT_CENTER.lat, DEFAULT_CENTER.lon, 15.0))
    }
    var centeredOnUser by remember { mutableStateOf(state.location != null) }
    LaunchedEffect(state.location) {
        val loc = state.location
        if (loc != null && !centeredOnUser) {
            centeredOnUser = true
            camera = CameraTarget.Center(loc.lat, loc.lon, 15.6)
        }
    }
    val sheet = rememberBottomSheetScaffoldState(rememberStandardBottomSheetState(skipHiddenState = true))
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Larger text needs more of the sheet visible so the search field stays in view.
        val scale = androidx.compose.ui.platform.LocalDensity.current.fontScale
        val peek = maxHeight * (if (scale > 1.3f) 0.72f else 0.56f)
        BottomSheetScaffold(
            scaffoldState = sheet,
            sheetPeekHeight = peek,
            sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            sheetContainerColor = MaterialTheme.colorScheme.background,
            sheetShadowElevation = 12.dp,
            sheetDragHandle = { BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.outline) },
            sheetContent = {
                HomeSheet(
                    state = state,
                    onSearch = { nav.open(SearchKey(SearchMode.BROWSE)) },
                    onStop = { nav.open(StopKey(it)) },
                    onAllowLocation = requestLocation,
                )
            },
        ) {
            Box(Modifier.fillMaxSize()) {
                if (data != null) {
                    val stops = remember(mapStops, state.nearby) {
                        (mapStops + state.nearby.orEmpty()).distinctBy { it.stop }.map {
                            val s = data.timetable.stops[it.stop]
                            MapStop(it.stop, s.lat, s.lon, s.isStation)
                        }
                    }
                    TransitMap(
                        archive = data.mapArchive,
                        dark = LocalExtra.current.isDark,
                        camera = camera,
                        modifier = Modifier.fillMaxSize(),
                        stops = stops,
                        user = state.location?.let { it.lat to it.lon },
                        accent = MaterialTheme.colorScheme.primary,
                        bottomPaddingDp = peek.value.toInt(),
                        onStopClick = { nav.open(StopKey(data.timetable.stops[it].id)) },
                        onCameraIdle = vm::onMapIdle,
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer))
                }
                // Data honesty: every screen with times says they are scheduled.
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.94f),
                    shadowElevation = 2.dp,
                    modifier = Modifier.statusBarsPadding().padding(Spacing.md),
                ) {
                    CapFontScale(1.3f) { Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(LocalExtra.current.scheduled))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.scheduled) + " · " + stringResource(R.string.offline_ready),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    } }
                }
                SmallFloatingActionButton(
                    onClick = {
                        val loc = state.location
                        if (loc == null) requestLocation() else camera = CameraTarget.Center(loc.lat, loc.lon, 15.6)
                    },
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = Spacing.lg, bottom = peek + Spacing.md),
                ) {
                    Icon(Icons.Rounded.MyLocation, contentDescription = stringResource(R.string.center_map))
                }
            }
        }
    }
}

@Composable
private fun HomeSheet(
    state: com.tmapp.android.ui.HomeState,
    onSearch: () -> Unit,
    onStop: (String) -> Unit,
    onAllowLocation: () -> Unit,
) {
    val data = LocalTransit.current
    val now = rememberNow()
    val hour = remember(now) { now.atZone(BOGOTA).hour }
    LazyColumn(
        Modifier.fillMaxWidth().heightIn(min = 200.dp),
        contentPadding = PaddingValues(bottom = Spacing.xxl),
    ) {
        item("head") {
            Column(Modifier.padding(horizontal = Spacing.gutter)) {
                Eyebrow(stringResource(when (hour) { in 5..11 -> R.string.greeting_morning; in 12..18 -> R.string.greeting_afternoon; else -> R.string.greeting_evening }))
                Spacer(Modifier.height(6.dp))
                CapFontScale { Text(stringResource(R.string.where_to), style = MaterialTheme.typography.displaySmall) }
                Spacer(Modifier.height(Spacing.lg))
                SearchPill(onSearch)
            }
        }
        if (!state.hasPermission) {
            item("permission") { LocationCard(onAllowLocation) }
        }
        if (state.saved.isNotEmpty() && data != null) {
            item("saved") {
                Column(Modifier.padding(top = Spacing.xl)) {
                    SectionTitle(stringResource(R.string.saved_title), Modifier.padding(horizontal = Spacing.gutter))
                    Spacer(Modifier.height(Spacing.md))
                    LazyRow(contentPadding = PaddingValues(horizontal = Spacing.gutter), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        items(state.saved, key = { it.stop }) { card -> SavedCard(card, now) { onStop(data.timetable.stops[card.stop].id) } }
                    }
                }
            }
        }
        item("nearby-title") {
            val nearby = state.nearby
            SectionTitle(
                stringResource(if (state.location != null) R.string.nearby_title else R.string.nearby_center_title),
                Modifier.padding(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.xl, bottom = Spacing.sm),
                subtitle = nearby?.let { pluralStringResource(R.plurals.nearby_count, it.size, it.size, 700) },
            )
        }
        val nearby = state.nearby
        if (nearby == null || data == null) {
            item("skeleton") { SkeletonRows(5, Modifier.padding(top = Spacing.md)) }
        } else if (nearby.isEmpty()) {
            item("empty") {
                Text(stringResource(R.string.nearby_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Spacing.gutter))
            }
        } else {
            items(nearby, key = { it.stop }) { n -> NearbyRow(n) { onStop(data.timetable.stops[n.stop].id) } }
        }
        item("bottom") { Spacer(Modifier.navigationBarsPadding()) }
    }
}

@Composable
fun SearchPill(onClick: () -> Unit, label: String = stringResource(R.string.search_hint)) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp).clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(horizontal = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(Spacing.md))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LocationCard(onAllow: () -> Unit) {
    Column(
        Modifier.padding(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.xl).fillMaxWidth()
            .clip(RoundedCornerShape(20.dp)).border(1.dp, LocalExtra.current.hairline, RoundedCornerShape(20.dp))
            .padding(Spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.LocationOn, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(Spacing.sm))
            Text(stringResource(R.string.location_rationale_title), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(stringResource(R.string.location_rationale_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Spacing.md))
        FilledTonalButton(onClick = onAllow, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.location_allow)) }
    }
}

@Composable
private fun SavedCard(card: SavedStopCard, now: java.time.Instant, onClick: () -> Unit) {
    val data = LocalTransit.current ?: return
    val stop = data.timetable.stops[card.stop]
    Column(
        Modifier.width(264.dp).clip(RoundedCornerShape(20.dp)).border(1.dp, LocalExtra.current.hairline, RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .clickable(role = Role.Button, onClick = onClick).padding(Spacing.lg),
    ) {
        Text(stop.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(Spacing.md))
        if (card.departures.isEmpty()) {
            Text(stringResource(R.string.no_departures), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        card.departures.forEach { d ->
            val route = data.timetable.routes[d.route]
            val spoken = stringResource(R.string.departure_cd, com.tmapp.transit.RouteCodes.sayable(route.code), d.destination, rememberTimeFormat().relative(now, d.time))
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clearAndSetSemantics { contentDescription = spoken }, verticalAlignment = Alignment.CenterVertically) {
                RouteBadge(route)
                Spacer(Modifier.width(Spacing.sm))
                Text(d.destination, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(rememberTimeFormat().compact(now, d.time), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
fun NearbyRow(n: NearbyStop, onClick: () -> Unit) {
    val data = LocalTransit.current ?: return
    val stop = data.timetable.stops[n.stop]
    val walk = (Geo.walkSeconds(n.meters) + 59) / 60
    val routes = n.routes.map { data.timetable.routes[it] }
    val label = "${stop.name}, ${stopKind(stop)}, ${stringResource(R.string.meters_walk, n.meters.toInt(), walk)}"
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
                .clearAndSetSemantics { contentDescription = label }
                .padding(horizontal = Spacing.gutter, vertical = Spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            StopTile(stop)
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(stop.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${stopKind(stop)} · ${stringResource(R.string.meters_walk, n.meters.toInt(), walk)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.sm))
                RouteBadges(routes, max = 7)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp).size(22.dp))
        }
        Hairline(Modifier.padding(start = Spacing.gutter + 52.dp).widthIn(max = 2000.dp))
    }
}
