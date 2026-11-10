package com.tmapp.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmapp.android.R
import com.tmapp.android.ui.LocalTransit
import com.tmapp.android.ui.Navigator
import com.tmapp.android.ui.RouteKey
import com.tmapp.android.ui.StopKey
import com.tmapp.android.ui.TripViewModel
import com.tmapp.android.ui.components.CapFontScale
import com.tmapp.android.ui.components.Eyebrow
import com.tmapp.android.ui.components.RouteBadge
import com.tmapp.android.ui.components.ScheduledTag
import com.tmapp.android.ui.components.durationText
import com.tmapp.android.ui.components.rememberTimeFormat
import com.tmapp.android.ui.components.transfersText
import com.tmapp.android.ui.map.CameraTarget
import com.tmapp.android.ui.map.MapLine
import com.tmapp.android.ui.map.MapPin
import com.tmapp.android.ui.map.MapStop
import com.tmapp.android.ui.map.TransitMap
import com.tmapp.android.ui.theme.LocalExtra
import com.tmapp.android.ui.theme.RouteColors
import com.tmapp.android.ui.theme.Spacing
import com.tmapp.transit.TransitLeg
import com.tmapp.transit.WalkLeg

@Composable
fun ItineraryScreen(nav: Navigator, trip: TripViewModel, index: Int) {
    // Observe so the screen survives replans gracefully (it closes if its option disappears).
    val state by trip.state.collectAsStateWithLifecycle()
    val itinerary = remember(state.plan, index) { trip.itinerary(index) }
    val data = LocalTransit.current
    val tf = rememberTimeFormat()
    val expanded = remember { mutableStateMapOf<Int, Boolean>() }
    val walkColor = LocalExtra.current.walk
    val accent = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurface
    DetailScreen(
        onBack = nav::back,
        mapHeight = 300.dp,
        map = {
            if (data != null && itinerary != null) {
                val tt = data.timetable
                val lines = remember(itinerary) {
                    itinerary.legs.map { leg ->
                        when (leg) {
                            is WalkLeg -> MapLine(doubleArrayOf(leg.from.lat, leg.from.lon, leg.to.lat, leg.to.lon), walkColor, dashed = true, width = 4f)
                            is TransitLeg -> MapLine(data.shapes.leg(tt, leg.pattern, leg.boardPosition, leg.alightPosition).coords, RouteColors.background(tt.routes[leg.route]), width = 6f)
                        }
                    }
                }
                val stops = remember(itinerary) {
                    itinerary.rides.flatMap { listOf(it.stops.first().stop, it.stops.last().stop) }.distinct().map {
                        MapStop(it, tt.stops[it].lat, tt.stops[it].lon, station = false)
                    }
                }
                val start = itinerary.legs.first().let { if (it is WalkLeg) it.from.lat to it.from.lon else tt.stops[(it as TransitLeg).stops.first().stop].let { s -> s.lat to s.lon } }
                val end = itinerary.legs.last().let { if (it is WalkLeg) it.to.lat to it.to.lon else tt.stops[(it as TransitLeg).stops.last().stop].let { s -> s.lat to s.lon } }
                val all = remember(lines) { lines.flatMap { it.coords.asList() }.toDoubleArray() }
                TransitMap(
                    archive = data.mapArchive,
                    dark = LocalExtra.current.isDark,
                    camera = remember(itinerary) { CameraTarget.Fit(all, paddingDp = 44) },
                    modifier = Modifier.fillMaxSize(),
                    lines = lines,
                    stops = stops,
                    pins = remember(itinerary) { listOf(MapPin(start.first, start.second, ink), MapPin(end.first, end.second, accent)) },
                    interactive = false,
                    bottomPaddingDp = 28,
                )
            }
        },
    ) {
        if (data == null || itinerary == null) return@DetailScreen
        val tt = data.timetable
        item("summary") {
            Column(Modifier.gutter().semantics(mergeDescendants = true) { heading() }) {
                Eyebrow(stringResource(R.string.itinerary_title))
                Spacer(Modifier.height(6.dp))
                CapFontScale { Row(verticalAlignment = Alignment.Bottom) {
                    Text(stringResource(R.string.time_range, tf.clock(itinerary.departure), tf.clock(itinerary.arrival)), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                    Text(durationText(itinerary.duration), style = MaterialTheme.typography.headlineSmall)
                } }
                Text(
                    if (itinerary.rides.isEmpty()) stringResource(R.string.walk_only) else transfersText(itinerary.transfers),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.sm))
                ScheduledTag(estimated = itinerary.rides.any { it.estimated })
            }
        }
        item("gap") { Spacer(Modifier.height(Spacing.lg)) }
        val legs = itinerary.legs
        legs.forEachIndexed { li, leg ->
            when (leg) {
                is WalkLeg -> item("leg$li") {
                    val minutes = leg.duration.toMinutes().toInt().coerceAtLeast(1)
                    val target = if (li == legs.lastIndex) stringResource(R.string.destination) else leg.to.name
                    TimelineRow(
                        title = stringResource(R.string.walk_to, target),
                        subtitle = stringResource(R.string.walk_detail, leg.meters, minutes),
                        color = walkColor,
                        first = li == 0,
                        last = false,
                        onClick = null,
                        trailing = tf.clock(leg.departure),
                        emphasis = false,
                        dashedAbove = true,
                        dashedBelow = true,
                    )
                }
                is TransitLeg -> {
                    val route = tt.routes[leg.route]
                    val color = RouteColors.background(route)
                    val board = tt.stops[leg.stops.first().stop]
                    val alight = tt.stops[leg.stops.last().stop]
                    item("board$li") {
                        Column {
                            TimelineRow(
                                title = board.name,
                                subtitle = null,
                                color = color,
                                first = li == 0,
                                last = false,
                                onClick = { nav.open(StopKey(tt.stops[tt.rootOf(board.index)].id)) },
                                trailing = tf.clock(leg.departure),
                                emphasis = true,
                                dashedAbove = li > 0 && legs[li - 1] is WalkLeg,
                            )
                            Row(Modifier.gutter().padding(start = 36.dp, bottom = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                                RouteBadge(route)
                                Spacer(Modifier.width(Spacing.sm))
                                Text(stringResource(R.string.board, route.code, leg.destination), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            }
                            val stopsBetween = leg.stops.size - 2
                            Row(Modifier.gutter().padding(start = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    pluralStringResource(R.plurals.ride_stops, leg.stops.size - 1, leg.stops.size - 1, durationText(leg.duration)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                                )
                                if (stopsBetween > 0) {
                                    TextButton(onClick = { expanded[li] = !(expanded[li] ?: false) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                        Text(stringResource(if (expanded[li] == true) R.string.hide_stops else R.string.show_stops))
                                    }
                                }
                                TextButton(onClick = { nav.open(RouteKey(route.id)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.view_route)) }
                            }
                        }
                    }
                    if (expanded[li] == true) {
                        val middle = leg.stops.subList(1, leg.stops.size - 1)
                        items(middle.size, key = { "mid$li-$it" }) { k ->
                            val st = middle[k]
                            TimelineRow(tt.stops[st.stop].name, null, color, first = false, last = false, onClick = null, trailing = tf.clock(st.departure), emphasis = false)
                        }
                    }
                    item("alight$li") {
                        TimelineRow(
                            title = stringResource(R.string.alight, alight.name),
                            subtitle = null,
                            color = color,
                            first = false,
                            last = li == legs.lastIndex,
                            onClick = { nav.open(StopKey(tt.stops[tt.rootOf(alight.index)].id)) },
                            trailing = tf.clock(leg.arrival),
                            emphasis = true,
                            dashedBelow = li < legs.lastIndex && legs[li + 1] is WalkLeg,
                        )
                    }
                }
            }
        }
        if (legs.last() is WalkLeg) {
            item("arrive") {
                TimelineRow(
                    title = stringResource(R.string.arrive, stringResource(R.string.destination)),
                    subtitle = null,
                    color = accent,
                    first = false,
                    last = true,
                    onClick = null,
                    trailing = tf.clock(itinerary.arrival),
                    emphasis = true,
                    dashedAbove = true,
                )
            }
        }
        item("honesty") {
            Text(stringResource(R.string.scheduled_detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.gutter().padding(top = Spacing.xl))
        }
    }
}
