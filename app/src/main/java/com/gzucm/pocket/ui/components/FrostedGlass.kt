package com.gzucm.pocket.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint

/**
 * 悬浮底栏占位尺寸。
 * 只作为测量前的兜底值 —— 实际占位由 MainScaffold 量出底栏真实高度后
 * 通过 [LocalFloatingBarInset] 下发，因为底栏高度还包含手势导航条的内边距。
 */
val FloatingBarHeight = 58.dp
val FloatingBarBottomGap = 14.dp

/** 内容末尾额外留白：滚到尽头时内容能完全离开底栏遮挡区 */
val FloatingBarOverscroll = 60.dp

/**
 * 测量前的估计值 + 兜底下限。
 *
 * 真机上底栏高度随手势条/三键条差异很大（三键导航条可到 48dp），
 * 只按实测容易漏掉余量 —— 1.1.1 就还是被遮挡了一行课。
 * 所以这里是 max(实测值, 170dp)，宁可多留空白也不许被压住。
 */
val FloatingBarMinInset = 170.dp
val FloatingBarTotalInset = FloatingBarMinInset

/**
 * 底栏实际占用高度（含手势导航条）+ 额外滚动留白。
 * 各滚动页面用它做内容底部的 padding，就不会被底栏压住。
 */
val LocalFloatingBarInset = androidx.compose.runtime.compositionLocalOf { FloatingBarTotalInset }

/**
 * 真·backdrop blur。
 *
 * Compose 自带的 `Modifier.blur` 只能模糊**自身内容**，模糊不了「后方已经画好的东西」。
 * 底栏要透出后方滚动内容，必须用 backdrop blur —— 这里用 haze：
 * 底层内容挂在 `hazeSource`，底栏挂 `hazeChild`，后者会抓取前者已渲染的像素做模糊。
 *
 * Android 12+ 走 RenderEffect 硬解；12 以下 haze 自动降级为半透明+色调，观感仍连贯。
 */
@Composable
fun rememberHazeState(): HazeState = remember { HazeState() }

@Composable
fun glassStyle(alpha: Float = 0.72f): HazeStyle {
    val bg = MaterialTheme.colorScheme.background
    val surface = MaterialTheme.colorScheme.surface
    return remember(alpha, bg, surface) {
        HazeStyle(
            backgroundColor = surface.copy(alpha = alpha),
            tint = HazeTint(bg.copy(alpha = alpha)),
            blurRadius = 22.dp,
            noiseFactor = 0.06f,
        )
    }
}

/** 给 LazyColumn / verticalScroll 用的底部留白：正好等于悬浮底栏占的高度 */
fun floatingBarContentPadding(horizontal: Dp = 18.dp, extraBottom: Dp = 20.dp): PaddingValues =
    PaddingValues(
        start = horizontal,
        end = horizontal,
        top = 4.dp,
        bottom = FloatingBarTotalInset + extraBottom,
    )