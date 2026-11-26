package com.gzucm.pocket

import com.gzucm.pocket.widget.SnapshotSession
import com.gzucm.pocket.widget.WidgetRender
import com.gzucm.pocket.widget.WidgetSnapshotData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 部件渲染的切片逻辑。
 *
 * 部件最容易翻车的不是「画得丑」，而是**时间判断错**：
 * 周三早上显示周二的课、课间十分钟说「正在上」、周一第一节显示「今天没课了」。
 * 这类 bug 只有把「现在几点」当参数传进来才测得出来 ——
 * 所以渲染层做成了纯函数，这里全部用固定时间跑。
 *
 * 基准：2026-09-07 是周一，作为第 1 周周一。
 */
class WidgetRenderTest {

    private val monday = LocalDate.of(2026, 9, 7)

    private fun s(
        name: String,
        weekday: Int,
        startUnit: Int,
        endUnit: Int,
        startTime: String,
        endTime: String,
        week: Int = 1,
        room: String = "综教楼101",
    ) = SnapshotSession(
        week = week,
        weekday = weekday,
        startUnit = startUnit,
        endUnit = endUnit,
        startTime = startTime,
        endTime = endTime,
        name = name,
        room = room,
    )

    private fun snapshot(vararg sessions: SnapshotSession) = WidgetSnapshotData(
        semesterStart = monday.toString(),
        totalWeeks = 20,
        sessions = sessions.toList(),
    )

    /** 第 [dayOffset] 天的 hh:mm（dayOffset=0 是周一） */
    private fun at(dayOffset: Int, h: Int, m: Int): LocalDateTime =
        monday.plusDays(dayOffset.toLong()).atTime(h, m)

    /** 周一的常规课表：1-2 节中药学、3-4 节中医诊断学 */
    private fun normalMonday() = snapshot(
        s("中药学", weekday = 1, startUnit = 1, endUnit = 2, startTime = "08:30", endTime = "10:00"),
        s("中医诊断学", weekday = 1, startUnit = 3, endUnit = 4, startTime = "10:20", endTime = "11:50"),
    )

    // ── 1. 正在上课 ────────────────────────────────────────────────

    @Test
    fun `正在上第3节时显示这节课和下课时间`() {
        // 10:40 落在 10:20-11:50 之间
        assertEquals(
            "中医诊断学 · 至 11:50",
            WidgetRender.currentLine(normalMonday(), at(0, 10, 40)),
        )
    }

    @Test
    fun `上课第一分钟就算正在上`() {
        assertEquals(
            "中医诊断学 · 至 11:50",
            WidgetRender.currentLine(normalMonday(), at(0, 10, 20)),
        )
    }

    @Test
    fun `下课那一分钟不算正在上`() {
        // 11:50 是下课时刻，此时应该已经指向「下一节」或者「今天没课了」
        assertEquals(
            WidgetRender.NoMoreText,
            WidgetRender.currentLine(normalMonday(), at(0, 11, 50)),
        )
    }

    // ── 2. 课间 ────────────────────────────────────────────────────

    @Test
    fun `两节课之间显示下一节`() {
        // 10:00 下课、10:20 上课，10:05 是课间
        assertEquals(
            "下一节 10:20 中医诊断学",
            WidgetRender.currentLine(normalMonday(), at(0, 10, 5)),
        )
    }

    @Test
    fun `早八之前显示第一节`() {
        assertEquals(
            "下一节 08:30 中药学",
            WidgetRender.currentLine(normalMonday(), at(0, 7, 0)),
        )
    }

    // ── 3. 今天上完了 ─────────────────────────────────────────────

    @Test
    fun `今天的课都上完了显示今天没课了`() {
        assertEquals(
            "今天没课了",
            WidgetRender.currentLine(normalMonday(), at(0, 21, 0)),
        )
    }

    // ── 4. 周日 / 没课的日子 ───────────────────────────────────────

    @Test
    fun `周日没有课时显示今天没课了`() {
        // 2026-09-13 是第 1 周的周日，这一天一条课都没有
        assertEquals(
            "今天没课了",
            WidgetRender.currentLine(normalMonday(), at(6, 10, 0)),
        )
    }

    @Test
    fun `学期开始之前显示今天没课了`() {
        val now = monday.minusDays(3).atTime(10, 0)
        assertEquals("今天没课了", WidgetRender.currentLine(normalMonday(), now))
    }

