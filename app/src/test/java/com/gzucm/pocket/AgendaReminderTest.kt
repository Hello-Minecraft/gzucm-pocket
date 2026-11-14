package com.gzucm.pocket

import com.gzucm.pocket.core.Agenda
import com.gzucm.pocket.core.AgendaReminder
import com.gzucm.pocket.core.ClassReminder
import com.gzucm.pocket.core.Semester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 日程提醒的排程计划（纯 JVM，不碰 AlarmManager）。
 *
 * 排程出错的表现是「没响 / 响错时间 / 改成不提醒了还在响」，
 * 这些只有把 plan 拆成纯函数才测得到 —— 和课程提醒同一个思路。
 */
class AgendaReminderTest {

    // 2026-09-07 是周一；11-16 是第 11 周周一
    private val sem = Semester.of("202601", "测试学期", LocalDate.of(2026, 9, 7), 19)
    private val monday = LocalDate.of(2026, 11, 16)

    private fun agenda(
        id: String,
        date: String,
        start: Int = 8 * 60 + 30,
        end: Int = 9 * 60 + 10,
        remind: Int = 10,
        weekly: Boolean = false,
        title: String = "日程$id",
    ) = Agenda(
        id = id,
        title = title,
        date = date,
        startMinute = start,
        endMinute = end,
        remindLeads = if (remind < 0) emptyList() else listOf(remind),
        weekly = weekly,
    )

    private fun epoch(date: LocalDate, minute: Int) = AgendaReminder.epochAt(date, minute)

    @Test
    fun `提前十分钟`() {
        val plans = AgendaReminder.plan(
            listOf(agenda("a", "2026-11-16", remind = 10)),
            sem, monday, epoch(monday, 0),
        )
        assertEquals("应排 1 条", 1, plans.size)
        assertEquals(
            "提醒时刻 = 08:30 - 10 分钟",
            epoch(monday, 8 * 60 + 30) - 10 * 60_000L,
            plans[0].whenAt,
        )
        assertEquals("提前量要一起带出去（通知文案用）", 10, plans[0].lead)
    }

    @Test
    fun `准时就是开始时刻`() {
        val plans = AgendaReminder.plan(
            listOf(agenda("a", "2026-11-16", remind = 0)),
            sem, monday, epoch(monday, 0),
        )
        assertEquals(1, plans.size)
        assertEquals(epoch(monday, 8 * 60 + 30), plans[0].whenAt)
    }

    @Test
    fun `不提醒的规则一条都不排`() {
        val plans = AgendaReminder.plan(
            listOf(agenda("a", "2026-11-16", remind = -1)),
            sem, monday, epoch(monday, 0),
        )
        assertTrue("remind = -1 应该被过滤", plans.isEmpty())
    }

    @Test
    fun `每周重复在未来窗口里各排一条`() {
        // 窗口 8 天：11-16 ~ 11-23，周一的日程应该出现两次
        val plans = AgendaReminder.plan(
            listOf(agenda("a", "2026-11-16", remind = 10, weekly = true)),
            sem, monday, epoch(monday, 0), days = 8,
        )
        assertEquals("每周重复在 8 天窗口内应有 2 条", 2, plans.size)
        assertNotEquals("不同日期的 code 必须不同", plans[0].code, plans[1].code)
    }

    @Test
    fun `提醒时刻已经过去的不排`() {
        // 现在是当天 12:00，08:20 的提醒已经过了
        val plans = AgendaReminder.plan(
            listOf(agenda("a", "2026-11-16", remind = 10)),
            sem, monday, epoch(monday, 12 * 60),
        )
        assertTrue("过了时刻就不该排", plans.isEmpty())
    }

    @Test
    fun `提前一天会把提醒排到前一天`() {
        val tuesday = monday.plusDays(1)
        val plans = AgendaReminder.plan(
            listOf(agenda("a", "2026-11-17", remind = 24 * 60)),
            sem, monday, epoch(monday, 0),
        )
        assertEquals("周二 08:30 的日程，提前一天 = 周一 08:30", 1, plans.size)
        assertEquals(epoch(monday, 8 * 60 + 30), plans[0].whenAt)
        assertTrue("提醒时刻必须早于日程开始", plans[0].whenAt < epoch(tuesday, 8 * 60 + 30))
    }

