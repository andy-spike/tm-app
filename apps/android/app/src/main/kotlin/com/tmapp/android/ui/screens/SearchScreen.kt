package com.tmapp.android.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmapp.android.R
import com.tmapp.android.ui.Endpoint
import com.tmapp.android.ui.LocalTransit
import com.tmapp.android.ui.Navigator
import com.tmapp.android.ui.RouteKey
import com.tmapp.android.ui.SearchViewModel
import com.tmapp.android.ui.StopKey
import com.tmapp.android.ui.TripViewModel
import com.tmapp.android.ui.components.GroupLabel
import com.tmapp.android.ui.components.RowDivider
import com.tmapp.android.ui.components.groupItem
import com.tmapp.android.ui.theme.LocalExtra
import com.tmapp.android.ui.components.RouteBadge
import com.tmapp.android.ui.components.RouteBadges
import com.tmapp.android.ui.components.StopTile
import com.tmapp.android.ui.components.stopKind
import com.tmapp.android.ui.components.systemLabel
import com.tmapp.android.ui.theme.Spacing
import com.tmapp.data.RecentPlace
import com.tmapp.transit.Place
import com.tmapp.transit.SearchHit
import kotlinx.serialization.Serializable

@Serializable
enum class SearchMode { BROWSE, FROM, TO }

