package com.tmapp.android.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.Train
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tmapp.android.R
import com.tmapp.android.ui.theme.GeistMono
import com.tmapp.android.ui.theme.LocalExtra
import com.tmapp.android.ui.theme.RouteColors
import com.tmapp.android.ui.theme.Spacing
import com.tmapp.transit.BOGOTA
import com.tmapp.transit.Route
import com.tmapp.transit.RouteCodes
import com.tmapp.transit.Stop
import com.tmapp.transit.SystemKind
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// ---- Time ------------------------------------------------------------------------------------------

/** A clock that ticks on the minute boundary so countdowns stay honest without wasted frames. */
@Composable
fun rememberNow(): Instant {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            val ms = 60_000 - (System.currentTimeMillis() % 60_000)
            delay(ms + 50)
            now = Instant.now()
        }
    }
    return now
}

@Composable
fun clock(instant: Instant): String {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    return remember(instant, locale) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(instant.atZone(BOGOTA))
    }
}

fun minutesUntil(now: Instant, t: Instant): Long = Duration.between(now, t).toMinutes().coerceAtLeast(0)

@Composable
fun durationText(d: Duration): String {
    val minutes = d.toMinutes().coerceAtLeast(1)
    return if (minutes < 60) stringResource(R.string.minutes_duration, minutes.toInt())
    else stringResource(R.string.hours_minutes_duration, (minutes / 60).toInt(), (minutes % 60).toInt())
}

/** "Now", "in 4 min" or a clock time beyond an hour. */
@Composable
fun relativeText(now: Instant, t: Instant): String {
    val m = minutesUntil(now, t)
    return when {
        m < 1 -> stringResource(R.string.now)
        m < 60 -> stringResource(R.string.in_minutes, m.toInt())
        else -> clock(t)
    }
}

/** Large minutes with a small unit, aligned on the baseline. Beyond an hour, a clock time. */
@Composable
fun Minutes(now: Instant, t: Instant, large: Boolean, modifier: Modifier = Modifier) {
    val m = minutesUntil(now, t)
    val numberStyle = if (large) MaterialTheme.typography.displayLarge else MaterialTheme.typography.headlineMedium
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        when {
            m < 1 -> Text(stringResource(R.string.now), style = if (large) MaterialTheme.typography.displaySmall else MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            m < 60 -> {
                Text(m.toString(), style = numberStyle)
                Spacer(Modifier.width(3.dp))
                Text(stringResource(R.string.min_short), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = if (large) 8.dp else 3.dp))
            }
            else -> Text(clock(t), style = if (large) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleLarge)
        }
    }
}

// ---- Routes ----------------------------------------------------------------------------------------

@Composable
fun RouteBadge(route: Route, modifier: Modifier = Modifier, large: Boolean = false, outlined: Boolean = false) {
    val bg = RouteColors.background(route)
    val shape = RoundedCornerShape(if (large) 8.dp else 6.dp)
    val padding = if (large) Modifier.padding(horizontal = 10.dp, vertical = 5.dp) else Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
    val style = MaterialTheme.typography.labelSmall.copy(
        fontFamily = GeistMono,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.2.sp,
        fontSize = if (large) 17.sp else 13.sp,
        lineHeight = if (large) 22.sp else 17.sp,
    )
    val base = modifier
        .clip(shape)
        .semantics { contentDescription = RouteCodes.sayable(route.code) }
    if (outlined) {
        Row(base.border(1.dp, MaterialTheme.colorScheme.outline, shape).then(padding), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(if (large) 8.dp else 6.dp).clip(CircleShape).background(bg))
            Spacer(Modifier.width(5.dp))
            Text(route.code, style = style, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        }
    } else {
        Box(base.background(bg).then(padding)) {
            Text(route.code, style = style, color = RouteColors.content(bg), maxLines = 1)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RouteBadges(routes: List<Route>, max: Int = 6, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        routes.take(max).forEach { RouteBadge(it, outlined = true) }
        if (routes.size > max) {
            Text("+${routes.size - max}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically))
        }
    }
}

@Composable
fun systemLabel(system: SystemKind): String = stringResource(
    when (system) {
        SystemKind.TRONCAL -> R.string.system_troncal
        SystemKind.ALIMENTADOR -> R.string.system_alimentador
        SystemKind.URBANO -> R.string.system_urbano
        SystemKind.COMPLEMENTARIO -> R.string.system_complementario
        SystemKind.ESPECIAL -> R.string.system_especial
        SystemKind.DUAL -> R.string.system_dual
        SystemKind.CABLE -> R.string.system_cable
        SystemKind.UNKNOWN -> R.string.system_unknown
    },
)

// ---- Stops -----------------------------------------------------------------------------------------

@Composable
fun StopTile(stop: Stop, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (stop.isStation) Icons.Rounded.Train else Icons.Rounded.DirectionsBus,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

@Composable
fun stopKind(stop: Stop): String = if (stop.isStation) stringResource(R.string.kind_station) else stringResource(R.string.kind_stop)

// ---- Labels and states -----------------------------------------------------------------------------

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier.semantics(mergeDescendants = true) { heading() }) {
        Text(text, style = MaterialTheme.typography.titleLarge)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The scheduled-data signal: a dot plus words, never colour alone. */
@Composable
fun ScheduledTag(modifier: Modifier = Modifier, estimated: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(LocalExtra.current.scheduled))
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(if (estimated) R.string.estimated else R.string.scheduled),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun WalkChip(minutes: Int, modifier: Modifier = Modifier) {
    Row(modifier.clearAndSetSemantics { contentDescription = "" }, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Rounded.DirectionsWalk, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("$minutes", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(LocalExtra.current.hairline))
}

/** Placeholder occupying the final content's space so nothing jumps when data arrives. */
@Composable
fun Bone(width: Dp, height: Dp, modifier: Modifier = Modifier) {
    val alpha by rememberInfiniteTransition(label = "bone").animateFloat(0.5f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Box(modifier.width(width).height(height).alpha(alpha).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh))
}

@Composable
fun SkeletonRows(count: Int = 4, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = Spacing.gutter), verticalArrangement = Arrangement.spacedBy(Spacing.xl)) {
        repeat(count) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Bone(40.dp, 40.dp)
                Spacer(Modifier.width(Spacing.md))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Bone(180.dp, 16.dp)
                    Bone(120.dp, 12.dp)
                }
            }
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.xxl), horizontalAlignment = Alignment.Start) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(Spacing.sm))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun transfersText(n: Int): String = if (n == 0) stringResource(R.string.transfers_none) else pluralStringResource(R.plurals.transfers, n, n)

/** Non-composable formatting for use inside lambdas (content descriptions, joins). */
class TimeFormat(private val context: android.content.Context) {
    private val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(context.resources.configuration.locales[0] ?: Locale.getDefault())

    fun clock(t: Instant): String = formatter.format(t.atZone(BOGOTA))

    /** "Ahora", "4 min" or a clock time: for tight rows. */
    fun compact(now: Instant, t: Instant): String {
        val m = minutesUntil(now, t)
        return when {
            m < 1 -> context.getString(R.string.now)
            m < 60 -> context.getString(R.string.minutes_duration, m.toInt())
            else -> clock(t)
        }
    }

    fun relative(now: Instant, t: Instant): String {
        val m = minutesUntil(now, t)
        return when {
            m < 1 -> context.getString(R.string.now)
            m < 60 -> context.getString(R.string.in_minutes, m.toInt())
            else -> clock(t)
        }
    }
}

@Composable
fun rememberTimeFormat(): TimeFormat {
    val context = LocalContext.current
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    return remember(context, locale) { TimeFormat(context) }
}
