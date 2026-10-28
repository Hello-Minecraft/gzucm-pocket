package com.gzucm.pocket

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import com.gzucm.pocket.core.CourseSession
import com.gzucm.pocket.core.FitnessGrade
import com.gzucm.pocket.core.FitnessItem
import com.gzucm.pocket.core.PowerDaily
import com.gzucm.pocket.core.PowerInfo
import com.gzucm.pocket.core.Semester
import com.gzucm.pocket.ui.grades.GradesScreen
import com.gzucm.pocket.ui.power.PowerScreen
import com.gzucm.pocket.ui.schedule.ScheduleScreen
import com.gzucm.pocket.ui.theme.GzucmTheme
import com.gzucm.pocket.ui.today.TodayScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * UI 冒烟测试：用 Robolectric 在 JVM 上真实组合 Compose 界面，
 * 目的是抓「运行时崩溃 / 布局异常 / 文案缺失」，不依赖模拟器。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class UiSmokeTest {

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
            name = "中医儿科学", code = "0301", teachers = "陈晓刚,郭书宁",
            week = 8, weekday = 1, startUnit = 1, endUnit = 2,
            startTime = "08:30", endTime = "10:00",
            room = "综教楼808（智慧课室）", category = "理论教学",
        ),
        CourseSession(
            name = "五官学", code = "0302", teachers = "廉丽华",
            week = 8, weekday = 1, startUnit = 3, endUnit = 4,
            startTime = "10:20", endTime = "11:50",
            room = "新课室楼302", category = "理论教学",
        ),
        CourseSession(
            name = "外科学(2)", code = "0303", teachers = "曾海平",
            week = 8, weekday = 3, startUnit = 7, endUnit = 9,
            startTime = "14:50", endTime = "17:10",
            room = "一附院结直肠外科", category = "实验教学",
        ),
        CourseSession(
            name = "龙氏治脊疗法", code = "0304", teachers = "范德辉",
            week = 8, weekday = 4, startUnit = 12, endUnit = 14,
            startTime = "19:00", endTime = "21:20",
            room = "综教楼407/409智能互动训练室", category = "实验教学",
        ),
    )

    private val power = PowerInfo(
        balance = 11.78, unit = "度", roomLabel = "三元里 6 栋 213",
        token = "node01test", schoolAreaNo = "2", buildingNo = "5754", roomNum = "5770",
    )

    private val daily = listOf(
        PowerDaily("2026-10-01", 0.18, 73.86, 28.80),
        PowerDaily("2026-10-02", 1.08, 72.78, 29.88),
        PowerDaily("2026-10-03", 1.78, 71.00, 31.66),
        PowerDaily("2026-10-04", 1.79, 69.21, 33.45),
        PowerDaily("2026-10-05", 2.88, 74.04, 28.62),
    )

    private fun set(block: @androidx.compose.runtime.Composable () -> Unit) {
        rule.setContent {
            GzucmTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize()) { block() }
            }
        }
    }

    @Test
    fun `课表页能渲染且显示课程名`() {
        set {
            ScheduleScreen(
                semester = semester,
                sessions = sessions,
                currentWeek = 8,
                viewingWeek = 8,
                onWeekChange = {},
                onCourseClick = {},
            )
        }
        rule.onNodeWithText("第 8 周").assertIsDisplayed()
        rule.onNodeWithText("中医儿科学").assertIsDisplayed()
        rule.onNodeWithText("外科学(2)").assertIsDisplayed()
    }

    @Test
    fun `课表页没有学期时显示提示`() {
        set {
            ScheduleScreen(
                semester = null,
                sessions = emptyList(),
                currentWeek = 1,
                viewingWeek = 1,
                onWeekChange = {},
                onCourseClick = {},
            )
        }
        rule.onNodeWithText("正在获取学期信息…").assertIsDisplayed()
    }

    @Test
    fun `今日页显示电量与课程`() {
        set {
            TodayScreen(
                semester = semester,
                currentWeek = 8,
                sessions = sessions,
                power = power,
                powerDaily = daily,
                onCourseClick = {},
                onOpenPower = {},
                onOpenSettings = {},
                onRefresh = {},
                syncing = false,
                notice = null,
            )
        }
        rule.onNodeWithText("11.78").assertIsDisplayed()
        rule.onNodeWithText("宿舍电量").assertIsDisplayed()
    }

    @Test
    fun `今日页能显示过期提示`() {
        set {
            TodayScreen(
                semester = semester,
                currentWeek = 8,
                sessions = sessions,
                power = null,
                powerDaily = emptyList(),
                onCourseClick = {},
                onOpenPower = {},
                onOpenSettings = {},
                onRefresh = {},
                syncing = false,
                notice = "登录状态已过期，当前显示离线缓存",
            )
        }
        rule.onNodeWithText("登录状态已过期，当前显示离线缓存").assertIsDisplayed()
        rule.onNodeWithText("去登录").assertIsDisplayed()
    }

    @Test
    fun `电费页显示余额与明细`() {
        set {
            PowerScreen(power = power, daily = daily, loading = false, onRefresh = {})
        }
        rule.onNodeWithText("11.78").assertIsDisplayed()
        rule.onNodeWithText("用电趋势").assertIsDisplayed()
        rule.onNodeWithText("每日明细").assertIsDisplayed()
    }

    @Test
    fun `电费页无数据时不崩`() {
        set {
            PowerScreen(power = null, daily = emptyList(), loading = false, onRefresh = {})
        }
        rule.onNodeWithText("暂时读不到电费数据").assertIsDisplayed()
    }

    @Test
    fun `成绩页体测标签能渲染`() {
        val fitness = listOf(
            FitnessGrade(
                term = "2025-2026-1", total = "86", level = "良好",
                items = listOf(
                    FitnessItem("身高", "172", "", ""),
                    FitnessItem("肺活量", "4200", "80", ""),
                ),
            ),
        )
        set {
            GradesScreen(
                grades = emptyList(),
                levels = emptyList(),
                fitness = fitness,
                loading = false,
                onRefresh = {},
            )
        }
        rule.onNodeWithText("课程成绩").assertIsDisplayed()
        rule.onNodeWithText("体测").assertIsDisplayed()
    }
}