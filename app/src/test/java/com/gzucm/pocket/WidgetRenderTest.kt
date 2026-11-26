package com.gzucm.pocket

import com.gzucm.pocket.widget.SnapshotSession
import com.gzucm.pocket.widget.TodayRowState
import com.gzucm.pocket.widget.WeekCellRole
import com.gzucm.pocket.widget.WeekColorCount
import com.gzucm.pocket.widget.WeekRowHeightDp
import com.gzucm.pocket.widget.WeekUnitCount
import com.gzucm.pocket.widget.WidgetRender
import com.gzucm.pocket.widget.WidgetSnapshotData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        teacher: String = "",
    ) = SnapshotSession(
        week = week,
        weekday = weekday,
        startUnit = startUnit,
        endUnit = endUnit,
        startTime = startTime,
        endTime = endTime,
        name = name,
        room = room,
        teacher = teacher,
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

    // ── 2x2 / 4x2 两张卡片 ────────────────────────────────────────

    @Test
    fun `两卡片_正在上课时第一张卡是当前课且高亮`() {
        val data = WidgetRender.todayCards(normalMonday(), at(0, 10, 40))

        assertNull("有课时不该给空态文案", data.emptyText)
        assertEquals(1, data.cards.size)
        val first = data.cards.first()
        assertEquals("中医诊断学", first.name)
        assertEquals("10:20", first.startText)
        assertEquals("11:50", first.endText)
        assertTrue("正在上的课必须高亮", first.ongoing)
    }

    @Test
    fun `两卡片_正在上课时第二张卡是再下一节`() {
        val data = snapshot(
            s("外科学(2)", weekday = 1, startUnit = 3, endUnit = 4, startTime = "10:20", endTime = "11:50", room = "综教808", teacher = "张伟"),
            s("中医骨伤科学", weekday = 1, startUnit = 5, endUnit = 6, startTime = "13:10", endTime = "14:40", room = "新课室202", teacher = "李娜"),
        )
        val cards = WidgetRender.todayCards(data, at(0, 10, 40)).cards

        assertEquals(2, cards.size)
        assertEquals("外科学(2)", cards[0].name)
        assertTrue(cards[0].ongoing)
        assertEquals("中医骨伤科学", cards[1].name)
        assertFalse("下一节不能用主题色", cards[1].ongoing)
        // 4x2 才有的两栏
        assertEquals("综教808", cards[0].room)
        assertEquals("张伟", cards[0].teacher)
        assertEquals("新课室202", cards[1].room)
    }

    @Test
    fun `两卡片_课间时第一张卡是下一节且不高亮`() {
        // 10:00 下课、10:20 上课，10:05 是课间
        val data = WidgetRender.todayCards(normalMonday(), at(0, 10, 5))

        assertEquals("中医诊断学", data.cards.first().name)
        assertFalse("课间不能高亮，否则用户以为已经上课了", data.cards.first().ongoing)
    }

    @Test
    fun `两卡片_课都上完后合并成今天没课了`() {
        val data = WidgetRender.todayCards(normalMonday(), at(0, 21, 0))

        assertTrue(data.cards.isEmpty())
        assertEquals("今天没课了", data.emptyText)
    }

    @Test
    fun `两卡片_周日合并成今天没课了`() {
        val data = WidgetRender.todayCards(normalMonday(), at(6, 10, 0))
        assertTrue(data.cards.isEmpty())
        assertEquals("今天没课了", data.emptyText)
    }

    @Test
    fun `两卡片_没同步过提示去App同步`() {
        val data = WidgetRender.todayCards(null, at(0, 10, 0))
        assertTrue(data.cards.isEmpty())
        assertEquals("打开 App 同步课表", data.emptyText)
    }

    @Test
    fun `多个老师只取第一个`() {
        val data = snapshot(
            s("外科学", weekday = 1, startUnit = 3, endUnit = 4, startTime = "10:20", endTime = "11:50", teacher = "张伟,李娜"),
        )
        assertEquals("张伟", WidgetRender.todayCards(data, at(0, 10, 40)).cards.first().teacher)
    }

    @Test
    fun `老快照没有老师字段时老师为空串不崩`() {
        // SnapshotSession.teacher 有默认值，老 JSON 解出来就是 ""
        val data = snapshot(
            s("外科学", weekday = 1, startUnit = 3, endUnit = 4, startTime = "10:20", endTime = "11:50"),
        )
        val card = WidgetRender.todayCards(data, at(0, 10, 40)).cards.first()
        assertEquals("", card.teacher)
        assertEquals("综教楼101", card.room)
    }

    // ── 4x4 今日课程 ──────────────────────────────────────────────

    @Test
    fun `4x4_跨两节的连堂课只出一条而不是两条`() {
        // 这是用户提的原问题：按节次排的时候 1-2 节会变成两行、课名重复两遍
        val data = snapshot(
            s("外科学", weekday = 1, startUnit = 1, endUnit = 2, startTime = "08:30", endTime = "10:00"),
        )
        val list = WidgetRender.todayCourses(data, at(0, 7, 0))

        assertNull(list.emptyText)
        assertEquals(1, list.rows.size)
        assertEquals("1-2", list.rows[0].unitText)
        assertEquals("08:30", list.rows[0].timeText)
        assertEquals("外科学", list.rows[0].name)
    }

    @Test
    fun `4x4_上完的课被过滤正在上的保留`() {
        // 10:40：中药学（10:00 下课）不该再出现，中医诊断学正在上要留着
        val list = WidgetRender.todayCourses(normalMonday(), at(0, 10, 40))

        assertEquals(listOf("中医诊断学"), list.rows.map { it.name })
        assertEquals(TodayRowState.ONGOING, list.rows[0].state)
    }

    @Test
    fun `4x4_课间时下一节是UPCOMING`() {
        val list = WidgetRender.todayCourses(normalMonday(), at(0, 10, 5))
        assertEquals(listOf("中医诊断学"), list.rows.map { it.name })
        assertEquals(TodayRowState.UPCOMING, list.rows[0].state)
    }

    @Test
    fun `4x4_今天课都上完时给空态文案`() {
        val list = WidgetRender.todayCourses(normalMonday(), at(0, 21, 0))
        assertTrue(list.rows.isEmpty())
        assertEquals("今天没课了", list.emptyText)
    }

    @Test
    fun `4x4_空快照时提示去App同步`() {
        val list = WidgetRender.todayCourses(snapshot(), at(0, 10, 0))
        assertTrue(list.rows.isEmpty())
        assertEquals("打开 App 同步课表", list.emptyText)
    }

    @Test
    fun `4x4_超过七门只出七条且没有还有N节`() {
        val data = snapshot(
            *(1..8).map { i ->
                s("课$i", weekday = 1, startUnit = i, endUnit = i, startTime = "%02d:00".format(7 + i), endTime = "%02d:40".format(7 + i))
            }.toTypedArray(),
        )
        val list = WidgetRender.todayCourses(data, at(0, 6, 0), max = 7)

        assertEquals(7, list.rows.size)
        assertEquals("课7", list.rows.last().name)
        // 用户明确说超出就不显示，不再有「还有 N 节」那一行
        assertTrue(list.rows.none { it.name.contains("还有") })
    }

    @Test
    fun `4x4_少于四门时按四门的高度分配`() {
        // 1 门课只占 1/4 高，不会被撑成一整块；5 门以上才真正按门数均分
        assertEquals(4, WidgetRender.widgetRowsToAllot(0))
        assertEquals(4, WidgetRender.widgetRowsToAllot(1))
        assertEquals(4, WidgetRender.widgetRowsToAllot(3))
        assertEquals(4, WidgetRender.widgetRowsToAllot(4))
        assertEquals(5, WidgetRender.widgetRowsToAllot(5))
        assertEquals(7, WidgetRender.widgetRowsToAllot(7))
        assertEquals(7, WidgetRender.widgetRowsToAllot(8))
    }

    // ── 4x3 周视图 ────────────────────────────────────────────────

    @Test
    fun `周视图同一门课跨周颜色一致`() {
        val data = snapshot(
            s("中医骨伤科学", weekday = 1, startUnit = 3, endUnit = 4, startTime = "10:20", endTime = "11:50", week = 1),
            s("中医骨伤科学", weekday = 1, startUnit = 3, endUnit = 4, startTime = "10:20", endTime = "11:50", week = 2),
        )
        // 列 1 = 周一
        val week1 = WidgetRender.weekGrid(data, at(0, 7, 0))
        val week2 = WidgetRender.weekGrid(data, at(7, 7, 0))

        assertTrue(week1.cell(3, 1).colorIndex in 0 until WeekColorCount)
        assertEquals(week1.cell(3, 1).colorIndex, week2.cell(3, 1).colorIndex)
        assertEquals(week1.cell(3, 1).colorIndex, WidgetRender.colorIndexFor("中医骨伤科学"))
    }

    @Test
    fun `周视图第1到2节连上的课跨两行`() {
        val data = snapshot(
            s("外科学", weekday = 1, startUnit = 1, endUnit = 2, startTime = "08:30", endTime = "10:00"),
        )
        val grid = WidgetRender.weekGrid(data, at(0, 7, 0))

        val start = grid.cell(1, 1)
        assertTrue(start.isStart)
        assertEquals(2, start.span)
        assertEquals(WeekRowHeightDp * 2, start.heightDp)
        assertEquals("外科学", start.name)
        assertEquals("08:30", start.time)

        // 第 2 节是同色留空的续格
        val second = grid.cell(2, 1)
        assertTrue(second.covered)
        assertFalse(second.isStart)
        assertNull(second.name)
        assertEquals(start.colorIndex, second.colorIndex)
    }

    @Test
    fun `周视图是7列12节且今天列按周日在前算`() {
        // 2026-09-07 是周一 → 列 1
        val grid = WidgetRender.weekGrid(normalMonday(), at(0, 7, 0))

        assertEquals(84, grid.cells.size)
        assertEquals(1, grid.todayCol)
        assertEquals(listOf("日", "一", "二", "三", "四", "五", "六"), grid.weekdays)
        // 周日在前：9月7日那周的周日是 9月6日
        assertEquals("6", grid.dates.first())
        assertEquals("12", grid.dates.last())
        // 周一的课落在列 1
        assertEquals("中药学", grid.cell(1, 1).name)
        assertNull(grid.cell(1, 0).name)
    }

    @Test
    fun `周视图表头说明今天的状态`() {
        assertEquals("第 1 周 · 正在上 中医诊断学", WidgetRender.weekHeader(normalMonday(), at(0, 10, 40)))
        assertEquals("第 1 周 · 下一节 10:20", WidgetRender.weekHeader(normalMonday(), at(0, 10, 5)))
        assertEquals("第 1 周 · 今日课程已结束", WidgetRender.weekHeader(normalMonday(), at(0, 21, 0)))
        assertEquals("打开 App 同步课表", WidgetRender.weekHeader(null, at(0, 10, 0)))
    }

    @Test
    fun `周视图行数固定12节 行号才等于节次`() {
        assertEquals(12, WeekUnitCount)
        // 一节课都没有时也要有 12 行占位，否则 ListView 的行号对不上节次
        val empty = WidgetRender.weekGrid(snapshot(), at(0, 9, 0))
        assertEquals(WeekUnitCount, empty.units.size)
        assertEquals(WeekUnitCount * 7, empty.cells.size)
    }

    @Test
    fun `当前节次行号按课时表算且课间落在下一节`() {
        // 早于第一节 → 第 1 行
        assertEquals(0, WidgetRender.currentRow(at(0, 7, 0)))
        assertEquals(0, WidgetRender.currentRow(at(0, 8, 30)))

        // 09:10–09:20 是第 1、2 节之间的课间 → 停在还没开始的第 2 节
        assertEquals(1, WidgetRender.currentRow(at(0, 9, 15)))
        assertEquals(1, WidgetRender.currentRow(at(0, 9, 30)))

        // 10:00–10:20 大课间 → 第 3 节
        assertEquals(2, WidgetRender.currentRow(at(0, 10, 10)))

        // 12:00 午休 → 下午第一节（第 5 节）
        assertEquals(4, WidgetRender.currentRow(at(0, 12, 59)))

        // 最后一节之后不越界
        assertEquals(WeekUnitCount - 1, WidgetRender.currentRow(at(0, 21, 30)))
        assertEquals(WeekUnitCount - 1, WidgetRender.currentRow(at(0, 23, 59)))
    }

    // ── 连块的角色 ────────────────────────────────────────────────
    //
    // 角色（single/first/middle/last）决定格子上下要不要留 inset、哪几个角要圆。
    // 算错的后果很具体：跨节的课中间出现一条缝（看起来像两门课），
    // 或者两门相邻的课粘成一整块（看起来像一门课拖了四节）。

    @Test
    fun `周视图连块_单节课是SINGLE`() {
        val data = snapshot(
            s("单节课", weekday = 1, startUnit = 3, endUnit = 3, startTime = "10:20", endTime = "11:00"),
        )
        val grid = WidgetRender.weekGrid(data, at(0, 7, 0))

        assertEquals(WeekCellRole.SINGLE, grid.cell(3, 1).role)
    }

    @Test
    fun `周视图连块_跨两节是FIRST和LAST`() {
        val data = snapshot(
            s("两节连上", weekday = 1, startUnit = 1, endUnit = 2, startTime = "08:30", endTime = "10:00"),
        )
        val grid = WidgetRender.weekGrid(data, at(0, 7, 0))

        assertEquals(WeekCellRole.FIRST, grid.cell(1, 1).role)
        assertEquals(WeekCellRole.LAST, grid.cell(2, 1).role)
    }

    @Test
    fun `周视图连块_跨三节是FIRST_MIDDLE_LAST`() {
        val data = snapshot(
            s("三节连上", weekday = 1, startUnit = 3, endUnit = 5, startTime = "10:20", endTime = "12:30"),
        )
        val grid = WidgetRender.weekGrid(data, at(0, 7, 0))

        assertEquals(WeekCellRole.FIRST, grid.cell(3, 1).role)
        assertEquals(WeekCellRole.MIDDLE, grid.cell(4, 1).role)
        assertEquals(WeekCellRole.LAST, grid.cell(5, 1).role)
        // 同一个颜色，视觉上才连得起来
        assertEquals(grid.cell(3, 1).colorIndex, grid.cell(4, 1).colorIndex)
        assertEquals(grid.cell(3, 1).colorIndex, grid.cell(5, 1).colorIndex)
    }

    @Test
    fun `周视图连块_两门课相邻时各自收口而不是连成一块`() {
        // A 1-2 节、B 3-4 节：第 2 节必须是 LAST（下方留缝），
        // 第 3 节必须是 FIRST（上方留缝），否则四节会被看成一门课
        val data = snapshot(
            s("A", weekday = 1, startUnit = 1, endUnit = 2, startTime = "08:30", endTime = "10:00"),
            s("B", weekday = 1, startUnit = 3, endUnit = 4, startTime = "10:20", endTime = "11:50"),
        )
        val grid = WidgetRender.weekGrid(data, at(0, 7, 0))

        assertEquals(WeekCellRole.LAST, grid.cell(2, 1).role)
        assertEquals(WeekCellRole.FIRST, grid.cell(3, 1).role)
    }

    @Test
    fun `周视图没课的格子colorIndex为负`() {
        val grid = WidgetRender.weekGrid(normalMonday(), at(0, 7, 0))
        assertEquals(-1, grid.cell(6, 1).colorIndex)
    }

    // ── 课程色下标 ────────────────────────────────────────────────

    @Test
    fun `colorIndexFor永远落在0到7且同一门课稳定`() {
        val names = listOf(
            "中药学", "中医诊断学", "外科学(2)", "中医骨伤科学", "就业指导(2)",
            "中医妇科学", "", "A", "很长的课程名称（合班）", "体育", "马克思主义基本原理",
        )
        names.forEach { name ->
            val i = WidgetRender.colorIndexFor(name)
            assertTrue("$name 的下标越界：$i", i in 0 until WeekColorCount)
            assertEquals("同一门课两次算出来必须一样", i, WidgetRender.colorIndexFor(name))
        }
    }

    @Test
    fun `两卡片_只有一节课时只给一张卡`() {
        // 10:40 上完第一节课后只剩一节 → 第二张卡要 INVISIBLE 占位，不能补出空卡
        val data = snapshot(
            s("唯一的一节", weekday = 1, startUnit = 3, endUnit = 4, startTime = "10:20", endTime = "11:50"),
        )
        val cards = WidgetRender.todayCards(data, at(0, 10, 40))
        assertEquals(1, cards.cards.size)
        assertNull(cards.emptyText)
    }

    @Test
    fun `三天的每列最多四节且统计溢出`() {
        val data = snapshot(
            *(1..6).map { i ->
                s("课$i", weekday = 1, startUnit = i, endUnit = i, startTime = "%02d:00".format(7 + i), endTime = "%02d:45".format(7 + i))
            }.toTypedArray(),
        )
        val cols = WidgetRender.threeDays(data, at(0, 7, 0), maxPerDay = 4)

        assertEquals(4, cols[0].courses.size)
        assertEquals(2, cols[0].overflow)
        assertEquals(listOf("课1", "课2", "课3", "课4"), cols[0].courses.map { it.name })
        // 明天 / 后天没课，不该有溢出
        assertEquals(0, cols[1].overflow)
        assertTrue(cols[1].courses.isEmpty())
    }

    // ── 2x1 的行内容 ──────────────────────────────────────────────

    @Test
    fun `2x1_正在上课时给课名和上课时间`() {
        val info = WidgetRender.currentInfo(normalMonday(), at(0, 10, 40))
        assertEquals("中医诊断学", info.name)
        // 时间是**上课**时间，不是下课时间：竖排版式里上面那行就是「几点开始」
        assertEquals("10:20", info.time)
        assertNull(info.emptyText)
        assertEquals(WidgetRender.colorIndexFor("中医诊断学"), info.colorIndex)
    }

    @Test
    fun `2x1_课间给下一节的课名和上课时间`() {
        val info = WidgetRender.currentInfo(normalMonday(), at(0, 10, 5))
        assertEquals("中医诊断学", info.name)
        assertEquals("10:20", info.time)
    }

    @Test
    fun `2x1_没课时只给一句话`() {
        assertEquals("今天没课了", WidgetRender.currentInfo(normalMonday(), at(0, 21, 0)).emptyText)
        assertEquals("打开 App 同步课表", WidgetRender.currentInfo(null, at(0, 10, 0)).emptyText)
        assertEquals("打开 App 同步课表", WidgetRender.currentInfo(snapshot(), at(0, 10, 0)).emptyText)
    }

    @Test
    fun `2x1和currentLine的口径必须一致`() {
        // 两个函数是同一套判断的两份表达，任何一个改了另一个忘了改，
        // 桌面上就会出现「2x1 说没课、2x2 说正在上」这种自相矛盾
        val times = listOf(at(0, 7, 0), at(0, 10, 5), at(0, 10, 40), at(0, 11, 50), at(0, 21, 0))
        times.forEach { now ->
            val line = WidgetRender.currentLine(normalMonday(), now)
            val info = WidgetRender.currentInfo(normalMonday(), now)
            if (info.emptyText != null) {
                assertEquals(line, info.emptyText)
            } else {
                assertTrue("$now 的 2x1 课名必须和 currentLine 指向同一门课：$line / ${info.name}", line.contains(info.name))
                assertTrue("$now 的时间必须是 hh:mm：${info.time}", info.time.matches(Regex("\\d{2}:\\d{2}")))
            }
        }
    }
}
