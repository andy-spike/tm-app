package com.tmapp.android.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmapp.android.BuildConfig
import com.tmapp.android.R
import com.tmapp.android.ui.AppViewModel
import com.tmapp.android.ui.CheckState
import com.tmapp.android.ui.LocalTransit
import com.tmapp.android.ui.Navigator
import com.tmapp.android.ui.RouteKey
import com.tmapp.android.ui.SavedStopCard
import com.tmapp.android.ui.SavedViewModel
import com.tmapp.android.ui.SettingsViewModel
import com.tmapp.android.ui.StopKey
import com.tmapp.android.ui.components.EmptyState
import com.tmapp.android.ui.components.GroupLabel
import com.tmapp.android.ui.components.RedHeader
import com.tmapp.android.ui.components.Minutes
import com.tmapp.android.ui.components.RouteBadge
import com.tmapp.android.ui.components.ScheduledTag
import com.tmapp.android.ui.components.SkeletonRows
import com.tmapp.android.ui.components.StopTile
import com.tmapp.android.ui.components.rememberNow
import com.tmapp.android.ui.components.stopKind
import com.tmapp.android.ui.theme.LocalExtra
import com.tmapp.android.ui.theme.Spacing
import com.tmapp.data.BundleOrigin
import com.tmapp.data.SyncResult
import com.tmapp.data.ThemeMode
import com.tmapp.transit.BOGOTA
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun SavedScreen(nav: Navigator) {
    val vm: SavedViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val data = LocalTransit.current
    val now = rememberNow()
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentPadding = PaddingValues(bottom = Spacing.xxl)) {
        item("title") { RedHeader(stringResource(R.string.saved_title)) }
        if (!state.loaded || data == null) {
            item("loading") { SkeletonRows(3, Modifier.padding(top = Spacing.xl)) }
            return@LazyColumn
        }
        if (state.stops.isEmpty() && state.routes.isEmpty()) {
            item("empty") { EmptyState(stringResource(R.string.saved_empty_title), stringResource(R.string.saved_empty_body)) }
            return@LazyColumn
        }
        if (state.stops.isNotEmpty()) {
            item("stops-title") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GroupLabel(stringResource(R.string.saved_stops), Modifier.weight(1f))
                    ScheduledTag(Modifier.padding(end = Spacing.gutter + 4.dp, top = Spacing.md))
                }
            }
            items(state.stops, key = { "s${it.stop}" }) { card ->
                SavedStopRow(card, now) { nav.open(StopKey(data.timetable.stops[card.stop].id)) }
            }
        }
        if (state.routes.isNotEmpty()) {
            item("routes-title") { GroupLabel(stringResource(R.string.saved_routes)) }
            items(state.routes.size, key = { "r${state.routes[it]}" }) { i ->
                val r = state.routes[i]
                RouteRow(r, first = i == 0, last = i == state.routes.lastIndex) { nav.open(RouteKey(data.timetable.routes[r].id)) }
            }
        }
    }
}

@Composable
private fun SavedStopRow(card: SavedStopCard, now: java.time.Instant, onClick: () -> Unit) {
    val data = LocalTransit.current ?: return
    val stop = data.timetable.stops[card.stop]
    Column(
        Modifier.gutter().padding(bottom = Spacing.sm).fillMaxWidth()
            .clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surface)
            .clickable(role = Role.Button, onClick = onClick).padding(Spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StopTile(stop)
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(stop.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stopKind(stop), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (card.departures.isEmpty()) {
            Text(stringResource(R.string.no_departures), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.md))
        }
        card.departures.forEachIndexed { i, d ->
            if (i == 0) Spacer(Modifier.height(Spacing.sm))
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(1.dp).background(LocalExtra.current.hairline))
            val route = data.timetable.routes[d.route]
            val spoken = stringResource(R.string.departure_cd, com.tmapp.transit.RouteCodes.sayable(route.code), d.destination, com.tmapp.android.ui.components.rememberTimeFormat().relative(now, d.time))
            Row(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken }, verticalAlignment = Alignment.CenterVertically) {
                RouteBadge(route)
                Spacer(Modifier.width(Spacing.md))
                Text(d.destination, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Minutes(now, d.time, large = false)
            }
        }
    }
}

