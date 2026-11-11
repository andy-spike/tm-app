package com.tmapp.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tmapp.android.R
import com.tmapp.data.ThemeMode
import com.tmapp.transit.Route
import com.tmapp.transit.SystemKind

/**
 * Source Sans 3: a humanist sans in the Frutiger family that Swiss transport signage made the
 * reference for wayfinding. One variable file covers every weight.
 */
private fun sans(weight: Int) = Font(R.font.source_sans_3, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val Sans = FontFamily(sans(400), sans(500), sans(600), sans(700), sans(800))

/** TransMilenio's two system colours: red for the trunk (BRT) network, blue for zonal SITP. */
object Brand {
    val red = Color(0xFFD0101E)
    val redDeep = Color(0xFFA80C17)
    val blue = Color(0xFF0B5CAD)
}

/** Colours beyond Material roles: hairlines, scheduled-data signal, the red header band. */
@Immutable
data class Extra(
    val hairline: Color,
    val subtle: Color,
    val scheduled: Color,
    val walk: Color,
    val header: Color,
    val onHeader: Color,
    val onHeaderMuted: Color,
    val station: Color,
    val stop: Color,
    val isDark: Boolean,
)

val LocalExtra = staticCompositionLocalOf { lightExtra }

// Light: SBB's "milk" grey ground with white cards; red is reserved for the header band,
// primary actions and the current selection.
private val lightScheme = lightColorScheme(
    primary = Brand.red,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFDE7E8),
    onPrimaryContainer = Color(0xFF5E0309),
    secondary = Brand.blue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2EDF9),
    onSecondaryContainer = Color(0xFF00264F),
    tertiary = Color(0xFF151515),
    onTertiary = Color.White,
    background = Color(0xFFF1F1F0),
    onBackground = Color(0xFF151515),
    surface = Color.White,
    onSurface = Color(0xFF151515),
    surfaceVariant = Color(0xFFEAEAE9),
    onSurfaceVariant = Color(0xFF5F5F5E),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFAFA),
    surfaceContainer = Color(0xFFF4F4F3),
    surfaceContainerHigh = Color(0xFFEBEBEA),
    surfaceContainerHighest = Color(0xFFE1E1E0),
    outline = Color(0xFFC9C9C8),
    outlineVariant = Color(0xFFE3E3E2),
    error = Color(0xFFB3261E),
)

// Dark: charcoal ground, raised grey cards; the red lifts to stay legible as text.
private val darkScheme = darkColorScheme(
    primary = Color(0xFFFF6B6F),
    onPrimary = Color(0xFF410006),
    primaryContainer = Color(0xFF5A0C12),
    onPrimaryContainer = Color(0xFFFFDADB),
    secondary = Color(0xFF8CC1FF),
    onSecondary = Color(0xFF00315F),
    secondaryContainer = Color(0xFF173A60),
    onSecondaryContainer = Color(0xFFD5E6FF),
    tertiary = Color(0xFFF2F2F2),
    onTertiary = Color(0xFF151515),
    background = Color(0xFF0E0E0E),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF1C1C1C),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFB0B0AF),
    surfaceContainerLowest = Color(0xFF1C1C1C),
    surfaceContainerLow = Color(0xFF181818),
    surfaceContainer = Color(0xFF222222),
    surfaceContainerHigh = Color(0xFF2A2A2A),
    surfaceContainerHighest = Color(0xFF333333),
    outline = Color(0xFF4A4A4A),
    outlineVariant = Color(0xFF303030),
    error = Color(0xFFF2B8B5),
)

private val lightExtra = Extra(
    hairline = Color(0xFFE6E6E5), subtle = Color(0xFF8A8A89), scheduled = Color(0xFFC77700), walk = Color(0xFF8A8A89),
    header = Brand.red, onHeader = Color.White, onHeaderMuted = Color(0xFFFFD6D8),
    station = Brand.red, stop = Brand.blue, isDark = false,
)
private val darkExtra = Extra(
    hairline = Color(0xFF2E2E2E), subtle = Color(0xFF8D8D8C), scheduled = Color(0xFFF2B14C), walk = Color(0xFF9A9A99),
    header = Color(0xFF9E0E18), onHeader = Color.White, onHeaderMuted = Color(0xFFF5C2C5),
    station = Color(0xFFC81420), stop = Color(0xFF1F6FC2), isDark = true,
)

