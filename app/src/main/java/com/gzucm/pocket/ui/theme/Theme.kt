package com.gzucm.pocket.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private val Mint = Color(0xFF4ADE9B)
private val MintDeep = Color(0xFF1D9E75)
private val Iris = Color(0xFF9C8CFF)
private val Amber = Color(0xFFF5B454)
private val Coral = Color(0xFFFF8A72)

private val DarkColors = darkColorScheme(
    primary = Mint,
    onPrimary = Color(0xFF00281A),
    primaryContainer = Color(0xFF13392B),
    onPrimaryContainer = Mint,
    secondary = Iris,
    onSecondary = Color(0xFF1A1038),
    tertiary = Amber,
    background = Color(0xFF0A0C0F),
    onBackground = Color(0xFFE9EDF0),
    surface = Color(0xFF12151A),
    onSurface = Color(0xFFE9EDF0),
    surfaceVariant = Color(0xFF1A1E25),
    onSurfaceVariant = Color(0xFF9AA3AE),
    outline = Color(0xFF2A3038),
    outlineVariant = Color(0xFF1E242B),
    error = Coral,
    onError = Color(0xFF2B0A04),
)

private val LightColors = lightColorScheme(
    primary = MintDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3F5E4),
    onPrimaryContainer = Color(0xFF06281C),
    secondary = Color(0xFF5B4BD6),
    tertiary = Color(0xFF9A6410),
    background = Color(0xFFF6F7F9),
    onBackground = Color(0xFF14181C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14181C),
    surfaceVariant = Color(0xFFEDF0F4),
    onSurfaceVariant = Color(0xFF5C646E),
    outline = Color(0xFFD5DAE1),
)

val AppTypography = Typography(
    displaySmall = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Medium),
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
)

/** 课程色板：按课程名散列取色，稳定且不刺眼 */
val CoursePalette = listOf(
    Color(0xFF4ADE9B),
    Color(0xFF7FA8FF),
    Color(0xFF9C8CFF),
    Color(0xFFF5B454),
    Color(0xFFFF8A72),
    Color(0xFF56D4DD),
    Color(0xFFE88BC4),
    Color(0xFFA3D655),
)

fun courseColor(name: String): Color {
    if (name.isEmpty()) return CoursePalette[0]
    var h = 0
    for (c in name) h = h * 31 + c.code
    return CoursePalette[Math.floorMod(h, CoursePalette.size)]
}

@Composable
fun GzucmTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // 关键：系统栏图标颜色跟随**实际使用的主题**而不是系统设置。
            // 浅色主题 → 深色图标（状态栏白底），深色主题 → 浅色图标。
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
            // 状态栏/导航栏本身保持透明，让 Surface 的背景色透上来
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
    }
    MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
}
