package com.tmapp.android.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material.icons.rounded.TripOrigin
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmapp.android.R
import com.tmapp.android.ui.LineGroup
import com.tmapp.android.ui.LocalTransit
import com.tmapp.android.ui.Navigator
import com.tmapp.android.ui.RouteKey
import com.tmapp.android.ui.StopViewModel
import com.tmapp.android.ui.TripKey
import com.tmapp.android.ui.TripViewModel
import com.tmapp.android.ui.components.CapFontScale
import com.tmapp.android.ui.components.Eyebrow
import com.tmapp.android.ui.components.Hairline
import com.tmapp.android.ui.components.Minutes
import com.tmapp.android.ui.components.RouteBadge
import com.tmapp.android.ui.components.ScheduledTag
import com.tmapp.android.ui.components.SectionTitle
import com.tmapp.android.ui.components.SkeletonRows
import com.tmapp.android.ui.components.StopTile
import com.tmapp.android.ui.components.clock
import com.tmapp.android.ui.components.relativeText
import com.tmapp.android.ui.components.rememberNow
import com.tmapp.android.ui.components.rememberTimeFormat
import com.tmapp.android.ui.components.stopKind
import com.tmapp.android.ui.map.CameraTarget
import com.tmapp.android.ui.map.MapStop
import com.tmapp.android.ui.map.TransitMap
import com.tmapp.android.ui.theme.LocalExtra
import com.tmapp.android.ui.theme.Spacing
import com.tmapp.transit.Departure
import com.tmapp.transit.Place
import com.tmapp.transit.RouteCodes

