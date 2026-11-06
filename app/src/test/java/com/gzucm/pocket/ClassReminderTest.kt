package com.gzucm.pocket

import com.gzucm.pocket.core.ClassReminder
import com.gzucm.pocket.core.CourseSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 上课提醒的时间计算。
 *
 * 最容易错的是「什么时候该用 10 分钟」——判据是 T-20 分钟那一刻是否还在上课，
 * 而不是「两节课之间有没有间隔」。
 */
class ClassReminderTest {

    private val date = LocalDate.of(2026, 10, 29)

    private fun s(
        name: String,
        start: String,
        end: String,
        weekday: Int = 4,
        startUnit: Int = 1,
        endUnit: Int = 2,
        room: String = "示例楼101",
        teacher: String = "张老师",
    ) = CourseSession(
        name = name, code = "", teachers = teacher, week = 8, weekday = weekday,
        startUnit = startUnit, endUnit = endUnit, startTime = start, endTime = end,
        room = room, category = "",
    )

    private fun hhmm(millis: Long): String =
        LocalTime.ofInstant(java.time.Instant.ofEpochMilli(millis), ZoneId.systemDefault())
            .toString().take(5)

    @Test
    fun `当天第一节课提前20分钟`() {
        val a = s("中医基础理论", "08:00", "09:40")
        val at = ClassReminder.remindAt(a, listOf(a), date)!!
        assertEquals("07:40", hhmm(at))
    }

    @Test
    fun `大课间20分钟仍算上课 走课间档`() {
        // 1-2 节 08:00-09:40，3-4 节 10:00-11:40 → 课间 20 分钟。
        // 按 T-20 判断：09:40 正好是下课时刻，会被判成「没课」，于是 09:40 就提醒，
        // 等于刚下课就响。改成 T-21（09:39 仍在课上）才正确走课间档 → 09:50。
        val a = s("中医基础理论", "08:00", "09:40", startUnit = 1, endUnit = 2)
        val b = s("中医诊断学", "10:00", "11:40", startUnit = 3, endUnit = 4)
        val at = ClassReminder.remindAt(b, listOf(a, b), date)!!
        assertEquals("09:50", hhmm(at))
    }

    @Test
    fun `两档提前量可以自定义`() {
        val morning = s("早八", "08:00", "09:40", startUnit = 1, endUnit = 2)
        val next = s("第二节", "10:00", "11:40", startUnit = 3, endUnit = 4)
        // 没课那档改 30 分钟
        val at1 = ClassReminder.remindAt(morning, listOf(morning, next), date, leadFree = 30)!!
        assertEquals("07:30", hhmm(at1))
        // 课间那档改 15 分钟
        val at2 = ClassReminder.remindAt(next, listOf(morning, next), date, leadBreak = 15)!!
        assertEquals("09:45", hhmm(at2))
    }

    @Test
    fun `leadFor 返回实际使用的那一档`() {
        val morning = s("早八", "08:00", "09:40", startUnit = 1, endUnit = 2)
        val next = s("第二节", "10:00", "11:40", startUnit = 3, endUnit = 4)
        assertEquals(20, ClassReminder.leadFor(morning, listOf(morning, next), date))
        assertEquals(10, ClassReminder.leadFor(next, listOf(morning, next), date))
        assertEquals(25, ClassReminder.leadFor(morning, listOf(morning, next), date, leadFree = 25))
    }

    @Test
    fun `课间只有10分钟时用10分钟提前量`() {
        // 上节 08:00-09:50，下节 10:00 开始。T-20 = 09:40 仍在课上 → 改 09:50
        val a = s("针灸学", "08:00", "09:50", startUnit = 1, endUnit = 2)
        val b = s("推拿学", "10:00", "11:40", startUnit = 3, endUnit = 4)
        val at = ClassReminder.remindAt(b, listOf(a, b), date)!!
        assertEquals("09:50", hhmm(at))
    }

    @Test
    fun `下午第一节课仍是20分钟`() {
        val morning = s("中医基础理论", "08:00", "11:40", startUnit = 1, endUnit = 4)
        val afternoon = s("内经", "14:00", "15:40", startUnit = 5, endUnit = 6)
        val at = ClassReminder.remindAt(afternoon, listOf(morning, afternoon), date)!!
        assertEquals("13:40", hhmm(at))
    }

    @Test
    fun `副标题用中点连接地点与教师`() {
        val a = s("伤寒论", "08:00", "09:40", room = "课室楼203", teacher = "李老师")
        assertEquals("课室楼203·李老师", ClassReminder.subtitle(a))
    }

    @Test
    fun `缺地点或教师时不留下多余的分隔符`() {
        val a = s("伤寒论", "08:00", "09:40", room = "", teacher = "李老师")
        assertEquals("李老师", ClassReminder.subtitle(a))
        val b = s("伤寒论", "08:00", "09:40", room = "课室楼203", teacher = "")
        assertEquals("课室楼203", ClassReminder.subtitle(b))
    }

    @Test
    fun `时间格式异常不崩只返回null`() {
        val bad = s("坏数据", "八点", "九点")
        assertEquals(null, ClassReminder.remindAt(bad, listOf(bad), date))
    }

