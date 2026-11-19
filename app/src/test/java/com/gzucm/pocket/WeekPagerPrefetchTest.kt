package com.gzucm.pocket

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import com.gzucm.pocket.core.ConflictDemo
import com.gzucm.pocket.ui.schedule.ScheduleScreen
import com.gzucm.pocket.ui.theme.GzucmTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 周课表「相邻页预排」必须真的打开（2026-11-17 修「翻页第一下不跟手」的回归抓手）。
 *
 * 背景：`beyondViewportPageCount` 默认 0，下一周要等手指拖动起来才组合，
 * 一页的组合全压在那一帧里 → 第一下拖不动。修法是首帧画完后再把它置 1。
 *
 * 断言方式：长按热区的 tag 带周次（`week-grid-longpress-{周}-{星期}`），
 * 数一下第 7 / 8 / 9 周的热区各在不在树上 —— 隔一周的第 6 周必须**不**在，
 * 否则说明预排开大了（会白白多组合两页）。
 *
 * ⚠️ 这条测试依赖「测试时钟会推帧」：`LaunchedEffect` 里的两次
 * `withFrameNanos {}` 只有在帧回调真的跑过之后才会置位。
 * Robolectric 下 `waitForIdle()` 会推帧，实测能过；哪天换了测试基建不推帧了，
 * 它会在**首屏**就报错，不会误报「预排没开」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h874dp-xhdpi")
class WeekPagerPrefetchTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `首帧之后左右相邻周进入组合树且只多一页`() {
        rule.setContent {
            GzucmTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize()) {
                    ScheduleScreen(
                        semester = ConflictDemo.semester(),
                        sessions = emptyList(),
                        currentWeek = 8,
                        viewingWeek = 8,
                        onWeekChange = {},
                        onCourseClick = {},
                    )
                }
            }
        }
        rule.waitForIdle()

        fun count(week: Int) =
            rule.onAllNodesWithTag("week-grid-longpress-$week-3").fetchSemanticsNodes().size

        assertEquals("当前页（第 8 周）必须在", 1, count(8))
        assertEquals("相邻页（第 7 周）应已预排", 1, count(7))
        assertEquals("相邻页（第 9 周）应已预排", 1, count(9))
        assertEquals("只预排相邻一周，第 6 周不该在", 0, count(6))
    }
}