    @Test
    fun `学期结束之后显示今天没课了`() {
        // 第 20 周之后
        val now = monday.plusWeeks(21).atTime(10, 0)
        assertEquals("今天没课了", WidgetRender.currentLine(normalMonday(), now))
    }

    // ── 5. 快照为空 ───────────────────────────────────────────────

    @Test
    fun `没有快照时提示打开App同步课表`() {
        assertEquals("打开 App 同步课表", WidgetRender.currentLine(null, at(0, 10, 0)))
        assertEquals(
            "打开 App 同步课表",
            WidgetRender.currentLine(snapshot(), at(0, 10, 0)),
        )
    }

    @Test
    fun `空态文案区分没同步和今天没课`() {
        assertEquals("打开 App 同步课表", WidgetRender.emptyText(null))
        assertEquals("打开 App 同步课表", WidgetRender.emptyText(snapshot()))
        assertEquals("今天没课了", WidgetRender.emptyText(normalMonday()))
    }

    // ── 6. 三天视图 ───────────────────────────────────────────────

    @Test
    fun `三天视图把今天明天后天的课分到各自列`() {
        val data = snapshot(
            s("中药学", weekday = 1, startUnit = 1, endUnit = 2, startTime = "08:30", endTime = "10:00"),
            s("方剂学", weekday = 2, startUnit = 3, endUnit = 4, startTime = "10:20", endTime = "11:50"),
            s("中医内科学", weekday = 3, startUnit = 5, endUnit = 6, startTime = "13:10", endTime = "14:40"),
        )
        val cols = WidgetRender.threeDays(data, at(0, 7, 0))

        assertEquals(3, cols.size)
        assertEquals(listOf("今天", "明天", "后天"), cols.map { it.label })
        assertEquals(listOf("中药学"), cols[0].courses.map { it.name })
        assertEquals(listOf("方剂学"), cols[1].courses.map { it.name })
        assertEquals(listOf("中医内科学"), cols[2].courses.map { it.name })
        assertEquals("9月7日", cols[0].dateText)
        assertEquals("9月8日", cols[1].dateText)
        assertEquals("9月9日", cols[2].dateText)
        assertTrue(cols[0].isToday)
        assertTrue(!cols[1].isToday)
    }

    @Test
    fun `三天视图跨周时明天的课按下一周算`() {
        // 周日看「明天」= 下周一（第 2 周）
        val data = snapshot(
            s("第2周周一课", weekday = 1, startUnit = 1, endUnit = 2, startTime = "08:30", endTime = "10:00", week = 2),
        )
        val cols = WidgetRender.threeDays(data, at(6, 20, 0))
        assertEquals(emptyList<String>(), cols[0].courses.map { it.name })
        assertEquals(listOf("第2周周一课"), cols[1].courses.map { it.name })
    }

    // ── 周次切片 ──────────────────────────────────────────────────

    @Test
    fun `只显示当前周的课`() {
        val data = snapshot(
            s("第1周课", weekday = 1, startUnit = 1, endUnit = 2, startTime = "08:30", endTime = "10:00", week = 1),
            s("第2周课", weekday = 1, startUnit = 3, endUnit = 4, startTime = "10:20", endTime = "11:50", week = 2),
        )
        // 第 2 周周一
        val names = WidgetRender.coursesOn(data, monday.plusWeeks(1)).map { it.name }
        assertEquals(listOf("第2周课"), names)
    }

    @Test
    fun `周日算在当周而不是下一周`() {
        val data = snapshot(
            s("周日课", weekday = 7, startUnit = 1, endUnit = 2, startTime = "08:30", endTime = "10:00", week = 1),
        )
        assertEquals(1, WidgetRender.weekOf(data, monday.plusDays(6)))
        assertEquals(listOf("周日课"), WidgetRender.coursesOn(data, monday.plusDays(6)).map { it.name })
    }

    @Test
    fun `表头带周次和日期`() {
        assertEquals("第 1 周 · 9月7日 周一", WidgetRender.header(normalMonday(), monday))
        assertEquals("第 2 周 · 9月14日 周一", WidgetRender.header(normalMonday(), monday.plusWeeks(1)))
    }

    @Test
    fun `没有快照时表头只显示日期`() {
        assertEquals("9月7日 周一", WidgetRender.header(null, monday))
    }

