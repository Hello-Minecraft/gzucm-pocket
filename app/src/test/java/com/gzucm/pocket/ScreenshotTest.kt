package com.gzucm.pocket

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
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
import com.gzucm.pocket.ui.components.micaSheetColor
import com.gzucm.pocket.ui.grades.GradesScreen
import com.gzucm.pocket.ui.power.PowerScreen
import com.gzucm.pocket.ui.schedule.ScheduleScreen
import com.gzucm.pocket.ui.theme.GzucmTheme
import com.gzucm.pocket.ui.today.TodayScreen
import org.junit.Rule
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.unit.dp
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
        // 公选课：教务带 kcflmc 分类，值照抄真实数据
        CourseGrade("2025-2026-1", "音乐鉴赏", 87.0, 3.7, 2.0, "公共选修课", "考试", "", true, "美育类"),
        CourseGrade("2024-2025-1", "影像技术与技能(摄影与摄像)(慕课)", 70.2, 2.0, 2.0, "公共选修课", "考试", "", true, "美育类"),
        CourseGrade("2024-2025-2", "人格心理学(慕课)", 90.0, 4.0, 2.0, "公共选修课", "考试", "", true, "人文社科与素养类"),
        CourseGrade("2024-2025-2", "普通话训练与口才艺术", 85.0, 3.5, 2.0, "公共选修课", "考试", "", true, "人文社科与素养类"),
        CourseGrade("2025-2026-1", "从爱因斯坦到霍金的宇宙", 85.6, 3.6, 3.0, "公共选修课", "考试", "", true, "网络课程类"),
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
        /**
         * 推进 Compose 测试时钟的毫秒数。`LaunchedEffect` 里的 `delay` 走的是
         * Compose 自己的 MonotonicFrameClock，推 Android Looper 对它没用
         * （底栏 3 秒渐隐就是这种）。
         */
        frameMs: Long = 0L,
        /** 按下并保持，用来拍 ripple / 触摸反馈的静态形态（传 testTag） */
        pressTag: String? = null,
        /**
         * 真实等待毫秒数。壁纸解码跑在 Dispatchers.IO，ShadowLooper / mainClock
         * 都推不动它；不等就会拍到「壁纸还没出来」或「墨色还没判定」的那一帧。
         */
        realWaitMs: Long = 0L,
        /** 截图前的额外交互，比如点一下切换开关 */
        prepare: (() -> Unit)? = null,
        block: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        rule.setContent {
            GzucmTheme(darkTheme = dark, seed = seed) {
                Surface(Modifier.fillMaxSize()) { block() }
            }
        }
        rule.waitForIdle()

        // 先滚动再 prepare：LazyColumn 只组合视口内的项，
        // 没滚到的话节点压根不存在，prepare 里会找不到。

        // 按住不放：ripple 只在按下期间可见，松手就淡出了
        if (pressTag != null) {
            val node = rule.onNodeWithTag(pressTag)
            println("pressed tab bounds = ${node.getUnclippedBoundsInRoot()}")
            node.performTouchInput { down(center) }
            rule.waitForIdle()
            // ripple 的展开是带时钟的动画，Robolectric 不会自己走完
            org.robolectric.shadows.ShadowLooper.idleMainLooper(
                400, java.util.concurrent.TimeUnit.MILLISECONDS,
            )
            rule.waitForIdle()
        }

        // Compose 的 LaunchedEffect 里用真实时钟 delay，waitForIdle 不会等它。
        // 需要验证「延迟后才发生的动作」时，靠 ShadowLooper 把主线程时间推过去。
        if (settleMs > 0) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper(
                settleMs, java.util.concurrent.TimeUnit.MILLISECONDS,
            )
            rule.waitForIdle()
        }

        if (realWaitMs > 0L) {
            /*
             * 只 sleep 不行：Robolectric 的 Looper 默认是 PAUSED，
             * IO 线程完成后 post 到主线程的回调不会自己执行。
             * 每一小段都要 idle 一次，让 state 更新 → 重组 → 再绘制。
             */
            var waited = 0L
            while (waited < realWaitMs) {
                Thread.sleep(100L)
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                rule.waitForIdle()
                waited += 100L
            }
        }

        if (frameMs > 0L) {
            rule.mainClock.advanceTimeBy(frameMs)
            rule.waitForIdle()
        }

        // 需要看底部时，把内容滚到尽头
        repeat(scrollUp) {
            rule.onRoot().performTouchInput { swipeUp() }
            rule.waitForIdle()
        }

        if (prepare != null) {
            prepare()
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

    /**
     * 造一张「高对比色块」的假壁纸并写进临时文件。
     *
     * 磨砂效果只有在壁纸本身颜色丰富、边界明确时才看得出来；
     * 用纯色壁纸拍出来跟没开一样，等于白测。
     */
    private fun makeTestWallpaper(light: Boolean = false): File {
        val ctx = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.app.Application>()
        val f = File(ctx.cacheDir, if (light) "test_wallpaper_light.png" else "test_wallpaper.png")
        val bmp = android.graphics.Bitmap.createBitmap(1080, 1920, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(
            if (light) android.graphics.Color.rgb(233, 227, 240)
            else android.graphics.Color.rgb(18, 34, 48),
        )
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val colors = intArrayOf(
            0xFFE8A33D.toInt(), 0xFF3D8BE8.toInt(),
            0xFFB04AD4.toInt(), 0xFF37C08A.toInt(),
        )
        colors.forEachIndexed { i, col ->
            paint.color = col
            canvas.drawCircle(180f + i * 300f, 320f + i * 430f, 240f, paint)
            canvas.drawRect(
                i * 250f, 1150f + i * 120f, i * 250f + 420f, 1500f + i * 120f, paint,
            )
        }
        f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    /** 临时把 store 换成测试壁纸，跑完恢复 —— 别的截图测试读同一份 prefs */
    private fun withTestWallpaper(
        dark: Boolean,
        name: String,
        cellFrost: Boolean = true,
        bgBlur: Boolean = false,
        alpha: Float = 0.9f,
        lightWallpaper: Boolean = false,
        block: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        val ctx = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.app.Application>()
        val store = com.gzucm.pocket.core.SessionStore(ctx)
        val oldPath = store.courseBgPath
        val oldAlpha = store.courseBgAlpha
        val oldOpacity = store.courseCellOpacity
        val oldFrost = store.courseCellFrost
        val oldBlur = store.courseBgBlur

        val f = makeTestWallpaper(lightWallpaper)
        store.courseBgPath = f.absolutePath
        store.courseBgAlpha = alpha
        store.courseCellOpacity = 0.40f
        store.courseCellFrost = cellFrost
        store.courseBgBlur = bgBlur
        try {
            shot(name, dark = dark, realWaitMs = 600L, block = block)
        } finally {
            store.courseBgPath = oldPath
            store.courseBgAlpha = oldAlpha
            store.courseCellOpacity = oldOpacity
            store.courseCellFrost = oldFrost
            store.courseBgBlur = oldBlur
            f.delete()
        }
    }

    /**
     * 课表 + 自定义壁纸 + 磨砂格子（2026-11-03）。
     */
    @Test
    fun scheduleFrosted() = withTestWallpaper(dark = true, name = "01e_schedule_frosted") {
        ScheduleScreen(semester, sessions, 8, 8, {}, {})
    }

    /**
     * 浅色主题下的磨砂。
     *
     * 雾面用的是主题 surface —— 深色下是"黑玻璃"，浅色下是"白玻璃"，
     * 观感差很远，必须两版都看。而且一次测试只能 setContent 一次，
     * 所以只能拆成两个方法。
     */
    @Test
    fun scheduleFrostedLight() = withTestWallpaper(dark = false, name = "01f_schedule_frosted_light") {
        ScheduleScreen(semester, sessions, 8, 8, {}, {})
    }

    /** 全屏背景模糊：整张壁纸糊掉，格子只留课程色（磨砂被短路） */
    @Test
    fun scheduleBgBlur() = withTestWallpaper(
        dark = true, name = "01h_schedule_bg_blur", bgBlur = true,
    ) {
        ScheduleScreen(semester, sessions, 8, 8, {}, {})
    }

    /** 关掉格子磨砂：背景清晰，格子只有课程色 */
    @Test
    fun scheduleNoCellFrost() = withTestWallpaper(
        dark = true, name = "01i_schedule_no_cell_frost", cellFrost = false,
    ) {
        ScheduleScreen(semester, sessions, 8, 8, {}, {})
    }

    /** 日视图 + 壁纸：卡片是圆角矩形，磨砂要跟着圆角裁，别溢出到卡外 */    @Test
    fun scheduleDayFrosted() {
        val ctx = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.app.Application>()
        val store = com.gzucm.pocket.core.SessionStore(ctx)
        val oldPath = store.courseBgPath
        val oldAlpha = store.courseBgAlpha
        val oldOpacity = store.courseCellOpacity
        val oldDayView = store.courseDayView

        val f = makeTestWallpaper()
        store.courseBgPath = f.absolutePath
        store.courseBgAlpha = 0.9f
        store.courseCellOpacity = 0.40f
        store.courseDayView = true
        try {
            shot("01g_schedule_day_frosted") {
                ScheduleScreen(semester, sessions, 8, 8, {}, {})
            }
        } finally {
            store.courseBgPath = oldPath
            store.courseBgAlpha = oldAlpha
            store.courseCellOpacity = oldOpacity
            store.courseDayView = oldDayView
            f.delete()
        }
    }

    /**
     * 纯色壁纸，用来复现「极端底色 + 极低格子不透明度」下文字读不出来。
     *
     * [makeTestWallpaper] 是深蓝底 + 彩色圆点，亮度落在中间区间，
     * 恰好是「怎么都读得清」的那一段 —— 所以这个坑一直没暴露。
     * 致辉 2026-11-19 报的两种情况都需要纯色才复现得出来：
     *
     *   · 纯黑壁纸 + 浅色主题 → 卡片底色近黑，onSurface(#14181C) 压上去等于隐形
     *   · 纯白壁纸 + 深色主题 → 卡片底色近白，onSurface(#E9EDF0) 也读不出来
     */
    private fun makeSolidWallpaper(argb: Int, fileName: String): File {
        val ctx = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.app.Application>()
        val f = File(ctx.cacheDir, fileName)
        val bmp = android.graphics.Bitmap.createBitmap(
            1080, 1920, android.graphics.Bitmap.Config.ARGB_8888,
        )
        bmp.eraseColor(argb)
        f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    /** 套一张现成的壁纸文件 + 指定格子不透明度，跑完恢复 prefs（别的用例读同一份） */
    private fun withWallpaperFile(
        file: File,
        dark: Boolean,
        name: String,
        opacity: Float,
        block: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        val ctx = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.app.Application>()
        val store = com.gzucm.pocket.core.SessionStore(ctx)
        val oldPath = store.courseBgPath
        val oldAlpha = store.courseBgAlpha
        val oldOpacity = store.courseCellOpacity
        val oldFrost = store.courseCellFrost
        val oldBlur = store.courseBgBlur
        val oldDayView = store.courseDayView

        store.courseBgPath = file.absolutePath
        store.courseBgAlpha = 1f
        store.courseCellOpacity = opacity
        store.courseCellFrost = true
        store.courseBgBlur = false
        store.courseDayView = true
        try {
            shot(name, dark = dark, realWaitMs = 700L, block = block)
        } finally {
            store.courseBgPath = oldPath
            store.courseBgAlpha = oldAlpha
            store.courseCellOpacity = oldOpacity
            store.courseCellFrost = oldFrost
            store.courseBgBlur = oldBlur
            store.courseDayView = oldDayView
            file.delete()
        }
    }

    /**
     * 问题反馈的正文。
     *
     * 只渲染 [FeedbackContent] 而不是整个 [FeedbackSheet]：
     * ModalBottomSheet 在 Robolectric 里停在收起状态，拍出来是全黑的。
     * 容器本身和打赏、电费说明共用 AdaptiveSheet，那边已经验过了。
     */
    @Test
    fun feedbackSheet() = shot("25_feedback") {
        com.gzucm.pocket.ui.settings.FeedbackContent(onBack = {})
    }

    /* ── 今日标记 / 回到今日（2026-11-19） ────────────────────── */

    /** 今天落在第几周由学期自己算，别写死 —— 否则换个日期跑就全绿。 */
    private fun todayWeekNow(): Int = semester.weekOf(java.time.LocalDate.now())

    /**
     * 周视图：今天那一格要有一圈主色描边。
     *
     * `week = currentWeek = 今天所在周`，星期条上「今天」与「当前查看」重合，
     * 正是最容易看不出差别的状态 —— 描边就是为这一刻加的。
     */
    @Test
    fun todayMarkWeek() {
        val tw = todayWeekNow()
        shot("21_today_mark_week") {
            ScheduleScreen(semester, sessions, tw, tw, {}, {})
        }
    }

    /** 周视图：翻到别的周时，右下角出现「今」。 */
    @Test
    fun todayFabWeek() {
        val tw = todayWeekNow()
        shot("22_today_fab_week") {
            // 签名是 (currentWeek, viewingWeek)：今天在第 tw 周、用户在看第 8 周
            ScheduleScreen(semester, sessions, tw, 8, {}, {})
        }
    }

    /** 日视图：今天那一格同样有描边。 */
    @Test
    fun todayMarkDay() {
        val ctx = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.app.Application>()
        val store = com.gzucm.pocket.core.SessionStore(ctx)
        val oldDayView = store.courseDayView
        store.courseDayView = true
        try {
            val tw = todayWeekNow()
            shot("23_today_mark_day") {
                ScheduleScreen(semester, sessions, tw, tw, {}, {})
            }
        } finally {
            store.courseDayView = oldDayView
        }
    }

    /** 日视图：滑到前一天，「今」按钮浮出来。 */
    @Test
    fun todayFabDay() {
        val ctx = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.app.Application>()
        val store = com.gzucm.pocket.core.SessionStore(ctx)
        val oldDayView = store.courseDayView
        store.courseDayView = true
        try {
            val tw = todayWeekNow()
            shot(
                "24_today_fab_day",
                prepare = {
                    // 向右拖过半页 → currentPage 变成前一天 → 按钮该出现
                    rule.onNodeWithTag("day-pager").performTouchInput {
                        down(center)
                        // 要真的翻过去（超过半页），只拖一点点 currentPage 不会变
                        repeat(12) { moveBy(androidx.compose.ui.geometry.Offset(60f, 0f)) }
                    }
                    rule.waitForIdle()
                },
            ) {
                ScheduleScreen(semester, sessions, tw, tw, {}, {})
            }
        } finally {
            store.courseDayView = oldDayView
        }
    }

    /**
     * 复现「往前一天滑时，前一天的磨砂用错位置」。
     *
     * 致辉 2026-11-19 报：后一天正常，前一天总是错 —— 卡片明明还在左边滑入，
     * 里面的磨砂却已经是「滑到位停在中间」那一块（背景的塔正好落在卡片正中）。
     *
     * 拖拽只走一半就截图（不抬手，Pager 不会回弹），这一刻是矛盾的现场：
     * 卡片的**视觉**位置在左半边，采样的**坐标**却指着屏幕中央。
     */
    @Test
    fun dayFrostSwipeBack() {
        val ctx = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.app.Application>()
        val store = com.gzucm.pocket.core.SessionStore(ctx)
        val oldPath = store.courseBgPath
        val oldAlpha = store.courseBgAlpha
        val oldOpacity = store.courseCellOpacity
        val oldFrost = store.courseCellFrost
        val oldDayView = store.courseDayView

        val f = makeTestWallpaper()
        store.courseBgPath = f.absolutePath
        store.courseBgAlpha = 0.9f
        store.courseCellOpacity = 0.40f
        store.courseCellFrost = true
        store.courseDayView = true
        try {
            shot(
                "19_day_frost_swipe_back",
                dark = true,
                realWaitMs = 600L,
                prepare = {
                    // 向右拖 = 露出前一天。分多步模拟手指，停在中间不抬手
                    rule.onNodeWithTag("day-pager").performTouchInput {
                        down(center)
                        repeat(10) { moveBy(androidx.compose.ui.geometry.Offset(55f, 0f)) }
                    }
                    rule.waitForIdle()
                },
            ) {
                ScheduleScreen(semester, sessions, 8, 8, {}, {})
            }
        } finally {
            store.courseBgPath = oldPath
            store.courseBgAlpha = oldAlpha
            store.courseCellOpacity = oldOpacity
            store.courseCellFrost = oldFrost
            store.courseDayView = oldDayView
            f.delete()
        }
    }

    /**
     * 对照组：向左拖，露出**后一天**。
     *
     * 致辉说「后一天本来就没问题」，修完滚动位移补偿之后它必须还是没问题 ——
     * 这条用例就是防「修好一个方向、弄坏另一个方向」。
     */
    @Test
    fun dayFrostSwipeForward() {
        val ctx = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.app.Application>()
        val store = com.gzucm.pocket.core.SessionStore(ctx)
        val oldPath = store.courseBgPath
        val oldAlpha = store.courseBgAlpha
        val oldOpacity = store.courseCellOpacity
        val oldFrost = store.courseCellFrost
        val oldDayView = store.courseDayView

        val f = makeTestWallpaper()
        store.courseBgPath = f.absolutePath
        store.courseBgAlpha = 0.9f
        store.courseCellOpacity = 0.40f
        store.courseCellFrost = true
        store.courseDayView = true
        try {
            shot(
                "20_day_frost_swipe_forward",
                dark = true,
                realWaitMs = 600L,
                prepare = {
                    rule.onNodeWithTag("day-pager").performTouchInput {
                        down(center)
                        repeat(10) { moveBy(androidx.compose.ui.geometry.Offset(-55f, 0f)) }
                    }
                    rule.waitForIdle()
                },
            ) {
                ScheduleScreen(semester, sessions, 8, 8, {}, {})
            }
        } finally {
            store.courseBgPath = oldPath
            store.courseBgAlpha = oldAlpha
            store.courseCellOpacity = oldOpacity
            store.courseCellFrost = oldFrost
            store.courseDayView = oldDayView
            f.delete()
        }
    }

    /**
     * 日视图 + 自定义壁纸 + **关掉格子磨砂**。
     *
     * 三分支里的第三态：卡片没有阴影，但有一圈 0.8dp 灰白描边勾轮廓
     * （2026-11-19 致辉定：模糊关就关阴影、加描边）。
     * 阴影本身 Robolectric 拍不出来，这张图验的是「描边出现 + 布局没塌」。
     */
    @Test
    fun dayFrostOff() {
        val ctx = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.app.Application>()
        val store = com.gzucm.pocket.core.SessionStore(ctx)
        val oldPath = store.courseBgPath
        val oldAlpha = store.courseBgAlpha
        val oldOpacity = store.courseCellOpacity
        val oldFrost = store.courseCellFrost
        val oldDayView = store.courseDayView

        val f = makeTestWallpaper()
        store.courseBgPath = f.absolutePath
        store.courseBgAlpha = 0.9f
        store.courseCellOpacity = 0.40f
        store.courseCellFrost = false
        store.courseDayView = true
        try {
            shot("18_day_frost_off", realWaitMs = 600L) {
                ScheduleScreen(semester, sessions, 8, 8, {}, {})
            }
        } finally {
            store.courseBgPath = oldPath
            store.courseBgAlpha = oldAlpha
            store.courseCellOpacity = oldOpacity
            store.courseCellFrost = oldFrost
            store.courseDayView = oldDayView
            f.delete()
        }
    }

    /**
     * 复现 A：**纯黑壁纸 + 浅色主题 + 10% 格子不透明度**。
     *
     * 10% 时卡片底色 = 磨砂黑 + 30% 白雾 ≈ #4C4C4C，
     * 而浅色主题的正文色是 #14181C —— 对比度约 2.2:1，远低于可读下限 4.5。
     */
    @Test
    fun dayLowOpacityOnBlack() {
        val f = makeSolidWallpaper(android.graphics.Color.BLACK, "test_wallpaper_black.png")
        withWallpaperFile(f, dark = false, name = "16_day_low_opacity_black", opacity = 0.10f) {
            ScheduleScreen(semester, sessions, 8, 8, {}, {})
        }
    }

    /**
     * 复现 B：**纯白壁纸 + 深色主题 + 10% 格子不透明度**。
     *
     * 白磨砂 + 30% 的 #171C23 雾面 ≈ #B9B9B9，
     * 深色主题正文 #E9EDF0 压上去对比度约 1.6:1，同样读不出来。
     */
    @Test
    fun dayLowOpacityOnWhite() {
        val f = makeSolidWallpaper(android.graphics.Color.WHITE, "test_wallpaper_white.png")
        withWallpaperFile(f, dark = true, name = "17_day_low_opacity_white", opacity = 0.10f) {
            ScheduleScreen(semester, sessions, 8, 8, {}, {})
        }
    }

    /**
     * 日视图（2026-11-01 新增）。
     *
     * 直接摆一个 DayScheduleView，不走 ScheduleScreen ——
     * 那边默认从存储里读周视图，改 store 会影响其他测试。
     */
    @Test
    fun scheduleDayView() = shot("01b_schedule_day") {
        androidx.compose.material3.Surface(
            androidx.compose.ui.Modifier.fillMaxSize(),
            color = androidx.compose.material3.MaterialTheme.colorScheme.background,
        ) {
            com.gzucm.pocket.ui.schedule.DayScheduleView(
                semester = semester,
                week = 8,
                currentWeek = 8,
                sessions = sessions,
                onCourseClick = {},
            )
        }
    }

    /**
     * 日视图带「正在上」状态。
     *
     * 造一门「从今天 0 点到现在都还没结束」的课，这样无论测试跑在几点，
     * phaseOf 都会判成 ONGOING —— 截图里一定能看到正在上的样式。
     * 不用写死具体时间，否则半夜跑测试就挂了。
     */
    @Test
    fun scheduleDayViewOngoing() = shot("01d_schedule_day_ongoing") {
        val today = java.time.LocalDate.now()
        val weekday = today.dayOfWeek.value
        val nowMin = java.time.LocalTime.now().let { it.hour * 60 + it.minute }

        // 从 00:00 到现在+30 分钟 —— 现在必然在区间内
        val endMin = nowMin + 30
        val ongoing = com.gzucm.pocket.core.CourseSession(
            name = "中医内科学",
            code = "B123",
            teachers = "张三",
            week = 8,
            weekday = weekday,
            startUnit = 1,
            endUnit = 4,
            startTime = "00:00",
            endTime = "%02d:%02d".format((endMin / 60) % 24, endMin % 60),
            room = "教学楼A101",
            category = "",
            campus = com.gzucm.pocket.core.Campus.UNDERGRAD,
        )
        // 再造一门已经下课的，验证降透明度
        val past = ongoing.copy(
            name = "已结束的课",
            startTime = "00:00",
            endTime = "00:01",
        )
        val upcoming = ongoing.copy(
            name = "晚上的课",
            startTime = "23:59",
            endTime = "23:59",
        )

        androidx.compose.material3.Surface(
            androidx.compose.ui.Modifier.fillMaxSize(),
            color = androidx.compose.material3.MaterialTheme.colorScheme.background,
        ) {
            com.gzucm.pocket.ui.schedule.DayScheduleView(
                semester = semester,
                week = 8,
                currentWeek = 8,
                sessions = listOf(past, ongoing, upcoming),
                onCourseClick = {},
                initialDay = weekday,
            )
        }
    }

    /** 日视图某天没课时的占位 */
    @Test
    fun scheduleDayViewEmpty() = shot("01c_schedule_day_empty") {
        androidx.compose.material3.Surface(
            androidx.compose.ui.Modifier.fillMaxSize(),
            color = androidx.compose.material3.MaterialTheme.colorScheme.background,
        ) {
            com.gzucm.pocket.ui.schedule.DayScheduleView(
                semester = semester,
                week = 8,
                currentWeek = 8,
                sessions = emptyList(),
                onCourseClick = {},
                initialDay = 6,
            )
        }
    }

    @Test
    fun today() = shot("02_today") {
        TodayScreen(semester, 8, sessions, power, daily, 0L, {}, {}, {}, {}, false, 0, 0, false, null)
    }

    /**
     * 平板横屏的今日页（872dp）：应该是双列 Dashboard。
     *
     * 用真机的宽高比（872×1200 竖着的平板转横屏会矮一些，这里取 872×600 更接近），
     * 高度不够会被裁掉下半截，看不出「一屏能看多少」这个关键收益。
     */
    /**
     * 侧栏底部动作区：同步中 / 空闲两种状态。
     *
     * 这张图是为了锁住「同步时按钮变成进度圈」——
     * 实机上同步失败只要 100ms，进度圈一闪而过，截不到，
     * 所以用截图测试直接把 spinning = true 的状态渲染出来看。
     *
     * 踩过的坑：转圈一直不显示，原因不是组件坏了，是调用方传的
     * `syncing && sessions.isEmpty()` —— 有课表时永远 false。
     */
    @Test
    @Config(sdk = [34], qualifiers = "w872dp-h900dp-xhdpi")
    fun railActions() = shot("53_rail_actions") {
        androidx.compose.foundation.layout.Row(
            androidx.compose.ui.Modifier.padding(20.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
        ) {
            com.gzucm.pocket.ui.adaptive.RailActionButton(
                icon = androidx.compose.material.icons.Icons.Rounded.Refresh,
                label = "同步中",
                spinning = true,
                onClick = {},
            )
            com.gzucm.pocket.ui.adaptive.RailActionButton(
                icon = androidx.compose.material.icons.Icons.Rounded.Refresh,
                label = "同步",
                spinning = false,
                onClick = {},
            )
            com.gzucm.pocket.ui.adaptive.RailActionButton(
                icon = androidx.compose.material.icons.Icons.Rounded.Settings,
                label = "设置",
                badge = true,
                onClick = {},
            )
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w872dp-h900dp-xhdpi")
    fun todayTablet() = shot("50_today_tablet") {
        TodayScreen(semester, 8, sessions, power, daily, 0L, {}, {}, {}, {}, false, 0, 0, false, null)
    }

    @Test
    fun grades() = shot("03_grades") {
        GradesScreen(grades, levels, fitness, false, 0, 0, null, 0L, {})
    }

    /** 点「已修学分」展开学分构成，再切到「公选」看分类汇总 */
    @Test
    fun gradesPublicCredit() = shot(
        "72_grades_public",
        prepare = {
            rule.onNodeWithText("已修学分").performClick()
            rule.waitForIdle()
            rule.onNodeWithText("公选").performClick()
        },
    ) {
        GradesScreen(grades, levels, fitness, false, 0, 0, null, 0L, {})
    }

    /** 有人把某一类修超了（6 学分）：分母退到 6，其余 4 分的条变成 2/3，不能溢出 */
    @Test
    fun gradesPublicOverflow() = shot(
        "77_grades_public_overflow",
        prepare = {
            rule.onNodeWithText("已修学分").performClick()
            rule.waitForIdle()
            rule.onNodeWithText("公选").performClick()
        },
    ) {
        GradesScreen(
            grades + listOf(
                CourseGrade("2025-2026-1", "美术鉴赏", 88.0, 3.8, 2.0, "公共选修课", "考试", "", true, "美育类"),
            ),
            levels, fitness, false, 0, 0, null, 0L, {},
        )
    }

    /** 切到公选后再切回总学分，不能卡在空态 */
    @Test
    fun gradesPublicBackToTotal() = shot(
        "73_grades_public_back",
        prepare = {
            rule.onNodeWithText("已修学分").performClick()
            rule.waitForIdle()
            rule.onNodeWithText("公选").performClick()
            rule.waitForIdle()
            rule.onNodeWithText("总学分").performClick()
        },
    ) {
        GradesScreen(grades, levels, fitness, false, 0, 0, null, 0L, {})
    }

    /** 点「已修学分」展开「学分构成」（总学分视角） */
    @Test
    fun gradesCreditExpanded() = shot(
        "74_grades_credit_expanded",
        prepare = { rule.onNodeWithText("已修学分").performClick() },
    ) {
        GradesScreen(grades, levels, fitness, false, 0, 0, null, 0L, {})
    }

    /** 再点一次要能收起来，不能只开不合 */
    @Test
    fun gradesCreditCollapsed() = shot(
        "75_grades_credit_collapsed",
        prepare = {
            rule.onNodeWithText("已修学分").performClick()
            rule.waitForIdle()
            rule.onNodeWithText("已修学分").performClick()
        },
    ) {
        GradesScreen(grades, levels, fitness, false, 0, 0, null, 0L, {})
    }

    /** 平板打开「电费设置」：余额卡要保持原尺寸，不能被全屏面板撑变形 */
    @Test
    @Config(sdk = [34], qualifiers = "w872dp-h900dp-xhdpi")
    fun powerWhySheet() = shot(
        "78_power_why_sheet",
        prepare = { rule.onNodeWithText("电费设置").performClick() },
    ) {
        PowerScreen(power = power, daily = daily, loading = false)
    }

    /** 差值图拖动选中某一天：竖线定位、其余柱变淡、顶部换成当天读数 */
    @Test
    @Config(sdk = [34], qualifiers = "w872dp-h900dp-xhdpi")
    fun powerDeltaSelected() = shot(
        "76_power_delta_selected",
        prepare = {
            rule.onNodeWithTag("delta_bars").performTouchInput {
                down(androidx.compose.ui.geometry.Offset(width * 0.18f, height / 2f))
                moveTo(androidx.compose.ui.geometry.Offset(width * 0.55f, height / 2f))
                up()
            }
        },
    ) {
        PowerScreen(power = power, daily = daily, loading = false)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w872dp-h900dp-xhdpi")
    fun powerTablet() = shot("51_power_tablet") {
        PowerScreen(power = power, daily = daily, loading = false)
    }

    @Test
    fun powerScreen() = shot("04_power") {
        PowerScreen(power = power, daily = daily, loading = false)
    }

    /**
     * 浅色电费页：**专盯余额卡的渐变断层**。
     *
     * 2026-11-19 真机反馈「浅色下剩余电量面板有色彩断层」，
     * 根因是渐变第一端用了半透明 primary（透出页面底色），第二端是不透明白面板。
     * 修完必须有一张浅色截图能看出来 —— 深色截图里那条色带会被压暗吞掉。
     */
    @Test
    fun powerLight() = shot("04b_power_light", dark = false) {
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

    /** 底栏按下态：ripple 高亮块与底栏外框之间的间距（静止态看不出来） */
    @Test
    fun bottomBarPressed() = shot("61_bottom_bar_pressed", pressTag = "tab_Today") {
        com.gzucm.pocket.ui.MainScaffold(
            tab = com.gzucm.pocket.ui.Tab.Today,
            onSelect = {},
        ) {
            TodayScreen(semester, 8, sessions, power, daily, 0L, {}, {}, {}, {}, false, 0, 0, false, null)
        }
    }

    /** 扫码登录模式：提示必须真的看得见，不能被 WebView 盖住 */
    @Test
    fun loginQrHint() = shot(
        "70_login_qr",
        prepare = { rule.onNodeWithText("扫码登录").performClick() },
    ) {
        com.gzucm.pocket.ui.login.LoginScreen(onLoggedIn = { _, _ -> })
    }

    /** 扫码提示在浅色下也要看得清 */
    @Test
    fun loginQrHintLight() = shot(
        "71_login_qr_light",
        dark = false,
        prepare = { rule.onNodeWithText("扫码登录").performClick() },
    ) {
        com.gzucm.pocket.ui.login.LoginScreen(onLoggedIn = { _, _ -> })
    }

    /** 研究生学段：底栏只应有 3 个 tab，没有电费 */
    @Test
    fun scaffoldGraduate() = shot("80_scaffold_graduate") {
        com.gzucm.pocket.ui.MainScaffold(
            tab = com.gzucm.pocket.ui.Tab.Today,
            onSelect = {},
            campus = com.gzucm.pocket.core.Campus.GRADUATE,
        ) {
            TodayScreen(semester, 8, sessions, power, daily, 0L, {}, {}, {}, {}, false, 0, 0, false, null)
        }
    }

    /**
     * 研究生学段的电费页 —— 没登录一卡通时的空态。
     *
     * 验证三件事：
     *   ① 研究生学段底栏确实有「电费」这个 tab（之前被过滤掉了）
     *   ② 空态标题是「还没登录一卡通」，不是「读不到电费」
     *   ③ 按钮是「登录一卡通」而不是「同步校园卡」
     */
    @Test
    fun scaffoldGraduatePowerEmpty() = shot("82_graduate_power_empty") {
        com.gzucm.pocket.ui.MainScaffold(
            tab = com.gzucm.pocket.ui.Tab.Power,
            onSelect = {},
            campus = com.gzucm.pocket.core.Campus.GRADUATE,
        ) {
            com.gzucm.pocket.ui.power.PowerScreen(
                power = null,
                daily = emptyList(),
                loading = false,
                ecardLoggedIn = false,
            )
        }
    }

    /** 1 号那天明细为空时应该有提示，不是一片空白 */
    @Test
    fun powerEmptyDailyHint() = shot("83_power_empty_daily") {
        com.gzucm.pocket.ui.power.PowerScreen(
            power = com.gzucm.pocket.core.PowerInfo(
                balance = 144.09,
                unit = "度",
                roomLabel = "三元里6栋   211",
                token = "t",
                schoolAreaNo = "2",
                buildingNo = "5754",
                roomNum = "211",
            ),
            daily = emptyList(),
            loading = false,
            ecardLoggedIn = true,
        )
    }

    /** 登录页选中研究生学段：应显示「登录研究生系统」，且没有扫码按钮 */
    @Test
    fun loginGraduate() = shot(
        "81_login_graduate",
        prepare = { rule.onNodeWithText("研究生").performClick() },
    ) {
        com.gzucm.pocket.ui.login.LoginScreen(
            onLoggedIn = { _, _ -> },
            initialCampus = com.gzucm.pocket.core.Campus.UNDERGRAD,
        )
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

    /**
     * 设置页铺在**真实 Sheet 底色**上的样子。
     *
     * [settings] 那个用例直接把 SettingsScreen 塞进 `shot()` 的 Surface，
     * 底是主题 surface(#12151A) —— 跟 App 里 AdaptiveSheet 的容器色不是一回事。
     * 2026-11-19 致辉报的「深色下设置页底色跟面板完全一样」，只有在 Sheet 真底色上才看得出来，
     * 所以这个用例套一层 [micaSheetColor]（深色 #101318），验「页面 < Sheet < 卡片」拉不拉得开。
     */
    @Test
    fun settingsInSheet() = shot("15_settings_in_sheet") {
        Box(Modifier.fillMaxSize().background(micaSheetColor())) {
            com.gzucm.pocket.ui.settings.SettingsScreen(
                onSync = {}, onLogout = {}, onClose = {},
                lastSyncText = "10-30 12:05",
                semesterText = "2026-2027 学年 第一学期",
            )
        }
    }

    /** 导出课表子页面：两条路 + 「微信发不出去？发文件就可以」的排布 */
    @Test
    fun exportSheet() = shot("11_export") {
        com.gzucm.pocket.ui.settings.ExportContent(
            courseCount = 25,
            weeksCovered = 18,
            totalWeeks = 18,
            tokenChars = 1304,
            onBack = {},
            onCopyToken = {},
            onShareFile = {},
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

    /** 冲突课表 · 浅色模式：专门用来看文字可读性 */
    /**
     * 平板横屏（872dp，小米平板 6 Pro 的典型可用宽度）。
     *
     * 这一档应该切到左导航栏 + 内容区，而不是底部 Tab。
     * 用 411dp 的手机尺寸看不出区别，必须换 qualifiers。
     *
     * 内容故意用一个带文字的占位 Box —— 这里要验证的是**骨架分叉**，
     * 不是具体页面。接入真实页面是第二步的事。
     */
    @Test
    @Config(sdk = [34], qualifiers = "w872dp-h600dp-xhdpi")
    fun tabletWide() = shot("40_tablet_wide") {
        com.gzucm.pocket.ui.MainScaffold(tab = com.gzucm.pocket.ui.Tab.Today, onSelect = {}) {
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.fillMaxSize(),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                androidx.compose.material3.Text("内容区")
            }
        }
    }

    /**
     * 平板横屏的课表页（872dp）。
     *
     * 用冲突演示数据，能一次看到：时间轴加宽、字号变大、
     * 重叠课从层叠改成并排。
     */
    /** 平板横屏的成绩页（872dp）：学期块应该排成两列。 */
    /** 平板体测页：验证「项目名 / 数值」两列排布有没有挤到一起。 */
    @Test
    @Config(sdk = [34], qualifiers = "w872dp-h1000dp-xhdpi")
    fun fitnessTablet() = shot("54_fitness_tablet") {
        GradesScreen(
            grades, levels, fitness, false, 0, 0, null, 0L, {},
            initialTabIndex = 2,
        )
    }

    /** 手机体测页对照：两列在这个宽度下应该刚好，不能改坏。 */
    @Test
    fun fitnessPhone() = shot("55_fitness_phone") {
        GradesScreen(
            grades, levels, fitness, false, 0, 0, null, 0L, {},
            initialTabIndex = 2,
        )
    }

    @Test
    @Config(sdk = [34], qualifiers = "w872dp-h1000dp-xhdpi")
    fun gradesTablet() = shot("52_grades_tablet") {
        GradesScreen(grades, levels, fitness, false, 0, 0, null, 0L, {})
    }

    @Test
    @Config(sdk = [34], qualifiers = "w872dp-h600dp-xhdpi")
    fun tabletSchedule() = shot("42_tablet_schedule") {
        ScheduleScreen(
            com.gzucm.pocket.core.ConflictDemo.semester(),
            com.gzucm.pocket.core.ConflictDemo.sessions(),
            8, 8, {}, {},
        )
    }

    /** 平板竖屏（768dp）：应该还是底部 Tab（Compact 档）。 */
    @Test
    @Config(sdk = [34], qualifiers = "w768dp-h1000dp-xhdpi")
    fun tabletNarrow() = shot("41_tablet_narrow") {
        com.gzucm.pocket.ui.MainScaffold(tab = com.gzucm.pocket.ui.Tab.Today, onSelect = {}) {
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.fillMaxSize(),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                androidx.compose.material3.Text("内容区")
            }
        }
    }

    @Test
    fun conflictDemoLight() = shot("22_conflict_light", dark = false) {
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

    // ── 自定义日程 ────────────────────────────────────────────────

    /**
     * 三条日程，专门覆盖容易出问题的位置：
     *   ① 早于第一节（07:20）—— 网格里没有这一行
     *   ② 午休中间（12:20）—— 11:50 到 13:10 之间，网格里是空档
     *   ③ 和周一的「五官学」撞时段 —— 验证独立层不破坏课程排布
     */
    private val agendaRules = listOf(
        com.gzucm.pocket.core.Agenda(
            id = "a1", title = "晨读", date = "2026-10-26",
            startMinute = 7 * 60 + 20, endMinute = 8 * 60 + 10,
            place = "图书馆",
        ),
        com.gzucm.pocket.core.Agenda(
            id = "a2", title = "部门例会", date = "2026-10-26",
            startMinute = 12 * 60 + 20, endMinute = 13 * 60 + 5,
            place = "融媒体中心", colorIndex = 2,
        ),
        com.gzucm.pocket.core.Agenda(
            id = "a3", title = "见习报到", date = "2026-10-26",
            startMinute = 10 * 60 + 30, endMinute = 11 * 60 + 40,
            place = "一附院", colorIndex = 3,
        ),
        com.gzucm.pocket.core.Agenda(
            id = "a4", title = "每周病例讨论", date = "2026-10-27",
            startMinute = 19 * 60, endMinute = 20 * 60 + 30,
            // 带地点：验证「标题 / 地点 / 起止时间」三段能不能在一格里排开
            place = "一附院示教室",
            weekly = true, colorIndex = 4,
        ),
    )

    private fun agendaOccurrences() =
        com.gzucm.pocket.core.AgendaLogic.expand(semester, agendaRules)

    @Test
    fun scheduleWithAgenda() = shot("23_schedule_agenda") {
        ScheduleScreen(
            semester = semester,
            sessions = sessions,
            currentWeek = 8,
            viewingWeek = 8,
            onWeekChange = {},
            onCourseClick = {},
            agendaRules = agendaRules,
            agendaOccurrences = agendaOccurrences(),
        )
    }

    @Test
    fun scheduleWithAgendaLight() = shot("23_schedule_agenda_light", dark = false) {
        ScheduleScreen(
            semester = semester,
            sessions = sessions,
            currentWeek = 8,
            viewingWeek = 8,
            onWeekChange = {},
            onCourseClick = {},
            agendaRules = agendaRules,
            agendaOccurrences = agendaOccurrences(),
        )
    }

    @Test
    fun dayViewWithAgenda() = shot("23_day_agenda") {
        // currentWeek 故意给 7：week(8) != currentWeek 时才会落到 initialDay，
        // 否则会停在「今天」（跑测试那天是周六，只有一门课）
        com.gzucm.pocket.ui.schedule.DayScheduleView(
            semester = semester,
            week = 8,
            currentWeek = 7,
            sessions = sessions,
            onCourseClick = {},
            agendaOccurrences = agendaOccurrences(),
            initialDay = 1,
        )
    }

    @Test
    fun todayWithAgenda() = shot("23_today_agenda") {
        /*
         * 今日页按 LocalDate.now() 取"今天"，所以这里必须造一条今天的日程，
         * 用 10 月那批测试数据是看不到的（截图会显示真实当天）。
         */
        val today = java.time.LocalDate.now()
        val todayOcc = listOf(
            com.gzucm.pocket.core.AgendaOccurrence(
                key = "t1|$today", sourceId = "t1", title = "病例讨论",
                date = today, week = semester.weekOf(today),
                weekday = today.dayOfWeek.value,
                startMinute = 19 * 60, endMinute = 20 * 60 + 30,
                place = "一附院示教室", note = "带病历本", colorIndex = 2,
                unitStart = 0, unitEnd = 0, weekly = false,
            ),
        )
        TodayScreen(
            semester, 8, sessions, power, daily, 0L, {}, {}, {}, {}, false, 0, 0, false, null,
            agendaOccurrences = agendaOccurrences() + todayOcc,
        )
    }

    @Test
    @Config(sdk = [34], qualifiers = "w900dp-h1000dp-xhdpi")
    fun agendaList() = shot("24_agenda_list", settleMs = 1200) {
        com.gzucm.pocket.ui.schedule.AgendaListSheet(
            visible = true,
            semester = semester,
            rules = agendaRules,
            onAdd = { _, _ -> },
            onEdit = {},
            onDismiss = {},
        )
    }

    @Test
    @Config(sdk = [34], qualifiers = "w900dp-h1000dp-xhdpi")
    fun agendaEditNew() = shot("24_agenda_edit_new", settleMs = 1200) {
        com.gzucm.pocket.ui.schedule.AgendaEditSheet(
            visible = true,
            semester = semester,
            initial = null,
            draftDate = semester.dateOf(8, 3),
            draftStartMinute = 14 * 60,
            onSave = {},
            onDelete = {},
            onDismiss = {},
        )
    }

    @Test
    @Config(sdk = [34], qualifiers = "w900dp-h1000dp-xhdpi")
    fun agendaEditExisting() = shot("24_agenda_edit_existing", settleMs = 1200) {
        com.gzucm.pocket.ui.schedule.AgendaEditSheet(
            visible = true,
            semester = semester,
            initial = agendaRules[2],
            draftDate = null,
            draftStartMinute = null,
            onSave = {},
            onDelete = {},
            onDismiss = {},
        )
    }

    // ── 2026-11-20：首页天气卡 / 接下来 / 首页显示管理 ──────────────

    /**
     * 一份假天气。
     *
     * 测试环境没有和风 key（`Weather.enabled == false`），所以线上那条路是「整块不渲染」；
     * 但 [com.gzucm.pocket.ui.today.TodayScreen] 的判定是
     * `sections.weather && (Weather.enabled || weather != null)` ——
     * 直接注入一份数据就能把卡片渲染出来，正好用来盯排版。
     */
    private fun demoWeather(): com.gzucm.pocket.core.Weather.Info {
        val today = java.time.LocalDate.now()
        fun d(offset: Long, text: String, icon: String, max: Int, min: Int) =
            com.gzucm.pocket.core.Weather.Day(
                date = today.plusDays(offset).toString(),
                text = text,
                icon = icon,
                max = max,
                min = min,
            )
        return com.gzucm.pocket.core.Weather.Info(
            city = com.gzucm.pocket.core.Weather.CityLabel,
            temp = 14,
            feelsLike = 11,
            humidity = 63,
            text = "阴",
            icon = "104",
            days = listOf(
                d(0, "阴", "104", 18, 12),
                d(1, "小雨", "305", 17, 11),
                d(2, "多云", "101", 21, 13),
            ),
            // 12 分钟前更新：卡片右上角应该显示「12 分钟前」
            fetchedAt = System.currentTimeMillis() - 12 * 60_000L,
        )
    }

    /** 未来几天的日程 / 考试，用来验「接下来」卡的五条上限和考试红点 */
    private fun demoUpcoming(): List<com.gzucm.pocket.core.AgendaOccurrence> {
        val t = java.time.LocalDate.now()
        fun o(offset: Long, title: String, h: Int, m: Int, place: String, exam: Boolean = false) =
            com.gzucm.pocket.core.AgendaOccurrence(
                key = "demo-$title",
                sourceId = "demo-$title",
                title = title,
                date = t.plusDays(offset),
                week = 8,
                weekday = t.plusDays(offset).dayOfWeek.value,
                startMinute = h * 60 + m,
                endMinute = h * 60 + m + 90,
                place = place,
                note = if (exam) "闭卷" else "",
                colorIndex = if (exam) com.gzucm.pocket.core.EXAM_COLOR_INDEX else 0,
                unitStart = 0,
                unitEnd = 0,
                weekly = false,
            )
        return listOf(
            o(1, "内科学见习", 8, 0, "内科楼 302"),
            o(2, "中医各家学说 随堂测", 14, 30, "新课室楼 501", exam = true),
            o(4, "摄影部例会", 19, 0, "融媒体中心"),
            o(6, "针灸科见习", 8, 30, "针灸科门诊"),
            o(9, "年度体测", 9, 0, "田径场"),
        )
    }

    @Test
    fun todayWeather() = shot("26_today_weather") {
        TodayScreen(
            semester, 8, sessions, power, daily, 0L, {}, {}, {}, {}, false, 0, 0, false, null,
            agendaOccurrences = demoUpcoming(),
            sections = com.gzucm.pocket.core.TodaySections(),
            weather = demoWeather(),
        )
    }

    @Test
    fun todayWeatherLight() = shot("27_today_weather_light", dark = false) {
        TodayScreen(
            semester, 8, sessions, power, daily, 0L, {}, {}, {}, {}, false, 0, 0, false, null,
            agendaOccurrences = demoUpcoming(),
            sections = com.gzucm.pocket.core.TodaySections(),
            weather = demoWeather(),
        )
    }

    /** 关掉电费和本周概览：只剩天气 + 课程 + 接下来 */
    @Test
    fun todaySectionsMinimal() = shot("28_today_minimal") {
        TodayScreen(
            semester, 8, sessions, power, daily, 0L, {}, {}, {}, {}, false, 0, 0, false, null,
            agendaOccurrences = demoUpcoming(),
            sections = com.gzucm.pocket.core.TodaySections(
                // 只给这三块 → normalized 把电费和本周概览补成全量顺序里的隐藏项
                allOrder = listOf(
                    com.gzucm.pocket.core.TodayBlock.WEATHER,
                    com.gzucm.pocket.core.TodayBlock.COURSE,
                    com.gzucm.pocket.core.TodayBlock.UPCOMING,
                ),
            ).normalized(),
            weather = demoWeather(),
        )
    }

    @Test
    fun todaySectionsSheet() = shot("29_today_sections", dark = false) {
        com.gzucm.pocket.ui.settings.TodaySectionsContent(
            sections = com.gzucm.pocket.core.TodaySections(upcomingDays = 7),
            onChange = {},
            showBack = true,
            onBack = {},
        )
    }

    /** 减掉两块之后的样子：剩下的一组分在上面，减掉的沉到最下面、字变灰、按钮变 + */
    @Test
    fun todaySectionsHidden() = shot("30_today_sections_hidden", dark = false) {
        com.gzucm.pocket.ui.settings.TodaySectionsContent(
            sections = com.gzucm.pocket.core.TodaySections(
                allOrder = listOf(
                    com.gzucm.pocket.core.TodayBlock.POWER,
                    com.gzucm.pocket.core.TodayBlock.COURSE,
                    com.gzucm.pocket.core.TodayBlock.UPCOMING,
                ),
            ).normalized(),
            onChange = {},
            showBack = true,
            onBack = {},
        )
    }

    /** 四块全减掉：今日页只剩课程，面板给一句说明而不是一片空白 */
    @Test
    fun todaySectionsAllHidden() = shot("31_today_sections_empty", dark = false) {
        com.gzucm.pocket.ui.settings.TodaySectionsContent(
            sections = com.gzucm.pocket.core.TodaySections(
                allOrder = listOf(com.gzucm.pocket.core.TodayBlock.COURSE),
            ).normalized(),
            onChange = {},
            showBack = true,
            onBack = {},
        )
    }
}
