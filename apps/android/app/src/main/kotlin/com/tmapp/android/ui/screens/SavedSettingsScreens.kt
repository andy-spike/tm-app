package com.tmapp.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.tmapp.android.ui.components.Eyebrow
import com.tmapp.android.ui.components.Hairline
import com.tmapp.android.ui.components.Minutes
import com.tmapp.android.ui.components.RouteBadge
import com.tmapp.android.ui.components.ScheduledTag
import com.tmapp.android.ui.components.SectionTitle
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
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding(), contentPadding = PaddingValues(bottom = Spacing.xxl)) {
        item("title") {
            Text(stringResource(R.string.saved_title), style = MaterialTheme.typography.displaySmall, modifier = Modifier.gutter().padding(top = Spacing.xl).semantics { heading() })
        }
        if (!state.loaded || data == null) {
            item("loading") { SkeletonRows(3, Modifier.padding(top = Spacing.xl)) }
            return@LazyColumn
        }
        if (state.stops.isEmpty() && state.routes.isEmpty()) {
            item("empty") { EmptyState(stringResource(R.string.saved_empty_title), stringResource(R.string.saved_empty_body)) }
            return@LazyColumn
        }
        if (state.stops.isNotEmpty()) {
            item("stops-title") { SectionTitle(stringResource(R.string.saved_stops), Modifier.gutter().padding(top = Spacing.xl, bottom = Spacing.sm)) }
            items(state.stops, key = { "s${it.stop}" }) { card ->
                SavedStopRow(card, now) { nav.open(StopKey(data.timetable.stops[card.stop].id)) }
            }
            item("scheduled") { ScheduledTag(Modifier.gutter().padding(top = Spacing.sm)) }
        }
        if (state.routes.isNotEmpty()) {
            item("routes-title") { SectionTitle(stringResource(R.string.saved_routes), Modifier.gutter().padding(top = Spacing.xl, bottom = Spacing.sm)) }
            items(state.routes, key = { "r$it" }) { r -> RouteRow(r) { nav.open(RouteKey(data.timetable.routes[r].id)) } }
        }
    }
}

@Composable
private fun SavedStopRow(card: SavedStopCard, now: java.time.Instant, onClick: () -> Unit) {
    val data = LocalTransit.current ?: return
    val stop = data.timetable.stops[card.stop]
    Column(
        Modifier.gutter().padding(vertical = Spacing.sm).fillMaxWidth()
            .clip(RoundedCornerShape(20.dp)).border(1.dp, LocalExtra.current.hairline, RoundedCornerShape(20.dp))
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
            Hairline(Modifier.padding(vertical = 6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RouteBadge(data.timetable.routes[d.route])
                Spacer(Modifier.width(Spacing.sm))
                Text(d.destination, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
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
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding(), contentPadding = PaddingValues(bottom = Spacing.xxl)) {
        item("title") {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.displaySmall, modifier = Modifier.gutter().padding(top = Spacing.xl).semantics { heading() })
        }
        item("data") {
            Card(Modifier.padding(top = Spacing.xl)) {
                Eyebrow(stringResource(R.string.data_title))
                Spacer(Modifier.height(Spacing.sm))
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
                    Button(onClick = vm::checkNow, enabled = check != CheckState.Checking, modifier = Modifier.heightIn(min = 48.dp)) {
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
            Card(Modifier.padding(top = Spacing.md)) {
                Eyebrow(stringResource(R.string.appearance))
                Spacer(Modifier.height(Spacing.md))
                val modes = listOf(ThemeMode.SYSTEM to R.string.theme_system, ThemeMode.LIGHT to R.string.theme_light, ThemeMode.DARK to R.string.theme_dark)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    modes.forEachIndexed { i, (mode, label) ->
                        SegmentedButton(
                            selected = settings.theme == mode,
                            onClick = { vm.setTheme(mode) },
                            shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                            modifier = Modifier.heightIn(min = 48.dp),
                            label = { Text(stringResource(label), maxLines = 1) },
                        )
                    }
                }
            }
        }
        item("privacy") {
            Card(Modifier.padding(top = Spacing.md)) {
                Eyebrow(stringResource(R.string.privacy_title))
                Spacer(Modifier.height(Spacing.sm))
                Text(stringResource(R.string.privacy_body), style = MaterialTheme.typography.bodyMedium)
            }
        }
        item("about") {
            Card(Modifier.padding(top = Spacing.md)) {
                Eyebrow(stringResource(R.string.about_title))
                Spacer(Modifier.height(Spacing.sm))
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
        modifier.gutter().fillMaxWidth().clip(RoundedCornerShape(20.dp)).border(1.dp, LocalExtra.current.hairline, RoundedCornerShape(20.dp)).padding(Spacing.lg),
        verticalArrangement = Arrangement.Top,
    ) { content() }
}
