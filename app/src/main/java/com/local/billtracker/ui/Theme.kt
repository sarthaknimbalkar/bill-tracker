package com.local.billtracker

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class StatusColors(val due: Color, val overdue: Color, val paid: Color, val paused: Color, val dueBg: Color, val overdueBg: Color, val paidBg: Color)

val LocalStatus = staticCompositionLocalOf { darkStatus }

private val darkStatus = StatusColors(
    due = Color(0xFF7DA2FF), overdue = Color(0xFFFF6B6B), paid = Color(0xFF4ADE80), paused = Color(0xFF9AA3B2),
    dueBg = Color(0xFF1E2A4A), overdueBg = Color(0xFF3A1D22), paidBg = Color(0xFF173226),
)
private val lightStatus = StatusColors(
    due = Color(0xFF3557D6), overdue = Color(0xFFD93A3A), paid = Color(0xFF1F9D55), paused = Color(0xFF6B7280),
    dueBg = Color(0xFFE6ECFF), overdueBg = Color(0xFFFDE8E8), paidBg = Color(0xFFE3F6EA),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF7DA2FF), onPrimary = Color(0xFF0B1A47),
    primaryContainer = Color(0xFF2A3FB0), onPrimaryContainer = Color(0xFFDDE5FF),
    secondaryContainer = Color(0xFF232A3B), onSecondaryContainer = Color(0xFFD7DEEE),
    background = Color(0xFF0E1116), onBackground = Color(0xFFE6E8EC),
    surface = Color(0xFF0E1116), onSurface = Color(0xFFE6E8EC),
    surfaceVariant = Color(0xFF1F242D), onSurfaceVariant = Color(0xFF9AA3B2),
    surfaceContainerLowest = Color(0xFF0A0C10), surfaceContainerLow = Color(0xFF13171D),
    surfaceContainer = Color(0xFF171B22), surfaceContainerHigh = Color(0xFF1E232B), surfaceContainerHighest = Color(0xFF262C35),
    outline = Color(0xFF3A414D), outlineVariant = Color(0xFF262C35),
    error = Color(0xFFFF6B6B),
)

private val Light = lightColorScheme(
    primary = Color(0xFF3557D6), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE5FF), onPrimaryContainer = Color(0xFF0B1A47),
    secondaryContainer = Color(0xFFE8ECF5), onSecondaryContainer = Color(0xFF1F2633),
    background = Color(0xFFF6F7FA), onBackground = Color(0xFF14171C),
    surface = Color(0xFFF6F7FA), onSurface = Color(0xFF14171C),
    surfaceVariant = Color(0xFFE9ECF2), onSurfaceVariant = Color(0xFF5F6878),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFBFCFD),
    surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFF0F2F6), surfaceContainerHighest = Color(0xFFE6E9EF),
    outline = Color(0xFFCBD1DB), outlineVariant = Color(0xFFE3E7ED),
    error = Color(0xFFD93A3A),
)

private val base = Typography()
private val AppType = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(letterSpacing = 0.2.sp),
)

val Mono = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun AppTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
    androidx.compose.runtime.CompositionLocalProvider(LocalStatus provides if (dark) darkStatus else lightStatus) {
        MaterialTheme(
            colorScheme = if (dark) Dark else Light,
            typography = AppType,
            shapes = Shapes(
                small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp),
                large = RoundedCornerShape(22.dp), extraLarge = RoundedCornerShape(28.dp),
            ),
            content = content,
        )
    }
}