    // ── 2x2 接下来 ───────────────────────────────────────────────

    @Test
    fun `接下来包含正在上的课且最多三节`() {
        val data = snapshot(
            s("A", weekday = 1, startUnit = 1, endUnit = 1, startTime = "08:30", endTime = "09:10"),
            s("B", weekday = 1, startUnit = 2, endUnit = 2, startTime = "09:20", endTime = "10:00"),
            s("C", weekday = 1, startUnit = 3, endUnit = 3, startTime = "10:20", endTime = "11:00"),
            s("D", weekday = 1, startUnit = 4, endUnit = 4, startTime = "11:10", endTime = "11:50"),
        )
        // 09:30：B 正在上，接下来是 C、D
        val names = WidgetRender.upcoming(data, at(0, 9, 30), max = 3).map { it.name }
        assertEquals(listOf("B", "C", "D"), names)
    }

    @Test
    fun `已经上完的课不进接下来`() {
        val names = WidgetRender.upcoming(normalMonday(), at(0, 11, 0), max = 3).map { it.name }
        // 中药学 10:00 就结束了
        assertEquals(listOf("中医诊断学"), names)
    }

    // ── 4x2 横排 ─────────────────────────────────────────────────

    @Test
    fun `横排包含上完的课并统计溢出`() {
        val data = snapshot(
            *(1..7).map { i ->
                s("课$i", weekday = 1, startUnit = i, endUnit = i, startTime = "%02d:%02d".format(8 + i, 0), endTime = "%02d:%02d".format(8 + i, 40))
            }.toTypedArray(),
        )
        val result = WidgetRender.todayHorizontal(data, at(0, 20, 0), max = 5)
        assertEquals(5, result.courses.size)
        assertEquals(7, result.total)
        assertEquals(2, result.overflow)
        // 横排不按「还没上」过滤：早上 8 点的课 20:00 也要在
        assertEquals("课1", result.courses.first().name)
    }

    // ── 4x4 时间轴 ───────────────────────────────────────────────

    @Test
    fun `时间轴固定12行且大课只在起始行标课程名`() {
        val data = snapshot(
            s("中医诊断学", weekday = 1, startUnit = 3, endUnit = 5, startTime = "10:20", endTime = "12:30"),
        )
        val rows = WidgetRender.timeline(data, at(0, 10, 40), 1, 12)

        assertEquals(12, rows.size)
        assertEquals((1..12).toList(), rows.map { it.unit })
        assertEquals("08:30", rows[0].time)
        assertEquals("10:20", rows[2].time)

        // 3、4、5 节都指向同一门课，但只有第 3 节是起始行
        assertEquals(listOf(3, 4, 5), rows.filter { it.course != null }.map { it.unit })
        assertEquals(listOf(3), rows.filter { it.isStart }.map { it.unit })
        assertTrue(rows[2].ongoing)
        assertTrue(rows[4].ongoing)
        assertEquals(null, rows[1].course)
    }

    @Test
    fun `时间轴没有课时所有行都是空节`() {
        val rows = WidgetRender.timeline(snapshot(), at(0, 10, 0))
        assertEquals(12, rows.size)
        assertTrue(rows.all { it.course == null && !it.isStart && !it.ongoing })
    }

    // ── 防御性：脏数据 ────────────────────────────────────────────

    @Test
    fun `学期起始日非法时按没课处理而不是崩`() {
        val data = WidgetSnapshotData(
            semesterStart = "不是日期",
            totalWeeks = 20,
            sessions = listOf(
                s("中药学", weekday = 1, startUnit = 1, endUnit = 2, startTime = "08:30", endTime = "10:00"),
            ),
        )
        assertNull(WidgetRender.weekOf(data, monday))
        assertEquals("今天没课了", WidgetRender.currentLine(data, at(0, 10, 0)))
    }

    @Test
    fun `同一节课重复两条只显示一次`() {
        val data = snapshot(
            s("中药学", weekday = 1, startUnit = 1, endUnit = 2, startTime = "08:30", endTime = "10:00"),
            s("中药学", weekday = 1, startUnit = 1, endUnit = 2, startTime = "08:30", endTime = "10:00"),
        )
        assertEquals(1, WidgetRender.coursesOn(data, monday).size)
    }
}
