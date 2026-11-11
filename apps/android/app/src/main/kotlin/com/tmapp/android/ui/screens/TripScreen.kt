package com.tmapp.android.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import com.tmapp.android.ui.components.BarSegment
import com.tmapp.android.ui.components.RedHeader
import com.tmapp.android.ui.components.TmCard
import com.tmapp.android.ui.components.TripBar
import com.tmapp.android.ui.components.minutesUntil
import com.tmapp.android.ui.components.clockText
import com.tmapp.android.ui.theme.Brand
import com.tmapp.android.ui.theme.RouteColors
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmapp.android.R
import com.tmapp.android.ui.Endpoint
import com.tmapp.android.ui.ItineraryKey
import com.tmapp.android.ui.LocalTransit
import com.tmapp.android.ui.Navigator
import com.tmapp.android.ui.PlanState
import com.tmapp.android.ui.SearchKey
import com.tmapp.android.ui.TimeMode
import com.tmapp.android.ui.TripViewModel
import com.tmapp.android.ui.components.Bone
import com.tmapp.android.ui.components.EmptyState
import com.tmapp.android.ui.components.RouteBadge
import com.tmapp.android.ui.components.ScheduledTag
import com.tmapp.android.ui.components.durationText
import com.tmapp.android.ui.components.rememberNow
import com.tmapp.android.ui.components.rememberTimeFormat
import com.tmapp.android.ui.components.transfersText
import com.tmapp.android.ui.theme.LocalExtra
import com.tmapp.android.ui.theme.Spacing
import com.tmapp.transit.BOGOTA
import com.tmapp.transit.Itinerary
import com.tmapp.transit.RouteCodes
import com.tmapp.transit.TransitLeg
import com.tmapp.transit.WalkLeg
import java.time.LocalDate
import java.time.LocalTime

@Composable
fun TripScreen(nav: Navigator, vm: TripViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val tf = rememberTimeFormat()
    var picker by remember { mutableStateOf<TimeMode?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.onPermissionResult() }
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentPadding = PaddingValues(bottom = Spacing.xxl)) {
        item("header") {
            RedHeader(stringResource(R.string.trip_title), overlap = Spacing.lg) {
                Endpoints(
                    from = endpointLabel(state.from),
                    to = endpointLabel(state.to),
                    onFrom = { nav.open(SearchKey(SearchMode.FROM)) },
                    onTo = { nav.open(SearchKey(SearchMode.TO)) },
                    onSwap = vm::swap,
                )
                val clock = tf.clock(state.date.atTime(state.time).atZone(BOGOTA).toInstant())
                val day = if (state.date != LocalDate.now(BOGOTA)) " · " + stringResource(R.string.tomorrow) else ""
                LazyRow(contentPadding = PaddingValues(horizontal = Spacing.gutter), horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(top = Spacing.md)) {
                    item { TimeChip(stringResource(R.string.leave_now), state.mode == TimeMode.NOW) { vm.setTime(TimeMode.NOW, LocalDate.now(BOGOTA), LocalTime.now(BOGOTA)) } }
                    item {
                        TimeChip(if (state.mode == TimeMode.DEPART) stringResource(R.string.depart_at, clock) + day else stringResource(R.string.depart_at_label), state.mode == TimeMode.DEPART) { picker = TimeMode.DEPART }
                    }
                    item {
                        TimeChip(if (state.mode == TimeMode.ARRIVE) stringResource(R.string.arrive_by, clock) + day else stringResource(R.string.arrive_by_label), state.mode == TimeMode.ARRIVE) { picker = TimeMode.ARRIVE }
                    }
                }
            }
        }
        when (val plan = state.plan) {
            PlanState.Idle -> item("idle") { EmptyState(stringResource(R.string.trip_empty_title), stringResource(R.string.trip_empty)) }
            PlanState.NeedLocation -> item("need") {
                TmCard(Modifier.padding(top = Spacing.xl)) {
                    Text(stringResource(R.string.trip_need_location), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(Spacing.md))
                    Button(onClick = {
                        permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }, shape = MaterialTheme.shapes.small, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.location_allow)) }
                }
            }
            PlanState.Loading -> items(3, key = { "bone$it" }) { OptionSkeleton(first = it == 0) }
            PlanState.NoFix -> item("nofix") {
                TmCard(Modifier.padding(top = Spacing.xl)) {
                    Text(stringResource(R.string.trip_no_fix), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(Spacing.md))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Button(onClick = vm::replan, shape = MaterialTheme.shapes.small, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.retry)) }
                        OutlinedButton(onClick = { nav.open(SearchKey(SearchMode.FROM)) }, shape = MaterialTheme.shapes.small, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.choose_origin)) }
                    }
                }
            }
            is PlanState.Done -> {
                if (plan.options.isEmpty()) {
                    item("none") { EmptyState(stringResource(R.string.trip_none_title), stringResource(R.string.trip_none)) }
                } else {
                    item("note") {
                        Row(Modifier.padding(start = Spacing.gutter + 4.dp, end = Spacing.gutter, top = Spacing.xl, bottom = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.connections).uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f).semantics { heading() },
                            )
                            ScheduledTag()
                        }
                    }
                    items(plan.options.size, key = { plan.options[it].signature.ifEmpty { "walk" } + it }) { i ->
                        OptionCard(plan.options[i]) { nav.open(ItineraryKey(i)) }
                    }
                }
            }
        }
    }
    picker?.let { mode ->
        TimeDialog(
            initial = state.time,
            initialTomorrow = state.date != LocalDate.now(BOGOTA),
            onDismiss = { picker = null },
            onConfirm = { time, tomorrow ->
                picker = null
                vm.setTime(mode, LocalDate.now(BOGOTA).plusDays(if (tomorrow) 1 else 0), time)
            },
        )
    }
}

