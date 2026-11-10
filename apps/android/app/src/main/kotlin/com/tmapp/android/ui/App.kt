package com.tmapp.android.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.tmapp.android.R
import com.tmapp.android.ui.screens.HomeScreen
import com.tmapp.android.ui.screens.ItineraryScreen
import com.tmapp.android.ui.screens.LoadFailed
import com.tmapp.android.ui.screens.RouteScreen
import com.tmapp.android.ui.screens.SavedScreen
import com.tmapp.android.ui.screens.SearchMode
import com.tmapp.android.ui.screens.SearchScreen
import com.tmapp.android.ui.screens.SettingsScreen
import com.tmapp.android.ui.screens.StopScreen
import com.tmapp.android.ui.screens.TripScreen
import com.tmapp.android.ui.theme.TmTheme
import com.tmapp.data.TransitData
import com.tmapp.data.TransitState
import kotlinx.serialization.Serializable

@Serializable data object NearbyKey : NavKey
@Serializable data object TripKey : NavKey
@Serializable data object SavedKey : NavKey
@Serializable data object SettingsKey : NavKey
@Serializable data class SearchKey(val mode: SearchMode) : NavKey
@Serializable data class StopKey(val id: String) : NavKey
@Serializable data class RouteKey(val id: String) : NavKey
@Serializable data class ItineraryKey(val index: Int) : NavKey

private val tabs = listOf(NearbyKey, TripKey, SavedKey, SettingsKey)

/** The decoded timetable, or null while it loads. */
val LocalTransit = staticCompositionLocalOf<TransitData?> { null }

/** Navigation actions shared by every screen. */
class Navigator(private val stack: NavBackStack<NavKey>) {
    fun open(key: NavKey) {
        if (stack.lastOrNull() != key) stack.add(key)
    }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun tab(key: NavKey) {
        // Nearby is the root; every other tab sits directly on top of it.
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
        if (key != NearbyKey) stack.add(key)
    }
    val current: NavKey? get() = stack.lastOrNull()
}

@Composable
fun TmApp() {
    val app: AppViewModel = hiltViewModel()
    val settings by app.settings.collectAsStateWithLifecycle()
    val state by app.state.collectAsStateWithLifecycle()
    TmTheme(settings.theme) {
        val stack = rememberNavBackStack(NearbyKey)
        val nav = Navigator(stack)
        // The planner is shared by the planner tab, the place picker and the itinerary screen.
        val activity = LocalActivity.current as ViewModelStoreOwner
        val trip: TripViewModel = hiltViewModel(activity)
        CompositionLocalProvider(LocalTransit provides (state as? TransitState.Ready)?.data) {
            val top = stack.lastOrNull()
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets(0),
                bottomBar = { if (top in tabs) TabBar(top!!, nav) },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    if (state is TransitState.Failed) {
                        LoadFailed((state as TransitState.Failed).message)
                        return@Box
                    }
                    NavDisplay(
                        backStack = stack,
                        onBack = { nav.back() },
                        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
                        transitionSpec = { (slideInHorizontally { it / 6 } + fadeIn()) togetherWith fadeOut() },
                        popTransitionSpec = { fadeIn() togetherWith (slideOutHorizontally { it / 6 } + fadeOut()) },
                        predictivePopTransitionSpec = { fadeIn() togetherWith (slideOutHorizontally { it / 6 } + fadeOut()) },
                        entryProvider = entryProvider {
                            entry<NearbyKey> { HomeScreen(nav) }
                            entry<TripKey> { TripScreen(nav, trip) }
                            entry<SavedKey> { SavedScreen(nav) }
                            entry<SettingsKey> { SettingsScreen() }
                            entry<SearchKey> { key -> SearchScreen(nav, key.mode, trip) }
                            entry<StopKey> { key -> StopScreen(nav, key.id, trip) }
                            entry<RouteKey> { key -> RouteScreen(nav, key.id) }
                            entry<ItineraryKey> { key -> ItineraryScreen(nav, trip, key.index) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TabBar(current: NavKey, nav: Navigator) {
    // Tab labels are single words; capping their scale keeps all four readable at the largest
    // system text sizes instead of wrapping into fragments. Content elsewhere scales fully.
    val density = androidx.compose.ui.platform.LocalDensity.current
    CompositionLocalProvider(LocalDensity provides androidx.compose.ui.unit.Density(density.density, density.fontScale.coerceAtMost(1.3f))) {
        TabBarContent(current, nav)
    }
}

@Composable
private fun TabBarContent(current: NavKey, nav: Navigator) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = androidx.compose.ui.unit.Dp(0f)) {
        TabItem(current == NearbyKey, Icons.Rounded.NearMe, Icons.Outlined.NearMe, R.string.tab_nearby) { nav.tab(NearbyKey) }
        TabItem(current == TripKey, Icons.Rounded.Directions, Icons.Outlined.Directions, R.string.tab_trip) { nav.tab(TripKey) }
        TabItem(current == SavedKey, Icons.Rounded.Bookmark, Icons.Outlined.BookmarkBorder, R.string.tab_saved) { nav.tab(SavedKey) }
        TabItem(current == SettingsKey, Icons.Rounded.Settings, Icons.Outlined.Settings, R.string.tab_settings) { nav.tab(SettingsKey) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TabItem(selected: Boolean, on: ImageVector, off: ImageVector, label: Int, onClick: () -> Unit) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(if (selected) on else off, contentDescription = null) },
        label = { Text(stringResource(label), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
            indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    )
}
