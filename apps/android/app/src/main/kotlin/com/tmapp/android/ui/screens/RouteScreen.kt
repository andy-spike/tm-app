package com.tmapp.android.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmapp.android.R
import com.tmapp.android.ui.LocalTransit
import com.tmapp.android.ui.Navigator
import com.tmapp.android.ui.RouteViewModel
import com.tmapp.android.ui.StopKey
import com.tmapp.android.ui.components.CapFontScale
import com.tmapp.android.ui.components.Eyebrow
import com.tmapp.android.ui.components.RouteBadge
import com.tmapp.android.ui.components.ScheduledTag
import com.tmapp.android.ui.components.SkeletonRows
import com.tmapp.android.ui.components.clock
import com.tmapp.android.ui.components.systemLabel
import com.tmapp.android.ui.map.CameraTarget
import com.tmapp.android.ui.map.MapLine
import com.tmapp.android.ui.map.MapStop
import com.tmapp.android.ui.map.TransitMap
import com.tmapp.android.ui.theme.LocalExtra
import com.tmapp.android.ui.theme.RouteColors
import com.tmapp.android.ui.theme.Spacing

@Composable
fun RouteScreen(nav: Navigator, routeId: String) {
    val vm = hiltViewModel<RouteViewModel, RouteViewModel.Factory>(key = routeId) { it.create(routeId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val data = LocalTransit.current
    val view = LocalView.current
    val route = data?.timetable?.routeById(routeId)
    val direction = state.directions?.getOrNull(state.selected)
    val color = route?.let { RouteColors.background(it) } ?: MaterialTheme.colorScheme.primary
    val savedMsg = route?.let { stringResource(if (state.saved) R.string.announce_unsaved else R.string.announce_saved, it.code) }
    DetailScreen(
        onBack = nav::back,
        map = {
            if (data != null && direction != null) {
                val tt = data.timetable
                val line = remember(direction.pattern) {
                    data.shapes.pattern(direction.pattern)?.coords
                        ?: DoubleArray(direction.stops.size * 2) { k -> tt.stops[direction.stops[k / 2]].let { if (k % 2 == 0) it.lat else it.lon } }
                }
                val stops = remember(direction.pattern) { direction.stops.map { MapStop(it, tt.stops[it].lat, tt.stops[it].lon, false) } }
                TransitMap(
                    archive = data.mapArchive,
                    dark = LocalExtra.current.isDark,
                    camera = remember(direction.pattern) { CameraTarget.Fit(line, paddingDp = 40) },
                    modifier = Modifier.fillMaxSize(),
                    lines = remember(line, color) { listOf(MapLine(line, color, width = 5f)) },
                    stops = stops,
                    interactive = false,
                    bottomPaddingDp = 28,
                )
            }
        },
        actions = {
            if (route != null) {
                RoundButton(
                    if (state.saved) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                    stringResource(if (state.saved) R.string.unsave_route else R.string.save_route),
                    onClick = { vm.toggleSaved(); savedMsg?.let { view.announceForAccessibility(it) } },
                    selected = state.saved,
                )
            }
        },
    ) {
        if (data == null || route == null) {
            item("loading") { SkeletonRows(4) }
            return@DetailScreen
        }
        val tt = data.timetable
        item("title") {
            Column(Modifier.gutter().semantics(mergeDescendants = true) { heading() }) {
                RouteBadge(route, large = true)
                Spacer(Modifier.height(Spacing.md))
                CapFontScale { Text(direction?.destination ?: route.longName, style = MaterialTheme.typography.headlineLarge) }
                Text(systemLabel(route.system) + (route.longName.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val directions = state.directions
        if (directions == null) {
            item("skeleton") { SkeletonRows(3, Modifier.padding(top = Spacing.xl)) }
            return@DetailScreen
        }
        if (directions.size > 1) {
            item("directions") {
                SingleChoiceSegmentedButtonRow(Modifier.gutter().padding(top = Spacing.lg).fillMaxWidth()) {
                    directions.take(3).forEachIndexed { i, d ->
                        SegmentedButton(
                            selected = i == state.selected,
                            onClick = { vm.select(i) },
                            shape = SegmentedButtonDefaults.itemShape(i, minOf(3, directions.size)),
                            modifier = Modifier.heightIn(min = 48.dp),
                            label = { Text(stringResource(R.string.toward, d.destination), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        }
        if (direction == null) return@DetailScreen
        item("tiles") {
            Row(Modifier.gutter().padding(top = Spacing.lg).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                val notToday = stringResource(R.string.not_today)
                Tile(stringResource(R.string.first_bus), direction.first?.let { clock(it) } ?: "–", Modifier.weight(1f))
                Tile(stringResource(R.string.last_bus), direction.last?.let { clock(it) } ?: "–", Modifier.weight(1f))
                Tile(stringResource(R.string.trips_today), if (direction.tripsToday > 0) direction.tripsToday.toString() else notToday, Modifier.weight(1f))
            }
        }
        item("scheduled") { ScheduledTag(Modifier.gutter().padding(top = Spacing.md)) }
        item("stops-title") {
            Text(
                pluralStringResource(R.plurals.stop_count, direction.stops.size, direction.stops.size),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.gutter().padding(top = Spacing.xl, bottom = Spacing.sm).semantics { heading() },
            )
        }
        val n = direction.stops.size
        items(n, key = { "s$it" }) { i ->
            val stop = tt.stops[direction.stops[i]]
            val station = tt.stops[tt.rootOf(stop.index)]
            TimelineRow(
                title = station.name,
                subtitle = stop.name.takeIf { it != station.name },
                color = color,
                first = i == 0,
                last = i == n - 1,
                onClick = { nav.open(StopKey(station.id)) },
            )
        }
    }
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier) {
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(16.dp)).border(1.dp, LocalExtra.current.hairline, RoundedCornerShape(16.dp))
            .padding(Spacing.md).semantics(mergeDescendants = true) {},
    ) {
        Eyebrow(label)
        Spacer(Modifier.height(6.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 2)
    }
}

/** A stop on a vertical line; terminals are larger rings. */
@Composable
fun TimelineRow(
    title: String,
    subtitle: String?,
    color: Color,
    first: Boolean,
    last: Boolean,
    onClick: (() -> Unit)?,
    trailing: String? = null,
    emphasis: Boolean = first || last,
    dashedAbove: Boolean = false,
    dashedBelow: Boolean = false,
) {
    val bg = MaterialTheme.colorScheme.background
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = Spacing.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.width(24.dp).fillMaxHeight()) {
            val x = size.width / 2
            val cy = size.height / 2
            val w = 4.dp.toPx()
            val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 5.dp.toPx()))
            if (!first) drawLine(color, Offset(x, 0f), Offset(x, cy), w, pathEffect = if (dashedAbove) dash else null)
            if (!last) drawLine(color, Offset(x, cy), Offset(x, size.height), w, pathEffect = if (dashedBelow) dash else null)
            val r = if (emphasis) 7.dp.toPx() else 4.5.dp.toPx()
            drawCircle(color, r, Offset(x, cy))
            drawCircle(bg, r - 2.5.dp.toPx(), Offset(x, cy))
        }
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f).padding(vertical = if (emphasis) 14.dp else 11.dp)) {
            Text(title, style = if (emphasis) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