@Composable
private fun endpointLabel(e: Endpoint?): String? = when (e) {
    null -> null
    Endpoint.MyLocation -> stringResource(R.string.my_location)
    is Endpoint.At -> e.place.name
}

/** The from/to card on the red band: two stacked fields joined by a dotted line, swap on the right. */
@Composable
private fun Endpoints(from: String?, to: String?, onFrom: () -> Unit, onTo: () -> Unit, onSwap: () -> Unit) {
    val hairline = LocalExtra.current.hairline
    Row(
        Modifier.gutter().fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            Column {
                EndpointField(stringResource(R.string.from), from, stringResource(R.string.choose_origin), destination = false, onFrom)
                Box(Modifier.padding(start = 48.dp).fillMaxWidth().height(1.dp).background(hairline))
                EndpointField(stringResource(R.string.to), to, stringResource(R.string.choose_destination), destination = true, onTo)
            }
            // The dotted connector between the two markers.
            val ink = MaterialTheme.colorScheme.onSurfaceVariant
            Canvas(Modifier.padding(start = 23.dp).width(2.dp).height(128.dp).align(Alignment.CenterStart)) {
                val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(0.1f, 4.dp.toPx()))
                drawLine(ink, Offset(size.width / 2, 44.dp.toPx()), Offset(size.width / 2, size.height - 44.dp.toPx()), 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, pathEffect = dash)
            }
        }
        IconButton(onClick = onSwap, modifier = Modifier.padding(end = Spacing.xs).size(48.dp)) {
            Icon(Icons.Rounded.SwapVert, stringResource(R.string.swap), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun EndpointField(label: String, value: String?, placeholder: String, destination: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = "$label: ${value ?: placeholder}" }
            .padding(start = 16.dp, end = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(16.dp)) {
            if (destination) {
                drawCircle(accent, size.minDimension / 2)
                drawCircle(Color.White, size.minDimension / 5)
            } else {
                drawCircle(ink, size.minDimension / 2 - 1.5.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
            }
        }
        Spacer(Modifier.width(Spacing.lg))
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value ?: placeholder,
                style = MaterialTheme.typography.titleMedium,
                color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A choice on the red band: outlined white when idle, solid white with red text when chosen. */
@Composable
private fun TimeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val extra = LocalExtra.current
    val shape = MaterialTheme.shapes.small
    Box(
        Modifier.heightIn(min = 40.dp).clip(shape)
            .then(if (selected) Modifier.background(Color.White) else Modifier.border(1.5.dp, extra.onHeader.copy(alpha = 0.6f), shape))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Brand.red else extra.onHeader,
            maxLines = 1,
        )
    }
}

/** Today/tomorrow choice inside the time dialog. */
@Composable
private fun DayChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.heightIn(min = 40.dp),
        shape = MaterialTheme.shapes.small,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initial: LocalTime, initialTomorrow: Boolean, onDismiss: () -> Unit, onConfirm: (LocalTime, Boolean) -> Unit) {
    val picker = rememberTimePickerState(initial.hour, initial.minute)
    var tomorrow by remember { mutableStateOf(initialTomorrow) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(LocalTime.of(picker.hour, picker.minute), tomorrow) }) { Text(stringResource(R.string.done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(bottom = Spacing.lg)) {
                    DayChip(stringResource(R.string.today), !tomorrow) { tomorrow = false }
                    DayChip(stringResource(R.string.tomorrow), tomorrow) { tomorrow = true }
                }
                TimePicker(picker)
            }
        },
    )
}

