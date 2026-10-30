package com.gzucm.pocket

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onNodeWithText
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

    /**
     * 时间冲突的数据。
     *
     * 真实教务系统里确实会出现：学校排课把一门课的几个周次排到同一时段，
     * 或者实验课与理论课撞车。这里覆盖四种典型的交叠形态，
     * 用于验证「两块不能盖在一起、文字不能互相压」。
     */
    private val conflictSessions = listOf(
        // ① 完全包含：长课 1-4 节，中间插一节短课 2-3 节
        s("毛泽东思想和中国特色社会主义理论体系概论", 1, 1, 4, "08:30", "11:50", "综教楼B202（智慧课室）"),
        s("医学伦理学", 1, 2, 3, "09:20", "11:00", "新课室楼301"),
        // ② 部分重叠：3-5 与 4-6，错开一半
        s("岭南骨伤康复学", 2, 3, 5, "10:20", "13:00", "教学楼B214"),
        s("中医骨伤科学", 2, 4, 6, "11:10", "13:50", "教学楼B212（智慧课室）"),
        // ③ 首节相同、长度不同
        s("大学心理健康教育", 3, 6, 10, "14:00", "17:40", "户外拓展实验基地"),
        s("马拉松运动医学", 3, 6, 7, "14:00", "15:30", "教学楼B212（智慧课室）"),
        // ④ 三课交叠：一段被两门课夹在中间
        s("方剂学", 4, 1, 3, "08:30", "11:00", "综教楼305"),
        s("中药学", 4, 2, 5, "09:20", "13:00", "综教楼306"),
        s("中医诊断学", 4, 4, 6, "11:10", "13:50", "综教楼307"),
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
        seed: Long = 0L,
        dark: Boolean = true,
        /** 额外推进主线程时钟的毫秒数：用于等 LaunchedEffect 里的 delay/动画 */
        settleMs: Long = 0L,
        block: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        rule.setContent {
            GzucmTheme(darkTheme = dark, seed = seed) {
                Surface(Modifier.fillMaxSize()) { block() }
            }
        }
        rule.waitForIdle()

        // Compose 的 LaunchedEffect 里用真实时钟 delay，waitForIdle 不会等它。
        // 需要验证「延迟后才发生的动作」时，靠 ShadowLooper 把主线程时间推过去。
        if (settleMs > 0) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper(
                settleMs, java.util.concurrent.TimeUnit.MILLISECONDS,
            )
            rule.waitForIdle()
        }

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
        TodayScreen(semester, 8, sessions, power, daily, 0L, {}, {}, {}, {}, false, 0, 0, false, null)
    }

    @Test
    fun grades() = shot("03_grades") {
        GradesScreen(grades, levels, fitness, false, 0, 0, null, 0L, {})
    }

    @Test
    fun powerScreen() = shot("04_power") {
        PowerScreen(power = power, daily = daily, loading = false)
    }

    /** 整体骨架：验证悬浮底栏的几何关系 */
    @Test
    fun scaffold() = shot("05_scaffold") {
        com.gzucm.pocket.ui.MainScaffold(
            tab = com.gzucm.pocket.ui.Tab.Today,
            onSelect = {},
        ) {
            TodayScreen(semester, 8, sessions, power, daily, 0L, {}, {}, {}, {}, false, 0, 0, false, null)
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

    /** 设置页：看主题色卡两行的排布 */
    @Test
    fun settings() = shot("07_settings") {
        com.gzucm.pocket.ui.settings.SettingsScreen(
            onSync = {}, onLogout = {}, onClose = {},
            lastSyncText = "10-30 12:05",
            semesterText = "2026-2027 学年 第一学期",
        )
    }

    /** 换成靛蓝主题后的首页：确认中性色没被带跑偏 */
    @Test
    fun todayWithIndigo() = shot("08_today_indigo", seed = 0xFF3B82F6) {
        TodayScreen(semester, 8, sessions, power, daily, 0L, {}, {}, {}, {}, false, 0, 0, false, null)
    }

    /** 换成莫兰迪苔绿：低饱和色在深底上会不会糊 */
    @Test
    fun todayWithMoss() = shot("09_today_moss", seed = 0xFF8CA07A) {
        TodayScreen(semester, 8, sessions, power, daily, 0L, {}, {}, {}, {}, false, 0, 0, false, null)
    }

    /** 浅色主题 + 靛蓝：浅底上主色太亮就看不清，必须确认一下 */
    @Test
    fun todayLightIndigo() = shot("10_today_light_indigo", seed = 0xFF3B82F6, dark = false) {
        TodayScreen(semester, 8, sessions, power, daily, 0L, {}, {}, {}, {}, false, 0, 0, false, null)
    }

    /** 浅色主题 + 豆沙（低饱和）：最容易发粉发白的组合 */
    @Test
    fun settingsLight() = shot("11_settings_light", dark = false) {
        com.gzucm.pocket.ui.settings.SettingsScreen(
            onSync = {}, onLogout = {}, onClose = {},
            lastSyncText = "10-30 12:05",
            semesterText = "2026-2027 学年 第一学期",
        )
    }

    /** 课表翻到别的周：确认「回到本周」按钮出现 */
    @Test
    fun scheduleOtherWeek() = shot("12_schedule_other_week") {
        ScheduleScreen(semester, sessions, 8, 12, {}, {})
    }

    /** 成绩页登录失效：文案仍是「暂无成绩」，但多一个去登录按钮 */
    @Test
    fun gradesNeedLogin() = shot("13_grades_need_login") {
        GradesScreen(
            grades = emptyList(), levels = emptyList(), fitness = emptyList(),
            loading = false, onRefresh = {}, needLogin = true, onOpenLogin = {},
        )
    }

    /** 设置页从「去登录」进来：应自动滚到「重新登录」并高亮 */
    @Test
    fun settingsFocusRelogin() = shot("14_settings_focus_relogin", scrollUp = 0) {
        com.gzucm.pocket.ui.settings.SettingsScreen(
            onSync = {}, onLogout = {}, onClose = {},
            lastSyncText = "10-30 12:05",
            semesterText = "2026-2027 学年 第一学期",
            focusRelogin = true,
        )
    }

    /**
     * 矮屏（640dp）下点「去登录」：设置页必须自己滚下去，把「重新登录」露出来。
     * 用矮屏是因为 891dp 时整页放得下，滚不滚看不出来。
     */
    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h640dp-xhdpi")
    fun settingsFocusReloginShort() = shot("15_focus_relogin_short", settleMs = 1_200) {
        com.gzucm.pocket.ui.settings.SettingsScreen(
            onSync = {}, onLogout = {}, onClose = {},
            lastSyncText = "10-30 12:05",
            semesterText = "2026-2027 学年 第一学期",
            focusRelogin = true,
        )
    }

    /** 同样的矮屏，但没有 focusRelogin：应该停在顶部，作为对照组 */
    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h640dp-xhdpi")
    fun settingsNoFocusShort() = shot("16_no_focus_short") {
        com.gzucm.pocket.ui.settings.SettingsScreen(
            onSync = {}, onLogout = {}, onClose = {},
            lastSyncText = "10-30 12:05",
            semesterText = "2026-2027 学年 第一学期",
        )
    }

    /** 对照组：没点「去登录」时，矮屏上「重新登录」本来在屏幕外 */
    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h640dp-xhdpi")
    fun `没点去登录时重新登录在视野外`() {
        rule.setContent {
            GzucmTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize()) {
                    com.gzucm.pocket.ui.settings.SettingsScreen(
                        onSync = {}, onLogout = {}, onClose = {},
                        lastSyncText = "10-30 12:05",
                        semesterText = "2026-2027 学年 第一学期",
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("重新登录").assertIsNotDisplayed()
    }

    /** 对照组：没登录过期（真的没成绩，比如大一新生）→ 只有文字，没有按钮 */
    @Test
    fun gradesEmptyNoLogin() = shot("17_grades_empty_plain") {
        GradesScreen(
            grades = emptyList(), levels = emptyList(), fitness = emptyList(),
            loading = false, onRefresh = {}, needLogin = false, onOpenLogin = {},
        )
    }

    /** 「关于软件」那行有更新提示时啥样 */
    @Test
    fun settingsUpdateBadge() = shot("18_settings_update_badge", scrollUp = 2) {
        com.gzucm.pocket.ui.settings.SettingsScreen(
            onSync = {}, onLogout = {}, onClose = {},
            lastSyncText = "10-30 12:05",
            semesterText = "2026-2027 学年 第一学期",
            hasUpdate = true,
        )
    }

    /** 换宿舍引导：四步 + 一句补救提示 */
    @Test
    fun rebindGuide() = shot("19_rebind_guide") {
        com.gzucm.pocket.ui.power.RebindGuideSheet(onDismiss = {})
    }

    /** 时间冲突：当前实现会叠在一起，先看问题是多严重 */
    @Test
    fun scheduleConflict() = shot("20_schedule_conflict") {
        ScheduleScreen(semester, conflictSessions, 8, 8, {}, {})
    }

    /** 内置冲突演示数据在真实界面的样子（走 ConflictDemo，不是测试手写的） */
    @Test
    fun conflictDemoSchedule() = shot("21_conflict_demo") {
        ScheduleScreen(
            com.gzucm.pocket.core.ConflictDemo.semester(),
            com.gzucm.pocket.core.ConflictDemo.sessions(),
            8, 8, {}, {},
        )
    }

    /** 冲突排布：并排分列模式（对照叠压） */
    @Test
    fun conflictSplitMode() = shot("22_conflict_split") {
        ScheduleScreen(
            com.gzucm.pocket.core.ConflictDemo.semester(),
            com.gzucm.pocket.core.ConflictDemo.sessions(),
            8, 8, {}, {},
        )
    }
}
