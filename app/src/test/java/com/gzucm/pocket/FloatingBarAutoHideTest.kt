package com.gzucm.pocket

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.gzucm.pocket.ui.FloatingBarAlphaKey
import com.gzucm.pocket.ui.MainScaffold
import com.gzucm.pocket.ui.Tab
import com.gzucm.pocket.ui.components.floatingBarGlassAlpha
import com.gzucm.pocket.ui.theme.GzucmTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 悬浮底栏自动渐隐（致辉 2026-11-18）。
 *
 * 约束逐条钉住：
 *  1. **只有课表页**静置 3 秒渐隐到 15%，今日 / 成绩 / 电费永远全亮；
 *  2. 半透明时第一次点只唤醒不切页（防止看不清时误触）；唤醒后再点才切页；
 *  3. 深浅色用不同的毛玻璃 alpha。
 *
 * 计时走 `delay(3000)`，Robolectric 要用 ShadowLooper 推主线程时钟，
 * 光 waitForIdle 不会让 delay 走完（同 ScreenshotTest 的 settleMs 做法）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h874dp-xhdpi")
class FloatingBarAutoHideTest {

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
        // Compose 的 delay 走测试自己的 MonotonicFrameClock，得推 mainClock；
        // 光推 Android 主线程 Looper 是不会让 LaunchedEffect 里的 delay 走完的。
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun barAlpha(): Float =
        rule.onNodeWithTag("floating_bar").fetchSemanticsNode().config[FloatingBarAlphaKey]

    @Test
    fun `课表页静置三秒渐隐到百分之十五`() {
        render(Tab.Schedule)
        assertEquals("刚进页面底栏全亮", 1f, barAlpha(), 0.02f)

        settle(3200L)   // 3000ms 计时 + 260ms 动画
        assertEquals("静置后要淡到 15%", 0.15f, barAlpha(), 0.02f)
    }

    @Test
    fun `今日页不会渐隐`() {
        render(Tab.Today)
        settle(4200L)
        assertEquals("非课表页底栏必须一直全亮", 1f, barAlpha(), 0.02f)
    }

    @Test
    fun `成绩页不会渐隐`() {
        render(Tab.Workbench)
        settle(4200L)
        assertEquals("成绩页底栏不该渐隐", 1f, barAlpha(), 0.02f)
    }

    @Test
    fun `电费页不会渐隐`() {
        render(Tab.Power)
        settle(4200L)
        assertEquals("电费页底栏不该渐隐", 1f, barAlpha(), 0.02f)
    }

    @Test
    fun `半透明时第一次点只唤醒不切页`() {
        val selected = mutableListOf<Tab>()
        render(Tab.Schedule) { selected.add(it) }
        settle(3200L)
        assertEquals(0.15f, barAlpha(), 0.02f)

        // 第一次点：只唤醒
        rule.onNodeWithTag("tab_Today").performClick()
        settle(320L)
        assertTrue("看不清时点一下不该直接切走页面", selected.isEmpty())
        assertEquals("但底栏要重新亮起来", 1f, barAlpha(), 0.02f)

        // 第二次点：正常切页
        rule.onNodeWithTag("tab_Today").performClick()
        rule.waitForIdle()
        assertEquals("唤醒后再点才切页", listOf(Tab.Today), selected)
    }

    @Test
    fun `深浅色毛玻璃透明度分开`() {
        assertEquals("深色底色要更实", 0.86f, floatingBarGlassAlpha(true), 0.001f)
        assertEquals("浅色别做成死白", 0.80f, floatingBarGlassAlpha(false), 0.001f)
        assertTrue(floatingBarGlassAlpha(true) > floatingBarGlassAlpha(false))
    }
}
