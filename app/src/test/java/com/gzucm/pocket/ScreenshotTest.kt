package com.gzucm.pocket

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.gzucm.pocket.core.CourseGrade
import com.gzucm.pocket.core.CourseSession
import com.gzucm.pocket.core.FitnessGrade
import com.gzucm.pocket.core.FitnessItem
import com.gzucm.pocket.core.LevelGrade
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
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

/**
 * 截图测试：Robolectric 原生图形模式下真实渲染 Compose，导出 PNG 供人眼检查。
 * 产物在 app/build/screenshots/。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ScreenshotTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File("build/screenshots").apply { mkdirs() }

    private val semester = Semester.of(
        code = "202601",
        name = "2026-2027 学年 第一学期",
        firstMonday = LocalDate.of(2026, 9, 7),
        totalWeeks = 19,
    )

    private fun s(
        name: String, wd: Int, ps: Int, pe: Int, t1: String, t2: String,
        room: String, cat: String = "理论教学", week: Int = 8,
    ) = CourseSession(
        name = name, code = "0001", teachers = "陈晓刚,郭书宁,黄蓓",
        week = week, weekday = wd, startUnit = ps, endUnit = pe,
        startTime = t1, endTime = t2, room = room, category = cat,
    )

    private val sessions = listOf(
        s("中医儿科学", 1, 1, 2, "08:30", "10:00", "综教楼808（智慧课室）"),
        s("五官学", 1, 3, 4, "10:20", "11:50", "新课室楼302"),
        s("外科学(2)", 1, 7, 9, "14:50", "17:10", "一附院结直肠外科", "实验教学"),
        s("龙氏治脊疗法", 1, 12, 14, "19:00", "21:20", "综教楼407/409智能互动训练室", "实验教学"),
        s("五官学", 2, 1, 2, "08:30", "10:00", "新课室楼302"),
        s("中医儿科学", 2, 3, 4, "10:20", "11:50", "综教楼808（智慧课室）"),
        s("中医各家学说", 2, 6, 8, "14:00", "16:20", "综教楼309"),
        s("中医骨伤科学", 3, 1, 2, "08:30", "10:00", "新课室楼202"),
        s("中医妇科学", 3, 3, 4, "10:20", "11:50", "新课室楼102"),
        s("岭南医家医著选介", 3, 6, 8, "14:00", "16:20", "综教楼804（智慧课室）"),
        s("外科学(2)", 4, 1, 2, "08:30", "10:00", "综教楼808（智慧课室）"),
        s("中医模拟实践课(4)", 4, 3, 4, "10:20", "11:50", "岭南医学研究中心", "实验教学"),
        s("就业指导(2)", 4, 6, 7, "14:00", "15:30", "新课室楼202"),
        s("中医妇科学", 5, 1, 2, "08:30", "10:00", "新课室楼302"),
        s("中医骨伤科学", 5, 3, 4, "10:20", "11:50", "新课室楼202"),
        s("中医人文与社会实践课(4)", 6, 2, 3, "09:20", "11:00", "", "实验教学"),
    )

    private val power = PowerInfo(
        balance = 11.78, unit = "度", roomLabel = "三元里 6 栋 213",
        token = "t", schoolAreaNo = "2", buildingNo = "5754", roomNum = "5770",
    )

    private val daily = listOf(
        PowerDaily("2026-10-01", 0.18, 73.86, 28.80),
        PowerDaily("2026-10-02", 1.08, 72.78, 29.88),
        PowerDaily("2026-10-03", 1.78, 71.00, 31.66),
        PowerDaily("2026-10-04", 1.79, 69.21, 33.45),
        PowerDaily("2026-10-05", 2.88, 74.04, 28.62),
        PowerDaily("2026-10-06", 2.10, 71.94, 30.72),
        PowerDaily("2026-10-07", 1.95, 69.99, 32.67),
        PowerDaily("2026-10-08", 2.34, 67.65, 35.01),
    )

    private val grades = listOf(
        CourseGrade("2025-2026-2", "临床预备课(1)", 87.0, 3.7, 2.0, "实践课", "考查", "", true),
        CourseGrade("2025-2026-2", "中医内科学(2)", 92.0, 4.2, 5.0, "专业课", "考试", "", true),
        CourseGrade("2025-2026-2", "中医外科学", 85.0, 3.5, 4.0, "专业课", "考试", "", true),
        CourseGrade("2025-2026-1", "中医诊断学", 78.0, 2.8, 4.5, "专业课", "考试", "", true),
        CourseGrade("2025-2026-1", "方剂学", 91.0, 4.1, 5.0, "专业课", "考试", "", true),
        CourseGrade("2025-2026-1", "医用生物学", 58.0, 0.0, 2.0, "基础课", "考试", "", false),
    )

    private val levels = listOf(
        LevelGrade("大学英语四级", "2023-2024-1", 502.0, "",
            "2023-12-16", listOf("听力" to "175.0", "阅读" to "195.0", "写作" to "132.0")),
    )

    private val fitness = listOf(
        FitnessGrade(
            "2025-2026-1", "86.5", "良好",
            listOf(
                FitnessItem("身高", "172", "", ""),
                FitnessItem("体重", "62.3", "100", ""),
                FitnessItem("肺活量", "4200", "80", ""),
                FitnessItem("50 米", "6.9", "90", ""),
                FitnessItem("坐位体前屈", "24", "100", ""),
                FitnessItem("立定跳远", "250", "85", ""),
                FitnessItem("引体/仰卧", "12", "70", ""),
                FitnessItem("800/1000 米", "3'45", "75", ""),
            ),
        ),
    )

    private fun shot(
        name: String,
        scrollUp: Int = 0,
        block: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        rule.setContent {
            GzucmTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize()) { block() }
            }
        }
        rule.waitForIdle()

        // 需要看底部时，把内容滚到尽头
        repeat(scrollUp) {
            rule.onRoot().performTouchInput { swipeUp() }
            rule.waitForIdle()
        }

        // Robolectric 的 captureToImage 走 PixelCopy，拿不到像素；
        // 直接把 decorView 画到软件 Canvas 上，NATIVE 图形模式下是真像素。
        val view = rule.activity.window.decorView
        val w = if (view.width > 0) view.width else 822
        val h = if (view.height > 0) view.height else 1782
        if (view.width == 0 || view.height == 0) {
            view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, w, h)
        }
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        view.draw(canvas)
        val f = File(outDir, "$name.png")
        f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        println("screenshot -> ${f.absolutePath} (${bmp.width}x${bmp.height}) ${f.length()}B")
    }

    @Test
    fun schedule() = shot("01_schedule") {
        ScheduleScreen(semester, sessions, 8, 8, {}, {})
    }

    @Test
    fun today() = shot("02_today") {
        TodayScreen(semester, 8, sessions, power, daily, {}, {}, {}, {}, false, null)
    }

    @Test
    fun grades() = shot("03_grades") {
        GradesScreen(grades, levels, fitness, false, {})
    }

    @Test
    fun powerScreen() = shot("04_power") {
        PowerScreen(power = power, daily = daily, loading = false, onRefresh = {})
    }

    /** 整体骨架：验证悬浮底栏的几何关系 */
    @Test
    fun scaffold() = shot("05_scaffold") {
        com.gzucm.pocket.ui.MainScaffold(
            tab = com.gzucm.pocket.ui.Tab.Today,
            onSelect = {},
        ) {
            TodayScreen(semester, 8, sessions, power, daily, {}, {}, {}, {}, false, null)
        }
    }

    /** 课表页在骨架里，看底栏会不会压住最后一行课 */
    @Test
    fun scaffoldSchedule() = shot("06_scaffold_schedule") {
        com.gzucm.pocket.ui.MainScaffold(
            tab = com.gzucm.pocket.ui.Tab.Schedule,
            onSelect = {},
        ) {
            ScheduleScreen(semester, sessions, 8, 8, {}, {})
        }
    }

    /**
     * 矮屏 + 滚到底：验证最后一行课不会被悬浮底栏压住。
     * 这是「课表最下端被遮挡」的直接回归验证。
     */
    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h640dp-xhdpi")
    fun scheduleScrolledToBottom() {
        shot("07_schedule_bottom", scrollUp = 6) {
            com.gzucm.pocket.ui.MainScaffold(
                tab = com.gzucm.pocket.ui.Tab.Schedule,
                onSelect = {},
            ) {
                ScheduleScreen(semester, sessions, 8, 8, {}, {})
            }
        }
    }
}