    @Test
    fun `提醒时刻早于开课时间`() {
        val a = s("温病学", "08:00", "09:40")
        val at = ClassReminder.remindAt(a, listOf(a), date)!!
        val start = ClassReminder.at(date, "08:00")!!
        assertTrue(at < start)
        assertEquals(20L * 60_000L, start - at)
    }

    // ── 调度（2026-11-06：3 天 → 7 天、稳定 requestCode）─────────────────

    /** 2026-10-26 是周一，作为第 1 周 */
    private val sem = com.gzucm.pocket.core.Semester.of(
        code = "202601", name = "测试学期",
        firstMonday = LocalDate.of(2026, 10, 26), totalWeeks = 20,
    )

    private fun millisAt(d: LocalDate, hhmm: String) = ClassReminder.at(d, hhmm)!!

    @Test
    fun `requestCode 在一学期内两两不同`() {
        // 原编码 weekday*1000+startUnit*10+day：周一第1节11号 与 周一第2节1号 都是 1021
        val seen = HashMap<Int, Pair<LocalDate, Int>>()
        var d = LocalDate.of(2026, 9, 1)
        repeat(400) {
            for (unit in 1..14) {
                val c = ClassReminder.requestCode(d, unit)
                val prev = seen.put(c, d to unit)
                assertEquals("撞车：$prev 与 ${d to unit}", null, prev)
            }
            d = d.plusDays(1)
        }
    }

    @Test
    fun `requestCode 恒为非负`() {
        // PendingIntent 的 requestCode 和通知 ID 都会用到它，负数会让通知 ID 计算出错
        var d = LocalDate.of(2020, 1, 1)
        repeat(365 * 30) {
            assertTrue(ClassReminder.requestCode(d, 14) >= 0)
            d = d.plusDays(1)
        }
    }

    @Test
    fun `计划覆盖未来 7 天 不止 3 天`() {
        // 第 1 周每天第 1 节 08:00 都有课
        val sessions = (1..7).map { wd ->
            s("课$wd", "08:00", "09:40", weekday = wd).copy(week = 1)
        }
        val today = LocalDate.of(2026, 10, 26) // 周一
        val now = millisAt(today, "06:00")
        val plans = ClassReminder.plan(sem, sessions, today, now)
        assertEquals(7, plans.size)
        // 第 7 天（周日）那条也在
        assertTrue(plans.any { it.session.weekday == 7 })
    }

    @Test
    fun `已经过了的提醒不排`() {
        val sessions = listOf(
            s("早八", "08:00", "09:40", weekday = 1).copy(week = 1),
            s("下午", "14:00", "15:40", weekday = 1, startUnit = 5, endUnit = 6).copy(week = 1),
        )
        val today = LocalDate.of(2026, 10, 26)
        // 现在 10 点：早八的 07:40 提醒已过，只排下午那条
        val plans = ClassReminder.plan(sem, sessions, today, millisAt(today, "10:00"))
        assertEquals(listOf("下午"), plans.map { it.session.name })
    }

    @Test
    fun `跨周时按每天自己的周次取课`() {
        // 第 1 周周日有课、第 2 周周一有课；今天是第 1 周周日
        val sessions = listOf(
            s("周日课", "08:00", "09:40", weekday = 7).copy(week = 1),
            s("下周一", "08:00", "09:40", weekday = 1).copy(week = 2),
            // 干扰项：第 1 周周一的课不该出现在「下周一」那天
            s("上周一", "08:00", "09:40", weekday = 1).copy(week = 1),
        )
        val sunday = LocalDate.of(2026, 11, 1)
        val plans = ClassReminder.plan(sem, sessions, sunday, millisAt(sunday, "06:00"))
        assertEquals(listOf("周日课", "下周一"), plans.map { it.session.name })
    }

    @Test
    fun `同一天同一节两门课只排一条 不产生重复 code`() {
        val sessions = listOf(
            s("甲", "08:00", "09:40", weekday = 1).copy(week = 1),
            s("乙", "08:00", "09:40", weekday = 1).copy(week = 1),
        )
        val today = LocalDate.of(2026, 10, 26)
        val plans = ClassReminder.plan(sem, sessions, today, millisAt(today, "06:00"))
        assertEquals(1, plans.size)
        assertEquals(plans.size, plans.map { it.code }.toSet().size)
    }

    @Test
    fun `学期外的日期不排`() {
        val sessions = listOf(s("课", "08:00", "09:40", weekday = 1).copy(week = 1))
        val before = LocalDate.of(2026, 10, 1) // 学期开始前
        assertTrue(ClassReminder.plan(sem, sessions, before, millisAt(before, "06:00")).isEmpty())
    }

    @Test
    fun `计划里的提前量与提醒时刻一致`() {
        // 防止 remindAt 与 leadFor 再次分叉：通知写的「N 分钟后」必须等于实际提前量
        val a = s("早八", "08:00", "09:40", weekday = 1, startUnit = 1, endUnit = 2).copy(week = 1)
        val b = s("三四节", "10:00", "11:40", weekday = 1, startUnit = 3, endUnit = 4).copy(week = 1)
        val today = LocalDate.of(2026, 10, 26)
        val plans = ClassReminder.plan(sem, listOf(a, b), today, millisAt(today, "06:00"))
        for (p in plans) {
            val start = millisAt(today, p.session.startTime)
            assertEquals(p.lead * 60_000L, start - p.whenAt)
        }
    }
}
