package com.gzucm.pocket

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.gzucm.pocket.core.ConflictDemo
import com.gzucm.pocket.ui.schedule.ScheduleScreen
import com.gzucm.pocket.ui.theme.GzucmTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference

/**
 * 长按课表空白格新建日程必须真的能触发（致辉 2026-11-14 报「没反应」）。
 *
 * 根因不在手势本身，而在列的**高度**：DayColumn 原来用 fillMaxHeight，
 * 但它在 verticalScroll 里，拿到的是无限高度约束 —— fillMaxHeight 等于没写，
 * 整列高度退化成「最高那个课程块有多高」，没课的列是 0。
 * 长按热区是 fillMaxSize，跟着缩成一条缝甚至 0，手指永远碰不到。
 *
 * 所以这里除了验证回调，还先断言热区高度 —— 这一条才是回归的真正抓手。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h874dp-xhdpi")
class AgendaLongPressTest {

    @get:Rule
    val rule = createComposeRule()

    private val semester = ConflictDemo.semester()

    @Test
    fun `长按空白列回传那一天的日期和分钟`() {
        val got = AtomicReference<Pair<LocalDate, Int>?>(null)
        /*
         * tag 带周次（第 8 周周三）。
         *
         * 课表用了 beyondViewportPageCount = 1，同一时刻左右相邻两页也在组合树里，
         * 只用「-星期」会在树上出现 3 个同名节点，取节点直接失败。
         */
        val tag = "week-grid-longpress-8-3"

        rule.setContent {
            GzucmTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize()) {
                    ScheduleScreen(
                        // 空的课表：每一格都是「空白格」，排除课程块抢走手势的干扰
                        sessions = emptyList(),
                        semester = semester,
                        currentWeek = 8,
                        viewingWeek = 8,
                        onWeekChange = {},
                        onCourseClick = {},
                        onAddAgenda = { date, minute -> got.set(date to (minute ?: -1)) },
                    )
                }
            }
        }
        rule.waitForIdle()

        // 9 个节次 × 58dp = 522dp。缩水的实现只会剩下几十 dp 甚至 0。
        rule.onNodeWithTag(tag).assertHeightIsAtLeast(500.dp)

        rule.onNodeWithTag(tag).performTouchInput {
            longClick(Offset(width / 2f, height * 0.9f))
        }
        rule.waitForIdle()

        val v = got.get()
        assertNotNull("长按空白格应该打开新建日程（回传日期和分钟）", v)
        assertEquals("日期应是查看周里的那一天（第 8 周周三）", semester.dateOf(8, 3), v!!.first)
        assertTrue("分钟应落在一天之内", v.second in 0..1439)
    }
}
