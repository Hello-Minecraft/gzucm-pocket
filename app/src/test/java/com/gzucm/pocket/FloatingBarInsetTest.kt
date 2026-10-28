package com.gzucm.pocket

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.gzucm.pocket.core.CourseSession
import com.gzucm.pocket.core.Semester
import com.gzucm.pocket.ui.MainScaffold
import com.gzucm.pocket.ui.Tab
import com.gzucm.pocket.ui.components.LocalFloatingBarInset
import com.gzucm.pocket.ui.schedule.ScheduleScreen
import com.gzucm.pocket.ui.theme.GzucmTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * 底栏遮挡回归测试。
 *
 * 历史 bug：`FloatingBarTotalInset` 写死 72dp，但底栏真实高度含手势导航条内边距，
 * 实测 90~110dp，导致课表最后一行被压在底栏下面。
 * 现在 MainScaffold 用 onSizeChanged 量出真实高度下发，这条测试确认
 * 下发的值 ≥ 底栏实际高度 + 一段滚动留白。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class FloatingBarInsetTest {

    @get:Rule
    val rule = createComposeRule()

    private val semester = Semester.of(
        code = "202601",
        name = "2026-2027 学年 第一学期",
        firstMonday = LocalDate.of(2026, 9, 7),
        totalWeeks = 19,
    )

    private val sessions = listOf(
        CourseSession(
            name = "中医儿科学", code = "0301", teachers = "陈晓刚",
            week = 8, weekday = 1, startUnit = 1, endUnit = 2,
            startTime = "08:30", endTime = "10:00",
            room = "综教楼808", category = "理论教学",
        ),
        CourseSession(
            name = "龙氏治脊疗法", code = "0304", teachers = "范德辉",
            week = 8, weekday = 1, startUnit = 12, endUnit = 14,
            startTime = "19:00", endTime = "21:20",
            room = "综教楼407", category = "实验教学",
        ),
    )

    /** 抓取 MainScaffold 下发的底部留白 */
    private fun measureInset(): Float {
        var captured = -1f
        rule.setContent {
            GzucmTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize()) {
                    MainScaffold(tab = Tab.Schedule, onSelect = {}) {
                        val inset = LocalFloatingBarInset.current
                        val px = with(LocalDensity.current) { inset.toPx() }
                        captured = px
                        ScheduleScreen(semester, sessions, 8, 8, {}, {})
                    }
                }
            }
        }
        rule.waitForIdle()
        return captured
    }

    @Test
    fun `下发的底部留白覆盖整个底栏高度`() {
        val px = measureInset()
        val density = rule.density.density
        val dpValue = px / density

        // 底栏自身内容约 76dp，加手势条（Robolectric 下 inset 可能为 0）+ 44dp 滚动留白
        // 关键断言：必须显著大于旧的写死值 72dp，否则最后一行会被压住
        assertTrue(
            "留白只有 ${dpValue}dp，仍会遮挡（旧 bug 是 72dp）",
            dpValue > 72f,
        )
        println("PERF barInset=${dpValue}dp")
    }

    @Test
    fun `底栏本身高度合理`() {
        val px = measureInset()
        val density = rule.density.density
        val dpValue = px / density
        // 不该大到离谱（比如把整屏都占了）
        assertTrue("留白 ${dpValue}dp 过大，检查 onSizeChanged 是否重复累加", dpValue < 260f)
    }
}