    @Test
    fun `学期外的规则不排`() {
        val plans = AgendaReminder.plan(
            // 9-07 之前，expand 会直接丢掉
            listOf(agenda("a", "2026-09-01", remind = 10)),
            sem, LocalDate.of(2026, 8, 30), epoch(LocalDate.of(2026, 8, 30), 0),
        )
        assertTrue(plans.isEmpty())
    }

    @Test
    fun `同一天多条日程的 code 互不相同`() {
        val plans = AgendaReminder.plan(
            listOf(
                agenda("a", "2026-11-16", start = 8 * 60 + 30),
                agenda("b", "2026-11-16", start = 10 * 60 + 10),
                agenda("c", "2026-11-16", start = 14 * 60 + 30),
            ),
            sem, monday, epoch(monday, 0),
        )
        assertEquals(3, plans.size)
        assertEquals("同一天 3 条应有 3 个不同 code", 3, plans.map { it.code }.toSet().size)
    }

    @Test
    fun `没有校历时一条都不排`() {
        val plans = AgendaReminder.plan(
            listOf(agenda("a", "2026-11-16", remind = 10)),
            null, monday, epoch(monday, 0),
        )
        assertTrue("没有学期就算不出周次，不能硬排", plans.isEmpty())
    }

    @Test
    fun `日程的 code 段和课程不重叠`() {
        val code = AgendaReminder.requestCode(monday, 5)
        val classCode = ClassReminder.requestCode(monday, 3)
        assertTrue("日程 code 必须明显大于课程 code（误取消是灾难）", code > classCode)
        assertTrue("日程 code 应在 2000 万级", code >= 20_000_000)
        assertTrue("课程 code 应在 2000 万以下", classCode < 20_000_000)
    }

    @Test
    fun `一条日程可以设多个档位各响一次`() {
        val rule = Agenda(
            id = "multi",
            title = "交材料",
            date = monday.toString(),
            startMinute = 10 * 60,
            endMinute = 11 * 60,
            remindLeads = listOf(24 * 60, 30, 0),
        )
        val yesterday = monday.minusDays(1)

        val plans = AgendaReminder.plan(
            listOf(rule), sem, yesterday, epoch(yesterday, 0), days = 3,
        )

        assertEquals("三个档位应该排三个闹钟", 3, plans.size)
        assertEquals("每个闹钟的 code 必须不同，否则会互相覆盖", 3, plans.map { it.code }.toSet().size)
        assertEquals(
            "同一条日程的多个档位共用一格格通知（后一条覆盖前一条）",
            1,
            plans.map { it.notifyId }.toSet().size,
        )
        assertEquals(
            "三个时刻：前一天 10:00、当天 09:30、当天 10:00",
            listOf(
                epoch(yesterday, 10 * 60),
                epoch(monday, 9 * 60 + 30),
                epoch(monday, 10 * 60),
            ),
            plans.map { it.whenAt }.sorted(),
        )
    }

    @Test
    fun `提前量文案`() {
        assertEquals("不提醒", AgendaReminder.label(-1))
        assertEquals("准时", AgendaReminder.label(0))
        assertEquals("提前 10 分钟", AgendaReminder.label(10))
        assertEquals("提前 1 小时", AgendaReminder.label(60))
        assertEquals("提前 1 天", AgendaReminder.label(24 * 60))
    }

    @Test
    fun `多选摘要按由近到远拼，不重复「提前」`() {
        assertEquals("不提醒", AgendaReminder.summary(emptyList()))
        assertEquals("准时", AgendaReminder.summary(listOf(0)))
        assertEquals(
            "乱序传进来也要排好再拼",
            "准时 · 30 分钟 · 1 天",
            AgendaReminder.summary(listOf(24 * 60, 0, 30)),
        )
    }
}