private fun typography(): Typography {
    // Tabular figures everywhere: times line up in columns like a departure board.
    val base = TextStyle(fontFamily = Sans, letterSpacing = 0.sp, fontFeatureSettings = "tnum")
    val bold = FontWeight.Bold
    return Typography(
        displayLarge = base.copy(fontSize = 54.sp, lineHeight = 54.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.5).sp),
        displayMedium = base.copy(fontSize = 42.sp, lineHeight = 46.sp, fontWeight = bold, letterSpacing = (-1).sp),
        displaySmall = base.copy(fontSize = 32.sp, lineHeight = 36.sp, fontWeight = bold, letterSpacing = (-0.6).sp),
        headlineLarge = base.copy(fontSize = 28.sp, lineHeight = 32.sp, fontWeight = bold, letterSpacing = (-0.4).sp),
        headlineMedium = base.copy(fontSize = 24.sp, lineHeight = 28.sp, fontWeight = bold, letterSpacing = (-0.3).sp),
        headlineSmall = base.copy(fontSize = 21.sp, lineHeight = 26.sp, fontWeight = bold, letterSpacing = (-0.2).sp),
        titleLarge = base.copy(fontSize = 19.sp, lineHeight = 24.sp, fontWeight = bold),
        titleMedium = base.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = base.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = base.copy(fontSize = 17.sp, lineHeight = 24.sp),
        bodyMedium = base.copy(fontSize = 15.sp, lineHeight = 21.sp),
        bodySmall = base.copy(fontSize = 13.5.sp, lineHeight = 18.sp),
        labelLarge = base.copy(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = base.copy(fontSize = 13.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold),
        labelSmall = base.copy(fontSize = 12.sp, lineHeight = 15.sp, fontWeight = bold, letterSpacing = 0.8.sp),
    )
}

private val appTypography = typography()

private val appShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun TmTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    CompositionLocalProvider(LocalExtra provides if (dark) darkExtra else lightExtra) {
        MaterialTheme(colorScheme = if (dark) darkScheme else lightScheme, typography = appTypography, shapes = appShapes, content = content)
    }
}

/**
 * Status-bar icon colour for the screen on show: light icons over the red header band, otherwise
 * the theme's. Screens with a band call this with `true`.
 */
@Composable
fun SystemBars(lightIcons: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    val dark = LocalExtra.current.isDark
    if (view.isInEditMode) return
    androidx.compose.runtime.SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark && !lightIcons
            isAppearanceLightNavigationBars = !dark
        }
    }
}

object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val gutter = 16.dp
}

/**
 * Badge colours. The feed's own colours are placeholders (every trunk route is #ff0000), so trunk
 * routes use approximations of TransMilenio's corridor colours by destination letter, and zonal
 * services use one colour per system.
 */
object RouteColors {
    private val corridor = mapOf(
        'A' to Color(0xFF1F4E9C), 'B' to Color(0xFF5BA632), 'C' to Color(0xFFF2B705), 'D' to Color(0xFF6A3E98),
        'E' to Color(0xFF8C2A3A), 'F' to Color(0xFFD7262F), 'G' to Color(0xFF2C8FD0), 'H' to Color(0xFFEE7D1D),
        'J' to Color(0xFFC59B2C), 'K' to Color(0xFFA86B3C), 'L' to Color(0xFF138E8E), 'M' to Color(0xFFC85A9A),
        'S' to Color(0xFF4A4A4F), 'Z' to Color(0xFF4A4A4F),
    )

    fun background(route: Route): Color = when (route.system) {
        SystemKind.TRONCAL -> route.code.firstOrNull()?.let(corridor::get) ?: Brand.red
        SystemKind.ALIMENTADOR -> Color(0xFF2E9B48)
        SystemKind.URBANO -> Brand.blue
        SystemKind.COMPLEMENTARIO -> Color(0xFFE07A10)
        SystemKind.ESPECIAL -> Color(0xFF7B3FA0)
        SystemKind.DUAL -> Color(0xFF00897B)
        SystemKind.CABLE -> Color(0xFF0097A7)
        SystemKind.UNKNOWN -> Color(0xFF5D5A55)
    }

    fun content(background: Color): Color = if (background.luminance() > 0.45f) Color(0xFF151515) else Color.White
}
