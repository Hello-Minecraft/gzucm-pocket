package com.gzucm.pocket

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.gzucm.pocket.ui.MainScaffold
import com.gzucm.pocket.ui.adaptive.LocalContentLeadingInset
import com.gzucm.pocket.ui.adaptive.NavRailAlphaKey
import com.gzucm.pocket.ui.Tab
import com.gzucm.pocket.ui.theme.GzucmTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 平板侧栏的自动渐隐（致辉 2026-11-21 要求「策略跟手机端一样」）。
 *
 * 把手机端底栏那三条约束原样钉在侧栏上：
 *  1. **只有课表页**静置 3 秒淡到 15%，今日 / 工作台 / 电费永远全亮；
 *  2. 半透明时第一次点只唤醒不切页；
 *  3. 侧栏本身仍然占 76dp（不做收起动画 —— 宽度一变整列课会跳）。
 *
 * 宽度用 `w1280dp` 才会走 [com.gzucm.pocket.ui.adaptive.AppLayout.Tablet] 那套
 * `WideScaffold`；手机宽度下根本没有侧栏可测。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-xhdpi")
class NavRailAutoHideTest {

    @get:Rule
    val rule = createComposeRule()

    private fun render(tab: Tab = Tab.Schedule, onSelect: (Tab) -> Unit = {}) {
        rule.setContent {
            GzucmTheme {
                MainScaffold(tab = tab, onSelect = onSelect) {
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        rule.waitForIdle()
    }

    private fun settle(ms: Long) {
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun railAlpha(): Float =
        rule.onNodeWithTag("nav_rail").fetchSemanticsNode().config[NavRailAlphaKey]

    @Test
    fun `课表页静置三秒侧栏渐隐到百分之十五`() {
        render(Tab.Schedule)
        assertEquals("刚进课表页侧栏全亮", 1f, railAlpha(), 0.02f)

        settle(3200L)   // 3000ms 计时 + 260ms 动画
        assertEquals("静置后要淡到 15%", 0.15f, railAlpha(), 0.02f)
    }

    @Test
    fun `今日页侧栏不渐隐`() {
        render(Tab.Today)
        settle(4200L)
        assertEquals("非课表页侧栏必须一直全亮", 1f, railAlpha(), 0.02f)
    }

    @Test
    fun `工作台侧栏不渐隐`() {
        render(Tab.Workbench)
        settle(4200L)
        assertEquals("工作台侧栏不该渐隐", 1f, railAlpha(), 0.02f)
    }

    @Test
    fun `课表页内容让开侧栏而背景仍铺满全屏`() {
        var bg: androidx.compose.ui.geometry.Rect? = null
        var content: androidx.compose.ui.geometry.Rect? = null
        rule.setContent {
            GzucmTheme {
                MainScaffold(tab = Tab.Schedule, onSelect = {}) {
                    val inset = LocalContentLeadingInset.current
                    // 复刻课表页的两层：壁纸铺满，内容列右移
                    Box(Modifier.fillMaxSize()) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.Blue)
                                .onGloballyPositioned { bg = it.boundsInRoot() },
                        )
                        Box(
                            Modifier
                                .fillMaxSize()
                                .padding(start = inset)
                                .background(Color.Red)
                                .onGloballyPositioned { content = it.boundsInRoot() },
                        )
                    }
                }
            }
        }
        rule.waitForIdle()

        val density = rule.density.density
        val b = bg ?: error("背景层没布局")
        val c = content ?: error("内容区没布局")

        assertEquals("壁纸必须从屏幕最左边开始", 0f, b.left / density, 1f)
        assertEquals("壁纸宽度是整个窗口", 1280f, b.width / density, 2f)
        assertEquals("内容要让开侧栏 76dp", 76f, c.left / density, 1f)
        assertEquals(
            "内容右边界不该被撑出屏幕",
            1280f,
            (c.right) / density,
            2f,
        )
    }

    @Test
    fun `半透明时第一次点侧栏只唤醒不切页`() {
        val selected = mutableListOf<Tab>()
        render(Tab.Schedule) { selected.add(it) }
        settle(3200L)
        assertEquals(0.15f, railAlpha(), 0.02f)

        // 第一项是「今日」，课表页时点它本来会切页
        rule.onNodeWithTag("nav_item_0").performClick()
        settle(320L)
        assertTrue("看不清时点一下不该直接切走页面", selected.isEmpty())
        assertEquals("但侧栏要重新亮起来", 1f, railAlpha(), 0.02f)

        rule.onNodeWithTag("nav_item_0").performClick()
        rule.waitForIdle()
        assertEquals("唤醒后再点才切页", listOf(Tab.Today), selected)
    }
}
