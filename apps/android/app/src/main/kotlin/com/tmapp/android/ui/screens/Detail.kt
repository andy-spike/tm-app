package com.tmapp.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tmapp.android.R
import com.tmapp.android.ui.theme.Spacing

/**
 * Map on top, content sheet over it. The map parallaxes away as the list scrolls; floating round
 * buttons stay reachable above it.
 */
@Composable
fun DetailScreen(
    onBack: () -> Unit,
    map: @Composable BoxScope.() -> Unit,
    actions: @Composable () -> Unit = {},
    mapHeight: androidx.compose.ui.unit.Dp = 260.dp,
    content: LazyListScope.() -> Unit,
) {
    val list = rememberLazyListState()
    val density = LocalDensity.current
    val offset by remember {
        derivedStateOf { if (list.firstVisibleItemIndex == 0) list.firstVisibleItemScrollOffset.toFloat() else with(density) { mapHeight.toPx() } }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxWidth().height(mapHeight + 20.dp).graphicsLayer { translationY = -offset * 0.5f }) { map() }
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Spacing.xxl)) {
            item("spacer") { Spacer(Modifier.height(mapHeight)) }
            item("sheet-top") {
                Box(
                    Modifier.fillMaxWidth().height(20.dp).clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                        .background(MaterialTheme.colorScheme.background),
                )
            }
            content()
            item("nav-bottom") { Spacer(Modifier.navigationBarsPadding()) }
        }
        // Once the sheet reaches the top, a solid strip keeps the status bar legible over the list.
        val bg = MaterialTheme.colorScheme.background
        val mapPx = with(density) { mapHeight.toPx() }
        Box(
            Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars)
                .graphicsLayer { alpha = ((offset - mapPx * 0.7f) / (mapPx * 0.3f)).coerceIn(0f, 1f) }
                .background(bg),
        )
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Spacing.md, vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), onBack)
            Spacer(Modifier.weight(1f))
            actions()
        }
    }
}

@Composable
fun RoundButton(icon: ImageVector, description: String, onClick: () -> Unit, selected: Boolean = false) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp).shadow(3.dp, CircleShape),
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
        ),
    ) { Icon(icon, contentDescription = description) }
}

@Composable
fun LoadFailed(message: String) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(Spacing.gutter), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        Text(stringResource(R.string.timetable_failed, message), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(Spacing.lg))
        val activity = androidx.activity.compose.LocalActivity.current
        Button(onClick = { activity?.recreate() }, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.retry)) }
    }
}

/** Offsets content horizontally to the screen gutter. */
fun Modifier.gutter() = this.padding(horizontal = Spacing.gutter)

