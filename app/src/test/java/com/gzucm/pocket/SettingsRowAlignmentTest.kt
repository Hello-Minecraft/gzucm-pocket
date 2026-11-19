package com.gzucm.pocket

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.gzucm.pocket.ui.settings.SettingsScreen
import com.gzucm.pocket.ui.theme.GzucmTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * 设置页底部几行的对齐（2026-11-18，致辉发现「重新登录」左右不对齐）。
 *
 * 起因是给「重新登录」做抖动高亮时，在包裹它的 Box 上加了 4dp 水平 padding，
 * 连带把里面的 ActionRow 也缩进去 —— 图标、文字、右箭头都比相邻行内缩 4dp。
 * 高亮和排版不能共用一层 padding，这里钉住文字左边缘必须一致（右侧箭头靠
 * ActionRow 自己的 16dp padding，行宽都一样，左边对齐了右边自然对齐）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h874dp-xhdpi")
class SettingsRowAlignmentTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `重新登录与相邻行的文字左边缘对齐`() {
        rule.setContent {
            GzucmTheme {
                SettingsScreen(
                    onSync = {}, onLogout = {}, onClose = {},
                    lastSyncText = "11-18 02:00",
                    semesterText = "2026-2027 学年 第一学期",
                )
            }
        }
        rule.waitForIdle()

        val labels = listOf("立即同步", "重新登录", "退出登录", "导出课表数据")
        val lefts = labels.map { rule.onNodeWithText(it).getUnclippedBoundsInRoot().left.value }
        lefts.forEachIndexed { i, left ->
            assertTrue(
                "「${labels[i]}」左边缘偏了 ${left - lefts[0]}dp（应为 0）",
                abs(left - lefts[0]) < 0.5f,
            )
        }
    }
}