@Composable
fun SearchScreen(nav: Navigator, mode: SearchMode, trip: TripViewModel) {
    val vm: SearchViewModel = hiltViewModel()
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val recents by vm.recents.collectAsStateWithLifecycle()
    val data = LocalTransit.current
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        trip.onPermissionResult()
        if (mode == SearchMode.FROM) trip.setFrom(Endpoint.MyLocation) else trip.setTo(Endpoint.MyLocation)
        nav.back()
    }

    fun pickStop(stopIndex: Int) {
        val tt = data?.timetable ?: return
        val stop = tt.stops[stopIndex]
        vm.remember(RecentPlace("stop:${stop.id}", stop.name, stop.lat, stop.lon, stop.id, null, 0))
        keyboard?.hide()
        when (mode) {
            SearchMode.BROWSE -> nav.open(StopKey(stop.id))
            SearchMode.FROM -> { trip.setFrom(Endpoint.At(Place(stop.name, stop.lat, stop.lon, stop.index))); nav.back() }
            SearchMode.TO -> { trip.setTo(Endpoint.At(Place(stop.name, stop.lat, stop.lon, stop.index))); nav.back() }
        }
    }

    fun pickRoute(routeIndex: Int) {
        val route = data?.timetable?.routes?.get(routeIndex) ?: return
        vm.remember(RecentPlace("route:${route.id}", route.code, 0.0, 0.0, null, route.id, 0))
        keyboard?.hide()
        nav.open(RouteKey(route.id))
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        // The query bar sits on the red band, like the planner it feeds.
        Row(Modifier.fillMaxWidth().background(LocalExtra.current.header).statusBarsPadding().padding(horizontal = Spacing.sm, vertical = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { keyboard?.hide(); nav.back() }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = LocalExtra.current.onHeader)
            }
            TextField(
                value = query,
                onValueChange = { vm.query.value = it },
                modifier = Modifier.weight(1f).focusRequester(focus),
                placeholder = {
                    Text(stringResource(when (mode) { SearchMode.BROWSE -> R.string.search_hint; SearchMode.FROM -> R.string.search_from; SearchMode.TO -> R.string.search_to }))
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                shape = MaterialTheme.shapes.medium,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                ),
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Rounded.Close, stringResource(R.string.clear)) }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    when (val first = results?.firstOrNull { mode == SearchMode.BROWSE || it is SearchHit.StopHit }) {
                        is SearchHit.StopHit -> pickStop(first.stop)
                        is SearchHit.RouteHit -> pickRoute(first.route)
                        null -> keyboard?.hide()
                    }
                }),
            )
            Spacer(Modifier.width(Spacing.sm))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Spacing.xxl)) {
            if (mode != SearchMode.BROWSE && query.isBlank()) {
                item("me") {
                    PlainRow(Icons.Rounded.MyLocation, stringResource(R.string.my_location), null, first = true, last = true, top = Spacing.lg) {
                        if (trip.state.value.from == Endpoint.MyLocation && mode == SearchMode.TO) trip.setFrom(null)
                        permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                }
            }
            val list = results
            if (list == null) {
                val shown = recents.filter { mode == SearchMode.BROWSE || it.stopId != null }
                if (shown.isNotEmpty()) {
                    item("recent-title") {
                        Row(Modifier.fillMaxWidth().padding(end = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                            GroupLabel(stringResource(R.string.recent), Modifier.weight(1f))
                            TextButton(onClick = vm::clearRecents, modifier = Modifier.padding(top = Spacing.md)) { Text(stringResource(R.string.clear_recents)) }
                        }
                    }
                    items(shown.size, key = { shown[it].key }) { i ->
                        val r = shown[i]
                        val tt = data?.timetable
                        val route = r.routeId?.let { tt?.routeById(it) }
                        val stop = r.stopId?.let { tt?.stopById(it) }
                        val first = i == 0
                        val last = i == shown.lastIndex
                        when {
                            route != null -> RouteRow(route.index, first, last) { pickRoute(route.index) }
                            stop != null -> PlainRow(Icons.Rounded.History, stop.name, stopKind(stop), first, last) { pickStop(stop.index) }
                        }
                    }
                } else {
                    item("tip") {
                        Text(stringResource(R.string.search_tip), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Spacing.gutter))
                    }
                }
            } else {
                val shown = if (mode == SearchMode.BROWSE) list else list.filterIsInstance<SearchHit.StopHit>()
                if (shown.isEmpty()) {
                    item("none") {
                        Text(stringResource(R.string.no_results, query), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(Spacing.gutter))
                    }
                }
                val tt = data?.timetable
                val repeated = shown.filterIsInstance<SearchHit.StopHit>().groupBy { tt?.stops?.get(it.stop)?.name }.filterValues { it.size > 1 }.keys
                if (shown.isNotEmpty()) item("results-gap") { Spacer(Modifier.height(Spacing.lg)) }
                items(shown.size, key = { when (val h = shown[it]) { is SearchHit.StopHit -> "s${h.stop}"; is SearchHit.RouteHit -> "r${h.route}" } }) { i ->
                    val first = i == 0
                    val last = i == shown.lastIndex
                    when (val hit = shown[i]) {
                        is SearchHit.StopHit -> StopResult(hit.stop, showCode = tt?.stops?.get(hit.stop)?.name in repeated, first = first, last = last) { pickStop(hit.stop) }
                        is SearchHit.RouteHit -> RouteRow(hit.route, first, last) { pickRoute(hit.route) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlainRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String?, first: Boolean, last: Boolean, top: androidx.compose.ui.unit.Dp = 0.dp, onClick: () -> Unit) {
    Column(Modifier.padding(top = top).groupItem(first, last, MaterialTheme.colorScheme.surface)) {
    if (!first) RowDivider(start = 64.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    }
}

@Composable
private fun StopResult(index: Int, showCode: Boolean = false, first: Boolean, last: Boolean, onClick: () -> Unit) {
    val data = LocalTransit.current ?: return
    val stop = data.timetable.stops[index]
    val routes = remember(index) { data.schedule.routesAt(index).map { data.timetable.routes[it] } }
    Column(Modifier.groupItem(first, last, MaterialTheme.colorScheme.surface)) {
        if (!first) RowDivider(start = 64.dp)
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        ) {
            StopTile(stop)
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(stop.name, style = MaterialTheme.typography.titleMedium)
                val code = stop.code?.removeSuffix("_TM")?.takeIf { showCode && !stop.isStation }
                Text(listOfNotNull(stopKind(stop), code).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (routes.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    RouteBadges(routes, max = 8)
                }
            }
        }
    }
}

@Composable
fun RouteRow(index: Int, first: Boolean, last: Boolean, onClick: () -> Unit) {
    val data = LocalTransit.current ?: return
    val route = data.timetable.routes[index]
    Column(Modifier.groupItem(first, last, MaterialTheme.colorScheme.surface)) {
        if (!first) RowDivider()
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(84.dp)) { RouteBadge(route, large = true) }
            Column(Modifier.weight(1f)) {
                Text(route.longName.ifBlank { route.code }, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(systemLabel(route.system), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
