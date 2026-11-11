package com.tmapp.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmapp.android.ui.theme.LocalExtra
import com.tmapp.android.ui.theme.Spacing

// ---- Grouped lists ---------------------------------------------------------------------------------

private val groupRadius = 16.dp

/**
 * One row of a grouped list: rows share a white card on the grey ground, with rounded corners only
 * at the group's ends. Lets lazy lists build cards out of individual items.
 */
fun Modifier.groupItem(first: Boolean, last: Boolean, color: Color): Modifier = this
    .padding(horizontal = Spacing.gutter)
    .clip(RoundedCornerShape(
        topStart = if (first) groupRadius else 0.dp, topEnd = if (first) groupRadius else 0.dp,
        bottomStart = if (last) groupRadius else 0.dp, bottomEnd = if (last) groupRadius else 0.dp,
    ))
    .background(color)

/** A white card on the grey ground. */
@Composable
fun TmCard(modifier: Modifier = Modifier, padding: Dp = Spacing.lg, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.padding(horizontal = Spacing.gutter).fillMaxWidth().clip(RoundedCornerShape(groupRadius))
            .background(MaterialTheme.colorScheme.surface).padding(padding),
        content = content,
    )
}

/** Divider inside a grouped card, inset from the start like a list separator. */
@Composable
fun RowDivider(start: Dp = Spacing.lg) {
    Box(Modifier.fillMaxWidth().padding(start = start).height(1.dp).background(LocalExtra.current.hairline))
}

/** Small uppercase label above a group, sitting on the grey ground. */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = Spacing.gutter + 4.dp, end = Spacing.gutter, top = Spacing.xl, bottom = Spacing.sm).semantics { heading() },
    )
}

// ---- Red header band -------------------------------------------------------------------------------

/**
 * The red band that opens each top-level screen, under the status bar. Content inside it reads as
 * white on red; [below] draws a white card that overlaps the band's lower edge.
 */
@Composable
fun RedHeader(title: String, modifier: Modifier = Modifier, overlap: Dp = 0.dp, content: @Composable ColumnScope.() -> Unit = {}) {
    val extra = LocalExtra.current
    Column(modifier.fillMaxWidth().background(extra.header).statusBarsPadding().padding(bottom = overlap)) {
        CapFontScale {
            Text(
                title,
                style = MaterialTheme.typography.headlineLarge,
                color = extra.onHeader,
                modifier = Modifier.padding(start = Spacing.gutter + 4.dp, end = Spacing.gutter, top = Spacing.lg, bottom = Spacing.lg).semantics { heading() },
            )
        }
        content()
    }
}

/** Gives content on the red band white text and controls. */
@Composable
fun OnHeader(content: @Composable () -> Unit) {
    val extra = LocalExtra.current
    CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides extra.onHeader, content = content)
}

// ---- Trip bar --------------------------------------------------------------------------------------

/** One segment of a [TripBar]: its share of the journey and how to draw it. */
class BarSegment(val start: Float, val end: Float, val color: Color, val walk: Boolean)

/**
 * A journey drawn to scale: rides as solid bars in their line colour, walks as dotted grey, with a
 * ring at each change. Line pictograms sit above the bar at the point each ride starts, nudged
 * right when two would collide.
 */
@Composable
fun TripBar(segments: List<BarSegment>, badges: List<Pair<Float, @Composable () -> Unit>>, modifier: Modifier = Modifier) {
    val walk = LocalExtra.current.walk
    val ring = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surface
    val track = MaterialTheme.colorScheme.outlineVariant
    Column(modifier.fillMaxWidth()) {
        if (badges.isNotEmpty()) {
            Layout(content = { badges.forEach { it.second() } }, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
                val width = constraints.maxWidth
                val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
                val gap = 4.dp.roundToPx()
                var cursor = 0
                val xs = placeables.mapIndexed { i, p ->
                    val wanted = (badges[i].first * width).toInt()
                    val x = maxOf(wanted, cursor).coerceAtMost(maxOf(0, width - p.width))
                    cursor = x + p.width + gap
                    x
                }
                val h = placeables.maxOfOrNull { it.height } ?: 0
                layout(width, h) { placeables.forEachIndexed { i, p -> p.placeRelative(xs[i], 0) } }
            }
            Spacer(Modifier.height(6.dp))
        }
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val cy = size.height / 2
            val thick = 6.dp.toPx()
            val thin = 3.dp.toPx()
            val dots = PathEffect.dashPathEffect(floatArrayOf(0.1f, 6.dp.toPx()))
            // Waiting time shows as the bare track between segments.
            drawLine(track, Offset(0f, cy), Offset(size.width, cy), 2.dp.toPx())
            segments.forEach { s ->
                val x0 = s.start * size.width
                val x1 = s.end * size.width
                if (s.walk) {
                    drawLine(walk, Offset(x0 + thin, cy), Offset(x1 - thin, cy), thin, cap = StrokeCap.Round, pathEffect = dots)
                } else {
                    drawRoundRect(s.color, Offset(x0, cy - thick / 2), Size(maxOf(thick, x1 - x0), thick), CornerRadius(thick / 2))
                }
            }
            // Rings mark the start, every change and the end.
            val marks = (segments.map { it.start } + (segments.lastOrNull()?.end ?: 1f)).distinct()
            val r = 5.dp.toPx()
            marks.forEach { m ->
                val x = (m * size.width).coerceIn(r, size.width - r)
                drawCircle(surface, r, Offset(x, cy))
                drawCircle(ring, r, Offset(x, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            }
        }
    }
}