@Composable
fun SettingsScreen() {
    val vm: SettingsViewModel = hiltViewModel()
    val app: AppViewModel = hiltViewModel()
    val data by vm.data.collectAsStateWithLifecycle()
    val check by vm.check.collectAsStateWithLifecycle()
    val settings by app.settings.collectAsStateWithLifecycle()
    val dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentPadding = PaddingValues(bottom = Spacing.xxl)) {
        item("title") { RedHeader(stringResource(R.string.settings_title)) }
        item("data") {
            GroupLabel(stringResource(R.string.data_title))
            Card {
                val d = data
                if (d == null) {
                    Text(stringResource(R.string.loading_timetable), style = MaterialTheme.typography.titleMedium)
                } else {
                    val tt = d.timetable
                    Text(stringResource(R.string.data_valid, dates.format(tt.feedStart), dates.format(tt.feedEnd)), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(if (d.bundle.origin == BundleOrigin.BUILT_IN) R.string.data_built_in else R.string.data_downloaded) + " · " +
                            stringResource(R.string.data_version, runCatching { dates.format(java.time.Instant.parse(d.bundle.builtAt).atZone(BOGOTA)) }.getOrDefault(tt.version)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (tt.feedEnd < LocalDate.now(BOGOTA)) {
                        Text(stringResource(R.string.data_expired), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = Spacing.sm))
                    }
                }
                Spacer(Modifier.height(Spacing.lg))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = vm::checkNow, enabled = check != CheckState.Checking, shape = MaterialTheme.shapes.small, modifier = Modifier.heightIn(min = 48.dp)) {
                        if (check == CheckState.Checking) {
                            CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(Spacing.sm))
                            Text(stringResource(R.string.checking))
                        } else {
                            Text(stringResource(R.string.check_updates))
                        }
                    }
                }
                val message = (check as? CheckState.Done)?.result?.let {
                    when (it) {
                        SyncResult.UpToDate -> stringResource(R.string.sync_up_to_date)
                        is SyncResult.Updated -> stringResource(R.string.sync_updated)
                        is SyncResult.Failed -> stringResource(R.string.sync_failed)
                    }
                }
                Text(
                    message ?: stringResource(R.string.sync_auto),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.sm).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        item("appearance") {
            GroupLabel(stringResource(R.string.appearance))
            Card {
                val modes = listOf(ThemeMode.SYSTEM to R.string.theme_system, ThemeMode.LIGHT to R.string.theme_light, ThemeMode.DARK to R.string.theme_dark)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    modes.forEachIndexed { i, (mode, label) ->
                        SegmentedButton(
                            selected = settings.theme == mode,
                            onClick = { vm.setTheme(mode) },
                            shape = SegmentedButtonDefaults.itemShape(i, modes.size, MaterialTheme.shapes.small),
                            modifier = Modifier.heightIn(min = 48.dp),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = MaterialTheme.colorScheme.tertiary,
                                activeContentColor = MaterialTheme.colorScheme.onTertiary,
                                inactiveContainerColor = MaterialTheme.colorScheme.surface,
                            ),
                            label = { Text(stringResource(label), maxLines = 1) },
                        )
                    }
                }
            }
        }
        item("privacy") {
            GroupLabel(stringResource(R.string.privacy_title))
            Card {
                Text(stringResource(R.string.privacy_body), style = MaterialTheme.typography.bodyMedium)
            }
        }
        item("about") {
            GroupLabel(stringResource(R.string.about_title))
            Card {
                Text(stringResource(R.string.about_body), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(Spacing.md))
                Text(stringResource(R.string.version_label, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.gutter().fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surface).padding(Spacing.lg),
        verticalArrangement = Arrangement.Top,
    ) { content() }
}