/**
 * One connection, SBB-style: first line and direction on top, then departure and arrival times
 * bracketing the journey drawn to scale, then where to board and how long it takes.
 */
@Composable
private fun OptionCard(it: Itinerary, onClick: () -> Unit) {
    val data = LocalTransit.current ?: return
    val tf = rememberTimeFormat()
    val now = rememberNow()
    val rides = it.rides
    val walkOnly = rides.isEmpty()
    val duration = durationText(it.duration)
    val transfers = if (walkOnly) stringResource(R.string.walk_only) else transfersText(it.transfers)
    val spoken = stringResource(R.string.itinerary_cd, tf.clock(it.departure), tf.clock(it.arrival), duration, transfers) +
        rides.joinToString("") { r -> ", " + RouteCodes.sayable(data.timetable.routes[r.route].code) }
    Column(
        Modifier.gutter().padding(bottom = Spacing.sm).fillMaxWidth()
            .clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = spoken }
            .padding(horizontal = Spacing.lg, vertical = 14.dp),
    ) {
        // Departure countdown, red while it is close enough to matter.
        val first = rides.firstOrNull()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                first?.let { f -> stringResource(R.string.toward, f.destination) } ?: stringResource(R.string.walk_only),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val m = minutesUntil(now, it.departure)
            Text(
                tf.relative(now, it.departure),
                style = MaterialTheme.typography.labelMedium,
                color = if (m < 15) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(Spacing.xs))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(clockText(tf.clock(it.departure)), style = MaterialTheme.typography.headlineSmall)
            ItineraryBar(it, Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 5.dp))
            Text(clockText(tf.clock(it.arrival)), style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(Spacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val board = first?.let { f -> data.timetable.stops[f.stops.first().stop].name }
            Text(
                listOfNotNull(board?.let { b -> stringResource(R.string.leave_from, b) }, transfers).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Spacing.md))
            Text(duration, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
        }
        if (rides.any { r -> r.estimated }) ScheduledTag(Modifier.padding(top = 6.dp), estimated = true)
    }
}

/** The itinerary drawn to scale with its line pictograms; see [TripBar]. */
@Composable
fun ItineraryBar(it: Itinerary, modifier: Modifier = Modifier) {
    val data = LocalTransit.current ?: return
    val total = it.duration.seconds.coerceAtLeast(1).toFloat()
    fun at(t: java.time.Instant) = (java.time.Duration.between(it.departure, t).seconds / total).coerceIn(0f, 1f)
    val segments = it.legs.map { leg ->
        BarSegment(at(leg.departure), at(leg.arrival), (leg as? TransitLeg)?.let { l -> RouteColors.background(data.timetable.routes[l.route]) } ?: Color.Gray, walk = leg is WalkLeg)
    }
    val badges: List<Pair<Float, @Composable () -> Unit>> = it.rides.map { r -> at(r.departure) to @Composable { RouteBadge(data.timetable.routes[r.route]) } }
    TripBar(segments, badges, modifier)
}

@Composable
private fun OptionSkeleton(first: Boolean) {
    Column(
        Modifier.gutter().padding(top = if (first) Spacing.xl else 0.dp, bottom = Spacing.sm).fillMaxWidth()
            .clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surface).padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Bone(140.dp, 12.dp)
        Bone(280.dp, 22.dp)
        Bone(200.dp, 12.dp)
    }
}