@Composable
fun StopScreen(nav: Navigator, stopId: String, trip: TripViewModel) {
    val vm = hiltViewModel<StopViewModel, StopViewModel.Factory>(key = stopId) { it.create(stopId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val data = LocalTransit.current
    val view = LocalView.current
    val stop = data?.timetable?.stopById(stopId)
    val now = rememberNow()
    val savedMsg = stop?.let { stringResource(if (state.saved) R.string.announce_unsaved else R.string.announce_saved, it.name) }
    DetailScreen(
        onBack = nav::back,
        map = {
            if (data != null && stop != null) {
                val pins = remember(stop) {
                    data.timetable.children[data.timetable.rootOf(stop.index)].map { s ->
                        val st = data.timetable.stops[s]
                        MapStop(s, st.lat, st.lon, st.isStation, selected = s == stop.index)
                    }
                }
                TransitMap(
                    archive = data.mapArchive,
                    dark = LocalExtra.current.isDark,
                    camera = remember(stop) { CameraTarget.Center(stop.lat, stop.lon, 16.2) },
                    modifier = Modifier.fillMaxSize(),
                    stops = pins,
                    accent = MaterialTheme.colorScheme.primary,
                    interactive = false,
                    bottomPaddingDp = 28,
                )
            }
        },
        actions = {
            if (stop != null) {
                RoundButton(
                    if (state.saved) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                    stringResource(if (state.saved) R.string.unsave_stop else R.string.save_stop),
                    onClick = {
                        vm.toggleSaved()
                        savedMsg?.let { view.announceForAccessibility(it) }
                    },
                    selected = state.saved,
                )
            }
        },
    ) {
        if (data == null || stop == null) {
            item("loading") { SkeletonRows(4) }
            return@DetailScreen
        }
        item("title") {
            Row(Modifier.gutter(), verticalAlignment = Alignment.CenterVertically) {
                StopTile(stop, size = 48.dp)
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.semantics(mergeDescendants = true) { heading() }) {
                    CapFontScale { Text(stop.name, style = MaterialTheme.typography.headlineMedium) }
                    val code = stop.code?.takeIf { !stop.isStation && !it.endsWith("_TM") && !it.startsWith("PA") }
                    Text(listOfNotNull(stopKind(stop), code).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item("actions") {
            val place = Place(stop.name, stop.lat, stop.lon, stop.index)
            Row(Modifier.gutter().padding(top = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Button(onClick = { trip.planWithStop(place, asOrigin = false); trip.rememberStop(stop.id, stop.name, stop.lat, stop.lon); nav.tab(TripKey) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.Directions, null)
                    Spacer(Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.directions_to_here))
                }
                OutlinedButton(onClick = { trip.planWithStop(place, asOrigin = true); trip.rememberStop(stop.id, stop.name, stop.lat, stop.lon); nav.tab(TripKey) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.TripOrigin, null)
                    Spacer(Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.directions_from_here))
                }
            }
        }
        val routes = state.routes.map { data.timetable.routes[it] }
        if (routes.size > 1) {
            item("filters") {
                LazyRow(contentPadding = PaddingValues(horizontal = Spacing.gutter), horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(top = Spacing.xl)) {
                    item {
                        FilterChip(
                            selected = state.filter == null,
                            onClick = { vm.setFilter(null) },
                            label = { Text(stringResource(R.string.all_routes)) },
                            modifier = Modifier.heightIn(min = 40.dp),
                            colors = chipColors(),
                        )
                    }
                    items(routes, key = { it.index }) { r ->
                        FilterChip(
                            selected = state.filter == r.index,
                            onClick = { vm.setFilter(if (state.filter == r.index) null else r.index) },
                            label = { RouteBadge(r) },
                            modifier = Modifier.heightIn(min = 40.dp),
                            colors = chipColors(),
                        )
                    }
                }
            }
        }
        val departures = state.departures
        if (departures == null || state.stop < 0) {
            item("skeleton") { SkeletonRows(3, Modifier.padding(top = Spacing.xl)) }
            return@DetailScreen
        }
        val filterRoute = state.filter
        val shown = if (filterRoute == null) departures else departures.filter { it.route == filterRoute }
        if (shown.isEmpty()) {
            item("none") {
                Column(Modifier.gutter().padding(top = Spacing.xl)) {
                    Text(stringResource(if (filterRoute == null) R.string.no_departures else R.string.no_departures_route), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(Spacing.sm))
                    ScheduledTag()
                }
            }
        } else {
            item("next") { NextBus(shown.first(), now) { nav.open(RouteKey(data.timetable.routes[shown.first().route].id)) } }
            val groups = StopViewModel.group(shown.drop(1))
            if (groups.isNotEmpty()) item("later-title") { SectionTitle(stringResource(R.string.later), Modifier.gutter().padding(top = Spacing.xl, bottom = Spacing.sm)) }
            items(groups.size, key = { groups[it].key }) { i ->
                LineRow(groups[i], now) { nav.open(RouteKey(data.timetable.routes[groups[i].route].id)) }
            }
        }
        item("honesty") {
            Text(stringResource(R.string.scheduled_detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.gutter().padding(top = Spacing.xl))
        }
    }
}

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.secondary,
    selectedLabelColor = MaterialTheme.colorScheme.onSecondary,
)

@Composable
private fun NextBus(d: Departure, now: java.time.Instant, onClick: () -> Unit) {
    val data = LocalTransit.current ?: return
    val route = data.timetable.routes[d.route]
    val spoken = stringResource(R.string.departure_cd, RouteCodes.sayable(route.code), d.destination, relativeText(now, d.time))
    Column(
        Modifier.gutter().padding(top = Spacing.xl).fillMaxWidth()
            .clip(RoundedCornerShape(24.dp)).border(1.dp, LocalExtra.current.hairline, RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = spoken }
            .padding(Spacing.xl),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow(stringResource(R.string.next_bus), Modifier.weight(1f))
            Text(clock(d.time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(Spacing.md))
        RouteBadge(route, large = true)
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f).padding(top = Spacing.md, end = Spacing.md)) {
                Text(stringResource(R.string.toward, d.destination), style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(Spacing.md))
                ScheduledTag(estimated = d.estimated)
            }
            CapFontScale { Minutes(now, d.time, large = true) }
        }
    }
}

@Composable
private fun LineRow(group: LineGroup, now: java.time.Instant, onClick: () -> Unit) {
    val data = LocalTransit.current ?: return
    val route = data.timetable.routes[group.route]
    val first = group.departures.first()
    val next = group.departures.drop(1).take(2)
    val tf = rememberTimeFormat()
    val spoken = stringResource(R.string.departure_cd, RouteCodes.sayable(route.code), group.destination, tf.relative(now, first.time)) +
        next.joinToString("") { ", " + tf.relative(now, it.time) }
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
                .clearAndSetSemantics { contentDescription = spoken }
                .padding(horizontal = Spacing.gutter, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                RouteBadge(route, outlined = true)
                Spacer(Modifier.height(6.dp))
                Text(group.destination, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (next.isNotEmpty()) {
                    Text(next.joinToString(" · ") { tf.clock(it.time) }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(Spacing.md))
            Minutes(now, first.time, large = false)
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Hairline(Modifier.padding(start = Spacing.gutter))
    }
}

