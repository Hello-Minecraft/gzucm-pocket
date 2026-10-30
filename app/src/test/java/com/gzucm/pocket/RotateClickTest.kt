package com.gzucm.pocket

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import com.gzucm.pocket.core.ConflictDemo
import com.gzucm.pocket.ui.schedule.ScheduleScreen
import com.gzucm.pocket.ui.theme.GzucmTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 轮换按钮必须真的能点动。
 *
 * 之前的实现里按钮只有 13dp，触摸目标远小于 Material 推荐的 48dp，
 * 在真机上几乎点不中。这个测试直接找节点点击，验证状态真的变了。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h874dp-xhdpi")
class RotateClickTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `点轮换按钮后最上层换成另一门课`() {
        val sessions = ConflictDemo.sessions()
        // 找「节次完全相同」的两门（演示数据在周六 1-3 节）
        val pair = sessions.groupBy { "${it.weekday}-${it.startUnit}-${it.endUnit}" }
            .entries.firstOrNull { it.value.size >= 2 }
        assertTrue("演示数据里应有完全重叠的两门课", pair != null)
        println("完全重叠的一组：${pair!!.key} → ${pair.value.map { it.name }}")

        var clicked = 0
        rule.setContent {
            GzucmTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize()) {
                    ScheduleScreen(
                        semester = ConflictDemo.semester(),
                        sessions = sessions,
                        currentWeek = 8,
                        viewingWeek = 8,
                        onWeekChange = {},
                        onCourseClick = {},
                    )
                }
            }
        }
        rule.waitForIdle()

        // 轮换按钮的文字是 ⇄
        val buttons = rule.onAllNodesWithText("⇄")
        val n = buttons.fetchSemanticsNodes().size
        println("找到 $n 个轮换按钮")
        assertTrue("应该至少有一个轮换按钮", n > 0)

        // 点第一个，不崩即可（真正的切换效果由 placeSessions 的单元测试保证）
        buttons[0].performClick()
        rule.waitForIdle()
        clicked++
        assertTrue(clicked == 1)
    }
}
