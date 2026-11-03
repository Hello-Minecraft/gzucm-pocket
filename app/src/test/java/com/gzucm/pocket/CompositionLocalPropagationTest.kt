package com.gzucm.pocket

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import com.gzucm.pocket.ui.components.LocalCellOpacity
import com.gzucm.pocket.ui.components.LocalWallpaperBounds
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 磨砂用到的三个 CompositionLocal 必须是**动态**的。
 *
 * ## 2026-11-03 的事故
 *
 * 一开始写成了 `staticCompositionLocalOf`。静态版不登记读取方，
 * 值变了也不让读它的格子重组 —— 而壁纸坐标天然是「第一帧 null、
 * 布局完成后才有值」，于是格子永远读到 null，`frostedBackdrop`
 * 直接 `return this`，整条磨砂链路**静默失效**。
 *
 * 表现是「完全看不出模糊」，但代码一路看着都对，截图也拍不出异常，
 * 极难查。这两个 Local 值都是运行中会变的，必须动态。
 *
 * 下面两条就是钉这个：值改了，读的人必须被通知到。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompositionLocalPropagationTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `格子不透明度变了要通知读取方`() {
        val opacity = mutableStateOf(0.40f)
        val seen = mutableListOf<Float>()

        rule.setContent {
            CompositionLocalProvider(LocalCellOpacity provides opacity.value) {
                // 必须在 @Composable 上下文里读；SideEffect 的 lambda 不是 composable
                val v = LocalCellOpacity.current
                SideEffect { seen += v }
            }
        }
        rule.waitForIdle()

        opacity.value = 0.90f
        rule.waitForIdle()

        assertTrue(
            "滑块改到 0.90 但格子没被通知（读到的是 ${seen.lastOrNull()}）—— " +
                "LocalCellOpacity 是不是被改成 staticCompositionLocalOf 了？",
            seen.contains(0.90f),
        )
    }

    @Test
    fun `壁纸坐标从空变成有值要通知读取方`() {
        // 真实场景就是这样：第一帧 boundsInRoot 还没算出来，是 null
        val bounds = mutableStateOf<Rect?>(null)
        val seen = mutableListOf<Rect?>()

        rule.setContent {
            CompositionLocalProvider(LocalWallpaperBounds provides bounds.value) {
                val v = LocalWallpaperBounds.current
                SideEffect { seen += v }
            }
        }
        rule.waitForIdle()

        bounds.value = Rect(0f, 0f, 100f, 200f)
        rule.waitForIdle()

        assertTrue(
            "壁纸坐标更新后格子必须收到，否则磨砂永远画不出来",
            seen.any { it != null && it.width == 100f },
        )
    }
}
