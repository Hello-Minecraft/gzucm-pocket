package com.gzucm.pocket.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * 教程页预览用的部件配色（浅 / 深两套）。
 *
 * ## 为什么不用 `colorResource(R.color.widget_*)`
 *
 * 那些资源在 `res/values` 与 `res/values-night` 各一套，跟的是**系统夜间模式**；
 * 而 App 的深色是**App 内部开关**（设置 → 深色模式：跟随系统 / 深色 / 浅色）。
 * 系统是浅色、App 切了深色时，页面黑了而预览卡片还是白的，非常刺眼。
 * 所以预览要按**当前 Compose 主题**选色 —— 数值照抄两套颜色资源，一个字不改。
 *
 * 桌面部件本身继续跟随系统夜间（XML / drawable 里仍是 `@color/widget_*`），
 * 这是正确行为：宿主重建 RemoteViews 时不会回调代码，运行时切色不生效。
 *
 * ## 新增颜色时
 *
 * 必须同时改 `res/values/colors.xml`、`res/values-night/colors.xml` 和这里的两套，
 * 三处数值保持一致。
 */
internal data class WidgetPalette(
    val bg: Color,
    val text: Color,
    val textDim: Color,
    val textWeak: Color,
    val accent: Color,
    /** 周视图「当前节次」那一行的整行底色（主题色低透明度叠层） */
    val weekRowNow: Color,
    /** 8 组课程色：普通块底 / 正在上 / 块内文字 / 圆点 */
    val kBg: List<Color>,
    val kNow: List<Color>,
    val kText: List<Color>,
    val kDot: List<Color>,
) {
    companion object {
        /** = res/values/colors.xml（与 Compose LightColors 对齐） */
        val Light = WidgetPalette(
            bg = Color(0xFFFFFFFF),
            text = Color(0xFF14181C),
            textDim = Color(0xFF575F67),
            textWeak = Color(0xFF8A9098),
            accent = Color(0xFF1D9E75),
            weekRowNow = Color(0x141D9E75),
            kBg = listOf(
                Color(0xFFDDF0EA), Color(0xFFE1EEFA), Color(0xFFECEBFA), Color(0xFFF9E5EC),
                Color(0xFFF6E7E2), Color(0xFFF6EADC), Color(0xFFDBF0F3), Color(0xFFE8F0DE),
            ),
            kNow = listOf(
                Color(0xFFC4E6DB), Color(0xFFCBE1F6), Color(0xFFDEDCF6), Color(0xFFF4D2DE),
                Color(0xFFEFD6CC), Color(0xFFEDDBC3), Color(0xFFC1E5EB), Color(0xFFD6E5C6),
            ),
            kText = listOf(
                Color(0xFF0F5C44), Color(0xFF1B4E80), Color(0xFF4A3F8C), Color(0xFF8C2B4A),
                Color(0xFF7A3A22), Color(0xFF6B420C), Color(0xFF0A5A66), Color(0xFF3A5A12),
            ),
            kDot = listOf(
                Color(0xFF1D9E75), Color(0xFF378ADD), Color(0xFF7F77DD), Color(0xFFD4537E),
                Color(0xFFC2603C), Color(0xFFBA7517), Color(0xFF0F9BB0), Color(0xFF639922),
            ),
        )

        /** = res/values-night/colors.xml（与 Compose DarkColors 对齐） */
        val Dark = WidgetPalette(
            bg = Color(0xFF12151A),
            text = Color(0xFFE9EDF0),
            textDim = Color(0xFF9AA3AE),
            textWeak = Color(0xFF79828C),
            accent = Color(0xFF4ADE9B),
            weekRowNow = Color(0x1F4ADE9B),
            kBg = listOf(
                Color(0xFF14302C), Color(0xFF192C41), Color(0xFF282941), Color(0xFF39212E),
                Color(0xFF352421), Color(0xFF342819), Color(0xFF113038), Color(0xFF222F1C),
            ),
            kNow = listOf(
                Color(0xFF153932), Color(0xFF1C334D), Color(0xFF2E2E4D), Color(0xFF442534),
                Color(0xFF402823), Color(0xFF3E2E19), Color(0xFF113841), Color(0xFF27371C),
            ),
            kText = listOf(
                Color(0xFF7FE3B8), Color(0xFFA8CCF2), Color(0xFFC4C0F2), Color(0xFFF5B4C8),
                Color(0xFFEFB19C), Color(0xFFF5BC63), Color(0xFF8CE3E9), Color(0xFFB6D986),
            ),
            kDot = listOf(
                Color(0xFF4ADE9B), Color(0xFF85B7EB), Color(0xFFAFA9EC), Color(0xFFED93B1),
                Color(0xFFE09277), Color(0xFFEF9F27), Color(0xFF5CD3DC), Color(0xFF97C459),
            ),
        )
    }
}

/**
 * 按**当前 Compose 主题**取预览配色。
 *
 * 判据用 `background.luminance()` 而不是 ThemeState：截图测试直接给
 * `GzucmTheme` 传 `darkTheme`，并不改 ThemeState（见 PITFALLS 第十七节）。
 * 读的是 `MaterialTheme`，App 里切换深色开关会重组，立即生效，不用重启。
 */
@Composable
internal fun widgetPalette(): WidgetPalette =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        WidgetPalette.Dark
    } else {
        WidgetPalette.Light
    }
