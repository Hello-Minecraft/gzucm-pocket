package com.gzucm.pocket.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 应用布局档位。
 *
 * 阈值直接对齐 Android 官方的 Window Size Classes（Material 3 的推荐值），
 * 不从设备型号或屏幕英寸反推 —— 平板上开了分屏、或者自由窗口拖小了，
 * 可用宽度可能只剩 400dp，那时候就该退回手机布局，而不是继续硬撑三栏。
 *
 * 为什么自己定义而不引 material3-window-size-class：
 * 官方库只给 Compact / Medium / Expanded 三档，且会带进一串额外依赖。
 * 我们只需要一个纯函数把 dp 映射成档位，自己写六行就够，
 * 也让全部断点集中在这一处，改阈值只改一个地方。
 */
@Immutable
enum class AppLayout {
    /** < 600dp：手机竖屏。底部 Tab，单列内容。 */
    Phone,

    /** 600 ~ 839dp：手机横屏 / 小平板竖屏 / 分屏。仍是底部 Tab，但内容可以加宽。 */
    Compact,

    /** 840 ~ 1199dp：平板横屏主流尺寸（11 寸小米平板就在这一档）。左侧导航 + 内容区。 */
    Tablet,

    /** >= 1200dp：更大的平板 / 桌面模式。左侧导航 + 内容区 + 可容纳右侧面板。 */
    Large,
}

/**
 * 按可用窗口宽度解析布局档位。
 *
 * 传进来的必须是**当前 Composable 实际拿到的宽度**（`BoxWithConstraints` 的
 * `maxWidth`），不是 `LocalConfiguration.screenWidthDp` —— 后者在多窗口下
 * 反映的仍是整块屏幕，分屏时会算错。
 */
fun resolveLayout(width: Dp): AppLayout = when {
    width < 600.dp -> AppLayout.Phone
    width < 840.dp -> AppLayout.Compact
    width < 1200.dp -> AppLayout.Tablet
    else -> AppLayout.Large
}

/** 平板及以上的档位是否该用左侧导航栏替代底部 Tab。 */
val AppLayout.usesNavigationRail: Boolean
    get() = this == AppLayout.Tablet || this == AppLayout.Large

/** 内容区是否宽到可以承载多列。 */
val AppLayout.allowsMultiColumn: Boolean
    get() = this != AppLayout.Phone

/**
 * 左侧导航栏宽度。
 *
 * 76dp：图标 24dp 居中、下方 12sp 文字，左右各留 26dp 边距。
 * 比 Material 默认的 80dp 略窄，因为我们的标签是中文双字，不需要更宽。
 */
val NavigationRailWidth: Dp = 76.dp

/** 导航项的高度（图标 + 文字），供点击热区用。 */
val NavigationRailItemHeight: Dp = 64.dp

/**
 * 从当前可用宽度解析档位，供 Composable 直接用。
 *
 * 用法：外层套 `BoxWithConstraints`，然后 `val layout = rememberLayout(maxWidth)`。
 */
@Composable
fun rememberLayout(width: Dp): AppLayout = resolveLayout(width)