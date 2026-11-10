package com.tmapp.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tmapp.android.R
import com.tmapp.data.ThemeMode
import com.tmapp.transit.Route
import com.tmapp.transit.SystemKind

val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
    Font(R.font.geist_bold, FontWeight.Bold),
)
val GeistMono = FontFamily(
    Font(R.font.geist_mono_medium, FontWeight.Medium),
    Font(R.font.geist_mono_semibold, FontWeight.SemiBold),
)

/** Colours beyond Material roles: hairlines, scheduled-data signal, map tints. */
@Immutable
data class Extra(
    val hairline: Color,
    val subtle: Color,
    val scheduled: Color,
    val walk: Color,
    val isDark: Boolean,
)

val LocalExtra = staticCompositionLocalOf { lightExtra }

private val lightScheme = lightColorScheme(
    primary = Color(0xFF0E7C5B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5F2E6),
    onPrimaryContainer = Color(0xFF00382A),
    secondary = Color(0xFF121214),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDEBE7),
    onSecondaryContainer = Color(0xFF121214),
    background = Color(0xFFFBFAF8),
    onBackground = Color(0xFF121214),
    surface = Color(0xFFFBFAF8),
    onSurface = Color(0xFF121214),
    surfaceVariant = Color(0xFFF1EFEB),
    onSurfaceVariant = Color(0xFF5D5A55),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F6F3),
    surfaceContainer = Color(0xFFF3F1EE),
    surfaceContainerHigh = Color(0xFFEDEBE7),
    surfaceContainerHighest = Color(0xFFE7E4DF),
    outline = Color(0xFFCFCBC4),
    outlineVariant = Color(0xFFE6E3DE),
    error = Color(0xFFB3261E),
)

private val darkScheme = darkColorScheme(
    primary = Color(0xFF46D6A2),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF0B3D2E),
    onPrimaryContainer = Color(0xFFBFF3DE),
    secondary = Color(0xFFF3F2EF),
    onSecondary = Color(0xFF121214),
    secondaryContainer = Color(0xFF26262A),
    onSecondaryContainer = Color(0xFFF3F2EF),
    background = Color(0xFF0B0B0D),
    onBackground = Color(0xFFF3F2EF),
    surface = Color(0xFF0B0B0D),
    onSurface = Color(0xFFF3F2EF),
    surfaceVariant = Color(0xFF1C1C20),
    onSurfaceVariant = Color(0xFFA9A7A2),
    surfaceContainerLowest = Color(0xFF070708),
    surfaceContainerLow = Color(0xFF121215),
    surfaceContainer = Color(0xFF16161A),
    surfaceContainerHigh = Color(0xFF1C1C20),
    surfaceContainerHighest = Color(0xFF242428),
    outline = Color(0xFF45454B),
    outlineVariant = Color(0xFF2A2A2F),
    error = Color(0xFFF2B8B5),
)

private val lightExtra = Extra(Color(0xFFE6E3DE), Color(0xFF8A867F), Color(0xFFC77700), Color(0xFF8A867F), false)
private val darkExtra = Extra(Color(0xFF2A2A2F), Color(0xFF8D8B87), Color(0xFFF2B14C), Color(0xFF9A9893), true)

private fun typography(): Typography {
    val base = TextStyle(fontFamily = Geist, letterSpacing = 0.sp)
    return Typography(
        displayLarge = base.copy(fontSize = 56.sp, lineHeight = 56.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-2).sp),
        displayMedium = base.copy(fontSize = 44.sp, lineHeight = 46.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1.5).sp),
        displaySmall = base.copy(fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp),
        headlineLarge = base.copy(fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp),
        headlineMedium = base.copy(fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp),
        headlineSmall = base.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
        titleLarge = base.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        titleMedium = base.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
        titleSmall = base.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        bodyLarge = base.copy(fontSize = 17.sp, lineHeight = 24.sp),
        bodyMedium = base.copy(fontSize = 15.sp, lineHeight = 21.sp),
        bodySmall = base.copy(fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = base.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        labelMedium = base.copy(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontFamily = GeistMono, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.4.sp),
    )
}

private val appTypography = typography()

@Composable
fun TmTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalExtra provides if (dark) darkExtra else lightExtra) {
        MaterialTheme(colorScheme = if (dark) darkScheme else lightScheme, typography = appTypography, content = content)
    }
}

object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val gutter = 20.dp
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
        SystemKind.TRONCAL -> route.code.firstOrNull()?.let(corridor::get) ?: Color(0xFFD7262F)
        SystemKind.ALIMENTADOR -> Color(0xFF2E9B48)
        SystemKind.URBANO -> Color(0xFF1C64B8)
        SystemKind.COMPLEMENTARIO -> Color(0xFFE07A10)
        SystemKind.ESPECIAL -> Color(0xFF7B3FA0)
        SystemKind.DUAL -> Color(0xFF00897B)
        SystemKind.CABLE -> Color(0xFF0097A7)
        SystemKind.UNKNOWN -> Color(0xFF5D5A55)
    }

    fun content(background: Color): Color = if (background.luminance() > 0.45f) Color(0xFF121214) else Color.White
}
