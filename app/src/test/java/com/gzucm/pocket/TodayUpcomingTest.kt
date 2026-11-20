package com.gzucm.pocket

import com.gzucm.pocket.core.AgendaOccurrence
import com.gzucm.pocket.core.EXAM_COLOR_INDEX
import com.gzucm.pocket.core.TodayLogic
import com.gzucm.pocket.core.TodaySections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 「接下来 N 天」的筛选口径（致辉 2026-11-20 要的「七天内日程在首页提醒出来」）。
 *
 * 三条边界是这里存在的理由：
 *   · **今天的不算**（上面已经有一整段今天的课和日程了）
 *   · **第 N 天当天算**（说七天就是七天，不是六天半）
 *   · 第 N+1 天不算
 */
class TodayUpcomingTest {

    private val today = LocalDate.of(2026, 11, 20) // 周五

    private fun occ(
        date: LocalDate,
        title: String,
        minute: Int,
        exam: Boolean = false,
    ) = AgendaOccurrence(
        key = "k-$title-${date}",
        sourceId = "s-$title",
        title = title,
        date = date,
        week = 8,
        weekday = date.dayOfWeek.value,
        startMinute = minute,
        endMinute = minute + 60,
        place = "",
        note = "",
        colorIndex = if (exam) EXAM_COLOR_INDEX else 0,
        unitStart = 0,
        unitEnd = 0,
        weekly = false,
    )

    @Test
    fun `今天不算 第七天算 第八天不算`() {
        val all = listOf(
            occ(today, "今天的事", 9 * 60),
            occ(today.plusDays(1), "明天的事", 9 * 60),
            occ(today.plusDays(7), "第七天", 9 * 60),
            occ(today.plusDays(8), "第八天", 9 * 60),
        )
        val got = TodayLogic.upcoming(all, today, days = 7).map { it.title }
        assertEquals(listOf("明天的事", "第七天"), got)
    }

    @Test
    fun `按日期和时间排序 同一天里早的在前`() {
        val all = listOf(
            occ(today.plusDays(3), "第三天下午", 15 * 60),
            occ(today.plusDays(1), "明天晚上", 19 * 60),
            occ(today.plusDays(1), "明天早上", 8 * 60),
        )
        val got = TodayLogic.upcoming(all, today, days = 7).map { it.title }
        assertEquals(listOf("明天早上", "明天晚上", "第三天下午"), got)
    }

    @Test
    fun `天数会被夹到 1 到 14`() {
        val all = listOf(
            occ(today.plusDays(1), "明天", 9 * 60),
            occ(today.plusDays(9), "第九天", 9 * 60),
            occ(today.plusDays(15), "第十五天", 9 * 60),
        )
        // days = 0 → 当成 1 天
        assertEquals(listOf("明天"), TodayLogic.upcoming(all, today, 0).map { it.title })
        // days = 99 → 当成 14 天，第十五天仍然不算
        assertEquals(
            listOf("明天", "第九天"),
            TodayLogic.upcoming(all, today, 99).map { it.title },
        )
    }

    @Test
    fun `没有日程时是空表 不抛异常`() {
        assertTrue(TodayLogic.upcoming(emptyList(), today, 7).isEmpty())
    }

    /**
     * 考试和日程一视同仁：用户眼里都是「那天有事」，
     * 所以合并后的列表里考试不能因为来源不同被排到后面。
     */
    @Test
    fun `考试按自己的时间插进同一天`() {
        val all = listOf(
            occ(today.plusDays(2), "日程 晚上", 20 * 60),
            occ(today.plusDays(2), "考试 上午", 9 * 60, exam = true),
        )
        val got = TodayLogic.upcoming(all, today, 7)
        assertEquals(listOf("考试 上午", "日程 晚上"), got.map { it.title })
    }

    @Test
    fun `默认配置是四个开关全开 七天`() {
        val d = TodaySections()
        assertTrue(d.power && d.weather && d.upcoming && d.weekOverview)
        assertEquals(7, d.upcomingDays)
        assertEquals(1, TodaySections.MinDays)
        assertEquals(14, TodaySections.MaxDays)
    }

    @Test
    fun `最多摆五条`() {
        assertEquals(5, TodayLogic.MaxUpcoming)
        val all = (1..9).map { occ(today.plusDays(it.toLong()), "第 $it 天", 9 * 60) }
        assertEquals(9, TodayLogic.upcoming(all, today, 14).size)
        assertEquals(5, TodayLogic.upcoming(all, today, 14).take(TodayLogic.MaxUpcoming).size)
    }
}
