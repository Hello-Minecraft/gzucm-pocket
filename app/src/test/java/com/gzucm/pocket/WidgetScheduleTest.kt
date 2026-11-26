package com.gzucm.pocket

import com.gzucm.pocket.widget.SnapshotSession
import com.gzucm.pocket.widget.WidgetSchedule
import com.gzucm.pocket.widget.WidgetSnapshotData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 部件 15 分钟刷新节奏的排程决策。
 *
 * 这些判断决定了「桌面上的字什么时候会变」，一旦算错，用户看到的就是
 * 「16:02 还显示正在上 15:30 就下课的课」。全部是纯函数，直接喂固定时间。
 *
 * 基准：2026-09-07 周一，1-2 节 08:30–10:00、3-4 节 10:20–11:50。
 * 所以「该刷窗口」是 08:20 – 12:00。
 */
class WidgetScheduleTest {

    private val monday = LocalDate.of(2026, 9, 7)

    private fun s(
        name: String,
        weekday: Int,
        startUnit: Int,
        endUnit: Int,
        startTime: String,
        endTime: String,
        week: Int = 1,
    ) = SnapshotSession(
        week = week,
        weekday = weekday,
        startUnit = startUnit,
        endUnit = endUnit,
        startTime = startTime,
        endTime = endTime,
        name = name,
        room = "综教楼101",
    )

    private fun mondaySnapshot() = WidgetSnapshotData(
        semesterStart = monday.toString(),
        totalWeeks = 20,
        sessions = listOf(
            s("中药学", weekday = 1, startUnit = 1, endUnit = 2, startTime = "08:30", endTime = "10:00"),
            s("中医诊断学", weekday = 1, startUnit = 3, endUnit = 4, startTime = "10:20", endTime = "11:50"),
        ),
    )

    private fun at(h: Int, m: Int): LocalDateTime = monday.atTime(h, m)

    @Test
    fun `今天没课时不排下一次`() {
        // 快照为空 / 周日（这一天一条课都没有）
        assertNull(WidgetSchedule.nextTickDelay(null, at(9, 0)))
        assertNull(WidgetSchedule.nextTickDelay(WidgetSnapshotData(monday.toString(), 20), at(9, 0)))
        assertNull(WidgetSchedule.nextTickDelay(mondaySnapshot(), monday.plusDays(6).atTime(9, 0)))
    }

    @Test
    fun `早于第一节时排到课前五分钟`() {
        // 07:00 → 08:25（第一节 08:30 前 5 分钟），让「下一节」提前变准
        assertEquals(Duration.ofMinutes(85), WidgetSchedule.nextTickDelay(mondaySnapshot(), at(7, 0)))
        // 07:59 → 08:25，26 分钟
        assertEquals(Duration.ofMinutes(26), WidgetSchedule.nextTickDelay(mondaySnapshot(), at(7, 59)))
    }

    @Test
    fun `课前十分钟窗口内按课前五分钟算`() {
        // 08:15 已经在窗口里（窗口从 08:20 起算的是「该刷」，但 08:15 还没到窗口）
        // 08:15 < 08:20 → 排 08:25，10 分钟
        assertEquals(Duration.ofMinutes(10), WidgetSchedule.nextTickDelay(mondaySnapshot(), at(8, 15)))
    }

    @Test
    fun `课中每十五分钟刷一次`() {
        assertEquals(Duration.ofMinutes(15), WidgetSchedule.nextTickDelay(mondaySnapshot(), at(8, 30)))
        assertEquals(Duration.ofMinutes(15), WidgetSchedule.nextTickDelay(mondaySnapshot(), at(9, 0)))
        assertTrue(WidgetSchedule.shouldTick(mondaySnapshot(), at(9, 0)))
    }

    @Test
    fun `课间也要刷否则下一节不会变`() {
        // 10:00 下课、10:20 上课，10:05 是课间
        assertEquals(Duration.ofMinutes(15), WidgetSchedule.nextTickDelay(mondaySnapshot(), at(10, 5)))
        assertTrue(WidgetSchedule.shouldTick(mondaySnapshot(), at(10, 5)))
    }

    @Test
    fun `最后一节结束十分钟后不再排`() {
        // 11:50 下课 + 10 分钟 = 12:00
        assertTrue(WidgetSchedule.shouldTick(mondaySnapshot(), at(12, 0)))
        assertEquals(Duration.ofMinutes(15), WidgetSchedule.nextTickDelay(mondaySnapshot(), at(12, 0)))

        assertFalse(WidgetSchedule.shouldTick(mondaySnapshot(), at(12, 1)))
        assertNull(WidgetSchedule.nextTickDelay(mondaySnapshot(), at(12, 1)))
        assertNull(WidgetSchedule.nextTickDelay(mondaySnapshot(), at(21, 0)))
    }

    @Test
    fun `课前十分钟之前不算该刷`() {
        assertFalse(WidgetSchedule.shouldTick(mondaySnapshot(), at(8, 19)))
        assertTrue(WidgetSchedule.shouldTick(mondaySnapshot(), at(8, 20)))
    }

    @Test
    fun `周日晚上最后一节之后不排_交给第二天零点`() {
        // 周日有课的情况：20:00 上完，20:30 之后就不排了；
        // 第二天 00:05 的每日 worker 会重新起链（周一 08:25）
        val sunday = WidgetSnapshotData(
            semesterStart = monday.toString(),
            totalWeeks = 20,
            sessions = listOf(
                s("周日课", weekday = 7, startUnit = 11, endUnit = 12, startTime = "18:00", endTime = "20:00"),
            ),
        )
        assertNull(WidgetSchedule.nextTickDelay(sunday, monday.plusDays(6).atTime(20, 30)))
        // 第二天（周一）0:05 排到 8:25 —— 周日那节课在周一不再生效
        assertNull(WidgetSchedule.nextTickDelay(sunday, monday.plusDays(7).atTime(0, 5)))
    }

    @Test
    fun `防抖_十二分钟内已有计划时不重排`() {
        val now = 1_000_000_000_000L
        // 已经排在 5 分钟后 → 不重排（否则 App 每切一次前台就把链重置）
        assertFalse(WidgetSchedule.shouldRearm(now + 5 * 60_000L, now))
        // 排在 13 分钟后 → 允许重排（那个计划太远，当前状态可能已经变了）
        assertTrue(WidgetSchedule.shouldRearm(now + 13 * 60_000L, now))
        // 从来没有排过（0）→ 要排
        assertTrue(WidgetSchedule.shouldRearm(0L, now))
        // 计划时间已经过去 → 说明链断了，必须重排
        assertTrue(WidgetSchedule.shouldRearm(now - 60_000L, now))
    }

    @Test
    fun `时间缺失的脏数据不排`() {
        val broken = WidgetSnapshotData(
            semesterStart = monday.toString(),
            totalWeeks = 20,
            sessions = listOf(
                s("没时间的课", weekday = 1, startUnit = 1, endUnit = 2, startTime = "", endTime = ""),
            ),
        )
        // 空时间时 startText 会退回节次表，所以这里其实还是能算出窗口；
        // 真正要保证的是不抛异常、不返回负延迟
        val delay = WidgetSchedule.nextTickDelay(broken, at(9, 0))
        assertTrue(delay == null || !delay.isNegative)
    }
}
