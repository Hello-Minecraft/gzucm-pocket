package com.gzucm.pocket

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import com.gzucm.pocket.core.TodayBlock
import com.gzucm.pocket.core.TodaySections
import com.gzucm.pocket.ui.settings.TodaySectionsSheet
import com.gzucm.pocket.ui.theme.GzucmTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 平板侧栏里的「长按拖动排序」（2026-11-22，致辉报「平板端排不了」）。
 *
 * ## 为什么单独一个文件
 *
 * 手机端这块一直能拖：它走 ModalBottomSheet，容器里**没有**垂直滚动。
 * 平板走 [com.gzucm.pocket.ui.adaptive.AdaptiveSheet] 的侧栏分支，正文默认套
 * `verticalScroll` —— 手指按下去还没到长按阈值，轻微抖动就超过 touch slop，
 * 外层滚动先把手势拿走，`detectDragGesturesAfterLongPress` 当场被取消。
 *
 * 修法是给这个面板传 `scrollable = false`。这条测试就钉这件事：
 * **在 1280dp（侧栏形态）下，长按拖动必须真的把块挪走**。
 *
 * 宽度必须 ≥ 840dp 才会走 RightSidePane；手机宽度下测的是另一条路径，
 * 测不到这个 bug。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-xhdpi")
class TodaySectionsPaneDragTest {

    @get:Rule
    val rule = createComposeRule()

    private fun render(onChange: (TodaySections) -> Unit) {
        rule.setContent {
            GzucmTheme {
                Box(Modifier.fillMaxSize()) {
                    TodaySectionsSheet(
                        visible = true,
                        onDismiss = {},
                        sections = TodaySections(),
                        onChange = onChange,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    /**
     * 长按 [tag] 这根拖动条，然后竖直移动 [dyPx] 像素再松手。
     *
     * 分多步移动，一把甩过去不行：拖动逻辑是**逐行**累加 dy 算落点的
     * （越过下一项一半才换位），一次性大跳会让 slop 和让位动画都算不准。
     */
    private fun dragBy(tag: String, dyPx: Float) {
        val steps = 8
        val step = dyPx / steps
        rule.onNodeWithTag(tag).performTouchInput {
            down(center)
            // 500ms 是 detectDragGesturesAfterLongPress 的阈值，多给 200ms 保稳
            advanceEventTime(700)
            repeat(steps) {
                moveBy(Offset(0f, step))
                advanceEventTime(24)
            }
            advanceEventTime(150)
            up()
        }
        rule.waitForIdle()
    }

    @Test
    fun `侧栏里能把本周概览拖到最前`() {
        var result: TodaySections? = null
        render { result = it }

        // 默认顺序 [天气, 电费, 课程, 待办, 概览, 接下来]，概览在第 5 行；往上拖 4 行
        dragBy("drag_week", -900f)

        val got = result ?: error("拖动没有提交任何改动的顺序")
        assertEquals(
            "拖到最上面就该排第一",
            TodayBlock.WEEK_OVERVIEW,
            got.allOrder.first(),
        )
        assertEquals("块数不能变", TodayBlock.entries.size, got.allOrder.size)
    }

    @Test
    fun `侧栏里把第一块往下拖也会换位`() {
        var result: TodaySections? = null
        render { result = it }

        // 天气在第 1 行，往下拖两行
        dragBy("drag_weather", 130f)

        val got = result ?: error("拖动没有提交任何改动的顺序")
        assertEquals(
            "天气应该不再是第一个",
            true,
            got.allOrder.first() != TodayBlock.WEATHER,
        )
        assertEquals("块数不能变", TodayBlock.entries.size, got.allOrder.size)
    }
}
