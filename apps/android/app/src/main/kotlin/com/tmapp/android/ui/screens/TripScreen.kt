package com.tmapp.android.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalButton
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
import com.tmapp.android.ui.components.Eyebrow
import com.tmapp.android.ui.components.RouteBadge
import com.tmapp.android.ui.components.ScheduledTag
import com.tmapp.android.ui.components.WalkChip
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
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding(), contentPadding = PaddingValues(bottom = Spacing.xxl)) {
        item("title") {
            Text(
                stringResource(R.string.trip_title),
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.gutter().padding(top = Spacing.xl).semantics { heading() },
            )
        }
        item("endpoints") {
            Endpoints(
                from = endpointLabel(state.from),
                to = endpointLabel(state.to),
                onFrom = { nav.open(SearchKey(SearchMode.FROM)) },
                onTo = { nav.open(SearchKey(SearchMode.TO)) },
                onSwap = vm::swap,
            )
        }
        item("time") {
            val clock = tf.clock(state.date.atTime(state.time).atZone(BOGOTA).toInstant())
            val day = if (state.date != LocalDate.now(BOGOTA)) " · " + stringResource(R.string.tomorrow) else ""
            LazyRow(contentPadding = PaddingValues(horizontal = Spacing.gutter), horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(top = Spacing.lg)) {
                item { TimeChip(stringResource(R.string.leave_now), state.mode == TimeMode.NOW) { vm.setTime(TimeMode.NOW, LocalDate.now(BOGOTA), LocalTime.now(BOGOTA)) } }
                item {
                    TimeChip(if (state.mode == TimeMode.DEPART) stringResource(R.string.depart_at, clock) + day else stringResource(R.string.depart_at_label), state.mode == TimeMode.DEPART) { picker = TimeMode.DEPART }
                }
                item {
                    TimeChip(if (state.mode == TimeMode.ARRIVE) stringResource(R.string.arrive_by, clock) + day else stringResource(R.string.arrive_by_label), state.mode == TimeMode.ARRIVE) { picker = TimeMode.ARRIVE }
                }
            }
        }
        when (val plan = state.plan) {
            PlanState.Idle -> item("idle") { EmptyState(stringResource(R.string.trip_title), stringResource(R.string.trip_empty)) }
            PlanState.NeedLocation -> item("need") {
                Column(Modifier.gutter().padding(top = Spacing.xl)) {
                    Text(stringResource(R.string.trip_need_location), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(Spacing.md))
                    FilledTonalButton(onClick = {
                        permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }) { Text(stringResource(R.string.location_allow)) }
                }
            }
            PlanState.Loading -> items(3, key = { "bone$it" }) { OptionSkeleton() }
            is PlanState.Done -> {
                if (plan.options.isEmpty()) {
                    item("none") { EmptyState(stringResource(R.string.trip_title), stringResource(R.string.trip_none)) }
                } else {
                    item("note") {
                        Row(Modifier.gutter().padding(top = Spacing.xl, bottom = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
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

@Composable
private fun Endpoints(from: String?, to: String?, onFrom: () -> Unit, onTo: () -> Unit, onSwap: () -> Unit) {
    val hairline = LocalExtra.current.hairline
    Row(
        Modifier.gutter().padding(top = Spacing.xl).fillMaxWidth().clip(RoundedCornerShape(20.dp)).border(1.dp, hairline, RoundedCornerShape(20.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            EndpointField(stringResource(R.string.from), from, stringResource(R.string.choose_origin), MaterialTheme.colorScheme.onSurface, onFrom)
            Box(Modifier.padding(start = 44.dp).fillMaxWidth().height(1.dp).background(hairline))
            EndpointField(stringResource(R.string.to), to, stringResource(R.string.choose_destination), MaterialTheme.colorScheme.primary, onTo)
        }
        IconButton(onClick = onSwap, modifier = Modifier.padding(end = Spacing.xs).size(48.dp)) {
            Icon(Icons.Rounded.SwapVert, stringResource(R.string.swap))
        }
    }
}

@Composable
private fun EndpointField(label: String, value: String?, placeholder: String, dot: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = "$label: ${value ?: placeholder}" }
            .padding(horizontal = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(Spacing.lg))
        Column {
            Eyebrow(label)
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

@Composable
private fun TimeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.heightIn(min = 40.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.secondary,
            selectedLabelColor = MaterialTheme.colorScheme.onSecondary,
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
                    TimeChip(stringResource(R.string.today), !tomorrow) { tomorrow = false }
                    TimeChip(stringResource(R.string.tomorrow), tomorrow) { tomorrow = true }
                }
                TimePicker(picker)
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
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
        Modifier.gutter().padding(vertical = Spacing.sm).fillMaxWidth()
            .clip(RoundedCornerShape(20.dp)).border(1.dp, LocalExtra.current.hairline, RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = spoken }
            .padding(Spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.time_range, tf.clock(it.departure), tf.clock(it.arrival)), style = MaterialTheme.typography.titleLarge)
                Text(transfers, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(duration, style = MaterialTheme.typography.headlineMedium)
        }
        Spacer(Modifier.height(Spacing.md))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            it.legs.forEachIndexed { i, leg ->
                if (i > 0) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                when (leg) {
                    is WalkLeg -> WalkChip(leg.duration.toMinutes().toInt().coerceAtLeast(1))
                    is TransitLeg -> RouteBadge(data.timetable.routes[leg.route])
                }
            }
        }
        rides.firstOrNull()?.let { first ->
            Spacer(Modifier.height(Spacing.md))
            val stop = data.timetable.stops[first.stops.first().stop]
            Text(
                stringResource(R.string.leave_from, stop.name) + " · " + stringResource(R.string.leaves_in, tf.relative(now, first.departure)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (rides.any { r -> r.estimated }) ScheduledTag(Modifier.padding(top = 4.dp), estimated = true)
        }
    }
}

@Composable
private fun OptionSkeleton() {
    Column(
        Modifier.gutter().padding(vertical = Spacing.sm).fillMaxWidth()
            .clip(RoundedCornerShape(20.dp)).border(1.dp, LocalExtra.current.hairline, RoundedCornerShape(20.dp)).padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Bone(160.dp, 22.dp)
        Bone(220.dp, 18.dp)
        Bone(260.dp, 14.dp)
    }
}

